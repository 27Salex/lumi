package io.github.salex27.lumi.data.hub

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class HubProtocolTest {

    @Test
    fun `events parse and unknown fields are ignored`() {
        val e = HubEvent.parse("""{"id":7,"type":"ask","source":"Claude Code","ask_id":"ab12","question":"Deploy?","options":["Yes","No"],"extra":1}""")!!
        assertEquals(7L, e.id)
        assertEquals(HubEvent.ASK, e.type)
        assertEquals("ab12", e.askId)
        assertEquals(listOf("Yes", "No"), e.options)
        assertNull(HubEvent.parse("not json"))
        // null fields from Python (None) are fine
        assertNull(HubEvent.parse("""{"id":1,"type":"message","source":"x","text":"hi","link":null}""")!!.link)
    }

    @Test
    fun `agent content is sanitized on the phone too`() {
        val e = HubEvent.parse(
            """{"id":1,"type":"message","source":"","text":"pay ‮exe.txt\u0007","link":"intent://scan#Intent;end",
               "options":["a","a"," ","b","c","d","e","f","g"]}"""
        )!!
        assertEquals("Agent", e.source)
        assertEquals("pay exe.txt", e.text)
        assertNull(e.link) // only http(s) links survive
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), e.options)
        assertEquals(16_000, HubEvent(text = "x".repeat(20_000)).sanitized().text!!.length)
    }

    @Test
    fun `safe links`() {
        assertTrue(HubSafety.isSafeLink("https://github.com/27Salex/lumi/pull/3"))
        listOf("javascript:alert(1)", "file:///sdcard/x", "https://user:pw@evil.com", "content://x", "tel:123", "https://a b")
            .forEach { assertFalse(it, HubSafety.isSafeLink(it)) }
    }

    @Test
    fun `sse frames`() {
        val p = SseParser()
        assertNull(p.feed(": connected"))
        assertNull(p.feed(""))
        assertNull(p.feed("id: 12"))
        assertNull(p.feed("event: message"))
        assertNull(p.feed("data: {\"a\":1}"))
        val f = p.feed("")!!
        assertEquals(12L, f.id)
        assertEquals("message", f.event)
        assertEquals("{\"a\":1}", f.data)
        // multi-line data joins with newlines; the state resets after a frame
        p.feed("data: one"); p.feed("data: two")
        val g = p.feed("")!!
        assertEquals("one\ntwo", g.data)
        assertNull(g.id)
    }

    @Test
    fun `hub address`() {
        assertEquals("https://pc.tailnet.ts.net:8443", HubSafety.normalizeAddress(" pc.tailnet.ts.net:8443/ "))
        assertEquals("https://pc.tailnet.ts.net", HubSafety.normalizeAddress("https://pc.tailnet.ts.net"))
        assertEquals("http://10.0.2.2:8766", HubSafety.normalizeAddress("http://10.0.2.2:8766"))
        assertNull(HubSafety.normalizeAddress("http://pc.tailnet.ts.net")) // cleartext only for the emulator loopback
        assertNull(HubSafety.normalizeAddress("https://pc/path"))
        assertNull(HubSafety.normalizeAddress(""))
        assertFalse(HubConfig("pc.ts.net", "").isConfigured)
        assertTrue(HubConfig("pc.ts.net", "t").isConfigured)
    }

    @Test
    fun `a proposed task is built by rules`() {
        ReplyLanguage.app = Lang.EN
        val now = LocalDateTime.of(2026, 10, 5, 10, 0)
        val t = HubTaskProposal.toTask("Review the PR", "tomorrow at 5pm", "Check tests", "Claude Code", now, ZoneOffset.UTC)
        assertEquals("Review the PR", t.title)
        assertEquals(LocalDateTime.of(2026, 10, 6, 17, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), t.dueAt)
        assertTrue(t.dueHasTime)
        assertTrue(t.description.startsWith("Check tests"))
        assertTrue(t.description.contains("Claude Code"))
        val es = HubTaskProposal.toTask("Llamar", "mañana", null, "x", now, ZoneOffset.UTC)
        assertEquals(LocalDateTime.of(2026, 10, 6, 0, 0).toLocalDate(), java.time.Instant.ofEpochMilli(es.dueAt!!).atZone(ZoneOffset.UTC).toLocalDate())
        // Unknown due text: no date, the text is kept
        val odd = HubTaskProposal.toTask("Ship", "when the moon is full", null, "x", now, ZoneOffset.UTC)
        assertNull(odd.dueAt)
        assertTrue(odd.description.contains("when the moon is full"))
    }

    @Test
    fun `payload round trip and the question lookup fragment`() {
        val e = HubEvent(type = HubEvent.ASK, source = "Claude", askId = "a1b2", options = listOf("Yes"))
        val p = HubPayload.of(e)!!
        val json = p.encode()
        assertTrue("LIKE fragment must match the stored JSON: $json", json.contains(HubPayload.askFragment("a1b2")))
        val back = HubPayload.decode(json)!!
        assertEquals(HubPayload.STATE_OPEN, back.state)
        assertEquals(listOf("Yes"), back.options)
        assertEquals(HubPayload.STATE_ANSWERED, HubPayload.decode(back.copy(state = HubPayload.STATE_ANSWERED).encode())!!.state)
        assertTrue(HubPayload(HubEvent.REPLY, turn = "t9").encode().contains(HubPayload.turnFragment("t9")))
        assertNull(HubPayload.decode("{broken"))
        assertNull(HubPayload.of(HubEvent(type = HubEvent.REPLY)))
    }
}
