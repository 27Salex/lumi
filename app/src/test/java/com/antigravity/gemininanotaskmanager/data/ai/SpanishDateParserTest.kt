package com.antigravity.gemininanotaskmanager.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SpanishDateParserTest {

    // Lunes 28/09/2026 10:00
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0)

    private fun parse(text: String) = SpanishDateParser.parse(text, now)

    @Test
    fun `mañana a las 6 es mañana a las 18h y limpia el título`() {
        val r = parse("llamar a mamá mañana a las 6")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 18, 0), r.dateTime)
        assertTrue(r.hasTime)
        assertEquals("llamar a mamá", r.remainingText)
    }

    @Test
    fun `hora con minutos`() {
        val r = parse("cita con el dentista el martes a las 10:30")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 10, 30), r.dateTime)
        assertEquals("cita con el dentista", r.remainingText)
    }

    @Test
    fun `antes del viernes es fecha sin hora`() {
        val r = parse("entregar informe antes del viernes")!!
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 0), r.dateTime)
        assertFalse(r.hasTime)
        assertEquals("entregar informe", r.remainingText)
    }

    @Test
    fun `mañana por la mañana`() {
        val r = parse("ir al banco mañana por la mañana")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 9, 0), r.dateTime)
        assertEquals("ir al banco", r.remainingText)
    }

    @Test
    fun `a las 5 de la tarde`() {
        val r = parse("reunión hoy a las 5 de la tarde")!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 17, 0), r.dateTime)
    }

    @Test
    fun `fecha con nombre de mes`() {
        val r = parse("examen de física el 15 de octubre")!!
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), r.dateTime)
        assertEquals("examen de física", r.remainingText)
    }

    @Test
    fun `fecha pasada del año pasa al siguiente`() {
        assertEquals(2027, parse("renovar DNI el 3 de marzo")!!.dateTime.year)
    }

    @Test
    fun `offset relativo en horas`() {
        val r = parse("sacar la ropa en 2 horas")!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 12, 0), r.dateTime)
        assertEquals("sacar la ropa", r.remainingText)
    }

    @Test
    fun `solo hora ya pasada va a mañana`() {
        assertEquals(LocalDateTime.of(2026, 9, 29, 8, 0), parse("despertar a las 8am")!!.dateTime)
    }

    @Test
    fun `el proximo lunes siendo lunes es la semana que viene`() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), parse("revisar el próximo lunes")!!.dateTime)
    }

    @Test
    fun `numeros sueltos no son horas`() {
        assertNull(parse("comprar 3 panes"))
    }

    @Test
    fun `sin fecha devuelve null`() {
        assertNull(parse("comprar leche"))
    }
}
