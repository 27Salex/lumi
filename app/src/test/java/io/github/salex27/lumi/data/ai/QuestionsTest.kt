package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.AssistantEngine
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.model.TaskAICommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** v3.7.3: questions are answered; memory is only touched when the user asks. */
class QuestionsTest {

    private val now = LocalDateTime.of(2026, 9, 29, 10, 0)
    private val rules = RuleBasedEngine(Random(1))
    private fun parse(t: String) = rules.parse(t, now)

    /** An LLM that always answers the same (as Gemma did on the user's phone). */
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
    fun `the custard question gets answered`() {
        val phrase = "he dejado una natilla fuera de la nevera toda la noche me la puedo comer"
        assertEquals(TaskAICommand.ASK, parse(phrase).action)
        // Even if Gemma says "remember it", nothing is saved to memory
        val c = viaLlm(phrase, TaskAICommand(action = TaskAICommand.REMEMBER, targetTitle = "He dejado una natilla en la nevera"))
        assertEquals(TaskAICommand.ASK, c.action)
        // Not even if it says create a task
        assertEquals(TaskAICommand.ASK, viaLlm(phrase, TaskAICommand(action = TaskAICommand.CREATE, targetTitle = "Natilla")).action)
    }

    /** Real outputs from the 1.0 Gemma batch on the emulator. */
    @Test
    fun `an invented priority or a translated title from the LLM are ignored`() {
        // Gemma: {"action":"CREATE","targetTitle":"revisar la caldera","category":"WORK","priority":"MEDIUM"}
        val boiler = viaLlm("créame una tarea que se llama revisar la caldera",
            TaskAICommand(action = TaskAICommand.CREATE, targetTitle = "revisar la caldera", category = "WORK", priority = "MEDIUM"))
        assertEquals(null, boiler.priority)
        // An explicit priority still counts
        val urgent = viaLlm("revisar la caldera, es urgente",
            TaskAICommand(action = TaskAICommand.CREATE, targetTitle = "revisar la caldera", priority = "MEDIUM"))
        assertEquals("HIGH", urgent.priority)
        // Gemma: "Dentista" for an English sentence → the rules' title
        val dentist = viaLlm("I have the dentist tomorrow at 5",
            TaskAICommand(action = TaskAICommand.CREATE, targetTitle = "Dentista", category = "HEALTH", dueDate = "2026-09-30T17:00", hasTime = true))
        assertEquals("Dentist", dentist.targetTitle)
    }

    /** Real raw outputs of Gemma 4 E2B on the emulator (the title came in newTitle). */
    @Test
    fun `Gemma's title is used even in newTitle and the rules compute the date`() {
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

        // Gemma said Sunday 4 for "el viernes"; the rules know it is Friday 2
        val dinner = AssistantPrompts.parseCommand("""{"action":"CREATE","newTitle":"Cena con los del trabajo","newStatus":"TODO","category":"WORK","dueDate":"2026-10-04","hasTime":false,"description":null}""")!!
        assertEquals("2026-10-02", viaLlm("el viernes cena con los del trabajo", dinner).dueDate)
    }

    @Test
    fun `acordarme de que is a memory`() {
        val c = parse("tengo que acordarme de que el coche está en la planta 3")
        assertEquals(TaskAICommand.REMEMBER, c.action)
        assertEquals("El coche está en la planta 3", c.targetTitle)
    }

    @Test
    fun `questions without question marks`() {
        listOf(
            "es malo dormir con el móvil al lado",
            "el pollo de ayer estará bueno todavía",
            "qué pasa si mezclo lejía con amoniaco",
            "cuánto dura el arroz cocido en la nevera",
            "se puede congelar el pan de molde"
        ).forEach { assertEquals(it, TaskAICommand.ASK, parse(it).action) }
    }

    @Test
    fun `commands and requested memories don't change`() {
        assertEquals(TaskAICommand.CREATE, parse("puedes apuntarme comprar natillas").action)
        assertEquals(TaskAICommand.REMEMBER, parse("recuerda que el wifi de la oficina es Lumi2024").action)
        // If asked explicitly, the LLM may save
        val c = viaLlm("recuerda que he dejado el coche en la planta 2", TaskAICommand(action = TaskAICommand.REMEMBER, targetTitle = "El coche está en la planta 2"))
        assertEquals(TaskAICommand.REMEMBER, c.action)
    }
}
