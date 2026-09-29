package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.DayMode
import io.github.salex27.lumi.domain.ai.DayPlanResult
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Decides which tasks to suggest from the weekday and the time ("what should I do today?").
 * Pure Kotlin: the computation is deterministic and shared by every engine; the LLM only phrases it.
 */
object DayPlanner {

    data class WorkSchedule(val startHour: Int = 9, val endHour: Int = 18)

    fun modeFor(now: LocalDateTime, schedule: WorkSchedule = WorkSchedule()): DayMode = when {
        now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY -> DayMode.WEEKEND
        now.hour in schedule.startHour until schedule.endHour -> DayMode.WORK_HOURS
        else -> DayMode.AFTER_WORK
    }

    fun plan(
        tasks: List<Task>,
        now: LocalDateTime,
        schedule: WorkSchedule = WorkSchedule(),
        zone: ZoneId = ZoneId.systemDefault(),
        maxSuggestions: Int = 3
    ): DayPlanResult {
        val mode = modeFor(now, schedule)
        val today = now.toLocalDate()
        val active = tasks.filter { it.isActive }

        fun Task.dueDateTime(): LocalDateTime? = dueAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        fun Task.dueDate(): LocalDate? = dueDateTime()?.toLocalDate()

        val overdue = active.filter { t ->
            val due = t.dueDateTime() ?: return@filter false
            if (t.dueHasTime) due.isBefore(now) else due.toLocalDate().isBefore(today)
        }.sortedWith(compareBy({ -it.priority.rank }, { it.dueAt }))

        val dueToday = active.filter { it !in overdue && it.dueDate() == today }
            .sortedWith(compareBy({ -it.priority.rank }, { it.dueAt }))

        val urgentIds = (overdue + dueToday).map { it.id }.toSet()
        val soonLimit = today.plusDays(1)

        // At the weekend / outside work hours work is parked, unless it is due tomorrow at the latest
        val (workParked, candidates) = active
            .filter { it.id !in urgentIds }
            .partition { t ->
                t.category == TaskCategory.WORK && mode != DayMode.WORK_HOURS &&
                    (t.dueDate()?.isAfter(soonLimit) ?: true)
            }

        val preference: List<TaskCategory> = when (mode) {
            DayMode.WORK_HOURS -> listOf(TaskCategory.WORK, TaskCategory.STUDY, TaskCategory.OTHER, TaskCategory.PERSONAL, TaskCategory.HEALTH)
            DayMode.AFTER_WORK -> listOf(TaskCategory.HEALTH, TaskCategory.PERSONAL, TaskCategory.STUDY, TaskCategory.OTHER, TaskCategory.WORK)
            DayMode.WEEKEND -> listOf(TaskCategory.PERSONAL, TaskCategory.HEALTH, TaskCategory.STUDY, TaskCategory.OTHER, TaskCategory.WORK)
        }

        val suggestions = candidates.sortedWith(
            compareBy<Task>(
                { if (it.priority == TaskPriority.HIGH) 0 else 1 },                           // urgent first
                { if (it.status == TaskStatus.IN_PROGRESS) 0 else 1 },                       // finish what was started
                { t -> if (t.dueDate()?.let { !it.isAfter(today.plusDays(3)) } == true) 0 else 1 }, // due soon
                { preference.indexOf(it.category) },                                           // fits the moment
                { -it.priority.rank },
                { it.dueAt ?: Long.MAX_VALUE },
                { -it.createdAt }
            )
        ).take(maxSuggestions)

        return DayPlanResult(
            mode = mode,
            overdue = overdue,
            dueToday = dueToday,
            suggestions = suggestions,
            postponedWork = workParked.size
        )
    }
}
