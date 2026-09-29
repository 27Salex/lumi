package com.antigravity.gemininanotaskmanager.presentation.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Reconocimiento de voz del sistema (SpeechRecognizer) + nivel de audio para la animación.
 *
 * v3.7 — **no corta al hacer una pausa**: el reconocedor de Google da el resultado en cuanto hay ~1 s de silencio
 * (e ignora los extras para alargarlo). Así que al llegar un resultado se guarda y se vuelve a escuchar; solo se
 * envía cuando pasan [pauseMillis] sin que vuelvas a hablar. Lo que dices en varios trozos llega como una frase.
 *
 * @param languageTag idioma BCP-47 del reconocimiento ("es-ES", "en-US"...). Se lee en cada escucha.
 * @param pauseMillis silencio que da por terminada la frase (Ajustes → Voz).
 */
class VoiceSpeechManager(
    private val context: Context,
    private val languageTag: () -> String = { Locale.getDefault().toLanguageTag() },
    private val pauseMillis: () -> Long = { 1_500L }
) {

    companion object {
        private const val TAG = "VoiceSpeechManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _rmsAmplitude = MutableStateFlow(0f)
    val rmsAmplitude: StateFlow<Float> = _rmsAmplitude.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    /** Lo reconocido hasta ahora en esta escucha (varios trozos). */
    private var buffer = ""
    /** Tras un trozo, esperando a ver si sigues hablando. */
    private var awaitingMore = false
    private var session = 0
    private var finalCallback: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param quiet escucha de continuación de la conversación: si no dices nada se cierra en silencio
     *        (llama a [onSilence]) en vez de mostrar «No te he oído».
     */
    fun startListening(
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        quiet: Boolean = false,
        onSilence: () -> Unit = {}
    ) {
        if (!isAvailable()) {
            onError("El reconocimiento de voz no está habilitado en este dispositivo.")
            return
        }
        stopListening()
        buffer = ""
        awaitingMore = false
        finalCallback = onFinalResult
        val mySession = ++session

        fun finish() {
            if (mySession != session) return
            handler.removeCallbacksAndMessages(null)
            val text = buffer.trim()
            stopListening()
            if (text.isNotBlank()) onFinalResult(text) else if (quiet) onSilence() else onError("No se detectó texto en la orden.")
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        // Sigues hablando: no se envía todavía
                        handler.removeCallbacksAndMessages(null)
                        awaitingMore = false
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        _rmsAmplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _rmsAmplitude.value = 0f
                    }

                    override fun onError(error: Int) {
                        if (mySession != session) return
                        _rmsAmplitude.value = 0f
                        // Esperando más y no dijo nada (o no se entendió) → se envía lo que ya había
                        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        if (buffer.isNotBlank() && (awaitingMore || silence || error == SpeechRecognizer.ERROR_CLIENT)) { finish(); return }
                        if (quiet && silence) { stopListening(); onSilence(); return }
                        stopListening()
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Error de grabación de audio."
                            SpeechRecognizer.ERROR_NO_MATCH -> "No te he entendido."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El micrófono está ocupado por otra app."
                            SpeechRecognizer.ERROR_CLIENT -> "El reconocimiento de voz se interrumpió."
                            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                "El servicio de voz de Google no responde; prueba con conexión o instala el idioma sin conexión."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Sin conexión suficiente para la voz. Descarga «Español» sin conexión en Ajustes → Idioma → Reconocimiento de voz."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso de micrófono (RECORD_AUDIO)."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                "El idioma de voz no está disponible. Descarga el paquete de voz en Ajustes del sistema → Idioma → Reconocimiento de voz."
                            else -> "No se pudo capturar audio (código $error)."
                        }
                        Log.w(TAG, "SpeechRecognizer error: $msg")
                        onError(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        if (mySession != session) return
                        _rmsAmplitude.value = 0f
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                        if (text.isNotBlank()) buffer = (buffer + " " + text).trim()
                        _liveTranscript.value = buffer
                        if (buffer.isBlank()) { finish(); return }
                        // Trozo recibido: se vuelve a escuchar un momento por si sigues hablando
                        awaitingMore = true
                        runCatching { startListening(intent()) }.onFailure { finish(); return }
                        handler.postDelayed({ if (awaitingMore) finish() }, pauseMillis())
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (partial.isNotBlank()) {
                            awaitingMore = false
                            handler.removeCallbacksAndMessages(null)
                            _liveTranscript.value = (buffer + " " + partial).trim()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            _liveTranscript.value = ""
            speechRecognizer?.startListening(intent())
            _isListening.value = true
        } catch (e: Exception) {
            _isListening.value = false
            _rmsAmplitude.value = 0f
            onError("Error al iniciar el micrófono: ${e.localizedMessage}")
        }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        val lang = languageTag()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        // Algunos reconocedores (Google/Samsung) ignoran EXTRA_LANGUAGE sin la preferencia
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Pistas de silencio (muchos reconocedores las ignoran; por eso existe la escucha encadenada)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMillis())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMillis())
    }

    /** Terminar ya (botón de parar): si ya había texto, se envía. */
    fun finishNow() {
        val text = buffer.trim()
        val cb = finalCallback
        stopListening()
        if (text.isNotBlank()) cb?.invoke(text)
    }

    fun stopListening() {
        session++
        handler.removeCallbacksAndMessages(null)
        awaitingMore = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error al detener SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
            _isListening.value = false
            _rmsAmplitude.value = 0f
        }
    }
}
