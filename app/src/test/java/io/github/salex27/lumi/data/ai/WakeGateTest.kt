package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.AudioContext
import io.github.salex27.lumi.service.wakeword.AudioEnvironment
import io.github.salex27.lumi.service.wakeword.OutputRoute
import io.github.salex27.lumi.service.wakeword.WakeGate
import io.github.salex27.lumi.service.wakeword.WakePhrases.Sensitivity
import io.github.salex27.lumi.service.wakeword.WakeTrigger
import io.github.salex27.lumi.service.wakeword.WakeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #6: fewer false wakes while the phone plays audio or a Bluetooth (car) route is active. */
class WakeGateTest {

    private val speakerMedia = AudioContext(mediaPlaying = true, route = OutputRoute.SPEAKER, inCall = false)
    private val carMedia = AudioContext(mediaPlaying = true, route = OutputRoute.BLUETOOTH, inCall = false)
    private val carIdle = AudioContext(mediaPlaying = false, route = OutputRoute.BLUETOOTH, inCall = false)
    private val headphones = AudioContext(mediaPlaying = true, route = OutputRoute.HEADPHONES, inCall = false)

    @Test
    fun `in silence nothing changes, with or without a trained voice`() {
        for (s in Sensitivity.entries) for (voice in listOf(false, true)) {
            val p = WakeGate.policy(s, AudioContext.QUIET, voice)
            assertTrue(p.listen)
            assertEquals(s.detectorThreshold, p.threshold)
            assertEquals(s.patience, p.patience)
            assertFalse(p.alwaysConfirm)
            assertFalse(p.raised)
            assertFalse(p.voiceRequired)
            assertEquals(0f, p.voiceRelax)
        }
    }

    @Test
    fun `with a trained voice, media keeps the user's bar and one window and Voice Match decides`() {
        for (s in Sensitivity.entries) for (ctx in listOf(speakerMedia, carMedia)) {
            val p = WakeGate.policy(s, ctx, voiceMatch = true)
            assertTrue(p.listen)
            assertEquals(s.detectorThreshold, p.threshold)
            assertEquals(1, p.patience)
            assertTrue(p.voiceRequired)
            assertFalse(p.alwaysConfirm)
            assertFalse(p.raised)
            assertEquals(WakeGate.MEDIA_VOICE_RELAX, p.voiceRelax)
            // Without a print: the old media bar, one window, and confirmation
            val np = p.noPrint!!
            assertEquals(WakeGate.MEDIA_NO_PRINT_THRESHOLD, np.threshold)
            assertTrue(np.alwaysConfirm)
        }
    }

    @Test
    fun `with a trained voice, an idle bluetooth route needs the voice but doesn't relax it`() {
        val p = WakeGate.policy(Sensitivity.NORMAL, carIdle, voiceMatch = true)
        assertEquals(Sensitivity.NORMAL.detectorThreshold, p.threshold)
        assertTrue(p.voiceRequired)
        assertEquals(0f, p.voiceRelax)
        // Without a print it behaves as before Voice Match (a little higher bar, confirm only when borderline)
        assertEquals(WakeGate.policy(Sensitivity.NORMAL, carIdle, voiceMatch = false), p.noPrint)
        assertEquals(WakeVerdict.Accept(false, false), WakeGate.verdict(0.9f, p, true, null, 0.42f))
        assertEquals(WakeVerdict.Accept(true, false), WakeGate.verdict(0.5f, p, true, null, 0.42f))
        assertEquals(WakeVerdict.RejectNoPrint, WakeGate.verdict(0.40f, p, true, null, 0.42f))
    }

