package io.github.salex27.lumi.service.wakeword

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
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.presentation.assistant.AssistantActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * "Hey Lumi" / "Oye Lumi": continuous OFFLINE listening with a wake word detector (openWakeWord, [OyeLumiDetector]; one
 * model for both phrases): it recognizes
 * the SOUND of the phrase, transcribes nothing and sends audio nowhere. It replaced Vosk, whose Spanish model has no
 * "lumi" in its vocabulary and could never write it. Vosk is only kept for the voice print.
 *
 * - A microphone-type foreground service (Android requires it and shows the mic indicator).
 * - Paused while the assistant uses the microphone and, if the user chooses, with the screen off.
 * - While other audio plays (series/music, car Bluetooth) echo cancellation is switched on and, with a trained voice,
 *   Voice Match decides; without one the bar goes up a little and the assistant confirms before acting; paused during
 *   calls ([WakeGate], [AudioEnvironment], issue #6).
 * - Can only be started with the app in the foreground (Android 14 microphone restriction).
 * - Opening the assistant over other apps needs "Display over other apps"; without it, a tappable notification is shown.
 */
class WakeWordService : Service() {

    private var detector: OyeLumiDetector? = null
    private val trigger = WakeTrigger(0.5f, 3)
    @Volatile private var listening = false
    @Volatile private var alive = true
    private var worker: Thread? = null
    /** The last 2 s of audio, to check the user's voice when the phrase is detected. */
    private val history = ShortArray(HISTORY)
    private var historyPos = 0
    // Voice print (Vosk), only if the user trained their voice
    private val printer by lazy { (application as TaskManagerApplication).let { VoicePrinter(it.wakeWordModel.modelPath, it.wakeWordModel.speakerModelPath) } }
    @Volatile private var modelReady = false
    @Volatile private var modelCheckedAt = 0L
    private var lastTrigger = 0L
    private var lastRejected = 0L
    private var lastSuppressed = 0L
    private var screenReceiver: BroadcastReceiver? = null
    /** Media playing / Bluetooth route / calls: raises the bar or pauses detection (issue #6). */
    private var audioEnv: AudioEnvironment? = null
    private var currentPolicy: WakePolicy? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, listeningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        _running.value = true
        registerScreenReceiver()
        audioEnv = AudioEnvironment(this)
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
     * Listening loop: records 80 ms, scores it and, if "Oye Lumi" is above the threshold enough times in a row (depending
     * on the sensitivity), checks the voice (if trained) and opens the assistant. Pauses by releasing the microphone.
     */
    @SuppressLint("MissingPermission") // the service is only started with the permission granted
    private fun loop() {
        val app = application as TaskManagerApplication
        try {
            detector = OyeLumiDetector(this)
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the detector", e)
            stopSelf(); return
        }
        val chunk = ShortArray(OyeLumiDetector.CHUNK)
        val env = audioEnv ?: return
        // With a trained voice, the voice print model is loaded now (1-2 s, once) instead of on the first wake
        if (app.voiceProfile.profile.value != null && app.wakeWordModel.isReady()) thread(name = "voice-print-load") { printer.preload() }
        listening = !screenOffPaused()
        while (alive) {
            if (!listening) { Thread.sleep(200); continue }
            val minBuf = AudioRecord.getMinBufferSize(OyeLumiDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, OyeLumiDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, OyeLumiDetector.CHUNK * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); Thread.sleep(1000); continue }
            // Echo cancellation + noise suppression, only switched on while other audio plays (issue #6)
            val effects = CaptureEffects(record.audioSessionId)
            record.startRecording()
            detector?.reset(); trigger.reset()
            var paused = false
            Log.i(TAG, "Listening for the wake phrase (echo cancellation available: ${effects.available})")
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
                val ctx = env.current()
                val policy = WakeGate.policy(sensitivity, ctx, voiceMatchAvailable())
                if (policy != currentPolicy) {
                    Log.i(TAG, "Wake policy: ${policy.reason} (threshold ${policy.threshold}, patience ${policy.patience}, $ctx)")
                    currentPolicy = policy
                }
                effects.setActive(ctx.mediaPlaying)
                // During a call nothing is scored; the stale windows are dropped when it ends
                if (!policy.listen) { if (!paused) { paused = true; detector?.reset(); trigger.reset() }; continue }
                paused = false
                trigger.threshold = policy.threshold
                trigger.patience = policy.patience
                val score = detector?.process(chunk)
                if (score != null && score >= 0.2f) lastScore.value = score
                if (trigger.update(score)) onDetected(score ?: 0f, policy, ctx)
                else if (WakeGate.suppressedByAudio(score, sensitivity.detectorThreshold, policy)) onSuppressed(score ?: 0f, policy, ctx)
            }
            runCatching { record.stop() }
            effects.close()
            record.release()
        }
    }

    private fun remember(chunk: ShortArray) {
        for (v in chunk) { history[historyPos] = v; historyPos = (historyPos + 1) % HISTORY }
    }

    private fun lastAudio(): ShortArray = ShortArray(HISTORY) { history[(historyPos + it) % HISTORY] }

    private fun onDetected(score: Float, policy: WakePolicy, ctx: AudioContext) {
        val app = application as TaskManagerApplication
        val voice = app.voiceProfile
        val now = SystemClock.elapsedRealtime()
        // Right after a wake, or while the same sound keeps firing after a rejection, nothing is checked again
        if (now - lastTrigger < COOLDOWN_MS || now - lastRejected < REJECT_HOLD_MS) return
        val label = "«Lumi» (${(score * 100).toInt()} %)"
        // If the user trained their voice, it has to be their voice (Vosk print over the last 2 s; ran on every
        // detection with a profile before too, so Voice Match as the media gate adds no work while listening)
        val profile = voice.profile.value?.takeIf { voiceMatchAvailable() }
        val started = SystemClock.elapsedRealtime()
        val similarity = profile?.let { p -> printer.print(lastAudio())?.print?.let { WakePhrases.cosine(it, p.embedding) } }
        val checkMs = SystemClock.elapsedRealtime() - started
        val verdict = WakeGate.verdict(score, policy, profile != null, similarity, voice.sensitivity.threshold)
        // Debug log to tune thresholds from real false wakes (issue #6)
        Log.i(TAG, "Wake: score=$score policy=${policy.reason} threshold=${policy.threshold} media=${ctx.mediaPlaying} " +
            "route=${ctx.route} voice=$similarity (${checkMs} ms) → $verdict")
        when (verdict) {
            is WakeVerdict.RejectVoice -> {
                lastRejected = SystemClock.elapsedRealtime()
                report(label, similarity, false, getString(io.github.salex27.lumi.R.string.wake_voice_mismatch))
            }
            WakeVerdict.RejectNoPrint -> {
                lastRejected = SystemClock.elapsedRealtime()
                report(label, null, false, getString(io.github.salex27.lumi.R.string.wake_no_print))
            }
            is WakeVerdict.Accept -> {
                lastTrigger = SystemClock.elapsedRealtime()
                // Borderline score or other audio playing without a voice match → the assistant confirms first
                report(label, similarity, true, getString(if (verdict.confirm) io.github.salex27.lumi.R.string.wake_accepted_confirm else io.github.salex27.lumi.R.string.wake_accepted))
                releaseMic() // the assistant needs the microphone; resumed when it closes (the assistant's onStop)
                openAssistant(verdict.confirm)
            }
        }
    }

    /** Voice Match can run: the user trained their voice and the print model is installed (checked every few s). */
    private fun voiceMatchAvailable(): Boolean {
        val app = application as TaskManagerApplication
        if (app.voiceProfile.profile.value == null) return false
        val now = SystemClock.elapsedRealtime()
        if (now - modelCheckedAt > MODEL_CHECK_MS) { modelReady = app.wakeWordModel.isReady(); modelCheckedAt = now }
        return modelReady
    }

    /** A score the user's sensitivity would have accepted, ignored because other audio was playing (diagnostics). */
    private fun onSuppressed(score: Float, policy: WakePolicy, ctx: AudioContext) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSuppressed < COOLDOWN_MS) return
        lastSuppressed = now
        Log.i(TAG, "Wake ignored: score=$score policy=${policy.reason} threshold=${policy.threshold} media=${ctx.mediaPlaying} route=${ctx.route}")
        report("«Lumi» (${(score * 100).toInt()} %)", null, false, getString(io.github.salex27.lumi.R.string.wake_ignored_audio))
    }

    /** Releases the microphone (so the assistant or voice training can use it). */
    private fun releaseMic() { listening = false }

    private fun resumeMic() { listening = true }

    private fun report(text: String, similarity: Float?, accepted: Boolean, reason: String) {
        lastHeard.value = Heard(text, similarity, accepted, reason, System.currentTimeMillis())
    }

    private fun openAssistant(confirm: Boolean) {
        val intent = AssistantActivity.intent(this, startListening = true, compact = true, fromWakeWord = true, wakeConfirm = confirm)
        if (Settings.canDrawOverlays(this)) {
            // Exempt from the background activity start restriction thanks to SYSTEM_ALERT_WINDOW
            startActivity(intent)
        } else {
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(NotificationManager::class.java).notify(
                NOTIF_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_assistant)
                    .setContentTitle("Lumi").setContentText(getString(io.github.salex27.lumi.R.string.wake_tap_to_talk)).setContentIntent(pi).setAutoCancel(true)
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
        audioEnv?.close()
        detector?.close()
        printer.close()
        _running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(io.github.salex27.lumi.R.string.wake_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(io.github.salex27.lumi.R.string.wake_channel_description)
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
            .setContentTitle(getString(io.github.salex27.lumi.R.string.wake_notif_title))
            .setContentText(getString(io.github.salex27.lumi.R.string.wake_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 3, AssistantActivity.intent(this, startListening = true, compact = true), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    /** The last short phrase heard and whether it triggered Lumi (diagnostics shown in Settings → My voice). */
    data class Heard(val text: String, val similarity: Float?, val accepted: Boolean, val reason: String, val at: Long)

    companion object {
        val lastHeard = kotlinx.coroutines.flow.MutableStateFlow<Heard?>(null)
        /** Most recent detector score ≥ 0.2 (diagnostics: how close the last sound was to "Oye Lumi"). */
        val lastScore = MutableStateFlow(0f)
        private const val HISTORY = 2 * 16_000
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIF_ID = 4242
        private const val COOLDOWN_MS = 4_000L
        /** After a rejected wake the same sound keeps firing for a few windows: not checked again for this long. */
        private const val REJECT_HOLD_MS = 1_000L
        private const val MODEL_CHECK_MS = 5_000L

        const val ACTION_PAUSE = "io.github.salex27.lumi.WAKE_PAUSE"
        const val ACTION_RESUME = "io.github.salex27.lumi.WAKE_RESUME"
        private const val ACTION_STOP_SELF = "io.github.salex27.lumi.WAKE_STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Only with the app in the foreground (Android 14+ won't start the microphone from the background). */
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, WakeWordService::class.java))
        fun pause(context: Context) { if (running.value) context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE)) }
        fun resume(context: Context) { if (running.value) context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME)) }
    }
}
