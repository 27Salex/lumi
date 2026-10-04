package io.github.salex27.lumi.service.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The user's voice profile (Lumi's "Voice Match"), stored ONLY on the phone as voice prints (numbers, never audio):
 * per phrase ("Hey Lumi", "Oye Lumi") the prints of the training samples, see [VoiceData] / [VoiceMatch]. A profile
 * from the old 3-sample training (one averaged print in SharedPreferences) is migrated and keeps working for both
 * phrases until each is trained again.
 */
class VoiceProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("voice_profile", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, FILE_NAME)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<VoiceData?> = _data.asStateFlow()

    var sensitivity: WakePhrases.Sensitivity
        get() = runCatching { WakePhrases.Sensitivity.valueOf(prefs.getString(K_SENS, null)!!) }.getOrDefault(WakePhrases.Sensitivity.NORMAL)
        set(v) { prefs.edit().putString(K_SENS, v.name).apply(); _sens.value = v }
    private val _sens = MutableStateFlow(sensitivity)
    val sensitivityFlow: StateFlow<WakePhrases.Sensitivity> = _sens.asStateFlow()

    /** The similarity bar for [phrase] with the current sensitivity (before the media relax). */
    fun bar(phrase: WakePhrase): Float =
        _data.value?.let { VoiceMatch.bar(it, sensitivity, phrase) } ?: sensitivity.threshold

    /** Saves a newly trained [phrase] (replaces its previous prints). */
    @Synchronized
    fun savePhrase(phrase: WakePhrase, prints: List<FloatArray>, names: Set<String>) {
        val current = _data.value ?: VoiceData(emptyMap())
        write(current.withPhrase(phrase, prints, names))
    }

    @Synchronized
    fun clear() {
        file.delete()
        prefs.edit().remove(K_VEC).remove(K_NAMES).apply()
        _data.value = null
    }

    @Synchronized
    private fun write(data: VoiceData) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(VoiceMatch.encode(data))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        prefs.edit().remove(K_VEC).remove(K_NAMES).apply() // migrated: the old print now lives in the file
        _data.value = data
    }

    private fun load(): VoiceData? {
        if (file.exists()) return runCatching { VoiceMatch.decode(file.readText()) }.getOrNull()
        // Old profile (1.0.x): one averaged print of 3 samples
        val vec = prefs.getString(K_VEC, null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: return null
        if (vec.isEmpty()) return null
        return VoiceMatch.migrate(vec, prefs.getStringSet(K_NAMES, emptySet()).orEmpty())
    }

    private companion object {
        const val FILE_NAME = "voice_profile.txt"
        const val K_VEC = "embedding"
        const val K_NAMES = "names"
        const val K_SENS = "sensitivity"
    }
}

/**
 * Training for one phrase: [VoiceMatch.SAMPLES_PER_PHRASE] samples in varied conditions (close, far, soft, louder,
 * with music), each a short phrase that yields a voice print. Records the microphone itself (16 kHz) and splits it
 * into utterances with Vosk; each print is computed with [VoicePrinter] like the wake check does, so training and
 * wakes are measured the same way. "Hey Lumi" listening must be paused meanwhile (microphone).
 */
class VoiceEnroller(private val modelPath: String, private val speakerPath: String) {

    /** What the user is asked to do for each sample (varied, so the print covers real conditions). */
    enum class Condition { CLOSE, FAR, SOFT, LOUD, MUSIC }

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Listening(val phrase: WakePhrase, val collected: Int, val condition: Condition) : State
        /** The last sample wasn't valid (noise, another phrase, no print): ask to repeat it. */
        data class Retry(val phrase: WakePhrase, val collected: Int, val condition: Condition, val heard: String) : State
        data class Done(val phrase: WakePhrase, val samples: Int) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var running = false
    /** The recording thread is still working (set before [start] returns). */
    val isRunning: Boolean get() = running
    @Volatile private var skipRequested = false
    private var worker: Thread? = null

    /**
     * Starts training [phrase] on a background thread; [onComplete] gets the prints and the transcribed names.
     * [source] replaces the microphone (self-test): fills the buffer and returns the samples read, -1 at the end.
     */
    fun start(phrase: WakePhrase, source: ((ShortArray) -> Int)? = null, onComplete: (List<FloatArray>, Set<String>) -> Unit) {
        stop()
        worker?.join(2_000) // the previous recording releases the microphone first
        worker = null
        running = true; skipRequested = false
        _state.value = State.Loading
        worker = thread(name = "voice-enroll") { record(phrase, source, onComplete) }
    }

