package io.github.salex27.lumi.presentation.ai

import io.github.salex27.lumi.domain.assistant.ReplyLanguage

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
 * System speech recognition (SpeechRecognizer) + audio level for the animation.
 *
 * v3.7 — **doesn't cut off on a pause**: Google's recognizer returns the result after ~1 s of silence (and ignores the
 * extras that should lengthen it). So when a result arrives it is kept and listening starts again; it is only sent
 * once [pauseMillis] pass without you talking again. What you say in several chunks arrives as one sentence.
 *
 * @param languageTag BCP-47 recognition language ("es-ES", "en-US"...). Read on every listening.
 * @param pauseMillis silence that ends the sentence (Settings → Voice).
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

    /** What has been recognized so far in this listening (several chunks). */
    private var buffer = ""
    /** After a chunk, waiting to see if you keep talking. */
    private var awaitingMore = false
    private var session = 0
    private var finalCallback: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param quiet follow-up listening in a conversation: if you say nothing it closes silently (calls [onSilence])
     *        instead of showing "I didn't hear you".
     */
    fun startListening(
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        quiet: Boolean = false,
        onSilence: () -> Unit = {},
        /** Called once per listening, when the microphone is really open (the moment to start talking). */
        onReady: () -> Unit = {}
    ) {
        if (!isAvailable()) {
            onError(ReplyLanguage.ui("El reconocimiento de voz no está habilitado en este dispositivo.", "Speech recognition isn't enabled on this device."))
            return
        }
        stopListening()
        buffer = ""
        awaitingMore = false
        finalCallback = onFinalResult
        val mySession = ++session
        Log.i(TAG, "Start listening (session $mySession, ${languageTag()}, quiet=$quiet)")
        var readyNotified = false

        fun finish() {
            if (mySession != session) return
            handler.removeCallbacksAndMessages(null)
            val text = buffer.trim()
            stopListening()
            if (text.isNotBlank()) onFinalResult(text) else if (quiet) onSilence() else onError(ReplyLanguage.ui("No se detectó texto en la orden.", "No text was detected."))
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                        if (!readyNotified && mySession == session) { readyNotified = true; onReady() }
                    }

                    override fun onBeginningOfSpeech() {
                        // Still talking: not sent yet
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
                        // Waiting for more and nothing was said (or understood) → send what was already there
                        val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        if (buffer.isNotBlank() && (awaitingMore || silence || error == SpeechRecognizer.ERROR_CLIENT)) { finish(); return }
                        // Explicit receiver: inside SpeechRecognizer.apply {} a bare stopListening() is the RECOGNIZER's, which
                        // after an error answers with ERROR_CLIENT → onError → stopListening… an endless loop (~30 errors/s)
                        if (quiet && silence) { this@VoiceSpeechManager.stopListening(); onSilence(); return }
                        this@VoiceSpeechManager.stopListening()
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> ReplyLanguage.ui("Error de grabación de audio.", "Audio recording error.")
                            SpeechRecognizer.ERROR_NO_MATCH -> ReplyLanguage.ui("No te he entendido.", "I didn't catch that.")
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ReplyLanguage.ui("No te he oído.", "I didn't hear you.")
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ReplyLanguage.ui("El micrófono está ocupado por otra app.", "Another app is using the microphone.")
                            SpeechRecognizer.ERROR_CLIENT -> ReplyLanguage.ui("El reconocimiento de voz se interrumpió.", "Speech recognition was interrupted.")
                            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                ReplyLanguage.ui("El servicio de voz de Google no responde; prueba con conexión o instala el idioma sin conexión.",
                                    "Google's speech service isn't responding; try with a connection or install the offline language.")
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                ReplyLanguage.ui("Sin conexión suficiente para la voz. Descarga el idioma sin conexión en Ajustes → Idioma → Reconocimiento de voz.",
                                    "Not enough connection for voice. Download the offline language in Settings → Language → Speech recognition.")
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ReplyLanguage.ui("Falta el permiso de micrófono (RECORD_AUDIO).", "The microphone permission (RECORD_AUDIO) is missing.")
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                ReplyLanguage.ui("El idioma de voz no está disponible. Descarga el paquete de voz en Ajustes del sistema → Idioma → Reconocimiento de voz.",
                                    "The voice language isn't available. Download the voice pack in system Settings → Language → Speech recognition.")
                            else -> ReplyLanguage.ui("No se pudo capturar audio (código $error).", "Couldn't capture audio (code $error).")
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
                        // Chunk received: listen again for a moment in case you keep talking (the RECOGNIZER's own
                        // startListening, on purpose: same session)
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
            onError(ReplyLanguage.ui("Error al iniciar el micrófono: ", "Could not start the microphone: ") + e.localizedMessage)
        }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        val lang = languageTag()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        // Some recognizers (Google/Samsung) ignore EXTRA_LANGUAGE without the preference
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Silence hints (many recognizers ignore them; that is why chained listening exists)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMillis())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMillis())
        // Android 14+: Lumi is bilingual, so the recognizer may switch between the chosen voice language and the other
        // one (Spanish ↔ English). Recognizers that don't support it ignore these extras.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val other = if (lang.startsWith("es", ignoreCase = true)) "en-US" else "es-ES"
            val both = arrayListOf(lang, other)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
            removeExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE)
        }
    }

    /** Finish now (stop button): if there was text already, it is sent. */
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
            Log.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
        } finally {
            speechRecognizer = null
            _isListening.value = false
            _rmsAmplitude.value = 0f
        }
    }
}
