package io.github.salex27.lumi.presentation.orbit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.salex27.lumi.data.hub.HubInbox
import io.github.salex27.lumi.data.hub.HubSettings
import io.github.salex27.lumi.data.local.AgentEntity
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.data.local.ChatSessionRow
import io.github.salex27.lumi.data.orbit.OrbitRepository
import io.github.salex27.lumi.domain.orbit.AgentBackendKind
import io.github.salex27.lumi.domain.orbit.AgentFaceStyle
import io.github.salex27.lumi.domain.orbit.AgentPalette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Orbit screens: the list of Orbits / agent inboxes / agents, and the open thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrbitViewModel(
    private val repo: OrbitRepository,
    private val inbox: HubInbox,
    hubSettings: HubSettings
) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val orbits: StateFlow<List<ChatSessionRow>> = repo.observeOrbits().state(emptyList())
    val inboxes: StateFlow<List<ChatSessionRow>> = repo.observeInboxes().state(emptyList())
    val agents: StateFlow<List<AgentEntity>> = repo.observeAgents().state(emptyList())
    val hubConfigured: StateFlow<Boolean> = MutableStateFlow(hubSettings.config.value.isConfigured).also { s ->
        viewModelScope.launch { hubSettings.config.collect { s.value = it.isConfigured } }
    }

    private val _open = MutableStateFlow<Long?>(null)
    /** The thread on screen (an Orbit or an agent inbox), null = the list. */
    val open: StateFlow<Long?> = _open.asStateFlow()
    val session: StateFlow<ChatSessionEntity?> = _open.flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeSession(id) }.state(null)
    val messages: StateFlow<List<ChatMessageEntity>> = _open.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeMessages(id) }.state(emptyList())
    val members: StateFlow<List<AgentEntity>> = _open.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeMembers(id) }.state(emptyList())

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    enum class Notice { QUESTION_CLOSED, HUB_UNREACHABLE, TASK_ADDED }

    fun openThread(id: Long?) { _open.value = id }

    fun chatWithClaude() = viewModelScope.launch { _open.value = repo.chatWithClaude() }

    fun createOrbit(title: String, agentIds: List<Long>) = viewModelScope.launch { _open.value = repo.createOrbit(title, agentIds) }

    fun renameThread(id: Long, title: String) = repo.rename(id, title)

    fun deleteThread(id: Long) {
        if (_open.value == id) _open.value = null
        repo.delete(id)
    }

    fun saveAgent(existing: AgentEntity?, kind: AgentBackendKind, name: String, color: AgentPalette, face: AgentFaceStyle, purpose: String) =
        viewModelScope.launch {
            if (existing == null) repo.createAgent(kind, name, color, face, purpose)
            else repo.updateAgent(existing.copy(name = name, color = color.name, face = face.name, purpose = purpose))
        }

    fun deleteAgent(id: Long) = viewModelScope.launch { repo.deleteAgent(id) }

    fun addMember(agentId: Long) { val id = _open.value ?: return; viewModelScope.launch { repo.addMember(id, agentId) } }
    fun removeMember(agentId: Long) { val id = _open.value ?: return; viewModelScope.launch { repo.removeMember(id, agentId) } }

    fun send(text: String) {
        val id = _open.value ?: return
        val s = session.value
        if (s?.kind == ChatSessionEntity.KIND_HUB) {
            // In an agent's inbox the box answers its open question
            val ask = messages.value.lastOrNull { OrbitUi.openAsk(it) }
            if (ask != null) answer(ask.id, text)
            return
        }
        viewModelScope.launch { repo.send(id, text) }
    }

    /** "Second opinion" on [message]: the user question it answered goes to [agentId]. */
    fun secondOpinion(message: ChatMessageEntity, agentId: Long) {
        val id = _open.value ?: return
        val question = messages.value.lastOrNull { it.id < message.id && it.role == ChatMessageEntity.ROLE_USER }?.text ?: return
        viewModelScope.launch { repo.secondOpinion(id, question, agentId) }
    }

    fun answer(messageId: Long, text: String) = viewModelScope.launch { report(inbox.answerAsk(messageId, text)) }
    fun acceptTask(messageId: Long) = viewModelScope.launch { report(inbox.acceptTask(messageId), added = true) }
    fun dismissTask(messageId: Long) = viewModelScope.launch { report(inbox.dismissTask(messageId)) }

    private fun report(outcome: HubInbox.Outcome, added: Boolean = false) {
        when (outcome) {
            HubInbox.Outcome.Closed -> _notices.tryEmit(Notice.QUESTION_CLOSED)
            is HubInbox.Outcome.Failed -> _notices.tryEmit(Notice.HUB_UNREACHABLE)
            HubInbox.Outcome.Done -> if (added) _notices.tryEmit(Notice.TASK_ADDED)
        }
    }

    class Factory(private val repo: OrbitRepository, private val inbox: HubInbox, private val hub: HubSettings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OrbitViewModel(repo, inbox, hub) as T
    }
}
