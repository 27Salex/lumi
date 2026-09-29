package io.github.salex27.lumi.domain.repository

/**
 * Notified after every local task change (create, edit, delete).
 * Implemented by sync (phone calendar + Google Tasks), place reminders, the live chip and the widget.
 */
interface TaskChangeListener {
    suspend fun onTaskSaved(taskId: Long) {}
    suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) {}
}
