package io.github.salex27.lumi.domain.orbit

import io.github.salex27.lumi.data.backup.ChatBackup
import io.github.salex27.lumi.data.local.AgentEntity
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.data.local.OrbitMemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitTest {
    private val claude = OrbitAgent(1, "Claude")
    private val claudeCode = OrbitAgent(2, "Claude Code")
    private val gema = OrbitAgent(3, "Gema")

    @Test
    fun `a single-agent orbit sends everything to its agent`() {
        val r = OrbitRouter.route("fix the failing build", listOf(claude))
        assertEquals(listOf(claude), r.agents)
        assertEquals("fix the failing build", r.text)
    }

    @Test
    fun `in a group lumi answers unless an agent is mentioned`() {
        assertTrue(OrbitRouter.route("what do you all think?", listOf(claude, gema)).toLumi)
        val r = OrbitRouter.route("@Gema, what do you think?", listOf(claude, gema))
        assertEquals(listOf(gema), r.agents)
        assertEquals("what do you think?", r.text)
        // Nobody in the Orbit: Lumi
        assertTrue(OrbitRouter.route("hi", emptyList()).toLumi)
    }

    @Test
    fun `mentions are case and accent insensitive, longest name wins, no duplicates`() {
        val members = listOf(claude, claudeCode, gema)
        assertEquals(listOf(claudeCode), OrbitRouter.mentions("@claude code please review", members))
        assertEquals(listOf(claude), OrbitRouter.mentions("@CLAUDE review", members))
        assertEquals(listOf(gema), OrbitRouter.mentions("@Gemá hola", members))
        assertEquals(listOf(gema, claude), OrbitRouter.mentions("@Gema then @Claude and @Gema again", members))
        // An address or a longer word is not a mention
        assertTrue(OrbitRouter.mentions("mail me at x@claudeville.com", members).isEmpty())
        assertTrue(OrbitRouter.mentions("@Claudia hi", members).isEmpty())
    }

    @Test
    fun `leading mentions are stripped, inner ones stay`() {
        val members = listOf(claude, gema)
        assertEquals("compare with what Gema said", OrbitRouter.stripLeadingMentions("@Claude @gema: compare with what Gema said", members))
        val r = OrbitRouter.route("ask @Claude about it", members)
        assertEquals(listOf(claude), r.agents)
        assertEquals("ask @Claude about it", r.text)
        // Only a mention: the agent still gets something
        assertEquals("@Claude", OrbitRouter.route("@Claude", members).text)
    }

    @Test
    fun `thread ids round trip`() {
        assertEquals("orbit-12-3", OrbitThreads.id(12, 3))
        assertEquals(12L to 3L, OrbitThreads.parse("orbit-12-3"))
        assertNull(OrbitThreads.parse("other-1-2"))
        assertNull(OrbitThreads.parse(null))
    }

    @Test
    fun `claude gets what happened since its last reply, without the message being sent`() {
        val lines = listOf(
            OrbitLine("User", "first", isUser = true),
            OrbitLine("Claude", "old answer", agentId = 1),
            OrbitLine("User", "@Gema idea?", isUser = true),
            OrbitLine("Gema", "use Rust", agentId = 3),
            OrbitLine("User", "@Claude thoughts?", isUser = true)
        )
        val ctx = OrbitContext.sinceLastReply(lines, agentId = 1)
        assertEquals("User: @Gema idea?\nGema: use Rust", ctx)
        assertFalse(ctx.contains("thoughts"))
        // Nothing new since its last reply
        assertEquals("", OrbitContext.sinceLastReply(lines.take(2) + OrbitLine("User", "next", isUser = true), 1))
        // Recent lines keep the newest within the budget
        assertEquals("Gema: use Rust", OrbitContext.recent(lines, maxChars = 20))
    }

    @Test
    fun `defaults and palette keys`() {
        assertEquals(AgentBackendKind.CLAUDE_PC, AgentBackendKind.of("CLAUDE_PC"))
        assertNull(AgentBackendKind.of("NOPE"))
        assertEquals(AgentPalette.SKY, AgentPalette.of("bogus"))
        assertEquals(AgentFaceStyle.SQUARE, AgentFaceStyle.of("SQUARE"))
    }

    @Test
    fun `agents and their orbits survive a backup round trip`() {
        val orbit = ChatSessionEntity(id = 7, kind = ChatSessionEntity.KIND_ORBIT, title = "Web", createdAt = 100, updatedAt = 200)
        val agent = AgentEntity(id = 4, name = "Claude", backend = "CLAUDE_PC", color = "ORANGE", face = "SPARK", createdAt = 55)
        val agents = ChatBackup.decodeAgents(ChatBackup.encodeAgents(listOf(agent), listOf(OrbitMemberEntity(7, 4)), listOf(orbit)))
        assertEquals(listOf(100L), agents.single().orbits)
        assertEquals("Claude", ChatBackup.toEntity(agents.single()).name)
        val msg = ChatMessageEntity(id = 1, sessionId = 7, role = ChatMessageEntity.ROLE_AGENT, text = "hi", createdAt = 150, agentId = 4)
        val s = ChatBackup.decode(ChatBackup.encode(listOf(orbit), listOf(msg)) { if (it == 4L) 55L else null }).single()
        assertEquals(55L, s.messages.single().agentKey)
        // On import the agent has a new id, found by its key
        assertEquals(9L, ChatBackup.toEntity(s.messages.single(), 70) { if (it == 55L) 9L else null }.agentId)
    }

    @Test fun contextHeaderHasTimeLanguageAndOptionalTasks() {
        val now = java.time.LocalDateTime.of(2026, 10, 7, 9, 30, 15)
        assertEquals("[Now: 2026-10-07 09:30 · user language: Spanish]", OrbitContext.header(now, "Spanish", null))
        val h = OrbitContext.header(now, "English", (1..10).map { "Task $it" })
        assertTrue(h.contains("Task 1; Task 2") && h.contains("+2 more"))
    }
}
