package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini en la nube (Gemini API, REST). Opcional y desactivado por defecto; se activa en Ajustes
 * con una API key gratuita de Google AI Studio. En el nivel gratuito Google puede usar los datos
 * para mejorar sus productos → se avisa en la pantalla de Ajustes.
 */
class CloudGeminiEngine(private val settings: SettingsRepository) : LlmEngine() {

    override val displayName: String get() = "Gemini cloud · ${settings.current.cloudModel}"

    override suspend fun isAvailable() = settings.current.cloudReady

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? =
        generate(system, user, maxTokens, temperature, webSearch = false)

    /** Con la herramienta «Google Search» de la Gemini API (gratis con límite diario): noticias, resultados, horarios… */
    override suspend fun askWeb(system: String, user: String, maxTokens: Int): String? = try {
        generate(system, user, maxTokens, 0.3f, webSearch = true)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null // sin búsqueda (límite, modelo sin herramientas…) → se responde sin ella
    }

    private suspend fun generate(system: String, user: String, maxTokens: Int, temperature: Float, webSearch: Boolean): String? {
        val s = settings.current
        if (!s.cloudReady) return null
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", user) } }
                }
            }
            if (webSearch) putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                // Margen amplio: los modelos con "thinking" consumen tokens antes de responder
                put("maxOutputTokens", maxTokens + 1024)
            }
        }
        val (code, response) = post(s.cloudModel, s.cloudApiKey, body.toString())
        if (code !in 200..299) throw CloudException(code, errorMessage(response))
        return extractText(response)
    }

    /** Prueba la conexión desde Ajustes. Devuelve null si va bien o el mensaje de error. */
    suspend fun testConnection(): String? = try {
        val reply = complete("Responde solo con la palabra OK.", "Prueba de conexión", 10, 0f)
        if (reply.isNullOrBlank()) "Respuesta vacía del modelo" else null
    } catch (e: CloudException) {
        when (e.code) {
            400, 403 -> "API key no válida o sin permisos (${e.code}): ${e.message}"
            404 -> "El modelo «${settings.current.cloudModel}» no existe o no está disponible"
            429 -> "Límite gratuito alcanzado, prueba más tarde (429)"
            else -> "Error ${e.code}: ${e.message}"
        }
    } catch (e: Exception) {
        "Sin conexión: ${e.message}"
    }

    private suspend fun post(model: String, apiKey: String, json: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey) // en cabecera, nunca en la URL
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(response: String): String? = runCatching {
        Json.parseToJsonElement(response).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { part ->
                val obj = part.jsonObject
                // Se ignoran las partes de "pensamiento" si el modelo las incluye
                if (obj["thought"]?.jsonPrimitive?.contentOrNull == "true") null
                else obj["text"]?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString("")
    }.getOrNull()

    private fun errorMessage(response: String): String = runCatching {
        Json.parseToJsonElement(response).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: response.take(200)

    class CloudException(val code: Int, message: String) : Exception(message)
}
