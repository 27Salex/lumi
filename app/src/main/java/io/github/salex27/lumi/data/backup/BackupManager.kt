package io.github.salex27.lumi.data.backup

import android.content.Context
import android.net.Uri
import io.github.salex27.lumi.data.local.AppDatabase
import io.github.salex27.lumi.data.local.MemoryEntity
import io.github.salex27.lumi.data.local.TaskEntity
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskReminder
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup to a JSON file (the user picks where: Drive, Downloads…). Used to move to another phone and to bring the data
 * of the old app (whose id contained "gemini") over to Lumi 1.0.
 *
 * Included: tasks (with their custom reminders), memory, places, routines, quick contacts and settings.
 * NOT included: the Gemini API key (a secret), ids tied to this phone (calendar, voice print), caches.
 * Importing ADDS to what is there (never deletes): tasks and memories that already exist aren't duplicated.
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val repository: TaskRepository
) {

    data class Summary(val tasks: Int, val memories: Int, val prefsFiles: Int)

    /** SharedPreferences files that are copied (and keys that aren't: secret or tied to this phone). */
    private val prefsToCopy = listOf("assistant_settings", "places", "routines", "contact_aliases")
    private val skippedKeys = setOf("cloud_api_key", "calendar_id", "calendar_sync", "google_tasks", "google_tasks_last_sync", "google_tasks_list_id")

    suspend fun export(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val tasks = database.taskDao().getAllTasksSnapshot()
        val memories = database.memoryDao().all()
        val root = JSONObject()
            .put("app", "Lumi")
            .put("format", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("tasks", JSONArray().apply { tasks.forEach { put(taskJson(it)) } })
            .put("memories", JSONArray().apply { memories.forEach { put(JSONObject().put("text", it.text).put("createdAt", it.createdAt)) } })
            .put("prefs", JSONObject().apply {
                prefsToCopy.forEach { name ->
                    val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    put(name, JSONObject().apply {
                        all.filterKeys { it !in skippedKeys }.forEach { (k, v) ->
                            when (v) {
                                is Boolean -> put(k, JSONObject().put("t", "b").put("v", v))
                                is Int -> put(k, JSONObject().put("t", "i").put("v", v))
                                is Long -> put(k, JSONObject().put("t", "l").put("v", v))
                                is Float -> put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                                is String -> put(k, JSONObject().put("t", "s").put("v", v))
                                is Set<*> -> put(k, JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() })))
                            }
                        }
                    })
                }
            })
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) }
            ?: error(ReplyLanguage.ui("No se pudo escribir el fichero", "Could not write the file"))
        Summary(tasks.size, memories.size, prefsToCopy.size)
    }

    private suspend fun taskJson(t: TaskEntity): JSONObject {
        val custom = database.reminderDao().forTask(t.id).filter { it.kind == TaskReminder.Kind.CUSTOM.name }.mapNotNull { it.offsetMinutes }
        return JSONObject()
            .put("title", t.title).put("description", t.description)
            .put("status", t.status.name).put("category", t.category.name).put("priority", t.priority.name)
            .put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)
            .putOpt("dueAt", t.dueAt).put("dueHasTime", t.dueHasTime).putOpt("completedAt", t.completedAt)
            .putOpt("recurrence", t.recurrence).put("autoReminders", t.autoReminders)
            .putOpt("placeTrigger", t.placeTrigger).putOpt("googleTaskId", t.googleTaskId)
            .putOpt("meetingTitle", t.meetingTitle).putOpt("meetingStart", t.meetingStart)
            .put("customReminders", JSONArray(custom))
    }

    /**
     * Imports a backup. The app then restarts itself so settings, places and routines are read again.
     * Returns how many NEW tasks and memories were added.
     */
    suspend fun import(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error(ReplyLanguage.ui("No se pudo leer el fichero", "Could not read the file"))
        val root = JSONObject(text)
        require(root.optString("app") == "Lumi") { ReplyLanguage.ui("Este fichero no es una copia de Lumi", "This file isn't a Lumi backup") }
        require(root.optInt("format") <= FORMAT_VERSION) { ReplyLanguage.ui("La copia es de una versión más nueva de Lumi: actualiza la app", "The backup is from a newer Lumi: update the app") }

        // Tasks: skip the ones that already exist (same title and creation time)
        val existing = database.taskDao().getAllTasksSnapshot().map { it.title to it.createdAt }.toSet()
        var addedTasks = 0
        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val o = tasks.getJSONObject(i)
            if ((o.getString("title") to o.getLong("createdAt")) in existing) continue
            val entity = TaskEntity(
                title = o.getString("title"), description = o.optString("description"),
                status = runCatching { TaskStatus.valueOf(o.getString("status")) }.getOrDefault(TaskStatus.TODO),
                category = runCatching { TaskCategory.valueOf(o.getString("category")) }.getOrDefault(TaskCategory.PERSONAL),
                priority = runCatching { TaskPriority.valueOf(o.optString("priority")) }.getOrDefault(TaskPriority.NONE),
                createdAt = o.getLong("createdAt"), updatedAt = o.optLong("updatedAt", o.getLong("createdAt")),
                dueAt = o.optLongOrNull("dueAt"), dueHasTime = o.optBoolean("dueHasTime"), completedAt = o.optLongOrNull("completedAt"),
                recurrence = o.optStringOrNull("recurrence"), autoReminders = o.optBoolean("autoReminders", true),
                placeTrigger = o.optStringOrNull("placeTrigger"), googleTaskId = o.optStringOrNull("googleTaskId"),
                meetingTitle = o.optStringOrNull("meetingTitle"), meetingStart = o.optLongOrNull("meetingStart")
            )
            // Through the repository: reminders, geofences and the widget get scheduled
            val id = repository.insertTask(entity.toDomain())
            val saved = repository.getTask(id) ?: continue
            o.optJSONArray("customReminders")?.let { arr -> for (j in 0 until arr.length()) repository.addCustomReminder(saved, arr.getInt(j)) }
            addedTasks++
        }

        // Memory: no duplicate texts
        val known = database.memoryDao().all().map { it.text.trim().lowercase() }.toMutableSet()
        var addedMemories = 0
        val memories = root.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until memories.length()) {
            val o = memories.getJSONObject(i)
            val t = o.getString("text").trim()
            if (!known.add(t.lowercase())) continue
            database.memoryDao().insert(MemoryEntity(text = t, createdAt = o.optLong("createdAt", System.currentTimeMillis())))
            addedMemories++
        }

        // Settings, places, routines and quick contacts: written as they are (commit: before the restart)
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        var files = 0
        for (name in prefsToCopy) {
            val values = prefs.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            values.keys().forEach { k ->
                if (k in skippedKeys) return@forEach
                val e = values.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> editor.putBoolean(k, e.getBoolean("v"))
                    "i" -> editor.putInt(k, e.getInt("v"))
                    "l" -> editor.putLong(k, e.getLong("v"))
                    "f" -> editor.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> editor.putString(k, e.getString("v"))
                    "ss" -> editor.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
            editor.commit()
            files++
        }
        Summary(addedTasks, addedMemories, files)
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        const val FORMAT_VERSION = 1
    }
}
