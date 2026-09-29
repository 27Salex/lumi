package io.github.salex27.lumi.domain.reminder

import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskReminder

/**
 * Schedules/cancels a task's reminders (several per task). Implemented with AlarmManager in `service.reminder`.
 * AUTO reminders are recomputed by Lumi on every change ([ReminderPlanner]); CUSTOM ones are requested by the user.
 */
interface ReminderScheduler {
    /** Recomputes and schedules all of the task's reminders; cancels them if the task is no longer active. */
    suspend fun schedule(task: Task)
    suspend fun cancel(taskId: Long)
    suspend fun remindersFor(taskId: Long): List<TaskReminder>
    /** Relative custom reminder: [offsetMinutes] before the due time. */
    suspend fun addCustom(task: Task, offsetMinutes: Int)
    suspend fun remove(reminderId: Long, task: Task)
    /** One-off reminder in [minutes] without changing the task's date ("+1 h", "remind me in 20 min"). */
    suspend fun snooze(taskId: Long, minutes: Int)
}
