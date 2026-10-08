package io.github.salex27.lumi.data.hub

import io.github.salex27.lumi.domain.pcview.PcReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection

data class PcMonitor(val id: Int, val name: String, val width: Int, val height: Int, val primary: Boolean)

/** One JPEG frame; [bytes] is null when the screen did not change (HTTP 304). */
data class PcFrame(val bytes: ByteArray?, val etag: String, val width: Int, val height: Int, val hubDelayMs: Long)

/** The hub answered with an error (never carries tokens or screen content). */
class PcException(val reply: PcReply.Failure) : IOException("${reply.httpCode} ${reply.error}")

/**
 * "My PC" calls to Lumi Hub. VIEW ONLY: there is no call that sends input or runs anything on the PC.
 * The unlock token is passed by the caller (kept in memory only) and is never logged.
 */
class PcViewClient(private val hub: HubClient, private val settings: HubSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fail(conn: HttpURLConnection): Nothing {
        val body = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(2_000) } }.getOrNull().orEmpty()
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        throw PcException(
            PcReply.Failure(
                conn.responseCode, o?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty(),
                o?.get("reason")?.jsonPrimitive?.contentOrNull.orEmpty(), o?.get("retry_after")?.jsonPrimitive?.intOrNull ?: 0
            )
        )
    }

    private fun call(method: String, path: String, token: String? = null, etag: String? = null, timeoutMs: Int = 15_000, read: (HttpURLConnection) -> Unit) {
        val conn = hub.open(method, path, timeoutMs, settings.pcConfig.value)
        try {
            token?.let { conn.setRequestProperty("X-Lumi-Unlock", it) }
            etag?.let { conn.setRequestProperty("If-None-Match", "\"$it\"") }
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write("{}".toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299 && code != 304) fail(conn)
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun text(conn: HttpURLConnection) = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(64_000) }

    /** "disabled" / "locked" / "unlocked" / "locked_out" as the hub reports it. */
    suspend fun status(): PcReply.Status = withContext(Dispatchers.IO) {
        var out = PcReply.Status("locked")
        call("GET", "/pc/status") { c ->
            val o = json.parseToJsonElement(text(c)).jsonObject
            out = PcReply.Status(o["state"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["retry_after"]?.jsonPrimitive?.intOrNull ?: 0,
                o["capture"]?.jsonPrimitive?.booleanOrNull ?: true, HubSafety.plain(o["os"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(16))
        }
        out
    }

    /** Asks for a short-lived unlock token. Call it only after the phone's biometric / screen lock succeeded. Returns (token, seconds). */
    suspend fun unlock(): Pair<String, Int> = withContext(Dispatchers.IO) {
        var out = "" to 0
        call("POST", "/pc/unlock") { c ->
            val o = json.parseToJsonElement(text(c)).jsonObject
            out = o["token"]?.jsonPrimitive?.contentOrNull.orEmpty() to (o["expires_in"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        if (out.first.isBlank()) throw IOException("no token")
        out
    }

    suspend fun extend(token: String): Int = withContext(Dispatchers.IO) {
        var left = 0
        call("POST", "/pc/extend", token) { c -> left = json.parseToJsonElement(text(c)).jsonObject["expires_in"]?.jsonPrimitive?.intOrNull ?: 0 }
        left
    }

    /** Revokes every unlock and drops viewers. Needs no unlock token. */
    suspend fun lock() = withContext(Dispatchers.IO) { call("POST", "/pc/lock") { }; Unit }

    suspend fun monitors(token: String): List<PcMonitor> = withContext(Dispatchers.IO) {
        var list = emptyList<PcMonitor>()
        call("GET", "/pc/monitors", token) { c ->
            val arr = json.parseToJsonElement(text(c)).jsonObject["monitors"] as? JsonArray
            list = arr.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                PcMonitor(
                    o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    HubSafety.plain(o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(40),
                    o["width"]?.jsonPrimitive?.intOrNull ?: 0, o["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    o["primary"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
        }
        list
    }

    suspend fun frame(token: String, monitor: Int, width: Int, quality: Int, etag: String?): PcFrame = withContext(Dispatchers.IO) {
        var out: PcFrame? = null
        call("GET", "/pc/frame?monitor=$monitor&w=$width&q=$quality", token, etag) { c ->
            val h = { name: String -> c.getHeaderField(name) }
            val tag = h("ETag")?.trim('"').orEmpty()
            val w = h("X-Lumi-Width")?.toIntOrNull() ?: 0
            val hh = h("X-Lumi-Height")?.toIntOrNull() ?: 0
            val delay = h("X-Lumi-Delay-Ms")?.toLongOrNull() ?: 150L
            out = if (c.responseCode == 304) PcFrame(null, tag, w, hh, delay)
            else PcFrame(c.inputStream.use { it.readNBytes(MAX_FRAME_BYTES) }, tag, w, hh, delay)
        }
        out ?: throw IOException("no frame")
    }

    private companion object {
        const val MAX_FRAME_BYTES = 3_000_000
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
