package io.github.salex27.lumi.data.relay

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.salex27.lumi.data.ai.CategoryHeuristics
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.relay.RelayItems
import io.github.salex27.lumi.domain.repository.TaskChangeListener
import io.github.salex27.lumi.domain.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lumi's side of the Claude connector (tools/lumi-relay).
 *
 * - Uploads a snapshot of the tasks after every change (debounced), so Claude can read them while the phone sleeps.
 * - Applies the changes Claude queued: on demand ([syncNow], app start, the 15-minute background job) and, while Lumi
 *   is on screen, through a long poll so they land in about a second.
 * - Every change goes through [TaskRepository] (reminders, recurrence, widget, Now Bar…). Items are structured data
 *   validated by [RelayItems]; they never reach the interpreter, an Intent or [io.github.salex27.lumi.presentation.agent.DeviceActions].
 *
 * Secrets (device token, Claude link) live in the "claude_relay" prefs, which are NOT in the backup.
 */
class ClaudeRelay(
    context: Context,
    private val repository: () -> TaskRepository,
    private val scope: CoroutineScope
) : TaskChangeListener {

    data class State(
        val url: String = "",
        val connected: Boolean = false,
        /** The address to paste in Claude as a custom connector (null until connected). */
        val connectorUrl: String? = null,
        val lastSync: Long = 0,
        val busy: Boolean = false,
        val error: String? = null,
        /** Changes from Claude applied since the app started (shown in Settings). */
        val applied: Int = 0
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state

    private var uploadJob: Job? = null
    private var pollJob: Job? = null
    @Volatile private var visible = false

    private fun readState() = State(
        url = prefs.getString(KEY_URL, null) ?: DEFAULT_URL,
        connected = prefs.getBoolean(KEY_CONNECTED, false),
        connectorUrl = connectorUrl(),
        lastSync = prefs.getLong(KEY_LAST_SYNC, 0)
    )

    private fun connectorUrl(): String? {
        if (!prefs.getBoolean(KEY_CONNECTED, false)) return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        val secret = prefs.getString(KEY_MCP_SECRET, null) ?: return null
        return "${url.trimEnd('/')}/mcp/$secret"
    }

    // ── Connecting ──

    /**
     * Claims the relay at [url] for this phone and creates a new Claude link. The device token is kept across
     * reconnections (the relay only accepts the phone that claimed it first); the Claude link changes every time.
     */
    suspend fun connect(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        val base = url.trim().trimEnd('/')
        runCatching {
            require(base.startsWith("https://")) { "The address must start with https://" }
            val device = prefs.getString(KEY_DEVICE_TOKEN, null) ?: randomToken().also { prefs.edit().putString(KEY_DEVICE_TOKEN, it).apply() }
            val secret = randomToken()
            val body = buildJsonObject { put("device_token", device); put("mcp_secret", secret) }.toString()
            val (code, text) = http(base, "POST", "/api/claim", body, auth = false)
            when (code) {
                in 200..299 -> Unit
                409 -> error("This relay already belongs to another phone")
                else -> error("The relay answered $code ${text.take(120)}")
            }
            prefs.edit().putString(KEY_URL, base).putString(KEY_MCP_SECRET, secret).putBoolean(KEY_CONNECTED, true).apply()
            _state.value = readState()
        }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            // Connected while Lumi is on screen: start listening right away (setVisible only runs on app start/stop)
            .also { if (it.isSuccess) { if (visible) setVisible(true) else syncNow() } }
    }

    /** Stops syncing and forgets the Claude link (the connector in Claude stops working until reconnected). */
    fun disconnect() {
        prefs.edit().putBoolean(KEY_CONNECTED, false).remove(KEY_MCP_SECRET).apply()
        pollJob?.cancel()
        pollJob = null
        _state.value = readState()
    }

    // ── Sync ──

    override suspend fun onTaskSaved(taskId: Long) = requestUpload()
    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) = requestUpload()

    /** Uploads the snapshot a moment after the last change (a batch of edits = one upload). */
    fun requestUpload() {
        if (!prefs.getBoolean(KEY_CONNECTED, false)) return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(1500)
            runCatching { uploadSnapshot() }.onFailure { Log.w(TAG, "upload: ${it.message}") }
        }
    }

    /** Applies pending changes from Claude and uploads the tasks. Safe to call from anywhere; never throws. */
    suspend fun syncNow(): Boolean {
        if (!prefs.getBoolean(KEY_CONNECTED, false)) return false
        _state.update { it.copy(busy = true) }
        val ok = runCatching {
            applyPending(waitSeconds = 0)
            uploadSnapshot()
        }.onFailure { e -> Log.w(TAG, "sync: ${e.message}") }
        _state.update {
            if (ok.isSuccess) it.copy(busy = false, error = null, lastSync = prefs.getLong(KEY_LAST_SYNC, 0))
            else it.copy(busy = false, error = ok.exceptionOrNull()?.message ?: "error")
        }
        return ok.isSuccess
    }

    /** While Lumi is on screen, keep a long poll open so Claude's changes land at once. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        pollJob?.cancel()
        if (!visible || !prefs.getBoolean(KEY_CONNECTED, false)) return
        pollJob = scope.launch {
            syncNow()
            var failures = 0
            while (isActive && prefs.getBoolean(KEY_CONNECTED, false)) {
                val ok = runCatching { applyPending(waitSeconds = 25) }.isSuccess
                failures = if (ok) 0 else failures + 1
                if (!ok) delay((5_000L * failures).coerceAtMost(60_000))
            }
        }
    }

    private suspend fun uploadSnapshot() = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val tasks = RelayItems.forSnapshot(repository().getAllTasks().first(), now)
        val body = buildJsonObject {
            put("zone", zone.id)
            put("today", LocalDate.now(zone).toString())
            put("tasks", buildJsonArray { tasks.forEach { add(RelayItems.toJson(it, zone)) } })
        }.toString()
        val (code, text) = http(baseUrl(), "PUT", "/api/snapshot", body)
        check(code in 200..299) { "snapshot $code ${text.take(120)}" }
        prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
        _state.update { it.copy(lastSync = now, error = null) }
    }

    /** Fetches the queued ops (holding the request up to [waitSeconds]) and applies them one by one. */
    private suspend fun applyPending(waitSeconds: Int) {
        val (code, text) = withContext(Dispatchers.IO) {
            http(baseUrl(), "GET", "/api/ops?wait=$waitSeconds", null, readTimeoutMs = (waitSeconds + 15) * 1000)
        }
        if (code == 401) {
            _state.update { it.copy(error = "The relay no longer accepts this phone: connect again") }
            error("unauthorized")
        }
        check(code in 200..299) { "ops $code" }
        val ops = json.parseToJsonElement(text).jsonObject["ops"]?.jsonArray.orEmpty()
        if (ops.isEmpty()) return
        mutex.withLock {
            for (raw in ops) {
                val op = raw.jsonObject
                val id = op["id"]?.jsonPrimitive?.contentOrNull ?: continue
                // Already applied (the result post was lost): only resend the stored result, never apply twice
                val result = prefs.getString(KEY_DONE_PREFIX + id, null)?.let { json.parseToJsonElement(it).jsonObject }
                    ?: apply(op).also { r ->
                        prefs.edit().putString(KEY_DONE_PREFIX + id, r.toString()).apply()
                        rememberDone(id)
                    }
                withContext(Dispatchers.IO) { http(baseUrl(), "POST", "/api/ops/$id/result", result.toString()) }
            }
        }
        uploadSnapshot()
    }

    /** Runs one op through the repository. Returns {ok, result}; a bad item fails alone, the rest still apply. */
    private suspend fun apply(op: JsonObject): JsonObject {
        val repo = repository()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val args = op["args"] as? JsonObject ?: JsonObject(emptyMap())
        val done = mutableListOf<JsonElement>()
        val errors = mutableListOf<String>()
        when (op["type"]?.jsonPrimitive?.contentOrNull) {
            "create" -> args["items"].items().forEachIndexed { i, el ->
                runCatching {
                    val item = el.jsonObject
                    val task = RelayItems.newTask(item, zone, today, now) { CategoryHeuristics.infer(it) }
                    val id = repo.insertTask(task)
                    val saved = repo.getTask(id) ?: task.copy(id = id)
                    RelayItems.remindBefore(item).forEach { repo.addCustomReminder(saved, it) }
                    done += RelayItems.describe(saved, zone)
                }.onFailure { errors += "items[$i]: ${it.message}" }
            }
            "update" -> args["items"].items().forEachIndexed { i, el ->
                runCatching {
                    val item = el.jsonObject
                    val id = item["id"]?.jsonPrimitive?.longOrNull ?: error("id is missing")
                    val task = repo.getTask(id) ?: error("no item with id $id")
                    val updated = RelayItems.applyUpdate(task, item, zone, today, now)
                    repo.updateTask(updated)
                    RelayItems.remindBefore(item).forEach { repo.addCustomReminder(updated, it) }
                    done += RelayItems.describe(repo.getTask(id) ?: updated, zone)
                }.onFailure { errors += "items[$i]: ${it.message}" }
            }
            "remove" -> args["ids"].items().forEach { el ->
                runCatching {
                    val id = el.jsonPrimitive.longOrNull ?: error("bad id")
                    val task = repo.getTask(id) ?: error("no item with id $id")
                    // Cancelled, not deleted: the user can still restore it in Lumi
                    repo.updateTask(task.copy(status = TaskStatus.CANCELLED, updatedAt = now))
                    done += RelayItems.describe(task.copy(status = TaskStatus.CANCELLED), zone)
                }.onFailure { errors += "${el}: ${it.message}" }
            }
            else -> errors += "unknown operation"
        }
        if (done.isNotEmpty()) _state.update { it.copy(applied = it.applied + done.size) }
        return buildJsonObject {
            put("ok", errors.isEmpty() || done.isNotEmpty())
            put("result", buildJsonObject {
                put("done", JsonArray(done))
                if (errors.isNotEmpty()) put("errors", JsonArray(errors.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        }
    }

    // ── Plumbing ──

    private fun rememberDone(id: String) {
        val ids = (prefs.getString(KEY_DONE_IDS, "") ?: "").split(',').filter { it.isNotBlank() } + id
        val keep = ids.takeLast(100)
        prefs.edit().apply {
            ids.dropLast(keep.size).forEach { remove(KEY_DONE_PREFIX + it) }
            putString(KEY_DONE_IDS, keep.joinToString(","))
        }.apply()
    }

    private fun baseUrl(): String = prefs.getString(KEY_URL, null) ?: error("not connected")

    private fun http(base: String, method: String, path: String, body: String?, auth: Boolean = true, readTimeoutMs: Int = 20_000): Pair<Int, String> {
        val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Lumi")
            if (auth) setRequestProperty("Authorization", "Bearer " + (prefs.getString(KEY_DEVICE_TOKEN, null) ?: error("no device token")))
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..399) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonElement?.items(): List<JsonElement> = (this as? JsonArray).orEmpty()
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        private const val TAG = "ClaudeRelay"
        const val PREFS = "claude_relay"
        /** The author's relay (Cloudflare Workers). Anyone else deploys tools/lumi-relay and types their own address. */
        const val DEFAULT_URL = "https://lumi-relay.lumi-6bf4dffd.workers.dev"
        private const val KEY_URL = "url"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_MCP_SECRET = "mcp_secret"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_DONE_IDS = "done_ids"
        private const val KEY_DONE_PREFIX = "done_"

        private fun randomToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
