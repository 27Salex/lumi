package com.antigravity.gemininanotaskmanager.domain.assistant

import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Lumi proactiva (puro, testeado): detecta huecos libres y redacta el repaso del día.
 * Los LLM no calculan nada de esto; como mucho lo redactan.
 */
object FreeTimeFinder {

    /** Hueco libre desde [start] hasta [end] con la tarea que Lumi propone adelantar. */
    data class FreeSlot(val start: Long, val end: Long, val suggestion: Task?, val alternatives: List<Task>) {
        val minutes: Int get() = ((end - start) / 60_000).toInt()
    }

    const val MIN_MINUTES = 25
    /** Horas «despiertas»: no se proponen huecos de madrugada. */
    const val DAY_START_HOUR = 8
    const val DAY_END_HOUR = 22

    /**
     * El hueco que empieza AHORA (o el próximo hoy si ahora estás ocupado) hasta la siguiente reunión o tarea con hora.
     * Solo si dura al menos [MIN_MINUTES]. [candidates] son las tareas sugeridas por el plan del día, en orden.
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

        // Tramos ocupados: reuniones con hora y tareas con hora (30 min por defecto)
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
        // Para un hueco se proponen tareas sin hora fija (las que tienen hora ya tienen su momento)
        val flexible = candidates.filter { it.isActive && !it.dueHasTime }
        return FreeSlot(start, end, flexible.firstOrNull(), flexible.drop(1).take(3))
    }
}

object CheckInComposer {

    data class CheckIn(val title: String, val body: String, val openToday: List<Task>)

    /**
     * Repaso de la tarde: qué cerraste hoy y qué queda para hoy (incluidas vencidas). null si hoy no hubo nada
     * (ni hecho ni pendiente): no se molesta.
     */
    fun compose(tasks: List<Task>, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): CheckIn? {
        val today = now.toLocalDate()
        fun Long.date(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
        val done = tasks.count { it.status == TaskStatus.COMPLETED && it.completedAt?.date() == today }
        val open = tasks.filter { it.isActive && it.dueAt?.let { d -> !d.date().isAfter(today) } == true }
            .sortedWith(compareBy({ -it.priority.rank }, { it.dueAt }))
        if (done == 0 && open.isEmpty()) return null

        val title = when {
            open.isEmpty() -> "Día cerrado"
            done == 0 -> "Repaso del día"
            else -> "Hoy: $done ${if (done == 1) "hecha" else "hechas"}"
        }
        val body = when {
            open.isEmpty() -> "Has cerrado $done ${if (done == 1) "tarea" else "tareas"} y no queda nada para hoy. Buen trabajo."
            else -> {
                val names = open.take(3).joinToString(", ") { "«${it.title}»" } + if (open.size > 3) " y ${open.size - 3} más" else ""
                val urgent = if (open.any { it.priority == TaskPriority.HIGH }) " Hay algo urgente." else ""
                "Quedan ${open.size} para hoy: $names.$urgent ¿Lo pasamos a mañana o ya está hecho?"
            }
        }
        return CheckIn(title, body, open)
    }
}
