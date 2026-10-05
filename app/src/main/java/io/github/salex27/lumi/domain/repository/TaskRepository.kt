package io.github.salex27.lumi.domain.repository

import io.github.salex27.lumi.domain.model.AIProcessingResult
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.TaskReminder
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    /** Memory of the active chat session (follow-ups and the LLM's conversation note); restored when a session resumes. */
    val conversation: io.github.salex27.lumi.domain.assistant.ConversationContext

    fun getAllTasks(): Flow<List<Task>>
    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>>
    fun getPendingCount(): Flow<Int>
    suspend fun getTask(id: Long): Task?
    suspend fun insertTask(task: Task): Long
    suspend fun updateTask(task: Task)
    suspend fun deleteTask(task: Task)
    suspend fun deleteTaskById(id: Long)

    /**
     * @param defaultCategory category to use when neither the AI nor keywords decide one.
     * @param route forces the top-level route (the user answered "task or agent?"); null = the router decides.
     */
    suspend fun processNaturalLanguageCommand(
        prompt: String,
        defaultCategory: TaskCategory? = null,
        route: io.github.salex27.lumi.domain.assistant.IntentRoute? = null
    ): AIProcessingResult

    /** Runs an already interpreted command (e.g. after picking in "Did you mean…?", with targetId). */
    suspend fun executeCommand(
        command: io.github.salex27.lumi.domain.model.TaskAICommand,
        defaultCategory: TaskCategory? = null
    ): AIProcessingResult

    /** Conversational summary of the current situation (written by the active AI engine). */
    suspend fun generateDailyBriefing(): AIProcessingResult

    /** "What should I do now?" based on weekday, time of day and due dates. */
    suspend fun planMyDay(): AIProcessingResult

    /** "Good morning" / "what do I have tomorrow?": weather, first appointment, tasks and (for tomorrow) the suggested alarm. */
    suspend fun dayBrief(date: java.time.LocalDate): AIProcessingResult

    /** Smart alarm for [date] from calendar, tasks and work hours (null = nothing early that day). */
    suspend fun smartAlarmPlan(date: java.time.LocalDate): io.github.salex27.lumi.domain.assistant.AlarmPlanner.Plan?

    suspend fun rescheduleAllReminders()

    // Multiple reminders per task
    suspend fun remindersFor(taskId: Long): List<TaskReminder>
    suspend fun addCustomReminder(task: Task, offsetMinutes: Int)
    suspend fun removeReminder(reminderId: Long, task: Task)

    /** Meetings in the next [days] days (phone calendar), to link tasks to them. */
    suspend fun upcomingMeetings(days: Int = 14): List<AgendaEvent>

    /** Reply typed into a reminder notification; returns what Lumi did. */
    suspend fun applyQuickReply(taskId: Long, text: String): String
}
