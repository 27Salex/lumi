package io.github.salex27.lumi.domain.reminder

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Lumi's automatic reminder policy (pure, tested). Decides HOW MANY reminders and WHEN depending on the kind of
 * task, to remind enough without nagging:
 *
 * Appointment with a time (e.g. dentist at 17:00):
 *  - the evening before at 20:00 (only if more than ~20 h away and it is work/health/study)
 *  - [leadMinutes] before (user setting, 30 min by default)
 *  - 10 min before (only if the previous one is ≥ 30 min)
 * Deadline without a time (e.g. "hand in the report on Friday"):
 *  - 2 days before at [morningHour] (only if ≥ 2 days away)
 *  - that day at [morningHour]
 *  - that day at 18:00 ("due today!")
 * Task linked to a meeting: 15 min before the meeting ("starts in 15 min, X is still open").
 * High priority: the evening before at 20:00 always (also without a time, whatever the category).
 * Custom reminders: the minutes-before the user asked for.
 * Labels are written in [lang] (the app language: they end up in notifications).
 */
object ReminderPlanner {

    data class Planned(val triggerAt: Long, val label: String)

    fun plan(
        task: Task,
        nowMillis: Long,
        leadMinutes: Int = 30,
        morningHour: Int = 9,
        customOffsets: List<Int> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        lang: Lang = ReplyLanguage.app
    ): List<Planned> {
        if (!task.isActive) return emptyList()
        val en = lang == Lang.EN
        val out = mutableListOf<Planned>()
        val due = task.dueAt

        if (task.autoReminders && due != null) {
            val dueDate = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            fun at(date: java.time.LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

            if (task.dueHasTime) {
                val important = task.priority == TaskPriority.HIGH ||
                    task.category in setOf(TaskCategory.WORK, TaskCategory.HEALTH, TaskCategory.STUDY)
                val eve = at(dueDate.minusDays(1), LocalTime.of(20, 0))
                if (important && due - nowMillis > 20 * HOUR) out += Planned(eve, if (en) "Tomorrow" else "Mañana")
                out += Planned(due - leadMinutes * MINUTE, (if (en) "In " else "En ") + humanMinutes(leadMinutes, lang))
                if (leadMinutes >= 30) out += Planned(due - 10 * MINUTE, if (en) "In 10 min" else "En 10 min")
            } else {
                val morning = LocalTime.of(morningHour, 0)
                if (task.priority == TaskPriority.HIGH && due - nowMillis > DAY) {
                    out += Planned(at(dueDate.minusDays(1), LocalTime.of(20, 0)), if (en) "Due tomorrow (high priority)" else "Mañana vence (prioridad alta)")
                }
                if (due - nowMillis > 2 * DAY) out += Planned(at(dueDate.minusDays(2), morning), if (en) "Due the day after tomorrow" else "Vence pasado mañana")
                out += Planned(at(dueDate, morning), if (en) "Due today" else "Vence hoy")
                out += Planned(at(dueDate, LocalTime.of(18, 0)), if (en) "Due today!" else "¡Hoy vence!")
            }
        }

        task.meeting?.let { m ->
            out += Planned(m.start - 15 * MINUTE, if (en) "«${m.title}» starts in 15 min" else "«${m.title}» empieza en 15 min")
        }

        if (due != null) customOffsets.distinct().forEach { off ->
            out += Planned(due - off * MINUTE, if (off == 0) (if (en) "Now" else "Ahora") else (if (en) "In " else "En ") + humanMinutes(off, lang))
        }

        // Future only, no near-duplicates (< 2 min apart), in order
        return out.filter { it.triggerAt > nowMillis }
            .sortedBy { it.triggerAt }
            .fold(mutableListOf()) { acc, p -> if (acc.none { kotlin.math.abs(it.triggerAt - p.triggerAt) < 2 * MINUTE }) acc += p; acc }
    }

    /** 30 → "30 min", 120 → "2 horas" / "2 hours", 1440 → "1 día" / "1 day". */
    fun humanMinutes(m: Int, lang: Lang = ReplyLanguage.current): String {
        val en = lang == Lang.EN
        return when {
            m < 60 -> "$m min"
            m % (60 * 24) == 0 -> (m / (60 * 24)).let { if (en) (if (it == 1) "1 day" else "$it days") else (if (it == 1) "1 día" else "$it días") }
            m % 60 == 0 -> (m / 60).let { if (en) (if (it == 1) "1 hour" else "$it hours") else (if (it == 1) "1 hora" else "$it horas") }
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
