package com.antigravity.gemininanotaskmanager.presentation.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antigravity.gemininanotaskmanager.data.ai.AssistantOrchestrator
import com.antigravity.gemininanotaskmanager.domain.model.AIProcessingResult
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommand
import com.antigravity.gemininanotaskmanager.presentation.agent.DeviceActions
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.repository.TaskRepository
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
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
    /** Ids de mensajes que ya terminaron la animación de escritura (no se re-animan al recomponer). */
    val revealed: Set<Long> = emptySet(),
    /** Último error del micrófono (se muestra en la píldora; antes se ignoraba y parecía que la voz "no funcionaba"). */
    val voiceError: String? = null,
    /**
     * Abierta por «Oye Lumi» y lo oído no suena a una orden (p.ej. conversación de fondo): se pide confirmación
     * en vez de crear tareas por error.
     */
    val pendingConfirmation: String? = null,
    /** Ruta pedida («llévame a casa»): la Activity abre la app de mapas y lo limpia. */
    val navigateTo: com.antigravity.gemininanotaskmanager.domain.model.NavDestination? = null,
    /** «¿Te refieres a…?»: tareas candidatas; al elegir se ejecuta el comando con esa tarea. */
    val choice: AIProcessingResult.Choose? = null,
    /** Varios contactos posibles para llamar / escribir. */
    val contactChoice: ContactChoice? = null,
    /** Abrir el editor de esta tarea (la Activity abre la app y lo limpia). */
    val openTaskId: Long? = null,
    /** Acción del móvil pendiente de ejecutar (la Activity la lanza y lo limpia). */
    val device: DeviceCommand? = null,
    val deviceContact: DeviceActions.Contact? = null,
    /** Lumi preguntó algo que falta («¿Qué le digo a Víctor?»): la siguiente frase lo completa. */
    val followUp: AIProcessingResult.AskFollowUp? = null,
    /** Rutina: acciones que faltan por lanzar (después de [device]) y la ruta del final. */
    val deviceQueue: List<DeviceCommand> = emptyList(),
    val routineNavigate: com.antigravity.gemininanotaskmanager.domain.model.NavDestination? = null,
    /** Lumi acaba de leer los mensajes de alguien: «respóndele que…» va a esa persona. */
    val replyTarget: com.antigravity.gemininanotaskmanager.domain.assistant.IncomingMessage? = null
) {
    /** Textos de las opciones a elegir (tareas o contactos). */
    val optionLabels: List<String>
        get() = choice?.options?.map { it.title }
            ?: contactChoice?.options?.map { "${it.name} · ${it.label}" }
            ?: emptyList()
}

data class ContactChoice(val command: DeviceCommand, val options: List<DeviceActions.Contact>)

