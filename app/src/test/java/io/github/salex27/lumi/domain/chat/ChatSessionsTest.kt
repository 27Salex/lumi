package io.github.salex27.lumi.domain.chat

import io.github.salex27.lumi.data.backup.ChatBackup
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.domain.assistant.ConversationContext
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #8: chat sessions, the token budget for the local model and the backup of chats. */
class ChatSessionsTest {

    @Test
    fun `sessions go stale after a long idle gap`() {
        val now = 10 * 3_600_000L
        assertFalse(SessionPolicy.isStale(now - 60_000L, now))
        assertFalse(SessionPolicy.isStale(now - SessionPolicy.IDLE_GAP_MILLIS, now))
        assertTrue(SessionPolicy.isStale(now - SessionPolicy.IDLE_GAP_MILLIS - 1, now))
    }

    @Test
    fun `automatic titles come from the first message`() {
        assertEquals("Qué tiempo hace mañana", SessionPolicy.titleFrom("¿qué tiempo hace mañana?"))
        assertEquals("Remind me to call the dentist", SessionPolicy.titleFrom("  remind me to   call the dentist. "))
        val long = SessionPolicy.titleFrom("remind me to call the dentist tomorrow at five and then buy some bread on the way home")
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= 43)
        assertFalse(long.contains("  "))
        assertEquals("", SessionPolicy.titleFrom("   "))
    }

    private fun turns(n: Int, size: Int = 150) = (1..n).map { ChatTurn("question $it " + "x".repeat(size), "answer $it " + "y".repeat(size), it.toLong()) }

    @Test
    fun `the budget keeps the newest turns and reports the overflow oldest first`() {
        val all = turns(12)
        val plan = ChatContextBuilder.plan(all, summary = "", budgetTokens = 360)
        assertTrue(plan.included.isNotEmpty())
        assertTrue(plan.overflow.isNotEmpty())
        assertEquals(all, plan.overflow + plan.included) // nothing lost, order kept
        assertEquals(all.last(), plan.included.last())
        val used = plan.included.sumOf { ChatContextBuilder.estimateTokens(ChatContextBuilder.line(it)) + 1 }
        assertTrue("used $used", used <= 360)
    }

    @Test
    fun `short chats fit whole and a long summary eats into the budget`() {
        assertTrue(ChatContextBuilder.plan(turns(3, 20), "").overflow.isEmpty())
        val withSummary = ChatContextBuilder.plan(turns(12), "s".repeat(600), 360)
        val without = ChatContextBuilder.plan(turns(12), "", 360)
        assertTrue(withSummary.included.size < without.included.size)
    }

    @Test
    fun `the newest turn always goes in even when it is long`() {
        val plan = ChatContextBuilder.plan(turns(2, 5_000), "", 50)
        assertEquals(1, plan.included.size)
        assertEquals(1, plan.overflow.size)
    }

    @Test
    fun `the note has the summary, the turns and the last task`() {
        val note = ChatContextBuilder.note(listOf(ChatTurn("buy bread tomorrow", "Done")), "User planned a trip to Rome.", lastTask = "Buy bread")
        assertTrue(note.startsWith("[EARLIER IN THIS CHAT: User planned a trip to Rome.]"))
        assertTrue(note.contains("User: «buy bread tomorrow» → Lumi: «Done»"))
        assertTrue(note.contains("LAST TASK: «Buy bread»"))
        assertEquals("", ChatContextBuilder.note(emptyList(), ""))
    }

    @Test
    fun `the rule summary keeps the user requests and stays short`() {
        val s = ChatContextBuilder.ruleSummary("Earlier stuff.", turns(40, 100))
        assertTrue(s.length <= 600)
        assertTrue(s.contains("question 40"))
        assertTrue(ChatContextBuilder.ruleSummary("", listOf(ChatTurn("plan my trip", "ok"))).contains("plan my trip"))
    }

    @Test
    fun `stored messages rebuild into turns skipping what the summary covers`() {
        val msgs = listOf(
            StoredMessage(false, "Hi, how can I help?", 1), // greeting-like reply before any user message: ignored
            StoredMessage(true, "remind me to buy bread", 10),
            StoredMessage(false, "Done: Buy bread", 12, TaskAICommand.CREATE, listOf(7)),
            StoredMessage(true, "move it to friday", 20),
            StoredMessage(false, "Moved", 22, TaskAICommand.RESCHEDULE, listOf(7)),
            StoredMessage(false, "Anything else?", 23),
            StoredMessage(true, "what's the weather", 30)
        )
        val all = SessionTurns.rebuild(msgs)
        assertEquals(3, all.size)
        assertEquals("Moved Anything else?", all[1].reply)
        assertEquals(TaskAICommand.RESCHEDULE, all[1].action)
        assertEquals(listOf(7L), all[0].taskIds)
        assertEquals("", all[2].reply) // still waiting for the reply
        // The first turn was folded at its record time (between the user message and the reply)
        val rest = SessionTurns.rebuild(msgs, foldedUntil = 11)
        assertEquals(listOf("move it to friday", "what's the weather"), rest.map { it.user })
    }

    @Test
    fun `a resumed session restores follow-ups only while fresh`() {
        var clock = 100_000L
        val ctx = ConversationContext(ttlMillis = 10_000, clock = { clock })
        ctx.restore(listOf(ConversationContext.Turn("remind me to buy bread", "Done", TaskAICommand.CREATE, 95_000)), "Summary.", Task(id = 7, title = "Buy bread"), 95_000)
        assertEquals(7L, ctx.lastTaskId())
        assertEquals(TaskAICommand.CREATE, ctx.lastAction())
        assertEquals("Summary.", ctx.summary)
        clock = 200_000
        assertNull(ctx.lastTaskId())
        assertTrue(ctx.promptNote().contains("Summary."))
        ctx.clear()
        assertEquals("", ctx.promptNote())
    }

    @Test
    fun `folding replaces the oldest turns with the summary`() {
        var clock = 0L
        val ctx = ConversationContext(clock = { ++clock })
        repeat(10) { ctx.record("question $it " + "x".repeat(200), "answer " + "y".repeat(200), TaskAICommand.ASK, null) }
        val overflow = ctx.overflow()
        assertTrue(overflow.size >= 3)
        assertEquals("question 0", overflow.first().user.substringBefore(" x"))
        // A turn recorded while the summary was being written survives the fold
        ctx.record("late question", "late answer", TaskAICommand.ASK, null)
        ctx.fold(overflow.last().at, "The user asked many questions.")
        assertEquals(11 - overflow.size, ctx.sessionTurns().size)
        assertEquals("late question", ctx.sessionTurns().last().user)
        assertTrue(ctx.promptNote().startsWith("[EARLIER IN THIS CHAT: The user asked many questions.]"))
        assertTrue(ctx.overflow().size < 3)
    }

    @Test
    fun `chats survive a backup round trip`() {
        val sessions = listOf(ChatSessionEntity(id = 4, title = "Trip", createdAt = 1, updatedAt = 5, summary = "Rome", summaryUntil = 2))
        val messages = listOf(
            ChatMessageEntity(id = 1, sessionId = 4, role = ChatMessageEntity.ROLE_USER, text = "plan «Rome»\nnow", createdAt = 2),
            ChatMessageEntity(id = 2, sessionId = 4, role = ChatMessageEntity.ROLE_ASSISTANT, text = "Sure", createdAt = 3, engine = "Gemma", action = "ASK", taskIds = "9"),
            ChatMessageEntity(id = 3, sessionId = 99, role = ChatMessageEntity.ROLE_USER, text = "orphan", createdAt = 4)
        )
        val back = ChatBackup.decode(ChatBackup.encode(sessions, messages))
        assertEquals(1, back.size)
        val s = back.single()
        assertEquals("Trip", s.title)
        assertEquals("Rome", s.summary)
        assertEquals(2L, s.summaryUntil)
        assertEquals(listOf("plan «Rome»\nnow", "Sure"), s.messages.map { it.text })
        val entity = ChatBackup.toEntity(s.messages[1], 42)
        assertEquals(42L, entity.sessionId)
        assertEquals("", entity.taskIds) // tasks get new ids on import
        assertEquals("ASK", entity.action)
    }
}
