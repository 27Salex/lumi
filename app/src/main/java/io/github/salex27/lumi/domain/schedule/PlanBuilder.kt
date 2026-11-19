package io.github.salex27.lumi.domain.schedule

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskKind
import io.github.salex27.lumi.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the Plan screen: one entry per day with its schedule blocks, reminders, tasks and calendar events, in time
 * order. Recurring items are projected onto the days they will repeat (only the next occurrence exists in the
 * database), marked [Entry.projected]. Pure, tested.
 */
object PlanBuilder {

    sealed interface Entry {
        /** Start in epoch millis, or null for an item of the whole day. */
        val start: Long?
        val end: Long?

        data class Item(
            val task: Task,
            override val start: Long?,
            override val end: Long?,
            /** A future repetition of a recurring item (not stored yet): shown dimmed, opens the real one. */
            val projected: Boolean = false
        ) : Entry

        data class Event(val event: AgendaEvent) : Entry {
            override val start: Long? get() = if (event.allDay) null else event.begin
            override val end: Long? get() = if (event.allDay) null else event.end
        }
    }

    data class Day(
        val date: LocalDate,
        /** Untimed first, then by start time. */
        val entries: List<Entry>,
        /** Minutes covered by schedule blocks (the "planned" time of the day). */
        val plannedMinutes: Int,
        val openTasks: Int,
        val doneTasks: Int
    )

    fun week(
        tasks: List<Task>,
        events: Map<LocalDate, List<AgendaEvent>>,
        from: LocalDate,
        days: Int = 7,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Day> {
        val to = from.plusDays(days.toLong() - 1)
        val byDay = mutableMapOf<LocalDate, MutableList<Entry>>()
        fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        for (t in tasks) {
            val due = t.dueAt ?: continue
            if (t.status == TaskStatus.CANCELLED) continue
            val day = dayOf(due)
            if (!day.isBefore(from) && !day.isAfter(to)) byDay.getOrPut(day) { mutableListOf() } += item(t, due)

            // Future repetitions inside the range (an active recurring item only exists once, at its next date)
            val rec = t.recurrence ?: continue
            if (!t.isActive) continue
            val time = Instant.ofEpochMilli(due).atZone(zone).toLocalTime()
            var next = rec.next(day)
            var guard = 0
            while (!next.isAfter(to) && guard++ < 400) {
                if (!next.isBefore(from)) {
                    val start = next.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    byDay.getOrPut(next) { mutableListOf() } += item(t, start, projected = true)
                }
                next = rec.next(next)
            }
        }
        events.forEach { (day, list) ->
            if (!day.isBefore(from) && !day.isAfter(to)) list.forEach { byDay.getOrPut(day) { mutableListOf() } += Entry.Event(it) }
        }

        return (0 until days).map { i ->
            val date = from.plusDays(i.toLong())
            val entries = byDay[date].orEmpty().sortedWith(compareBy({ it.start != null }, { it.start ?: 0L }, { title(it) }))
            val items = entries.filterIsInstance<Entry.Item>()
            Day(
                date = date,
                entries = entries,
                plannedMinutes = items.filter { it.task.kind == TaskKind.BLOCK }.sumOf { it.task.durationMinutes ?: 0 },
                openTasks = items.count { it.task.kind == TaskKind.TASK && it.task.isActive && !it.projected },
                doneTasks = items.count { it.task.kind == TaskKind.TASK && it.task.status == TaskStatus.COMPLETED }
            )
        }
    }

    /** Active to-dos whose date has already gone by (shown on top of today). */
    fun overdue(tasks: List<Task>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Task> =
        tasks.filter { t ->
            t.kind == TaskKind.TASK && t.isActive &&
                t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().isBefore(today) } == true
        }.sortedBy { it.dueAt }

    private fun item(t: Task, start: Long, projected: Boolean = false): Entry.Item {
        val timed = t.dueHasTime
        val end = t.durationMinutes?.takeIf { timed }?.let { start + it * 60_000L }
        return Entry.Item(t, if (timed) start else null, end, projected)
    }

    private fun title(e: Entry) = when (e) {
        is Entry.Item -> e.task.title
        is Entry.Event -> e.event.title
    }
}
