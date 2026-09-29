package com.antigravity.gemininanotaskmanager.domain.ai

import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import java.time.LocalDateTime

/**
 * Contrato de un "cerebro" del asistente. Implementaciones actuales:
 * - `GeminiNanoEngine` (LLM, puede no estar disponible)
 * - `RuleBasedEngine` (reglas deterministas, siempre disponible)
 *
 * Para añadir otro proveedor (Claude, ML Kit GenAI, un servidor propio...) basta con implementar
 * esta interfaz y registrarlo en `TaskManagerApplication`. Ver MEMORY.md.
 */
interface AssistantEngine {
    val displayName: String

    /** true si el motor puede responder ahora mismo (modelo descargado, API key configurada...). */
    suspend fun isAvailable(): Boolean

    /** Convierte lenguaje natural en una intención. Devuelve null si no puede → se prueba el siguiente motor. */
    suspend fun interpret(prompt: String, now: LocalDateTime): TaskAICommand?

    /** Redacta la respuesta conversacional. Devuelve null si no puede → se prueba el siguiente motor. */
    suspend fun writeReply(request: ReplyRequest): String?

    /** Tarea libre y corta (p.ej. extraer destinatario y texto de un WhatsApp). null si el motor no es un LLM. */
    suspend fun ask(system: String, user: String, maxTokens: Int = 200): String? = null

    /** Como [ask] pero con búsqueda en Google (datos actuales). null si el motor no puede buscar. */
    suspend fun askWeb(system: String, user: String, maxTokens: Int = 350): String? = null
}

/** Qué tiene que contar el asistente. Los datos ya están calculados; el motor solo redacta. */
sealed interface ReplyRequest {
    val now: LocalDateTime

    data class TaskCreated(val task: Task, override val now: LocalDateTime) : ReplyRequest
    data class TaskUpdated(val task: Task, override val now: LocalDateTime) : ReplyRequest
    data class TaskRescheduled(val task: Task, val previousDueAt: Long?, override val now: LocalDateTime) : ReplyRequest
    /** Pregunta del usuario: responder SOLO con [facts] (memoria) y/o [task]. */
    data class Recall(val question: String, val facts: List<String>, val task: Task?, override val now: LocalDateTime) : ReplyRequest
    data class PriorityChanged(val task: Task, override val now: LocalDateTime) : ReplyRequest
    data class TasksCreated(val tasks: List<Task>, override val now: LocalDateTime) : ReplyRequest
    data class DayPlan(
        val plan: DayPlanResult,
        override val now: LocalDateTime,
        /** Eventos de hoy del calendario del móvil (vacío si no hay permiso). */
        val events: List<AgendaEvent> = emptyList(),
        /** Hueco libre desde ahora (si lo hay) y qué adelantar en él. */
        val freeSlot: com.antigravity.gemininanotaskmanager.domain.assistant.FreeTimeFinder.FreeSlot? = null
    ) : ReplyRequest
    data class Briefing(val tasks: List<Task>, override val now: LocalDateTime) : ReplyRequest
    /** Leer los mensajes sin leer en voz alta: [digest] es el resumen ya hecho por reglas (el LLM lo redacta mejor). */
    data class Messages(val messages: List<com.antigravity.gemininanotaskmanager.domain.assistant.IncomingMessage>, val digest: String, override val now: LocalDateTime) : ReplyRequest
}

/** Momento del día/semana, determina qué tipo de tareas se sugieren. */
enum class DayMode { WORK_HOURS, AFTER_WORK, WEEKEND }

data class DayPlanResult(
    val mode: DayMode,
    val overdue: List<Task>,
    val dueToday: List<Task>,
    /** Sugerencias ordenadas (sin repetir las de overdue/dueToday). */
    val suggestions: List<Task>,
    /** Tareas de trabajo que se han dejado fuera a propósito (p.ej. en fin de semana). */
    val postponedWork: Int
)
