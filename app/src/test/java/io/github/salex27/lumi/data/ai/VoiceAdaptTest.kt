package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.PhrasePrints
import io.github.salex27.lumi.service.wakeword.VoiceData
import io.github.salex27.lumi.service.wakeword.VoiceMatch
import io.github.salex27.lumi.service.wakeword.VoiceMatch.WakeSample
import io.github.salex27.lumi.service.wakeword.WakeFeedback
import io.github.salex27.lumi.service.wakeword.WakePhrase
import io.github.salex27.lumi.service.wakeword.WakePhrases.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Adaptive Voice Match: learning from confirmed wakes, calibration from dismissed ones, guards against poisoning. */
class VoiceAdaptTest {

    private val rnd = Random(7)
    private fun base(seed: Int) = Random(seed).let { r -> FloatArray(128) { r.nextFloat() * 2 - 1 } }
    private fun sample(voice: FloatArray, noise: Float) = FloatArray(voice.size) { voice[it] + (rnd.nextFloat() * 2 - 1) * noise }
    private val me = base(1)
    private val tv = base(2)
    private val data = VoiceData(mapOf(WakePhrase.HEY to PhrasePrints(List(12) { sample(me, 0.6f) })))

    private fun wake(print: FloatArray, score: Float = 0.9f, media: Boolean = false): WakeSample {
        val s = VoiceMatch.score(print, data, WakePhrase.HEY)!!
        return WakeSample(print, WakePhrase.HEY, score, s.similarity, VoiceMatch.bar(data, Sensitivity.NORMAL, WakePhrase.HEY), media)
    }

    @Test
    fun `a clear confirmed wake is learned for its phrase`() {
        val w = wake(sample(me, 0.5f))
        assertTrue(w.similarity >= w.bar + VoiceMatch.LEARN_MARGIN)
        val next = VoiceMatch.learn(data, w)
        assertEquals(1, next.prints(WakePhrase.HEY)!!.learned.size)
        assertEquals(12, next.prints(WakePhrase.HEY)!!.enrolled.size)
    }

    @Test
    fun `doubtful wakes are never learned`() {
        // Low detector score
        assertSame(data, VoiceMatch.learn(data, wake(sample(me, 0.5f), score = 0.45f)))
        // Voice just above the bar (not a clear match)
        val w = wake(sample(me, 0.5f))
        assertSame(data, VoiceMatch.learn(data, WakeSample(w.print, w.phrase, 0.9f, w.bar + 0.05f, w.bar, false)))
        // While media plays it needs a bigger margin
        assertSame(data, VoiceMatch.learn(data, WakeSample(w.print, w.phrase, 0.9f, w.bar + 0.10f, w.bar, true)))
        assertTrue(VoiceMatch.learn(data, WakeSample(w.print, w.phrase, 0.9f, w.bar + 0.16f, w.bar, true)) !== data)
        // A phrase that isn't trained
        assertSame(data, VoiceMatch.learn(data, WakeSample(w.print, WakePhrase.OYE, 0.9f, 0.9f, 0.4f, false)))
    }

    @Test
    fun `learned prints are a bounded rolling set`() {
        var d = data
        repeat(VoiceMatch.LEARNED_MAX + 5) { d = VoiceMatch.learn(d, wake(sample(me, 0.5f))) }
        assertEquals(VoiceMatch.LEARNED_MAX, d.prints(WakePhrase.HEY)!!.learned.size)
    }

    @Test
    fun `learning real wakes pulls the print towards real conditions`() {
        // Real wakes sound a bit different from the training (room, distance): a shifted version of the voice
        val real = FloatArray(128) { me[it] + 0.6f * tv[it] * if (it % 3 == 0) 1f else 0f }
        val before = VoiceMatch.score(sample(real, 0.3f), data, WakePhrase.HEY)!!.similarity
        var d = data
        repeat(8) {
            val p = sample(real, 0.3f)
            val s = VoiceMatch.score(p, d, WakePhrase.HEY)!!.similarity
            d = VoiceMatch.learn(d, WakeSample(p, WakePhrase.HEY, 0.9f, s, 0.3f, false))
        }
        val after = VoiceMatch.score(sample(real, 0.3f), d, WakePhrase.HEY)!!.similarity
        assertTrue("before $before after $after", after > before)
    }

