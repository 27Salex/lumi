package io.github.salex27.lumi.domain.time

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Formats due dates in natural language, Spanish or English:
 * "hoy a las 17:00" / "today at 17:00", "mañana" / "tomorrow", "el viernes 3" / "Friday 3", "el 15 de octubre" / "October 15".
 * [lang] defaults to the language of the reply in progress; screens pass the app language.
 */
object DueDateFormatter {

    private val ES = Locale.forLanguageTag("es-ES")
    private val EN = Locale.forLanguageTag("en-US")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun format(
        dueAt: Long,
        hasTime: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        lang: Lang = ReplyLanguage.current
    ): String {
        val due = LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAt), zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), due.toLocalDate())
        val en = lang == Lang.EN
        val day = when {
            days == 0L -> if (en) "today" else "hoy"
            days == 1L -> if (en) "tomorrow" else "mañana"
            days == 2L -> if (en) "the day after tomorrow" else "pasado mañana"
            days == -1L -> if (en) "yesterday" else "ayer"
            days in 3..6 -> if (en) due.format(DateTimeFormatter.ofPattern("EEEE d", EN)) else "el ${due.format(DateTimeFormatter.ofPattern("EEEE d", ES))}"
            else -> if (en) due.format(DateTimeFormatter.ofPattern("MMMM d", EN)) else "el ${due.format(DateTimeFormatter.ofPattern("d 'de' MMMM", ES))}"
        }
        return if (hasTime) "$day ${if (en) "at" else "a las"} ${due.format(TIME)}" else day
    }

    /** True once it has passed (with a time: that instant; without: the whole day). */
    fun isOverdue(dueAt: Long, hasTime: Boolean, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val due = LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAt), zone)
        return if (hasTime) due.isBefore(now) else due.toLocalDate().isBefore(now.toLocalDate())
    }

    fun greeting(now: LocalDateTime, lang: Lang = ReplyLanguage.current): String = when (now.hour) {
        in 5..11 -> if (lang == Lang.EN) "Good morning" else "Buenos días"
        in 12..19 -> if (lang == Lang.EN) "Good afternoon" else "Buenas tardes"
        else -> if (lang == Lang.EN) "Good evening" else "Buenas noches"
    }

    fun weekdayName(now: LocalDateTime, lang: Lang = ReplyLanguage.current): String =
        now.format(DateTimeFormatter.ofPattern("EEEE", if (lang == Lang.EN) EN else ES))
}
