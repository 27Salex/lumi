package io.github.salex27.lumi.domain.ai

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import java.time.LocalDateTime

/**
 * Contract for one of the assistant's "brains". Current implementations:
 * - `GeminiNanoEngine` (Gemini Nano through AICore; not available on every phone)
 * - `GemmaLocalEngine` (Gemma on-device through LiteRT-LM)
 * - `CloudGeminiEngine` (Gemini API, optional, user key)
 * - `RuleBasedEngine` (deterministic rules, always available)
 *
 * To add another provider (Claude, ML Kit GenAI, your own server…) implement this interface and register it in
 * `TaskManagerApplication`.
 */
interface AssistantEngine {
    val displayName: String

    /** True when the engine can answer right now (model downloaded, API key set…). */
    suspend fun isAvailable(): Boolean

    /** Turns natural language into an intent. Returns null when it can't → the next engine is tried. */
    suspend fun interpret(prompt: String, now: LocalDateTime): TaskAICommand?

    /** Writes the conversational reply. Returns null when it can't → the next engine is tried. */
    suspend fun writeReply(request: ReplyRequest): String?

    /** Short free-form task (e.g. extract recipient and text from a WhatsApp request). Null if the engine is not an LLM. */
    suspend fun ask(system: String, user: String, maxTokens: Int = 200): String? = null

    /** Like [ask] but with Google Search (current data). Null if the engine can't search. */
    suspend fun askWeb(system: String, user: String, maxTokens: Int = 350): String? = null
}

/** What the assistant has to say. The data is already computed; the engine only phrases it. */
sealed interface ReplyRequest {
    val now: LocalDateTime

    data class TaskCreated(val task: Task, override val now: LocalDateTime) : ReplyRequest
    data class TaskUpdated(val task: Task, override val now: LocalDateTime) : ReplyRequest
    data class TaskRescheduled(val task: Task, val previousDueAt: Long?, override val now: LocalDateTime) : ReplyRequest
    /** A question from the user: answer ONLY with [facts] (memory) and/or [task]. */
    data class Recall(val question: String, val facts: List<String>, val task: Task?, override val now: LocalDateTime) : ReplyRequest
    data class PriorityChanged(val task: Task, override val now: LocalDateTime) : ReplyRequest
    data class TasksCreated(val tasks: List<Task>, override val now: LocalDateTime) : ReplyRequest
    data class DayPlan(
        val plan: DayPlanResult,
        override val now: LocalDateTime,
        /** Today's events from the phone calendar (empty without permission). */
        val events: List<AgendaEvent> = emptyList(),
        /** Free slot starting now (if any) and what to get ahead with in it. */
        val freeSlot: io.github.salex27.lumi.domain.assistant.FreeTimeFinder.FreeSlot? = null
    ) : ReplyRequest
    data class Briefing(val tasks: List<Task>, override val now: LocalDateTime) : ReplyRequest
    /** Read unread messages aloud: [digest] is the rule-based summary (the LLM phrases it better). */
    data class Messages(val messages: List<io.github.salex27.lumi.domain.assistant.IncomingMessage>, val digest: String, override val now: LocalDateTime) : ReplyRequest
}

/** Moment of the day/week; decides which kind of tasks are suggested. */
enum class DayMode { WORK_HOURS, AFTER_WORK, WEEKEND }

data class DayPlanResult(
    val mode: DayMode,
    val overdue: List<Task>,
    val dueToday: List<Task>,
    /** Ordered suggestions (not repeating overdue/dueToday). */
    val suggestions: List<Task>,
    /** Work tasks deliberately left out (e.g. at the weekend). */
    val postponedWork: Int
)
