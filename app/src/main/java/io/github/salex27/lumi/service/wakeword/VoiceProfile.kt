package io.github.salex27.lumi.service.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * The user's voice profile (Lumi's "Voice Match"). Stored ONLY on the phone:
 * - print: the average of the x-vectors (128 numbers) of 3 "Oye Lumi" samples;
 * - names: how the model transcribed the user's "Lumi" (e.g. "lomi"), to recognize it better.
 */
class VoiceProfileStore(context: Context) {

    data class Profile(val embedding: FloatArray, val names: Set<String>)

    private val prefs = context.getSharedPreferences("voice_profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    var sensitivity: WakePhrases.Sensitivity
        get() = runCatching { WakePhrases.Sensitivity.valueOf(prefs.getString(K_SENS, null)!!) }.getOrDefault(WakePhrases.Sensitivity.NORMAL)
        set(v) { prefs.edit().putString(K_SENS, v.name).apply(); _sens.value = v }
    private val _sens = MutableStateFlow(sensitivity)
    val sensitivityFlow: StateFlow<WakePhrases.Sensitivity> = _sens.asStateFlow()

    fun save(embedding: FloatArray, names: Set<String>) {
        prefs.edit().putString(K_VEC, embedding.joinToString(",")).putStringSet(K_NAMES, names).apply()
        _profile.value = Profile(embedding, names)
    }

    fun clear() {
        prefs.edit().remove(K_VEC).remove(K_NAMES).apply()
        _profile.value = null
    }

    private fun load(): Profile? {
        val vec = prefs.getString(K_VEC, null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: return null
        if (vec.isEmpty()) return null
        return Profile(vec, prefs.getStringSet(K_NAMES, emptySet()).orEmpty())
    }

    private companion object {
        const val K_VEC = "embedding"
        const val K_NAMES = "names"
        const val K_SENS = "sensitivity"
    }
}

/**
 * Training: listens until it has [SAMPLES] valid "Oye Lumi" samples (a short phrase starting with "oye/hola" that
 * carries a voice print). "Oye Lumi" listening must be paused meanwhile (microphone).
 */
class VoiceEnroller(private val modelPath: String, private val speakerPath: String) {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Listening(val collected: Int) : State
        /** The last sample wasn't valid (noise, another phrase): ask to repeat it. */
        data class Retry(val collected: Int, val heard: String) : State
        data object Done : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var model: Model? = null
    private var speaker: SpeakerModel? = null
    private var service: SpeechService? = null
    private val vectors = mutableListOf<FloatArray>()
    private val names = mutableSetOf<String>()

    /** Must be called off the main thread (loading the model takes 1-2 s). */
    fun start(onComplete: (FloatArray, Set<String>) -> Unit) {
        stop()
        vectors.clear(); names.clear()
        _state.value = State.Loading
        try {
            val m = Model(modelPath).also { model = it }
            val spk = SpeakerModel(speakerPath).also { speaker = it }
            val rec = Recognizer(m, SAMPLE_RATE, spk)
            service = SpeechService(rec, SAMPLE_RATE).also { it.startListening(listener(onComplete)) }
            _state.value = State.Listening(0)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start training", e)
            _state.value = State.Failed(e.message ?: io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Error al abrir el micrófono", "Could not open the microphone"))
            stop()
        }
    }

    private fun listener(onComplete: (FloatArray, Set<String>) -> Unit) = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = handle(hypothesis, onComplete)
        override fun onFinalResult(hypothesis: String?) = handle(hypothesis, onComplete)
        override fun onError(exception: Exception?) { _state.value = State.Failed(exception?.message ?: io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Error de micrófono", "Microphone error")) }
        override fun onTimeout() {}
    }

    private fun handle(json: String?, onComplete: (FloatArray, Set<String>) -> Unit) {
        val obj = runCatching { JSONObject(json ?: return) }.getOrNull() ?: return
        val text = obj.optString("text")
        if (text.isBlank()) return
        val spk = obj.optJSONArray("spk")
        val phrase = WakePhrases.phraseFromSample(text)
        if (spk == null || phrase == null) {
            _state.value = State.Retry(vectors.size, text)
            return
        }
        vectors += FloatArray(spk.length()) { spk.getDouble(it).toFloat() }
        // The whole phrase is saved (path B) and, if it can be split, the name (path A)
        names += phrase
        WakePhrases.nameFromSample(text)?.let { names += it }
        if (vectors.size >= SAMPLES) {
            stop()
            _state.value = State.Done
            onComplete(WakePhrases.mean(vectors), names.toSet())
        } else {
            _state.value = State.Listening(vectors.size)
        }
    }

    fun stop() {
        runCatching { service?.stop(); service?.shutdown() }
        service = null
        runCatching { speaker?.close(); model?.close() }
        speaker = null; model = null
        if (_state.value is State.Listening || _state.value is State.Loading || _state.value is State.Retry) _state.value = State.Idle
    }

    companion object {
        private const val TAG = "VoiceEnroller"
        const val SAMPLES = 3
        private const val SAMPLE_RATE = 16000f
    }
}
