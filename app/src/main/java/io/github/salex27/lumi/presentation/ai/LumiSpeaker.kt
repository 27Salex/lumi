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
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.LanguageDetector
import io.github.salex27.lumi.domain.assistant.ReplyLanguage

/**
 * Lumi speaks: reads replies aloud when you talk to it by voice ("Oye Lumi, what do I have today?").
 * Uses the system TextToSpeech (Google / Samsung, offline voices). Ducks the music while it speaks (audio focus with
 * "ducking") and says nothing if the setting is off.
 */
class LumiSpeaker(private val context: Context, private val settings: SettingsRepository) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    /** Text requested before the engine finished starting. */
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

    /** Releases the engine (when the assistant closes). Recreated when speaking again. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun create(): TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val engine = tts ?: return@TextToSpeech
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech not available ($status)")
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
        engine.setLanguage(localeFor(text))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Voice for this text: Lumi answers in the language it was spoken to, so an English reply isn't read with a Spanish
     * voice. The chosen voice language is kept when it matches (es-MX, en-GB…).
     */
    private fun localeFor(text: String): Locale {
        val lang = LanguageDetector.detect(text, ReplyLanguage.app)
        val chosen = Locale.forLanguageTag(settings.current.voiceLanguage)
        return when {
            Lang.of(chosen.language) == lang -> chosen
            lang == Lang.EN -> Locale.US
            else -> Locale.forLanguageTag("es-ES")
        }
    }

    private fun done() {
        _speaking.value = false
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    companion object {
        private const val TAG = "LumiSpeaker"
        private const val UTTERANCE_ID = "lumi_reply"

        /**
         * Adapts on-screen text for speech: no angle quotes, no "1." from lists (read as pauses), dashes as commas
         * and no stray emojis.
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
