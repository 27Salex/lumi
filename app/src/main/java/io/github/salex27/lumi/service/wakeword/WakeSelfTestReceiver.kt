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
                intent.getStringExtra("enroll")?.let { enroll(app, it.split(",")) }
                intent.getStringExtra("wav")?.let { run(app, it, intent.getStringExtra("ctx") ?: "quiet") }
            } catch (e: Exception) {
                Log.e(TAG, "Failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun enroll(app: TaskManagerApplication, paths: List<String>) {
        VoicePrinter(app.wakeWordModel.modelPath, app.wakeWordModel.speakerModelPath).use { printer ->
            val results = paths.map { printer.print(readWav(it)) }
            val prints = results.mapNotNull { it?.print }
            Log.i(TAG, "enroll: ${prints.size} of ${paths.size} prints; heard ${results.map { it?.text }}")
            if (prints.isNotEmpty()) app.voiceProfile.save(WakePhrases.mean(prints), emptySet())
        }
    }

    private fun run(app: TaskManagerApplication, path: String, ctxName: String) {
        val samples = readWav(path)
        val ctx = when (ctxName) {
            "speaker" -> AudioContext(true, OutputRoute.SPEAKER, false)
            "bt" -> AudioContext(true, OutputRoute.BLUETOOTH, false)
            "btidle" -> AudioContext(false, OutputRoute.BLUETOOTH, false)
            "headphones" -> AudioContext(true, OutputRoute.HEADPHONES, false)
            else -> AudioContext.QUIET
        }
        val voice = app.voiceProfile
        val profile = voice.profile.value?.takeIf { app.wakeWordModel.isReady() }
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
                    val similarity = profile?.let { p -> result?.print?.let { WakePhrases.cosine(it, p.embedding) } }
                    val verdict = WakeGate.verdict(score ?: 0f, policy, profile != null, similarity, voice.sensitivity.threshold)
                    Log.i(TAG, "wake at ${"%.2f".format(i / 16000f)} s: score=${"%.3f".format(score)} policy=${policy.reason} " +
                        "threshold=${policy.threshold} voice=$similarity heard=«${result?.text}» print=${ms} ms → $verdict")
                    holdUntil = i + (if (verdict is WakeVerdict.Accept) 4 else 1) * OyeLumiDetector.SAMPLE_RATE
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