    @Test
    fun `without a trained voice, media raises the bar modestly, one window, and always confirms`() {
        for (s in Sensitivity.entries) for (ctx in listOf(speakerMedia, carMedia)) {
            val p = WakeGate.policy(s, ctx, voiceMatch = false)
            assertEquals(maxOf(s.detectorThreshold, WakeGate.NO_PROFILE_MEDIA_THRESHOLD), p.threshold)
            assertEquals(1, p.patience)
            assertTrue(p.alwaysConfirm)
            assertFalse(p.voiceRequired)
            assertEquals(s.detectorThreshold < WakeGate.NO_PROFILE_MEDIA_THRESHOLD, p.raised)
        }
        // Strict is already at the media bar
        assertEquals(0.50f, WakeGate.policy(Sensitivity.STRICT, speakerMedia, false).threshold)
    }

    @Test
    fun `bluetooth route without playback raises a little, never above the media bar`() {
        val p = WakeGate.policy(Sensitivity.NORMAL, carIdle, voiceMatch = false)
        assertEquals(Sensitivity.NORMAL.detectorThreshold + WakeGate.BLUETOOTH_IDLE_BONUS, p.threshold, 1e-6f)
        assertEquals(Sensitivity.NORMAL.patience, p.patience)
        assertFalse(p.alwaysConfirm)
        assertTrue(p.raised)
        val strict = WakeGate.policy(Sensitivity.STRICT, carIdle, voiceMatch = false)
        assertEquals(Sensitivity.STRICT.detectorThreshold, strict.threshold)
        assertFalse(strict.raised)
    }

    @Test
    fun `media on headphones keeps the bar and confirms unless the voice matches`() {
        for (voice in listOf(false, true)) {
            val p = WakeGate.policy(Sensitivity.NORMAL, headphones, voice)
            assertEquals(Sensitivity.NORMAL.detectorThreshold, p.threshold)
            assertTrue(p.alwaysConfirm)
            assertFalse(p.raised)
            assertFalse(p.voiceRequired)
        }
    }

    @Test
    fun `calls pause detection`() {
        for (voice in listOf(false, true)) {
            assertFalse(WakeGate.policy(Sensitivity.RELAXED, AudioContext(false, OutputRoute.SPEAKER, inCall = true), voice).listen)
        }
    }

