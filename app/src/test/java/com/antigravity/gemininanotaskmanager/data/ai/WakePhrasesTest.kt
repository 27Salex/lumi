package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.service.wakeword.CommandLikeness
import com.antigravity.gemininanotaskmanager.service.wakeword.WakePhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Datos reales: transcripciones LIBRES del modelo vosk-model-small-es-0.42 con audio es-ES sintético
 * (ver MEMORY.md, v3.0.3). Con la gramática cerrada anterior, 18 de estas 20 frases activaban a Lumi.
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
    fun `ninguna frase de conversación activa a Lumi`() {
        conversation.forEach { assertFalse(it, WakePhrases.isWakePhrase(it)) }
    }

    @Test
    fun `oye lumi dicho con pausa sí activa`() {
        listOf("oye lo mi", "hola alumni", "oye lumi").forEach { assertTrue(it, WakePhrases.isWakePhrase(it)) }
    }

    @Test
    fun `el nombre aprendido del usuario amplía la detección`() {
        assertFalse(WakePhrases.isWakePhrase("oye el uni"))
        assertEquals("eluni", WakePhrases.nameFromSample("oye el uni"))
        assertTrue(WakePhrases.isWakePhrase("oye el uni", listOf("eluni")))
    }

    @Test
    fun `huella de voz - misma persona parecida, otra distinta`() {
        val me = floatArrayOf(1f, 0.9f, 0.1f, 0f)
        val meAgain = floatArrayOf(0.95f, 1f, 0.15f, 0.05f)
        val other = floatArrayOf(-0.2f, 0.1f, 1f, 0.9f)
        assertTrue(WakePhrases.cosine(me, meAgain) > WakePhrases.Sensitivity.STRICT.threshold)
        assertTrue(WakePhrases.cosine(me, other) < WakePhrases.Sensitivity.RELAXED.threshold)
    }

    @Test
    fun `órdenes frente a conversación de fondo`() {
        listOf("recuérdame llamar a mamá", "qué hago hoy", "mueve la reunión al jueves", "tengo que comprar pan",
            "ya he terminado el informe", "mañana a las 5 dentista").forEach { assertTrue(it, CommandLikeness.looksLikeCommand(it)) }
        listOf("pues ayer estuvimos en la playa", "no sé tío", "vale vale", "la verdad es que sí")
            .forEach { assertFalse(it, CommandLikeness.looksLikeCommand(it)) }
    }
}
