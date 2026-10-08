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
import io.github.salex27.lumi.data.chat.ChatMemory
import io.github.salex27.lumi.data.chat.ChatStore
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.data.local.ChatSessionRow
import kotlinx.coroutines.CompletableDeferred
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
        val isError: Boolean = false,
        /** Web results the answer came from (#7), shown as links. */
        val sources: List<io.github.salex27.lumi.domain.search.WebHit> = emptyList(),
        /** Nearby places (OpenStreetMap, untrusted), shown as tappable rows that open the maps app. */
        val places: List<io.github.salex27.lumi.domain.places.NearbyPlace> = emptyList()
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
    /** Set when a nearby search needs the location permission: the question to repeat once it is granted. */
    val locationRequest: String? = null,
    val deviceContact: DeviceActions.Contact? = null,
    /** Lumi asked for something missing ("What should I tell Víctor?"): the next sentence fills it in. */
    val followUp: AIProcessingResult.AskFollowUp? = null,
    /** Routine: actions still to launch (after [device]) and the final route. */
    val deviceQueue: List<DeviceCommand> = emptyList(),
    val routineNavigate: io.github.salex27.lumi.domain.model.NavDestination? = null,
    /** Lumi just read someone's messages: "reply that…" goes to that person. */
    val replyTarget: io.github.salex27.lumi.domain.assistant.IncomingMessage? = null,
    /** Chat session on screen (null = a new chat, created with its first message) and the saved sessions. */
    val sessionId: Long? = null,
    val sessionTitle: String = "",
    val sessions: List<SessionItem> = emptyList(),
    /** "Task or Claude?": Lumi wasn't sure what kind of request it was (#1). */
    val clarify: AIProcessingResult.Clarify? = null,
    /** A web search is running (#7): the thinking line says so. */
    val searching: Boolean = false
) {
    /** Texts of the options to choose from (tasks, contacts or the kind of request). */
    val optionLabels: List<String>
        get() = choice?.options?.map { it.title }
            ?: contactChoice?.options?.map { "${it.name} · ${it.label}" }
            ?: clarify?.options?.map { routeLabel(it) }
            ?: emptyList()
}

/** Chip of a "task or agent?" question, in the conversation language. */
internal fun routeLabel(route: io.github.salex27.lumi.domain.assistant.IntentRoute): String = when (route) {
    io.github.salex27.lumi.domain.assistant.IntentRoute.TASK -> ReplyLanguage.t("Apúntalo como tarea", "Add it as a task")
    io.github.salex27.lumi.domain.assistant.IntentRoute.AGENT -> ReplyLanguage.t("Pásaselo a Claude", "Send it to Claude")
    else -> route.name
}

data class ContactChoice(val command: DeviceCommand, val options: List<DeviceActions.Contact>)

/** Web sources stored with an assistant message (JSON payload), so a resumed chat still shows them. */
internal object MessagePayload {
    @kotlinx.serialization.Serializable
    private data class Payload(
        val sources: List<io.github.salex27.lumi.domain.search.WebHit> = emptyList(),
        val places: List<io.github.salex27.lumi.domain.places.NearbyPlace> = emptyList()
    )
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun of(sources: List<io.github.salex27.lumi.domain.search.WebHit>, places: List<io.github.salex27.lumi.domain.places.NearbyPlace> = emptyList()): String? =
        if (sources.isEmpty() && places.isEmpty()) null else json.encodeToString(Payload(sources, places))
    fun places(payload: String?): List<io.github.salex27.lumi.domain.places.NearbyPlace> =
        payload?.let { runCatching { json.decodeFromString<Payload>(it).places }.getOrNull() }.orEmpty()
    fun sources(payload: String?): List<io.github.salex27.lumi.domain.search.WebHit> =
        payload?.let { runCatching { json.decodeFromString<Payload>(it).sources }.getOrNull() }.orEmpty()
}

/** A saved chat in the session list. */
data class SessionItem(val id: Long, val title: String, val preview: String, val updatedAt: Long, val messageCount: Int)

