package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.ConversationContext
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.LanguageDetector
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** 1.0: Lumi understands English (language detection, dates, commands, follow-ups, quick math). */
class EnglishTest {

    private val engine = RuleBasedEngine(Random(1))
    // Monday 28/09/2026 10:00
    private val now = LocalDateTime.of(2026, 9, 28, 10, 0)
    private fun parse(t: String) = engine.parse(t, now)

    @Before fun english() { ReplyLanguage.current = Lang.EN }
    @After fun reset() { ReplyLanguage.current = Lang.ES }

    // ── Language detection ─────────────────────────────────────────────────

    @Test
    fun `detects English and Spanish`() {
        assertEquals(Lang.EN, LanguageDetector.detect("remind me to call mum tomorrow at 5"))
        assertEquals(Lang.EN, LanguageDetector.detect("what's the weather like today?"))
        assertEquals(Lang.ES, LanguageDetector.detect("recuérdame llamar a mamá mañana a las 5"))
        assertEquals(Lang.ES, LanguageDetector.detect("¿qué tiempo hace hoy?"))
    }

    @Test
    fun `ambiguous short answers keep the fallback`() {
        assertEquals(Lang.ES, LanguageDetector.detect("ok", Lang.ES))
        assertEquals(Lang.EN, LanguageDetector.detect("ok", Lang.EN))
    }

    // ── Dates ──────────────────────────────────────────────────────────────

    @Test
    fun `tomorrow at 5 pm`() {
        val r = EnglishDateParser.parse("call the bank tomorrow at 5 pm", now)!!
        assertEquals(LocalDateTime.of(2026, 9, 29, 17, 0), r.dateTime)
        assertTrue(r.hasTime)
    }

    @Test
    fun `weekday names go forward`() {
        val r = EnglishDateParser.parse("dentist on friday", now)!!
        assertEquals(LocalDate.of(2026, 10, 2), r.dateTime.toLocalDate())
        assertFalse(r.hasTime)
    }

    @Test
    fun `month names`() {
        val r = EnglishDateParser.parse("exam on October 15", now)!!
        assertEquals(LocalDate.of(2026, 10, 15), r.dateTime.toLocalDate())
    }

    @Test
    fun `next month, weekends and months ahead`() {
        val m = EnglishDateParser.parse("renew my passport next month", now)!!
        assertEquals(LocalDate.of(2026, 10, 28), m.dateTime.toLocalDate())
        assertEquals("renew my passport", m.remainingText)
        assertEquals(LocalDate.of(2026, 10, 3), EnglishDateParser.parse("clean the garage this weekend", now)!!.dateTime.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 10), EnglishDateParser.parse("visit grandma next weekend", now)!!.dateTime.toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 28), EnglishDateParser.parse("dentist check-up in 3 months", now)!!.dateTime.toLocalDate())
    }

    @Test
    fun `no date in English returns null`() {
        assertNull(EnglishDateParser.parse("buy milk", now))
    }

    // ── Commands ───────────────────────────────────────────────────────────

    @Test
    fun `remind me creates a clean timed task`() {
        val c = parse("remind me to call the bank tomorrow at 5 pm")
        assertEquals(TaskAICommand.CREATE, c.action)
        assertEquals("Call the bank", c.targetTitle)
        assertEquals("2026-09-29T17:00", c.dueDate)
        assertTrue(c.hasTime)
    }

    @Test
    fun `English titles drop a leading the`() {
        assertEquals("Dentist", parse("I have the dentist tomorrow at 5").targetTitle)
        assertEquals("Dentist", TaskPhraseParser.cleanTitle("the dentist"))
    }

    @Test
    fun `remember that is a memory, not a task`() {
        val c = parse("I need to remember that the car is on level 3")
        assertEquals(TaskAICommand.REMEMBER, c.action)
        assertEquals("The car is on level 3", c.targetTitle)
        assertTrue(AssistantIntents.asksToRemember("I need to remember that the car is on level 3"))
        assertEquals(TaskAICommand.CREATE, parse("remind me to buy bread").action)
    }

    @Test
    fun `shopping list items become buy tasks`() {
        val c = parse("add milk, bread and coffee to my shopping list")
        assertEquals(TaskAICommand.CREATE_MANY, c.action)
        assertEquals(listOf("Buy milk", "Buy bread", "Buy coffee"), c.items.map { it.targetTitle })
        assertEquals("Buy milk", TaskPhraseParser.cleanTitle("Add milk to shopping list"))
        assertEquals("Comprar leche", TaskPhraseParser.cleanTitle("añadir leche a la lista de la compra"))
    }

    @Test
    fun `urgent sets high priority`() {
        val c = parse("add a task finish the report, it's urgent")
        assertEquals(TaskAICommand.CREATE, c.action)
        assertEquals("HIGH", c.priority)
    }

    @Test
    fun `when I get home is a place reminder`() {
        val c = parse("remind me to water the plants when I get home")
        assertEquals(TaskAICommand.CREATE, c.action)
        assertEquals("casa", c.place)
        assertTrue(c.placeOnArrive)
        assertEquals("Water the plants", c.targetTitle)
    }

    @Test
    fun `weather questions are not tasks`() {
        assertNotEquals(TaskAICommand.CREATE, parse("what's the weather like tomorrow?").action)
        assertNotEquals(TaskAICommand.CREATE, parse("will it rain this afternoon?").action)
    }

    @Test
    fun `phone actions are not tasks`() {
        assertEquals(TaskAICommand.DEVICE, parse("set a timer for 10 minutes").action)
        assertEquals(TaskAICommand.DEVICE, parse("turn on the flashlight").action)
        assertEquals(TaskAICommand.DEVICE, parse("call mum").action)
    }

    @Test
    fun `marking done completes a task`() {
        assertEquals(TaskAICommand.UPDATE_STATUS, parse("I finished the report").action)
    }

    // ── Follow-ups ─────────────────────────────────────────────────────────

    @Test
    fun `follow-ups refer to the last task`() {
        val move = parse("move it to friday")
        assertEquals(TaskAICommand.RESCHEDULE, move.action)
        assertTrue(move.refersToLast)
        assertEquals(TaskAICommand.SET_PRIORITY, parse("make it urgent").action)
        assertTrue(parse("mark it as done").refersToLast)
        // Fillers before the correction
        assertTrue(parse("actually, move it to friday").refersToLast)
        assertEquals(TaskAICommand.SET_PRIORITY, parse("no wait, make it urgent").action)
        ReplyLanguage.current = Lang.ES
        assertTrue(parse("mejor muévela al viernes").refersToLast)
    }

    @Test
    fun `the conversation remembers the last task for a while`() {
        var clock = 0L
        val ctx = ConversationContext(ttlMillis = 1_000, clock = { clock })
        ctx.record("remind me to buy bread", "Done", TaskAICommand.CREATE, Task(id = 7, title = "Buy bread"))
        assertEquals(7L, ctx.lastTaskId())
        assertTrue(ctx.promptNote().contains("Buy bread"))
        clock = 2_000
        assertNull(ctx.lastTaskId())
        // The turn stays in the session's context, but the stale task is no longer offered for "it"
        assertTrue(ctx.promptNote().contains("remind me to buy bread"))
        assertTrue(!ctx.promptNote().contains("LAST TASK"))
    }

    // ── Quick math ─────────────────────────────────────────────────────────

    @Test
    fun `quick math in English`() {
        assertEquals("15% of 80 is 12.", QuickMath.answer("what's 15% of 80"))
        assertTrue(QuickMath.answer("234 times 12")!!.contains("2808"))
    }
}
