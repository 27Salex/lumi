package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.PhrasePrints
import io.github.salex27.lumi.service.wakeword.VoiceData
import io.github.salex27.lumi.service.wakeword.VoiceEnroller
import io.github.salex27.lumi.service.wakeword.VoiceMatch
import io.github.salex27.lumi.service.wakeword.WakePhrase
import io.github.salex27.lumi.service.wakeword.WakePhrases.Sensitivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Voice Match: per-phrase prints, the bar from the enrolment spread, legacy migration and storage. */
class VoiceMatchTest {

    private val rnd = Random(42)
    private fun base(seed: Int) = Random(seed).let { r -> FloatArray(128) { r.nextFloat() * 2 - 1 } }
    /** A "sample" of a voice: its base direction plus noise of size [noise]. */
    private fun sample(voice: FloatArray, noise: Float) = FloatArray(voice.size) { voice[it] + (rnd.nextFloat() * 2 - 1) * noise }
    private val me = base(1)
    private val other = base(2)

    @Test
    fun `the phrase is told from the transcript, otherwise null`() {
        assertEquals(WakePhrase.OYE, WakePhrase.fromTranscript("hoy lunes"))
        assertEquals(WakePhrase.OYE, WakePhrase.fromTranscript("Oye, Lumi"))
        assertEquals(WakePhrase.OYE, WakePhrase.fromTranscript("oyéndome"))
        assertEquals(WakePhrase.HEY, WakePhrase.fromTranscript("el humi"))
        assertEquals(WakePhrase.HEY, WakePhrase.fromTranscript("eh bildu me"))
        assertEquals(WakePhrase.HEY, WakePhrase.fromTranscript("ahí le hume"))
        assertEquals(WakePhrase.HEY, WakePhrase.fromTranscript("ilumina"))
        assertEquals(WakePhrase.HEY, WakePhrase.fromTranscript("ayúdame"))
        assertNull(WakePhrase.fromTranscript("home"))
        assertNull(WakePhrase.fromTranscript(""))
    }

    @Test
    fun `a wake is matched against its phrase's print, or the best of both`() {
        val hey = List(12) { sample(me, 0.5f) }
        val oyeVoice = base(3) // the same user sounds different saying the Spanish phrase
        val oye = List(12) { sample(oyeVoice, 0.5f) }
        val data = VoiceData(mapOf(WakePhrase.HEY to PhrasePrints(hey), WakePhrase.OYE to PhrasePrints(oye)))
        val wake = sample(oyeVoice, 0.5f)
        assertEquals(WakePhrase.OYE, VoiceMatch.score(wake, data, WakePhrase.OYE)!!.phrase)
        // Unknown phrase → the best of both: still the Oye print
        val best = VoiceMatch.score(wake, data, null)!!
        assertEquals(WakePhrase.OYE, best.phrase)
        assertTrue(best.similarity > 0.8f)
        // A phrase that isn't trained falls back to the best of the trained ones
        val onlyHey = VoiceData(mapOf(WakePhrase.HEY to PhrasePrints(hey)))
        assertEquals(WakePhrase.HEY, VoiceMatch.score(sample(me, 0.5f), onlyHey, WakePhrase.OYE)!!.phrase)
        assertNull(VoiceMatch.score(wake, VoiceData(emptyMap()), null))
        // Someone else scores low
        assertTrue(VoiceMatch.score(sample(other, 0.5f), data, null)!!.similarity < 0.3f)
    }

    @Test
    fun `the bar follows the spread within a fixed range around the sensitivity`() {
        val steady = PhrasePrints(List(12) { sample(me, 0.3f) })  // very similar samples
        val shaky = PhrasePrints(List(12) { sample(me, 3f) })     // samples all over the place
        val sSteady = VoiceMatch.spreadBar(steady)!!
        val sShaky = VoiceMatch.spreadBar(shaky)!!
        assertTrue("steady $sSteady > shaky $sShaky", sSteady > sShaky)
        for (s in Sensitivity.entries) {
            val hi = VoiceMatch.bar(VoiceData(mapOf(WakePhrase.HEY to steady)), s, WakePhrase.HEY)
            val lo = VoiceMatch.bar(VoiceData(mapOf(WakePhrase.HEY to shaky)), s, WakePhrase.HEY)
            assertEquals(s.threshold + VoiceMatch.SPREAD_RANGE, hi, 1e-6f)
            assertEquals(maxOf(0.25f, s.threshold - VoiceMatch.SPREAD_RANGE), lo, 1e-6f)
        }
        // A spread in range: mean − 1.5 sd, moved by the sensitivity's distance from Normal
        val loo = listOf(0.62f, 0.55f, 0.48f, 0.70f, 0.66f, 0.41f, 0.59f)
        val (m, sd) = VoiceMatch.meanSd(loo)
        assertEquals(0.5729f, m, 1e-3f)
        assertTrue(sd > 0.08f && sd < 0.10f)
    }

