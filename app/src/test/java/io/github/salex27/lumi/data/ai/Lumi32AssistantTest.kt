package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.CheckInComposer
import io.github.salex27.lumi.domain.assistant.FreeTimeFinder
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** v3.2: free gaps, evening check-in and "take me to…". */
class Lumi32AssistantTest {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 15, 0) // lunes 15:00
    private val zone = ZoneId.systemDefault()
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    private fun event(id: Long, start: LocalDateTime, minutes: Long = 60) =
        AgendaEvent(id, "Reunión $id", millis(start), millis(start.plusMinutes(minutes)), false, 0, "Trabajo")

    // ── Free gaps ─────────────────────────────────────────────────────────

    @Test
    fun `gap until the next meeting with a suggested untimed task`() {
        val informe = Task(id = 1, title = "Informe")
        val llamada = Task(id = 2, title = "Llamada", dueAt = millis(now.plusHours(3)), dueHasTime = true)
        val slot = FreeTimeFinder.find(now, listOf(event(10, now.plusMinutes(40))), listOf(informe, llamada), listOf(llamada, informe))
        assertNotNull(slot)
        assertEquals(40, slot!!.minutes)
        assertEquals("Informe", slot.suggestion?.title) // the fixed-time one isn't suggested for the gap
    }

    @Test
    fun `if you're in a meeting now, the gap starts when it ends`() {
        val slot = FreeTimeFinder.find(now, listOf(event(10, now.minusMinutes(30)), event(11, now.plusMinutes(90))), emptyList(), emptyList())
        assertEquals(millis(now.plusMinutes(30)), slot!!.start)
        assertEquals(60, slot.minutes)
    }

    @Test
    fun `short or small-hours gaps don't count`() {
        assertNull(FreeTimeFinder.find(now, listOf(event(10, now.plusMinutes(15))), emptyList(), emptyList())?.takeIf { it.start == millis(now) })
        assertNull(FreeTimeFinder.find(now.withHour(23), emptyList(), emptyList(), emptyList()))
    }

    // ── Evening check-in ──────────────────────────────────────────────────

    @Test
    fun `check-in with done and pending items, urgent first`() {
        val today = now.withHour(20)
        val tasks = listOf(
            Task(id = 1, title = "Hecha", status = TaskStatus.COMPLETED, completedAt = millis(today.withHour(11))),
            Task(id = 2, title = "Normal", dueAt = millis(today.withHour(9))),
            Task(id = 3, title = "Urgente", dueAt = millis(today.withHour(10)), priority = TaskPriority.HIGH),
            Task(id = 4, title = "Mañana", dueAt = millis(today.plusDays(1)))
        )
        val c = CheckInComposer.compose(tasks, today)!!
        assertEquals("Hoy: 1 hecha", c.title)
        assertEquals(listOf("Urgente", "Normal"), c.openToday.map { it.title })
        assertTrue(c.body.contains("Hay algo urgente"))
    }

    @Test
    fun `with nothing today it doesn't bother`() {
        assertNull(CheckInComposer.compose(listOf(Task(id = 1, title = "Algún día")), now))
    }

    // ── "Take me to…" ─────────────────────────────────────────────────────

    @Test
    fun `route phrases`() {
        fun nav(t: String) = engine.parse(t, now)
        assertEquals(TaskAICommand.NAVIGATE, nav("llévame a casa").action)
        assertEquals("casa", nav("llévame a casa").targetTitle)
        assertEquals("reunión", nav("¿cómo llego a la reunión?").targetTitle)
        assertEquals("Calle Mayor 5", nav("ruta hasta Calle Mayor 5").targetTitle)
        assertEquals("trabajo", nav("cómo voy al trabajo").targetTitle)
    }

    @Test
    fun `doesn't confuse routes with tasks or summaries`() {
        assertEquals(TaskAICommand.CREATE, engine.parse("ir al gimnasio mañana", now).action)
        assertEquals(TaskAICommand.SUMMARIZE, engine.parse("¿cómo voy?", now).action)
    }
}
