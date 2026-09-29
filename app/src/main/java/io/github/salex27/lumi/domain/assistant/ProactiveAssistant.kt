package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Proactive Lumi (pure, tested): finds free time slots and writes the evening check-in.
 * LLMs never compute any of this; at most they phrase it.
 */
object FreeTimeFinder {

    /** Free slot from [start] to [end] with the task Lumi suggests doing early. */
    data class FreeSlot(val start: Long, val end: Long, val suggestion: Task?, val alternatives: List<Task>) {
        val minutes: Int get() = ((end - start) / 60_000).toInt()
    }

    const val MIN_MINUTES = 25
    /** "Awake" hours: no slots are suggested in the middle of the night. */
    const val DAY_START_HOUR = 8
    const val DAY_END_HOUR = 22

    /**
     * The slot starting NOW (or the next one today if you are busy now) until the next meeting or timed task.
     * Only if it lasts at least [MIN_MINUTES]. [candidates] are the day plan's suggested tasks, in order.
     */
    fun find(
        now: LocalDateTime,
        events: List<AgendaEvent>,
        tasks: List<Task>,
        candidates: List<Task>,
        zone: ZoneId = ZoneId.systemDefault()
    ): FreeSlot? {
        if (now.hour < DAY_START_HOUR || now.hour >= DAY_END_HOUR) return null
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val dayEnd = now.toLocalDate().atTime(DAY_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

        // Busy ranges: timed meetings and timed tasks (30 min by default)
        val busy = events.filter { !it.allDay }.map { it.begin to maxOf(it.end, it.begin + 15 * 60_000L) } +
            tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null }.map { it.dueAt!! to it.dueAt + 30 * 60_000L }
        val sorted = busy.filter { it.second > nowMs && it.first < dayEnd }.sortedBy { it.first }

        var cursor = nowMs
        for ((b, e) in sorted) {
            if (b - cursor >= MIN_MINUTES * 60_000L) return slot(cursor, b, candidates)
            cursor = maxOf(cursor, e)
        }
        return if (dayEnd - cursor >= MIN_MINUTES * 60_000L) slot(cursor, dayEnd, candidates) else null
    }

    private fun slot(start: Long, end: Long, candidates: List<Task>): FreeSlot {
        // A slot suggests tasks without a fixed time (timed tasks already have their moment)
        val flexible = candidates.filter { it.isActive && !it.dueHasTime }
        return FreeSlot(start, end, flexible.firstOrNull(), flexible.drop(1).take(3))
    }
}

object CheckInComposer {

    data class CheckIn(val title: String, val body: String, val openToday: List<Task>)

    /**
     * Evening check-in: what you closed today and what is left for today (overdue included). Null if nothing happened
     * today (nothing done or pending): no need to bother. Written in [lang] (the app language: it is a notification).
     */
    fun compose(tasks: List<Task>, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault(), lang: Lang = ReplyLanguage.app): CheckIn? {
        val en = lang == Lang.EN
        val today = now.toLocalDate()
        fun Long.date(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
        val done = tasks.count { it.status == TaskStatus.COMPLETED && it.completedAt?.date() == today }
        val open = tasks.filter { it.isActive && it.dueAt?.let { d -> !d.date().isAfter(today) } == true }
            .sortedWith(compareBy({ -it.priority.rank }, { it.dueAt }))
        if (done == 0 && open.isEmpty()) return null

        val title = when {
            open.isEmpty() -> if (en) "Day wrapped up" else "Día cerrado"
            done == 0 -> if (en) "Daily check-in" else "Repaso del día"
            else -> if (en) "Today: $done done" else "Hoy: $done ${if (done == 1) "hecha" else "hechas"}"
        }
        val body = when {
            open.isEmpty() -> if (en) "You closed $done ${if (done == 1) "task" else "tasks"} and nothing is left for today. Nice work."
            else "Has cerrado $done ${if (done == 1) "tarea" else "tareas"} y no queda nada para hoy. Buen trabajo."
            else -> {
                val names = open.take(3).joinToString(", ") { "«${it.title}»" } +
                    if (open.size > 3) (if (en) " and ${open.size - 3} more" else " y ${open.size - 3} más") else ""
                val urgent = if (open.any { it.priority == TaskPriority.HIGH }) (if (en) " Something is urgent." else " Hay algo urgente.") else ""
                if (en) "${open.size} left for today: $names.$urgent Move them to tomorrow, or are they done?"
                else "Quedan ${open.size} para hoy: $names.$urgent ¿Lo pasamos a mañana o ya está hecho?"
            }
        }
        return CheckIn(title, body, open)
    }
}
