package io.github.salex27.lumi.data.sync

import io.github.salex27.lumi.data.local.TaskDao
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.domain.repository.TaskChangeListener

/**
 * Keeps one event in the chosen calendar for every ACTIVE timed task (appointments).
 * When the task is completed, loses its time or is deleted, the event is removed.
 */
class CalendarTaskSync(
    private val calendar: DeviceCalendar,
    private val dao: TaskDao,
    private val settings: SettingsRepository
) : TaskChangeListener {

    override suspend fun onTaskSaved(taskId: Long) {
        val s = settings.current
        if (!s.calendarSyncEnabled || s.calendarId < 0) return
        val entity = dao.getTaskById(taskId) ?: return
        val task = entity.toDomain()
        if (task.isActive && task.dueAt != null && task.dueHasTime) {
            val eventId = calendar.upsertTaskEvent(task, s.calendarId, entity.calendarEventId)
            if (eventId != entity.calendarEventId) dao.setCalendarEventId(taskId, eventId)
        } else if (entity.calendarEventId != null) {
            calendar.deleteEvent(entity.calendarEventId)
            dao.setCalendarEventId(taskId, null)
        }
    }

    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) {
        if (calendarEventId != null) calendar.deleteEvent(calendarEventId)
    }

    /** When sync is turned on: create events for the existing appointments. */
    suspend fun syncAll() {
        dao.getActiveTasksWithDue().filter { it.dueHasTime }.forEach { onTaskSaved(it.id) }
    }
}
