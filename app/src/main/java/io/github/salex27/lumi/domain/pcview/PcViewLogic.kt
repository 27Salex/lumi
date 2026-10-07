package io.github.salex27.lumi.domain.pcview

import kotlin.math.max
import kotlin.math.min

/** Where the "My PC" screen is. View only: nothing here sends input to the PC. */
enum class PcPhase { NOT_CONFIGURED, CHECKING, HUB_OFFLINE, DISABLED, LOCKED, UNLOCKING, VIEWING, EXPIRED, LOCKED_OUT, RECONNECTING, NO_SECURE_LOCK }

data class PcState(
    val phase: PcPhase = PcPhase.CHECKING,
    val expiresAtMs: Long = 0L,
    val retryAfterSeconds: Int = 0
)

/** What the hub answered (or that it could not be reached). */
sealed interface PcReply {
    data class Status(val state: String, val retryAfter: Int = 0) : PcReply
    data class Failure(val httpCode: Int, val error: String, val reason: String = "", val retryAfter: Int = 0) : PcReply
    data object Unreachable : PcReply
}

/** Pure rules of the unlock state machine (tested). The token itself never appears here. */
object PcViewLogic {
    const val EXTEND_WHEN_LEFT_MS = 120_000L
    const val MAX_RECONNECTS = 5

    fun fromReply(reply: PcReply, previous: PcState): PcState = when (reply) {
        PcReply.Unreachable ->
            if (previous.phase == PcPhase.VIEWING || previous.phase == PcPhase.RECONNECTING) PcState(PcPhase.RECONNECTING, previous.expiresAtMs)
            else PcState(PcPhase.HUB_OFFLINE)
        is PcReply.Status -> when (reply.state) {
            "disabled" -> PcState(PcPhase.DISABLED)
            "locked_out" -> PcState(PcPhase.LOCKED_OUT, retryAfterSeconds = reply.retryAfter)
            "unlocked" -> if (previous.phase == PcPhase.VIEWING) previous else PcState(PcPhase.LOCKED)
            else -> PcState(PcPhase.LOCKED)
        }
        is PcReply.Failure -> when {
            reply.error == "disabled" || reply.httpCode == 403 -> PcState(PcPhase.DISABLED)
            reply.error == "locked_out" || reply.httpCode == 429 -> PcState(PcPhase.LOCKED_OUT, retryAfterSeconds = reply.retryAfter)
            reply.error == "unlock_required" && reply.reason == "expired" -> PcState(PcPhase.EXPIRED)
            reply.error == "unlock_required" -> PcState(PcPhase.LOCKED)
            reply.httpCode == 401 -> PcState(PcPhase.HUB_OFFLINE) // wrong token / not the owner: shown as a link problem
            else -> if (previous.phase == PcPhase.VIEWING || previous.phase == PcPhase.RECONNECTING) PcState(PcPhase.RECONNECTING, previous.expiresAtMs) else previous
        }
    }

    fun unlocked(nowMs: Long, expiresInSeconds: Int) = PcState(PcPhase.VIEWING, expiresAtMs = nowMs + expiresInSeconds * 1000L)

    /** The local clock may hit the end before the hub says so: stop showing frames and ask for a new unlock. */
    fun tick(state: PcState, nowMs: Long): PcState =
        if ((state.phase == PcPhase.VIEWING || state.phase == PcPhase.RECONNECTING) && nowMs >= state.expiresAtMs) PcState(PcPhase.EXPIRED) else state

    /** Extend while the user is looking and the unlock is about to end. */
    fun shouldExtend(state: PcState, nowMs: Long, paused: Boolean): Boolean =
        state.phase == PcPhase.VIEWING && !paused && state.expiresAtMs - nowMs in 0..EXTEND_WHEN_LEFT_MS

    fun secondsLeft(state: PcState, nowMs: Long): Int = max(0, ((state.expiresAtMs - nowMs) / 1000).toInt())

    /** Reconnect with a growing pause (1, 2, 4, 8, 8 s); null = give up and show the offline message. */
    fun reconnectDelayMs(attempt: Int): Long? = if (attempt >= MAX_RECONNECTS) null else 1000L shl min(attempt, 3)

