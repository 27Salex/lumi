package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.DayMode
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskStatus
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
    fun `during work hours work goes first`() {
        val plan = DayPlanner.plan(listOf(gym, shopping, work), LocalDateTime.of(2026, 9, 30, 11, 0), zone = zone)
        assertEquals(DayMode.WORK_HOURS, plan.mode)
        assertEquals(work, plan.suggestions.first())
    }

    @Test
    fun `in the evening work is parked`() {
        val plan = DayPlanner.plan(listOf(work, gym), LocalDateTime.of(2026, 9, 30, 20, 0), zone = zone)
        assertEquals(DayMode.AFTER_WORK, plan.mode)
        assertTrue(work !in plan.suggestions)
        assertEquals(1, plan.postponedWork)
    }

    @Test
    fun `work due tomorrow isn't parked even on a weekend`() {
        val sat = LocalDateTime.of(2026, 10, 3, 10, 0)
        val urgentWork = work.copy(dueAt = at(sat.plusDays(1)))
        val plan = DayPlanner.plan(listOf(urgentWork, gym), sat, zone = zone)
        assertTrue(urgentWork in plan.suggestions)
    }

    @Test
    fun `overdue and today are separated`() {
        val now = LocalDateTime.of(2026, 9, 30, 12, 0)
        val overdue = shopping.copy(dueAt = at(now.minusHours(2)), dueHasTime = true)
        val today = gym.copy(dueAt = at(now.plusHours(6)), dueHasTime = true)
        val done = work.copy(status = TaskStatus.COMPLETED, dueAt = at(now.minusDays(1)))
        val plan = DayPlanner.plan(listOf(overdue, today, done), now, zone = zone)
        assertEquals(listOf(overdue), plan.overdue)
        assertEquals(listOf(today), plan.dueToday)
    }

    @Test
    fun `parseCommand tolerates markdown and surrounding text`() {
        val raw = "Claro:\n```json\n{\"action\":\"create\",\"targetTitle\":\"Pagar luz\",\"category\":\"PERSONAL\",\"dueDate\":\"2026-10-01\"}\n```"
        val cmd = AssistantPrompts.parseCommand(raw)!!
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Pagar luz", cmd.targetTitle)
        assertEquals("2026-10-01", cmd.dueDate)
    }

    @Test
    fun `parseCommand rejects made-up actions and broken JSON`() {
        assertNull(AssistantPrompts.parseCommand("{\"action\":\"DANCE\"}"))
        assertNull(AssistantPrompts.parseCommand("no sé qué decirte"))
    }

    @Test
    fun `parseIso accepts dates with and without time`() {
        assertEquals(LocalDateTime.of(2026, 10, 3, 17, 0), AssistantOrchestrator.parseIso("2026-10-03T17:00"))
        assertEquals(LocalDateTime.of(2026, 10, 3, 9, 0), AssistantOrchestrator.parseIso("2026-10-03"))
    }

    @Test
    fun `a reply that sends the user to look it up is a deflection`() {
        assertTrue(AssistantPrompts.deflects("No tengo información específica; consulta las webs de Renfe."))
        assertTrue(AssistantPrompts.deflects("I don't have specific information, check the official website."))
        assertTrue(!AssistantPrompts.deflects("Renfe y Ouigo operan alta velocidad; Ouigo suele ser más barata."))
    }
}
