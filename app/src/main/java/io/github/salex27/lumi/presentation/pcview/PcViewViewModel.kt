package io.github.salex27.lumi.presentation.pcview

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.salex27.lumi.data.hub.HubSettings
import io.github.salex27.lumi.data.hub.PcException
import io.github.salex27.lumi.data.hub.PcMonitor
import io.github.salex27.lumi.data.hub.PcViewClient
import io.github.salex27.lumi.domain.pcview.PcPhase
import io.github.salex27.lumi.domain.pcview.PcReply
import io.github.salex27.lumi.domain.pcview.PcState
import io.github.salex27.lumi.domain.pcview.PcViewLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class PcFrameImage(val image: ImageBitmap, val width: Int, val height: Int)

/**
 * "My PC" (view only). The unlock token lives only in memory here: leaving the screen or the app locks again.
 * Nothing in this class sends input to the PC.
 */
class PcViewViewModel(private val client: PcViewClient, private val settings: HubSettings) : ViewModel() {
    private val _state = MutableStateFlow(PcState(PcPhase.CHECKING))
    val state: StateFlow<PcState> = _state.asStateFlow()
    private val _monitors = MutableStateFlow<List<PcMonitor>>(emptyList())
    val monitors: StateFlow<List<PcMonitor>> = _monitors.asStateFlow()
    private val _monitor = MutableStateFlow(0)
    val monitor: StateFlow<Int> = _monitor.asStateFlow()
    private val _frame = MutableStateFlow<PcFrameImage?>(null)
    val frame: StateFlow<PcFrameImage?> = _frame.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()

    @Volatile private var token: String? = null
    private var job: Job? = null
    private var etag: String? = null