/** Respuesta hablada/escrita a «¿Te refieres a…?»: índice elegido, -1 = ninguna, null = no es una respuesta. */
internal fun parseChoiceAnswer(text: String, labels: List<String>): Int? {
    val t = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .trim('.', '!', ' ', ',')
    if (Regex("^(?:no|ninguna|ninguno|cancela|nada|olvidalo|dejalo)$").matches(t)) return -1
    if (Regex("^(?:si|vale|esa|ese|eso|correcto|exacto|claro|venga|ok|la primera|el primero|primera|primero|1|uno|una)$").matches(t)) return 0
    if (Regex("^(?:la segunda|el segundo|segunda|segundo|2|dos)$").matches(t)) return 1.takeIf { it < labels.size }
    if (Regex("^(?:la tercera|el tercero|tercera|tercero|3|tres)$").matches(t)) return 2.takeIf { it < labels.size }
    // Por nombre: «la de Víctor al trabajo»
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
    /** Lee la respuesta en voz alta (solo si la petición llegó por voz). */
    private val speak: (String) -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    val activeEngine: StateFlow<String> = orchestrator.activeEngine

    private var nextId = 0L
    /** La petición en curso llegó por voz → la respuesta se lee en voz alta (manos libres). */
    private var voiceTurn = false

    init {
        val now = LocalDateTime.now()
        append(
            ChatMessage.Assistant(
                id = nextId++,
                text = "${DueDateFormatter.greeting(now)}. ¿En qué te ayudo? Puedes pedirme que apunte algo, " +
                    "preguntarme qué hacer ahora o decirme que ya terminaste una tarea."
            )
        )
        viewModelScope.launch { orchestrator.refreshActiveEngine() }
    }

    fun send(text: String) = send(text, fromVoice = false)

    private fun send(text: String, fromVoice: Boolean) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isThinking) return
        // ¿Responde a una pregunta de Lumi («¿Qué le digo a Víctor?»)?
        _state.value.followUp?.takeIf { it.slot == "confirm" }?.let { f ->
            // «¿Lo envío?» / «¿Te la pongo?» → sí ejecuta, no cancela, otra cosa se trata como frase nueva
            val answer = parseChoiceAnswer(prompt, listOf("confirmar"))
            _state.update { it.copy(followUp = null) }
            if (answer != null) {
                voiceTurn = fromVoice
                append(ChatMessage.User(nextId++, prompt))
                if (answer < 0) say("Vale, no lo hago.") else run { repository.executeCommand(f.command) }
                return
            }
        }
        _state.value.followUp?.let { f ->
            _state.update { it.copy(followUp = null) }
            voiceTurn = fromVoice
            append(ChatMessage.User(nextId++, prompt))
            val msg = DeviceCommand.parse(f.command.device) as? DeviceCommand.Message
            if (msg == null || Regex("(?iu)^(?:nada|d[eé]jalo|cancela|olv[ií]dalo|no)$").matches(prompt)) {
                append(ChatMessage.Assistant(nextId++, "Vale, no envío nada.")); return
            }
            val filled = when (f.slot) {
                "contact" -> msg.copy(contact = prompt.removePrefix("a ").trim())
                else -> msg.copy(text = prompt.removePrefix("que ").trim().replaceFirstChar { it.uppercase() })
            }
            run { repository.executeCommand(f.command.copy(device = filled.serialize())) }
            return
        }
        // ¿Es la respuesta a «¿Te refieres a…?»? («sí», «la segunda», «no»…)
        val labels = _state.value.optionLabels
        if (labels.isNotEmpty()) {
            val answer = parseChoiceAnswer(prompt, labels)
            if (answer != null) {
                voiceTurn = fromVoice
                append(ChatMessage.User(nextId++, prompt))
                if (answer < 0) {
                    _state.update { it.copy(choice = null, contactChoice = null) }
                    append(ChatMessage.Assistant(nextId++, "Vale, no toco nada."))
                    if (fromVoice) speak("Vale, no toco nada.")
                } else pick(answer, echo = false)
                return
            }
            _state.update { it.copy(choice = null, contactChoice = null) } // otra cosa: se descarta la pregunta
        }
        voiceTurn = fromVoice
        _state.update { it.copy(voiceError = null) }
        append(ChatMessage.User(nextId++, prompt))
        val rewritten = withReplyTarget(prompt)
        if (rewritten.isEmpty()) { say("¿Qué le digo a ${(DeviceCommand.parse(_state.value.followUp?.command?.device) as? DeviceCommand.Message)?.contact.orEmpty()}?"); return }
        run { repository.processNaturalLanguageCommand(rewritten) }
    }

    /**
     * Tras leer los mensajes de alguien: «respóndele que ya voy» / «dile que sí» → «dile a Víctor que ya voy»;
     * «sí» a «¿Le respondo?» → Lumi pregunta qué le dice.
     */
    private fun withReplyTarget(prompt: String): String {
        val target = _state.value.replyTarget ?: return prompt
        _state.update { it.copy(replyTarget = null) }
        val who = target.conversation
        Regex("(?iu)^(?:s[ií],?\\s+)?(?:resp[oó]nde(?:le)?|cont[eé]sta(?:le)?|dile|escr[ií]bele|p[oó]nle)\\s+(?:que\\s+)?(.+)$").find(prompt.trim())?.let { m ->
            if (!Regex("(?iu)^a\\s+").containsMatchIn(m.groupValues[1])) return "dile a $who que ${m.groupValues[1]}"
        }
        if (parseChoiceAnswer(prompt, listOf(who)) == 0 || Regex("(?iu)^(?:resp[oó]nde(?:le)?|cont[eé]sta(?:le)?)$").matches(prompt.trim())) {
            val msg = DeviceCommand.Message(who, "", !target.app.contains("mensaje", true))
            _state.update {
                it.copy(followUp = AIProcessingResult.AskFollowUp(
                    com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand(
                        action = com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand.DEVICE, device = msg.serialize()
                    ), "message", "", ""
                ))
            }
            return ""
        }
        return prompt
    }

    fun planMyDay() = quick("¿Qué hago ahora?") { repository.planMyDay() }
    fun briefing() = quick("¿Cómo voy?") { repository.generateDailyBriefing() }

    fun markRevealed(id: Long) = _state.update { it.copy(revealed = it.revealed + id) }

    fun setVoiceError(message: String?) = _state.update { it.copy(voiceError = message) }

    /** Resultado de voz. Si Lumi se abrió sola por «Oye Lumi», solo se ejecuta directamente si parece una orden. */
    fun sendVoice(text: String, fromWakeWord: Boolean) {
        if (fromWakeWord && !com.antigravity.gemininanotaskmanager.service.wakeword.CommandLikeness.looksLikeCommand(text)) {
            _state.update { it.copy(pendingConfirmation = text) }
        } else send(text, fromVoice = true)
    }

    fun confirmPending() {
        val text = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        send(text, fromVoice = true)
    }

    fun navigationHandled() = _state.update { it.copy(navigateTo = null) }

    /** El usuario eligió la opción [index] (tocándola o respondiendo). */
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
        append(ChatMessage.Assistant(nextId++, "Vale, no toco nada."))
    }

    fun openTaskHandled() = _state.update { it.copy(openTaskId = null) }
    fun deviceHandled() = _state.update { it.copy(device = null, deviceContact = null) }

    /**
     * Rutina: lanza la siguiente acción de la cola. Cuando se acaba, la ruta (si la hay).
     * Devuelve true si aún queda algo por hacer (la Activity no debe cerrarse).
     */
    /** Hay una rutina en marcha: los accesos que falten se mencionan, sin abrir pantallas del sistema a mitad. */
    var routineActive = false
        private set

    fun nextDevice(): Boolean {
        val s = _state.value
        return when {
            s.deviceQueue.isNotEmpty() -> { _state.update { it.copy(device = s.deviceQueue.first(), deviceQueue = s.deviceQueue.drop(1)) }; true }
            s.routineNavigate != null -> { _state.update { it.copy(navigateTo = s.routineNavigate, routineNavigate = null) }; true }
            else -> { routineActive = false; false }
        }
    }

    private val _listenAgain = MutableStateFlow(0)
    /** Cambia cuando Lumi ha contestado a una frase dicha por voz: la Activity vuelve a escuchar al acabar de hablar. */
    val listenAgain: StateFlow<Int> = _listenAgain.asStateFlow()

    /**
     * Botón de un aviso («Escribir a Roberto», «Llamar a mamá»): lanza la acción y da la tarea por hecha.
     */
    fun runTaskAction(command: DeviceCommand, taskId: Long) {
        append(ChatMessage.Assistant(nextId++, when (command) {
            is DeviceCommand.Call -> "Llamando a ${command.contact}."
            is DeviceCommand.Message -> "Te abro el chat con ${command.contact}" + if (command.text.isNotBlank()) " con el mensaje escrito." else "."
            else -> "Hecho."
        }))
        _state.update { it.copy(device = command) }
        if (taskId > 0) viewModelScope.launch {
            repository.getTask(taskId)?.let { repository.updateTask(it.copy(status = com.antigravity.gemininanotaskmanager.domain.model.TaskStatus.COMPLETED)) }
        }
    }

    /** Lumi dice algo y lo lee en voz alta (abierta desde la notificación de la mañana con «Escuchar»). */
    fun sendSpoken(text: String) = send(text, fromVoice = true)
    fun retryDevice(command: DeviceCommand, contact: DeviceActions.Contact? = null) = _state.update { it.copy(device = command, deviceContact = contact) }
    fun askContact(command: DeviceCommand, options: List<DeviceActions.Contact>) {
        _state.update { it.copy(contactChoice = ContactChoice(command, options)) }
        append(ChatMessage.Assistant(nextId++, "Tengo varios. ¿A cuál?"))
        if (voiceTurn) speak("Tengo varios. ¿A cuál?")
    }

    /** Mensaje de Lumi que no viene del repositorio (p.ej. «no encuentro a Ana en tus contactos»). */
    fun say(text: String, isError: Boolean = false) {
        append(ChatMessage.Assistant(nextId++, text, isError = isError))
        if (voiceTurn) speak(text)
    }

    fun discardPending() = _state.update { it.copy(pendingConfirmation = null) }

    private fun quick(label: String, block: suspend () -> AIProcessingResult) {
        if (_state.value.isThinking) return
        voiceTurn = false
        append(ChatMessage.User(nextId++, label))
        run(block)
    }

    private fun run(block: suspend () -> AIProcessingResult) {
        _state.update { it.copy(isThinking = true) }
        viewModelScope.launch {
            val result = runCatching { block() }.getOrElse { AIProcessingResult.Error("Algo ha fallado: ${it.localizedMessage}") }
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
                    // Rutina sin acciones del móvil pero con ruta → directamente a la ruta
                    val r = result as? AIProcessingResult.Routine
                    if (r != null && r.devices.isEmpty() && r.navigate != null) st.copy(navigateTo = r.navigate) else st
                }
            }
            routineActive = result is AIProcessingResult.Routine
            if (voiceTurn) speak(result.reply)
            // Conversación seguida: tras contestar por voz, se vuelve a escuchar (salvo si Lumi se va a otra app)
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