    @Test
    fun `dismissed wakes only calibrate the bar, bounded, and never into the user's range`() {
        val bar0 = VoiceMatch.bar(data, Sensitivity.NORMAL, WakePhrase.HEY)
        // One dismissed wake isn't enough
        var d = VoiceMatch.reject(data, wake(sample(tv, 0.3f)))
        assertEquals(bar0, VoiceMatch.bar(d, Sensitivity.NORMAL, WakePhrase.HEY), 1e-6f)
        assertTrue(d.prints(WakePhrase.HEY)!!.learned.isEmpty()) // never added to the print
        // TV voices far below the bar change nothing (the bar already rejects them)
        repeat(5) { d = VoiceMatch.reject(d, wake(sample(tv, 0.3f))) }
        assertEquals(bar0, VoiceMatch.bar(d, Sensitivity.NORMAL, WakePhrase.HEY), 1e-6f)
        // Dismissed wakes close to the bar raise it just above them, by at most MAX_NEGATIVE_RAISE
        val c = VoiceMatch.centroid(data.prints(WakePhrase.HEY)!!)
        val near = List(4) { mix(c, sample(tv, 0.2f), bar0 + 0.02f) }
        var dn = data
        near.forEach { p -> dn = VoiceMatch.reject(dn, WakeSample(p, WakePhrase.HEY, 0.9f, 0f, bar0, false)) }
        val raised = VoiceMatch.bar(dn, Sensitivity.NORMAL, WakePhrase.HEY)
        assertTrue("raised $raised vs $bar0", raised > bar0)
        assertTrue(raised <= bar0 + VoiceMatch.MAX_NEGATIVE_RAISE + 1e-6f)
        // The user's own voice dismissed (changed their mind) doesn't move it
        var du = data
        repeat(5) { du = VoiceMatch.reject(du, wake(sample(me, 0.2f))) }
        assertEquals(bar0, VoiceMatch.bar(du, Sensitivity.NORMAL, WakePhrase.HEY), 1e-6f)
        // Bounded list
        repeat(30) { du = VoiceMatch.reject(du, wake(sample(tv, 0.3f))) }
        assertEquals(VoiceMatch.NEGATIVES_MAX, du.negatives.size)
    }

    /** A print whose cosine to [c] is about [target], built from [c] and an unrelated [other]. */
    private fun mix(c: FloatArray, other: FloatArray, target: Float): FloatArray {
        var lo = 0f; var hi = 1f
        repeat(40) {
            val a = (lo + hi) / 2
            val v = FloatArray(c.size) { a * c[it] / norm(c) + (1 - a) * other[it] / norm(other) }
            if (VoiceMatch.cosine(v, c) < target) lo = a else hi = a
        }
        return FloatArray(c.size) { hi * c[it] / norm(c) + (1 - hi) * other[it] / norm(other) }
    }

    private fun norm(v: FloatArray) = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()

    @Test
    fun `reset forgets learned and dismissed data but keeps the training`() {
        var d = VoiceMatch.learn(data, wake(sample(me, 0.5f)))
        d = VoiceMatch.reject(d, wake(sample(tv, 0.3f)))
        val f = VoiceMatch.forget(d)
        assertTrue(f.prints(WakePhrase.HEY)!!.learned.isEmpty())
        assertTrue(f.negatives.isEmpty())
        assertEquals(12, f.prints(WakePhrase.HEY)!!.enrolled.size)
        // And storage keeps learned prints and negatives
        val back = VoiceMatch.decode(VoiceMatch.encode(d))!!
        assertEquals(1, back.prints(WakePhrase.HEY)!!.learned.size)
        assertEquals(1, back.negatives.size)
    }

    @Test
    fun `only the first outcome of the latest wake counts, within its window`() {
        val fb = WakeFeedback()
        val w = wake(sample(me, 0.5f))
        val id = fb.begin(w, now = 1_000)
        assertNull(fb.outcome(id + 1, true, 2_000)) // unknown id
        assertNotNull(fb.outcome(id, true, 2_000))
        assertNull(fb.outcome(id, false, 2_500))    // already used
        // A dismissal only counts if it came quickly
        val id2 = fb.begin(w, now = 10_000)
        assertNull(fb.outcome(id2, false, 10_000 + WakeFeedback.DISMISS_WINDOW_MS + 1))
        val id3 = fb.begin(w, now = 50_000)
        assertNotNull(fb.outcome(id3, true, 50_000 + 40_000))
        // A newer wake replaces the older one
        val old = fb.begin(w, now = 100_000)
        val new = fb.begin(w, now = 101_000)
        assertNull(fb.outcome(old, true, 102_000))
        assertNotNull(fb.outcome(new, true, 102_000))
    }
}
