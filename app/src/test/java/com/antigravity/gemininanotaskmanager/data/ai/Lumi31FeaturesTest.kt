package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.live.LiveUpdatePlanner
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.reminder.ReminderPlanner
import com.antigravity.gemininanotaskmanager.presentation.ai.LumiSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** v3.1: prioridad, avisos por lugar, actualización en directo y respuestas habladas. */
class Lumi31FeaturesTest {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0) // lunes
    private val zone = ZoneId.systemDefault()
    private fun parse(t: String) = engine.parse(t, now)
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    // ── Prioridad ──────────────────────────────────────────────────────────

    @Test
    fun `es urgente marca prioridad alta y sale del título`() {
        val cmd = parse("llamar al banco, es urgente")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Llamar al banco", cmd.targetTitle)
        assertEquals("HIGH", cmd.priority)
    }

    @Test
    fun `urgente al principio también cuenta`() {
        val cmd = parse("urgente: enviar el contrato mañana")
        assertEquals("HIGH", cmd.priority)
        assertEquals("Enviar el contrato", cmd.targetTitle)
        assertEquals("2026-09-29", cmd.dueDate)
    }

    @Test
    fun `no es urgente es prioridad baja, no alta`() {
        assertEquals("LOW", parse("ordenar el trastero, no es urgente").priority)
        assertEquals("LOW", parse("leer ese libro cuando pueda").priority)
    }

    @Test
    fun `prioridad media explícita`() {
        assertEquals("MEDIUM", parse("revisar facturas con prioridad media").priority)
    }

    @Test
    fun `sin palabras de prioridad no se inventa`() {
        assertNull(parse("comprar pan").priority)
        // "importante" dentro del título no debería romperlo si no es una marca: aquí sí es marca
        assertNull(parse("preparar la reunión del sprint mañana").priority)
    }

    @Test
    fun `cambiar prioridad de una tarea existente`() {
        val cmd = parse("pon lo del dentista como urgente")
        assertEquals(TaskAICommand.SET_PRIORITY, cmd.action)
        assertEquals("dentista", cmd.targetTitle)
        assertEquals("HIGH", cmd.priority)

        val baja = parse("marca el informe con prioridad baja")
        assertEquals(TaskAICommand.SET_PRIORITY, baja.action)
        assertEquals("informe", baja.targetTitle)
        assertEquals("LOW", baja.priority)

        assertEquals(TaskAICommand.SET_PRIORITY, parse("prioriza el informe").action)
    }

    @Test
    fun `marcar como hecha sigue siendo completar, no prioridad`() {
        assertEquals(TaskAICommand.UPDATE_STATUS, parse("marcar como hecha la compra").action)
    }

    @Test
    fun `el plan del día pone primero lo urgente`() {
        val normal = Task(id = 1, title = "Ordenar escritorio", category = TaskCategory.WORK)
        val urgent = Task(id = 2, title = "Enviar contrato", category = TaskCategory.WORK, priority = TaskPriority.HIGH)
        val plan = DayPlanner.plan(listOf(normal, urgent), now)
        assertEquals("Enviar contrato", plan.suggestions.first().title)
    }

    @Test
    fun `prioridad alta sin hora recibe aviso la víspera`() {
        val due = millis(now.plusDays(3).withHour(9))
        val base = Task(id = 1, title = "Pagar multa", dueAt = due, dueHasTime = false)
        val normal = ReminderPlanner.plan(base, millis(now))
        val high = ReminderPlanner.plan(base.copy(priority = TaskPriority.HIGH), millis(now))
        assertTrue(high.size == normal.size + 1)
        assertTrue(high.any { it.label.contains("prioridad alta") })
    }

    // ── Lugares ────────────────────────────────────────────────────────────

    @Test
    fun `cuando llegue a casa crea aviso por lugar`() {
        val cmd = parse("cuando llegue a casa recuérdame sacar la basura")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Sacar la basura", cmd.targetTitle)
        assertEquals("casa", cmd.place)
        assertTrue(cmd.placeOnArrive)
    }

    @Test
    fun `al salir de la oficina se normaliza a trabajo`() {
        val cmd = parse("recuérdame comprar leche al salir de la oficina")
        assertEquals("Comprar leche", cmd.targetTitle)
        assertEquals("trabajo", cmd.place)
        assertFalse(cmd.placeOnArrive)
    }

    @Test
    fun `lugar y prioridad juntos`() {
        val cmd = parse("cuando llegue al gimnasio pesarme, es importante")
        assertEquals("gimnasio", cmd.place)
        assertEquals("HIGH", cmd.priority)
        assertEquals("Pesarme", cmd.targetTitle)
    }

    @Test
    fun `frases de lugar con artículo correcto`() {
        assertEquals("al llegar a casa", PlaceTrigger("casa").describe())
        assertEquals("al salir del trabajo", PlaceTrigger("trabajo", onArrive = false).describe())
        assertEquals("al llegar a la universidad", PlaceTrigger("universidad").describe())
        assertEquals("en el gimnasio", PlaceTrigger.withArticle("en", "gimnasio"))
    }

    @Test
    fun `PlaceTrigger se serializa y vuelve igual`() {
        val t = PlaceTrigger("trabajo", onArrive = false)
        assertEquals(t, PlaceTrigger.parse(t.serialize()))
        assertNull(PlaceTrigger.parse("basura"))
    }

    // ── Actualización en directo ──────────────────────────────────────────

    private fun event(id: Long, title: String, start: LocalDateTime, minutes: Long = 60) =
        AgendaEvent(id, title, millis(start), millis(start.plusMinutes(minutes)), false, 0, "Trabajo")

    @Test
    fun `muestra lo más cercano dentro de las próximas 2 horas`() {
        val tasks = listOf(
            Task(id = 1, title = "Dentista", dueAt = millis(now.plusMinutes(90)), dueHasTime = true),
            Task(id = 2, title = "Sin hora", dueAt = millis(now.plusMinutes(30)), dueHasTime = false)
        )
        val events = listOf(event(10, "Daily", now.plusMinutes(45)))
        val result = LiveUpdatePlanner.plan(tasks, events, millis(now))
        assertEquals("Daily", result.item?.title)
        // Mientras se muestra, se refresca la barra de progreso cada 5 min
        assertEquals(millis(now.plusMinutes(5)), result.nextRefreshAt)
        // 2 h antes = 0 %, a la hora = 100 %: faltan 45 de 120 min → 62 %
        assertEquals(62, LiveUpdatePlanner.progress(result.item!!, millis(now)))
    }

    @Test
    fun `nada en la ventana no muestra nada y revisa antes de que entre lo siguiente`() {
        val tasks = listOf(Task(id = 1, title = "Cena", dueAt = millis(now.plusHours(3)), dueHasTime = true))
        val result = LiveUpdatePlanner.plan(tasks, emptyList(), millis(now))
        assertNull(result.item)
        assertEquals(millis(now.plusHours(1)), result.nextRefreshAt) // 3 h - ventana de 2 h
    }

    @Test
    fun `reunión en curso sigue visible y los eventos de Lumi o ocultos se excluyen`() {
        val ongoing = event(10, "Revisión", now.minusMinutes(20), minutes = 60)
        assertEquals("Revisión", LiveUpdatePlanner.plan(emptyList(), listOf(ongoing), millis(now)).item?.title)
        assertNull(LiveUpdatePlanner.plan(emptyList(), listOf(ongoing), millis(now), excludedEventIds = setOf(10)).item)
        assertNull(LiveUpdatePlanner.plan(emptyList(), listOf(ongoing), millis(now), hiddenKey = "MEETING:10").item)
    }

    @Test
    fun `cuenta atrás legible`() {
        val t = millis(now)
        assertEquals("en 25 min", LiveUpdatePlanner.countdown(t + 25 * 60_000, t))
        assertEquals("en 1 h 10 min", LiveUpdatePlanner.countdown(t + 70 * 60_000, t))
        assertEquals("ahora", LiveUpdatePlanner.countdown(t - 1, t))
    }

    // ── Respuestas habladas ───────────────────────────────────────────────

    @Test
    fun `el texto se adapta para leerlo en voz alta`() {
        val spoken = LumiSpeaker.forSpeech("Te propongo:\n1. «Enviar contrato» — vence hoy\n2. Llamar a Ana\n\n¿Empezamos?")
        assertEquals("Te propongo: Enviar contrato, vence hoy. Llamar a Ana. ¿Empezamos?", spoken)
    }
}