    fun refresh() {
        if (_state.value.phase == PcPhase.VIEWING || _state.value.phase == PcPhase.UNLOCKING) return
        if (!settings.pcConfig.value.isConfigured) { _state.value = PcState(PcPhase.NOT_CONFIGURED); return }
        _state.value = PcState(PcPhase.CHECKING)
        viewModelScope.launch {
            _state.value = try {
                PcViewLogic.fromReply(client.status(), _state.value)
            } catch (e: PcException) {
                PcViewLogic.fromReply(e.reply, _state.value)
            } catch (e: IOException) {
                PcViewLogic.fromReply(PcReply.Unreachable, _state.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // an answer that is not the hub's JSON (another server on this port) must not leave the screen on "Checking"
                PcState(PcPhase.HUB_OLD)
            }
        }
    }

    fun noSecureLock() { _state.value = PcState(PcPhase.NO_SECURE_LOCK) }

    /** Call only after the platform biometric / device-credential prompt succeeded. */
    fun unlockAfterAuth() {
        _state.value = PcState(PcPhase.UNLOCKING)
        viewModelScope.launch {
            try {
                val (t, seconds) = client.unlock()
                token = t
                _monitors.value = client.monitors(t)
                _monitor.value = _monitors.value.firstOrNull { it.primary }?.id ?: _monitors.value.firstOrNull()?.id ?: 0
                _paused.value = false
                etag = null
                _state.value = PcViewLogic.unlocked(System.currentTimeMillis(), seconds)
                startLoop()
            } catch (e: PcException) {
                _state.value = PcViewLogic.fromReply(e.reply, PcState(PcPhase.LOCKED))
            } catch (e: IOException) {
                _state.value = PcState(PcPhase.HUB_OFFLINE)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // unlocked but the follow-up failed: not a missing route, drop the unlock and let the user retry
                if (token != null) { token = null; _state.value = PcState(PcPhase.LOCKED) } else _state.value = PcState(PcPhase.HUB_OLD)
            }
        }
    }

    fun selectMonitor(id: Int) { _monitor.value = id; etag = null; _frame.value = null }

    fun setPaused(value: Boolean) { _paused.value = value; if (!value) etag = null }

    /** Revokes the unlock on the hub (drops viewers) and forgets it here. */
    fun lockNow() {
        end(PcState(PcPhase.LOCKED), revoke = true)
    }

    /** The screen left the foreground: stop capturing and lock, so every viewing starts with an unlock. */
    fun onLeave() {
        if (token != null) end(PcState(PcPhase.LOCKED), revoke = true)
    }

    private fun end(next: PcState, revoke: Boolean) {
        job?.cancel(); job = null
        token = null
        _frame.value = null
        _monitors.value = emptyList()
        _state.value = next
        if (revoke) {
            // viewModelScope may be cancelled with the screen: finish the call anyway, best effort, on its own thread
            Thread { runCatching { kotlinx.coroutines.runBlocking { client.lock() } } }.start()
        }
    }

    private fun startLoop() {
        job?.cancel()
        job = viewModelScope.launch {
            var tuning = PcViewLogic.Tuning(1280, 60, 150)
            var attempt = 0
            while (isActive) {
                val t = token ?: break
                val now = System.currentTimeMillis()
                val ticked = PcViewLogic.tick(_state.value, now)
                if (ticked.phase == PcPhase.EXPIRED) { end(ticked, revoke = false); break }
                _secondsLeft.value = PcViewLogic.secondsLeft(ticked, now)
                if (_paused.value) { delay(500); continue }
                try {
                    if (PcViewLogic.shouldExtend(ticked, now, false)) {
                        _state.value = PcViewLogic.unlocked(now, client.extend(t))
                    }
                    val started = System.currentTimeMillis()
                    val f = client.frame(t, _monitor.value, tuning.width, tuning.quality, etag)
                    val took = System.currentTimeMillis() - started
                    attempt = 0
                    if (_state.value.phase == PcPhase.RECONNECTING) _state.value = PcState(PcPhase.VIEWING, _state.value.expiresAtMs)
                    val bytes = f.bytes
                    if (bytes != null) {
                        etag = f.etag
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                            _frame.value = PcFrameImage(it.asImageBitmap(), it.width, it.height)
                        }
                    }
                    tuning = PcViewLogic.adapt(tuning, took, bytes?.size ?: 0, f.hubDelayMs)
                    delay(tuning.delayMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PcException) {
                    val next = PcViewLogic.fromReply(e.reply, _state.value)
                    if (next.phase == PcPhase.RECONNECTING) {
                        _state.value = next
                        delay(PcViewLogic.reconnectDelayMs(attempt++) ?: run { end(PcState(PcPhase.HUB_OFFLINE), revoke = false); return@launch })
                    } else { end(next, revoke = false); break }
                } catch (e: IOException) {
                    tuning = PcViewLogic.degrade(tuning)
                    _state.value = PcViewLogic.fromReply(PcReply.Unreachable, _state.value)
                    delay(PcViewLogic.reconnectDelayMs(attempt++) ?: run { end(PcState(PcPhase.HUB_OFFLINE), revoke = false); return@launch })
                } catch (e: OutOfMemoryError) {
                    tuning = PcViewLogic.degrade(tuning) // a huge frame: ask for a smaller one, keep the last image
                    delay(1_000)
                } catch (e: RuntimeException) {
                    // Anything unexpected (bad header, closed stream...) is a hiccup of the link, never a crash
                    tuning = PcViewLogic.degrade(tuning)
                    _state.value = PcViewLogic.fromReply(PcReply.Unreachable, _state.value)
                    delay(PcViewLogic.reconnectDelayMs(attempt++) ?: run { end(PcState(PcPhase.HUB_OFFLINE), revoke = false); return@launch })
                }
            }
        }
    }

    override fun onCleared() {
        if (token != null) end(PcState(PcPhase.LOCKED), revoke = true)
    }

    class Factory(private val client: PcViewClient, private val settings: HubSettings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PcViewViewModel(client, settings) as T
    }
}
