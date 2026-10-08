package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.IntentRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class IntentRouterTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    /**
     * The evaluation set (src/test/resources/intent_eval.tsv): the rules must catch every AGENT / OPINION / UNSURE
     * sentence and leave every other one to the pipeline (a wrong rule is worse than no rule).
     */
    @Test
    fun `evaluation set`() {
        val rows = javaClass.classLoader!!.getResource("intent_eval.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').let { (label, text) -> IntentRoute.valueOf(label) to text } }
        assertTrue("the set should have ~100 sentences", rows.size >= 95)
        val ruled = setOf(IntentRoute.AGENT, IntentRoute.OPINION, IntentRoute.UNSURE)
        val wrong = rows.mapNotNull { (label, text) ->
            val expected = label.takeIf { it in ruled }
            val got = IntentRouter.classify(text, now)
            if (got != expected) "«$text»: expected $expected, got $got" else null
        }
        assertTrue("${wrong.size} of ${rows.size} wrong:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** Secretary set (secretary_eval.tsv): clear secretary jobs go to Claude, everything Lumi does itself is left alone. */
    @Test
    fun `secretary evaluation set`() {
        val rows = javaClass.classLoader!!.getResource("secretary_eval.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('	').let { (label, text) -> IntentRoute.valueOf(label) to text } }
        assertTrue(rows.size >= 60)
        val ruled = setOf(IntentRoute.DELEGATE, IntentRoute.AGENT, IntentRoute.OPINION, IntentRoute.UNSURE)
        val wrong = rows.mapNotNull { (label, text) ->
            val expected = label.takeIf { it in ruled }
            val got = IntentRouter.classify(text, now)
            if (got != expected) "«$text»: expected $expected, got $got" else null
        }
        val delegates = rows.filter { it.first == IntentRoute.DELEGATE }
        val caught = delegates.count { IntentRouter.classify(it.second, now) == IntentRoute.DELEGATE }
        val falsePositives = rows.count { it.first != IntentRoute.DELEGATE && IntentRouter.classify(it.second, now) == IntentRoute.DELEGATE }
        println("SECRETARY_EVAL delegate recall $caught/${delegates.size}, false delegations $falsePositives, correct ${rows.size - wrong.size}/${rows.size}")
        assertTrue("${wrong.size} of ${rows.size} wrong:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `the agent gets the request without its name`() {
        assertEquals("Quiero hacer una nueva web", IntentRouter.agentRequest("Quiero hacer una nueva web con Claude"))
        assertEquals("arregla el build que falla", IntentRouter.agentRequest("Claude, arregla el build que falla"))
        assertEquals("actualice las dependencias", IntentRouter.agentRequest("dile a Claude que actualice las dependencias"))
        assertEquals("fix the failing build", IntentRouter.agentRequest("ask Claude to fix the failing build"))
        assertEquals("haz un script para renombrar fotos", IntentRouter.agentRequest("haz un script para renombrar fotos, que lo haga Claude"))
        assertEquals("el login no funciona", IntentRouter.agentRequest("pásale esto a Claude: el login no funciona"))
        // Only the session: the whole sentence goes
        assertEquals("abre una sesión de Claude", IntentRouter.agentRequest("abre una sesión de Claude"))
        assertNull(IntentRouter.agentRequest("what is Claude Code?"))
        // Other agent names can be taught
        assertEquals("review my essay", IntentRouter.agentRequest("ask Nova to review my essay", listOf("Nova")))
    }

    @Test
    fun `a wish becomes a task title`() {
        assertEquals("Programar una nueva web", IntentRouter.wishObject("Me gustaría programar una nueva web"))
        assertEquals("Build a website for my band", IntentRouter.wishObject("I'd like to build a website for my band."))
        assertEquals("comprar pan", IntentRouter.wishObject("comprar pan"))
    }

    @Test
    fun `a bare search request refers to the previous question`() {
        for (s in listOf("pues busca un sitio", "busca un sitio", "búscame uno", "vale busca algún restaurante", "search for a place", "then look up some options"))
            assertTrue(s, IntentRouter.isBareSearchFollowUp(s))
        for (s in listOf("busca un sitio para el dentista", "busca mis llaves", "search for trains to Madrid", "recuérdame buscar un sitio"))
            assertFalse(s, IntentRouter.isBareSearchFollowUp(s))
    }
}
