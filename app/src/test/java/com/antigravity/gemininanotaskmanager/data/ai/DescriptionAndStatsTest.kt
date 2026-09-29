package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.ai.ReplyRequest
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import com.antigravity.gemininanotaskmanager.domain.stats.StatsCalculator
import com.antigravity.gemininanotaskmanager.domain.stats.StatsRange
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

    // ── Descripción: solo si el usuario la da ──────────────────────────────

    @Test
    fun `sin marcador no hay descripción`() {
        assertNull(engine.parse("recuérdame comprar un regalo para Ana el viernes", now).description)
    }

    @Test
    fun `nota con dos puntos se guarda como descripción`() {
        val cmd = engine.parse("comprar regalo para Ana el viernes, nota: algo de libros", now)
        assertEquals("Algo de libros", cmd.description)
        assertEquals("Comprar regalo para Ana", cmd.targetTitle)
        assertEquals("2026-10-02", cmd.dueDate)
    }

    @Test
    fun `descripción dictada por voz sin dos puntos`() {
        val cmd = engine.parse("preparar la reunión mañana a las 10 descripción revisar presupuesto", now)
        assertEquals("Revisar presupuesto", cmd.description)
        assertEquals("Preparar la reunión", cmd.targetTitle)
    }

    @Test
    fun `nota sin dos puntos es parte del título`() {
        val cmd = engine.parse("sacar buena nota en el examen", now)
        assertNull(cmd.description)
        assertTrue(cmd.targetTitle!!.contains("nota"))
    }

    // ── Plan con eventos del calendario ────────────────────────────────────

    @Test
    fun `el plan menciona los eventos del calendario`() = runBlocking {
        val zone = ZoneId.systemDefault()
        val later = LocalDateTime.now().plusHours(2)
        val event = AgendaEvent(1, "Reunión con el equipo", later.atZone(zone).toInstant().toEpochMilli(),
            later.plusHours(1).atZone(zone).toInstant().toEpochMilli(), false, 0, "Trabajo")
        val plan = DayPlanner.plan(emptyList(), now)
        val reply = engine.writeReply(ReplyRequest.DayPlan(plan, now, listOf(event)))
        assertTrue(reply, reply.contains("Reunión con el equipo"))
    }

    // ── Estadísticas ───────────────────────────────────────────────────────

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 28)
    private fun at(d: LocalDate, h: Int = 12) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun done(daysAgo: Long, cat: TaskCategory = TaskCategory.WORK) = Task(
        title = "t$daysAgo", status = TaskStatus.COMPLETED, category = cat,
        createdAt = at(today.minusDays(daysAgo)), completedAt = at(today.minusDays(daysAgo))
    )

    @Test
    fun `semana cuenta completadas por día, racha y variación`() {
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
    fun `la racha cuenta desde ayer si hoy aún no hay nada`() {
        val s = StatsCalculator.compute(listOf(done(1), done(2)), StatsRange.WEEK, today, zone, at(today))
        assertEquals(2, s.streak)
    }

    @Test
    fun `mes tiene 30 días y ratio sobre lo creado`() {
        val open = Task(title = "abierta", createdAt = at(today))
        val s = StatsCalculator.compute(listOf(done(0), open), StatsRange.MONTH, today, zone, at(today))
        assertEquals(30, s.days.size)
        assertEquals(50, s.completionRate)
    }
}
