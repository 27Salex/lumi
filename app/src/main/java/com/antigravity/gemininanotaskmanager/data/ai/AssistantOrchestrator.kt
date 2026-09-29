package com.antigravity.gemininanotaskmanager.data.ai

import android.util.Log
import com.antigravity.gemininanotaskmanager.domain.ai.AssistantEngine
import com.antigravity.gemininanotaskmanager.domain.ai.ReplyRequest
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime

/**
 * Cadena de motores: se usa el primero disponible y, si falla o tarda demasiado, el siguiente.
 * Orden: Gemini Nano (AICore) → Gemma on-device → Gemini cloud (opcional) → reglas (siempre).
 */
class AssistantOrchestrator(
    /** Motor + timeout en ms. `null` = sin límite: solo se pasa al siguiente si falla o no devuelve nada. */
    private val llmEngines: List<Pair<AssistantEngine, Long?>>,
    private val rules: RuleBasedEngine,
    /** Recuerdos relacionados con una frase (memoria bajo demanda; nunca la memoria entera). */
    private val memoryFor: suspend (String) -> List<String> = { emptyList() }
) {
    data class Interpretation(val command: TaskAICommand, val engineName: String)

    val rulesName: String get() = rules.displayName

    /** Interpretación de reglas forzada como CREATE (p.ej. «pasa por el banco» cuando no hay tarea que mover). */
    fun rulesCreate(prompt: String, now: LocalDateTime): TaskAICommand = rules.parseCreate(prompt, now)

    private val _activeEngine = MutableStateFlow(rules.displayName)
    /** Nombre del motor que respondería ahora mismo (para mostrarlo en la UI). */
    val activeEngine: StateFlow<String> = _activeEngine.asStateFlow()

    suspend fun refreshActiveEngine() {
        _activeEngine.value = llmEngines.firstOrNull { (engine, _) -> runCatching { engine.isAvailable() }.getOrDefault(false) }
            ?.first?.displayName ?: rules.displayName
    }

    suspend fun interpret(prompt: String, now: LocalDateTime): Interpretation {
        val ruleCommand = rules.parse(prompt, now)
        // Órdenes muy claras (móvil, memoria, editar, rutas): las reglas aciertan siempre y no hace falta esperar al LLM
        if (ruleCommand.action in RULES_FIRST) {
            Log.i("LumiInterpret", "«$prompt» · reglas directas · ${ruleCommand.action}/${ruleCommand.targetTitle}")
            _activeEngine.value = rules.displayName
            return Interpretation(ruleCommand, rules.displayName)
        }
        // Memoria bajo demanda: al LLM solo le llegan los 2-3 recuerdos que tienen que ver con la frase
        val context = runCatching { memoryFor(prompt) }.getOrDefault(emptyList())
        val llmPrompt = if (context.isEmpty()) prompt
        else "$prompt\n[CONTEXTO PERSONAL — úsalo solo para entender la frase, no lo copies: ${context.joinToString("; ")}]"
        for ((engine, timeout) in llmEngines) {
            if (!runCatching { engine.isAvailable() }.getOrDefault(false)) continue
            val started = System.currentTimeMillis()
            val cmd = withTimeoutOrNullLogged(engine, timeout) { engine.interpret(llmPrompt, now) } ?: continue
            _activeEngine.value = engine.displayName
            val final = reconcile(cmd, ruleCommand, prompt)
            // Diagnóstico (adb logcat -s LumiInterpret): qué decidió el LLM, qué decían las reglas y qué se usa
            Log.i("LumiInterpret", "«$prompt» · ${engine.displayName} ${System.currentTimeMillis() - started} ms · " +
                "LLM=${cmd.action}/${cmd.targetTitle} · reglas=${ruleCommand.action}/${ruleCommand.targetTitle} · final=${final.action}/${final.targetTitle}")
            return Interpretation(final, engine.displayName)
        }
        _activeEngine.value = rules.displayName
        return Interpretation(ruleCommand, rules.displayName)
    }

    /** Solo reglas (instantáneo): lo usan los pasos de las rutinas, que son frases cortas y claras. */
    fun rulesInterpret(prompt: String, now: LocalDateTime): TaskAICommand = rules.parse(prompt, now)

    /**
     * Pregunta general. Con [web] se prueba antes un motor con búsqueda en Google (Gemini online), para datos
     * actuales; si no hay, la cadena normal. Devuelve (texto, motor) o null si solo hay reglas.
     */
    suspend fun answer(system: String, user: String, web: Boolean, maxTokens: Int = 350): Pair<String, String>? {
        if (web) for ((engine, timeout) in llmEngines) {
            if (!runCatching { engine.isAvailable() }.getOrDefault(false)) continue
            val text = withTimeoutOrNullLogged(engine, timeout) { engine.askWeb(system, user, maxTokens) } ?: continue
            return text to "${engine.displayName} + Google"
        }
        return ask(system, user, maxTokens)
    }

    /** Tarea libre para el primer LLM disponible (null si solo hay reglas). */
    suspend fun ask(system: String, user: String, maxTokens: Int = 200): Pair<String, String>? {
        for ((engine, timeout) in llmEngines) {
            if (!runCatching { engine.isAvailable() }.getOrDefault(false)) continue
            val text = withTimeoutOrNullLogged(engine, timeout) { engine.ask(system, user, maxTokens) } ?: continue
            return text to engine.displayName
        }
        return null
    }

    suspend fun reply(request: ReplyRequest): Pair<String, String> {
        for ((engine, timeout) in llmEngines) {
            if (!runCatching { engine.isAvailable() }.getOrDefault(false)) continue
            val text = withTimeoutOrNullLogged(engine, timeout) { engine.writeReply(request) } ?: continue
            return text to engine.displayName
        }
        return rules.writeReply(request) to rules.displayName
    }

    /**
     * El LLM entiende mejor la intención; las reglas son más fiables con fechas y categorías.
     * Se combinan: si el LLM omite algo que las reglas sí detectaron, se completa.
     */
    private fun reconcile(llm: TaskAICommand, rules: TaskAICommand, prompt: String): TaskAICommand {
        if (llm.action == TaskAICommand.CREATE_MANY) {
            // Cada elemento se completa con lo que las reglas vean en la frase completa (fecha compartida…)
            val items = llm.items.filter { !it.targetTitle.isNullOrBlank() }
            if (items.size < 2) return rules // el LLM no separó bien → reglas
            return llm.copy(items = items.mapIndexed { i, item ->
                reconcile(item.copy(action = TaskAICommand.CREATE), rules.items.getOrNull(i) ?: rules, prompt)
            })
        }
        // Si el LLM no vio un cambio de prioridad que las reglas sí («pon X como urgente»), mandan las reglas
        if (rules.action == TaskAICommand.SET_PRIORITY && llm.action != TaskAICommand.SET_PRIORITY) return rules
        // Las rutas son frases muy claras: si las reglas la vieron, mandan (un LLM pequeño tiende a crear una tarea)
        if (rules.action == TaskAICommand.NAVIGATE) return rules
        // Una pregunta que el LLM convirtió en tarea (demasiado literal) → se responde
        if (rules.action == TaskAICommand.RECALL && llm.action == TaskAICommand.CREATE) return rules
        if ((llm.action == TaskAICommand.ASK || llm.action == TaskAICommand.WEATHER) && llm.targetTitle.isNullOrBlank()) return llm.copy(targetTitle = prompt)
        // La memoria solo se toca si el usuario lo pide: Gemma guardó «he dejado una natilla en la nevera» cuando era
        // una pregunta («¿me la puedo comer?»)
        if ((llm.action == TaskAICommand.REMEMBER || llm.action == TaskAICommand.FORGET) && rules.action != llm.action &&
            !AssistantIntents.asksToRemember(prompt) && !Regex("(?iu)\\bolv[ií]da").containsMatchIn(prompt)) {
            return if (AssistantIntents.looksLikeQuestion(prompt) || rules.action == TaskAICommand.RECALL || rules.action == TaskAICommand.ASK)
                TaskAICommand(action = TaskAICommand.ASK, targetTitle = prompt) else rules
        }
        // Pregunta clara que el LLM convirtió en tarea → se responde
        if (llm.action == TaskAICommand.CREATE && rules.action == TaskAICommand.ASK) return rules
        if (llm.action == TaskAICommand.DEVICE && llm.device.isNullOrBlank()) return rules
        if (llm.action == TaskAICommand.EDIT && llm.targetTitle.isNullOrBlank()) return rules
        if (llm.action == TaskAICommand.RECALL && llm.targetTitle.isNullOrBlank()) return llm.copy(targetTitle = prompt)
        if (llm.action == TaskAICommand.NAVIGATE && llm.targetTitle.isNullOrBlank()) return rules
        if (llm.action == TaskAICommand.SET_PRIORITY) {
            return if (TaskPriority.fromString(llm.priority) == null) llm.copy(priority = rules.priority ?: "HIGH") else llm
        }
        if (llm.action != TaskAICommand.CREATE) return llm
        val llmDateValid = llm.dueDate?.let { runCatching { parseIso(it) }.isSuccess } == true
        return llm.copy(
            // Título «sin limpiar» (se copió la frase: «una tarea llamada … para cuando vuelva a casa») → el de las reglas
            targetTitle = llm.targetTitle?.let(TaskPhraseParser::cleanTitle)?.takeIf { it.isNotBlank() && !looksUncleaned(it) } ?: rules.targetTitle,
            // La descripción del LLM solo vale si sale literalmente de lo que dijo el usuario (no inventada)
            description = llm.description?.takeIf { isQuotedFrom(it, prompt) } ?: rules.description,
            category = llm.category ?: rules.category,
            // Fechas: manda el cálculo de las reglas (exacto). Gemma se equivoca con los días de la semana
            // («el viernes» → domingo 4); su fecha solo se usa si las reglas no vieron ninguna
            dueDate = rules.dueDate ?: llm.dueDate.takeIf { llmDateValid },
            hasTime = if (rules.dueDate != null) rules.hasTime else llmDateValid && llm.hasTime,
            recurrence = llm.recurrence?.takeIf { com.antigravity.gemininanotaskmanager.domain.model.Recurrence.parse(it) != null } ?: rules.recurrence,
            remindBeforeMinutes = (llm.remindBeforeMinutes + rules.remindBeforeMinutes).distinct().filter { it in 0..(60 * 24 * 30) },
            meeting = llm.meeting ?: rules.meeting,
            priority = llm.priority?.takeIf { TaskPriority.fromString(it) != null && it.uppercase() != "NONE" } ?: rules.priority,
            // El lugar lo detectan mejor las reglas (normalizan «oficina» → «trabajo»)
            place = rules.place ?: llm.place?.takeIf { it.isNotBlank() && it != "null" }?.let { TaskPhraseParser.normalizePlace(it) },
            placeOnArrive = if (rules.place != null) rules.placeOnArrive else llm.placeOnArrive
        )
    }

    private fun looksUncleaned(title: String) = Regex(
        "(?iu)\\btarea\\s+(?:llamada|que\\s+se\\s+llame)|\\b(?:cuando|en\\s+cuanto)\\s+(?:llegue|vuelva|salga|regrese)\\b|^recu[eé]rdame\\b|\\bav[ií]same\\b|^(?:crea|apunta|a[ñn]ade)\\s+"
    ).containsMatchIn(title)

    /** true si al menos el 80 % de las palabras de [text] aparecen en [source]. */
    private fun isQuotedFrom(text: String, source: String): Boolean {
        val words = CategoryHeuristics.normalize(text).split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return false
        val src = CategoryHeuristics.normalize(source)
        return words.count { src.contains(it) } >= (words.size * 0.8)
    }

    private suspend fun <T> withTimeoutOrNullLogged(engine: AssistantEngine, timeout: Long?, block: suspend () -> T?): T? =
        try {
            if (timeout == null) block() else withTimeout(timeout) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "${engine.displayName} superó ${timeout} ms, se usa el siguiente motor")
            null
        }

    companion object {
        private const val TAG = "AssistantOrchestrator"
        private val RULES_FIRST = setOf(
            TaskAICommand.DEVICE, TaskAICommand.REMEMBER, TaskAICommand.FORGET, TaskAICommand.NAVIGATE, TaskAICommand.EDIT,
            TaskAICommand.WEATHER, TaskAICommand.DAY_BRIEF, TaskAICommand.SMART_ALARM, TaskAICommand.NOTIFICATIONS, TaskAICommand.ASK
        )

        /** Acepta "2026-10-03T17:00" o "2026-10-03" (→ 09:00 sin hora). */
        fun parseIso(value: String): LocalDateTime =
            if (value.contains('T')) LocalDateTime.parse(value.trim())
            else java.time.LocalDate.parse(value.trim()).atTime(9, 0)
    }
}
