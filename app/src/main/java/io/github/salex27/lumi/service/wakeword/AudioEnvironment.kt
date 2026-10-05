package io.github.salex27.lumi.service.wakeword

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable

/**
 * Watches what the phone does with audio (issue #6) and turns it into an [AudioContext] for [WakeGate]: other apps
 * playing media ([AudioManager.isMusicActive], refreshed by an [AudioManager.AudioPlaybackCallback]), the output route
 * (Bluetooth / headphones / speaker, refreshed by an [AudioDeviceCallback]) and calls ([AudioManager.getMode]).
 * The callbacks run on the main thread; the detector thread reads the snapshot and polls as a backup.
 */
class AudioEnvironment(context: Context) : Closeable {

    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var mediaNow = false
    @Volatile private var lastMediaAt = 0L
    @Volatile private var route = OutputRoute.SPEAKER
    @Volatile private var inCall = false
    @Volatile private var checkedAt = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { refresh() }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refresh() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refresh() }
    }

    init {
        runCatching {
            am.registerAudioPlaybackCallback(playbackCallback, handler)
            am.registerAudioDeviceCallback(deviceCallback, handler)
        }.onFailure { Log.w(TAG, "Audio callbacks unavailable, polling only", it) }
        refresh()
    }

    /** The current context; re-reads the system state if the last reading is older than [POLL_MS]. */
    fun current(): AudioContext {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt > POLL_MS) refresh()
        return AudioContext(WakeGate.mediaActive(mediaNow, lastMediaAt, now), route, inCall)
    }

    @Synchronized
    fun refresh() {
        runCatching {
            val now = SystemClock.elapsedRealtime()
            val mode = am.mode
            // A ringing phone plays a loud tune through the speaker: treated like media
            mediaNow = am.isMusicActive || mode == AudioManager.MODE_RINGTONE
            if (mediaNow) lastMediaAt = now
            inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            route = routeOf(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
            checkedAt = now
        }.onFailure { Log.w(TAG, "Could not read the audio state", it) }
    }

    override fun close() {
        runCatching {
            am.unregisterAudioPlaybackCallback(playbackCallback)
            am.unregisterAudioDeviceCallback(deviceCallback)
        }
    }

    companion object {
        private const val TAG = "AudioEnvironment"
        private const val POLL_MS = 500L

        private val BLUETOOTH = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET
        )

        /** Android routes media to the most specific connected output: Bluetooth, then headphones, then the speaker. */
        fun routeOf(outputTypes: Collection<Int>): OutputRoute = when {
            outputTypes.any { it in BLUETOOTH } -> OutputRoute.BLUETOOTH
            outputTypes.any { it in HEADPHONES } -> OutputRoute.HEADPHONES
            else -> OutputRoute.SPEAKER
        }
    }
}

/**
 * Acoustic echo cancellation + noise suppression on the wake word's [android.media.AudioRecord] session, so what the
 * phone's own speaker plays is subtracted from the microphone. Only switched on while other audio plays: the model was
 * trained on unprocessed audio, so in silence the signal stays exactly as before. Devices without the effects just skip it.
 */
class CaptureEffects(sessionId: Int) : Closeable {

    private val aec: AcousticEchoCanceler? =
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull() else null
    private val ns: NoiseSuppressor? =
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(sessionId) }.getOrNull() else null
    private var on: Boolean? = null

    val available: Boolean get() = aec != null || ns != null

    fun setActive(active: Boolean) {
        if (on == active) return
        on = active
        listOfNotNull<AudioEffect>(aec, ns).forEach { fx ->
            runCatching { fx.enabled = active }.onFailure { Log.w(TAG, "Could not toggle ${fx.javaClass.simpleName}", it) }
        }
    }

    override fun close() {
        runCatching { aec?.release() }
        runCatching { ns?.release() }
    }

    private companion object {
        const val TAG = "CaptureEffects"
    }
}
