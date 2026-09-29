package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.assistant.CheckInComposer
import com.antigravity.gemininanotaskmanager.domain.assistant.FreeTimeFinder
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** v3.2: huecos libres, repaso de la tarde y «llévame a…». */
class Lumi32AssistantTest {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 15, 0) // lunes 15:00
    private val zone = ZoneId.systemDefault()
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    private fun event(id: Long, start: LocalDateTime, minutes: Long = 60) =
        AgendaEvent(id, "Reunión $id", millis(start), millis(start.plusMinutes(minutes)), false, 0, "Trabajo")

    // ── Huecos libres ─────────────────────────────────────────────────────

    @Test
    fun `hueco hasta la siguiente reunión con la tarea sin hora propuesta`() {
        val informe = Task(id = 1, title = "Informe")
        val llamada = Task(id = 2, title = "Llamada", dueAt = millis(now.plusHours(3)), dueHasTime = true)
        val slot = FreeTimeFinder.find(now, listOf(event(10, now.plusMinutes(40))), listOf(informe, llamada), listOf(llamada, informe))
        assertNotNull(slot)
        assertEquals(40, slot!!.minutes)
        assertEquals("Informe", slot.suggestion?.title) // la de hora fija no se propone para el hueco
    }

    @Test
    fun `si ahora estás en una reunión, el hueco empieza al acabar`() {
        val slot = FreeTimeFinder.find(now, listOf(event(10, now.minusMinutes(30)), event(11, now.plusMinutes(90))), emptyList(), emptyList())
        assertEquals(millis(now.plusMinutes(30)), slot!!.start)
        assertEquals(60, slot.minutes)
    }

    @Test
    fun `huecos cortos o de madrugada no cuentan`() {
        assertNull(FreeTimeFinder.find(now, listOf(event(10, now.plusMinutes(15))), emptyList(), emptyList())?.takeIf { it.start == millis(now) })
        assertNull(FreeTimeFinder.find(now.withHour(23), emptyList(), emptyList(), emptyList()))
    }

    // ── Repaso de la tarde ────────────────────────────────────────────────

    @Test
    fun `repaso con lo hecho y lo pendiente, lo urgente primero`() {
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
    fun `sin nada hoy no se molesta`() {
        assertNull(CheckInComposer.compose(listOf(Task(id = 1, title = "Algún día")), now))
    }

    // ── «Llévame a…» ──────────────────────────────────────────────────────

    @Test
    fun `frases de ruta`() {
        fun nav(t: String) = engine.parse(t, now)
        assertEquals(TaskAICommand.NAVIGATE, nav("llévame a casa").action)
        assertEquals("casa", nav("llévame a casa").targetTitle)
        assertEquals("reunión", nav("¿cómo llego a la reunión?").targetTitle)
        assertEquals("Calle Mayor 5", nav("ruta hasta Calle Mayor 5").targetTitle)
        assertEquals("trabajo", nav("cómo voy al trabajo").targetTitle)
    }

    @Test
    fun `no confunde rutas con tareas ni resúmenes`() {
        assertEquals(TaskAICommand.CREATE, engine.parse("ir al gimnasio mañana", now).action)
        assertEquals(TaskAICommand.SUMMARIZE, engine.parse("¿cómo voy?", now).action)
    }
}
