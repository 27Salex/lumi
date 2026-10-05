package io.github.salex27.lumi.data.orbit

import android.util.Log
import io.github.salex27.lumi.data.chat.ChatStore
import io.github.salex27.lumi.data.hub.HubEvent
import io.github.salex27.lumi.data.hub.HubPayload
import io.github.salex27.lumi.data.local.AgentEntity
import io.github.salex27.lumi.data.local.ChatDao
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.data.local.ChatSessionRow
import io.github.salex27.lumi.data.local.OrbitDao
import io.github.salex27.lumi.data.local.OrbitMemberEntity
import io.github.salex27.lumi.domain.assistant.LanguageDetector
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.orbit.AgentBackendKind
import io.github.salex27.lumi.domain.orbit.AgentFaceStyle
import io.github.salex27.lumi.domain.orbit.AgentPalette
import io.github.salex27.lumi.domain.orbit.OrbitAgent
import io.github.salex27.lumi.domain.orbit.OrbitContext
import io.github.salex27.lumi.domain.orbit.OrbitLine
import io.github.salex27.lumi.domain.orbit.OrbitRouter
import io.github.salex27.lumi.domain.orbit.OrbitThreads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orbit: group threads (chat sessions of kind ORBIT) with AI agents. Routing v1 is explicit (`@Name`, see
 * [OrbitRouter]); each agent's backend answers either at once (Lumi's brain) or by streaming Hub `reply` events
 * (Claude Code on the PC), which are folded into one message per turn.
 *
 * Agent output is plain text in the thread: nothing an agent says is executed.
 */
