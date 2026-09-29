package io.github.salex27.lumi.data.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Extracts English dates and times from a sentence and returns the text without them (the English twin of
 * [SpanishDateParser], same [SpanishDateParser.Result]). Pure Kotlin, tested. Examples:
 *  - "call mom tomorrow at 6"           → tomorrow 18:00, "call mom"
 *  - "hand in the report by Friday"     → Friday (no time), "hand in the report"
 *  - "dentist on October 15 at 10:30am" → Oct 15 10:30, "dentist"
 *  - "check the oven in 20 minutes"     → now + 20 min
 * "at 5" with no am/pm and no part of day means 17:00 (people rarely schedule things at 5 in the morning).
 */
object EnglishDateParser {

    private const val I = "(?i)"

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY
    )
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"
    )
    private const val MONTH = "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept?|oct|nov|dec)"
    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30)

    /** Deadline words left over once the date is removed ("by", "before", "until"…). */
    private const val DEADLINE = "(?:(?:by|before|until|till|no\\s+later\\s+than|due|for|on|this|next)\\s+)?"

    fun parse(input: String, now: LocalDateTime): SpanishDateParser.Result? {
        var text = " $input "
        var date: LocalDate? = null
        var time: LocalTime? = null
        var period: String? = null // "morning" | "afternoon" | "evening" | "night"

        fun consume(regex: Regex, onMatch: (MatchResult) -> Unit): Boolean {
            val m = regex.find(text) ?: return false
            onMatch(m)
            text = text.removeRange(m.range).let { " $it " }
            return true
        }

        // ── Relative offsets: "in 20 minutes", "in 2 hours", "in a week" ─────────
        var relative: LocalDateTime? = null
        consume(Regex("$I\\b(?:in|within)\\s+(\\d+|a|an|one|two|three|four|five|ten|fifteen|twenty|thirty|half\\s+an)\\s+(minutes?|mins?|hours?|hrs?|days?|weeks?|months?)\\b")) { m ->
            val raw = m.groupValues[1].lowercase()
            val unit = m.groupValues[2].lowercase()
            relative = if (raw.startsWith("half")) now.plusMinutes(30) else {
                val n = (raw.toLongOrNull() ?: NUMBER_WORDS[raw]?.toLong() ?: 1L)
                when {
                    unit.startsWith("min") -> now.plusMinutes(n)
                    unit.startsWith("h") -> now.plusHours(n)
                    unit.startsWith("d") -> now.plusDays(n)
                    unit.startsWith("mo") -> now.plusMonths(n)
                    else -> now.plusWeeks(n)
                }
            }
        }
        relative?.let { r ->
            val precise = Regex("$I\\b(?:minutes?|mins?|hours?|hrs?)\\b").containsMatchIn(input) || input.contains("half an", true)
            return SpanishDateParser.Result(r, precise, tidy(text))
        }

        // ── Part of day ─────────────────────────────────────────────────────────
        consume(Regex("$I\\b(?:in\\s+the\\s+|this\\s+|tomorrow\\s+(?=morning|afternoon|evening|night))?(morning|afternoon|evening)\\b")) { m ->
            period = m.groupValues[1].lowercase()
            if (m.value.contains("tomorrow", true)) date = now.toLocalDate().plusDays(1)
        }
        consume(Regex("$I\\b(?:tonight|tomorrow\\s+night|at\\s+night)\\b")) { m ->
            period = "night"
            date = if (m.value.contains("tomorrow", true)) now.toLocalDate().plusDays(1) else date ?: now.toLocalDate()
        }

        // ── Day ─────────────────────────────────────────────────────────────────
        if (date == null) consume(Regex("$I\\b${DEADLINE}(?:the\\s+)?day\\s+after\\s+tomorrow\\b")) { date = now.toLocalDate().plusDays(2) }
        if (date == null) consume(Regex("$I\\b${DEADLINE}tomorrow\\b")) { date = now.toLocalDate().plusDays(1) }
        if (date == null) consume(Regex("$I\\b${DEADLINE}today\\b")) { date = now.toLocalDate() }
        if (date == null) consume(Regex("$I\\b${DEADLINE}next\\s+week\\b")) { date = now.toLocalDate().plusWeeks(1) }
        if (date == null) consume(Regex("$I\\b${DEADLINE}next\\s+month\\b")) { date = now.toLocalDate().plusMonths(1) }
        if (date == null) consume(Regex("$I\\b${DEADLINE}next\\s+year\\b")) { date = now.toLocalDate().plusYears(1) }
        // "this weekend" → Saturday (today if it is already the weekend); "next weekend" → the following Saturday
        if (date == null) consume(Regex("$I\\b(?:(?:by|for|on|over)\\s+)?(?:(this|next)\\s+)?weekend\\b")) { m ->
            val today = now.toLocalDate()
            val weekendNow = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
            var sat = today
            while (sat.dayOfWeek != DayOfWeek.SATURDAY) sat = sat.plusDays(1)
            date = when {
                m.groupValues[1].equals("next", true) -> sat.plusWeeks(1)
                weekendNow -> today
                else -> sat
            }
        }
        // "October 15", "Oct 15th", "15 October", "the 15th of October"
        if (date == null) consume(Regex("$I\\b${DEADLINE}(?:the\\s+)?$MONTH\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")) { m ->
            date = dayOfMonth(now.toLocalDate(), monthOf(m.groupValues[1]), m.groupValues[2].toInt())
        }
        if (date == null) consume(Regex("$I\\b${DEADLINE}(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTH\\b")) { m ->
            date = dayOfMonth(now.toLocalDate(), monthOf(m.groupValues[2]), m.groupValues[1].toInt())
        }
        // "on the 3rd" → this month (or next, if it has passed)
        if (date == null) consume(Regex("$I\\b(?:on\\s+|by\\s+)the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b")) { m ->
            val today = now.toLocalDate()
            val d = m.groupValues[1].toInt().coerceIn(1, 31)
            var candidate = today.withDayOfMonth(d.coerceAtMost(today.lengthOfMonth()))
            if (candidate.isBefore(today)) candidate = today.plusMonths(1).let { it.withDayOfMonth(d.coerceAtMost(it.lengthOfMonth())) }
            date = candidate
        }
        // "on Friday", "this Monday", "next Tuesday", "by Friday"
        if (date == null) consume(Regex("$I\\b(?:(?:on|by|before|until|this|next|due|for)\\s+)?(?:(next)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")) { m ->
            val target = WEEKDAYS.getValue(m.groupValues[2].lowercase())
            val today = now.toLocalDate()
            var d = today.plusDays(1)
            while (d.dayOfWeek != target) d = d.plusDays(1)
            // "this Friday" said on a Friday means today
            if (Regex("$I\\bthis\\s").containsMatchIn(m.value) && today.dayOfWeek == target) d = today
            date = d
        }

        // ── Time ────────────────────────────────────────────────────────────────
        consume(Regex("$I\\b(?:at\\s+|around\\s+|by\\s+)?(noon|midday|midnight)\\b")) { m ->
            time = if (m.groupValues[1].lowercase() == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON
        }
        // Only accepted with a clear marker, so loose numbers aren't mistaken ("buy 3 loaves"):
        // "at 5", "at 5:30", "5pm", "17:30". Groups: 1=hour, 2=minutes, 3=am/pm.
        if (time == null) consume(
            Regex("$I\\b(?:(?:at|around|by|from)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?|(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)|(\\d{1,2}):(\\d{2}))(?=\\W|$)")
        ) { m ->
            val g = m.groupValues
            val (hs, ms, suffix) = when {
                g[1].isNotBlank() -> Triple(g[1], g[2], g[3])
                g[4].isNotBlank() -> Triple(g[4], g[5], g[6])
                else -> Triple(g[7], g[8], "")
            }
            var h = hs.toInt()
            val min = ms.toIntOrNull() ?: 0
            val s = suffix.lowercase().replace(".", "")
            when {
                s == "pm" && h < 12 -> h += 12
                s == "am" && h == 12 -> h = 0
                s.isBlank() && (period == "afternoon" || period == "evening" || period == "night") && h < 12 -> h += 12
                s.isBlank() && period == null && h in 1..7 && g[7].isBlank() -> h += 12 // "at 5" → 17:00 (but "07:30" stays)
            }
            if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
        }
        if (time == null && period != null && date != null) time = when (period) {
            "morning" -> LocalTime.of(9, 0)
            "afternoon" -> LocalTime.of(16, 0)
            "evening" -> LocalTime.of(19, 0)
            else -> LocalTime.of(21, 0)
        }

        if (date == null && time == null) {
            if (period == null) return null
            date = now.toLocalDate()
        }
        val day = date ?: run {
            // Time only: today if it hasn't passed yet, otherwise tomorrow
            if (time!!.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        }
        return SpanishDateParser.Result(day.atTime(time ?: LocalTime.of(9, 0)), time != null, tidy(text))
    }

    private fun monthOf(name: String): Int {
        val n = name.lowercase().take(3)
        return MONTHS.indexOfFirst { it.startsWith(n) } + 1
    }

    /** That day of that month, this year or next year if it has already passed. */
    private fun dayOfMonth(today: LocalDate, month: Int, day: Int): LocalDate {
        val thisYear = LocalDate.of(today.year, month, 1).let { it.withDayOfMonth(day.coerceIn(1, it.lengthOfMonth())) }
        return if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
    }

    /** Removes double spaces and dangling connectors at the end ("call mom on" → "call mom"). */
    private fun tidy(text: String): String = text.replace(Regex("\\s+"), " ").trim()
        .replace(Regex("$I\\s+(?:on|at|by|for|before|until|the|this|next|due|in)$"), "")
        .trim().trim(',', '.')
}
