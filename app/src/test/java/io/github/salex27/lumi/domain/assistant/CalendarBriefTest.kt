package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarBriefTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val date = LocalDate.of(2026, 10, 8)
    private val now = LocalDateTime.of(2026, 10, 8, 8, 0)
    private fun ms(h: Int, m: Int = 0, d: LocalDate = date) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(title: String, h: Int, dur: Int = 60, id: Long = title.hashCode().toLong()) =
        AgendaEvent(id, title, ms(h), ms(h) + dur * 60_000L, false, 0, "Work")
    private fun allDay(title: String, d: LocalDate, days: Int = 1) = AgendaEvent(
        99, title, d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), d.plusDays(days.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, 0, "Home"
    )

    @Test fun `all-day event of the previous day is not pulled into today by the time zone`() {
        val r = CalendarEvents.forDay(listOf(allDay("Yesterday fair", date.minusDays(1)), allDay("Holiday", date)), date, zone)
        assertEquals(listOf("Holiday"), r.map { it.title })
    }

    @Test fun `multi-day all-day event covers its middle days but not the end day`() {
        val e = allDay("Trip", date.minusDays(1), days = 3) // days -1, 0, +1
        assertEquals(1, CalendarEvents.forDay(listOf(e), date, zone).size)
        assertEquals(1, CalendarEvents.forDay(listOf(e), date.plusDays(1), zone).size)
        assertEquals(0, CalendarEvents.forDay(listOf(e), date.plusDays(2), zone).size)
    }

    @Test fun `same meeting in two calendars counts once and everything is in time order`() {
        val r = CalendarEvents.forDay(listOf(ev("Sprint", 11, id = 1), ev("Standup", 9), ev("sprint ", 11, id = 2), allDay("Holiday", date)), date, zone)
        assertEquals(listOf("Holiday", "Standup", "Sprint"), r.map { it.title })
    }

    @Test fun `overlapping meetings are both kept`() {
        val r = CalendarEvents.forDay(listOf(ev("A", 10, 90), ev("B", 11, 60)), date, zone)
        assertEquals(listOf("A", "B"), r.map { it.title })
    }

    @Test fun `an overnight event still running at the start of the day belongs to it`() {
        val night = AgendaEvent(5, "Night shift", ms(22, d = date.minusDays(1)), ms(6), false, 0, "")
        assertEquals(1, CalendarEvents.forDay(listOf(night), date, zone).size)
    }

    @Test fun `signature changes when a meeting moves and not when order does`() {
        val a = listOf(ev("A", 10), ev("B", 12)); val b = listOf(ev("B", 12), ev("A", 10))
        assertEquals(CalendarEvents.signature(a), CalendarEvents.signature(b))
        assertNotEquals(CalendarEvents.signature(a), CalendarEvents.signature(listOf(ev("A", 11), ev("B", 12))))
        assertEquals(CalendarEvents.signature(emptyList()), CalendarEvents.signature(emptyList()))
    }

    @Test fun `brief mentions meetings, first up and all-day events (english)`() {
        val tasks = listOf(Task(id = 1, title = "Call bank", dueAt = ms(14), dueHasTime = true))
        val b = DayBriefComposer.compose(date, now, tasks, listOf(ev("Sprint", 11), ev("Dentist", 9), allDay("Holiday", date)), null, "Good morning", zone = zone, lang = Lang.EN)
        assertTrue(b.body, b.body.contains("First up: «Dentist» at 9:00"))
        assertTrue(b.body, b.body.contains("2 meetings in total"))
        assertTrue(b.body, b.body.contains("All day: «Holiday»"))
    }

    @Test fun `brief with only meetings does not say there is nothing planned`() {
        val b = DayBriefComposer.compose(date, now, emptyList(), listOf(ev("Sprint", 11)), null, "Buenos días", zone = zone, lang = Lang.ES)
        assertTrue(b.body, b.body.contains("Lo primero: «Sprint» a las 11:00"))
        assertTrue(b.body, !b.body.contains("Nada apuntado") && !b.body.contains("No tienes nada apuntado"))
    }

    @Test fun `without calendar permission the brief still works from tasks`() {
        val tasks = listOf(Task(id = 1, title = "Call bank", dueAt = ms(14), dueHasTime = true))
        val b = DayBriefComposer.compose(date, now, tasks, emptyList(), null, "Hi", zone = zone, lang = Lang.EN)
        assertTrue(b.body, b.body.contains("Call bank"))
    }

    @Test fun `meetings that already ended today are left out of the brief`() {
        val later = LocalDateTime.of(2026, 10, 8, 12, 0)
        val b = DayBriefComposer.compose(date, later, emptyList(), listOf(ev("Standup", 9), ev("Review", 16)), null, "Hi", zone = zone, lang = Lang.EN)
        assertTrue(b.body, b.body.contains("«Review»") && !b.body.contains("Standup"))
    }

    @Test fun `smart alarm anchors on the first meeting and ignores all-day events`() {
        val p = AlarmPlanner.plan(date, listOf(allDay("Holiday", date), ev("Early call", 8)), emptyList(), AlarmPlanner.Config(workStartHour = null), zone, Lang.EN)!!
        assertEquals("Early call", p.anchorTitle)
    }
}
