package io.github.salex27.lumi.domain.pcview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcViewLogicTest {
    private val none = PcState(PcPhase.CHECKING)

    @Test fun statusMapsToPhases() {
        assertEquals(PcPhase.DISABLED, PcViewLogic.fromReply(PcReply.Status("disabled"), none).phase)
        assertEquals(PcPhase.LOCKED, PcViewLogic.fromReply(PcReply.Status("locked"), none).phase)
        val out = PcViewLogic.fromReply(PcReply.Status("locked_out", 90), none)
        assertEquals(PcPhase.LOCKED_OUT, out.phase)
        assertEquals(90, out.retryAfterSeconds)
    }

    @Test fun failuresMapToFriendlyPhases() {
        fun f(code: Int, e: String, r: String = "") = PcViewLogic.fromReply(PcReply.Failure(code, e, r), PcState(PcPhase.VIEWING, 5000)).phase
        assertEquals(PcPhase.DISABLED, f(403, "disabled"))
        assertEquals(PcPhase.EXPIRED, f(401, "unlock_required", "expired"))
        assertEquals(PcPhase.LOCKED, f(401, "unlock_required", "revoked"))
        assertEquals(PcPhase.LOCKED, f(401, "unlock_required", "invalid"))
        assertEquals(PcPhase.LOCKED_OUT, f(429, "locked_out"))
        assertEquals(PcPhase.HUB_OFFLINE, f(401, "unauthorized"))
        assertEquals(PcPhase.RECONNECTING, f(503, "capture_failed"))
    }

    @Test fun unreachableDependsOnWhetherWeWereViewing() {
        assertEquals(PcPhase.HUB_OFFLINE, PcViewLogic.fromReply(PcReply.Unreachable, none).phase)
        val viewing = PcViewLogic.unlocked(1000, 600)
        val r = PcViewLogic.fromReply(PcReply.Unreachable, viewing)
        assertEquals(PcPhase.RECONNECTING, r.phase)
        assertEquals(viewing.expiresAtMs, r.expiresAtMs) // the unlock clock keeps running while reconnecting
    }

    @Test fun unlockExpiresOnTheLocalClockAndExtendsNearTheEnd() {
        val s = PcViewLogic.unlocked(0, 600)
        assertEquals(600_000L, s.expiresAtMs)
        assertEquals(PcPhase.VIEWING, PcViewLogic.tick(s, 599_999).phase)
        assertEquals(PcPhase.EXPIRED, PcViewLogic.tick(s, 600_000).phase)
        assertFalse(PcViewLogic.shouldExtend(s, 100_000, false))
        assertTrue(PcViewLogic.shouldExtend(s, 500_000, false))
        assertFalse(PcViewLogic.shouldExtend(s, 500_000, paused = true)) // paused: let it run out
        assertFalse(PcViewLogic.shouldExtend(PcState(PcPhase.LOCKED), 0, false))
        assertEquals(100, PcViewLogic.secondsLeft(s, 500_000))
        assertEquals(0, PcViewLogic.secondsLeft(s, 900_000))
    }

    @Test fun degradeAsksForLessOnAWeakLink() {
        assertEquals(PcViewLogic.Tuning(1280, 50, 500), PcViewLogic.degrade(PcViewLogic.Tuning(1280, 60, 150)))
        assertEquals(PcViewLogic.Tuning(960, 30, 900), PcViewLogic.degrade(PcViewLogic.Tuning(1280, 40, 900)))
        assertEquals(PcViewLogic.Tuning(480, 30, 500), PcViewLogic.degrade(PcViewLogic.Tuning(500, 30, 100)))
    }

    @Test fun reconnectBackoffGivesUp() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), (0..5).map { PcViewLogic.reconnectDelayMs(it) })
        assertNull(PcViewLogic.reconnectDelayMs(PcViewLogic.MAX_RECONNECTS))
    }

    @Test fun qualityAdaptsToSpeed() {
        val base = PcViewLogic.Tuning(1280, 60, 150)
        assertEquals(50, PcViewLogic.adapt(base, 900, 500_000, 100).quality)
        assertEquals(65, PcViewLogic.adapt(base, 100, 50_000, 100).quality)
        assertEquals(60, PcViewLogic.adapt(base, 400, 200_000, 100).quality)
        assertEquals(960, PcViewLogic.adapt(base.copy(quality = 30), 900, 500_000, 100).width)
        assertEquals(30, PcViewLogic.adapt(base.copy(quality = 30), 900, 500_000, 100).quality) // floor
    }

    // View 1000x500 (landscape), image 2000x1000.
    private val vw = 1000f
    private val vh = 500f
    private val iw = 2000f
    private val ih = 1000f

    @Test fun fitAndFitWidth() {
        val fit = ZoomPan.fit(vw, vh, iw, ih)
        assertEquals(0.5f, fit.scale, 1e-4f)
        assertEquals(0f, fit.offX, 1e-3f)
        // portrait view 400x800: fit-to-width is smaller than the view tall, so it is centered
        val fw = ZoomPan.fitWidth(400f, 800f, iw, ih)
        assertEquals(0.2f, fw.scale, 1e-4f)
        assertEquals((800f - ih * 0.2f) / 2f, fw.offY, 1e-3f)
        // a short view: the image is taller than the view, so it shows the top
        val short = ZoomPan.fitWidth(400f, 100f, iw, ih)
        assertEquals(0f, short.offY, 1e-3f)
    }

    @Test fun pinchKeepsThePointUnderTheFingersAndNeverLeavesAGap() {
        val fit = ZoomPan.fit(vw, vh, iw, ih)
        val z = ZoomPan.gesture(fit, 2f, 0f, 0f, 500f, 250f, vw, vh, iw, ih)
        assertEquals(1f, z.scale, 1e-4f)
        // the image point under the centroid (500,250) stays there
        assertEquals(500f, z.offX + 1000f * z.scale, 1e-2f)
        assertEquals(250f, z.offY + 500f * z.scale, 1e-2f)
        val panned = ZoomPan.gesture(z, 1f, 5000f, 5000f, 0f, 0f, vw, vh, iw, ih)
        assertEquals(0f, panned.offX, 1e-3f)
        assertEquals(0f, panned.offY, 1e-3f)
        val other = ZoomPan.gesture(z, 1f, -5000f, -5000f, 0f, 0f, vw, vh, iw, ih)
        assertEquals(vw - iw * z.scale, other.offX, 1e-3f)
        assertEquals(vh - ih * z.scale, other.offY, 1e-3f)
    }

    @Test fun zoomIsClamped() {
        val fit = ZoomPan.fit(vw, vh, iw, ih)
        assertEquals(fit.scale, ZoomPan.gesture(fit, 0.1f, 0f, 0f, 0f, 0f, vw, vh, iw, ih).scale, 1e-4f)
        assertEquals(3f, ZoomPan.gesture(fit, 100f, 0f, 0f, 0f, 0f, vw, vh, iw, ih).scale, 1e-4f)
    }

    @Test fun doubleTapTogglesFit() {
        val fit = ZoomPan.fit(vw, vh, iw, ih)
        val zoomed = ZoomPan.doubleTap(fit, 300f, 200f, vw, vh, iw, ih)
        assertTrue(zoomed.scale > fit.scale * 2)
        assertEquals(fit, ZoomPan.doubleTap(zoomed, 300f, 200f, vw, vh, iw, ih))
    }

    @Test fun degenerateSizesDoNotCrash() {
        assertEquals(1f, ZoomPan.fit(0f, 0f, 0f, 0f).scale, 0f)
    }
}
