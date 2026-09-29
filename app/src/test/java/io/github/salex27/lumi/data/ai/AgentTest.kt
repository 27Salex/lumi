package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.domain.assistant.DeviceCommandParser
import io.github.salex27.lumi.domain.assistant.MemoryRetriever
import io.github.salex27.lumi.domain.assistant.RenameSplitter
import io.github.salex27.lumi.domain.assistant.TaskMatcher
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** Agent mode: finding the right task, editing, on-demand memory and phone actions. */
class AgentTest {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0)
    private fun parse(t: String) = engine.parse(t, now)

    private val tasks = listOf(
        Task(id = 1, title = "Llevar a Víctor"),
        Task(id = 2, title = "Preparar presentación del sprint"),
        Task(id = 3, title = "Comprar regalo de cumpleaños"),
        Task(id = 4, title = "Llamar a Víctor por su cumpleaños")
    )

    // ── Finding the task ──────────────────────────────────────────────────

    @Test
    fun `finds the task even with extra words`() {
        val d = TaskMatcher.decide("mi tarea sobre llevar a Víctor al trabajo", tasks)
        assertEquals(1L, (d as TaskMatcher.Decision.Sure).task.id)
    }

    @Test
    fun `when in doubt asks with the candidates`() {
        val d = TaskMatcher.decide("lo de Víctor", tasks)
        assertTrue(d is TaskMatcher.Decision.Ask)
        assertTrue((d as TaskMatcher.Decision.Ask).candidates.map { it.id }.containsAll(listOf(1L, 4L)))
    }

    @Test
    fun `no match invents nothing`() {
        assertEquals(TaskMatcher.Decision.NotFound, TaskMatcher.decide("sacar al perro", tasks))
    }

    @Test
    fun `plurals and accents don't matter`() {
        val d = TaskMatcher.decide("la presentacion del sprint", tasks)
        assertEquals(2L, (d as TaskMatcher.Decision.Sure).task.id)
    }

    @Test
    fun `answers to did you mean`() {
        val labels = listOf("Llevar a Víctor", "Llamar a Víctor por su cumpleaños")
        assertEquals(0, io.github.salex27.lumi.presentation.assistant.parseChoiceAnswer("Sí", labels))
        assertEquals(1, io.github.salex27.lumi.presentation.assistant.parseChoiceAnswer("la segunda", labels))
        assertEquals(-1, io.github.salex27.lumi.presentation.assistant.parseChoiceAnswer("no", labels))
        assertEquals(1, io.github.salex27.lumi.presentation.assistant.parseChoiceAnswer("la del cumpleaños", labels))
        assertNull(io.github.salex27.lumi.presentation.assistant.parseChoiceAnswer("pon una alarma a las 7", labels))
    }

    // ── Editing ───────────────────────────────────────────────────────────

    @Test
    fun `plain edit opens the editor`() {
        val c = parse("edítame la tarea que se llama llevar a Víctor")
        assertEquals(TaskAICommand.EDIT, c.action)
        assertEquals("llevar a Víctor", c.targetTitle)
        assertNull(c.newTitle)
    }

    @Test
    fun `edit with changes`() {
        val c = parse("edita lo del dentista y ponle prioridad alta")
        assertEquals(TaskAICommand.EDIT, c.action)
        assertEquals("dentista", c.targetTitle)
        assertEquals("HIGH", c.priority)

        val d = parse("edita la presentación y pásala al viernes")
        assertEquals("2026-10-02", d.dueDate)
    }

    @Test
    fun `rename, note and area`() {
        val r = parse("cambia el nombre de llevar a Víctor a llevar a Víctor al trabajo")
        assertEquals(TaskAICommand.EDIT, r.action)
        // The right split is decided by the repository with the real tasks
        assertEquals("llevar a Víctor" to "llevar a Víctor al trabajo", RenameSplitter.split(r.renameSpec!!, tasks))

        val n = parse("añade una nota a la presentación: incluir métricas de septiembre")
        assertEquals("Incluir métricas de septiembre", n.description)

        val a = parse("pasa la presentación a trabajo")
        assertEquals("WORK", a.category)
    }

    @Test
    fun `moving to a date is still rescheduling`() {
        assertEquals(TaskAICommand.RESCHEDULE, parse("pasa la presentación a mañana").action)
    }

    // ── Memory ────────────────────────────────────────────────────────────

    @Test
    fun `remember facts without confusing them with tasks`() {
        val r = parse("recuerda que el wifi de la oficina es Lumi2024")
        assertEquals(TaskAICommand.REMEMBER, r.action)
        assertEquals("El wifi de la oficina es Lumi2024", r.targetTitle)
        assertEquals(TaskAICommand.REMEMBER, parse("mi dentista es la doctora López").action)
        // Obligation or date → task
        assertEquals(TaskAICommand.CREATE, parse("recuerda que tengo que llamar a mamá mañana").action)
        assertEquals(TaskAICommand.FORGET, parse("olvida que el wifi es Lumi2024").action)
    }

    @Test
    fun `questions are answered, not turned into tasks`() {
        assertEquals(TaskAICommand.RECALL, parse("¿cuál es el wifi de la oficina?").action)
        assertEquals(TaskAICommand.RECALL, parse("cómo se llama mi dentista").action)
        assertEquals(TaskAICommand.PLAN_DAY, parse("¿qué hago ahora?").action)
    }

    @Test
    fun `memory only brings related facts`() {
        val memory = listOf(
            MemoryRetriever.Memory(1, "El wifi de la oficina es Lumi2024"),
            MemoryRetriever.Memory(2, "Mi dentista es la doctora López"),
            MemoryRetriever.Memory(3, "Víctor trabaja en el polígono sur"),
            MemoryRetriever.Memory(4, "La contraseña del gimnasio es 4455")
        )
        assertEquals(listOf(1L), MemoryRetriever.relevant("¿cuál es el wifi de la oficina?", memory).map { it.id })
        assertEquals(2L, MemoryRetriever.relevant("cómo se llama mi dentista", memory).first().id)
        assertTrue(MemoryRetriever.relevant("qué tiempo hace", memory).isEmpty())
    }

    // ── Phone actions ─────────────────────────────────────────────────────

    @Test
    fun `phone actions`() {
        assertEquals(DeviceCommand.OpenApp("Spotify"), DeviceCommandParser.parse("abre Spotify"))
        assertEquals(DeviceCommand.Alarm(7, 30, null), DeviceCommandParser.parse("pon una alarma a las 7:30"))
        assertEquals(DeviceCommand.Alarm(19, 0, null), DeviceCommandParser.parse("ponme una alarma a las 7 de la tarde"))
        assertEquals(DeviceCommand.Timer(600, "la pasta"), DeviceCommandParser.parse("pon un temporizador de 10 minutos para la pasta"))
        assertEquals(DeviceCommand.Call("mamá"), DeviceCommandParser.parse("llama a mamá"))
        assertEquals(DeviceCommand.Message("Víctor", "Llego en 10 minutos", true), DeviceCommandParser.parse("envíale un whatsapp a Víctor diciendo que llego en 10 minutos"))
        assertEquals(DeviceCommand.PlayMusic("Rosalía", "spotify"), DeviceCommandParser.parse("pon Rosalía en spotify"))
        assertEquals(DeviceCommand.Flashlight(true), DeviceCommandParser.parse("enciende la linterna"))
        assertEquals(DeviceCommand.OpenSettings(DeviceCommand.SettingsPanel.WIFI), DeviceCommandParser.parse("abre el wifi"))
    }

    @Test
    fun `messages said many ways never become tasks`() {
        fun msg(t: String) = DeviceCommandParser.parse(t) as DeviceCommand.Message
        assertEquals(DeviceCommand.Message("Víctor", "Ya estoy abajo", true), msg("envíale un wasap a Víctor diciéndole que ya estoy abajo"))
        assertEquals(DeviceCommand.Message("mi madre", "Llego en 10 minutos", true), msg("escríbele a mi madre por WhatsApp que llego en 10 minutos"))
        assertEquals(DeviceCommand.Message("Ana", "Ya salgo", true), msg("manda un whatsapp a Ana: ya salgo"))
        assertEquals(DeviceCommand.Message("Víctor", "Llego tarde", true), msg("dile a Víctor que llego tarde"))
        assertEquals(DeviceCommand.Message("Ana", "Voy de camino", false), msg("envía un mensaje a Ana diciendo voy de camino"))
        // No text → incomplete (asks "What should I tell…?")
        assertEquals(DeviceCommand.Message("Víctor", "", true), msg("envía un WhatsApp a Víctor"))
        // Odd sentence with a clear intent → incomplete for the LLM, never a task
        assertEquals(TaskAICommand.DEVICE, parse("oye mándale al grupo del curro por whatsapp lo de la reunión").action)
        // Infinitive ("enviar WhatsApp a…", as the user said it) → send, not a task
        assertEquals(DeviceCommand.Message("Víctor", "Ya voy", true), msg("enviar whatsapp a Víctor que ya voy"))
        assertEquals(TaskAICommand.DEVICE, parse("enviar un WhatsApp a Víctor").action)
        // For later it is a task
        assertEquals(TaskAICommand.CREATE, parse("recuérdame enviar un WhatsApp a Ana mañana").action)
        assertEquals(TaskAICommand.CREATE, parse("enviar un whatsapp a Ana mañana").action)
        // A date inside the text doesn't make it a task
        assertEquals(DeviceCommand.Message("Víctor", "Llego mañana a las 9", true), msg("dile a Víctor que llego mañana a las 9"))
    }

    @Test
    fun `doesn't confuse tasks with phone actions`() {
        assertNull(DeviceCommandParser.parse("llama al banco mañana"))
        assertNull(DeviceCommandParser.parse("pon lo del dentista como urgente"))
        assertEquals(TaskAICommand.CREATE, parse("llama al banco mañana").action)
        assertEquals(TaskAICommand.EDIT, parse("abre la tarea del dentista").action)
        val round = DeviceCommand.Message("Ana", "Hola | qué tal", false)
        assertEquals(round, DeviceCommand.parse(round.serialize()))
    }
}
