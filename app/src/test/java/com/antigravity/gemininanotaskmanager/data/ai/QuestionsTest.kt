package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.ai.AssistantEngine
import com.antigravity.gemininanotaskmanager.domain.ai.ReplyRequest
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** v3.7.3: las preguntas se responden; la memoria solo se toca si el usuario lo pide. */
class QuestionsTest {

    private val now = LocalDateTime.of(2026, 9, 29, 10, 0)
    private val rules = RuleBasedEngine(Random(1))
    private fun parse(t: String) = rules.parse(t, now)

    /** Un LLM que siempre contesta lo mismo (como hizo Gemma en el móvil del usuario). */
    private class FakeLlm(val answer: TaskAICommand) : AssistantEngine {
        override val displayName = "Gemma falsa"
        override suspend fun isAvailable() = true
        override suspend fun interpret(prompt: String, now: LocalDateTime) = answer
        override suspend fun writeReply(request: ReplyRequest): String? = null
    }

    private fun viaLlm(prompt: String, llm: TaskAICommand) = runBlocking {
        AssistantOrchestrator(listOf(FakeLlm(llm) to null), rules).interpret(prompt, now).command
    }

    @Test
    fun `la pregunta de la natilla se responde`() {
        val phrase = "he dejado una natilla fuera de la nevera toda la noche me la puedo comer"
        assertEquals(TaskAICommand.ASK, parse(phrase).action)
        // Aunque Gemma diga «recuérdalo», no se guarda en la memoria
        val c = viaLlm(phrase, TaskAICommand(action = TaskAICommand.REMEMBER, targetTitle = "He dejado una natilla en la nevera"))
        assertEquals(TaskAICommand.ASK, c.action)
        // Ni aunque diga crear una tarea
        assertEquals(TaskAICommand.ASK, viaLlm(phrase, TaskAICommand(action = TaskAICommand.CREATE, targetTitle = "Natilla")).action)
    }

    /** Salidas crudas reales de Gemma 4 E2B en el emulador (el título venía en newTitle). */
    @Test
    fun `el titulo de Gemma se usa aunque venga en newTitle y la fecha la calculan las reglas`() {
        val dentist = AssistantPrompts.parseCommand("""{"action":"CREATE","newTitle":"dentista","newStatus":"TODO","category":"HEALTH","dueDate":"2026-09-30T17:00","hasTime":true,"description":null}""")!!
        assertEquals("dentista", dentist.targetTitle)
        val c = viaLlm("mañana tengo dentista a las 5", dentist)
        assertEquals("Dentista", c.targetTitle)
        assertEquals("2026-09-30T17:00", c.dueDate)

        val mother = AssistantPrompts.parseCommand("""{"action":"CREATE","newTitle":"Llamar a mi madre","newStatus":"TODO","category":"PERSONAL","place":"trabajo","placeOnArrive":false}""")!!
        val m = viaLlm("necesito que me recuerdes llamar a mi madre cuando salga del trabajo", mother)
        assertEquals("Llamar a mi madre", m.targetTitle)
        assertEquals("trabajo", m.place)
        assertEquals(false, m.placeOnArrive)

        // Gemma dijo domingo 4 para «el viernes»; las reglas saben que es el viernes 2
        val dinner = AssistantPrompts.parseCommand("""{"action":"CREATE","newTitle":"Cena con los del trabajo","newStatus":"TODO","category":"WORK","dueDate":"2026-10-04","hasTime":false,"description":null}""")!!
        assertEquals("2026-10-02", viaLlm("el viernes cena con los del trabajo", dinner).dueDate)
    }

    @Test
    fun `acordarme de que es un recuerdo`() {
        val c = parse("tengo que acordarme de que el coche está en la planta 3")
        assertEquals(TaskAICommand.REMEMBER, c.action)
        assertEquals("El coche está en la planta 3", c.targetTitle)
    }

    @Test
    fun `preguntas sin interrogaciones`() {
        listOf(
            "es malo dormir con el móvil al lado",
            "el pollo de ayer estará bueno todavía",
            "qué pasa si mezclo lejía con amoniaco",
            "cuánto dura el arroz cocido en la nevera",
            "se puede congelar el pan de molde"
        ).forEach { assertEquals(it, TaskAICommand.ASK, parse(it).action) }
    }

    @Test
    fun `las ordenes y los recuerdos pedidos no cambian`() {
        assertEquals(TaskAICommand.CREATE, parse("puedes apuntarme comprar natillas").action)
        assertEquals(TaskAICommand.REMEMBER, parse("recuerda que el wifi de la oficina es Lumi2024").action)
        // Si lo pide expresamente, el LLM sí puede guardar
        val c = viaLlm("recuerda que he dejado el coche en la planta 2", TaskAICommand(action = TaskAICommand.REMEMBER, targetTitle = "El coche está en la planta 2"))
        assertEquals(TaskAICommand.REMEMBER, c.action)
    }
}