    /** Ends the music round early (the music samples are optional); finishes with what was collected. */
    fun skipCondition() { skipRequested = true }

    @SuppressLint("MissingPermission") // only offered with "Hey Lumi" on, which needs the microphone permission
    private fun record(phrase: WakePhrase, source: ((ShortArray) -> Int)?, onComplete: (List<FloatArray>, Set<String>) -> Unit) {
        val printer = VoicePrinter(modelPath, speakerPath)
        var record: AudioRecord? = null
        try {
            val rec = printer.streamRecognizer() ?: throw IllegalStateException("model")
            rec.use {
                val read: (ShortArray) -> Int = source ?: run {
                    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4)).also { record = it }
                    if (r.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic")
                    r.startRecording()
                    val mic: (ShortArray) -> Int = { buf -> r.read(buf, 0, buf.size) }
                    mic
                }
                val prints = mutableListOf<FloatArray>()
                val names = mutableSetOf<String>()
                val utterance = ShortRing(UTTERANCE_MAX)
                val chunk = ShortArray(CHUNK)
                emit(State.Listening(phrase, 0, conditionFor(0)))
                while (running) {
                    if (skipRequested && conditionFor(prints.size) == Condition.MUSIC && prints.size >= MIN_SAMPLES) break
                    val n = read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    utterance.add(chunk, n)
                    if (!rec.acceptWaveForm(chunk, n)) continue
                    val text = runCatching { JSONObject(rec.result).optString("text") }.getOrDefault("")
                    val audio = utterance.last(SAMPLE_WINDOW)
                    utterance.clear()
                    if (text.isBlank()) continue
                    val sample = WakePhrases.phraseFromSample(text)
                    // Training isn't time-critical: a short sample may be repeated up to 3 times to get its print
                    val print = sample?.let { printer.print(audio, maxRepeats = 3)?.print }
                    if (sample == null || print == null) {
                        emit(State.Retry(phrase, prints.size, conditionFor(prints.size), text))
                        continue
                    }
                    prints += print
                    names += sample
                    WakePhrases.nameFromSample(text)?.let { names += it }
                    if (prints.size >= SAMPLES) break
                    emit(State.Listening(phrase, prints.size, conditionFor(prints.size)))
                }
                runCatching { record?.stop() }
                if (running && prints.size >= MIN_SAMPLES) {
                    val kept = VoiceMatch.dropOutliers(prints)
                    _state.value = State.Done(phrase, kept.size)
                    onComplete(kept, names.toSet())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Training failed", e)
            if (running) _state.value = State.Failed(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Error al abrir el micrófono", "Could not open the microphone"))
        } finally {
            record?.release()
            printer.close()
            running = false
        }
    }

    private fun emit(s: State) { if (running) _state.value = s }

    fun stop() {
        running = false // the recording thread ends within ~0.1 s (or after the print in progress)
        val s = _state.value
        if (s is State.Listening || s is State.Loading || s is State.Retry) _state.value = State.Idle
    }

    /** Last [capacity] samples of the current utterance. */
    private class ShortRing(private val capacity: Int) {
        private val buf = ShortArray(capacity)
        private var size = 0
        private var pos = 0
        fun add(src: ShortArray, n: Int) { for (i in 0 until n) { buf[pos] = src[i]; pos = (pos + 1) % capacity; if (size < capacity) size++ } }
        fun last(max: Int): ShortArray { val k = minOf(max, size); return ShortArray(k) { buf[(pos - k + it + capacity) % capacity] } }
        fun clear() { size = 0 }
    }

    companion object {
        private const val TAG = "VoiceEnroller"
        const val SAMPLES = VoiceMatch.SAMPLES_PER_PHRASE
        /** The music round can be skipped: this many samples are enough to finish. */
        const val MIN_SAMPLES = 9
        private const val SAMPLE_RATE = OyeLumiDetector.SAMPLE_RATE
        private const val CHUNK = 1600 // 0.1 s
        private const val UTTERANCE_MAX = 6 * SAMPLE_RATE
        /** Each sample's print is taken from its last 2.5 s, like the wake check's 2 s window plus the trailing pause. */
        private const val SAMPLE_WINDOW = 5 * SAMPLE_RATE / 2

        /** close ×3, far ×2, soft ×2, louder ×2, with music ×3 (the last round, which can be skipped). */
        fun conditionFor(index: Int): Condition = when (index) {
            in 0..2 -> Condition.CLOSE
            in 3..4 -> Condition.FAR
            in 5..6 -> Condition.SOFT
            in 7..8 -> Condition.LOUD
            else -> Condition.MUSIC
        }
    }
}
