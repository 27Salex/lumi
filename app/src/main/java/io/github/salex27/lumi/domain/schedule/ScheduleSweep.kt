package io.github.salex27.lumi.domain.schedule

import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reminders and schedule blocks are not checked off by hand: once their moment is over they close themselves
 * (which also creates the next occurrence of a recurring one). Pure, tested.
 */
object ScheduleSweep {
    private const val MINUTE = 60_000L

    /** True when [task] is an active reminder or block whose time is over at [now]. To-dos never finish by themselves. */
    fun isOver(task: Task, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!task.isActive || task.kind == TaskKind.TASK) return false
        val due = task.dueAt ?: return false
        return if (task.dueHasTime) {
            val end = task.endAt ?: due
            // A reminder stays visible a few minutes after it rang
            end + (if (task.kind == TaskKind.REMINDER) 5 * MINUTE else 0) <= now
        } else {
            Instant.ofEpochMilli(due).atZone(zone).toLocalDate().isBefore(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        }
    }

    fun overdue(tasks: List<Task>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Task> =
        tasks.filter { isOver(it, now, zone) }

    /** Today's date in [zone] (helper for callers without a clock of their own). */
    fun today(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
