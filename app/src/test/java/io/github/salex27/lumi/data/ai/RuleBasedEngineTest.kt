package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.DayMode
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class RuleBasedEngineTest {

    private val engine = RuleBasedEngine(Random(42))
    private val monday10 = LocalDateTime.of(2026, 9, 28, 10, 0)

    private fun parse(text: String) = engine.parse(text, monday10)

    @Test
    fun `recuérdame with a date creates a clean task with category and time`() {
        val cmd = parse("Recuérdame llamar a mamá mañana a las 6")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Llamar a mamá", cmd.targetTitle)
        assertEquals(TaskCategory.PERSONAL.name, cmd.category)
        assertEquals("2026-09-29T18:00", cmd.dueDate)
        assertTrue(cmd.hasTime)
    }

    @Test
    fun `planning questions`() {
        listOf("¿Qué hago hoy?", "que puedo hacer ahora", "¿Por dónde empiezo?", "planifica mi día").forEach {
            assertEquals(it, TaskAICommand.PLAN_DAY, parse(it).action)
        }
    }

    @Test
    fun `recuérdame que tengo que is NOT planning`() {
        val cmd = parse("recuérdame que tengo que llamar al fontanero")
        assertEquals(TaskAICommand.CREATE, cmd.action)
    }

    @Test
    fun `summary`() {
        assertEquals(TaskAICommand.SUMMARIZE, parse("¿cómo voy?").action)
        assertEquals(TaskAICommand.SUMMARIZE, parse("dame un resumen").action)
    }

    @Test
    fun `changing the status is not a summary`() {
        assertFalse(parse("cambia el estado de la reunión").action == TaskAICommand.SUMMARIZE)
    }

    @Test
    fun `complete a task`() {
        val cmd = parse("ya terminé la presentación del sprint")
        assertEquals(TaskAICommand.UPDATE_STATUS, cmd.action)
        assertEquals(TaskStatus.COMPLETED.name, cmd.newStatus)
        assertEquals("Presentación del sprint", cmd.targetTitle)
    }

    @Test
    fun `weekend plan reply mentions the day and parks work`() = runBlocking {
        val saturday = LocalDateTime.of(2026, 10, 3, 11, 0)
        val tasks = listOf(
            Task(id = 1, title = "Informe trimestral", category = TaskCategory.WORK),
            Task(id = 2, title = "Ir al gimnasio", category = TaskCategory.HEALTH)
        )
        val plan = DayPlanner.plan(tasks, saturday, zone = ZoneId.of("UTC"))
        assertEquals(DayMode.WEEKEND, plan.mode)
        val reply = engine.writeReply(ReplyRequest.DayPlan(plan, saturday))
        assertTrue(reply, reply.contains("sábado"))
        assertTrue(reply, reply.contains("Ir al gimnasio"))
        assertFalse(reply, reply.contains("Informe trimestral"))
    }

    @Test
    fun `empty briefing invites to create`() = runBlocking {
        val reply = engine.writeReply(ReplyRequest.Briefing(emptyList(), monday10))
        assertNotNull(reply)
        assertTrue(reply.contains("vacía"))
    }
}
