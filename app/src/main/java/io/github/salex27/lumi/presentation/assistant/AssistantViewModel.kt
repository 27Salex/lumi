package io.github.salex27.lumi.presentation.assistant

import io.github.salex27.lumi.domain.assistant.LanguageDetector

import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.salex27.lumi.data.ai.AssistantOrchestrator
import io.github.salex27.lumi.domain.model.AIProcessingResult
import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.presentation.agent.DeviceActions
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.repository.TaskRepository
import io.github.salex27.lumi.domain.time.DueDateFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

sealed interface ChatMessage {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatMessage
    data class Assistant(
        override val id: Long,
        val text: String,
        val engine: String = "",
        val tasks: List<Task> = emptyList(),
        val isError: Boolean = false
    ) : ChatMessage
}

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    /** Ids of messages whose typing animation already finished (not re-animated on recomposition). */
    val revealed: Set<Long> = emptySet(),
    /** Last microphone error (shown in the pill; it used to be ignored and voice seemed "not to work"). */
    val voiceError: String? = null,
    /**
     * Opened by "Oye Lumi" and what it heard doesn't sound like a command (e.g. background conversation): confirmation
     * is asked instead of creating tasks by mistake.
     */
    val pendingConfirmation: String? = null,
    /** Requested route ("take me home"): the Activity opens the maps app and clears it. */
    val navigateTo: io.github.salex27.lumi.domain.model.NavDestination? = null,
    /** "Did you mean…?": candidate tasks; choosing one runs the command with that task. */
    val choice: AIProcessingResult.Choose? = null,
    /** Several possible contacts to call / text. */
    val contactChoice: ContactChoice? = null,
    /** Open this task's editor (the Activity opens the app and clears it). */
    val openTaskId: Long? = null,
    /** Pending phone action (the Activity launches it and clears it). */
    val device: DeviceCommand? = null,
    val deviceContact: DeviceActions.Contact? = null,
    /** Lumi asked for something missing ("What should I tell Víctor?"): the next sentence fills it in. */
    val followUp: AIProcessingResult.AskFollowUp? = null,
    /** Routine: actions still to launch (after [device]) and the final route. */
    val deviceQueue: List<DeviceCommand> = emptyList(),
    val routineNavigate: io.github.salex27.lumi.domain.model.NavDestination? = null,
    /** Lumi just read someone's messages: "reply that…" goes to that person. */
    val replyTarget: io.github.salex27.lumi.domain.assistant.IncomingMessage? = null
) {
    /** Texts of the options to choose from (tasks or contacts). */
    val optionLabels: List<String>
        get() = choice?.options?.map { it.title }
            ?: contactChoice?.options?.map { "${it.name} · ${it.label}" }
            ?: emptyList()
}

data class ContactChoice(val command: DeviceCommand, val options: List<DeviceActions.Contact>)

