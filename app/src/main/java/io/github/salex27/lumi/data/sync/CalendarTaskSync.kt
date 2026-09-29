package io.github.salex27.lumi.data.sync

import io.github.salex27.lumi.data.local.TaskDao
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.domain.repository.TaskChangeListener

/**
 * Mantiene un evento en el calendario elegido por cada tarea ACTIVA con hora (citas).
 * Si la tarea se completa, pierde la hora o se borra, el evento se elimina.
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

    /** Al activar la sincronización: crear eventos para las citas ya existentes. */
    suspend fun syncAll() {
        dao.getActiveTasksWithDue().filter { it.dueHasTime }.forEach { onTaskSaved(it.id) }
    }
}
