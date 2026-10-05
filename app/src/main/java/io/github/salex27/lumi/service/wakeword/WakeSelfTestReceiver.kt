package io.github.salex27.lumi.service.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.salex27.lumi.TaskManagerApplication
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Oye Lumi" detector diagnostics: runs a WAV (16 kHz, mono, PCM16) through the same pipeline as the microphone and
 * logs the scores. Used to check that the phone computes the same as the training.
 *   adb shell am broadcast -n <package>/.service.wakeword.WakeSelfTestReceiver --es wav /sdcard/Download/test.wav
 * With a trained voice it also decides each wake like [WakeWordService] (policy, Voice Match over the last 2 s,
 * verdict and how long the print took), in the audio context given by `--es ctx quiet|speaker|bt|btidle|headphones`.
 * `--es enroll a.wav,b.wav,…` trains the voice from clips instead of the microphone.
 */
class WakeSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as TaskManagerApplication
        Thread {
            try {
                if (intent.getBooleanExtra("clear", false)) app.voiceProfile.clear()
                intent.getStringExtra("enroll")?.let {
                    enroll(app, it.split(","), WakePhrase.valueOf(intent.getStringExtra("phrase") ?: "OYE"))
                }
                intent.getStringExtra("enrollstream")?.let {
                    enrollStream(app, it.split(","), WakePhrase.valueOf(intent.getStringExtra("phrase") ?: "OYE"))
                }
                intent.getStringExtra("wav")?.let { run(app, it, intent.getStringExtra("ctx") ?: "quiet", intent.getBooleanExtra("beginwake", false)) }
                // Adaptive Voice Match: report what the user did after a wake begun with "beginwake"
                if (intent.hasExtra("wakeid")) {
                    app.voiceProfile.wakeOutcome(intent.getLongExtra("wakeid", 0L), intent.getBooleanExtra("confirmed", false))
                    val d = app.voiceProfile.data.value
                    Log.i(TAG, "outcome: learned ${d?.phrases?.values?.sumOf { it.learned.size }}, dismissed ${d?.negatives?.size}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Same as "Train my voice" for [phrase], from clips (one sample each) instead of the microphone. */
    private fun enroll(app: TaskManagerApplication, paths: List<String>, phrase: WakePhrase) {
        VoicePrinter(app.wakeWordModel.modelPath, app.wakeWordModel.speakerModelPath).use { printer ->
            val results = paths.map { printer.print(readWav(it)) }
            val prints = VoiceMatch.dropOutliers(results.mapNotNull { it?.print })
            Log.i(TAG, "enroll $phrase: ${prints.size} of ${paths.size} prints; heard ${results.map { it?.text }}")
            if (prints.isNotEmpty()) app.voiceProfile.savePhrase(phrase, prints, emptySet())
            app.voiceProfile.data.value?.prints(phrase)?.let { p ->
                val loo = VoiceMatch.leaveOneOut(p.enrolled)
                Log.i(TAG, "enroll $phrase: spread ${VoiceMatch.meanSd(loo)} spreadBar=${VoiceMatch.spreadBar(p)} " +
                    "bar=${app.voiceProfile.bar(phrase)} (${app.voiceProfile.sensitivity})")
            }
        }
    }

    /**
     * The real training loop ([VoiceEnroller], as from Settings) fed with the clips one after another, 1.5 s of quiet
     * between them, instead of the microphone: checks the utterance splitting and the per-sample prints.
     */
    private fun enrollStream(app: TaskManagerApplication, paths: List<String>, phrase: WakePhrase) {
        val gap = ShortArray(3 * OyeLumiDetector.SAMPLE_RATE / 2) { ((it * 7919) % 21 - 10).toShort() }
        val stream = paths.fold(ShortArray(0)) { acc, p -> acc + gap + readWav(p) } + gap + gap
        var pos = 0
        val enroller = VoiceEnroller(app.wakeWordModel.modelPath, app.wakeWordModel.speakerModelPath)
        val done = java.util.concurrent.CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        enroller.start(phrase, source = { buf ->
            if (pos >= stream.size) -1 else {
                val n = minOf(buf.size, stream.size - pos)
                System.arraycopy(stream, pos, buf, 0, n); pos += n; n
            }
        }) { prints, names ->
            app.voiceProfile.savePhrase(phrase, prints, names)
            done.countDown()
        }
        var last: VoiceEnroller.State? = null
        // Until it finishes, the recording thread ends (stream over), or 2 minutes
        while (done.count > 0 && enroller.isRunning && SystemClock.elapsedRealtime() - started < 120_000) {
            val s = enroller.state.value
            if (s != last) { Log.i(TAG, "enrollstream state: $s"); last = s }
            Thread.sleep(20)
        }
        Log.i(TAG, "enrollstream $phrase: final ${enroller.state.value} in ${SystemClock.elapsedRealtime() - started} ms; " +
            "saved ${app.voiceProfile.data.value?.prints(phrase)?.enrolled?.size} prints, bar=${app.voiceProfile.bar(phrase)}")
        enroller.stop()
    }

    private fun run(app: TaskManagerApplication, path: String, ctxName: String, beginWake: Boolean) {
        val samples = readWav(path)
        val ctx = when (ctxName) {
            "speaker" -> AudioContext(true, OutputRoute.SPEAKER, false)
            "bt" -> AudioContext(true, OutputRoute.BLUETOOTH, false)
            "btidle" -> AudioContext(false, OutputRoute.BLUETOOTH, false)
            "headphones" -> AudioContext(true, OutputRoute.HEADPHONES, false)
            else -> AudioContext.QUIET
        }
        val voice = app.voiceProfile
        val profile = voice.data.value?.takeIf { app.wakeWordModel.isReady() }
        val policy = WakeGate.policy(voice.sensitivity, ctx, profile != null)
        val printer = profile?.let { VoicePrinter(app.wakeWordModel.modelPath, app.wakeWordModel.speakerModelPath) }
        try {
            val loadStart = SystemClock.elapsedRealtime()
            printer?.preload()
            if (printer != null) Log.i(TAG, "voice print model loaded in ${SystemClock.elapsedRealtime() - loadStart} ms")
            OyeLumiDetector(app).use { d ->
                val scores = mutableListOf<Float>()
                val trigger = WakeTrigger(policy.threshold, policy.patience)
                var holdUntil = -1
                var i = 0
                while (i + OyeLumiDetector.CHUNK <= samples.size) {
                    val score = d.process(samples.copyOfRange(i, i + OyeLumiDetector.CHUNK))
                    score?.let { scores += it }
                    i += OyeLumiDetector.CHUNK
                    if (!trigger.update(score) || i < holdUntil) continue
                    // The service's decision on the last 2 s, as it would happen live
                    val window = ShortArray(2 * OyeLumiDetector.SAMPLE_RATE) { k ->
                        samples.getOrElse(i - 2 * OyeLumiDetector.SAMPLE_RATE + k) { 0 }
                    }
                    val start = SystemClock.elapsedRealtime()
                    val result = printer?.print(window)
                    val ms = SystemClock.elapsedRealtime() - start
                    val phrase = result?.let { WakePhrase.fromTranscript(it.text) }
                    val match = profile?.let { p -> result?.print?.let { VoiceMatch.score(it, p, phrase) } }
                    val bar = match?.let { voice.bar(it.phrase) } ?: voice.sensitivity.threshold
                    val verdict = WakeGate.verdict(score ?: 0f, policy, profile != null, match?.similarity, bar)
                    Log.i(TAG, "wake at ${"%.2f".format(i / 16000f)} s: score=${"%.3f".format(score)} policy=${policy.reason} " +
                        "threshold=${policy.threshold} voice=${match?.similarity} bar=$bar phrase=${match?.phrase}/$phrase " +
                        "heard=«${result?.text}» print=${ms} ms → $verdict")
                    holdUntil = i + (if (verdict is WakeVerdict.Accept) 4 else 1) * OyeLumiDetector.SAMPLE_RATE
                    // Like the service: the accepted wake waits for the assistant's outcome (id for --el wakeid / the Activity)
                    if (beginWake && verdict is WakeVerdict.Accept && match != null && result?.print != null) {
                        val id = voice.beginWake(VoiceMatch.WakeSample(result.print, match.phrase, score ?: 0f, match.similarity, bar, policy.voiceRelax > 0f))
                        Log.i(TAG, "wakeId=$id")
                    }
                }
                Log.i(TAG, "${File(path).name} max=${"%.4f".format(scores.maxOrNull() ?: 0f)} scores=${scores.joinToString(",") { "%.3f".format(it) }}")
            }
        } finally {
            printer?.close()
        }
    }

    private fun readWav(path: String): ShortArray {
        val bytes = File(path).readBytes()
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(pcm.remaining()).also { pcm.get(it) }
    }

    private companion object { const val TAG = "OyeLumiSelfTest" }
}
