package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.assistant.AlarmPlanner
import com.antigravity.gemininanotaskmanager.domain.assistant.DayBriefComposer
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommand
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommandParser
import com.antigravity.gemininanotaskmanager.domain.assistant.IncomingMessage
import com.antigravity.gemininanotaskmanager.domain.assistant.MessageDigest
import com.antigravity.gemininanotaskmanager.domain.assistant.RoutineMatcher
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.weather.DayForecast
import com.antigravity.gemininanotaskmanager.domain.weather.HourForecast
import com.antigravity.gemininanotaskmanager.domain.weather.PartOfDay
import com.antigravity.gemininanotaskmanager.domain.weather.WeatherAdvisor
import com.antigravity.gemininanotaskmanager.domain.weather.WeatherQuery
import com.antigravity.gemininanotaskmanager.domain.weather.WeatherReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/** v3.6: el tiempo, preguntas generales, resumen del día, alarma inteligente, mensajes y rutinas. */
class Lumi36Test {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0) // lunes
    private val zone = ZoneId.systemDefault()
    private fun parse(t: String) = engine.parse(t, now)
    private fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    // ── Reconocer las frases ────────────────────────────────────────────────

    @Test
    fun `preguntas del tiempo`() {
        listOf(
            "¿qué tiempo hace?", "¿va a llover esta tarde?", "¿necesito paraguas mañana?", "el tiempo en Madrid",
            "¿hará frío el sábado?", "¿me llevo chaqueta esta noche?", "qué tal el tiempo el fin de semana"
        ).forEach { assertEquals(it, TaskAICommand.WEATHER, parse(it).action) }
    }

    @Test
    fun `lo que no es el tiempo`() {
        assertEquals(TaskAICommand.CREATE, parse("recuérdame coger el paraguas mañana").action)
        assertFalse(AssistantIntents.isWeather("¿cuánto tiempo tengo libre hoy?"))
        assertFalse(AssistantIntents.isWeather("comprar un paraguas"))
    }

    @Test
    fun `detalles de la pregunta del tiempo`() {
        val q = AssistantIntents.weather("¿Va a llover mañana por la tarde en Madrid?", now)!!
        assertEquals(LocalDate.of(2026, 9, 29), q.date)
        assertEquals(PartOfDay.AFTERNOON, q.part)
        assertEquals(WeatherQuery.Topic.RAIN, q.topic)
        assertEquals("Madrid", q.place)
        // «esta mañana» es hoy, no mañana
        val q2 = AssistantIntents.weather("¿hace frío esta mañana?", now)!!
        assertEquals(now.toLocalDate(), q2.date)
        assertEquals(PartOfDay.MORNING, q2.part)
        assertEquals(WeatherQuery.Topic.COLD, q2.topic)
        assertNull(q2.place)
    }

    @Test
    fun `resumen del dia, alarma y mensajes`() {
        assertEquals(TaskAICommand.DAY_BRIEF, parse("¿qué tengo mañana?").action)
        assertEquals("2026-09-29", parse("¿qué tengo mañana?").dueDate)
        assertEquals("2026-09-28", parse("resumen de hoy").dueDate)
        assertEquals(TaskAICommand.SMART_ALARM, parse("pon la alarma para mañana").action)
        assertEquals("ASK", parse("¿a qué hora me pongo la alarma?").newStatus)
        // Con hora concreta sigue siendo una alarma normal
        assertEquals(TaskAICommand.DEVICE, parse("pon una alarma a las 7").action)
        assertEquals("", parse("¿qué me han escrito?").targetTitle)
        assertEquals("Víctor", parse("¿qué me ha dicho Víctor?").targetTitle)
        assertEquals(TaskAICommand.NOTIFICATIONS, parse("léeme los mensajes").action)
        // «¿Cómo voy?» sigue siendo el resumen de tareas
        assertEquals(TaskAICommand.SUMMARIZE, parse("¿cómo voy?").action)
    }

    @Test
    fun `preguntas generales no son tareas`() {
        listOf("dame ideas para cenar algo ligero", "explícame qué es la inflación", "cuéntame un chiste", "¿cuánto es el 15% de 80?")
            .forEach { assertEquals(it, TaskAICommand.ASK, parse(it).action) }
        // Preguntas con «¿» siguen yendo a RECALL (memoria → tareas → pregunta general)
        assertEquals(TaskAICommand.RECALL, parse("¿quién escribió el Quijote?").action)
        // Y las tareas siguen siendo tareas
        assertEquals(TaskAICommand.CREATE, parse("comprar pan mañana").action)
    }

    @Test
    fun `cuentas sin IA`() {
        assertEquals("El 15 % de 80 es 12.", QuickMath.answer("¿cuánto es el 15% de 80?"))
        assertEquals("234 × 12 = 2808", QuickMath.answer("234 por 12"))
        assertEquals("10 ÷ 4 = 2,5", QuickMath.answer("cuánto es 10 entre 4"))
        assertNull(QuickMath.answer("dame ideas para cenar"))
    }

    @Test
    fun `no molestar`() {
        assertEquals(DeviceCommand.DoNotDisturb(true), DeviceCommandParser.parse("activa no molestar"))
        assertEquals(DeviceCommand.DoNotDisturb(false), DeviceCommandParser.parse("desactiva el modo no molestar"))
        val reply = DeviceCommand.ReplyMessage("k|1", "Víctor", "Ya voy | bajo", "WhatsApp")
        assertEquals(reply, DeviceCommand.parse(reply.serialize()))
    }

    // ── El tiempo ─────────────────────────────────────────────────────────────

    private fun report(rainFrom: Int = 18, rainProb: Int = 70): WeatherReport {
        val hours = (0 until 72).map { i ->
            val t = now.toLocalDate().atStartOfDay().plusHours(i.toLong())
            val rain = t.toLocalDate() == now.toLocalDate() && t.hour >= rainFrom
            HourForecast(t, 12.0 + (t.hour.coerceIn(6, 15) - 6), if (rain) rainProb else 5, if (rain) 63 else 1)
        }
        val days = (0 until 3).map { DayForecast(now.toLocalDate().plusDays(it.toLong()), if (it == 0) 63 else 1, 21.0, 12.0, if (it == 0) rainProb else 5) }
        return WeatherReport("Madrid", 0, 17.0, 2, hours, days)
    }

    @Test
    fun `respuestas del tiempo`() {
        val r = report()
        val rain = WeatherAdvisor.answer(WeatherQuery(now.toLocalDate(), topic = WeatherQuery.Topic.RAIN), r, now)
        assertTrue(rain, rain.startsWith("Sí, coge paraguas") && rain.contains("18:00") && rain.contains("70 %"))
        val tomorrow = WeatherAdvisor.answer(WeatherQuery(now.toLocalDate().plusDays(1), topic = WeatherQuery.Topic.RAIN), r, now)
        assertTrue(tomorrow, tomorrow.startsWith("No, mañana"))
        val general = WeatherAdvisor.answer(WeatherQuery(now.toLocalDate()), r, now)
        assertTrue(general, general.startsWith("Ahora 17°") && general.contains("entre 12° y 21°"))
        val cold = WeatherAdvisor.answer(WeatherQuery(now.toLocalDate().plusDays(1), PartOfDay.MORNING, WeatherQuery.Topic.COLD), r, now)
        assertTrue(cold, cold.contains("chaqueta ligera"))
    }

    @Test
    fun `avisa si una tarea al aire libre coincide con lluvia`() {
        val run = Task(id = 1, title = "Salir a correr", dueAt = ms(now.withHour(19)), dueHasTime = true)
        val office = Task(id = 2, title = "Revisar informe", dueAt = ms(now.withHour(19)), dueHasTime = true)
        val w = WeatherAdvisor.taskWarnings(listOf(run, office), report(), now, zone)
        assertEquals(listOf(1L), w.map { it.task.id })
        assertTrue(w.first().message.contains("lluvia probable"))
    }

    // ── Alarma inteligente ──────────────────────────────────────────────────

    private fun event(title: String, at: LocalDateTime, location: String = "") =
        AgendaEvent(1, title, ms(at), ms(at.plusHours(1)), false, 0, "", location)

    @Test
    fun `alarma segun la primera cita`() {
        val tuesday = LocalDate.of(2026, 9, 29)
        val cfg = AlarmPlanner.Config(prepMinutes = 60, travelMinutes = 30, workStartHour = 9)
        // Dentista a las 8:30 con dirección → 8:30 − 60 − 30 = 7:00
        val p = AlarmPlanner.plan(tuesday, listOf(event("Dentista", tuesday.atTime(8, 30), "C/ Mayor 5")), emptyList(), cfg, zone)!!
        assertEquals(LocalTime.of(7, 0), p.wake)
        assertTrue(p.reason, p.reason.contains("Dentista") && p.reason.contains("30 de trayecto"))
        // Sin nada: entra a trabajar a las 9 → 7:30
        assertEquals(LocalTime.of(7, 30), AlarmPlanner.plan(tuesday, emptyList(), emptyList(), cfg, zone)!!.wake)
        // Sábado sin nada temprano → sin alarma
        assertNull(AlarmPlanner.plan(LocalDate.of(2026, 10, 3), listOf(event("Comida", LocalDate.of(2026, 10, 3).atTime(14, 0))), emptyList(), cfg, zone))
        // Tarea con hora (sin lugar: sin trayecto) y redondeo a 5 min: 8:12 − 60 = 7:12 → 7:10
        val t = Task(id = 1, title = "Llamada", dueAt = ms(LocalDate.of(2026, 10, 3).atTime(8, 12)), dueHasTime = true)
        assertEquals(LocalTime.of(7, 10), AlarmPlanner.plan(LocalDate.of(2026, 10, 3), emptyList(), listOf(t), cfg, zone)!!.wake)
        // De madrugada, «mañana» es hoy
        assertEquals(LocalDate.of(2026, 9, 28), AlarmPlanner.targetDate(LocalDateTime.of(2026, 9, 28, 1, 30)))
    }

    @Test
    fun `resumen del dia con tiempo y primera cita`() {
        val tasks = listOf(Task(id = 1, title = "Salir a correr", dueAt = ms(now.withHour(19)), dueHasTime = true))
        val b = DayBriefComposer.compose(now.toLocalDate(), now, tasks, listOf(event("Sprint", now.withHour(11))), report(), "Buenos días", zone = zone)
        assertTrue(b.title, b.title.startsWith("Buenos días · 12–21°"))
        assertTrue(b.body, b.body.contains("Lo primero: «Sprint» a las 11:00"))
        assertTrue(b.body, b.body.contains("Ojo: «Salir a correr»"))
    }

    // ── Mensajes y rutinas ──────────────────────────────────────────────────

    private fun msg(conv: String, sender: String, text: String, t: Long, group: Boolean = false) =
        IncomingMessage("k-$conv", "com.whatsapp", "WhatsApp", conv, sender, text, t, group, canReply = true)

    @Test
    fun `resumen de mensajes`() {
        val list = listOf(
            msg("Víctor", "Víctor", "¿Bajas?", 1), msg("Víctor", "Víctor", "Estoy en la puerta", 3),
            msg("Familia", "Mamá", "Cena a las 9", 2, group = true)
        )
        val d = MessageDigest.compose(list)
        assertTrue(d, d.startsWith("Tienes 3 mensajes de 2 conversaciones. Víctor (2): «¿Bajas?» «Estoy en la puerta»."))
        assertTrue(d, d.contains("Familia: «Mamá: Cena a las 9»"))
        assertEquals(2, MessageDigest.filter(list, "victor").size)
        assertEquals("Víctor", MessageDigest.replyTarget(MessageDigest.filter(list, "víctor"))?.conversation)
        assertNull(MessageDigest.replyTarget(list)) // varias conversaciones → no se sabe a quién
        assertEquals("No tienes mensajes sin leer de Ana.", MessageDigest.compose(emptyList(), "Ana"))
    }

    @Test
    fun `rutinas por frase`() {
        val r = RoutineMatcher.DEFAULTS
        assertEquals("night", RoutineMatcher.match("Buenas noches, Lumi", r)?.id)
        assertEquals("morning", RoutineMatcher.match("oye lumi buenos días", r)?.id)
        assertEquals("home", RoutineMatcher.match("Me voy a casa.", r)?.id)
        assertNull(RoutineMatcher.match("buenas noches a todos los de la oficina", r))
        assertNull(RoutineMatcher.match("buenas noches", r.map { it.copy(enabled = false) }))
        // Todos los pasos de las rutinas predefinidas los entienden las reglas (ninguno acaba creando una tarea)
        r.flatMap { it.steps }.forEach { step ->
            val a = parse(step).action
            assertTrue("$step → $a", a != TaskAICommand.CREATE && a != TaskAICommand.CREATE_MANY)
        }
        assertNotNull(DeviceCommandParser.parse("activa no molestar"))
    }
}