    /** Adaptive quality: the tuning changes with the time a frame took and its size. */
    data class Tuning(val width: Int, val quality: Int, val delayMs: Long)

    fun adapt(current: Tuning, frameMs: Long, bytes: Int, hubDelayMs: Long): Tuning {
        val slow = frameMs > 700 || bytes > 400_000
        val fast = frameMs < 250 && bytes < 150_000
        val quality = when { slow -> current.quality - 10; fast -> current.quality + 5; else -> current.quality }.coerceIn(30, 80)
        val width = when { slow && current.quality <= 35 -> current.width * 3 / 4; fast && current.quality >= 75 -> current.width * 5 / 4; else -> current.width }
            .coerceIn(480, 1920)
        return Tuning(width, quality, max(hubDelayMs, if (slow) 400L else 150L))
    }
}

/** Pinch-zoom and pan math, in view pixels. offX/offY are the top-left corner of the image inside the view. */
data class ViewTransform(val scale: Float, val offX: Float, val offY: Float)

object ZoomPan {
    const val MAX_ZOOM_OVER_FIT = 6f

    fun fitScale(viewW: Float, viewH: Float, imgW: Float, imgH: Float): Float =
        if (imgW <= 0f || imgH <= 0f || viewW <= 0f || viewH <= 0f) 1f else min(viewW / imgW, viewH / imgH)

    fun fitWidthScale(viewW: Float, imgW: Float): Float = if (imgW <= 0f || viewW <= 0f) 1f else viewW / imgW

    /** Smaller than the view: centered. Larger: the edges cannot leave a gap. */
    private fun clampAxis(offset: Float, view: Float, size: Float): Float =
        if (size <= view) (view - size) / 2f else offset.coerceIn(view - size, 0f)

    private fun maxScale(fit: Float) = max(fit * MAX_ZOOM_OVER_FIT, 3f)

    fun clamp(t: ViewTransform, viewW: Float, viewH: Float, imgW: Float, imgH: Float): ViewTransform {
        val fit = fitScale(viewW, viewH, imgW, imgH)
        val scale = t.scale.coerceIn(fit, maxScale(fit))
        return ViewTransform(scale, clampAxis(t.offX, viewW, imgW * scale), clampAxis(t.offY, viewH, imgH * scale))
    }

    fun fit(viewW: Float, viewH: Float, imgW: Float, imgH: Float): ViewTransform =
        clamp(ViewTransform(fitScale(viewW, viewH, imgW, imgH), 0f, 0f), viewW, viewH, imgW, imgH)

    /** Fit to width, showing the top of the screen. */
    fun fitWidth(viewW: Float, viewH: Float, imgW: Float, imgH: Float): ViewTransform =
        clamp(ViewTransform(fitWidthScale(viewW, imgW), 0f, 0f), viewW, viewH, imgW, imgH)

    /** A pinch/drag step: zoom around the centroid (cx, cy) plus a pan. */
    fun gesture(t: ViewTransform, zoom: Float, panX: Float, panY: Float, cx: Float, cy: Float,
                viewW: Float, viewH: Float, imgW: Float, imgH: Float): ViewTransform {
        val fit = fitScale(viewW, viewH, imgW, imgH)
        val newScale = (t.scale * zoom).coerceIn(fit, maxScale(fit))
        val k = newScale / t.scale
        val moved = ViewTransform(newScale, cx - (cx - t.offX) * k + panX, cy - (cy - t.offY) * k + panY)
        return clamp(moved, viewW, viewH, imgW, imgH)
    }

    /** Double tap: back to fit, or in to 2.5x fit around the tap. */
    fun doubleTap(t: ViewTransform, x: Float, y: Float, viewW: Float, viewH: Float, imgW: Float, imgH: Float): ViewTransform {
        val fit = fitScale(viewW, viewH, imgW, imgH)
        return if (t.scale > fit * 1.3f) fit(viewW, viewH, imgW, imgH)
        else gesture(t, 2.5f * fit / t.scale, 0f, 0f, x, y, viewW, viewH, imgW, imgH)
    }
}