    @Test
    fun `without a voice, a series line scoring like a sound-alike no longer wakes Lumi`() {
        // "Hey Lucy" on the speaker scores ~0.4 on average: enough on Normal in silence, not with media playing
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET, false)
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia, false)
        val scores = listOf(0.42f, 0.45f, 0.4f)
        assertTrue(fires(quiet.threshold, quiet.patience, scores))
        assertFalse(fires(media.threshold, media.patience, scores))
        // A clear "Hey Lumi" in a single window still wakes it (and gets confirmed)
        assertTrue(fires(media.threshold, media.patience, listOf(0.2f, 0.7f, 0.3f)))
    }

    private fun fires(threshold: Float, patience: Int, scores: List<Float>): Boolean {
        val t = WakeTrigger(threshold, patience)
        return scores.any { t.update(it) }
    }

    @Test
    fun `with media, Voice Match is required and a match skips the confirmation`() {
        val p = WakeGate.policy(Sensitivity.NORMAL, speakerMedia, voiceMatch = true)
        val bar = Sensitivity.NORMAL.threshold
        // The user's voice mixed with the TV: a little below their usual bar still passes
        assertEquals(WakeVerdict.Accept(false, true), WakeGate.verdict(0.4f, p, true, bar - 0.03f, bar))
        // An actor saying "Hey Lucy": the print is someone else's
        assertTrue(WakeGate.verdict(0.9f, p, true, 0.08f, bar) is WakeVerdict.RejectVoice)
        assertTrue(WakeGate.verdict(0.9f, p, true, bar - WakeGate.MEDIA_VOICE_RELAX - 0.01f, bar) is WakeVerdict.RejectVoice)
        // No print came out: only a strong score goes on, and it is confirmed
        assertEquals(WakeVerdict.RejectNoPrint, WakeGate.verdict(0.67f, p, true, null, bar))
        assertEquals(WakeVerdict.Accept(true, false), WakeGate.verdict(0.85f, p, true, null, bar))
    }

    @Test
    fun `in quiet the voice bar isn't relaxed and a mismatch is rejected`() {
        val p = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET, voiceMatch = true)
        val bar = Sensitivity.NORMAL.threshold
        assertTrue(WakeGate.verdict(0.9f, p, true, bar - 0.01f, bar) is WakeVerdict.RejectVoice)
        assertEquals(WakeVerdict.Accept(false, true), WakeGate.verdict(0.36f, p, true, bar, bar))
        // A profile but no print (model missing): as before, borderline scores confirm
        assertEquals(WakeVerdict.Accept(true, false), WakeGate.verdict(0.36f, p, true, null, bar))
        assertEquals(WakeVerdict.Accept(false, false), WakeGate.verdict(0.97f, p, true, null, bar))
    }

    @Test
    fun `without a voice the verdict only decides the confirmation`() {
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia, voiceMatch = false)
        assertEquals(WakeVerdict.Accept(true, false), WakeGate.verdict(0.99f, media, false, null, 0.42f))
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET, voiceMatch = false)
        assertEquals(WakeVerdict.Accept(false, false), WakeGate.verdict(0.97f, quiet, false, null, 0.42f))
        assertEquals(WakeVerdict.Accept(true, false), WakeGate.verdict(0.40f, quiet, false, null, 0.42f))
    }

    @Test
    fun `the voice bar never goes below the floor`() {
        val p = WakeGate.policy(Sensitivity.RELAXED, carMedia, voiceMatch = true)
        // Media: relaxed, but never below the media floor (Relaxed 0.30 → 0.35, stricter than in quiet)
        assertEquals(WakeGate.MEDIA_MIN_VOICE_THRESHOLD, WakeGate.voiceBar(0.30f, p), 1e-6f)
        assertEquals(0.37f, WakeGate.voiceBar(0.42f, p), 1e-6f)
        // Quiet: the user's bar as is, never below the global floor
        val quiet = WakeGate.policy(Sensitivity.RELAXED, AudioContext.QUIET, voiceMatch = true)
        assertEquals(0.30f, WakeGate.voiceBar(0.30f, quiet), 1e-6f)
        assertEquals(WakeGate.MIN_VOICE_THRESHOLD, WakeGate.voiceBar(0.2f, quiet), 1e-6f)
    }

    @Test
    fun `borderline scores ask for confirmation unless the voice matched`() {
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET, false)
        assertTrue(WakeGate.needsConfirmation(quiet.threshold + 0.05f, quiet, voiceMatched = false))
        assertFalse(WakeGate.needsConfirmation(0.97f, quiet, voiceMatched = false))
        assertFalse(WakeGate.needsConfirmation(quiet.threshold + 0.05f, quiet, voiceMatched = true))
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia, false)
        assertTrue(WakeGate.needsConfirmation(0.99f, media, voiceMatched = false))
        assertFalse(WakeGate.needsConfirmation(0.99f, media, voiceMatched = true))
    }

    @Test
    fun `suppressed wakes are reported only when the raised bar rejected them`() {
        val base = Sensitivity.NORMAL.detectorThreshold
        val media = WakeGate.policy(Sensitivity.NORMAL, speakerMedia, false)
        val quiet = WakeGate.policy(Sensitivity.NORMAL, AudioContext.QUIET, false)
        assertTrue(WakeGate.suppressedByAudio(0.45f, base, media))
        assertFalse(WakeGate.suppressedByAudio(0.2f, base, media))
        assertFalse(WakeGate.suppressedByAudio(0.95f, base, media))
        assertFalse(WakeGate.suppressedByAudio(0.45f, base, quiet))
        assertFalse(WakeGate.suppressedByAudio(null, base, media))
        // With Voice Match nothing is suppressed by the bar: the voice decides
        assertFalse(WakeGate.suppressedByAudio(0.45f, base, WakeGate.policy(Sensitivity.NORMAL, speakerMedia, true)))
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
