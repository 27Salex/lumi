package io.github.salex27.lumi.data.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads and locates the Gemma model for LiteRT-LM.
 * Uses the system DownloadManager: it keeps downloading if the app is closed, retries on network drops and shows its
 * own progress notification.
 */
class GemmaModelManager(private val context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State {
            val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
        }
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private val prefs = context.getSharedPreferences("gemma_model", Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val modelsDir: File get() = File(context.getExternalFilesDir(null), MODELS_DIR).apply { mkdirs() }

    val modelFile: File get() = File(modelsDir, MODEL_FILE)
    private val partialFile: File get() = File(modelsDir, "$MODEL_FILE.part")

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        when {
            modelFile.exists() && modelFile.length() > MIN_VALID_BYTES -> _state.value = State.Ready
            prefs.getLong(K_DOWNLOAD_ID, -1L) >= 0 -> startPolling() // download in progress from a previous session
        }
    }

    fun startDownload() {
        if (_state.value is State.Downloading || _state.value == State.Ready) return
        partialFile.delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Cerebro local Gemma", "Gemma on-device brain"))
            .setDescription(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Descargando modelo de IA on-device (~2,6 GB)", "Downloading the on-device AI model (~2.6 GB)"))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(partialFile))
            .setAllowedOverMetered(false) // Wi-Fi only: it is 2.6 GB
            .setAllowedOverRoaming(false)
        val id = downloadManager.enqueue(request)
        prefs.edit().putLong(K_DOWNLOAD_ID, id).apply()
        _state.value = State.Downloading(0, EXPECTED_BYTES)
        startPolling()
    }

    fun cancelDownload() {
        val id = prefs.getLong(K_DOWNLOAD_ID, -1L)
        if (id >= 0) downloadManager.remove(id)
        prefs.edit().remove(K_DOWNLOAD_ID).apply()
        pollJob?.cancel()
        partialFile.delete()
        _state.value = State.NotDownloaded
    }

    fun deleteModel() {
        cancelDownload()
        modelFile.delete()
        _state.value = State.NotDownloaded
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val id = prefs.getLong(K_DOWNLOAD_ID, -1L)
                if (id < 0) break
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    finishWithFailure(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("La descarga se canceló", "The download was cancelled"))
                    break
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                cursor.close()

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove(K_DOWNLOAD_ID).apply()
                        if (partialFile.renameTo(modelFile)) {
                            _state.value = State.Ready
                        } else {
                            finishWithFailure(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("No se pudo guardar el modelo", "Could not save the model"))
                        }
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        finishWithFailure(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Error de descarga", "Download error") + " ($reason)")
                        break
                    }
                    else -> _state.value = State.Downloading(done, if (total > 0) total else EXPECTED_BYTES)
                }
                delay(700)
            }
        }
    }

    private fun finishWithFailure(reason: String) {
        Log.w(TAG, reason)
        prefs.edit().remove(K_DOWNLOAD_ID).apply()
        partialFile.delete()
        _state.value = State.Failed(reason)
    }

    companion object {
        private const val TAG = "GemmaModelManager"
        private const val K_DOWNLOAD_ID = "download_id"
        private const val MODELS_DIR = "models"

        // Gemma 4 E2B (Apache 2.0, no gating) — universal CPU/GPU build.
        // There is an NPU variant for Snapdragon 8 Elite (…_qualcomm_sm8750.litertlm).
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE"
        const val EXPECTED_BYTES = 2_588_147_712L
        private const val MIN_VALID_BYTES = 1_000_000_000L
    }
}
