package io.github.salex27.lumi.data.sync

import android.util.Log
import io.github.salex27.lumi.data.local.SyncTombstone
import io.github.salex27.lumi.data.local.SyncTombstoneDao
import io.github.salex27.lumi.data.local.TaskDao
import io.github.salex27.lumi.data.local.TaskEntity
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.repository.TaskChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Two-way sync with Google Tasks (REST API, free: 50,000 requests/day).
 *
 * - Uses its own "Lumi" list so it doesn't mix with the user's other lists.
 * - Conflicts: the most recent change wins (local updatedAt vs remote `updated`).
 * - Google Tasks only stores the due DATE (not the time): the local time is kept while the day matches.
 * - Local deletions → tombstones sent on the next sync.
 * - Cancelled → deleted in Google Tasks (there is no "cancelled" state there).
 */
class GoogleTasksSync(
    private val auth: GoogleTasksAuth,
    private val dao: TaskDao,
    private val tombstones: SyncTombstoneDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** Called for every task inserted/changed from Google (reminders, calendar, widget). */
    private val onRemoteChange: suspend (taskId: Long) -> Unit
) : TaskChangeListener {

    sealed interface Status {
        data object Idle : Status
        data object Running : Status
        data class Done(val pulled: Int, val pushed: Int, val at: Long) : Status
        data class Error(val message: String) : Status
        /** Permission has to be granted again from Settings. */
        data object NeedsConsent : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private var debounceJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    // ── Triggers ─────────────────────────────────────────────────────────────

    override suspend fun onTaskSaved(taskId: Long) = requestSync()

    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) {
        if (googleTaskId != null) tombstones.insert(SyncTombstone(googleTaskId))
        requestSync()
    }

    /** Batches consecutive changes into one sync (3 s after the last one). */
    fun requestSync() {
        if (!settings.current.googleTasksEnabled) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(3_000)
            syncNow()
        }
    }

    suspend fun syncNow(): Status {
        if (!settings.current.googleTasksEnabled) return Status.Idle
        return mutex.withLock {
            _status.value = Status.Running
            val result = try {
                when (val a = auth.authorize()) {
                    is GoogleTasksAuth.Result.Token -> runSync(a.accessToken)
                    is GoogleTasksAuth.Result.NeedsConsent -> Status.NeedsConsent
                    is GoogleTasksAuth.Result.Failed -> Status.Error(a.message)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Sync failed", e)
                Status.Error(e.message ?: e.javaClass.simpleName)
            }
            _status.value = result
            result
        }
    }

    // ── Algorithm ────────────────────────────────────────────────────────────

    private suspend fun runSync(token: String): Status = withContext(Dispatchers.IO) {
        val syncStart = System.currentTimeMillis()
        val lastSync = settings.current.googleTasksLastSync
        val listId = ensureList(token)
        var pulled = 0
        var pushed = 0

        // 1) Pending local deletions
        tombstones.all().forEach { t ->
            val (code, _) = http(token, "DELETE", "/lists/$listId/tasks/${t.googleTaskId}")
            if (code in 200..299 || code == 404) tombstones.delete(t.googleTaskId)
        }

        // 2) Pull remote changes
        val remote = fetchAll(token, listId, updatedMin = if (lastSync > 0) lastSync - 60_000 else null)
        for (r in remote) {
            val gid = r["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val remoteUpdated = r.str("updated")?.let { Instant.parse(it).toEpochMilli() } ?: continue
            val deleted = r.str("deleted") == "true"
            val local = dao.getByGoogleTaskId(gid)

            if (local == null) {
                if (deleted || r.str("title").isNullOrBlank()) continue
                val id = dao.insertTask(fromRemote(r, TaskEntity(title = ""), remoteUpdated).copy(googleTaskId = gid))
                onRemoteChange(id); pulled++
                continue
            }
            if (local.remoteUpdatedAt != null && remoteUpdated <= local.remoteUpdatedAt) continue // already seen
            val localChanged = local.isDirty(lastSync)
            if (localChanged && local.updatedAt > remoteUpdated) continue // local wins; pushed in step 3
            if (deleted) {
                dao.deleteTaskById(local.id)
            } else {
                dao.updateTask(fromRemote(r, local, remoteUpdated))
            }
            onRemoteChange(local.id); pulled++
        }

        // 3) Push local changes
        for (local in dao.getAllTasksSnapshot()) {
            val gid = local.googleTaskId
            when {
                local.status == TaskStatus.CANCELLED -> if (gid != null) {
                    http(token, "DELETE", "/lists/$listId/tasks/$gid")
                    dao.updateTask(local.copy(googleTaskId = null, remoteUpdatedAt = null))
                }
                gid == null -> {
                    val (code, body) = http(token, "POST", "/lists/$listId/tasks", toRemote(local).toString())
                    if (code in 200..299) {
                        val created = json.parseToJsonElement(body).jsonObject
                        dao.updateTask(local.copy(googleTaskId = created.str("id"), remoteUpdatedAt = created.updatedMillis()))
                        pushed++
                    }
                }
                local.isDirty(lastSync) -> {
                    val (code, body) = http(token, "PATCH", "/lists/$listId/tasks/$gid", toRemote(local).toString())
                    when {
                        code in 200..299 -> {
                            dao.updateTask(local.copy(remoteUpdatedAt = json.parseToJsonElement(body).jsonObject.updatedMillis()))
                            pushed++
                        }
                        code == 404 -> dao.updateTask(local.copy(googleTaskId = null, remoteUpdatedAt = null)) // recreated next time
                    }
                }
            }
        }

        settings.update { it.copy(googleTasksLastSync = syncStart) }
        Status.Done(pulled, pushed, System.currentTimeMillis())
    }

    private fun ensureList(token: String): String {
        settings.current.googleTasksListId.takeIf { it.isNotBlank() }?.let { id ->
            if (http(token, "GET", "/users/@me/lists/$id").first == 200) return id
        }
        val (code, body) = http(token, "GET", "/users/@me/lists?maxResults=100")
        check(code == 200) { "Could not read the lists ($code)" }
        val existing = json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
            ?.map { it.jsonObject }?.firstOrNull { it.str("title") == LIST_TITLE }?.str("id")
        val id = existing ?: run {
            val (c, b) = http(token, "POST", "/users/@me/lists", buildJsonObject { put("title", LIST_TITLE) }.toString())
            check(c in 200..299) { "Could not create the «$LIST_TITLE» list ($c)" }
            json.parseToJsonElement(b).jsonObject.str("id")!!
        }
        settings.update { it.copy(googleTasksListId = id) }
        return id
    }

    private fun fetchAll(token: String, listId: String, updatedMin: Long?): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var pageToken: String? = null
        do {
            val q = buildString {
                append("?showCompleted=true&showHidden=true&showDeleted=true&maxResults=100")
                updatedMin?.let { append("&updatedMin=").append(enc(Instant.ofEpochMilli(it).toString())) }
                pageToken?.let { append("&pageToken=").append(enc(it)) }
            }
            val (code, body) = http(token, "GET", "/lists/$listId/tasks$q")
            check(code == 200) { "Could not read the tasks ($code)" }
            val page = json.parseToJsonElement(body).jsonObject
            page["items"]?.jsonArray?.mapTo(out) { it.jsonObject }
            pageToken = page.str("nextPageToken")
        } while (pageToken != null)
        return out
    }

    private fun fromRemote(r: JsonObject, base: TaskEntity, remoteUpdated: Long): TaskEntity {
        val zone = ZoneId.systemDefault()
        val remoteDue = r.str("due")?.let { Instant.parse(it).atZone(ZoneOffset.UTC).toLocalDate() }
        val localDue = base.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val (dueAt, hasTime) = when {
            remoteDue == null -> null to false
            remoteDue == localDue -> base.dueAt to base.dueHasTime // keep the local time
            else -> remoteDue.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() to false
        }
        val completed = r.str("status") == "completed"
        return base.copy(
            title = r.str("title").orEmpty().ifBlank { base.title },
            description = r.str("notes").orEmpty(),
            status = when {
                completed -> TaskStatus.COMPLETED
                base.status == TaskStatus.IN_PROGRESS -> TaskStatus.IN_PROGRESS
                else -> TaskStatus.TODO
            },
            completedAt = if (completed) r.str("completed")?.let { Instant.parse(it).toEpochMilli() } ?: remoteUpdated else null,
            dueAt = dueAt,
            dueHasTime = hasTime,
            updatedAt = remoteUpdated,
            remoteUpdatedAt = remoteUpdated
        )
    }

    private fun toRemote(t: TaskEntity): JsonObject = buildJsonObject {
        put("title", t.title)
        put("notes", t.description)
        put("status", if (t.status == TaskStatus.COMPLETED) "completed" else "needsAction")
        t.dueAt?.let {
            val day: LocalDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            put("due", "${day}T00:00:00.000Z")
        }
    }

    private fun http(token: String, method: String, path: String, body: String? = null): Pair<Int, String> {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            // HttpURLConnection has no PATCH: use the standard Google APIs override
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            body?.let { b -> conn.outputStream.use { it.write(b.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** Changed locally since the last version seen in Google (avoids re-sending what was just pulled). */
    private fun TaskEntity.isDirty(lastSync: Long) = updatedAt > (remoteUpdatedAt ?: lastSync)

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.updatedMillis() = str("updated")?.let { Instant.parse(it).toEpochMilli() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "GoogleTasksSync"
        private const val BASE = "https://tasks.googleapis.com/tasks/v1"
        const val LIST_TITLE = "Lumi"
    }
}
