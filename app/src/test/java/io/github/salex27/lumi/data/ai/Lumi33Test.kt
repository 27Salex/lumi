package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.service.wakeword.WakePhrases
import io.github.salex27.lumi.service.wakeword.WakePhrases.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.3: more tolerant "Oye Lumi" (learned phrases) and places with their own address. */
class Lumi33Test {

    // ── "Oye Lumi" ─────────────────────────────────────────────────────────

    @Test
    fun `usual transcriptions of a quick oye trigger on Normal`() {
        listOf("hoy lumi", "o lumi", "oiga lumi", "¡Oye, Lumí!", "hey lomi").forEach {
            assertTrue(it, WakePhrases.matches(it, emptyList(), Sensitivity.NORMAL))
        }
    }

    @Test
    fun `Strict only accepts the exact calling words`() {
        assertTrue(WakePhrases.matches("oye lumi", emptyList(), Sensitivity.STRICT))
        assertFalse(WakePhrases.matches("hoy lumi", emptyList(), Sensitivity.STRICT))
    }

    @Test
    fun `the phrase learned in training triggers even if the model misses oye`() {
        val learned = listOf(WakePhrases.phraseFromSample("olé lumbre")!!)
        assertTrue(WakePhrases.matches("ole lumbre", learned, Sensitivity.NORMAL))
        assertFalse(WakePhrases.matches("ole lumbre", emptyList(), Sensitivity.NORMAL))
    }

    @Test
    fun `Relaxed accepts the call followed by two more words`() {
        assertTrue(WakePhrases.matches("oye lumi qué tal", emptyList(), Sensitivity.RELAXED))
        assertFalse(WakePhrases.matches("oye lumi qué tal", emptyList(), Sensitivity.NORMAL))
    }

    @Test
    fun `conversation still doesn't trigger on Normal`() {
        listOf(
            "oye luis mira esto", "hola luis mi", "hoy lunes tengo reunión", "oye lucía", "hola qué tal",
            "o luego", "hoy llueve", "oye mira", "lo mismo", "hola lucas", "hola luna llena", "hoy luna"
        ).forEach { assertFalse(it, WakePhrases.matches(it, emptyList(), Sensitivity.NORMAL)) }
    }

    // ── Places ─────────────────────────────────────────────────────────────

    @Test
    fun `old place format still works`() {
        val p = PlaceTrigger.parse("casa|LEAVE")!!
        assertEquals("casa", p.place)
        assertFalse(p.onArrive)
        assertTrue(p.notify)
        assertFalse(p.isAdHoc)
    }

    @Test
    fun `ad hoc address without reminder survives serialization`() {
        val p = PlaceTrigger("Mercadona Gran Vía", onArrive = true, notify = false, lat = 40.42, lng = -3.70, address = "Gran Vía 12, Madrid")
        val back = PlaceTrigger.parse(p.serialize())!!
        assertEquals(p, back)
        assertTrue(back.isAdHoc)
        assertNull(PlaceTrigger.parse("|ARRIVE"))
    }
}