/** Messages created in this screen get ids from here up; history loaded from a session gets 1..n. */
internal const val LIVE_ID_BASE = 1_000_000L
/** The greeting is never stored: it only shows on an empty chat. */
internal const val GREETING_ID = -1L

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
    private val speak: (String) -> Unit = {},
    /** Chat sessions (null in previews/tests: nothing is stored). */
    private val chatStore: ChatStore? = null,
    private val chatMemory: ChatMemory? = null,
    /** Hands a request to Claude on the PC (Orbit 1:1 through Lumi Hub); false when the Hub isn't set up. */
    private val handOff: suspend (String) -> Boolean = { false },
    searchingFlow: StateFlow<Boolean> = MutableStateFlow(false)
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    val activeEngine: StateFlow<String> = orchestrator.activeEngine

    private var nextId = LIVE_ID_BASE
    /** The current request came by voice → the reply is read aloud (hands-free). */
    private var voiceTurn = false

    /** Session being written; messages wait in [pendingWrites] until the session to resume is known. */
    private var handle = ChatStore.Handle()
    private val ready = CompletableDeferred<Unit>()
    private val pendingWrites = mutableListOf<ChatMessageEntity>()

    init {
        _state.update { it.copy(messages = listOf(greeting())) }
        viewModelScope.launch { orchestrator.refreshActiveEngine() }
        viewModelScope.launch { searchingFlow.collect { s -> _state.update { it.copy(searching = s) } } }
        val store = chatStore
        if (store == null) ready.complete(Unit) else {
            viewModelScope.launch { store.observeSessions().collect { rows -> _state.update { it.copy(sessions = rows.map(::toItem)) } } }
            viewModelScope.launch {
                try {
                    loadSession(store.sessionToResume(), keepLive = true)
                } finally {
                    val h = handle
                    synchronized(pendingWrites) {
                        pendingWrites.forEach { e -> store.append(h, e) { _state.update { st -> if (st.sessionId == null && handle === h) st.copy(sessionId = h.sessionId) else st } } }
                        pendingWrites.clear()
                        ready.complete(Unit)
                    }
                }
            }
        }
    }

    private fun greeting() = ChatMessage.Assistant(
        id = GREETING_ID,
        text = "${DueDateFormatter.greeting(LocalDateTime.now(), ReplyLanguage.app)}. " + ReplyLanguage.ui(
            "¿En qué te ayudo? Puedes pedirme que apunte algo, preguntarme qué hacer ahora o decirme que ya terminaste una tarea.",
            "How can I help? You can ask me to note something down, ask what to do now or tell me you finished a task."
        )
    )

    private fun toItem(row: ChatSessionRow) = SessionItem(
        row.session.id, row.session.title.ifBlank { ReplyLanguage.ui("Chat", "Chat") }, row.preview.orEmpty(), row.session.updatedAt, row.messageCount
    )

    /**
     * Shows [session] (null = a new chat) and loads it into Lumi's conversation memory. With [keepLive], messages typed
     * before the history finished loading (an initial prompt) stay after it.
     */
    private suspend fun loadSession(session: ChatSessionEntity?, keepLive: Boolean) {
        val store = chatStore ?: return
        val history = session?.let { s -> store.messages(s.id) }.orEmpty()
        val ui = history.mapIndexed { i, m -> toUi(m, i + 1L) }
        chatMemory?.resume(session)
        handle = ChatStore.Handle(session?.id)
        _state.update { st ->
            val live = if (keepLive) st.messages.filter { it.id >= LIVE_ID_BASE } else emptyList()
            val body = ui + live
            st.copy(
                messages = if (body.isEmpty()) listOf(greeting()) else body,
                revealed = st.revealed + ui.map { it.id },
                sessionId = session?.id, sessionTitle = session?.title.orEmpty(),
                choice = if (keepLive) st.choice else null, contactChoice = if (keepLive) st.contactChoice else null,
                followUp = if (keepLive) st.followUp else null, replyTarget = if (keepLive) st.replyTarget else null
            )
        }
    }

    private suspend fun toUi(m: ChatMessageEntity, id: Long): ChatMessage =
        if (m.role == ChatMessageEntity.ROLE_USER) ChatMessage.User(id, m.text)
        else ChatMessage.Assistant(
            id, m.text, m.engine, m.taskIdList.take(6).mapNotNull { runCatching { repository.getTask(it) }.getOrNull() }, m.isError,
            MessagePayload.sources(m.payload), MessagePayload.places(m.payload)
        )

    // ── Sessions ──────────────────────────────────────────────────────────────

    /** "New chat": the old one stays in the list. */
    fun newSession() {
        if (_state.value.isThinking) return
        chatStore?.setActive(null)
        viewModelScope.launch { loadSession(null, keepLive = false) }
    }

    /** Resumes a saved chat from the list. */
    fun openSession(id: Long) {
        val store = chatStore ?: return
        if (_state.value.isThinking || id == _state.value.sessionId) return
        viewModelScope.launch {
            val session = store.session(id) ?: return@launch
            store.setActive(id)
            loadSession(session, keepLive = false)
        }
    }

    fun renameSession(id: Long, title: String) {
        chatStore?.rename(id, title)
        if (id == _state.value.sessionId && title.isNotBlank()) _state.update { it.copy(sessionTitle = title.trim()) }
    }

    fun deleteSession(id: Long) {
        chatStore?.delete(id)
        if (id == _state.value.sessionId) viewModelScope.launch { loadSession(null, keepLive = false) }
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
                    _state.update { it.copy(choice = null, contactChoice = null, clarify = null) }
                    append(ChatMessage.Assistant(nextId++, t("Vale, no toco nada.", "OK, I won't touch anything.")))
                    if (fromVoice) speak(t("Vale, no toco nada.", "OK, I won't touch anything."))
                } else pick(answer, echo = false)
                return
            }
            _state.update { it.copy(choice = null, contactChoice = null, clarify = null) } // something else: the question is dropped
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
        } else {
            if (fromWakeWord) reportWake(confirmed = true) // the user went on with a command: it was them
            send(text, fromVoice = true)
        }
    }

    /**
     * Adaptive Voice Match: told once whether the wake that opened Lumi was really the user ([confirmed]) or was
     * dismissed right away. Set by the Activity when opened by the wake word.
     */
    var onWakeOutcome: ((confirmed: Boolean) -> Unit)? = null

    fun reportWake(confirmed: Boolean) {
        val report = onWakeOutcome ?: return
        onWakeOutcome = null
        report(confirmed)
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
        reportWake(confirmed = true) // "yes, note it down"
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
        s.clarify?.let { c ->
            val route = c.options.getOrNull(index) ?: return
            if (echo) append(ChatMessage.User(nextId++, routeLabel(route)))
            _state.update { it.copy(clarify = null) }
            run { repository.processNaturalLanguageCommand(c.text, route = route) }
            return
        }
        s.contactChoice?.let { cc ->
            val contact = cc.options.getOrNull(index) ?: return
            if (echo) append(ChatMessage.User(nextId++, contact.name))
            _state.update { it.copy(contactChoice = null, device = cc.command, deviceContact = contact) }
        }
    }

    fun pickNone() {
        _state.update { it.copy(choice = null, contactChoice = null, clarify = null) }
        append(ChatMessage.Assistant(nextId++, t("Vale, no toco nada.", "OK, I won't touch anything.")))
    }

    fun openTaskHandled() = _state.update { it.copy(openTaskId = null) }
    fun deviceHandled() = _state.update { it.copy(device = null, deviceContact = null) }
    fun locationHandled() = _state.update { it.copy(locationRequest = null) }

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

    /** Hook set by the Activity: runs the first callback once the phone is unlocked (asking to unlock if needed). */
    var unlockGate: ((onUnlocked: () -> Unit, onCancelled: () -> Unit) -> Unit)? = null
    private var untrustedTurn = false

    /** Text that came from another app (Share → Lumi): whatever it triggers waits for the user's confirmation. */
    fun sendUntrusted(text: String) {
        untrustedTurn = true
        try { send(text) } finally { untrustedTurn = false }
    }

    /** Results that expose private data or reach the PC: over the lock screen they wait for an unlock. */
    private fun needsUnlock(r: AIProcessingResult) =
        r is AIProcessingResult.Agent || r is AIProcessingResult.Messages || r is AIProcessingResult.Memory

    private suspend fun gateOnUnlock(r: AIProcessingResult): AIProcessingResult {
        val gate = unlockGate ?: return r
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            gate(
                { if (cont.isActive) cont.resumeWith(Result.success(r)) },
                { if (cont.isActive) cont.resumeWith(Result.success(AIProcessingResult.Error(t("Desbloquea el móvil y vuelve a pedírmelo.", "Unlock the phone and ask me again."), r.engine))) }
            )
        }
    }

    /** Result of a turn that started from shared text: actions are never run silently. */
    private fun distrust(r: AIProcessingResult): AIProcessingResult = when (r) {
        is AIProcessingResult.Device -> AIProcessingResult.AskFollowUp(
            io.github.salex27.lumi.domain.model.TaskAICommand(action = io.github.salex27.lumi.domain.model.TaskAICommand.DEVICE, device = r.command.serialize()),
            "confirm", t("El texto compartido pide: ${r.reply} ¿Lo hago?", "The shared text asks for this: ${r.reply} Shall I do it?"), r.engine
        )
        is AIProcessingResult.Agent -> AIProcessingResult.Error(t("No paso a Claude texto compartido desde otras apps. Escríbelo tú aquí.", "I don't pass text shared from other apps on to Claude. Type it here yourself."), r.engine)
        is AIProcessingResult.Routine -> if (r.devices.isEmpty()) r else r.copy(devices = emptyList(), navigate = null)
        else -> r
    }
    fun retryDevice(command: DeviceCommand, contact: DeviceActions.Contact? = null) = _state.update { it.copy(device = command, deviceContact = contact) }
    fun askContact(command: DeviceCommand, options: List<DeviceActions.Contact>) {
        _state.update { it.copy(contactChoice = ContactChoice(command, options)) }
        append(ChatMessage.Assistant(nextId++, t("Tengo varios. ¿A cuál?", "I have several. Which one?")))
        if (voiceTurn) speak(t("Tengo varios. ¿A cuál?", "I have several. Which one?"))
    }

    /** A message from Lumi that doesn't come from the repository (e.g. "I can't find Ana in your contacts"). */
    fun say(text: String, isError: Boolean = false, sources: List<io.github.salex27.lumi.domain.search.WebHit> = emptyList()) {
        append(ChatMessage.Assistant(nextId++, text, isError = isError, sources = sources))
        if (voiceTurn) speak(text)
    }

    fun discardPending() {
        if (_state.value.pendingConfirmation != null) reportWake(confirmed = false)
        _state.update { it.copy(pendingConfirmation = null) }
    }

    private fun quick(label: String, block: suspend () -> AIProcessingResult) {
        if (_state.value.isThinking) return
        voiceTurn = false
        ReplyLanguage.current = ReplyLanguage.app
        append(ChatMessage.User(nextId++, label))
        run(block)
    }

    private fun run(block: suspend () -> AIProcessingResult) {
        val untrusted = untrustedTurn
        _state.update { it.copy(isThinking = true) }
        viewModelScope.launch {
            ready.await() // the resumed session must be in Lumi's memory before it interprets ("move it")
            val lastBefore = repository.conversation.sessionTurns().lastOrNull()
            val result = runCatching { block() }.getOrElse { AIProcessingResult.Error(t("Algo ha fallado: ", "Something went wrong: ") + it.localizedMessage) }
                .let { r -> if (untrusted) distrust(r) else r }
                .let { r -> if (needsUnlock(r)) gateOnUnlock(r) else r }
                .let { r -> if (r is AIProcessingResult.Agent) handOffReply(r) else r }
            // The repository recorded the turn: its action is kept with the reply for follow-ups after resuming
            val action = repository.conversation.sessionTurns().lastOrNull()?.takeIf { it !== lastBefore }?.action
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
                    isError = result is AIProcessingResult.Error,
                    sources = (result as? AIProcessingResult.WebAnswer)?.sources.orEmpty(),
                    places = (result as? AIProcessingResult.Nearby)?.places.orEmpty()
                ),
                action
            )
            chatMemory?.afterTurn(handle)
            _state.update {
                it.copy(
                    isThinking = false,
                    navigateTo = (result as? AIProcessingResult.Navigate)?.destination,
                    choice = result as? AIProcessingResult.Choose,
                    clarify = result as? AIProcessingResult.Clarify,
                    openTaskId = (result as? AIProcessingResult.OpenTask)?.task?.id,
                    device = (result as? AIProcessingResult.Device)?.command
                        ?: (result as? AIProcessingResult.Routine)?.devices?.firstOrNull(),
                    locationRequest = (result as? AIProcessingResult.Nearby)?.takeIf { r -> r.needsLocationPermission }?.query,
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
            // Citation marks ("[1]") are for the screen, not for the voice
            if (voiceTurn) speak(result.reply.replace(Regex("\\s*\\[\\d+]"), ""))
            // Continuous conversation: after answering by voice, listen again (unless Lumi goes to another app)
            val leaves = result is AIProcessingResult.Navigate || result is AIProcessingResult.OpenTask ||
                (result is AIProcessingResult.Device && !result.command.staysInLumi) ||
                (result is AIProcessingResult.Routine && (result.navigate != null || result.devices.any { !it.staysInLumi }))
            if (voiceTurn && !leaves) _listenAgain.update { it + 1 }
        }
    }

    /** Sends the request to Claude (Orbit 1:1 through the Hub) and says what happened. */
    private suspend fun handOffReply(r: AIProcessingResult.Agent): AIProcessingResult {
        val sent = runCatching { handOff(r.request) }.getOrDefault(false)
        return r.copy(reply = if (sent) t(
            "Se lo he pasado a Claude en tu PC. Su respuesta aparecerá en Orbit (arriba a la derecha en Inicio).",
            "Sent to Claude on your PC. Its answer will show up in Orbit (top right on Home)."
        ) else t(
            "Para pasarle cosas a Claude, conecta Lumi Hub en Ajustes (Claude Code en tu PC por Tailscale).",
            "To send things to Claude, connect Lumi Hub in Settings (Claude Code on your PC over Tailscale)."
        ))
    }

    /** Shows a message and stores it in the session ([action] = the command a reply executed, for follow-ups). */
    private fun append(message: ChatMessage, action: String? = null) {
        _state.update { st -> st.copy(messages = st.messages.filter { it.id != GREETING_ID } + message) }
        val store = chatStore ?: return
        val entity = when (message) {
            is ChatMessage.User -> ChatMessageEntity(sessionId = 0, role = ChatMessageEntity.ROLE_USER, text = message.text, createdAt = store.stamp())
            is ChatMessage.Assistant -> ChatMessageEntity(
                sessionId = 0, role = ChatMessageEntity.ROLE_ASSISTANT, text = message.text, createdAt = store.stamp(),
                engine = message.engine, isError = message.isError, action = action,
                taskIds = ChatMessageEntity.joinIds(message.tasks.map { it.id }),
                payload = MessagePayload.of(message.sources, message.places)
            )
        }
        synchronized(pendingWrites) {
            if (!ready.isCompleted) { pendingWrites += entity; return }
        }
        val h = handle
        store.append(h, entity) { if (_state.value.sessionId == null && handle === h) _state.update { it.copy(sessionId = h.sessionId) } }
    }

    class Factory(
        private val repository: TaskRepository,
        private val orchestrator: AssistantOrchestrator,
        private val speak: (String) -> Unit = {},
        private val chatStore: ChatStore? = null,
        private val chatMemory: ChatMemory? = null,
        private val handOff: suspend (String) -> Boolean = { false },
        private val searching: StateFlow<Boolean> = MutableStateFlow(false)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(repository, orchestrator, speak, chatStore, chatMemory, handOff, searching) as T
    }
}

/** Lumi's local replies in the language of the conversation. */
private fun t(es: String, en: String) = ReplyLanguage.t(es, en)
