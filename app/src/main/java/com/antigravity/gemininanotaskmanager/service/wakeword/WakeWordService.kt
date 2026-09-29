package com.antigravity.gemininanotaskmanager.service.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.antigravity.gemininanotaskmanager.R
import com.antigravity.gemininanotaskmanager.TaskManagerApplication
import com.antigravity.gemininanotaskmanager.presentation.assistant.AssistantActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * «Oye Lumi»: escucha continua OFFLINE con un detector de palabra de activación (openWakeWord, [OyeLumiDetector]):
 * reconoce el SONIDO de la frase, no transcribe nada ni envía audio a ningún sitio. v3.5: sustituye a Vosk, cuyo
 * modelo español no tiene «lumi» en su vocabulario y nunca podía escribirlo. Vosk queda solo para la huella de voz.
 *
 * - Servicio en primer plano de tipo micrófono (Android lo exige y muestra el indicador de micro).
 * - Se pausa mientras el asistente usa el micrófono y, si el usuario lo elige, con la pantalla apagada.
 * - Solo se puede arrancar con la app en primer plano (restricción de Android 14 para el micrófono).
 * - Para abrir el asistente sobre otras apps hace falta el permiso «Mostrar sobre otras apps»;
 *   sin él se muestra una notificación para tocar.
 */
class WakeWordService : Service() {

