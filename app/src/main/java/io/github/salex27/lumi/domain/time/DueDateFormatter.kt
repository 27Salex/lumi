package io.github.salex27.lumi.domain.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Formatea fechas límite en español natural: "hoy a las 17:00", "mañana", "el viernes 3", "el 15 oct". */
object DueDateFormatter {

    private val ES = Locale.forLanguageTag("es-ES")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", ES)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE d", ES)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d 'de' MMMM", ES)

    fun format(
        dueAt: Long,
        hasTime: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val due = LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAt), zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), due.toLocalDate())
        val day = when {
            days == 0L -> "hoy"
            days == 1L -> "mañana"
            days == 2L -> "pasado mañana"
            days == -1L -> "ayer"
            days in 3..6 -> "el ${due.format(WEEKDAY)}"
            else -> "el ${due.format(DAY_MONTH)}"
        }
        return if (hasTime) "$day a las ${due.format(TIME)}" else day
    }

    /** true si ya pasó (con hora: el instante; sin hora: el día completo). */
    fun isOverdue(dueAt: Long, hasTime: Boolean, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val due = LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAt), zone)
        return if (hasTime) due.isBefore(now) else due.toLocalDate().isBefore(now.toLocalDate())
    }

    fun greeting(now: LocalDateTime): String = when (now.hour) {
        in 5..11 -> "Buenos días"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }

    fun weekdayName(now: LocalDateTime): String = now.format(DateTimeFormatter.ofPattern("EEEE", ES))
}
