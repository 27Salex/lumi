package io.github.salex27.lumi.data.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.salex27.lumi.data.settings.SettingsRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gemma on-device with LiteRT-LM. Works on any phone with ~6 GB of RAM (Galaxy S25 included) without AICore.
 * The engine is loaded once (up to ~10 s) and reused.
 *
 * Lessons learned (early versions always fell back to Gemini cloud):
 * - The default `ThinkingConfig()` = reasoning ON with no limit → endless answers. It is turned off.
 * - Without `maxOutputToken` the model may generate up to its maximum. It is always limited.
 * - The native call isn't interrupted by a coroutine timeout: `cancelProcess()` must be called; otherwise it keeps
 *   generating while holding the mutex and blocks every following request.
 */
class GemmaLocalEngine(
    private val context: Context,
    private val modelManager: GemmaModelManager,
    private val settings: SettingsRepository
) : LlmEngine() {

    override val displayName = "Gemma 4 · on-device"

    enum class LoadState { IDLE, LOADING, READY, ERROR }

    /** Data for the diagnostics screen in Settings. */
    data class Diagnostics(
        val backend: String? = null,
        val loadMillis: Long? = null,
        val lastLatencyMillis: Long? = null,
        val lastOutputChars: Int? = null,
        val lastError: String? = null,
        val selfTestRunning: Boolean = false,
        val selfTestResult: String? = null
    )

    private val _loadState = MutableStateFlow(LoadState.IDLE)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engineBackend: String? = null

    /** If the GPU loaded but failed to generate (e.g. OpenCL missing), CPU is used from then on. */
    private val prefs = context.getSharedPreferences("gemma_engine", Context.MODE_PRIVATE)
    private var forceCpu: Boolean
        get() = prefs.getBoolean(K_FORCE_CPU, false)
        set(v) { prefs.edit().putBoolean(K_FORCE_CPU, v).apply() }

    /**
     * "Trying the GPU": set BEFORE using it and cleared once it has produced an answer. If the app dies halfway (a native
     * GPU crash can't be caught: on the emulator WebGPU kills the app with SIGSEGV), the flag is still there on the
     * next launch → CPU is used directly instead of crashing in a loop.
     */
    private var gpuTrialPending: Boolean
        get() = prefs.getBoolean(K_GPU_TRIAL, false)
        set(v) { prefs.edit().putBoolean(K_GPU_TRIAL, v).commit() } // commit: it must be on disk before a possible crash

    init {
        if (gpuTrialPending) {
            Log.w(TAG, "The app died while trying the GPU: Gemma will use the CPU")
            forceCpu = true
            gpuTrialPending = false
        }
    }

    /** The emulator has no real GPU (its WebGPU kills the app when loading Gemma). */
    private val isEmulator: Boolean = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish") ||
        android.os.Build.FINGERPRINT.startsWith("generic") || android.os.Build.PRODUCT.contains("sdk")

    override suspend fun isAvailable(): Boolean =
        settings.current.gemmaEnabled && modelManager.state.value == GemmaModelManager.State.Ready &&
            _loadState.value != LoadState.ERROR

    /** Loads the model into memory in the background so the first answer is fast. */
    suspend fun warmUp() {
        if (isAvailable()) withContext(Dispatchers.Default) { mutex.withLock { ensureEngine() } }
    }

    fun release() {
        engine?.close()
        engine = null
        engineBackend = null
        _loadState.value = LoadState.IDLE
    }

    /** Tries the GPU again (e.g. after an update that fixes the problem). */
    fun retryGpu() {
        forceCpu = false
        release()
    }

    /** Manual test from Settings: loads (if needed), generates a short sentence and measures times. */
    suspend fun selfTest() {
        if (_loadState.value == LoadState.ERROR) _loadState.value = LoadState.IDLE // allow a retry
        _diagnostics.update { it.copy(selfTestRunning = true, selfTestResult = null) }
        val start = SystemClock.elapsedRealtime()
        val result = runCatching {
            complete(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Responde en español con una sola frase corta.", "Reply in English with one short sentence."), io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Saluda al usuario.", "Greet the user."), 40, 0.3f)
        }
        val ms = SystemClock.elapsedRealtime() - start
        _diagnostics.update {
            it.copy(
                selfTestRunning = false,
                selfTestResult = result.fold(
                    onSuccess = { text -> if (text.isNullOrBlank()) io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Sin respuesta", "No answer") + " (${ms} ms)" else "«${text.trim()}» " + io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("en", "in") + " ${ms} ms" },
                    onFailure = { e -> "Error: ${e.message}" }
                )
            )
        }
    }

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                try {
                    generate(system, user, maxTokens, temperature).also { if (engineBackend == "GPU" && gpuTrialPending) gpuTrialPending = false }
                } catch (t: Throwable) {
                    if (t is CancellationException || engineBackend != "GPU") throw t
                    // The GPU initialized but can't generate → CPU (slower, but still local)
                    Log.w(TAG, "Gemma failed on the GPU (${t.message}); switching to CPU")
                    forceCpu = true
                    release()
                    generate(system, user, maxTokens, temperature)
                }
            }
        }

    /** Must be called holding the mutex. */
    private suspend fun generate(system: String, user: String, maxTokens: Int, temperature: Float): String? {
        val e = ensureEngine() ?: return null
        val start = SystemClock.elapsedRealtime()
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.toDouble()),
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
        ).use { conversation ->
            try {
                val sb = StringBuilder()
                conversation.sendMessageAsync(user).collect { chunk -> appendChunk(sb, chunk) }
                val text = sb.toString()
                _diagnostics.update {
                    it.copy(lastLatencyMillis = SystemClock.elapsedRealtime() - start, lastOutputChars = text.length, lastError = null)
                }
                text
            } catch (c: CancellationException) {
                // Orchestrator timeout: stop the native generation to release the mutex NOW
                runCatching { conversation.cancelProcess() }
                _diagnostics.update { it.copy(lastError = "Cancelled after ${SystemClock.elapsedRealtime() - start} ms (timeout)") }
                throw c
            } catch (t: Throwable) {
                _diagnostics.update { it.copy(lastError = t.message ?: t.javaClass.simpleName) }
                throw t
            }
        }
    }

    /** Accepts both incremental and accumulated fragments. */
    private fun appendChunk(sb: StringBuilder, chunk: Message) {
        val piece = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        if (piece.isEmpty()) return
        if (sb.isNotEmpty() && piece.length >= sb.length && piece.startsWith(sb)) {
            sb.setLength(0) // the runtime sent the accumulated text
        }
        sb.append(piece)
    }

    /** Must be called holding the mutex. Tries the GPU and, if it fails, the CPU. */
    private fun ensureEngine(): Engine? {
        engine?.let { return it }
        if (_loadState.value == LoadState.ERROR) return null
        val path = modelManager.modelFile.absolutePath
        _loadState.value = LoadState.LOADING
        val errors = mutableListOf<String>()
        val backends = if (forceCpu || isEmulator) listOf<Backend>(Backend.CPU()) else listOf(Backend.GPU(), Backend.CPU())
        for (backend in backends) {
            val start = SystemClock.elapsedRealtime()
            if (backend.name == "GPU") gpuTrialPending = true
            try {
                val e = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = context.cacheDir.absolutePath))
                e.initialize()
                val ms = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "Gemma loaded with the ${backend.name} backend in $ms ms")
                engine = e
                engineBackend = backend.name
                _loadState.value = LoadState.READY
                _diagnostics.update { it.copy(backend = backend.name, loadMillis = ms, lastError = errors.joinToString("; ").ifBlank { null }) }
                return e
            } catch (t: Throwable) {
                if (backend.name == "GPU") gpuTrialPending = false
                Log.w(TAG, "Could not load Gemma with ${backend.name}: ${t.message}")
                errors += "${backend.name}: ${t.message}"
            }
        }
        _loadState.value = LoadState.ERROR
        _diagnostics.update { it.copy(lastError = errors.joinToString("; ")) }
        return null
    }

    private companion object {
        const val TAG = "GemmaLocalEngine"
        const val K_FORCE_CPU = "force_cpu"
        const val K_GPU_TRIAL = "gpu_trial_pending"
    }
}
