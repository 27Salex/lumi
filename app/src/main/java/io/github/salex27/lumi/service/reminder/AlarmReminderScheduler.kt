package io.github.salex27.lumi.service.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.salex27.lumi.data.local.ReminderDao
import io.github.salex27.lumi.data.local.ReminderEntity
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskReminder
import io.github.salex27.lumi.domain.reminder.ReminderPlanner
import io.github.salex27.lumi.domain.reminder.ReminderScheduler
import java.time.Instant

/**
 * Multiple reminders with AlarmManager: one alarm per `reminders` row (requestCode = reminder id).
 * Exact alarms if the user granted "Alarms & reminders"; otherwise inexact ones.
 */
class AlarmReminderScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val dao: ReminderDao
) : ReminderScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override suspend fun schedule(task: Task) {
        val existing = dao.forTask(task.id)
        existing.forEach { cancelAlarm(it.id) }
        dao.deleteAuto(task.id)
        if (!task.isActive) {
            existing.filter { it.kind == TaskReminder.Kind.CUSTOM.name }.forEach { dao.delete(it.id) }
            return
        }
        val now = System.currentTimeMillis()
        val s = settings.current

        // 1) Lumi's automatic ones
        ReminderPlanner.plan(task, now, s.reminderLeadMinutes, s.dateOnlyReminderHour).forEach { p ->
            dao.insert(ReminderEntity(taskId = task.id, triggerAt = p.triggerAt, kind = TaskReminder.Kind.AUTO.name, label = p.label))
        }
        // 2) Custom ones: relative ones follow the due date if it changes
        existing.filter { it.kind == TaskReminder.Kind.CUSTOM.name }.forEach { r ->
            val trigger = r.offsetMinutes?.let { off -> task.dueAt?.minus(off * 60_000L) } ?: r.triggerAt
            if (trigger != r.triggerAt) { dao.delete(r.id); dao.insert(r.copy(id = 0, triggerAt = trigger)) }
        }
        // 3) Schedule every future one
        dao.forTask(task.id).filter { it.triggerAt > now }.forEach { setAlarm(it) }
    }

    override suspend fun cancel(taskId: Long) {
        dao.forTask(taskId).forEach { cancelAlarm(it.id); dao.delete(it.id) }
    }

    override suspend fun remindersFor(taskId: Long): List<TaskReminder> = dao.forTask(taskId).map { it.toDomain() }

    override suspend fun addCustom(task: Task, offsetMinutes: Int) {
        val due = task.dueAt ?: return
        dao.insert(
            ReminderEntity(
                taskId = task.id, triggerAt = due - offsetMinutes * 60_000L, kind = TaskReminder.Kind.CUSTOM.name,
                offsetMinutes = offsetMinutes,
                label = if (offsetMinutes == 0) "Ahora" else "En ${ReminderPlanner.humanMinutes(offsetMinutes)}"
            )
        )
        schedule(task)
    }

    override suspend fun remove(reminderId: Long, task: Task) {
        cancelAlarm(reminderId)
        dao.delete(reminderId)
    }

    /** "+1 h" from the notification: a one-off reminder that doesn't change the task's date. */
    override suspend fun snooze(taskId: Long, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val id = dao.insert(ReminderEntity(taskId = taskId, triggerAt = at, kind = TaskReminder.Kind.CUSTOM.name, label = "Pospuesto"))
        dao.byId(id)?.let { setAlarm(it) }
    }

    /** After a reboot or a settings change: reschedule pending alarms. */
    suspend fun rescheduleAllAlarms() {
        dao.upcoming(System.currentTimeMillis()).forEach { setAlarm(it) }
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun setAlarm(r: ReminderEntity) {
        val pending = pendingIntent(r.id, r.taskId)
        if (canScheduleExact()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pending)
        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pending)
        Log.d(TAG, "Aviso ${r.id} (${r.label}) de la tarea ${r.taskId} → ${Instant.ofEpochMilli(r.triggerAt)}")
    }

    private fun cancelAlarm(reminderId: Long) = alarmManager.cancel(pendingIntent(reminderId, 0))

    private fun pendingIntent(reminderId: Long, taskId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminderId.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND)
            .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val TAG = "AlarmReminderScheduler"
    }
}
