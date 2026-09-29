package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.stats.StatsCalculator
import io.github.salex27.lumi.domain.stats.StatsRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class DescriptionAndStatsTest {

    private val engine = RuleBasedEngine(Random(1))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0)

    // ── Description: only if the user gives it ─────────────────────────────

    @Test
    fun `no marker means no description`() {
        assertNull(engine.parse("recuérdame comprar un regalo para Ana el viernes", now).description)
    }

    @Test
    fun `note with a colon is saved as the description`() {
        val cmd = engine.parse("comprar regalo para Ana el viernes, nota: algo de libros", now)
        assertEquals("Algo de libros", cmd.description)
        assertEquals("Comprar regalo para Ana", cmd.targetTitle)
        assertEquals("2026-10-02", cmd.dueDate)
    }

    @Test
    fun `description dictated by voice without a colon`() {
        val cmd = engine.parse("preparar la reunión mañana a las 10 descripción revisar presupuesto", now)
        assertEquals("Revisar presupuesto", cmd.description)
        assertEquals("Preparar la reunión", cmd.targetTitle)
    }

    @Test
    fun `note without a colon is part of the title`() {
        val cmd = engine.parse("sacar buena nota en el examen", now)
        assertNull(cmd.description)
        assertTrue(cmd.targetTitle!!.contains("nota"))
    }

    // ── Plan with calendar events ──────────────────────────────────────────

    @Test
    fun `the plan mentions calendar events`() = runBlocking {
        val zone = ZoneId.systemDefault()
        val later = LocalDateTime.now().plusHours(2)
        val event = AgendaEvent(1, "Reunión con el equipo", later.atZone(zone).toInstant().toEpochMilli(),
            later.plusHours(1).atZone(zone).toInstant().toEpochMilli(), false, 0, "Trabajo")
        val plan = DayPlanner.plan(emptyList(), now)
        val reply = engine.writeReply(ReplyRequest.DayPlan(plan, now, listOf(event)))
        assertTrue(reply, reply.contains("Reunión con el equipo"))
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 28)
    private fun at(d: LocalDate, h: Int = 12) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun done(daysAgo: Long, cat: TaskCategory = TaskCategory.WORK) = Task(
        title = "t$daysAgo", status = TaskStatus.COMPLETED, category = cat,
        createdAt = at(today.minusDays(daysAgo)), completedAt = at(today.minusDays(daysAgo))
    )

    @Test
    fun `week counts completed per day, streak and change`() {
        val tasks = listOf(done(0), done(1), done(1, TaskCategory.HEALTH), done(2), done(9), done(10))
        val s = StatsCalculator.compute(tasks, StatsRange.WEEK, today, zone, at(today))
        assertEquals(7, s.days.size)
        assertEquals(4, s.completed)
        assertEquals(2, s.previousCompleted)
        assertEquals(2, s.delta)
        assertEquals(3, s.streak) // hoy, ayer y anteayer
        assertEquals(2, s.bestDay!!.completed)
        assertEquals(TaskCategory.WORK, s.byCategory.first().first)
    }

    @Test
    fun `the streak counts from yesterday if today is still empty`() {
        val s = StatsCalculator.compute(listOf(done(1), done(2)), StatsRange.WEEK, today, zone, at(today))
        assertEquals(2, s.streak)
    }

    @Test
    fun `month has 30 days and ratio over created`() {
        val open = Task(title = "abierta", createdAt = at(today))
        val s = StatsCalculator.compute(listOf(done(0), open), StatsRange.MONTH, today, zone, at(today))
        assertEquals(30, s.days.size)
        assertEquals(50, s.completionRate)
    }
}
