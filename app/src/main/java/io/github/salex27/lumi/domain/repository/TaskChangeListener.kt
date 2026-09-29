package io.github.salex27.lumi.domain.repository

/**
 * Se notifica tras cada cambio local de tareas (crear, editar, borrar).
 * Lo implementa la sincronización (calendario del móvil + Google Tasks) y el widget.
 */
interface TaskChangeListener {
    suspend fun onTaskSaved(taskId: Long) {}
    suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) {}
}
