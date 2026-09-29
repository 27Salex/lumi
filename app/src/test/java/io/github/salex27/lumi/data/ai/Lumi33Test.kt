package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.service.wakeword.WakePhrases
import io.github.salex27.lumi.service.wakeword.WakePhrases.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.3: «Oye Lumi» más tolerante (frases aprendidas) y lugares con dirección propia. */
class Lumi33Test {

    // ── «Oye Lumi» ─────────────────────────────────────────────────────────

    @Test
    fun `transcripciones habituales de un oye rápido activan en Normal`() {
        listOf("hoy lumi", "o lumi", "oiga lumi", "¡Oye, Lumí!", "hey lomi").forEach {
            assertTrue(it, WakePhrases.matches(it, emptyList(), Sensitivity.NORMAL))
        }
    }

    @Test
    fun `Estricta solo acepta las palabras de llamada exactas`() {
        assertTrue(WakePhrases.matches("oye lumi", emptyList(), Sensitivity.STRICT))
        assertFalse(WakePhrases.matches("hoy lumi", emptyList(), Sensitivity.STRICT))
    }

    @Test
    fun `la frase aprendida al entrenar activa aunque el modelo no oiga oye`() {
        val learned = listOf(WakePhrases.phraseFromSample("olé lumbre")!!)
        assertTrue(WakePhrases.matches("ole lumbre", learned, Sensitivity.NORMAL))
        assertFalse(WakePhrases.matches("ole lumbre", emptyList(), Sensitivity.NORMAL))
    }

    @Test
    fun `Relajada acepta la llamada seguida de dos palabras más`() {
        assertTrue(WakePhrases.matches("oye lumi qué tal", emptyList(), Sensitivity.RELAXED))
        assertFalse(WakePhrases.matches("oye lumi qué tal", emptyList(), Sensitivity.NORMAL))
    }

    @Test
    fun `la conversación sigue sin activar en Normal`() {
        listOf(
            "oye luis mira esto", "hola luis mi", "hoy lunes tengo reunión", "oye lucía", "hola qué tal",
            "o luego", "hoy llueve", "oye mira", "lo mismo", "hola lucas", "hola luna llena", "hoy luna"
        ).forEach { assertFalse(it, WakePhrases.matches(it, emptyList(), Sensitivity.NORMAL)) }
    }

    // ── Lugares ────────────────────────────────────────────────────────────

    @Test
    fun `formato antiguo de lugar sigue funcionando`() {
        val p = PlaceTrigger.parse("casa|LEAVE")!!
        assertEquals("casa", p.place)
        assertFalse(p.onArrive)
        assertTrue(p.notify)
        assertFalse(p.isAdHoc)
    }

    @Test
    fun `dirección suelta y sin aviso se serializan y vuelven igual`() {
        val p = PlaceTrigger("Mercadona Gran Vía", onArrive = true, notify = false, lat = 40.42, lng = -3.70, address = "Gran Vía 12, Madrid")
        val back = PlaceTrigger.parse(p.serialize())!!
        assertEquals(p, back)
        assertTrue(back.isAdHoc)
        assertNull(PlaceTrigger.parse("|ARRIVE"))
    }
}
