package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.data.settings.BrainConfig
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/** An HTTP error from a cloud brain, with the provider's message. */
class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Engines on a provider's API with the user's own key (#2). Raw HttpURLConnection like CloudGeminiEngine (no SDK
 * dependency). Keys travel in headers only. Any failure returns null through [LlmEngine] → the next engine answers.
 */
abstract class ApiEngine(protected val config: () -> BrainConfig) : LlmEngine() {

    /** Tests the connection from Settings: null when fine, or a readable error. */
    suspend fun testConnection(): String? = try {
        val reply = completeFor(Purpose.ASK, "Reply only with the word OK.", "Connection test", 16, 0f)
        if (reply.isNullOrBlank()) ReplyLanguage.ui("Respuesta vacía del modelo", "Empty reply from the model") else null
    } catch (e: ApiException) {
        when (e.code) {
            401, 403 -> ReplyLanguage.ui("Clave no válida o sin permisos", "Invalid key or missing permissions") + " (${e.code})"
            404 -> ReplyLanguage.ui("Modelo o dirección no encontrados", "Model or address not found") + " (404): ${e.message}"
            429 -> ReplyLanguage.ui("Límite alcanzado, prueba más tarde (429)", "Rate limit reached, try again later (429)")
            else -> "Error ${e.code}: ${e.message}"
        }
    } catch (e: Exception) {
        ReplyLanguage.ui("Sin conexión: ", "No connection: ") + (e.message ?: e.javaClass.simpleName)
    }

    protected suspend fun postJson(url: String, headers: Map<String, String>, body: String, readTimeoutMs: Int = 40_000): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw ApiException(code, ApiFormats.errorMessage(text))
                text
            } finally {
                conn.disconnect()
            }
        }
}

/**
 * Claude through the Anthropic API (Messages API). Interpreting uses the fast model (Haiku by default, as the issue
 * suggests); replies and questions use the reply model (Opus by default). Never a Claude subscription: API key only.
 */
class AnthropicEngine(config: () -> BrainConfig) : ApiEngine(config) {
    override val displayName: String get() = "Claude · ${config().anthropicReplyModel}"
    override suspend fun isAvailable() = config().anthropicKey.isNotBlank()

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? =
        completeFor(Purpose.ASK, system, user, maxTokens, temperature)

    override suspend fun completeFor(purpose: Purpose, system: String, user: String, maxTokens: Int, temperature: Float): String? {
        val c = config()
        if (c.anthropicKey.isBlank()) return null
        val model = if (purpose == Purpose.INTERPRET) c.anthropicInterpretModel else c.anthropicReplyModel
        val body = ApiFormats.anthropicRequest(model, system, user, maxTokens, temperature)
        val headers = mutableMapOf("x-api-key" to c.anthropicKey, "anthropic-version" to "2023-06-01")
        if (ApiFormats.anthropicUsesFallbacks(model)) headers["anthropic-beta"] = ApiFormats.ANTHROPIC_FALLBACK_BETA
        return ApiFormats.anthropicText(postJson("https://api.anthropic.com/v1/messages", headers, body))
    }
}

/**
 * The OpenAI API, or any OpenAI-compatible server (Ollama, LM Studio, llama.cpp server, OpenRouter, Groq…): base URL +
 * optional key + model name.
 */
class OpenAiEngine(config: () -> BrainConfig, private val compatible: Boolean) : ApiEngine(config) {
    private val base: String get() = if (compatible) config().compatibleBaseUrl else "https://api.openai.com/v1"
    private val key: String get() = if (compatible) config().compatibleKey else config().openAiKey
    private val model: String get() = if (compatible) config().compatibleModel else config().openAiModel

    override val displayName: String get() = if (compatible) "${ApiFormats.hostOf(base)} · $model" else "OpenAI · $model"
    override suspend fun isAvailable() =
        if (compatible) ApiFormats.validBaseUrl(base) != null && model.isNotBlank() else key.isNotBlank() && model.isNotBlank()

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? {
        val url = ApiFormats.validBaseUrl(base) ?: return null
        if (!compatible && key.isBlank()) return null
        val body = ApiFormats.openAiRequest(model, system, user, maxTokens, temperature, officialApi = !compatible)
        val headers = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")
        // Local servers on a laptop CPU can be slow: more time than the cloud
        return ApiFormats.openAiText(postJson("$url/chat/completions", headers, body, if (compatible) 120_000 else 40_000))
    }
}

/** Request bodies and response parsing of the cloud brains (pure, tested). */
object ApiFormats {
    private val json = Json { ignoreUnknownKeys = true }

    const val ANTHROPIC_FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /** Haiku takes a temperature; the current Opus/Sonnet/Fable models reject sampling parameters. */
    fun anthropicTakesTemperature(model: String) = model.startsWith("claude-haiku")

    /** Server-side refusal fallbacks ("default" routing) on the models that support them. */
    fun anthropicUsesFallbacks(model: String) = model in setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")

    fun anthropicRequest(model: String, system: String, user: String, maxTokens: Int, temperature: Float): String = buildJsonObject {
        put("model", model)
        // Models that always think spend tokens before the text: leave room so short answers aren't cut off
        put("max_tokens", if (anthropicTakesTemperature(model)) maxTokens else maxTokens + 2048)
        put("system", system)
        putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", user) } }
        if (anthropicTakesTemperature(model)) put("temperature", temperature)
        // Lumi's calls are short: low effort keeps them fast and cheap on models with adaptive thinking
        else putJsonObject("output_config") { put("effort", "low") }
        if (anthropicUsesFallbacks(model)) put("fallbacks", "default")
    }.toString()

    /** Text blocks of a Messages API response; null on a refusal or no text. */
    fun anthropicText(body: String): String? {
        val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        if (o["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") return null
        return (o["content"] as? JsonArray).orEmpty()
            .mapNotNull { b -> (b as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("").takeIf { it.isNotBlank() }
    }

    fun openAiRequest(model: String, system: String, user: String, maxTokens: Int, temperature: Float, officialApi: Boolean): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", user) }
        }
        // The official API renamed the limit (reasoning models reject max_tokens and custom temperatures);
        // compatible servers still expect the classic fields
        if (officialApi) put("max_completion_tokens", maxTokens + 1024)
        else { put("max_tokens", maxTokens); put("temperature", temperature) }
        put("stream", false)
    }.toString()

    fun openAiText(body: String): String? = runCatching {
        val o = json.parseToJsonElement(body) as JsonObject
        val first = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        ((first?.get("message") as? JsonObject)?.get("content"))?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("(?s)<think>.*?</think>"), "") // local reasoning models print their thinking
            ?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** {"error":{"message":…}} (both providers), or the start of the body. */
    fun errorMessage(body: String): String = runCatching {
        val e = (json.parseToJsonElement(body) as JsonObject)["error"]
        ((e as? JsonObject)?.get("message") ?: e)?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(200)

    /**
     * "https://openrouter.ai/api/v1/" → "https://openrouter.ai/api/v1". HTTPS, or plain HTTP only to the emulator's
     * host or localhost (debug); a server on the LAN should be published over HTTPS (e.g. `tailscale serve`).
     */
    fun validBaseUrl(input: String): String? {
        val s = input.trim().trimEnd('/')
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:\\d{1,5})?(/[\\w./-]*)?$").matchEntire(s) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2]
        if (scheme == "http" && host !in setOf("10.0.2.2", "127.0.0.1", "localhost")) return null
        return s
    }

    fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "OpenAI-compatible" }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
