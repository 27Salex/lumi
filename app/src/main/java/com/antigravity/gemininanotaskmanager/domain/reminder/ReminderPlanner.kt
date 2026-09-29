package com.antigravity.gemininanotaskmanager.domain.reminder

import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Política de avisos automáticos de Lumi (pura, testeada). Decide CUÁNTOS avisos y CUÁNDO según el
 * tipo de tarea, para avisar lo suficiente sin agobiar:
 *
 * Cita con hora (p.ej. dentista 17:00):
 *  - la víspera a las 20:00 (solo si falta más de ~20 h y es trabajo/salud/estudios)
 *  - [leadMinutes] antes (ajuste del usuario, 30 min por defecto)
 *  - 10 min antes (solo si el anterior es ≥ 30 min)
 * Fecha límite sin hora (p.ej. «entregar informe el viernes»):
 *  - 2 días antes a las [morningHour] (solo si faltan ≥ 2 días)
 *  - ese día a las [morningHour]
 *  - ese día a las 18:00 («¡hoy vence!»)
 * Tarea vinculada a una reunión: 15 min antes de la reunión («empieza en 15 min, te queda X»).
 * Prioridad alta: la víspera a las 20:00 siempre (también sin hora y sea de la categoría que sea).
 * Avisos personalizados: minutos antes del vencimiento que pidió el usuario.
 */
object ReminderPlanner {

    data class Planned(val triggerAt: Long, val label: String)

    fun plan(
        task: Task,
        nowMillis: Long,
        leadMinutes: Int = 30,
        morningHour: Int = 9,
        customOffsets: List<Int> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Planned> {
        if (!task.isActive) return emptyList()
        val out = mutableListOf<Planned>()
        val due = task.dueAt

        if (task.autoReminders && due != null) {
            val dueDate = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            fun at(date: java.time.LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

            if (task.dueHasTime) {
                val important = task.priority == TaskPriority.HIGH ||
                    task.category in setOf(TaskCategory.WORK, TaskCategory.HEALTH, TaskCategory.STUDY)
                val eve = at(dueDate.minusDays(1), LocalTime.of(20, 0))
                if (important && due - nowMillis > 20 * HOUR) out += Planned(eve, "Mañana")
                out += Planned(due - leadMinutes * MINUTE, "En ${humanMinutes(leadMinutes)}")
                if (leadMinutes >= 30) out += Planned(due - 10 * MINUTE, "En 10 min")
            } else {
                val morning = LocalTime.of(morningHour, 0)
                if (task.priority == TaskPriority.HIGH && due - nowMillis > DAY) {
                    out += Planned(at(dueDate.minusDays(1), LocalTime.of(20, 0)), "Mañana vence (prioridad alta)")
                }
                if (due - nowMillis > 2 * DAY) out += Planned(at(dueDate.minusDays(2), morning), "Vence pasado mañana")
                out += Planned(at(dueDate, morning), "Vence hoy")
                out += Planned(at(dueDate, LocalTime.of(18, 0)), "¡Hoy vence!")
            }
        }

        task.meeting?.let { m ->
            out += Planned(m.start - 15 * MINUTE, "«${m.title}» empieza en 15 min")
        }

        if (due != null) customOffsets.distinct().forEach { off ->
            out += Planned(due - off * MINUTE, if (off == 0) "Ahora" else "En ${humanMinutes(off)}")
        }

        // Solo futuros, sin duplicados cercanos (< 2 min), en orden
        return out.filter { it.triggerAt > nowMillis }
            .sortedBy { it.triggerAt }
            .fold(mutableListOf()) { acc, p -> if (acc.none { kotlin.math.abs(it.triggerAt - p.triggerAt) < 2 * MINUTE }) acc += p; acc }
    }

    fun humanMinutes(m: Int): String = when {
        m < 60 -> "$m min"
        m % (60 * 24) == 0 -> (m / (60 * 24)).let { if (it == 1) "1 día" else "$it días" }
        m % 60 == 0 -> (m / 60).let { if (it == 1) "1 hora" else "$it horas" }
        else -> "${m / 60} h ${m % 60} min"
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
