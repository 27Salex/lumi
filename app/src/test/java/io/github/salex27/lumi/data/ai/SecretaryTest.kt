package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.data.hub.AgentModel
import io.github.salex27.lumi.data.hub.AgentOptions
import io.github.salex27.lumi.data.hub.ModelChoice
import io.github.salex27.lumi.domain.ai.BrainChain
import io.github.salex27.lumi.domain.ai.BrainChoice
import io.github.salex27.lumi.domain.ai.EngineId
import io.github.salex27.lumi.domain.assistant.Delegation
import io.github.salex27.lumi.domain.model.TaskAICommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SecretaryTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    /** A fake hub: returns the scripted answers in order (null = offline) and counts the calls. */
    private class FakeTransport(var configured: Boolean = true, vararg answers: String?) : PcBrainTransport {
        private val queue = ArrayDeque(answers.toList())
        val sent = mutableListOf<String>()
        override fun isConfigured() = configured
        override suspend fun complete(text: String, timeoutMs: Long): String? { sent += text; return queue.removeFirstOrNull() }
    }

    @Test
    fun `typos, plurals and clitics still route to Claude`() {
        for (s in listOf("redáctame un correo para el jefe", "reddacta un corero", "prepárame el resumen de mi semana", "buscame vuelos a Roma"))
            assertEquals(s, SecretaryRouter.Verdict.DELEGATE, SecretaryRouter.judge(s, now))
        for (s in listOf("comprar un regalo", "compra pan", "escribe a mamá que llego tarde", "llama al casero", "dame el resumen de hoy", "resume mi día"))
            assertNull(s, SecretaryRouter.judge(s, now))
    }

    @Test
    fun `a dated draft is ambiguous, an explicit draft verb is not`() {
        assertEquals(SecretaryRouter.Verdict.AMBIGUOUS, SecretaryRouter.judge("prepara el informe del jueves", now))
        assertEquals(SecretaryRouter.Verdict.DELEGATE, SecretaryRouter.judge("redacta el informe para el jueves", now))
    }

    @Test
    fun `the PC brain parses the model's command`() = runBlocking {
        val json = """{"action":"CREATE","targetTitle":"Llamar al dentista"}"""
        val t = FakeTransport(true, "Here you go: $json")
        val cmd = PcBrainEngine(t).interpret("llama al dentista", now)
        assertNotNull(cmd)
        assertEquals(TaskAICommand.CREATE, cmd!!.action)
        assertEquals("Llamar al dentista", cmd.targetTitle)
        assertTrue("the request is sent with the instructions", t.sent.single().contains("llama al dentista"))
    }

    @Test
    fun `a long instruction never cuts the input`() {
        val p = PcBrainEngine.prompt(AssistantPrompts.INTERPRET_SYSTEM, "CURRENT DATE: x\nSENTENCE: llama al dentista")
        assertTrue(p.length <= 3_900)
        assertTrue(p.endsWith("SENTENCE: llama al dentista"))
        assertTrue(p.contains("CREATE_MANY"))
    }

    @Test
    fun `garbage from the PC gives null so the chain moves on`() = runBlocking {
        assertNull(PcBrainEngine(FakeTransport(true, "sorry, I cannot")).interpret("hola", now))
    }

    @Test
    fun `offline hub falls back to the rules and steps aside for a while`() = runBlocking {
        var clock = 0L
        val t = FakeTransport(true, null, null)
        val pc = PcBrainEngine(t, clock = { clock }, backoffMs = 120_000L)
        val orchestrator = AssistantOrchestrator(listOf(pc to 20_000L), RuleBasedEngine())
        val first = orchestrator.interpret("apunta comprar pan mañana", now)
        assertEquals(orchestrator.rulesName, first.engineName) // the rules answered
        assertEquals(1, t.sent.size)
        assertFalse("after a failure the PC is skipped", pc.isAvailable())
        orchestrator.interpret("apunta comprar leche", now)
        assertEquals("no new attempt while backing off", 1, t.sent.size)
        clock = 130_000L
        assertTrue(pc.isAvailable())
    }

    @Test
    fun `an unconfigured hub is never available`() = runBlocking {
        assertFalse(PcBrainEngine(FakeTransport(false)).isAvailable())
    }

    @Test
    fun `the PC brain can be chosen and is the last resort otherwise`() {
        assertEquals(EngineId.PC, BrainChain.order(BrainChoice.PC_CLAUDE).first())
        assertEquals(EngineId.PC, BrainChain.order(BrainChoice.AUTO).last())
        assertEquals(EngineId.entries.size, BrainChain.order(BrainChoice.PC_CLAUDE).toSet().size)
    }

    @Test
    fun `fast choice picks the light model and low effort`() {
        val o = AgentOptions(listOf(AgentModel("claude-opus-5-5", "Opus"), AgentModel("claude-haiku-4-5", "Haiku")), listOf("low", "high"), null, null)
        assertEquals(ModelChoice("claude-haiku-4-5", "low"), ModelChoice.fast(o))
        assertEquals(ModelChoice(), ModelChoice.fast(null))
    }

    @Test
    fun `the hand-off carries date, language, recent turns and tasks only when given`() {
        val with = Delegation.message("redacta un correo", now, "Spanish", listOf("hola" to "Hola", "qué tengo hoy" to "Nada"), listOf("Llamar a Ana", "meeting 10:00 Standup"))
        assertTrue(with.contains("2026-10-05 10:00"))
        assertTrue(with.contains("user language: Spanish"))
        assertTrue(with.contains("Llamar a Ana") && with.contains("Standup"))
        assertTrue(with.contains("Earlier in this chat"))
        assertTrue(with.contains("draft"))
        assertTrue(with.trimEnd().endsWith("redacta un correo"))
        val without = Delegation.message("redacta un correo", now, "Spanish", emptyList(), null)
        assertFalse(without.contains("open tasks"))
        assertFalse(without.contains("Earlier in this chat"))
        assertTrue(Delegation.message("x".repeat(10_000), now, "English", emptyList(), null).length <= 3_800)
    }
}
