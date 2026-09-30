package io.github.salex27.lumi.service.wakeword

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wake phrase detector with openWakeWord: one model fires on "Hey Lumi" (English) and "Oye Lumi" (Spanish). Unlike Vosk
 * it doesn't transcribe: it recognizes the SOUND of the phrase.
 *
 * Pipeline (identical to openWakeWord in Python, which it was trained with):
 *  1. 16 kHz PCM16 audio in chunks of 1280 samples (80 ms).
 *  2. melspectrogram.tflite over the last 1280+480 → 8 frames of 32 bands, transformed with x/10 + 2.
 *  3. embedding_model.tflite over the last 76 frames → one 96-value embedding per chunk.
 *  4. Our own classifier (an MLP trained for "Hey Lumi" + "Oye Lumi", [WakeClassifier]) over the last 16 embeddings.
 */
class OyeLumiDetector(context: Context) : Closeable {

    private val mel = Interpreter(asset(context, "oww/melspectrogram.tflite"), Interpreter.Options().setNumThreads(1))
    private val embedding = Interpreter(asset(context, "oww/embedding_model.tflite"), Interpreter.Options().setNumThreads(1))
    private val classifier = context.assets.open("oww/oye_lumi.bin").use { WakeClassifier.load(it) }

    private val raw = ShortArray(RAW_CONTEXT)
    private var rawFilled = 0
    // The mel buffer starts full of 1s (like openWakeWord)
    private val melFrames = ArrayDeque<FloatArray>().apply { repeat(76) { addLast(FloatArray(32) { 1f }) } }
    private val features = ArrayDeque<FloatArray>()

    /** Processes 1280 samples (80 ms). Returns the 0..1 score, or null while the buffer fills up (~1.3 s). */
    fun process(chunk: ShortArray): Float? {
        require(chunk.size == CHUNK) { "Expected $CHUNK samples" }
        // The last 1280+480 samples (at the start, whatever there is)
        System.arraycopy(raw, CHUNK, raw, 0, RAW_CONTEXT - CHUNK)
        System.arraycopy(chunk, 0, raw, RAW_CONTEXT - CHUNK, CHUNK)
        rawFilled = minOf(RAW_CONTEXT, rawFilled + CHUNK)
        val n = rawFilled
        val input = Array(1) { FloatArray(n) { i -> raw[RAW_CONTEXT - n + i].toFloat() } }
        mel.resizeInput(0, intArrayOf(1, n))
        mel.allocateTensors()
        val frames = mel.getOutputTensor(0).shape()[2]
        val out = Array(1) { Array(1) { Array(frames) { FloatArray(32) } } }
        mel.run(input, out)
        out[0][0].forEach { f -> melFrames.addLast(FloatArray(32) { f[it] / 10f + 2f }) }
        while (melFrames.size > MEL_MAX) melFrames.removeFirst()

        // Embedding of the last 76 frames
        val window = Array(1) { Array(76) { r -> Array(32) { c -> FloatArray(1) { melFrames[melFrames.size - 76 + r][c] } } } }
        val emb = Array(1) { Array(1) { Array(1) { FloatArray(96) } } }
        embedding.run(window, emb)
        features.addLast(emb[0][0][0].copyOf())
        while (features.size > 16) features.removeFirst()
        if (features.size < 16) return null

        val x = FloatArray(16 * 96)
        features.forEachIndexed { i, f -> System.arraycopy(f, 0, x, i * 96, 96) }
        return classifier.score(x)
    }

    fun reset() {
        rawFilled = 0; raw.fill(0)
        melFrames.clear(); repeat(76) { melFrames.addLast(FloatArray(32) { 1f }) }
        features.clear()
    }

    override fun close() {
        mel.close(); embedding.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        private const val RAW_CONTEXT = CHUNK + 480
        private const val MEL_MAX = 10 * 97

        private fun asset(context: Context, path: String): ByteBuffer {
            val bytes = context.assets.open(path).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}

/**
 * "Oye Lumi" classifier: an MLP (Linear → ReLU → … → Linear) in pure Kotlin, no runtime. Weights exported by train.py:
 * [n_layers] and per layer [in, out, W (out×in), b], little-endian. Tested on the JVM.
 */
class WakeClassifier(private val layers: List<Layer>) {

    class Layer(val input: Int, val output: Int, val weights: FloatArray, val bias: FloatArray)

    fun score(x: FloatArray): Float {
        var a = x
        layers.forEachIndexed { li, l ->
            val out = FloatArray(l.output)
            for (o in 0 until l.output) {
                var s = l.bias[o]
                val base = o * l.input
                for (i in 0 until l.input) s += l.weights[base + i] * a[i]
                out[o] = if (li < layers.lastIndex) maxOf(0f, s) else s
            }
            a = out
        }
        return 1f / (1f + kotlin.math.exp(-a[0]))
    }

    companion object {
        fun load(stream: InputStream): WakeClassifier {
            val bytes = stream.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int
            val layers = List(n) {
                val input = buf.int; val output = buf.int
                val w = FloatArray(input * output) { buf.float }
                val b = FloatArray(output) { buf.float }
                Layer(input, output, w, b)
            }
            return WakeClassifier(layers)
        }
    }
}

/** Avoids triggering on a single spike: [patience] consecutive chunks above the threshold are required. */
class WakeTrigger(var threshold: Float, var patience: Int) {
    private var streak = 0
    fun update(score: Float?): Boolean {
        if (score == null) { streak = 0; return false }
        streak = if (score >= threshold) streak + 1 else 0
        if (streak >= patience) { streak = 0; return true }
        return false
    }
    fun reset() { streak = 0 }
}
