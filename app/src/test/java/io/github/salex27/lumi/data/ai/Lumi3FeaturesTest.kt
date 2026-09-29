package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.LinkedMeeting
import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.reminder.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class Lumi3FeaturesTest {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0) // lunes
    private fun parse(t: String) = engine.parse(t, now)

    // ── Recurrence ─────────────────────────────────────────────────────────

    @Test
    fun `gym every Monday and Thursday at 19`() {
        val cmd = parse("gimnasio cada lunes y jueves a las 19:00")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Gimnasio", cmd.targetTitle)
        val r = Recurrence.parse(cmd.recurrence)!!
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), r.daysOfWeek)
        assertEquals("2026-09-28T19:00", cmd.dueDate) // today is Monday and it isn't 19:00 yet
    }

    @Test
    fun `pay rent on the 1st of every month`() {
        val cmd = parse("pagar el alquiler el día 1 de cada mes")
        val r = Recurrence.parse(cmd.recurrence)!!
        assertEquals(Recurrence.Frequency.MONTHLY, r.frequency)
        assertEquals(1, r.dayOfMonth)
        assertEquals("2026-10-01", cmd.dueDate)
        assertEquals("Pagar el alquiler", cmd.targetTitle)
    }

    @Test
    fun `next occurrence`() {
        val weekly = Recurrence(Recurrence.Frequency.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        assertEquals(LocalDate.of(2026, 10, 1), weekly.next(LocalDate.of(2026, 9, 28)))
        assertEquals(LocalDate.of(2026, 10, 5), weekly.next(LocalDate.of(2026, 10, 1)))
        val monthly31 = Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = 31)
        assertEquals(LocalDate.of(2026, 10, 31), monthly31.next(LocalDate.of(2026, 9, 30)))
        assertEquals(LocalDate.of(2026, 11, 30), monthly31.next(LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDate.of(2026, 10, 2), Recurrence(Recurrence.Frequency.WEEKDAYS).next(LocalDate.of(2026, 10, 1)))
        assertEquals(LocalDate.of(2026, 10, 5), Recurrence(Recurrence.Frequency.WEEKDAYS).next(LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun `serialize and read recurrence`() {
        val r = Recurrence(Recurrence.Frequency.WEEKLY, setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY))
        assertEquals("WEEKLY:MO,TH", r.serialize())
        assertEquals(r, Recurrence.parse("WEEKLY:MO,TH"))
        assertEquals("Cada lunes y jueves", r.label())
    }

    // ── Rescheduling ───────────────────────────────────────────────────────

    @Test
    fun `move the meeting to Thursday`() {
        val cmd = parse("mueve la reunión al jueves")
        assertEquals(TaskAICommand.RESCHEDULE, cmd.action)
        assertEquals("reunión", cmd.targetTitle)
        assertEquals("2026-10-01", cmd.dueDate)
    }

    @Test
    fun `postpone the dentist a week`() {
        val cmd = parse("pospón lo del dentista una semana")
        assertEquals(TaskAICommand.RESCHEDULE, cmd.action)
        assertEquals("dentista", cmd.targetTitle)
        assertEquals(7 * 24 * 60, cmd.postponeMinutes)
    }

    @Test
    fun `moving the call an hour earlier is negative`() {
        assertEquals(-60, parse("adelanta la llamada una hora").postponeMinutes)
    }

    @Test
    fun `infinitive pasar is a new task`() {
        assertEquals(TaskAICommand.CREATE, parse("pasar la ITV mañana").action)
    }

    // ── Brain dump ─────────────────────────────────────────────────────────

    @Test
    fun `several tasks in one sentence share the date`() {
        val cmd = parse("mañana tengo que comprar pan, llamar a Ana y acabar el informe")
        assertEquals(TaskAICommand.CREATE_MANY, cmd.action)
        assertEquals(3, cmd.items.size)
        assertEquals(listOf("Comprar pan", "Llamar a Ana", "Acabar el informe"), cmd.items.map { it.targetTitle })
        assertTrue(cmd.items.all { it.dueDate == "2026-09-29" })
    }

    @Test
    fun `buying bread and milk is a single task`() {
        val cmd = parse("comprar pan y leche")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Comprar pan y leche", cmd.targetTitle)
    }

    // ── Reminders ──────────────────────────────────────────────────────────

    @Test
    fun `remind me 2 hours and 10 minutes before`() {
        val cmd = parse("cita con el médico el jueves a las 11, avísame 2 horas antes y 10 minutos antes")
        assertEquals(listOf(120, 10), cmd.remindBeforeMinutes)
        assertEquals("Cita con el médico", cmd.targetTitle)
    }

    @Test
    fun `automatic policy for an important appointment`() {
        val zone = ZoneId.of("UTC")
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val due = LocalDateTime.of(2026, 10, 1, 11, 0).atZone(zone).toInstant().toEpochMilli()
        val task = Task(title = "Médico", category = TaskCategory.HEALTH, dueAt = due, dueHasTime = true)
        val plan = ReminderPlanner.plan(task, nowMs, leadMinutes = 30, zone = zone)
        assertEquals(listOf("Mañana", "En 30 min", "En 10 min"), plan.map { it.label })
    }

    @Test
    fun `policy for an untimed deadline and a linked meeting`() {
        val zone = ZoneId.of("UTC")
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val due = LocalDate.of(2026, 10, 2).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val meetingStart = LocalDateTime.of(2026, 10, 2, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val task = Task(title = "Informe", dueAt = due, meeting = LinkedMeeting(1, "Sprint review", meetingStart))
        val labels = ReminderPlanner.plan(task, nowMs, zone = zone).map { it.label }
        assertEquals(listOf("Vence pasado mañana", "Vence hoy", "«Sprint review» empieza en 15 min", "¡Hoy vence!"), labels)
    }

    @Test
    fun `completed tasks have no reminders`() {
        val task = Task(title = "x", dueAt = System.currentTimeMillis() + 3_600_000, dueHasTime = true,
            status = io.github.salex27.lumi.domain.model.TaskStatus.COMPLETED)
        assertTrue(ReminderPlanner.plan(task, System.currentTimeMillis()).isEmpty())
    }

    // ── Meetings ───────────────────────────────────────────────────────────

    @Test
    fun `meeting hint and calendar matching`() {
        assertEquals("reunión del sprint", TaskPhraseParser.extractMeetingHint("preparar slides para la reunión del sprint"))
        val nowMs = 1_000L
        val events = listOf(
            AgendaEvent(1, "Dentista", 5_000, 6_000, false, 0, ""),
            AgendaEvent(2, "Sprint review", 9_000, 10_000, false, 0, ""),
            AgendaEvent(3, "Reunión semanal", 3_000, 4_000, false, 0, "")
        )
        assertEquals(2L, MeetingMatcher.match("reunión del sprint", events, nowMs)?.id)
        assertEquals(3L, MeetingMatcher.match("la reunión", events, nowMs)?.id) // generic → next meeting
        assertNull(MeetingMatcher.match("reunión con Marta", events, nowMs))
    }
}