/** Spoken/typed answer to "Did you mean…?": chosen index, -1 = none, null = not an answer. Spanish and English. */
internal fun parseChoiceAnswer(text: String, labels: List<String>): Int? {
    val t = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .trim('.', '!', ' ', ',')
    if (Regex("^(?:no|ninguna|ninguno|cancela|nada|olvidalo|dejalo|none|neither|cancel|nothing|forget it|never ?mind|no thanks)$").matches(t)) return -1
    if (Regex("^(?:si|vale|esa|ese|eso|correcto|exacto|claro|venga|ok|la primera|el primero|primera|primero|1|uno|una|" +
            "yes|yeah|yep|sure|okay|right|correct|that one|go ahead|do it|send it|the first one|the first|first|one)$").matches(t)) return 0
    if (Regex("^(?:la segunda|el segundo|segunda|segundo|2|dos|the second one|the second|second|two)$").matches(t)) return 1.takeIf { it < labels.size }
    if (Regex("^(?:la tercera|el tercero|tercera|tercero|3|tres|the third one|the third|third|three)$").matches(t)) return 2.takeIf { it < labels.size }
    // By name: "la de Víctor al trabajo" / "the one with Víctor"
    val words = t.split(" ").filter { it.length >= 4 }
    if (words.isEmpty()) return null
    val scores = labels.map { l ->
        val n = java.text.Normalizer.normalize(l.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        words.count { n.contains(it) }
    }
    val best = scores.maxOrNull() ?: 0
    return if (best > 0 && scores.count { it == best } == 1) scores.indexOf(best) else null
}

class AssistantViewModel(
    private val repository: TaskRepository,
    orchestrator: AssistantOrchestrator,
    /** Reads the reply aloud (only if the request came by voice). */
    private val speak: (String) -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    val activeEngine: StateFlow<String> = orchestrator.activeEngine

    private var nextId = 0L
    /** The current request came by voice → the reply is read aloud (hands-free). */
    private var voiceTurn = false

    init {
        val now = LocalDateTime.now()
        append(
            ChatMessage.Assistant(
                id = nextId++,
                text = "${DueDateFormatter.greeting(now, ReplyLanguage.app)}. " + ReplyLanguage.ui(
                    "¿En qué te ayudo? Puedes pedirme que apunte algo, preguntarme qué hacer ahora o decirme que ya terminaste una tarea.",
                    "How can I help? You can ask me to note something down, ask what to do now or tell me you finished a task."
                )
            )
        )
        viewModelScope.launch { orchestrator.refreshActiveEngine() }
    }

    fun send(text: String) = send(text, fromVoice = false)

    private fun send(text: String, fromVoice: Boolean) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isThinking) return
        // Replies in the language the user is speaking (also Lumi's local answers below)
        ReplyLanguage.current = LanguageDetector.detect(prompt, ReplyLanguage.current)
        // Is it answering a question from Lumi ("What should I tell Víctor?")?
        _state.value.followUp?.takeIf { it.slot == "confirm" }?.let { f ->
            // "Shall I send it?" / "Shall I set it?" → yes runs, no cancels, anything else is treated as a new sentence
            val answer = parseChoiceAnswer(prompt, listOf("confirm"))
            _state.update { it.copy(followUp = null) }
            if (answer != null) {
                voiceTurn = fromVoice
                append(ChatMessage.User(nextId++, prompt))
                if (answer < 0) say(t("Vale, no lo hago.", "OK, I won't.")) else run { repository.executeCommand(f.command) }
                return
            }
        }
        _state.value.followUp?.let { f ->
            _state.update { it.copy(followUp = null) }
            voiceTurn = fromVoice
            append(ChatMessage.User(nextId++, prompt))
            val msg = DeviceCommand.parse(f.command.device) as? DeviceCommand.Message
            if (msg == null || Regex("(?iu)^(?:nada|d[eé]jalo|cancela|olv[ií]dalo|no|nothing|cancel|forget it|never ?mind)$").matches(prompt)) {
                append(ChatMessage.Assistant(nextId++, t("Vale, no envío nada.", "OK, I won't send anything."))); return
            }
            val filled = when (f.slot) {
                "contact" -> msg.copy(contact = prompt.removePrefix("a ").removePrefix("to ").trim())
                else -> msg.copy(text = prompt.removePrefix("que ").removePrefix("that ").trim().replaceFirstChar { it.uppercase() })
            }
            run { repository.executeCommand(f.command.copy(device = filled.serialize())) }
            return
        }
        // Is it the answer to "Did you mean…?" ("yes", "the second one", "no"…)
        val labels = _state.value.optionLabels
        if (labels.isNotEmpty()) {
            val answer = parseChoiceAnswer(prompt, labels)
            if (answer != null) {
                voiceTurn = fromVoice
                append(ChatMessage.User(nextId++, prompt))
                if (answer < 0) {
                    _state.update { it.copy(choice = null, contactChoice = null) }
                    append(ChatMessage.Assistant(nextId++, t("Vale, no toco nada.", "OK, I won't touch anything.")))
                    if (fromVoice) speak(t("Vale, no toco nada.", "OK, I won't touch anything."))
                } else pick(answer, echo = false)
                return
            }
            _state.update { it.copy(choice = null, contactChoice = null) } // something else: the question is dropped
        }
        voiceTurn = fromVoice
        _state.update { it.copy(voiceError = null) }
        append(ChatMessage.User(nextId++, prompt))
        val replyWho = _state.value.replyTarget?.conversation.orEmpty()
        val rewritten = withReplyTarget(prompt)
        if (rewritten.isEmpty()) { say(t("¿Qué le digo a $replyWho?", "What should I tell $replyWho?")); return }
        run { repository.processNaturalLanguageCommand(rewritten) }
    }

    /**
     * After reading someone's messages: "reply that I'm coming" / "tell them yes" → "tell Víctor that I'm coming";
     * "yes" to "Shall I reply?" → Lumi asks what to say.
     */
    private fun withReplyTarget(prompt: String): String {
        val target = _state.value.replyTarget ?: return prompt
        _state.update { it.copy(replyTarget = null) }
        val who = target.conversation
        Regex("(?iu)^(?:s[ií],?\\s+)?(?:resp[oó]nde(?:le)?|cont[eé]sta(?:le)?|dile|escr[ií]bele|p[oó]nle)\\s+(?:que\\s+)?(.+)$").find(prompt.trim())?.let { m ->
            if (!Regex("(?iu)^a\\s+").containsMatchIn(m.groupValues[1])) return "dile a $who que ${m.groupValues[1]}"
        }
        Regex("(?i)^(?:yes,?\\s+)?(?:reply|answer|tell\\s+(?:him|her|them)|text\\s+(?:him|her|them)|say)\\s+(?:that\\s+)?(.+)$").find(prompt.trim())?.let { m ->
            return "tell $who that ${m.groupValues[1]}"
        }
        if (parseChoiceAnswer(prompt, listOf(who)) == 0 ||
            Regex("(?iu)^(?:resp[oó]nde(?:le)?|cont[eé]sta(?:le)?|reply|answer(?: (?:him|her|them))?)$").matches(prompt.trim())) {
            val msg = DeviceCommand.Message(who, "", !target.app.contains("mensaje", true) && !target.app.contains("messages", true))
            _state.update {
                it.copy(followUp = AIProcessingResult.AskFollowUp(
                    io.github.salex27.lumi.domain.model.TaskAICommand(
                        action = io.github.salex27.lumi.domain.model.TaskAICommand.DEVICE, device = msg.serialize()
                    ), "message", "", ""
                ))
            }
            return ""
        }
        return prompt
    }

    fun planMyDay() = quick(ReplyLanguage.ui("¿Qué hago ahora?", "What should I do now?")) { repository.planMyDay() }
    fun briefing() = quick(ReplyLanguage.ui("¿Cómo voy?", "How am I doing?")) { repository.generateDailyBriefing() }

    fun markRevealed(id: Long) = _state.update { it.copy(revealed = it.revealed + id) }

    fun setVoiceError(message: String?) = _state.update { it.copy(voiceError = message) }

    /**
     * Voice result. If Lumi opened on its own through "Oye Lumi", it only runs directly if it sounds like a command and
     * the wake itself wasn't doubtful (see [confirmNextWakeRequest]).
     */
    fun sendVoice(text: String, fromWakeWord: Boolean) {
        val mustConfirm = fromWakeWord && confirmWake
        if (fromWakeWord) confirmWake = false // only the first request after the wake
        if (fromWakeWord && (mustConfirm || !io.github.salex27.lumi.service.wakeword.CommandLikeness.looksLikeCommand(text))) {
            _state.update { it.copy(pendingConfirmation = text) }
        } else send(text, fromVoice = true)
    }

    /**
     * The wake word fired with a borderline score or while other audio was playing (a series line like "remind me…"
     * could pass as a command): the next voice request is confirmed even if it sounds like a command (issue #6).
     */
    fun confirmNextWakeRequest() { confirmWake = true }
    private var confirmWake = false

    fun confirmPending() {
        val text = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        send(text, fromVoice = true)
    }

    fun navigationHandled() = _state.update { it.copy(navigateTo = null) }

    /** The user chose option [index] (by tapping it or answering). */
    fun pick(index: Int, echo: Boolean = true) {
        val s = _state.value
        s.choice?.let { choice ->
            val task = choice.options.getOrNull(index) ?: return
            if (echo) append(ChatMessage.User(nextId++, task.title))
            _state.update { it.copy(choice = null) }
            run { repository.executeCommand(choice.command.copy(targetId = task.id)) }
            return
        }
        s.contactChoice?.let { cc ->
            val contact = cc.options.getOrNull(index) ?: return
            if (echo) append(ChatMessage.User(nextId++, contact.name))
            _state.update { it.copy(contactChoice = null, device = cc.command, deviceContact = contact) }
        }
    }

    fun pickNone() {
        _state.update { it.copy(choice = null, contactChoice = null) }
        append(ChatMessage.Assistant(nextId++, t("Vale, no toco nada.", "OK, I won't touch anything.")))
    }

    fun openTaskHandled() = _state.update { it.copy(openTaskId = null) }
    fun deviceHandled() = _state.update { it.copy(device = null, deviceContact = null) }

    /** A routine is running: missing accesses are mentioned, without opening system screens halfway through. */
    var routineActive = false
        private set

    /**
     * Routine: launches the next action in the queue. When it is empty, the route (if any).
     * Returns true if something is still left to do (the Activity must not close).
     */
    fun nextDevice(): Boolean {
        val s = _state.value
        return when {
            s.deviceQueue.isNotEmpty() -> { _state.update { it.copy(device = s.deviceQueue.first(), deviceQueue = s.deviceQueue.drop(1)) }; true }
            s.routineNavigate != null -> { _state.update { it.copy(navigateTo = s.routineNavigate, routineNavigate = null) }; true }
            else -> { routineActive = false; false }
        }
    }

    private val _listenAgain = MutableStateFlow(0)
    /** Changes when Lumi has answered something said by voice: the Activity listens again once it finishes speaking. */
    val listenAgain: StateFlow<Int> = _listenAgain.asStateFlow()

    /**
     * A reminder's button ("Text Roberto", "Call mum"): runs the action and marks the task done.
     */
    fun runTaskAction(command: DeviceCommand, taskId: Long) {
        append(ChatMessage.Assistant(nextId++, when (command) {
            is DeviceCommand.Call -> t("Llamando a ${command.contact}.", "Calling ${command.contact}.")
            is DeviceCommand.Message -> t("Te abro el chat con ${command.contact}", "Opening the chat with ${command.contact}") +
                if (command.text.isNotBlank()) t(" con el mensaje escrito.", " with the message written.") else "."
            else -> t("Hecho.", "Done.")
        }))
        _state.update { it.copy(device = command) }
        if (taskId > 0) viewModelScope.launch {
            repository.getTask(taskId)?.let { repository.updateTask(it.copy(status = io.github.salex27.lumi.domain.model.TaskStatus.COMPLETED)) }
        }
    }

    /** Sends a spoken request (opened from the morning notification with "Listen"): the reply is read aloud. */
    fun sendSpoken(text: String) = send(text, fromVoice = true)
    fun retryDevice(command: DeviceCommand, contact: DeviceActions.Contact? = null) = _state.update { it.copy(device = command, deviceContact = contact) }
    fun askContact(command: DeviceCommand, options: List<DeviceActions.Contact>) {
        _state.update { it.copy(contactChoice = ContactChoice(command, options)) }
        append(ChatMessage.Assistant(nextId++, t("Tengo varios. ¿A cuál?", "I have several. Which one?")))
        if (voiceTurn) speak(t("Tengo varios. ¿A cuál?", "I have several. Which one?"))
    }

    /** A message from Lumi that doesn't come from the repository (e.g. "I can't find Ana in your contacts"). */
    fun say(text: String, isError: Boolean = false) {
        append(ChatMessage.Assistant(nextId++, text, isError = isError))
        if (voiceTurn) speak(text)
    }

    fun discardPending() = _state.update { it.copy(pendingConfirmation = null) }

    private fun quick(label: String, block: suspend () -> AIProcessingResult) {
        if (_state.value.isThinking) return
        voiceTurn = false
        ReplyLanguage.current = ReplyLanguage.app
        append(ChatMessage.User(nextId++, label))
    }

    private fun run(block: suspend () -> AIProcessingResult) {
        _state.update { it.copy(isThinking = true) }
        viewModelScope.launch {
            val result = runCatching { block() }.getOrElse { AIProcessingResult.Error(t("Algo ha fallado: ", "Something went wrong: ") + it.localizedMessage) }
            val tasks = when (result) {
                is AIProcessingResult.Created -> listOf(result.task)
                is AIProcessingResult.Updated -> listOf(result.task)
                is AIProcessingResult.Plan -> result.suggestions
                is AIProcessingResult.CreatedMany -> result.tasks
                is AIProcessingResult.Routine -> result.tasks
                else -> emptyList()
            }
            append(
                ChatMessage.Assistant(
                    id = nextId++,
                    text = result.reply,
                    engine = result.engine,
                    tasks = tasks,
                    isError = result is AIProcessingResult.Error
                )
            )
            _state.update {
                it.copy(
                    isThinking = false,
                    navigateTo = (result as? AIProcessingResult.Navigate)?.destination,
                    choice = result as? AIProcessingResult.Choose,
                    openTaskId = (result as? AIProcessingResult.OpenTask)?.task?.id,
                    device = (result as? AIProcessingResult.Device)?.command
                        ?: (result as? AIProcessingResult.Routine)?.devices?.firstOrNull(),
                    deviceQueue = (result as? AIProcessingResult.Routine)?.devices?.drop(1).orEmpty(),
                    routineNavigate = (result as? AIProcessingResult.Routine)?.navigate?.takeIf { result.devices.isNotEmpty() },
                    followUp = result as? AIProcessingResult.AskFollowUp,
                    replyTarget = (result as? AIProcessingResult.Messages)?.replyTo
                ).let { st ->
                    // Routine without phone actions but with a route → straight to the route
                    val r = result as? AIProcessingResult.Routine
                    if (r != null && r.devices.isEmpty() && r.navigate != null) st.copy(navigateTo = r.navigate) else st
                }
            }
            routineActive = result is AIProcessingResult.Routine
            if (voiceTurn) speak(result.reply)
            // Continuous conversation: after answering by voice, listen again (unless Lumi goes to another app)
            val leaves = result is AIProcessingResult.Navigate || result is AIProcessingResult.OpenTask ||
                (result is AIProcessingResult.Device && !result.command.staysInLumi) ||
                (result is AIProcessingResult.Routine && (result.navigate != null || result.devices.any { !it.staysInLumi }))
            if (voiceTurn && !leaves) _listenAgain.update { it + 1 }
        }
    }

    private fun append(message: ChatMessage) = _state.update { it.copy(messages = it.messages + message) }

    class Factory(
        private val repository: TaskRepository,
        private val orchestrator: AssistantOrchestrator,
        private val speak: (String) -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(repository, orchestrator, speak) as T
    }
}

/** Lumi's local replies in the language of the conversation. */
private fun t(es: String, en: String) = ReplyLanguage.t(es, en)
