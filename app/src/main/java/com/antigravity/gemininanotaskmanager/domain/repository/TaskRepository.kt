package com.antigravity.gemininanotaskmanager.domain.repository

import com.antigravity.gemininanotaskmanager.domain.model.AIProcessingResult
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.TaskReminder
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getAllTasks(): Flow<List<Task>>
    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>>
    fun getPendingCount(): Flow<Int>
    suspend fun getTask(id: Long): Task?
    suspend fun insertTask(task: Task): Long
    suspend fun updateTask(task: Task)
    suspend fun deleteTask(task: Task)
    suspend fun deleteTaskById(id: Long)

    /** @param defaultCategory categoría a usar si ni la IA ni las palabras clave la determinan. */
    suspend fun processNaturalLanguageCommand(
        prompt: String,
        defaultCategory: TaskCategory? = null
    ): AIProcessingResult

    /** Ejecuta un comando ya interpretado (p.ej. tras elegir en «¿Te refieres a…?», con targetId). */
    suspend fun executeCommand(
        command: com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand,
        defaultCategory: TaskCategory? = null
    ): AIProcessingResult

    /** Resumen conversacional de la situación actual (lo redacta el motor de IA activo). */
    suspend fun generateDailyBriefing(): AIProcessingResult

    /** "¿Qué hago ahora?" según día de la semana, hora y fechas límite. */
    suspend fun planMyDay(): AIProcessingResult

    /** «Buenos días» / «¿qué tengo mañana?»: tiempo, primera cita, tareas y (para mañana) la alarma propuesta. */
    suspend fun dayBrief(date: java.time.LocalDate): AIProcessingResult

    /** Alarma inteligente para [date] según calendario, tareas y horario (null = no hay nada temprano). */
    suspend fun smartAlarmPlan(date: java.time.LocalDate): com.antigravity.gemininanotaskmanager.domain.assistant.AlarmPlanner.Plan?

    suspend fun rescheduleAllReminders()

    // Avisos múltiples
    suspend fun remindersFor(taskId: Long): List<TaskReminder>
    suspend fun addCustomReminder(task: Task, offsetMinutes: Int)
    suspend fun removeReminder(reminderId: Long, task: Task)

    /** Reuniones de los próximos [days] días (calendario del móvil) para vincular tareas. */
    suspend fun upcomingMeetings(days: Int = 14): List<AgendaEvent>

    /** Respuesta desde la notificación de un aviso; devuelve lo que hizo Lumi. */
    suspend fun applyQuickReply(taskId: Long, text: String): String
}
