package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.AudioContext
import io.github.salex27.lumi.service.wakeword.AudioEnvironment
import io.github.salex27.lumi.service.wakeword.OutputRoute
import io.github.salex27.lumi.service.wakeword.WakeGate
import io.github.salex27.lumi.service.wakeword.WakePhrases.Sensitivity
import io.github.salex27.lumi.service.wakeword.WakeTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #6: fewer false wakes while the phone plays audio or a Bluetooth (car) route is active. */
class WakeGateTest {

    private val speakerMedia = AudioContext(mediaPlaying = true, route = OutputRoute.SPEAKER, inCall = false)
    private val carMedia = AudioContext(mediaPlaying = true, route = OutputRoute.BLUETOOTH, inCall = false)

    @Test
    fun `in silence nothing changes`() {
        for (s in Sensitivity.entries) {
            val p = WakeGate.policy(s, AudioContext.QUIET)
            assertTrue(p.listen)
            assertEquals(s.detectorThreshold, p.threshold)
            assertEquals(s.patience, p.patience)
            assertFalse(p.alwaysConfirm)
            assertFalse(p.raised)
        }
    }

    @Test
    fun `media on the speaker raises the bar sharply and needs several windows`() {
        for (s in Sensitivity.entries) {
            val p = WakeGate.policy(s, speakerMedia)
            assertTrue(p.listen)
            assertTrue(p.threshold >= WakeGate.MEDIA_SPEAKER_THRESHOLD)
            assertTrue(p.patience >= 2)
            assertTrue(p.alwaysConfirm)
            assertTrue(p.raised)
        }
    }

    @Test
    fun `car bluetooth media is the strictest`() {
        val car = WakeGate.policy(Sensitivity.RELAXED, carMedia)
        val speaker = WakeGate.policy(Sensitivity.RELAXED, speakerMedia)
        assertTrue(car.threshold > speaker.threshold)
        assertTrue(car.patience > speaker.patience)
        assertTrue(car.alwaysConfirm)
    }

    @Test
    fun `bluetooth route without playback raises a little`() {
        val p = WakeGate.policy(Sensitivity.NORMAL, AudioContext(false, OutputRoute.BLUETOOTH, false))
        assertEquals(Sensitivity.NORMAL.detectorThreshold + WakeGate.BLUETOOTH_IDLE_BONUS, p.threshold, 1e-6f)
        assertEquals(Sensitivity.NORMAL.patience, p.patience)
        assertFalse(p.alwaysConfirm)
    }

    @Test
    fun `media on headphones keeps the bar but confirms`() {
        val p = WakeGate.policy(Sensitivity.NORMAL, AudioContext(true, OutputRoute.HEADPHONES, false))
        assertEquals(Sensitivity.NORMAL.detectorThreshold, p.threshold)
        assertTrue(p.alwaysConfirm)
        assertFalse(p.raised)
    }

    @Test
    fun `calls pause detection`() {
        val p = WakeGate.policy(Sensitivity.RELAXED, AudioContext(false, OutputRoute.SPEAKER, inCall = true))
        assertFalse(p.listen)
    }

    @Test
    fun `a series line scoring like a sound-alike no longer wakes Lumi`() {
        // "Hey Lucy" on the speaker scores ~0.4-0.6: enough on Normal in silence, not with media playing
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET)
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia)
        val scores = listOf(0.55f, 0.6f, 0.5f)
        assertTrue(fires(quiet.threshold, quiet.patience, scores))
        assertFalse(fires(media.threshold, media.patience, scores))
        // A clear "Hey Lumi" (0.95+) over several windows still wakes it
        assertTrue(fires(media.threshold, media.patience, listOf(0.96f, 0.98f, 0.97f)))
        // A single loud spike isn't enough
        assertFalse(fires(media.threshold, media.patience, listOf(0.2f, 0.95f, 0.3f)))
    }

    private fun fires(threshold: Float, patience: Int, scores: List<Float>): Boolean {
        val t = WakeTrigger(threshold, patience)
        return scores.any { t.update(it) }
    }

    @Test
    fun `borderline scores ask for confirmation unless the voice matched`() {
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET)
        assertTrue(WakeGate.needsConfirmation(quiet.threshold + 0.05f, quiet, voiceMatched = false))
        assertFalse(WakeGate.needsConfirmation(0.97f, quiet, voiceMatched = false))
        assertFalse(WakeGate.needsConfirmation(quiet.threshold + 0.05f, quiet, voiceMatched = true))
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia)
        assertTrue(WakeGate.needsConfirmation(0.99f, media, voiceMatched = false))
        assertFalse(WakeGate.needsConfirmation(0.99f, media, voiceMatched = true))
    }

    @Test
    fun `suppressed wakes are reported only when the raised bar rejected them`() {
        val base = Sensitivity.NORMAL.detectorThreshold
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia)
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET)
        assertTrue(WakeGate.suppressedByAudio(0.5f, base, media))
        assertFalse(WakeGate.suppressedByAudio(0.2f, base, media))
        assertFalse(WakeGate.suppressedByAudio(0.95f, base, media))
        assertFalse(WakeGate.suppressedByAudio(0.5f, base, quiet))
        assertFalse(WakeGate.suppressedByAudio(null, base, media))
    }

    @Test
    fun `media counts for a moment after it stops`() {
        assertTrue(WakeGate.mediaActive(true, 0L, 10_000L))
        assertTrue(WakeGate.mediaActive(false, 9_000L, 10_000L))
        assertFalse(WakeGate.mediaActive(false, 10_000L - WakeGate.MEDIA_HOLD_MS, 10_000L))
        assertFalse(WakeGate.mediaActive(false, 0L, 1_000L)) // never played
    }

    @Test
    fun `output route prefers bluetooth then headphones`() {
        // AudioDeviceInfo types: 2 speaker, 8 A2DP, 7 SCO, 4 wired headphones, 22 USB headset
        assertEquals(OutputRoute.SPEAKER, AudioEnvironment.routeOf(listOf(2)))
        assertEquals(OutputRoute.BLUETOOTH, AudioEnvironment.routeOf(listOf(2, 8)))
        assertEquals(OutputRoute.BLUETOOTH, AudioEnvironment.routeOf(listOf(2, 7, 4)))
        assertEquals(OutputRoute.HEADPHONES, AudioEnvironment.routeOf(listOf(2, 4)))
        assertEquals(OutputRoute.HEADPHONES, AudioEnvironment.routeOf(listOf(22)))
    }
}
