package io.github.salex27.lumi.data.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Extracts Spanish dates and times from a sentence and returns the text without them. English: [EnglishDateParser].
 * Pure Kotlin (JVM-testable). Examples:
 *  - "llamar a mamá mañana a las 6"      → tomorrow 18:00, "llamar a mamá"
 *  - "entregar informe antes del viernes" → Friday (no time), "entregar informe"
 *  - "cita con el dentista el 15 de octubre a las 10:30"
 *  - "revisar el horno en 20 minutos"
 */
object SpanishDateParser {

    data class Result(
        val dateTime: LocalDateTime,
        val hasTime: Boolean,
        /** The original sentence without the time expressions, ready to use as a title. */
        val remainingText: String
    ) {
        /** "2026-10-03T17:00" with a time, "2026-10-03" with a day only (the TaskAICommand.dueDate format). */
        fun isoDate(): String = if (hasTime) dateTime.toString() else dateTime.toLocalDate().toString()
    }

    private const val I = "(?iu)"
    private const val MANANA = "ma(?:ñ|n)ana"

    private val WEEKDAYS = mapOf(
        "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY,
        "miercoles" to DayOfWeek.WEDNESDAY, "miércoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY, "sábado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY
    )

    private val MONTHS = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    private val NUMBER_WORDS = mapOf("un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "media" to 30)

    // Deadline connectors left over once the date is removed ("para el", "antes del"…)
    private val DEADLINE_PREFIX = "(?:(?:para|al|antes\\s+de|antes\\s+del|hasta|hasta\\s+el|como\\s+tarde|deadline|fecha\\s+l[ií]mite)\\s+)?"

    fun parse(input: String, now: LocalDateTime): Result? {
        var text = " $input "
        var date: LocalDate? = null
        var time: LocalTime? = null
        var dayPeriod: String? = null // "manana" | "tarde" | "noche"

        fun consume(regex: Regex, onMatch: (MatchResult) -> Unit): Boolean {
            val m = regex.find(text) ?: return false
            onMatch(m)
            text = text.removeRange(m.range).let { " $it " }
            return true
        }

        // ── Relative offsets: "en 2 horas", "dentro de 30 minutos" ─────────────────
        consume(Regex("$I\\s(?:en|dentro\\s+de)\\s+(\\d+|un|una|dos|tres|media)\\s+(horas?|minutos?|mins?)\\b")) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1].lowercase()] ?: 1
            val isHour = m.groupValues[2].lowercase().startsWith("hora")
            val target = if (m.groupValues[1].lowercase() == "media") now.plusMinutes(30)
            else if (isHour) now.plusHours(n.toLong()) else now.plusMinutes(n.toLong())
            date = target.toLocalDate(); time = target.toLocalTime().withSecond(0).withNano(0)
        }

        // ── Day ─────────────────────────────────────────────────────────────────
        consume(Regex("$I\\s${DEADLINE_PREFIX}pasado\\s+$MANANA\\b")) { date = now.toLocalDate().plusDays(2) }

        // Part of day: "por la mañana", "esta tarde", "de la noche" (before "mañana" = tomorrow)
        consume(Regex("$I\\s(?:por|de|en|esta)\\s+(?:la\\s+)?($MANANA|tarde|noche)\\b")) { m ->
            val p = m.groupValues[1].lowercase()
            dayPeriod = if (p.startsWith("ma")) "manana" else p
            if (m.value.trim().lowercase().startsWith("esta") && date == null) date = now.toLocalDate()
        }

        if (date == null) consume(Regex("$I\\s$DEADLINE_PREFIX$MANANA\\b")) { date = now.toLocalDate().plusDays(1) }
        if (date == null) consume(Regex("$I\\s${DEADLINE_PREFIX}hoy\\b")) { date = now.toLocalDate() }
        if (date == null) consume(Regex("$I\\s${DEADLINE_PREFIX}esta\\s+semana\\b")) {
            date = now.toLocalDate().with(DayOfWeek.FRIDAY).let { if (it.isBefore(now.toLocalDate())) now.toLocalDate() else it }
        }

        // "15 de octubre", "el 3 de marzo"
        if (date == null) consume(Regex("$I\\s${DEADLINE_PREFIX}(?:el\\s+)?(\\d{1,2})\\s+de\\s+(${MONTHS.joinToString("|")})\\b")) { m ->
            date = futureDate(now.toLocalDate(), m.groupValues[1].toInt(), MONTHS.indexOf(m.groupValues[2].lowercase()) + 1)
        }

        // "15/10", "el 3/11/2026"
        if (date == null) consume(Regex("$I\\s${DEADLINE_PREFIX}(?:el\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")) { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            date = if (y != null) runCatching { LocalDate.of(y, mo, d) }.getOrNull() else futureDate(now.toLocalDate(), d, mo)
        }

        // "el viernes", "este lunes", "el próximo martes", "el jueves que viene"
        if (date == null) consume(
            Regex("$I\\s${DEADLINE_PREFIX}(?:el\\s+|este\\s+)?(pr[oó]ximo\\s+)?(${WEEKDAYS.keys.joinToString("|")})(\\s+que\\s+viene)?\\b")
        ) { m ->
            val dow = WEEKDAYS.getValue(m.groupValues[2].lowercase())
            val forceNextWeek = m.groupValues[1].isNotBlank() || m.groupValues[3].isNotBlank()
            var d = now.toLocalDate()
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            if (forceNextWeek && d == now.toLocalDate()) d = d.plusDays(7)
            date = d
        }

        // ── Time ────────────────────────────────────────────────────────────────
        if (time == null) consume(Regex("$I\\s(?:a\\s+)?(?:las?\\s+)?mediod[ií]a\\b")) { time = LocalTime.NOON }
        // Only accepted as a time with a clear marker, so loose numbers aren't mistaken ("comprar 3 panes"):
        // "a las 5", "sobre la una", "17:30", "5pm". Groups: 1=hour, 2=minutes, 3=am/pm, 4=part of day.
        val period = "(?:\\s+(?:de\\s+la|por\\s+la)\\s+($MANANA|tarde|noche))?(?=[\\s,.;!?]|$)"
        val timePatterns = listOf(
            Regex("$I\\s(?:a|sobre|hacia)\\s+las?\\s+(\\d{1,2}|una)(?:[:.h](\\d{2}))?\\s*(am|pm|h)?$period"),
            Regex("$I\\s(\\d{1,2})[:.h](\\d{2})\\s*(am|pm|h)?$period"),
            Regex("$I\\s(\\d{1,2})()\\s*(am|pm)$period")
        )
        for (pattern in timePatterns) if (time == null) consume(pattern) { m ->
            var h = if (m.groupValues[1].lowercase() == "una") 1 else m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val suffix = m.groupValues[3].lowercase()
            val period = m.groupValues[4].lowercase().ifBlank { null }?.let { if (it.startsWith("ma")) "manana" else it }
            if (period != null) dayPeriod = period
            when {
                suffix.startsWith("p") && h < 12 -> h += 12
                suffix.startsWith("a") && h == 12 -> h = 0
                (dayPeriod == "tarde" || dayPeriod == "noche") && h < 12 -> h += 12
                suffix.isBlank() && dayPeriod == null && h in 1..7 -> h += 12 // "a las 5" → 17:00
            }
            if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
        }

        if (time == null && dayPeriod != null) {
            time = when (dayPeriod) { "manana" -> LocalTime.of(9, 0); "tarde" -> LocalTime.of(16, 0); else -> LocalTime.of(21, 0) }
        }

        if (date == null && time == null) return null

        val resolvedDate = date ?: run {
            // Time only: today if it hasn't passed yet, otherwise tomorrow
            if (time!!.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        }
        return Result(
            dateTime = LocalDateTime.of(resolvedDate, time ?: LocalTime.of(9, 0)),
            hasTime = time != null,
            remainingText = cleanup(text)
        )
    }

    private fun futureDate(today: LocalDate, day: Int, month: Int): LocalDate? {
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
    }

    /** Removes double spaces and dangling connectors at the end ("comprar pan para el" → "comprar pan"). */
    private fun cleanup(text: String): String {
        var t = text.replace(Regex("\\s+"), " ").trim().trim(',', ';')
        val trailing = Regex("(?iu)(?:\\s+|^)(?:el|la|los|las|de|del|al|para|antes|hasta|a|en|y|sobre)$")
        while (trailing.containsMatchIn(t)) t = t.replace(trailing, "").trim().trim(',', ';')
        return t.trim()
    }
}
