package io.github.salex27.lumi.service.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Oye Lumi" detector diagnostics: runs a WAV (16 kHz, mono, PCM16) through the same pipeline as the microphone and
 * logs the scores. Used to check that the phone computes the same as the training.
 *   adb shell am broadcast -n <package>/.service.wakeword.WakeSelfTestReceiver --es wav /sdcard/Download/test.wav
 */
class WakeSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("wav") ?: return
        val pending = goAsync()
        Thread {
            try {
                val bytes = File(path).readBytes()
                val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = ShortArray(pcm.remaining()).also { pcm.get(it) }
                OyeLumiDetector(context).use { d ->
                    val scores = mutableListOf<Float>()
                    var i = 0
                    while (i + OyeLumiDetector.CHUNK <= samples.size) {
                        d.process(samples.copyOfRange(i, i + OyeLumiDetector.CHUNK))?.let { scores += it }
                        i += OyeLumiDetector.CHUNK
                    }
                    Log.i("OyeLumiSelfTest", "${File(path).name} max=${"%.4f".format(scores.maxOrNull() ?: 0f)} scores=${scores.joinToString(",") { "%.3f".format(it) }}")
                }
            } catch (e: Exception) {
                Log.e("OyeLumiSelfTest", "Fallo", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
