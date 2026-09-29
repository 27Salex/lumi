package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.CommandSplitter
import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.domain.assistant.DeviceCommandParser
import io.github.salex27.lumi.domain.assistant.TaskActions
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** v3.7: several commands in one sentence, "for when I get home" tasks, action button in reminders. */
class Lumi37Test {

    private val engine = RuleBasedEngine(Random(3))
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0)
    private fun parse(t: String) = engine.parse(t, now)

    @Test
    fun `the user's sentence creates a clean task with an arrive-home reminder`() {
        val c = parse("Una tarea llamada escribir a Roberto para cuando vuelva a casa")
        assertEquals(TaskAICommand.CREATE, c.action)
        assertEquals("Escribir a Roberto", c.targetTitle)
        assertEquals("casa", c.place)
        assertEquals(true, c.placeOnArrive)
        val c2 = parse("crea una tarea que se llame comprar pilas cuando salga del trabajo")
        assertEquals("Comprar pilas", c2.targetTitle)
        assertEquals("trabajo", c2.place)
        assertEquals(false, c2.placeOnArrive)
    }

    @Test
    fun `que se llama doesn't end up in the title`() {
        assertEquals("Hacer Installer BIOS sensor para Robert", parse("créame una tarea que se llama hacer Installer BIOS sensor para Robert").targetTitle)
        // What Gemma returned on the user's phone
        assertEquals("Hacer Installer BIOS sensor para Robert", TaskPhraseParser.cleanTitle("Que se llama hacer Installer BIOS sensor para Robert"))
        assertEquals("Comprar pan", TaskPhraseParser.cleanTitle("una tarea llamada comprar pan"))
        assertEquals("Revisar el informe", TaskPhraseParser.cleanTitle("Tarea: revisar el informe"))
        assertEquals("Llamar a la tarea de Ana", TaskPhraseParser.cleanTitle("Llamar a la tarea de Ana")) // leaves alone what doesn't start like that
        assertEquals("Hacer la tarea de mates", TaskPhraseParser.cleanTitle("Hacer la tarea de mates"))
    }

    @Test
    fun `letting someone know on arrival is a task, not a message now`() {
        assertNull(DeviceCommandParser.parse("dile a Roberto que ya estoy cuando llegue a casa"))
        val c = parse("avisa a Roberto cuando llegue a casa")
        assertEquals(TaskAICommand.CREATE, c.action)
        assertEquals("casa", c.place)
    }

    @Test
    fun `several commands in one sentence`() {
        assertEquals(
            listOf("apunta comprar pan", "pon una alarma a las 7", "dile a Ana que llego tarde"),
            CommandSplitter.split("apunta comprar pan y pon una alarma a las 7 y luego dile a Ana que llego tarde")
        )
        assertEquals(
            listOf("crea una tarea escribir a Roberto cuando llegue a casa", "llama a mamá"),
            CommandSplitter.split("crea una tarea escribir a Roberto y avísame cuando llegue a casa, y llama a mamá")
        )
        // A single command isn't split
        listOf(
            "comprar pan y leche",
            "dile a Ana que compre pan y que me espere",
            "comprar pan, llamar a Ana y acabar el informe", // list of tasks → handled by the brain dump
            "pon el informe como urgente"
        ).forEach { assertEquals(it, 1, CommandSplitter.split(it).size) }
        // "…y ponle prioridad alta" refines the previous command
        assertEquals(listOf("apunta llamar al banco prioridad alta"), CommandSplitter.split("apunta llamar al banco y ponle prioridad alta"))
    }

    @Test
    fun `action button depends on the task`() {
        val write = TaskActions.detect(Task(title = "Escribir a Roberto", placeTrigger = PlaceTrigger("casa")))!!
        assertEquals(DeviceCommand.Message("Roberto", "", true), write.command)
        assertEquals("Escribir a Roberto", write.label)
        val warn = TaskActions.detect(Task(title = "Avisar a Roberto", placeTrigger = PlaceTrigger("casa")))!!
        assertEquals(DeviceCommand.Message("Roberto", "Ya he llegado a casa", true), warn.command)
        val said = TaskActions.detect(Task(title = "Avisa a mamá que ya he salido"))!!
        assertEquals(DeviceCommand.Message("mamá", "Ya he salido", true), said.command)
        assertEquals(DeviceCommand.Call("mamá"), TaskActions.detect(Task(title = "Llamar a mamá"))!!.command)
        assertNull(TaskActions.detect(Task(title = "Comprar pan")))
    }
}
