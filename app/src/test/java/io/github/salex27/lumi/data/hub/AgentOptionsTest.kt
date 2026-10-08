package io.github.salex27.lumi.data.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOptionsTest {
    private val body = """{"models":[{"id":"opus","label":"Opus"},{"id":"sonnet","label":"Sonnet"},{"id":"bad id!","label":"x"},{"id":"opus","label":"dup"}],
        "efforts":["low","high","bad value!"],"defaults":{"model":"sonnet","effort":"high"}}"""

    @Test fun `options parse, drop invalid ids and duplicates`() {
        val o = AgentOptions.parse(body)
        assertEquals(listOf("opus", "sonnet"), o.models.map { it.id })
        assertEquals(listOf("low", "high"), o.efforts)
        assertEquals("sonnet", o.defaultModel); assertEquals("high", o.defaultEffort)
    }

    @Test fun `defaults the hub does not offer are dropped and bad bodies throw`() {
        val o = AgentOptions.parse("""{"models":[{"id":"a","label":"A"}],"efforts":[],"defaults":{"model":"zzz","effort":"max"}}""")
        assertNull(o.defaultModel); assertNull(o.defaultEffort)
        assertTrue(runCatching { AgentOptions.parse("[]") }.isFailure)
    }

    @Test fun `thread choice wins field by field and retired models are dropped`() {
        val o = AgentOptions.parse(body)
        assertEquals(ModelChoice("opus", "high"), ModelChoice.resolve(ModelChoice("opus", null), ModelChoice("sonnet", "high"), o))
        assertEquals(ModelChoice(null, "low"), ModelChoice.resolve(ModelChoice("gone", null), ModelChoice("gone2", "low"), o))
        assertEquals(ModelChoice(), ModelChoice.resolve(ModelChoice("opus", "high"), ModelChoice(), null)) // older hub: nothing is sent
    }

    @Test fun `turn_delta keeps the spaces at the edges of a chunk`() {
        val e = HubEvent.parse("""{"type":"turn_delta","thread":"orbit-1-2","turn":"t1","text":" world "}""")!!
        assertEquals(" world ", e.text)
        assertEquals(HubEvent.TURN_DELTA, e.type)
        assertEquals("hi", HubEvent.parse("""{"type":"reply","text":"  hi  "}""")!!.text)
    }
}