    @Test
    fun `few samples or a legacy print keep the fixed bar`() {
        val legacy = VoiceMatch.migrate(sample(me, 0.3f), setOf("lomi"))
        for (s in Sensitivity.entries) {
            assertEquals(s.threshold, VoiceMatch.bar(legacy, s, WakePhrase.HEY))
            assertEquals(s.threshold, VoiceMatch.bar(legacy, s, WakePhrase.OYE))
        }
        val few = VoiceData(mapOf(WakePhrase.OYE to PhrasePrints(List(4) { sample(me, 0.3f) })))
        assertNull(VoiceMatch.spreadBar(few.prints(WakePhrase.OYE)!!))
        assertEquals(Sensitivity.NORMAL.threshold, VoiceMatch.bar(few, Sensitivity.NORMAL, WakePhrase.OYE))
    }

    @Test
    fun `a migrated 3-sample profile matches both phrases exactly as before`() {
        val old = sample(me, 0.3f)
        val data = VoiceMatch.migrate(old, setOf("lomi"))
        val wake = sample(me, 0.5f)
        val before = io.github.salex27.lumi.service.wakeword.WakePhrases.cosine(wake, old)
        for (ph in listOf(WakePhrase.HEY, WakePhrase.OYE, null)) {
            assertEquals(before, VoiceMatch.score(wake, data, ph)!!.similarity, 1e-6f)
        }
        assertEquals(setOf("lomi"), data.names)
        assertTrue(data.phrases.values.all { it.legacy })
    }

    @Test
    fun `training one phrase replaces it and keeps the other`() {
        val legacy = VoiceMatch.migrate(sample(me, 0.3f), emptySet())
        val fresh = List(12) { sample(me, 0.4f) }
        val data = legacy.withPhrase(WakePhrase.HEY, fresh, setOf("eh"))
        assertEquals(12, data.prints(WakePhrase.HEY)!!.enrolled.size)
        assertTrue(!data.prints(WakePhrase.HEY)!!.legacy)
        assertTrue(data.prints(WakePhrase.OYE)!!.legacy)
        assertNull(VoiceData(mapOf(WakePhrase.HEY to PhrasePrints(fresh))).without(WakePhrase.HEY))
    }

    @Test
    fun `an enrolment sample unlike the rest is dropped`() {
        val prints = List(11) { sample(me, 0.4f) } + listOf(sample(other, 0.1f))
        val kept = VoiceMatch.dropOutliers(prints)
        assertEquals(11, kept.size)
        // Never more than two, and nothing with few samples
        assertEquals(11, VoiceMatch.dropOutliers(List(10) { sample(me, 0.4f) } + List(3) { sample(base(9 + it), 0.1f) }).size)
        assertEquals(3, VoiceMatch.dropOutliers(List(2) { sample(me, 0.4f) } + listOf(sample(other, 0.1f))).size)
    }

    @Test
    fun `storage round-trips and rejects garbage`() {
        val data = VoiceData(
            mapOf(
                WakePhrase.HEY to PhrasePrints(List(3) { sample(me, 0.4f) }, List(2) { sample(me, 0.4f) }),
                WakePhrase.OYE to PhrasePrints(listOf(sample(me, 0.4f)), legacy = true)
            ),
            negatives = listOf(sample(other, 0.4f)), names = setOf("lomi", "oyelumi")
        )
        val back = VoiceMatch.decode(VoiceMatch.encode(data))
        assertNotNull(back)
        back!!
        assertEquals(data.names, back.names)
        for (ph in WakePhrase.entries) {
            val a = data.phrases.getValue(ph); val b = back.phrases.getValue(ph)
            assertEquals(a.legacy, b.legacy)
            a.enrolled.zip(b.enrolled).forEach { (x, y) -> assertArrayEquals(x, y, 0f) }
            a.learned.zip(b.learned).forEach { (x, y) -> assertArrayEquals(x, y, 0f) }
            assertEquals(a.learned.size, b.learned.size)
        }
        assertArrayEquals(data.negatives[0], back.negatives[0], 0f)
        assertNull(VoiceMatch.decode("hello"))
        assertNull(VoiceMatch.decode("version=2\nHEY.enrolled=1,2,x"))
        assertNull(VoiceMatch.decode("version=2\nnames=a"))
    }

    @Test
    fun `training asks for varied conditions and ends with the optional music round`() {
        val conditions = (0 until VoiceEnroller.SAMPLES).map { VoiceEnroller.conditionFor(it) }
        assertEquals(VoiceMatch.SAMPLES_PER_PHRASE, conditions.size)
        assertEquals(VoiceEnroller.Condition.entries.toSet(), conditions.toSet())
        assertEquals(VoiceEnroller.Condition.MUSIC, conditions.last())
        assertEquals(VoiceEnroller.MIN_SAMPLES, conditions.indexOf(VoiceEnroller.Condition.MUSIC))
    }
}
