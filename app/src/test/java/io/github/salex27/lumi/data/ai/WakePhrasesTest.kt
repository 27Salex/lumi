package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.CommandLikeness
import io.github.salex27.lumi.service.wakeword.WakePhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real data: FREE transcriptions from the vosk-model-small-es-0.42 model with synthetic es-ES audio
 * (see MEMORY.md, v3.0.3). With the old closed grammar, 18 of these 20 phrases triggered Lumi.
 */
class WakePhrasesTest {

    private val conversation = listOf(
        "oye luis mira esto", "oye lo has mirado", "hola lumbre", "oye lupe mira", "hola mi amor",
        "oye lo hicimos mucho", "hola lo mío es tuyo", "oye lo miramos luego", "oye luis más bienes",
        "hola lourdes mira", "oye lo mínimo", "hola luisa cómo estás", "hola lunes y miércoles",
        "oye lo mismo digo", "hola lo miras tu", "oye luego miramos", "hola luis mi", "oye lucía mi coche",
        "oye mi madre dice", "hola luna llena"
    )

    @Test
    fun `no conversation phrase triggers Lumi`() {
        conversation.forEach { assertFalse(it, WakePhrases.isWakePhrase(it)) }
    }

    @Test
    fun `oye lumi said with a pause triggers`() {
        listOf("oye lo mi", "hola alumni", "oye lumi").forEach { assertTrue(it, WakePhrases.isWakePhrase(it)) }
    }

    @Test
    fun `the user's learned name widens detection`() {
        assertFalse(WakePhrases.isWakePhrase("oye el uni"))
        assertEquals("eluni", WakePhrases.nameFromSample("oye el uni"))
        assertTrue(WakePhrases.isWakePhrase("oye el uni", listOf("eluni")))
    }

    @Test
    fun `voice print - same person similar, other person different`() {
        val me = floatArrayOf(1f, 0.9f, 0.1f, 0f)
        val meAgain = floatArrayOf(0.95f, 1f, 0.15f, 0.05f)
        val other = floatArrayOf(-0.2f, 0.1f, 1f, 0.9f)
        assertTrue(WakePhrases.cosine(me, meAgain) > WakePhrases.Sensitivity.STRICT.threshold)
        assertTrue(WakePhrases.cosine(me, other) < WakePhrases.Sensitivity.RELAXED.threshold)
    }

    @Test
    fun `commands versus background conversation`() {
        listOf("recuérdame llamar a mamá", "qué hago hoy", "mueve la reunión al jueves", "tengo que comprar pan",
            "ya he terminado el informe", "mañana a las 5 dentista").forEach { assertTrue(it, CommandLikeness.looksLikeCommand(it)) }
        listOf("pues ayer estuvimos en la playa", "no sé tío", "vale vale", "la verdad es que sí")
            .forEach { assertFalse(it, CommandLikeness.looksLikeCommand(it)) }
    }
}
