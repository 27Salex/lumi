package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.live.LiveUpdatePlanner
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.reminder.ReminderPlanner
import io.github.salex27.lumi.presentation.ai.LumiSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** v3.1: priority, place reminders, live update and spoken replies. */
class Lumi31FeaturesTest {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0) // lunes
    private val zone = ZoneId.systemDefault()
    private fun parse(t: String) = engine.parse(t, now)
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    // ── Priority ───────────────────────────────────────────────────────────

    @Test
    fun `es urgente sets high priority and leaves the title`() {
        val cmd = parse("llamar al banco, es urgente")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Llamar al banco", cmd.targetTitle)
        assertEquals("HIGH", cmd.priority)
    }

    @Test
    fun `urgente at the start also counts`() {
        val cmd = parse("urgente: enviar el contrato mañana")
        assertEquals("HIGH", cmd.priority)
        assertEquals("Enviar el contrato", cmd.targetTitle)
        assertEquals("2026-09-29", cmd.dueDate)
    }

    @Test
    fun `no es urgente is low priority, not high`() {
        assertEquals("LOW", parse("ordenar el trastero, no es urgente").priority)
        assertEquals("LOW", parse("leer ese libro cuando pueda").priority)
    }

    @Test
    fun `explicit medium priority`() {
        assertEquals("MEDIUM", parse("revisar facturas con prioridad media").priority)
    }

    @Test
    fun `without priority words none is invented`() {
        assertNull(parse("comprar pan").priority)
        // "importante" inside the title shouldn't break it unless it is a marker: here it is one
        assertNull(parse("preparar la reunión del sprint mañana").priority)
    }

    @Test
    fun `changing the priority of an existing task`() {
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
    fun `mark as done is still complete, not priority`() {
        assertEquals(TaskAICommand.UPDATE_STATUS, parse("marcar como hecha la compra").action)
    }

    @Test
    fun `the day plan puts urgent things first`() {
        val normal = Task(id = 1, title = "Ordenar escritorio", category = TaskCategory.WORK)
        val urgent = Task(id = 2, title = "Enviar contrato", category = TaskCategory.WORK, priority = TaskPriority.HIGH)
        val plan = DayPlanner.plan(listOf(normal, urgent), now)
        assertEquals("Enviar contrato", plan.suggestions.first().title)
    }

    @Test
    fun `untimed high priority gets a reminder the day before`() {
        val due = millis(now.plusDays(3).withHour(9))
        val base = Task(id = 1, title = "Pagar multa", dueAt = due, dueHasTime = false)
        val normal = ReminderPlanner.plan(base, millis(now))
        val high = ReminderPlanner.plan(base.copy(priority = TaskPriority.HIGH), millis(now))
        assertTrue(high.size == normal.size + 1)
        assertTrue(high.any { it.label.contains("prioridad alta") })
    }

    // ── Places ─────────────────────────────────────────────────────────────

    @Test
    fun `when I get home creates a place reminder`() {
        val cmd = parse("cuando llegue a casa recuérdame sacar la basura")
        assertEquals(TaskAICommand.CREATE, cmd.action)
        assertEquals("Sacar la basura", cmd.targetTitle)
        assertEquals("casa", cmd.place)
        assertTrue(cmd.placeOnArrive)
    }

    @Test
    fun `leaving the office normalizes to work`() {
        val cmd = parse("recuérdame comprar leche al salir de la oficina")
        assertEquals("Comprar leche", cmd.targetTitle)
        assertEquals("trabajo", cmd.place)
        assertFalse(cmd.placeOnArrive)
    }

    @Test
    fun `place and priority together`() {
        val cmd = parse("cuando llegue al gimnasio pesarme, es importante")
        assertEquals("gimnasio", cmd.place)
        assertEquals("HIGH", cmd.priority)
        assertEquals("Pesarme", cmd.targetTitle)
    }

    @Test
    fun `place phrases with the right article`() {
        assertEquals("al llegar a casa", PlaceTrigger("casa").describe())
        assertEquals("al salir del trabajo", PlaceTrigger("trabajo", onArrive = false).describe())
        assertEquals("al llegar a la universidad", PlaceTrigger("universidad").describe())
        assertEquals("en el gimnasio", PlaceTrigger.withArticle("en", "gimnasio"))
    }

    @Test
    fun `PlaceTrigger survives serialization`() {
        val t = PlaceTrigger("trabajo", onArrive = false)
        assertEquals(t, PlaceTrigger.parse(t.serialize()))
        assertNull(PlaceTrigger.parse("basura"))
    }

    // ── Live update ───────────────────────────────────────────────────────

    private fun event(id: Long, title: String, start: LocalDateTime, minutes: Long = 60) =
        AgendaEvent(id, title, millis(start), millis(start.plusMinutes(minutes)), false, 0, "Trabajo")

    @Test
    fun `shows the nearest item within the next 2 hours`() {
        val tasks = listOf(
            Task(id = 1, title = "Dentista", dueAt = millis(now.plusMinutes(90)), dueHasTime = true),
            Task(id = 2, title = "Sin hora", dueAt = millis(now.plusMinutes(30)), dueHasTime = false)
        )
        val events = listOf(event(10, "Daily", now.plusMinutes(45)))
        val result = LiveUpdatePlanner.plan(tasks, events, millis(now))
        assertEquals("Daily", result.item?.title)
        // While shown, the progress bar refreshes every 5 min
        assertEquals(millis(now.plusMinutes(5)), result.nextRefreshAt)
        // 2 h before = 0 %, on time = 100 %: 45 of 120 min left → 62 %
        assertEquals(62, LiveUpdatePlanner.progress(result.item!!, millis(now)))
    }

    @Test
    fun `nothing in the window shows nothing and checks before the next item enters`() {
        val tasks = listOf(Task(id = 1, title = "Cena", dueAt = millis(now.plusHours(3)), dueHasTime = true))
        val result = LiveUpdatePlanner.plan(tasks, emptyList(), millis(now))
        assertNull(result.item)
        assertEquals(millis(now.plusHours(1)), result.nextRefreshAt) // 3 h - ventana de 2 h
    }

    @Test
    fun `ongoing meeting stays visible and Lumi's or hidden events are excluded`() {
        val ongoing = event(10, "Revisión", now.minusMinutes(20), minutes = 60)
        assertEquals("Revisión", LiveUpdatePlanner.plan(emptyList(), listOf(ongoing), millis(now)).item?.title)
        assertNull(LiveUpdatePlanner.plan(emptyList(), listOf(ongoing), millis(now), excludedEventIds = setOf(10)).item)
        assertNull(LiveUpdatePlanner.plan(emptyList(), listOf(ongoing), millis(now), hiddenKey = "MEETING:10").item)
    }

    @Test
    fun `readable countdown`() {
        val t = millis(now)
        assertEquals("en 25 min", LiveUpdatePlanner.countdown(t + 25 * 60_000, t))
        assertEquals("en 1 h 10 min", LiveUpdatePlanner.countdown(t + 70 * 60_000, t))
        assertEquals("ahora", LiveUpdatePlanner.countdown(t - 1, t))
    }

    // ── Spoken replies ────────────────────────────────────────────────────

    @Test
    fun `text is adapted to be read aloud`() {
        val spoken = LumiSpeaker.forSpeech("Te propongo:\n1. «Enviar contrato» — vence hoy\n2. Llamar a Ana\n\n¿Empezamos?")
        assertEquals("Te propongo: Enviar contrato, vence hoy. Llamar a Ana. ¿Empezamos?", spoken)
    }
}
