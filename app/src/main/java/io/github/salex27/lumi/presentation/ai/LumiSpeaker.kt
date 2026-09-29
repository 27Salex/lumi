package io.github.salex27.lumi.presentation.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.salex27.lumi.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Lumi habla: lee en voz alta las respuestas cuando le hablas por voz («Oye Lumi, ¿qué tengo hoy?»).
 * Usa el TextToSpeech del sistema (Google / Samsung, voces offline en español). Baja el volumen de la música
 * mientras habla (foco de audio con «ducking») y no dice nada si el ajuste está desactivado.
 */
class LumiSpeaker(private val context: Context, private val settings: SettingsRepository) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    /** Texto pedido antes de que el motor terminara de iniciarse. */
    private var pending: String? = null

    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
    }

    fun speak(text: String) {
        if (!settings.current.speakReplies) return
        val clean = forSpeech(text)
        if (clean.isBlank()) return
        val engine = tts ?: create().also { tts = it }
        if (!ready) { pending = clean; return }
        say(engine, clean)
    }

    fun stop() {
        pending = null
        tts?.stop()
        done()
    }

    /** Libera el motor (al cerrar el asistente). Se recrea al volver a hablar. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun create(): TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val engine = tts ?: return@TextToSpeech
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech no disponible ($status)")
            return@TextToSpeech
        }
        val locale = Locale.forLanguageTag(settings.current.voiceLanguage)
        if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.forLanguageTag("es-ES"))
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) = done()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = done()
        })
        ready = true
        pending?.let { pending = null; say(engine, it) }
    }

    private fun say(engine: TextToSpeech, text: String) {
        audio.requestAudioFocus(focus)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun done() {
        _speaking.value = false
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    companion object {
        private const val TAG = "LumiSpeaker"
        private const val UTTERANCE_ID = "lumi_reply"

        /**
         * Adapta el texto de pantalla a la voz: sin comillas angulares, sin «1.» de las listas (se leen como
         * pausas), guiones como comas y sin emojis sueltos.
         */
        fun forSpeech(text: String): String = text
            .replace("«", "").replace("»", "")
            .replace(Regex("(?m)^\\s*\\d+\\.\\s*"), "")
            .replace(" — ", ", ")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("\\n+"), ". ")
            .replace(Regex("([.:;?!])\\s*\\."), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
