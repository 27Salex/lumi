package io.github.salex27.lumi.data.ai

import android.util.Log
import io.github.salex27.lumi.domain.ai.AssistantEngine
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskPriority
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime

/**
 * Engine chain: the first available engine is used and, if it fails or takes too long, the next one.
 * Order: Gemini Nano (AICore) → Gemma on-device → Gemini cloud (optional) → rules (always).
 */
class AssistantOrchestrator(
    /** Engine + timeout in ms. `null` = no limit: only move on if it fails or returns nothing. */
    private val llmEngines: List<Pair<AssistantEngine, Long?>>,
    private val rules: RuleBasedEngine,
    /** Memories related to a sentence (on-demand memory; never the whole memory). */
    private val memoryFor: suspend (String) -> List<String> = { emptyList() }
) {
    data class Interpretation(val command: TaskAICommand, val engineName: String)

    val rulesName: String get() = rules.displayName

    /** Rules interpretation forced to CREATE (e.g. "pasa por el banco" when there is no task to move). */
    fun rulesCreate(prompt: String, now: LocalDateTime): TaskAICommand = rules.parseCreate(prompt, now)

    private val _activeEngine = MutableStateFlow(rules.displayName)
    /** Name of the engine that would answer right now (shown in the UI). */
    val activeEngine: StateFlow<String> = _activeEngine.asStateFlow()

    suspend fun refreshActiveEngine() {
        _activeEngine.value = llmEngines.firstOrNull { (engine, _) -> runCatching { engine.isAvailable() }.getOrDefault(false) }
            ?.first?.displayName ?: rules.displayName
    }

    /**
     * @param conversation recent conversation note (ConversationContext.promptNote) so "move it to 5" can be resolved.
     */
    suspend fun interpret(prompt: String, now: LocalDateTime, conversation: String = ""): Interpretation {
        val ruleCommand = rules.parse(prompt, now)
        // Very clear commands (phone, memory, edit, routes, "move it"): rules always get them right, no need to wait for the LLM
        if (ruleCommand.action in RULES_FIRST || ruleCommand.refersToLast) {
            Log.i("LumiInterpret", "«$prompt» · rules-first · ${ruleCommand.action}/${ruleCommand.targetTitle}")
            _activeEngine.value = rules.displayName
            return Interpretation(ruleCommand, rules.displayName)
        }
        // On-demand memory: the LLM only receives the 2-3 memories related to the sentence
        val context = runCatching { memoryFor(prompt) }.getOrDefault(emptyList())
        val llmPrompt = buildString {
            append(prompt)
            if (context.isNotEmpty()) append("\n[PERSONAL CONTEXT — use it only to understand the sentence, don't copy it: ${context.joinToString("; ")}]")
            if (conversation.isNotBlank()) append("\n").append(conversation)
        }
        for ((engine, timeout) in llmEngines) {
            if (!runCatching { engine.isAvailable() }.getOrDefault(false)) continue
            val started = System.currentTimeMillis()
            val cmd = withTimeoutOrNullLogged(engine, timeout) { engine.interpret(llmPrompt, now) } ?: continue
            _activeEngine.value = engine.displayName
            val final = reconcile(cmd, ruleCommand, prompt)
            // Diagnostics (adb logcat -s LumiInterpret): what the LLM decided, what the rules said and what is used
            Log.i("LumiInterpret", "«$prompt» · ${engine.displayName} ${System.currentTimeMillis() - started} ms · " +
                "LLM=${cmd.action}/${cmd.targetTitle} · rules=${ruleCommand.action}/${ruleCommand.targetTitle} · final=${final.action}/${final.targetTitle}")
            return Interpretation(final, engine.displayName)
        }
        _activeEngine.value = rules.displayName
        return Interpretation(ruleCommand, rules.displayName)
    }

    /** Rules only (instant): used by routine steps, which are short, clear sentences. */
    fun rulesInterpret(prompt: String, now: LocalDateTime): TaskAICommand = rules.parse(prompt, now)

    /**
     * General question. With [web], an engine with Google Search (Gemini online) is tried first for current data;
     * otherwise the normal chain. Returns (text, engine), or null when only rules are available.
     */
    suspend fun answer(system: String, user: String, web: Boolean, maxTokens: Int = 350): Pair<String, String>? {
        if (web) for ((engine, timeout) in llmEngines) {
            if (!runCatching { engine.isAvailable() }.getOrDefault(false)) continue
            val text = withTimeoutOrNullLogged(engine, timeout) { engine.askWeb(system, user, maxTokens) } ?: continue
            return text to "${engine.displayName} + Google"
        }
        return ask(system, user, maxTokens)
    }

    /** Free-form task for the first available LLM (null when only rules are available). */
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
     * The LLM understands intent better; rules are more reliable with dates and categories.
     * They are combined: whatever the LLM leaves out and the rules detected is filled in.
     */
    private fun reconcile(llm: TaskAICommand, rules: TaskAICommand, prompt: String): TaskAICommand {
        if (llm.action == TaskAICommand.CREATE_MANY) {
            // Each item is completed with what the rules see in the whole sentence (shared date…)
            val items = llm.items.filter { !it.targetTitle.isNullOrBlank() }
            if (items.size < 2) return rules // the LLM didn't split it well → rules
            return llm.copy(items = items.mapIndexed { i, item ->
                reconcile(item.copy(action = TaskAICommand.CREATE), rules.items.getOrNull(i) ?: rules, prompt)
            })
        }
        // If the LLM missed a priority change the rules saw ("pon X como urgente"), the rules win
        if (rules.action == TaskAICommand.SET_PRIORITY && llm.action != TaskAICommand.SET_PRIORITY) return rules
        // Routes are very clear sentences: if the rules saw one, they win (a small LLM tends to create a task)
        if (rules.action == TaskAICommand.NAVIGATE) return rules
        // A question the LLM turned into a task (too literal) → it gets answered
        if (rules.action == TaskAICommand.RECALL && llm.action == TaskAICommand.CREATE) return rules
        if ((llm.action == TaskAICommand.ASK || llm.action == TaskAICommand.WEATHER) && llm.targetTitle.isNullOrBlank()) return llm.copy(targetTitle = prompt)
        // Memory is only touched when the user asks: Gemma saved "he dejado una natilla en la nevera" when it was a
        // question ("¿me la puedo comer?")
        if ((llm.action == TaskAICommand.REMEMBER || llm.action == TaskAICommand.FORGET) && rules.action != llm.action &&
            !AssistantIntents.asksToRemember(prompt) && !Regex("(?iu)\\bolv[ií]da|\\bforget\\b").containsMatchIn(prompt)) {
            return if (AssistantIntents.looksLikeQuestion(prompt) || rules.action == TaskAICommand.RECALL || rules.action == TaskAICommand.ASK)
                TaskAICommand(action = TaskAICommand.ASK, targetTitle = prompt) else rules
        }
        // A clear question the LLM turned into a task → it gets answered
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
            // "Uncleaned" title (the sentence was copied: "una tarea llamada … para cuando vuelva a casa") → the rules' one
            targetTitle = llm.targetTitle?.let(TaskPhraseParser::cleanTitle)?.takeIf { it.isNotBlank() && !looksUncleaned(it) } ?: rules.targetTitle,
            // The LLM's description only counts if it comes literally from what the user said (not invented)
            description = llm.description?.takeIf { isQuotedFrom(it, prompt) } ?: rules.description,
            category = llm.category ?: rules.category,
            // Dates: the rules' computation wins (exact). Gemma gets weekdays wrong ("el viernes" → Sunday the 4th);
            // its date is only used when the rules saw none
            dueDate = rules.dueDate ?: llm.dueDate.takeIf { llmDateValid },
            hasTime = if (rules.dueDate != null) rules.hasTime else llmDateValid && llm.hasTime,
            recurrence = llm.recurrence?.takeIf { io.github.salex27.lumi.domain.model.Recurrence.parse(it) != null } ?: rules.recurrence,
            remindBeforeMinutes = (llm.remindBeforeMinutes + rules.remindBeforeMinutes).distinct().filter { it in 0..(60 * 24 * 30) },
            meeting = llm.meeting ?: rules.meeting,
            priority = llm.priority?.takeIf { TaskPriority.fromString(it) != null && it.uppercase() != "NONE" } ?: rules.priority,
            // The rules detect places better (they normalize "oficina" → "trabajo")
            place = rules.place ?: llm.place?.takeIf { it.isNotBlank() && it != "null" }?.let { TaskPhraseParser.normalizePlace(it) },
            placeOnArrive = if (rules.place != null) rules.placeOnArrive else llm.placeOnArrive
        )
    }

    private fun looksUncleaned(title: String) = Regex(
        "(?iu)\\btarea\\s+(?:llamada|que\\s+se\\s+llame)|\\b(?:cuando|en\\s+cuanto)\\s+(?:llegue|vuelva|salga|regrese)\\b|^recu[eé]rdame\\b|\\bav[ií]same\\b|^(?:crea|apunta|a[ñn]ade)\\s+|" +
            "\\btask\\s+(?:called|named)|\\bwhen\\s+i\\s+(?:get|arrive|leave)\\b|^remind\\s+me\\b|^(?:create|add)\\s+"
    ).containsMatchIn(title)

    /** True when at least 80 % of the words of [text] appear in [source]. */
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
            Log.w(TAG, "${engine.displayName} took over ${timeout} ms, using the next engine")
            null
        }

    companion object {
        private const val TAG = "AssistantOrchestrator"
        private val RULES_FIRST = setOf(
            TaskAICommand.DEVICE, TaskAICommand.REMEMBER, TaskAICommand.FORGET, TaskAICommand.NAVIGATE, TaskAICommand.EDIT,
            TaskAICommand.WEATHER, TaskAICommand.DAY_BRIEF, TaskAICommand.SMART_ALARM, TaskAICommand.NOTIFICATIONS, TaskAICommand.ASK
        )

        /** Accepts "2026-10-03T17:00" or "2026-10-03" (→ 09:00 without a time). */
        fun parseIso(value: String): LocalDateTime =
            if (value.contains('T')) LocalDateTime.parse(value.trim())
            else java.time.LocalDate.parse(value.trim()).atTime(9, 0)
    }
}
