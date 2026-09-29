package com.antigravity.gemininanotaskmanager.service.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.ZipInputStream

/**
 * Modelo pequeño de Vosk en español (~40 MB, Apache 2.0) para detectar «Oye Lumi» sin internet.
 *
 * La descarga vive en el scope de la aplicación (no en el de una pantalla): si Android cierra la Activity
 * — p.ej. al abrir el permiso «Mostrar sobre otras apps» — la descarga sigue. Es reanudable (HTTP Range sobre
 * un fichero .part) y reintenta con espera creciente, pensado para datos móviles inestables (v3.0.1).
 */
class VoskModelManager(private val context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float, val attempt: Int = 1) : State
        data object Installing : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    /** Modelo que se descarga: reconocimiento (40 MB) + huella de voz para «Entrenar mi voz» (13 MB). */
    private data class Artifact(val url: String, val dirName: String, val expectedBytes: Long, val marker: String)

    private val artifacts = listOf(
        Artifact(MODEL_URL, "vosk-model", EXPECTED_BYTES, "am"),
        Artifact(SPK_URL, "vosk-spk", SPK_BYTES, "final.ext.raw")
    )

    private fun dir(a: Artifact) = File(context.filesDir, a.dirName)
    private fun part(a: Artifact) = File(context.filesDir, "${a.dirName}.zip.part")
    private fun isInstalled(a: Artifact) = File(dir(a), a.marker).exists()

    val modelPath: String get() = dir(artifacts[0]).absolutePath
    val speakerModelPath: String get() = dir(artifacts[1]).absolutePath

    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Deferred<Boolean>? = null

    fun isReady() = artifacts.all(::isInstalled)

    /** Inicia (o se une a) la descarga. Devuelve true cuando el modelo está listo. */
    suspend fun download(): Boolean {
        if (isReady()) { _state.value = State.Ready; return true }
        val running = job?.takeIf { it.isActive } ?: scope.async(Dispatchers.IO) { downloadWithRetries() }.also { job = it }
        return running.await()
    }

    private suspend fun downloadWithRetries(): Boolean {
        val pending = artifacts.filterNot(::isInstalled)
        val totalBytes = pending.sumOf { it.expectedBytes }
        var doneBefore = 0L
        for (a in pending) {
            if (!downloadArtifact(a, doneBefore, totalBytes)) return false
            doneBefore += a.expectedBytes
        }
        return if (isReady()) { _state.value = State.Ready; true }
        else { _state.value = State.Failed("El modelo descargado no es válido"); false }
    }

    private suspend fun downloadArtifact(a: Artifact, doneBefore: Long, totalBytes: Long): Boolean {
        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                fetch(a, attempt, doneBefore, totalBytes)
                _state.value = State.Installing
                unzip(part(a), dir(a))
                part(a).delete()
                return true
            } catch (e: CancellationException) {
                throw e // no es un fallo de red: no se convierte en error
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Intento $attempt de descarga falló: ${e.message}")
                if (e is BadZip) part(a).delete() // corrupto → empezar de cero
                if (attempt < MAX_ATTEMPTS) delay(2_000L * attempt)
            }
        }
        _state.value = State.Failed(explain(lastError))
        return false
    }

    /** Descarga reanudando desde lo que ya haya en el .part. El progreso cuenta todos los ficheros. */
    private fun fetch(a: Artifact, attempt: Int, doneBefore: Long, totalBytes: Long) {
        val partFile = part(a)
        val already = partFile.takeIf { it.exists() }?.length() ?: 0L
        val conn = (URL(a.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resumed) throw IOException("El servidor respondió $code")
            val start = if (resumed) already else 0L
            val total = start + (conn.contentLengthLong.takeIf { it > 0 } ?: (a.expectedBytes - start))

            RandomAccessFile(partFile, "rw").use { out ->
                out.setLength(start) // si el servidor no admite Range, se reescribe desde el principio
                out.seek(start)
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastEmit > 256 * 1024) {
                            _state.value = State.Downloading(((doneBefore + done).toFloat() / totalBytes).coerceAtMost(0.99f), attempt)
                            lastEmit = done
                        }
                    }
                    if (done < total) throw IOException("Descarga incompleta ($done de $total bytes)")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** El zip trae una carpeta raíz (vosk-model-small-es-0.42/…): se aplana en [target]. */
    private fun unzip(zip: File, target: File) {
        val tmp = File(context.filesDir, "${target.name}.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isBlank()) continue
                    val out = File(tmp, relative)
                    // Protección contra "zip slip"
                    require(out.canonicalPath.startsWith(tmp.canonicalPath)) { "Entrada de zip no válida" }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw BadZip(e)
        }
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("No se pudo instalar el modelo")
    }

    private fun explain(e: Exception?): String = when (e) {
        is UnknownHostException -> "Sin conexión a internet"
        is SocketTimeoutException -> "La conexión es muy lenta; inténtalo con Wi-Fi"
        is BadZip -> "El archivo llegó dañado; vuelve a intentarlo"
        null -> "Error desconocido"
        else -> e.message ?: e.javaClass.simpleName
    }

    private class BadZip(cause: Exception) : IOException("Zip dañado: ${cause.message}", cause)

    companion object {
        private const val TAG = "VoskModelManager"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        private const val EXPECTED_BYTES = 39_817_833L
        private const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
        private const val SPK_BYTES = 13_869_103L
        private const val MAX_ATTEMPTS = 4
    }
}
