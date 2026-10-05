package io.github.salex27.lumi.data.hub

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live link to Lumi Hub: an SSE stream that is open ONLY while some Lumi screen is visible (nothing runs in the
 * background, so the always-on services and the battery are untouched). Reconnects with backoff; events go to the
 * [HubInbox] in order, and agent replies are also published on [replies] for open threads.
 */
class HubConnection(
    private val settings: HubSettings,
    private val client: HubClient,
    private val inbox: HubInbox,
    private val scope: CoroutineScope
) {
    enum class Status { OFF, CONNECTING, CONNECTED, OFFLINE }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _replies = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)
    /** Streamed `reply` events (wake-per-message turns). */
    val replies: SharedFlow<HubEvent> = _replies.asSharedFlow()

    private var job: Job? = null
    private var visible = false
    private var stopJob: Job? = null

    init {
        // A new address or token restarts the stream
        scope.launch { settings.config.drop(1).collect { if (visible) restart() } }
    }

    /** Called by the app when its first screen starts / its last screen stops (with a grace period for rotations). */
    @Synchronized
    fun setVisible(isVisible: Boolean) {
        stopJob?.cancel()
        if (isVisible) {
            visible = true
            if (job?.isActive != true) restart()
        } else {
            stopJob = scope.launch {
                delay(STOP_GRACE_MS)
                synchronized(this@HubConnection) { visible = false; stop() }
            }
        }
    }

    @Synchronized
    private fun restart() {
        stop()
        if (!settings.config.value.isConfigured) { _status.value = Status.OFF; return }
        job = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                _status.value = Status.CONNECTING
                try {
                    client.stream(settings.lastEventId, onConnected = { _status.value = Status.CONNECTED; backoff = 2_000L }) { e ->
                        if (e.type == HubEvent.REPLY) _replies.emit(e) else inbox.handle(e)
                        if (e.id > settings.lastEventId) settings.lastEventId = e.id
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.i(TAG, "stream closed: ${e.message}")
                }
                if (!isActive) break
                _status.value = Status.OFFLINE
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    @Synchronized
    private fun stop() {
        job?.cancel()
        job = null
        client.closeStream() // unblocks the reading thread
        _status.value = Status.OFF
    }

    private companion object {
        const val STOP_GRACE_MS = 5_000L
        const val TAG = "LumiHub"
    }
}
