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
 * Gemma on-device con LiteRT-LM. Funciona en cualquier móvil con ~6 GB de RAM (incl. Galaxy S25),
 * sin depender de AICore. El motor se carga una vez (hasta ~10 s) y se reutiliza.
 *
 * Lecciones aprendidas (v2.0 caía siempre a Gemini cloud, ver MEMORY.md):
 * - `ThinkingConfig()` por defecto = razonamiento ACTIVADO y sin límite → respuestas eternas. Se desactiva.
 * - Sin `maxOutputToken` el modelo puede generar hasta su máximo. Se limita siempre.
 * - La llamada nativa no se interrumpe con un timeout de corrutina: hay que llamar a
 *   `cancelProcess()`; si no, sigue generando con el mutex tomado y bloquea todas las peticiones siguientes.
 */
class GemmaLocalEngine(
    private val context: Context,
    private val modelManager: GemmaModelManager,
    private val settings: SettingsRepository
) : LlmEngine() {

    override val displayName = "Gemma 4 · on-device"

    enum class LoadState { IDLE, LOADING, READY, ERROR }

    /** Datos para la pantalla de diagnóstico de Ajustes. */
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

    /** Si la GPU cargó pero falló al generar (p.ej. falta OpenCL), se usa CPU a partir de entonces. */
    private val prefs = context.getSharedPreferences("gemma_engine", Context.MODE_PRIVATE)
    private var forceCpu: Boolean
        get() = prefs.getBoolean(K_FORCE_CPU, false)
        set(v) { prefs.edit().putBoolean(K_FORCE_CPU, v).apply() }

    /**
     * «Probando la GPU»: se marca ANTES de usarla y se borra cuando ya ha generado una respuesta. Si la app muere a
     * mitad (un fallo nativo de la GPU no se puede capturar: en el emulador WebGPU tira la app con SIGSEGV), al
     * volver a abrirla la marca sigue ahí → se usa CPU directamente en vez de caer en bucle.
     */
    private var gpuTrialPending: Boolean
        get() = prefs.getBoolean(K_GPU_TRIAL, false)
        set(v) { prefs.edit().putBoolean(K_GPU_TRIAL, v).commit() } // commit: tiene que estar en disco antes del posible fallo

    init {
        if (gpuTrialPending) {
            Log.w(TAG, "La app se cerró probando la GPU: Gemma usará CPU")
            forceCpu = true
            gpuTrialPending = false
        }
    }

    /** El emulador no tiene GPU real (su WebGPU tira la app al cargar Gemma). */
    private val isEmulator: Boolean = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish") ||
        android.os.Build.FINGERPRINT.startsWith("generic") || android.os.Build.PRODUCT.contains("sdk")

    override suspend fun isAvailable(): Boolean =
        settings.current.gemmaEnabled && modelManager.state.value == GemmaModelManager.State.Ready &&
            _loadState.value != LoadState.ERROR

    /** Carga el modelo en memoria en segundo plano para que la primera respuesta sea rápida. */
    suspend fun warmUp() {
        if (isAvailable()) withContext(Dispatchers.Default) { mutex.withLock { ensureEngine() } }
    }

    fun release() {
        engine?.close()
        engine = null
        engineBackend = null
        _loadState.value = LoadState.IDLE
    }

    /** Vuelve a intentar la GPU (p.ej. tras una actualización que arregle el problema). */
    fun retryGpu() {
        forceCpu = false
        release()
    }

    /** Prueba manual desde Ajustes: carga (si hace falta), genera una frase corta y mide tiempos. */
    suspend fun selfTest() {
        if (_loadState.value == LoadState.ERROR) _loadState.value = LoadState.IDLE // permitir reintentar
        _diagnostics.update { it.copy(selfTestRunning = true, selfTestResult = null) }
        val start = SystemClock.elapsedRealtime()
        val result = runCatching {
            complete("Responde en español con una sola frase corta.", "Saluda al usuario.", 40, 0.3f)
        }
        val ms = SystemClock.elapsedRealtime() - start
        _diagnostics.update {
            it.copy(
                selfTestRunning = false,
                selfTestResult = result.fold(
                    onSuccess = { text -> if (text.isNullOrBlank()) "Sin respuesta (${ms} ms)" else "«${text.trim()}» en ${ms} ms" },
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
                    // La GPU se inicializó pero no puede generar → CPU (más lenta, pero sigue siendo local)
                    Log.w(TAG, "Gemma falló en GPU (${t.message}); se cambia a CPU")
                    forceCpu = true
                    release()
                    generate(system, user, maxTokens, temperature)
                }
            }
        }

    /** Debe llamarse con el mutex tomado. */
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
                // Timeout del orquestador: parar la generación nativa para liberar el mutex YA
                runCatching { conversation.cancelProcess() }
                _diagnostics.update { it.copy(lastError = "Cancelado tras ${SystemClock.elapsedRealtime() - start} ms (timeout)") }
                throw c
            } catch (t: Throwable) {
                _diagnostics.update { it.copy(lastError = t.message ?: t.javaClass.simpleName) }
                throw t
            }
        }
    }

    /** Tolera tanto fragmentos incrementales como acumulados. */
    private fun appendChunk(sb: StringBuilder, chunk: Message) {
        val piece = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        if (piece.isEmpty()) return
        if (sb.isNotEmpty() && piece.length >= sb.length && piece.startsWith(sb)) {
            sb.setLength(0) // el runtime envió el texto acumulado
        }
        sb.append(piece)
    }

    /** Debe llamarse con el mutex tomado. Intenta GPU y, si falla, CPU. */
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
                Log.i(TAG, "Gemma cargado con backend ${backend.name} en $ms ms")
                engine = e
                engineBackend = backend.name
                _loadState.value = LoadState.READY
                _diagnostics.update { it.copy(backend = backend.name, loadMillis = ms, lastError = errors.joinToString("; ").ifBlank { null }) }
                return e
            } catch (t: Throwable) {
                if (backend.name == "GPU") gpuTrialPending = false
                Log.w(TAG, "No se pudo cargar Gemma con ${backend.name}: ${t.message}")
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
