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
import io.github.salex27.lumi.domain.orbit.LeaderAgent
import io.github.salex27.lumi.domain.orbit.OrbitLeader
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
    private val scope: CoroutineScope,
    /** The leader's memory of the user's routing choices, and which Orbits route on their own. */
    val routing: RoutingMemory? = null,
    /** (system, user, maxTokens) → Lumi's brain, for the leader's pick when the rules have no opinion. */
    private val ask: suspend (String, String, Int) -> String? = { _, _, _ -> null },
    /** Titles of today's open tasks, offered to agents with can_read_tasks. */
    private val todayTasks: suspend () -> List<String> = { emptyList() }
) {
    /** Message rows of one streamed turn are created/updated under this lock (send and the event stream race). */
    private val turns = Mutex()

    fun observeOrbits(): Flow<List<ChatSessionRow>> = store.observeSessions(ChatSessionEntity.KIND_ORBIT)
    fun observeInboxes(): Flow<List<ChatSessionRow>> = store.observeSessions(ChatSessionEntity.KIND_HUB)
    fun observeAllChats(): Flow<List<ChatSessionRow>> = store.observeAllSessions()
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

    /**
     * "New chat" with an agent: ALWAYS a fresh 1:1 thread (own row in Chats, own Claude session), titled from its first
     * message. [agentId] null = the Claude Code agent (created if missing). Older threads stay untouched.
     */
    suspend fun newChat(agentId: Long?): Long {
        val agent = agentId?.let { dao.agent(it) }
            ?: dao.agents().firstOrNull { it.backend == AgentBackendKind.CLAUDE_PC.name }
            ?: dao.agent(createAgent(AgentBackendKind.CLAUDE_PC))!!
        val now = store.stamp()
        val id = chatDao.insertSession(ChatSessionEntity(kind = ChatSessionEntity.KIND_ORBIT, title = "", titleCustom = false, createdAt = now, updatedAt = now))
        dao.addMember(OrbitMemberEntity(id, agent.id))
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
        when {
            !route.toLumi -> route.agents.forEach { a -> members.firstOrNull { it.id == a.id }?.let { ask(sessionId, it, route.text) } }
            members.isEmpty() -> answerAsLumi(sessionId, route.text)
            else -> lead(sessionId, route.text, members)
        }
    }

    // ── Lumi as the team leader (#5) ─────────────────────────────────────────────────────────────────────────

    /**
     * A group message without a mention: Lumi decides who should answer. It answers itself, or names the agent and
     * why, and either hands the message over (Orbit on automatic) or asks first (default). Always visible.
     */
    private suspend fun lead(sessionId: Long, text: String, members: List<AgentEntity>) {
        val agents = members.map { LeaderAgent(it.id, it.name, AgentBackendKind.of(it.backend), it.purpose) }
        val examples = routing?.examples?.value.orEmpty()
        // A short reply right after an agent asked a question belongs to that agent ("barcelona")
        val last = lines(sessionId).dropLast(1).lastOrNull() // the user's own message is already stored
        val followUp = last?.agentId?.let { id -> members.firstOrNull { it.id == id } }
            ?.takeIf { OrbitLeader.isFollowUp(text, last.text, true) }
        if (followUp != null) { ask(sessionId, followUp, text); return }
        var decision = OrbitLeader.decide(text, agents, examples)
        // Only when the rules have no opinion, and only for real requests: Lumi's brain picks (or keeps it)
        if (decision.why == OrbitLeader.Why.NONE && text.trim().split(Regex("\\s+")).size >= 4) {
            val (system, user) = OrbitLeader.llmPrompt(text, agents)
            val pick = runCatching { ask(system, user, 12) }.getOrNull()
            decision = OrbitLeader.decide(text, agents, examples, pick)
        }
        val agent = decision.agent?.let { d -> members.firstOrNull { it.id == d.id } }
        if (agent == null) { answerAsLumi(sessionId, text); return }
        val reason = reason(decision.why)
        val choices = listOf(agent.id) + members.filter { it.id != agent.id }.map { it.id } + LUMI_CHOICE
        val auto = chatDao.session(sessionId)?.let { routing?.isAuto(it.createdAt) } == true
        val note = if (auto) ReplyLanguage.t("Se lo paso a ${agent.name}: $reason.", "Passing this to ${agent.name}: $reason.")
        else ReplyLanguage.t("¿Se lo paso a ${agent.name}? $reason.", "Shall I pass this to ${agent.name}? ${reason.replaceFirstChar { it.uppercase() }}.")
        appendAndWait(sessionId, ChatMessageEntity(
            sessionId = sessionId, role = ChatMessageEntity.ROLE_ASSISTANT, text = note, createdAt = store.stamp(),
            payload = HubPayload(HubPayload.ROUTE, routeTo = agent.id, choices = choices, question = text.take(2_000),
                state = if (auto) STATE_DONE else HubPayload.STATE_OPEN).encode()
        ))
        if (auto) ask(sessionId, agent, text)
    }

    /**
     * The user picked who answers a leader message: [agentId] (or [LUMI_CHOICE]). A proposal is closed; on a hand-off
     * that already happened it is a redirect. Either way the choice is remembered for similar messages.
     */
    suspend fun pickRoute(messageId: Long, agentId: Long) {
        store.flush()
        val m = store.message(messageId) ?: return
        val p = HubPayload.decode(m.payload)?.takeIf { it.hub == HubPayload.ROUTE } ?: return
        if (p.state == HubPayload.STATE_CLOSED || agentId !in p.choices) return
        val question = p.question ?: return
        val agent = if (agentId == LUMI_CHOICE) null else dao.members(m.sessionId).firstOrNull { it.id == agentId }
        if (agentId != LUMI_CHOICE && agent == null) return
        store.update(m.id, m.text, p.copy(state = HubPayload.STATE_CLOSED, answer = agent?.name ?: "Lumi").encode())
        routing?.remember(question, agent?.name ?: OrbitLeader.LUMI)
        // On automatic the first agent is already answering: only a different pick re-asks
        if (p.state == STATE_DONE && agentId == p.routeTo) return
        if (agent == null) answerAsLumi(m.sessionId, question) else ask(m.sessionId, agent, question)
    }

    private fun reason(why: OrbitLeader.Why): String = when (why) {
        OrbitLeader.Why.LEARNED -> ReplyLanguage.t("como elegiste la otra vez", "like you chose last time")
        OrbitLeader.Why.PURPOSE -> ReplyLanguage.t("encaja con su papel", "it fits its role")
        OrbitLeader.Why.CODE -> ReplyLanguage.t("es trabajo de código en tu PC", "it's code work on your PC")
        OrbitLeader.Why.COMPLEX -> ReplyLanguage.t("es un encargo grande para mí", "it's a big job for me")
        OrbitLeader.Why.WEB -> ReplyLanguage.t("necesita buscar en la web", "it needs a web search")
        OrbitLeader.Why.FOLLOW_UP, OrbitLeader.Why.LLM, OrbitLeader.Why.NONE -> ReplyLanguage.t("parece lo suyo", "it looks like the best fit")
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
        val context = if (kind == AgentBackendKind.CLAUDE_PC) {
            val header = OrbitContext.header(java.time.LocalDateTime.now(), if (ReplyLanguage.current == io.github.salex27.lumi.domain.assistant.Lang.ES) "Spanish" else "English",
                if (agent.canReadTasks) runCatching { todayTasks() }.getOrNull() else null)
            header + "\n" + OrbitContext.sinceLastReply(lines, agent.id)
        } else OrbitContext.recent(lines)
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
        /** "Lumi answers" among a leader message's choices. */
        const val LUMI_CHOICE = 0L
        private const val TAG = "LumiOrbit"
    }
}