    private var detector: OyeLumiDetector? = null
    private val trigger = WakeTrigger(0.5f, 3)
    @Volatile private var listening = false
    @Volatile private var alive = true
    private var worker: Thread? = null
    /** Últimos 2 s de audio para comprobar la voz del usuario al detectar la frase. */
    private val history = ShortArray(HISTORY)
    private var historyPos = 0
    // Huella de voz (Vosk), solo si el usuario entrenó su voz
    private var voskModel: Model? = null
    private var speakerModel: SpeakerModel? = null
    private var lastTrigger = 0L
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, listeningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        _running.value = true
        registerScreenReceiver()
        worker = thread(name = "oye-lumi") { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> releaseMic()
            ACTION_RESUME -> if (!screenOffPaused()) resumeMic()
            ACTION_STOP_SELF -> {
                (application as TaskManagerApplication).settings.update { it.copy(wakeWordEnabled = false) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Bucle de escucha: graba 80 ms, puntúa y, si «Oye Lumi» supera el umbral varias veces seguidas (según la
     * sensibilidad), comprueba la voz (si está entrenada) y abre el asistente. Se pausa soltando el micrófono.
     */
    @SuppressLint("MissingPermission") // el servicio solo se arranca con el permiso concedido
    private fun loop() {
        val app = application as TaskManagerApplication
        try {
            detector = OyeLumiDetector(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo cargar el detector", e)
            stopSelf(); return
        }
        val chunk = ShortArray(OyeLumiDetector.CHUNK)
        listening = !screenOffPaused()
        while (alive) {
            if (!listening) { Thread.sleep(200); continue }
            val minBuf = AudioRecord.getMinBufferSize(OyeLumiDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, OyeLumiDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, OyeLumiDetector.CHUNK * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); Thread.sleep(1000); continue }
            record.startRecording()
            detector?.reset(); trigger.reset()
            Log.i(TAG, "Escuchando «Oye Lumi»")
            while (alive && listening) {
                var read = 0
                while (read < chunk.size && alive && listening) {
                    val n = record.read(chunk, read, chunk.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < chunk.size) continue
                remember(chunk)
                val sensitivity = app.voiceProfile.sensitivity
                trigger.threshold = sensitivity.detectorThreshold
                trigger.patience = sensitivity.patience
                val score = detector?.process(chunk)
                if (score != null && score >= 0.2f) lastScore.value = score
                if (trigger.update(score)) onDetected(score ?: 0f)
            }
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun remember(chunk: ShortArray) {
        for (v in chunk) { history[historyPos] = v; historyPos = (historyPos + 1) % HISTORY }
    }

    private fun lastAudio(): ShortArray = ShortArray(HISTORY) { history[(historyPos + it) % HISTORY] }

    private fun onDetected(score: Float) {
        val app = application as TaskManagerApplication
        val voice = app.voiceProfile
        val profile = voice.profile.value
        val label = "«Oye Lumi» (${(score * 100).toInt()} %)"
        // Si el usuario entrenó su voz, tiene que ser su voz (huella de Vosk sobre los últimos 2 s)
        var similarity: Float? = null
        if (profile != null) {
            val vec = speakerVector(lastAudio())
            if (vec != null) {
                similarity = WakePhrases.cosine(vec, profile.embedding)
                if (similarity < voice.sensitivity.threshold) { report(label, similarity, false, "La voz no se parece lo bastante"); return }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now
        report(label, similarity, true, "Activada")
        releaseMic() // el asistente necesita el micrófono; se reanuda al cerrarlo (onStop del asistente)
        openAssistant()
    }

    /** Huella de voz (x-vector) de un fragmento, con el modelo de Vosk. null si no hay modelo o no sale huella. */
    private fun speakerVector(audio: ShortArray): FloatArray? {
        val app = application as TaskManagerApplication
        if (!app.wakeWordModel.isReady()) return null
        return runCatching {
            val m = voskModel ?: Model(app.wakeWordModel.modelPath).also { voskModel = it }
            val spk = speakerModel ?: SpeakerModel(app.wakeWordModel.speakerModelPath).also { speakerModel = it }
            Recognizer(m, OyeLumiDetector.SAMPLE_RATE.toFloat(), spk).use { rec ->
                val bytes = ByteArray(audio.size * 2)
                audio.forEachIndexed { i, v -> bytes[2 * i] = (v.toInt() and 0xff).toByte(); bytes[2 * i + 1] = (v.toInt() shr 8).toByte() }
                rec.acceptWaveForm(bytes, bytes.size)
                val arr = JSONObject(rec.finalResult).optJSONArray("spk") ?: return@use null
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
        }.onFailure { Log.w(TAG, "Sin huella de voz", it) }.getOrNull()
    }

    /** Soltar el micrófono (para que el asistente o el entrenamiento de voz puedan usarlo). */
    private fun releaseMic() { listening = false }

    private fun resumeMic() { listening = true }

    private fun report(text: String, similarity: Float?, accepted: Boolean, reason: String) {
        lastHeard.value = Heard(text, similarity, accepted, reason, System.currentTimeMillis())
    }

    private fun openAssistant() {
        val intent = AssistantActivity.intent(this, startListening = true, compact = true, fromWakeWord = true)
        if (Settings.canDrawOverlays(this)) {
            // Exento de la restricción de abrir actividades en segundo plano gracias a SYSTEM_ALERT_WINDOW
            startActivity(intent)
        } else {
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(NotificationManager::class.java).notify(
                NOTIF_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_assistant)
                    .setContentTitle("Lumi").setContentText("Toca para hablar").setContentIntent(pi).setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build()
            )
            resumeMic()
        }
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> if (onlyScreenOn()) releaseMic()
                    Intent.ACTION_SCREEN_ON -> resumeMic()
                }
            }
        }
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun onlyScreenOn() = (application as TaskManagerApplication).settings.current.wakeWordScreenOnly
    private fun screenOffPaused() = onlyScreenOn() && !getSystemService(PowerManager::class.java).isInteractive

    override fun onDestroy() {
        screenReceiver?.let { unregisterReceiver(it) }
        alive = false; listening = false
        worker?.join(1500)
        detector?.close()
        speakerModel?.close()
        voskModel?.close()
        _running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Oye Lumi", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso permanente mientras Lumi escucha la palabra de activación"
                setShowBadge(false)
            }
        )
    }

    private fun listeningNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP_SELF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle("Di «Oye Lumi»")
            .setContentText("Escucha local, sin internet. Solo reacciona a «Oye Lumi».")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 3, AssistantActivity.intent(this, startListening = true, compact = true), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    /** Última frase corta oída y si activó a Lumi (diagnóstico visible en Ajustes → Mi voz). */
    data class Heard(val text: String, val similarity: Float?, val accepted: Boolean, val reason: String, val at: Long)

    companion object {
        val lastHeard = kotlinx.coroutines.flow.MutableStateFlow<Heard?>(null)
        /** Puntuación más reciente del detector ≥ 0,2 (diagnóstico: cuánto se ha parecido lo último a «Oye Lumi»). */
        val lastScore = MutableStateFlow(0f)
        private const val HISTORY = 2 * 16_000
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIF_ID = 4242
        private const val COOLDOWN_MS = 4_000L

        const val ACTION_PAUSE = "com.antigravity.gemininanotaskmanager.WAKE_PAUSE"
        const val ACTION_RESUME = "com.antigravity.gemininanotaskmanager.WAKE_RESUME"
        private const val ACTION_STOP_SELF = "com.antigravity.gemininanotaskmanager.WAKE_STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Solo con la app en primer plano (Android 14+ no deja arrancar el micrófono desde segundo plano). */
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, WakeWordService::class.java))
        fun pause(context: Context) { if (running.value) context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE)) }
        fun resume(context: Context) { if (running.value) context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME)) }
    }
}
