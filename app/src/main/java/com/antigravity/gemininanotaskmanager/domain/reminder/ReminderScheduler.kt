package com.antigravity.gemininanotaskmanager.domain.reminder

import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskReminder

/**
 * Programa/cancela los avisos (varios por tarea) de una tarea. Implementado con AlarmManager en `service.reminder`.
 * Los avisos AUTO los recalcula Lumi en cada cambio ([ReminderPlanner]); los CUSTOM los pide el usuario.
 */
interface ReminderScheduler {
    /** Recalcula y programa todos los avisos de la tarea; si ya no está activa, los cancela. */
    suspend fun schedule(task: Task)
    suspend fun cancel(taskId: Long)
    suspend fun remindersFor(taskId: Long): List<TaskReminder>
    /** Aviso personalizado relativo: [offsetMinutes] antes del vencimiento. */
    suspend fun addCustom(task: Task, offsetMinutes: Int)
    suspend fun remove(reminderId: Long, task: Task)
    /** Aviso puntual dentro de [minutes] sin cambiar la fecha de la tarea («+1 h», «avísame en 20 min»). */
    suspend fun snooze(taskId: Long, minutes: Int)
}
