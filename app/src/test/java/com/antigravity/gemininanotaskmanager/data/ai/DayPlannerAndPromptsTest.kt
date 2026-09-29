package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.ai.DayMode
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DayPlannerAndPromptsTest {

    private val zone = ZoneId.of("UTC")
    private fun at(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    private val work = Task(id = 1, title = "Preparar informe", category = TaskCategory.WORK)
    private val gym = Task(id = 2, title = "Gimnasio", category = TaskCategory.HEALTH)
    private val shopping = Task(id = 3, title = "Comprar regalo", category = TaskCategory.PERSONAL)

    @Test
    fun `en horario laboral el trabajo va primero`() {
        val plan = DayPlanner.plan(listOf(gym, shopping, work), LocalDateTime.of(2026, 9, 30, 11, 0), zone = zone)
        assertEquals(DayMode.WORK_HOURS, plan.mode)
        assertEquals(work, plan.suggestions.first())
    }

    @Test
    fun `por la tarde el trabajo se aparca`() {
        val plan = DayPlanner.plan(listOf(work, gym), LocalDateTime.of(2026, 9, 30, 20, 0), zone = zone)
        assertEquals(DayMode.AFTER_WORK, plan.mode)
        assertTrue(work !in plan.suggestions)
        assertEquals(1, plan.postponedWork)
    }

    @Test
    fun `el trabajo que vence mañana no se aparca aunque sea fin de semana`() {
        val sat = LocalDateTime.of(2026, 10, 3, 10, 0)
        val urgentWork = work.copy(dueAt = at(sat.plusDays(1)))
        val plan = DayPlanner.plan(listOf(urgentWork, gym), sat, zone = zone)
        assertTrue(urgentWork in plan.suggestions)
    }

    @Test
    fun `vencidas y de hoy se separan`() {
        val now = LocalDateTime.of(2026, 9, 30, 12, 0)
        val overdue = shopping.copy(dueAt = at(now.minusHours(2)), dueHasTime = true)
        val today = gym.copy(dueAt = at(now.plusHours(6)), dueHasTime = true)
        val done = work.copy(status = TaskStatus.COMPLETED, dueAt = at(now.minusDays(1)))
        val plan = DayPlanner.plan(listOf(overdue, today, done), now, zone = zone)
        assertEquals(listOf(overdue), plan.overdue)
        assertEquals(listOf(today), plan.dueToday)
    }

    @Test
    fun `parseCommand tolera markdown y texto alrededor`() {
        val raw = "Claro:\n```json\n{\"action\":\"create\",\"targetTitle\":\"Pagar luz\",\"category\":\"PERSONAL\",\"dueDate\":\"2026-10-01\"}\n```"
        val cmd = AssistantPrompts.parseCommand(raw)!!
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Pagar luz", cmd.targetTitle)
        assertEquals("2026-10-01", cmd.dueDate)
    }

    @Test
    fun `parseCommand rechaza acciones inventadas y JSON roto`() {
        assertNull(AssistantPrompts.parseCommand("{\"action\":\"DANCE\"}"))
        assertNull(AssistantPrompts.parseCommand("no sé qué decirte"))
    }

    @Test
    fun `parseIso acepta fecha con y sin hora`() {
        assertEquals(LocalDateTime.of(2026, 10, 3, 17, 0), AssistantOrchestrator.parseIso("2026-10-03T17:00"))
        assertEquals(LocalDateTime.of(2026, 10, 3, 9, 0), AssistantOrchestrator.parseIso("2026-10-03"))
    }
}
