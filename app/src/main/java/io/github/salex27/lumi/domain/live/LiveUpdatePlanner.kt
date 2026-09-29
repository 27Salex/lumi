package io.github.salex27.lumi.domain.live

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task

/**
 * Qué enseñar en la actualización en directo (pantalla de bloqueo / Now Bar) y cuándo recalcularlo.
 * Puro y testeado. Solo cosas con hora: una tarea con hora o una reunión del calendario que empiece en
 * las próximas [LOOKAHEAD_MS] (o esté en curso). Gana la más cercana; a igualdad, la reunión.
 */
object LiveUpdatePlanner {

    data class Item(
        val kind: Kind,
        /** Id de la tarea o del evento. */
        val id: Long,
        val title: String,
        val start: Long,
        /** Hasta cuándo se muestra: fin de la reunión o unos minutos después de la hora de la tarea. */
        val until: Long,
        /** Dirección de la reunión (para «Cómo llegar»). */
        val location: String = ""
    ) {
        enum class Kind { TASK, MEETING }
        /** Clave estable para «Ocultar». */
        val key: String get() = "$kind:$id"
        fun isOngoing(now: Long) = now >= start
    }

    data class Result(val item: Item?, val nextRefreshAt: Long)

    const val LOOKAHEAD_MS = 2 * 60 * 60_000L
    /** Una tarea con hora sigue visible este rato tras su hora (por si aún no la has hecho). */
    const val TASK_GRACE_MS = 15 * 60_000L
    /** Sin nada que mostrar se revisa cada hora (reuniones añadidas desde otra app). */
    const val IDLE_REFRESH_MS = 60 * 60_000L
    /** Mientras hay algo en pantalla se refresca la barra de progreso cada 5 min (la cuenta atrás la lleva el sistema). */
    const val ACTIVE_REFRESH_MS = 5 * 60_000L

    /** Progreso 0..100: de «entra en la ventana» (2 h antes) a la hora; en curso, del inicio al final. */
    fun progress(item: Item, now: Long): Int {
        val (from, to) = if (item.isOngoing(now)) item.start to item.until else (item.start - LOOKAHEAD_MS) to item.start
        if (to <= from) return 100
        return (((now - from).toDouble() / (to - from)) * 100).toInt().coerceIn(0, 100)
    }

    fun plan(
        tasks: List<Task>,
        events: List<AgendaEvent>,
        now: Long,
        excludedEventIds: Set<Long> = emptySet(),
        /** Elemento que el usuario ocultó desde la notificación. */
        hiddenKey: String? = null
    ): Result {
        val taskItems = tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null }
            .map { Item(Item.Kind.TASK, it.id, it.title, it.dueAt!!, it.dueAt + TASK_GRACE_MS) }
        val eventItems = events.filter { !it.allDay && it.id !in excludedEventIds }
            .map { Item(Item.Kind.MEETING, it.id, it.title, it.begin, maxOf(it.end, it.begin + TASK_GRACE_MS), it.location) }
        val all = taskItems + eventItems

        val visible = all.filter { now < it.until && it.start <= now + LOOKAHEAD_MS && it.key != hiddenKey }
            .sortedWith(compareBy<Item>({ it.start }, { if (it.kind == Item.Kind.MEETING) 0 else 1 }))
        val chosen = visible.firstOrNull()

        // Próximo cambio: empieza lo mostrado (pasa a «Ahora»), termina, o entra algo nuevo en la ventana
        val candidates = buildList {
            chosen?.let { add(it.start); add(it.until); add(now + ACTIVE_REFRESH_MS) }
            all.filter { it.start > now + LOOKAHEAD_MS }.minOfOrNull { it.start - LOOKAHEAD_MS }?.let { add(it) }
            add(now + IDLE_REFRESH_MS)
        }.filter { it > now }
        return Result(chosen, candidates.min())
    }

    /** «en 25 min», «en 1 h 10 min», «ahora». */
    fun countdown(start: Long, now: Long): String {
        val minutes = ((start - now + 59_999) / 60_000).toInt()
        return when {
            minutes <= 0 -> "ahora"
            minutes < 60 -> "en $minutes min"
            minutes % 60 == 0 -> "en ${minutes / 60} h"
            else -> "en ${minutes / 60} h ${minutes % 60} min"
        }
    }
}
