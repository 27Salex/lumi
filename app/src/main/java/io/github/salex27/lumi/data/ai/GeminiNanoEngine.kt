package io.github.salex27.lumi.data.ai

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gemini Nano on-device through AICore (ML Kit GenAI Prompt API).
 * Limitations:
 * - Only devices with the Prompt API (Galaxy S26, Pixel 9+, Z Fold7…). On a Galaxy S25 → UNAVAILABLE.
 * - AICore only runs inference with the app in the foreground (a visible Activity), never from a Service.
 */
class GeminiNanoEngine : LlmEngine() {

    override val displayName = "Gemini Nano · AICore"

    enum class Status { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val model: GenerativeModel? by lazy {
        runCatching { Generation.getClient() }.onFailure { Log.w(TAG, "Could not create the client: ${it.message}") }.getOrNull()
    }
    private var systemPromptSupported: Boolean? = null

    suspend fun refreshStatus(): Status {
        val m = model ?: return Status.UNAVAILABLE.also { _status.value = it }
        val s = runCatching { m.checkStatus() }.getOrElse {
            Log.w(TAG, "checkStatus failed: ${it.message}")
            FeatureStatus.UNAVAILABLE
        }
        return when (s) {
            FeatureStatus.AVAILABLE -> Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
            else -> Status.UNAVAILABLE
        }.also { _status.value = it }
    }

    /** Asks AICore to download Gemini Nano (only if the device supports it). */
    suspend fun download() {
        val m = model ?: return
        _status.value = Status.DOWNLOADING
        runCatching { m.download().collect { } }.onFailure { Log.w(TAG, "Nano download failed: ${it.message}") }
        refreshStatus()
    }

    override suspend fun isAvailable(): Boolean =
        (if (_status.value == Status.UNKNOWN) refreshStatus() else _status.value) == Status.AVAILABLE

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? {
        val m = model ?: return null
        val useSystem = systemPromptSupported ?: runCatching { m.isSystemPromptAvailable() }.getOrDefault(false)
            .also { systemPromptSupported = it }

        val request = if (useSystem) {
            generateContentRequest(SystemInstruction(system), TextPart(user)) {
                this.temperature = temperature
                maxOutputTokens = maxTokens
            }
        } else {
            generateContentRequest(TextPart("$system\n\n$user")) {
                this.temperature = temperature
                maxOutputTokens = maxTokens
            }
        }
        return m.generateContent(request).candidates.firstOrNull()?.text
    }

    private companion object {
        const val TAG = "GeminiNanoEngine"
    }
}
