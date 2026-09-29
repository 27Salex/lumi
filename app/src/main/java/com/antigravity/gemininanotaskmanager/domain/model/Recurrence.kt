package com.antigravity.gemininanotaskmanager.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Regla de repetición de una tarea. Se guarda como texto compacto en Room:
 * "DAILY", "WEEKDAYS", "WEEKLY:MO,TH", "MONTHLY:1", "YEARLY".
 * Al completar una tarea recurrente se crea la siguiente con [next].
 */
data class Recurrence(
    val frequency: Frequency,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null
) {
    enum class Frequency { DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

    /** Próxima fecha estrictamente posterior a [after]. */
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
            // Día 31 en meses cortos → último día del mes
            fun inMonth(anyDay: LocalDate) = anyDay.withDayOfMonth(dom.coerceAtMost(anyDay.lengthOfMonth()))
            val thisMonth = inMonth(after)
            if (thisMonth.isAfter(after)) thisMonth else inMonth(after.withDayOfMonth(1).plusMonths(1))
        }
        Frequency.YEARLY -> after.plusYears(1)
    }

    /** Primera fecha válida a partir de [from] (inclusive), para tareas nuevas sin fecha explícita. */
    fun firstOnOrAfter(from: LocalDate): LocalDate = next(from.minusDays(1))

    fun serialize(): String = when (frequency) {
        Frequency.WEEKLY -> "WEEKLY:" + daysOfWeek.sorted().joinToString(",") { it.name.take(2) }
        Frequency.MONTHLY -> "MONTHLY:${dayOfMonth ?: 1}"
        else -> frequency.name
    }

    /** Texto para la UI: "Cada día", "Cada lunes y jueves", "Cada mes, el día 1"… */
    fun label(): String = when (frequency) {
        Frequency.DAILY -> "Cada día"
        Frequency.WEEKDAYS -> "Días laborables"
        Frequency.WEEKLY -> {
            val names = daysOfWeek.sorted().map { it.getDisplayName(TextStyle.FULL, ES) }
            "Cada " + when (names.size) {
                0 -> "semana"
                1 -> names[0]
                else -> names.dropLast(1).joinToString(", ") + " y " + names.last()
            }
        }
        Frequency.MONTHLY -> "Cada mes, el día ${dayOfMonth ?: 1}"
        Frequency.YEARLY -> "Cada año"
    }

    companion object {
        private val ES = Locale.forLanguageTag("es-ES")

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

/** Reunión del calendario a la que está vinculada una tarea (p.ej. «preparar slides» → «Reunión del sprint»). */
data class LinkedMeeting(val eventId: Long, val title: String, val start: Long)

/** Aviso de una tarea. AUTO = los decide Lumi (se recalculan solos); CUSTOM = los pide el usuario. */
data class TaskReminder(
    val id: Long = 0,
    val taskId: Long,
    val triggerAt: Long,
    val kind: Kind,
    /** Para CUSTOM relativos: minutos antes del vencimiento (se recalcula si cambia la fecha). */
    val offsetMinutes: Int? = null,
    /** Texto de la notificación: "Mañana", "En 1 hora", "La reunión empieza en 15 min"… */
    val label: String
) {
    enum class Kind { AUTO, CUSTOM }
}
