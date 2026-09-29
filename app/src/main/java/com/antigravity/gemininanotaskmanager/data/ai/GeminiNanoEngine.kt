package com.antigravity.gemininanotaskmanager.data.ai

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
 * Gemini Nano on-device vía AICore (ML Kit GenAI Prompt API).
 * Limitaciones (ver MEMORY.md):
 * - Solo dispositivos con Prompt API (Galaxy S26, Pixel 9+, Z Fold7…). En Galaxy S25 → UNAVAILABLE.
 * - AICore solo permite inferencia con la app en primer plano (Activity visible), nunca desde un Service.
 */
class GeminiNanoEngine : LlmEngine() {

    override val displayName = "Gemini Nano · AICore"

    enum class Status { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val model: GenerativeModel? by lazy {
        runCatching { Generation.getClient() }.onFailure { Log.w(TAG, "No se pudo crear el cliente: ${it.message}") }.getOrNull()
    }
    private var systemPromptSupported: Boolean? = null

    suspend fun refreshStatus(): Status {
        val m = model ?: return Status.UNAVAILABLE.also { _status.value = it }
        val s = runCatching { m.checkStatus() }.getOrElse {
            Log.w(TAG, "checkStatus falló: ${it.message}")
            FeatureStatus.UNAVAILABLE
        }
        return when (s) {
            FeatureStatus.AVAILABLE -> Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
            else -> Status.UNAVAILABLE
        }.also { _status.value = it }
    }

    /** Pide a AICore que descargue Gemini Nano (solo si el dispositivo lo soporta). */
    suspend fun download() {
        val m = model ?: return
        _status.value = Status.DOWNLOADING
        runCatching { m.download().collect { } }.onFailure { Log.w(TAG, "Descarga de Nano falló: ${it.message}") }
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
