package io.github.salex27.lumi.data.hub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where the hub is and the phone's token. The token is a secret: the "hub" prefs file is not in the backup. */
data class HubConfig(val address: String = "", val token: String = "") {
    val baseUrl: String? get() = HubSafety.normalizeAddress(address)
    val isConfigured: Boolean get() = baseUrl != null && token.isNotBlank()
}

class HubSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(HubConfig(prefs.getString(K_ADDRESS, "").orEmpty(), prefs.getString(K_TOKEN, "").orEmpty()))
    val config: StateFlow<HubConfig> = _config.asStateFlow()

    fun save(address: String, token: String) {
        val c = HubConfig(address.trim(), token.trim())
        prefs.edit().putString(K_ADDRESS, c.address).putString(K_TOKEN, c.token).putLong(K_LAST_EVENT, 0L).apply()
        _config.value = c
    }

    /** Last event received, so a reconnection only gets what is new (the hub also resends open questions). */
    var lastEventId: Long
        get() = prefs.getLong(K_LAST_EVENT, 0L)
        set(value) = prefs.edit().putLong(K_LAST_EVENT, value).apply()

    private companion object {
        const val K_ADDRESS = "address"
        const val K_TOKEN = "token"
        const val K_LAST_EVENT = "last_event_id"
    }
}

/** The PC as Lumi Hub describes it. */
data class HubAgent(val id: String, val name: String, val host: String)
data class HubHealth(val host: String, val agents: List<HubAgent>)

/** HTTP calls to Lumi Hub (plain HttpURLConnection, like the rest of the app). */
class HubClient(private val settings: HubSettings) {

    class HubException(val code: Int, message: String) : IOException(message)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(): HubHealth = withContext(Dispatchers.IO) {
        val body = json.parseToJsonElement(request("GET", "/health", null)).jsonObject
        HubHealth(
            body["host"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            (body["agents"] as? JsonArray).orEmpty().mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                HubAgent(
                    o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    HubSafety.plain(o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(60),
                    HubSafety.plain(o["host"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(60)
                )
            }
        )
    }

    /** Answers a lumi_ask question. False when it was already answered or timed out. */
    suspend fun answer(askId: String, answer: String): Boolean = withContext(Dispatchers.IO) {
        try {
            request("POST", "/answer", JsonObject(mapOf("ask_id" to JsonPrimitive(askId), "answer" to JsonPrimitive(answer))).toString()); true
        } catch (e: HubException) {
            if (e.code == 410) false else throw e
        }
    }

    /** Wakes [agent] for one turn on [thread] (its reply arrives as `reply` events). Returns the turn id. */
    suspend fun chat(thread: String, agent: String, text: String): String = withContext(Dispatchers.IO) {
        val body = JsonObject(mapOf("thread" to JsonPrimitive(thread), "agent" to JsonPrimitive(agent), "text" to JsonPrimitive(text)))
        json.parseToJsonElement(request("POST", "/chat", body.toString())).jsonObject["turn"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** Web search done by Claude Code on the PC (#7): only the query is sent. Slow (up to ~2 min). */
    suspend fun search(query: String): List<io.github.salex27.lumi.domain.search.WebHit> = withContext(Dispatchers.IO) {
        io.github.salex27.lumi.data.search.SearchParsers.hub(
            request("POST", "/search", JsonObject(mapOf("query" to JsonPrimitive(query))).toString(), readTimeoutMs = 150_000)
        )
    }

    /** The agent forgets this thread's session (the next message starts a fresh one). */
    suspend fun forget(thread: String) = withContext(Dispatchers.IO) {
        request("POST", "/forget", JsonObject(mapOf("thread" to JsonPrimitive(thread))).toString()); Unit
    }

    /** Reads the event stream until it closes or the coroutine is cancelled. */
    suspend fun stream(since: Long, onConnected: () -> Unit, onEvent: suspend (HubEvent) -> Unit) = withContext(Dispatchers.IO) {
        val conn = open("GET", "/events?since=$since", readTimeoutMs = 60_000) // the hub sends a keepalive every 20 s
        activeStream = conn
        try {
            conn.setRequestProperty("Accept", "text/event-stream")
            if (since > 0) conn.setRequestProperty("Last-Event-ID", since.toString())
            check(conn)
            onConnected()
            val parser = SseParser()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val frame = parser.feed(line) ?: continue
                    val event = HubEvent.parse(frame.data) ?: continue
                    onEvent(if (event.id == 0L && frame.id != null) event.copy(id = frame.id) else event)
                }
            }
        } finally {
            if (activeStream === conn) activeStream = null
            conn.disconnect()
        }
    }

    @Volatile private var activeStream: HttpURLConnection? = null

    /** Closes the open event stream (its blocked read fails at once). Safe from any thread. */
    fun closeStream() {
        val conn = activeStream ?: return
        activeStream = null
        Thread { runCatching { conn.disconnect() } }.start()
    }

    private fun open(method: String, path: String, readTimeoutMs: Int = 20_000): HttpURLConnection {
        val c = settings.config.value
        val base = c.baseUrl ?: throw HubException(0, "not configured")
        return (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer ${c.token}")
        }
    }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) throw HubException(code, "HTTP $code")
    }

    private fun request(method: String, path: String, body: String?, readTimeoutMs: Int = 20_000): String {
        val conn = open(method, path, readTimeoutMs)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(conn)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(256_000) }
        } finally {
            conn.disconnect()
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
