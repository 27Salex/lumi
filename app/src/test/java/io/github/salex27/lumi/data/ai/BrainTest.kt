package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.BrainChain
import io.github.salex27.lumi.domain.ai.BrainChoice
import io.github.salex27.lumi.domain.ai.EngineId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {

    @Test
    fun `the chosen brain leads and the rest keep their order`() {
        assertEquals(BrainChain.DEFAULT_ORDER, BrainChain.order(BrainChoice.AUTO))
        val claude = BrainChain.order(BrainChoice.ANTHROPIC)
        assertEquals(EngineId.ANTHROPIC, claude.first())
        assertEquals(listOf(EngineId.NANO, EngineId.GEMMA, EngineId.GEMINI_CLOUD, EngineId.OPENAI, EngineId.OPENAI_COMPATIBLE), claude.drop(1))
        BrainChoice.entries.forEach { assertEquals(EngineId.entries.size, BrainChain.order(it).toSet().size) }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun `anthropic requests per model family`() {
        val haiku = obj(ApiFormats.anthropicRequest("claude-haiku-4-5", "sys", "hi", 256, 0.1f))
        assertEquals("claude-haiku-4-5", haiku["model"]!!.jsonPrimitive.content)
        assertEquals(256, haiku["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("sys", haiku["system"]!!.jsonPrimitive.content)
        assertEquals("user", haiku["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(haiku.containsKey("temperature"))
        assertFalse(haiku.containsKey("fallbacks"))
        // Opus 5.5: no sampling parameters, room for thinking, low effort, refusal fallbacks
        val opus = obj(ApiFormats.anthropicRequest("claude-opus-5-5", "sys", "hi", 400, 0.7f))
        assertFalse(opus.containsKey("temperature"))
        assertFalse(opus.containsKey("thinking"))
        assertTrue(opus["max_tokens"]!!.jsonPrimitive.content.toInt() > 400)
        assertEquals("low", opus["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", opus["fallbacks"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic responses`() {
        val ok = """{"id":"m","type":"message","role":"assistant","content":[{"type":"thinking","thinking":""},{"type":"text","text":"Hola"},{"type":"text","text":" Ana"}],"stop_reason":"end_turn"}"""
        assertEquals("Hola Ana", ApiFormats.anthropicText(ok))
        assertNull(ApiFormats.anthropicText("""{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal"}}"""))
        assertNull(ApiFormats.anthropicText("not json"))
        assertEquals("invalid x-api-key", ApiFormats.errorMessage("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
    }

    @Test
    fun `openai and compatible requests and responses`() {
        val official = obj(ApiFormats.openAiRequest("gpt-4o-mini", "sys", "hi", 200, 0.1f, officialApi = true))
        assertTrue(official.containsKey("max_completion_tokens"))
        assertFalse(official.containsKey("temperature"))
        assertEquals("system", official["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        val local = obj(ApiFormats.openAiRequest("llama3.2", "sys", "hi", 200, 0.1f, officialApi = false))
        assertEquals(200, local["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(local.containsKey("temperature"))
        val reply = """{"choices":[{"index":0,"message":{"role":"assistant","content":"<think>hmm</think>\n{\"action\":\"ASK\"}"}}]}"""
        assertEquals("{\"action\":\"ASK\"}", ApiFormats.openAiText(reply))
        assertNull(ApiFormats.openAiText("""{"choices":[]}"""))
        assertEquals("model not found", ApiFormats.errorMessage("""{"error":{"message":"model not found","type":"invalid_request_error"}}"""))
        assertEquals("plain error", ApiFormats.errorMessage("""{"error":"plain error"}"""))
    }

    @Test
    fun `base urls`() {
        assertEquals("https://openrouter.ai/api/v1", ApiFormats.validBaseUrl(" https://openrouter.ai/api/v1/ "))
        assertEquals("http://10.0.2.2:11434/v1", ApiFormats.validBaseUrl("http://10.0.2.2:11434/v1"))
        assertNull(ApiFormats.validBaseUrl("http://192.168.1.20:11434/v1")) // plain HTTP on the LAN is refused
        assertNull(ApiFormats.validBaseUrl("ftp://x"))
        assertNull(ApiFormats.validBaseUrl(""))
        assertEquals("openrouter.ai", ApiFormats.hostOf("https://openrouter.ai/api/v1"))
    }
}
