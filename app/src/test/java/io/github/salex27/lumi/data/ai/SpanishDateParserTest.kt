package io.github.salex27.lumi.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SpanishDateParserTest {

    // Monday 28/09/2026 10:00
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0)

    private fun parse(text: String) = SpanishDateParser.parse(text, now)

    @Test
    fun `tomorrow at 6 is 18h and cleans the title`() {
        val r = parse("llamar a mamá mañana a las 6")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 18, 0), r.dateTime)
        assertTrue(r.hasTime)
        assertEquals("llamar a mamá", r.remainingText)
    }

    @Test
    fun `time with minutes`() {
        val r = parse("cita con el dentista el martes a las 10:30")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 10, 30), r.dateTime)
        assertEquals("cita con el dentista", r.remainingText)
    }

    @Test
    fun `before Friday is a date without a time`() {
        val r = parse("entregar informe antes del viernes")!!
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 0), r.dateTime)
        assertFalse(r.hasTime)
        assertEquals("entregar informe", r.remainingText)
    }

    @Test
    fun `tomorrow morning`() {
        val r = parse("ir al banco mañana por la mañana")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 9, 0), r.dateTime)
        assertEquals("ir al banco", r.remainingText)
    }

    @Test
    fun `at 5 in the afternoon`() {
        val r = parse("reunión hoy a las 5 de la tarde")!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 17, 0), r.dateTime)
    }

    @Test
    fun `date with a month name`() {
        val r = parse("examen de física el 15 de octubre")!!
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), r.dateTime)
        assertEquals("examen de física", r.remainingText)
    }

    @Test
    fun `a past date of the year moves to next year`() {
        assertEquals(2027, parse("renovar DNI el 3 de marzo")!!.dateTime.year)
    }

    @Test
    fun `relative offset in hours`() {
        val r = parse("sacar la ropa en 2 horas")!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 12, 0), r.dateTime)
        assertEquals("sacar la ropa", r.remainingText)
    }

    @Test
    fun `a time alone that already passed goes to tomorrow`() {
        assertEquals(LocalDateTime.of(2026, 9, 29, 8, 0), parse("despertar a las 8am")!!.dateTime)
    }

    @Test
    fun `next Monday on a Monday is next week`() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), parse("revisar el próximo lunes")!!.dateTime)
    }

    @Test
    fun `loose numbers aren't times`() {
        assertNull(parse("comprar 3 panes"))
    }

    @Test
    fun `no date returns null`() {
        assertNull(parse("comprar leche"))
    }

    @Test
    fun `de manana and antes de manana mean tomorrow, not the morning`() {
        val a = parse("entregar el informe antes de mañana")!!
        assertEquals(java.time.LocalDate.of(2026, 9, 29), a.dateTime.toLocalDate())
        assertFalse(a.hasTime)
        val b = parse("reunión de mañana a las 5")!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 17, 0), b.dateTime)
        // with the article it is still the morning
        assertEquals(LocalDateTime.of(2026, 9, 29, 9, 0), parse("ir al banco mañana de la mañana")!!.dateTime)
    }

    @Test
    fun `12 at night is midnight and madrugada is early morning`() {
        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), parse("despertar a las 12 de la noche")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0), parse("fiesta mañana a las 12 de la noche")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 9, 29, 3, 0), parse("vuelo mañana a las 3 de la madrugada")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 9, 29, 23, 0), parse("cena mañana a las 11 de la noche")!!.dateTime)
    }
}
