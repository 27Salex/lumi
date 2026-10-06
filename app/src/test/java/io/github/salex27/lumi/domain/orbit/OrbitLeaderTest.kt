package io.github.salex27.lumi.domain.orbit

import io.github.salex27.lumi.domain.orbit.OrbitLeader.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitLeaderTest {
    private val claude = LeaderAgent(1, "Claude", AgentBackendKind.CLAUDE_PC)
    private val writer = LeaderAgent(2, "Nova", AgentBackendKind.LUMI, "Writes and edits texts, emails and posts")
    private val team = listOf(claude, writer)

    @Test
    fun `small talk and quick questions stay with lumi`() {
        val d = OrbitLeader.decide("good morning, how are you?", team, emptyList())
        assertNull(d.agent)
        assertEquals(Why.NONE, d.why)
        assertFalse(d.complex)
    }

    @Test
    fun `code work goes to claude code, in both languages`() {
        assertEquals(claude, OrbitLeader.decide("the build fails after the last commit, can you look?", team, emptyList()).agent)
        assertEquals(Why.CODE, OrbitLeader.decide("arregla el bug del login en el repo", team, emptyList()).why)
        // Without a capable agent there is nobody to hand code to
        assertNull(OrbitLeader.decide("fix the build", listOf(writer), emptyList()).agent)
    }

    @Test
    fun `big jobs are delegations`() {
        val d = OrbitLeader.decide("Quiero hacer una nueva web para mi tienda", team, emptyList())
        assertEquals(claude, d.agent)
        assertTrue(d.complex)
        assertTrue(OrbitLeader.isComplex("research the best e-ink tablets, then compare prices, then write a summary"))
        assertFalse(OrbitLeader.isComplex("what time is it in Tokyo?"))
    }

    @Test
    fun `an agent's purpose attracts matching messages`() {
        val d = OrbitLeader.decide("draft two emails for the newsletter posts", team, emptyList())
        assertEquals(writer, d.agent)
        assertEquals(Why.PURPOSE, d.why)
    }

    @Test
    fun `past choices win, including choosing lumi itself, newest first`() {
        val examples = listOf(RoutingExample("plan my trip to Rome next month", "Nova", 1))
        val d = OrbitLeader.decide("plan my trip to Paris next month", team, examples)
        assertEquals(writer, d.agent)
        assertEquals(Why.LEARNED, d.why)
        // The user later preferred Lumi for code questions like this one
        val lumi = listOf(RoutingExample("explain what a git branch is", OrbitLeader.LUMI, 5))
        val e = OrbitLeader.decide("explain what a git rebase is", team, lumi)
        assertNull(e.agent)
        assertEquals(Why.LEARNED, e.why)
        // An example for an agent that isn't in this Orbit is ignored
        assertEquals(Why.CODE, OrbitLeader.decide("explain the git branch", listOf(claude), listOf(RoutingExample("explain the git branch", "Ghost", 1))).why)
    }

    @Test
    fun `the llm pick is only a tie breaker`() {
        assertEquals(writer, OrbitLeader.decide("which one would you pick for my sister", team, emptyList(), llmPick = "nova.").agent)
        assertNull(OrbitLeader.decide("which one would you pick for my sister", team, emptyList(), llmPick = "Lumi").agent)
        // Rules beat the LLM
        assertEquals(claude, OrbitLeader.decide("fix the gradle build", team, emptyList(), llmPick = "Nova").agent)
        val (system, user) = OrbitLeader.llmPrompt("hi there", team)
        assertTrue(system.contains("ONE name"))
        assertTrue(user.contains("Nova: Writes and edits") && user.contains("Claude: Claude Code"))
    }

    @Test
    fun `web research without a mention goes to claude`() {
        val d = OrbitLeader.decide("best high-speed trains to Madrid tomorrow", team, emptyList())
        assertEquals(claude, d.agent)
        assertEquals(Why.WEB, d.why)
        assertEquals(claude, OrbitLeader.decide("busca el mejor hotel barato en Sevilla", team, emptyList()).agent)
        assertNull(OrbitLeader.decide("good morning, how are you?", team, emptyList()).agent)
    }

    @Test
    fun `short answer after an agent question is a follow up`() {
        assertTrue(OrbitLeader.isFollowUp("barcelona", "Which city do you leave from?", true))
        assertFalse(OrbitLeader.isFollowUp("barcelona", "Here are the trains.", true))
        assertFalse(OrbitLeader.isFollowUp("barcelona", "Which city?", false))
        assertFalse(OrbitLeader.isFollowUp("and what about flights to Rome?", "Which city?", true))
    }
}
