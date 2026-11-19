package io.github.salex27.lumi.domain.relay

import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskKind
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Claude connector's item format (see tools/lumi-relay) ↔ Lumi tasks. Pure, tested.
 *
 * Items come from an LLM through the relay: they only ever become structured task fields here, never a command,
 * an intent or a prompt (AGENTS.md rule 9). Anything malformed is rejected with a message Claude can read.
 */
object RelayItems {
    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private const val MAX_TITLE = 200
    private const val MAX_NOTES = 4000

    class Invalid(message: String) : IllegalArgumentException(message)

    // ── Tasks → snapshot ──

    /** One task as the relay stores it (dates local to [zone]). */
    fun toJson(task: Task, zone: ZoneId): JsonObject = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        if (task.description.isNotBlank()) put("notes", task.description)
        put("kind", task.kind.name.lowercase())
        put("status", task.status.name)
        put("category", task.category.name)
        put("priority", task.priority.name)
        task.dueAt?.let { due ->
            val dt = Instant.ofEpochMilli(due).atZone(zone)
            put("date", dt.toLocalDate().format(DATE))
            if (task.dueHasTime) put("time", dt.toLocalTime().format(TIME))
        }
        task.endAt?.let { put("end_time", Instant.ofEpochMilli(it).atZone(zone).toLocalTime().format(TIME)) }
        task.recurrence?.let { put("repeat", repeatOut(it)) }
        task.placeTrigger?.let { put("place", it.place) }
    }

    /** Which tasks go up: everything active, plus what closed in the last [keepDays] days. */
    fun forSnapshot(tasks: List<Task>, now: Long, keepDays: Int = 30): List<Task> {
        val since = now - keepDays * 86_400_000L
        return tasks.filter { it.isActive || (it.completedAt ?: it.updatedAt) >= since }
            .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.id }))
            .take(1500)
    }

    // ── Items → tasks ──

    /** A new task from a create item. [today] fills the date of a recurring item without one. */
    fun newTask(item: JsonObject, zone: ZoneId, today: LocalDate, now: Long, inferCategory: (String) -> TaskCategory?): Task {
        val title = item.str("title")?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: throw Invalid("title is required")
        val kind = item.str("kind")?.let { TaskKind.fromString(it) ?: throw Invalid("unknown kind $it") } ?: TaskKind.TASK
        val recurrence = item.str("repeat")?.let { repeatIn(it) }
        val time = item.str("time")?.let { time(it) }
        var date = item.str("date")?.let { date(it) }
        if (date == null && recurrence != null) date = recurrence.firstOnOrAfter(today)
        if (date == null && time != null) date = today
        val due = date?.let { (if (time != null) it.atTime(time) else it.atStartOfDay()).atZone(zone).toInstant().toEpochMilli() }
        val notes = item.str("notes")?.take(MAX_NOTES).orEmpty()
        return Task(
            title = title,
            description = notes,
            kind = kind,
            category = item.str("category")?.let { TaskCategory.fromString(it) } ?: inferCategory("$title $notes") ?: TaskCategory.PERSONAL,
            priority = item.str("priority")?.let { TaskPriority.fromString(it) } ?: TaskPriority.NONE,
            dueAt = due,
            dueHasTime = due != null && time != null,
            recurrence = recurrence,
            durationMinutes = if (time != null) duration(item, time) else null,
            createdAt = now,
            updatedAt = now
        )
    }

    /** [task] with the fields present in an update item applied (absent fields stay as they are). */
    fun applyUpdate(task: Task, item: JsonObject, zone: ZoneId, today: LocalDate, now: Long): Task {
        var t = task
        item.str("title")?.let { t = t.copy(title = it.trim().take(MAX_TITLE).ifEmpty { throw Invalid("title is empty") }) }
        item.str("notes")?.let { t = t.copy(description = it.take(MAX_NOTES)) }
        item.str("kind")?.let { t = t.copy(kind = TaskKind.fromString(it) ?: throw Invalid("unknown kind $it")) }
        item.str("category")?.let { t = t.copy(category = TaskCategory.fromString(it) ?: throw Invalid("unknown category $it")) }
        item.str("priority")?.let { t = t.copy(priority = TaskPriority.fromString(it) ?: throw Invalid("unknown priority $it")) }
        item.str("repeat")?.let { t = t.copy(recurrence = repeatIn(it)) }
        item.str("status")?.let { t = t.copy(status = status(it)) }

        if (item.bool("clear_date") == true) {
            t = t.copy(dueAt = null, dueHasTime = false, durationMinutes = null)
        } else {
            val newDate = item.str("date")?.let { date(it) }
            val newTime = item.str("time")?.let { time(it) }
            if (newDate != null || newTime != null) {
                val old = t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
                val d = newDate ?: old?.toLocalDate() ?: today
                val tm = newTime ?: if (t.dueHasTime) old?.toLocalTime() else null
                val dt = if (tm != null) LocalDateTime.of(d, tm) else d.atStartOfDay()
                t = t.copy(dueAt = dt.atZone(zone).toInstant().toEpochMilli(), dueHasTime = tm != null)
            }
            if (item.containsKey("end_time") || item.containsKey("duration_minutes")) {
                val start = t.dueAt?.takeIf { t.dueHasTime }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
                    ?: throw Invalid("an end needs a start time")
                t = t.copy(durationMinutes = duration(item, start))
            }
        }
        return t.copy(updatedAt = now)
    }

    /** Extra reminders asked in an item, in minutes before the start (1 minute .. 14 days, at most 5). */
    fun remindBefore(item: JsonObject): List<Int> =
        (item["remind_before_minutes"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
            .filter { it in 1..14 * 24 * 60 }.distinct().take(5)

    /** Short line for the op result ("Gym · 2026-11-20 19:00-20:00"). */
    fun describe(task: Task, zone: ZoneId): JsonObject = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        put("kind", task.kind.name.lowercase())
        task.dueAt?.let { due ->
            val dt = Instant.ofEpochMilli(due).atZone(zone)
            put("date", dt.toLocalDate().format(DATE))
            if (task.dueHasTime) put("time", dt.toLocalTime().format(TIME))
        }
        task.endAt?.let { put("end_time", Instant.ofEpochMilli(it).atZone(zone).toLocalTime().format(TIME)) }
        if (task.status != TaskStatus.TODO) put("status", task.status.name.lowercase())
    }

    // ── Field parsers ──

    private fun duration(item: JsonObject, start: LocalTime): Int? {
        item.int("duration_minutes")?.let { return it.takeIf { m -> m in 1..24 * 60 } ?: throw Invalid("duration_minutes must be 1..1440") }
        val end = item.str("end_time")?.let { time(it) } ?: return null
        var minutes = end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60
        if (minutes <= 0) minutes += 24 * 60 // ends after midnight (night shift)
        return minutes
    }

    fun repeatIn(value: String): Recurrence? {
        val v = value.trim().lowercase()
        if (v == "none" || v.isEmpty()) return null
        val r = Recurrence.parse(v.uppercase()) ?: throw Invalid("repeat must be daily | weekdays | weekly:mo,th | monthly:15 | yearly | none")
        if (r.frequency == Recurrence.Frequency.WEEKLY && r.daysOfWeek.isEmpty() && v.contains(':')) throw Invalid("unknown weekdays in $value")
        return r
    }

    fun repeatOut(r: Recurrence): String = r.serialize().lowercase()

    private fun status(v: String): TaskStatus = when (v.trim().lowercase()) {
        "todo" -> TaskStatus.TODO
        "in_progress" -> TaskStatus.IN_PROGRESS
        "done", "completed" -> TaskStatus.COMPLETED
        "cancelled", "canceled" -> TaskStatus.CANCELLED
        else -> throw Invalid("unknown status $v")
    }

    private fun date(v: String): LocalDate = runCatching { LocalDate.parse(v.trim(), DATE) }.getOrNull() ?: throw Invalid("date must be YYYY-MM-DD")

    private fun time(v: String): LocalTime {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(v.trim()) ?: throw Invalid("time must be HH:MM")
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) throw Invalid("time must be HH:MM")
        return LocalTime.of(h, min)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