class OrbitRepository(
    private val store: ChatStore,
    private val chatDao: ChatDao,
    private val dao: OrbitDao,
    private val backends: AgentBackends,
    private val scope: CoroutineScope
) {
    /** Message rows of one streamed turn are created/updated under this lock (send and the event stream race). */
    private val turns = Mutex()

    fun observeOrbits(): Flow<List<ChatSessionRow>> = store.observeSessions(ChatSessionEntity.KIND_ORBIT)
    fun observeInboxes(): Flow<List<ChatSessionRow>> = store.observeSessions(ChatSessionEntity.KIND_HUB)
    fun observeAgents(): Flow<List<AgentEntity>> = dao.observeAgents()
    fun observeMembers(sessionId: Long): Flow<List<AgentEntity>> = dao.observeMembers(sessionId)
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> = chatDao.observeMessages(sessionId)
    fun observeSession(sessionId: Long): Flow<ChatSessionEntity?> = chatDao.observeSession(sessionId)

    /** Folds Hub replies into their messages. Started once by the app. */
    fun start(replies: SharedFlow<HubEvent>) {
        scope.launch { replies.collect { e -> runCatching { onReply(e) }.onFailure { Log.w(TAG, "reply", it) } } }
    }

    // ── Agents and Orbits ────────────────────────────────────────────────────────────────────────────────────

    suspend fun createAgent(
        kind: AgentBackendKind, name: String = kind.defaultName, color: AgentPalette = kind.defaultColor,
        face: AgentFaceStyle = kind.defaultFace, purpose: String = ""
    ): Long = dao.insertAgent(
        AgentEntity(name = cleanName(name, kind.defaultName), backend = kind.name, color = color.name, face = face.name, purpose = purpose.trim().take(1_000))
    )

    suspend fun updateAgent(agent: AgentEntity) = dao.updateAgent(agent.copy(name = cleanName(agent.name, agent.name), purpose = agent.purpose.trim().take(1_000)))
    suspend fun deleteAgent(id: Long) = dao.deleteAgent(id)

    suspend fun createOrbit(title: String, agentIds: List<Long>): Long {
        val now = store.stamp()
        val id = chatDao.insertSession(
            ChatSessionEntity(kind = ChatSessionEntity.KIND_ORBIT, title = title.trim().take(80).ifBlank { "Orbit" }, titleCustom = true, createdAt = now, updatedAt = now)
        )
        agentIds.distinct().forEach { dao.addMember(OrbitMemberEntity(id, it)) }
        return id
    }

    suspend fun addMember(sessionId: Long, agentId: Long) = dao.addMember(OrbitMemberEntity(sessionId, agentId))
    suspend fun removeMember(sessionId: Long, agentId: Long) = dao.removeMember(sessionId, agentId)

    fun rename(sessionId: Long, title: String) = store.rename(sessionId, title)
    fun delete(sessionId: Long) = store.delete(sessionId)

    /**
     * "Chat with Claude" in one step: the Claude Code agent (created if missing) and its 1:1 Orbit (reused if it
     * exists). It behaves like a normal chat: no mention needed. Returns the Orbit id.
     */
    suspend fun chatWithClaude(): Long {
        val agent = dao.agents().firstOrNull { it.backend == AgentBackendKind.CLAUDE_PC.name }
            ?: dao.agent(createAgent(AgentBackendKind.CLAUDE_PC))!!
        store.flush()
        val existing = chatDao.allSessions().filter { it.kind == ChatSessionEntity.KIND_ORBIT }
            .lastOrNull { s -> dao.members(s.id).map { it.id } == listOf(agent.id) }
        return existing?.id ?: createOrbit(agent.name, listOf(agent.id))
    }

    // ── Messages ───────────────────────────────────────────────────────────────────────────────────────────────

    /** The user writes in an Orbit: stored, routed, and the chosen agents (or Lumi) start answering. */
    suspend fun send(sessionId: Long, text: String) {
        val clean = text.trim().take(4_000).ifBlank { return }
        ReplyLanguage.current = LanguageDetector.detect(clean, ReplyLanguage.current)
        val members = dao.members(sessionId)
        appendAndWait(sessionId, ChatMessageEntity(sessionId = sessionId, role = ChatMessageEntity.ROLE_USER, text = clean, createdAt = store.stamp()))
        val route = OrbitRouter.route(clean, members.map { OrbitAgent(it.id, it.name) })
        if (route.toLumi) answerAsLumi(sessionId, route.text)
        else route.agents.forEach { a -> members.firstOrNull { it.id == a.id }?.let { ask(sessionId, it, route.text) } }
    }

    /** "Second opinion": the same question goes to another agent of the Orbit. */
    suspend fun secondOpinion(sessionId: Long, question: String, agentId: Long) {
        val agent = dao.members(sessionId).firstOrNull { it.id == agentId } ?: return
        appendAndWait(sessionId, ChatMessageEntity(
            sessionId = sessionId, role = ChatMessageEntity.ROLE_USER, createdAt = store.stamp(),
            text = "@${agent.name} " + ReplyLanguage.ui("¿qué opinas tú?", "what's your take?") + "\n«${question.take(600)}»"
        ))
        ask(sessionId, agent, question)
    }

    private suspend fun ask(sessionId: Long, agent: AgentEntity, text: String) {
        val kind = AgentBackendKind.of(agent.backend)
        val backend = backends.of(kind)
        val lines = lines(sessionId)
        val context = if (kind == AgentBackendKind.CLAUDE_PC) OrbitContext.sinceLastReply(lines, agent.id) else OrbitContext.recent(lines)
        val placeholder = appendAndWait(sessionId, agentMessage(sessionId, agent.id, "", HubPayload(HubEvent.REPLY, state = HubPayload.STATE_OPEN)))
        val result = backend?.start(AgentRequest(agent, text, context, OrbitThreads.id(sessionId, agent.id)))
            ?: AgentStart.Failed(ReplyLanguage.ui("Este agente aún no está disponible.", "This agent isn't available yet."))
        turns.withLock {
            store.flush()
            when (result) {
                is AgentStart.Reply -> store.update(placeholder, result.text, HubPayload(HubEvent.REPLY, state = STATE_DONE).encode())
                is AgentStart.Failed -> store.update(placeholder, result.reason, HubPayload(HubEvent.REPLY, state = STATE_DONE).encode(), isError = true)
                is AgentStart.Streaming -> {
                    // A reply event may already have created the turn's row: then the placeholder goes away
                    val early = store.messageWithPayload(HubPayload.turnFragment(result.turn))
                    if (early != null && early.id != placeholder) store.deleteMessage(placeholder)
                    else store.update(placeholder, "", HubPayload(HubEvent.REPLY, turn = result.turn, state = HubPayload.STATE_OPEN).encode())
                }
            }
        }
    }

    /** Lumi answers in the Orbit with its brain (conversation only: tasks and phone actions stay in the assistant). */
    private suspend fun answerAsLumi(sessionId: Long, text: String) {
        val lumi = AgentEntity(id = 0, name = "Lumi", backend = AgentBackendKind.LUMI.name, color = AgentPalette.SKY.name, face = AgentFaceStyle.DOTS.name)
        val placeholder = appendAndWait(sessionId, ChatMessageEntity(
            sessionId = sessionId, role = ChatMessageEntity.ROLE_ASSISTANT, text = "", createdAt = store.stamp(),
            payload = HubPayload(HubEvent.REPLY, state = HubPayload.STATE_OPEN).encode()
        ))
        val result = backends.of(AgentBackendKind.LUMI)?.start(AgentRequest(lumi, text, OrbitContext.recent(lines(sessionId)), ""))
        val done = HubPayload(HubEvent.REPLY, state = STATE_DONE).encode()
        when (result) {
            is AgentStart.Reply -> store.updateWithEngine(placeholder, result.text, done, result.engine)
            is AgentStart.Failed -> store.update(placeholder, result.reason, done, isError = true)
            else -> store.update(placeholder, "…", done, isError = true)
        }
    }

    private suspend fun onReply(e: HubEvent) {
        val (sessionId, agentId) = OrbitThreads.parse(e.thread) ?: return
        val turn = e.turn ?: return
        turns.withLock {
            store.flush()
            val payload = HubPayload(HubEvent.REPLY, turn = turn, state = if (e.done) STATE_DONE else HubPayload.STATE_OPEN).encode()
            val text = when {
                e.error != null && e.text.isNullOrBlank() -> ReplyLanguage.ui("Claude no ha podido responder: ", "Claude couldn't answer: ") + e.error
                else -> e.text.orEmpty()
            }
            val existing = store.messageWithPayload(HubPayload.turnFragment(turn))
            if (existing != null) {
                if (HubPayload.decode(existing.payload)?.state == STATE_DONE) return@withLock // a late partial after the end
                store.update(existing.id, text, payload, isError = e.error != null && e.done)
            } else if (chatDao.session(sessionId) != null) {
                store.append(ChatStore.Handle(sessionId, ChatSessionEntity.KIND_ORBIT), agentMessage(sessionId, agentId, text, null).copy(payload = payload, isError = e.error != null && e.done))
            }
            store.flush()
        }
    }

    private fun agentMessage(sessionId: Long, agentId: Long, text: String, payload: HubPayload?) = ChatMessageEntity(
        sessionId = sessionId, role = ChatMessageEntity.ROLE_AGENT, text = text, createdAt = store.stamp(), agentId = agentId,
        payload = payload?.encode()
    )

    private suspend fun appendAndWait(sessionId: Long, message: ChatMessageEntity): Long {
        val id = CompletableDeferred<Long>()
        store.append(ChatStore.Handle(sessionId, ChatSessionEntity.KIND_ORBIT), message) { id.complete(it) }
        return id.await()
    }

    private suspend fun lines(sessionId: Long): List<OrbitLine> {
        store.flush()
        val agents = dao.agents().associateBy { it.id }
        return chatDao.messages(sessionId).filter { it.text.isNotBlank() && !it.isError }.map { m ->
            when (m.role) {
                ChatMessageEntity.ROLE_USER -> OrbitLine("User", m.text, isUser = true)
                ChatMessageEntity.ROLE_AGENT -> OrbitLine(agents[m.agentId]?.name ?: m.engine.ifBlank { "Agent" }, m.text, m.agentId)
                else -> OrbitLine("Lumi", m.text)
            }
        }
    }

    private fun cleanName(name: String, fallback: String) =
        name.replace(Regex("[@\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(30).ifBlank { fallback }

    companion object {
        const val STATE_DONE = "done"
        private const val TAG = "LumiOrbit"
    }
}
