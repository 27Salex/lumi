package com.antigravity.gemininanotaskmanager.service.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnóstico del detector «Oye Lumi»: pasa un WAV (16 kHz, mono, PCM16) por la misma tubería que el micrófono y
 * escribe las puntuaciones en el log. Sirve para comprobar que el móvil calcula lo mismo que el entrenamiento.
 *   adb shell am broadcast -n <paquete>/.service.wakeword.WakeSelfTestReceiver --es wav /sdcard/Download/prueba.wav
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
