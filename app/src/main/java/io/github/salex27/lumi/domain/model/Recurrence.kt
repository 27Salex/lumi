package io.github.salex27.lumi.domain.model

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * A task's repetition rule. Stored as compact text in Room:
 * "DAILY", "WEEKDAYS", "WEEKLY:MO,TH", "MONTHLY:1", "YEARLY".
 * Completing a recurring task creates the next one with [next].
 */
data class Recurrence(
    val frequency: Frequency,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null
) {
    enum class Frequency { DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

    /** Next date strictly after [after]. */
    fun next(after: LocalDate): LocalDate = when (frequency) {
        Frequency.DAILY -> after.plusDays(1)
        Frequency.WEEKDAYS -> generateSequence(after.plusDays(1)) { it.plusDays(1) }
            .first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        Frequency.WEEKLY -> {
            val days = daysOfWeek.ifEmpty { setOf(after.dayOfWeek) }
            generateSequence(after.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek in days }
        }
        Frequency.MONTHLY -> {
            val dom = dayOfMonth ?: after.dayOfMonth
            // Day 31 in short months → last day of the month
            fun inMonth(anyDay: LocalDate) = anyDay.withDayOfMonth(dom.coerceAtMost(anyDay.lengthOfMonth()))
            val thisMonth = inMonth(after)
            if (thisMonth.isAfter(after)) thisMonth else inMonth(after.withDayOfMonth(1).plusMonths(1))
        }
        Frequency.YEARLY -> after.plusYears(1)
    }

    /** First valid date on or after [from], for new tasks without an explicit date. */
    fun firstOnOrAfter(from: LocalDate): LocalDate = next(from.minusDays(1))

    fun serialize(): String = when (frequency) {
        Frequency.WEEKLY -> "WEEKLY:" + daysOfWeek.sorted().joinToString(",") { it.name.take(2) }
        Frequency.MONTHLY -> "MONTHLY:${dayOfMonth ?: 1}"
        else -> frequency.name
    }

    /** UI text: "Cada día" / "Every day", "Cada lunes y jueves" / "Every Monday and Thursday"… */
    fun label(lang: Lang = ReplyLanguage.current): String {
        val en = lang == Lang.EN
        return when (frequency) {
            Frequency.DAILY -> if (en) "Every day" else "Cada día"
            Frequency.WEEKDAYS -> if (en) "Weekdays" else "Días laborables"
            Frequency.WEEKLY -> {
                val names = daysOfWeek.sorted().map { it.getDisplayName(TextStyle.FULL, if (en) EN else ES) }
                (if (en) "Every " else "Cada ") + when (names.size) {
                    0 -> if (en) "week" else "semana"
                    1 -> names[0]
                    else -> names.dropLast(1).joinToString(", ") + (if (en) " and " else " y ") + names.last()
                }
            }
            Frequency.MONTHLY -> if (en) "Monthly, on day ${dayOfMonth ?: 1}" else "Cada mes, el día ${dayOfMonth ?: 1}"
            Frequency.YEARLY -> if (en) "Every year" else "Cada año"
        }
    }

    companion object {
        private val ES = Locale.forLanguageTag("es-ES")
        private val EN = Locale.forLanguageTag("en-US")

        fun parse(value: String?): Recurrence? {
            if (value.isNullOrBlank()) return null
            val parts = value.trim().uppercase().split(":", limit = 2)
            val freq = runCatching { Frequency.valueOf(parts[0]) }.getOrNull() ?: return null
            return when (freq) {
                Frequency.WEEKLY -> Recurrence(freq, parts.getOrNull(1).orEmpty().split(",")
                    .mapNotNull { code -> DayOfWeek.entries.firstOrNull { it.name.startsWith(code.trim()) && code.isNotBlank() } }.toSet())
                Frequency.MONTHLY -> Recurrence(freq, dayOfMonth = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 31) ?: 1)
                else -> Recurrence(freq)
            }
        }
    }
}

/** Calendar meeting a task is linked to (e.g. "prepare slides" → "Sprint meeting"). */
data class LinkedMeeting(val eventId: Long, val title: String, val start: Long)

/** A task reminder. AUTO = decided by Lumi (recomputed automatically); CUSTOM = requested by the user. */
data class TaskReminder(
    val id: Long = 0,
    val taskId: Long,
    val triggerAt: Long,
    val kind: Kind,
    /** For relative CUSTOM reminders: minutes before the due time (recomputed if the date changes). */
    val offsetMinutes: Int? = null,
    /** Notification text: "Tomorrow", "In 1 hour", "The meeting starts in 15 min"… */
    val label: String
) {
    enum class Kind { AUTO, CUSTOM }
}
