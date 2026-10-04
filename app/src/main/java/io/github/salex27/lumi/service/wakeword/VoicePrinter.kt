package io.github.salex27.lumi.service.wakeword

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.Closeable

/**
 * Voice print (Vosk x-vector) of a short clip, plus what the small Spanish model transcribed (tells "Oye" from "Hey").
 * Only runs when the detector fired (never continuously). The models are loaded once, on first use or by [preload].
 */
class VoicePrinter(private val modelPath: String, private val speakerPath: String) : Closeable {

    /** [print] = 128 numbers, null if Vosk heard no speech in the clip; [text] = the transcript. */
    class Result(val print: FloatArray?, val text: String)

    private val lock = Any()
    private var model: Model? = null
    private var speaker: SpeakerModel? = null
    @Volatile private var closed = false

    /** Loads the models now (1-2 s), e.g. on a background thread when listening starts. */
    fun preload() { models() }

    /**
     * Vosk only computes a print from ~0.5 s of speech it aligned to words, and a short "Hey Lumi" often falls below
     * that (no print for ~40 % of wakes). The print is a mean over frames, so when none comes out the clip is decoded
     * once more repeated twice: same voice statistics, twice the frames (only then, so it costs nothing otherwise).
     */
    fun print(audio: ShortArray, maxRepeats: Int = 2): Result? = runCatching {
        val first = decode(audio) ?: return null
        var repeated = audio
        for (k in 2..maxRepeats) {
            if (first.print != null) return first
            repeated += audio
            decode(repeated)?.print?.let { return Result(it, first.text) }
        }
        first
    }.onFailure { Log.w(TAG, "No voice print", it) }.getOrNull()

    /** A recognizer on the same model, without the speaker model: splits live audio into utterances (training). */
    fun streamRecognizer(): Recognizer? = models()?.let { (m, _) -> Recognizer(m, OyeLumiDetector.SAMPLE_RATE.toFloat()) }

    private fun decode(audio: ShortArray): Result? {
        val (m, spk) = models() ?: return null
        return Recognizer(m, OyeLumiDetector.SAMPLE_RATE.toFloat(), spk).use { rec ->
            val bytes = ByteArray(audio.size * 2)
            audio.forEachIndexed { i, v -> bytes[2 * i] = (v.toInt() and 0xff).toByte(); bytes[2 * i + 1] = (v.toInt() shr 8).toByte() }
            // An utterance end inside the clip closes a result early: the print can be in that one or in the final one
            val results = buildList {
                if (rec.acceptWaveForm(bytes, bytes.size)) add(JSONObject(rec.result))
                add(JSONObject(rec.finalResult))
            }
            val best = results.maxBy { it.optInt("spk_frames") }
            val arr = best.optJSONArray("spk")
            Result(arr?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
                results.joinToString(" ") { it.optString("text") }.trim())
        }
    }

    private fun models(): Pair<Model, SpeakerModel>? = synchronized(lock) {
        if (closed) return null
        runCatching {
            val m = model ?: Model(modelPath).also { model = it }
            val spk = speaker ?: SpeakerModel(speakerPath).also { speaker = it }
            m to spk
        }.onFailure { Log.w(TAG, "Could not load the voice print model", it) }.getOrNull()
    }

    override fun close() = synchronized(lock) {
        closed = true
        runCatching { speaker?.close() }; speaker = null
        runCatching { model?.close() }; model = null
    }

    private companion object { const val TAG = "VoicePrinter" }
}
