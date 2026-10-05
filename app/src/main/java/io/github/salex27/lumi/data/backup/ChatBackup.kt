package io.github.salex27.lumi.data.backup

import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Chat sessions in the backup file (format 2). Pure Kotlin + kotlinx.serialization so it is unit-tested (org.json is a
 * stub in JVM tests). Message task ids are NOT exported: tasks get new ids on import.
 */
object ChatBackup {

    @Serializable
    data class Message(
        val role: String, val text: String, val createdAt: Long, val engine: String = "", val isError: Boolean = false,
        val action: String? = null, val agentId: Long? = null, val payload: String? = null,
        /** createdAt of the message's agent (ids change on import; agents are matched by this key). */
        val agentKey: Long? = null
    )

    @Serializable
    data class Session(
        val kind: String, val title: String, val titleCustom: Boolean = false, val createdAt: Long, val updatedAt: Long,
        val summary: String = "", val summaryUntil: Long = 0L, val messages: List<Message> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(sessions: List<ChatSessionEntity>, messages: List<ChatMessageEntity>, agentKeyOf: (Long) -> Long? = { null }): String {
        val bySession = messages.groupBy { it.sessionId }
        return json.encodeToString(sessions.map { s ->
            Session(
                s.kind, s.title, s.titleCustom, s.createdAt, s.updatedAt, s.summary, s.summaryUntil,
                bySession[s.id].orEmpty().map { m -> Message(m.role, m.text, m.createdAt, m.engine, m.isError, m.action, null, m.payload, m.agentId?.let(agentKeyOf)) }
            )
        })
    }

    fun decode(text: String): List<Session> = json.decodeFromString(text)

    /** A session already on the phone (same kind and creation time; it may have been renamed since) is not imported again. */
    fun key(kind: String, createdAt: Long) = "$kind|$createdAt"

    fun toEntity(s: Session) = ChatSessionEntity(
        kind = s.kind, title = s.title, titleCustom = s.titleCustom, createdAt = s.createdAt, updatedAt = s.updatedAt,
        summary = s.summary, summaryUntil = s.summaryUntil
    )

    fun toEntity(m: Message, sessionId: Long, agentIdOf: (Long) -> Long? = { null }) = ChatMessageEntity(
        sessionId = sessionId, role = m.role, text = m.text, createdAt = m.createdAt, engine = m.engine, isError = m.isError,
        action = m.action, agentId = m.agentKey?.let(agentIdOf), payload = m.payload
    )

    /** An Orbit agent in the backup, with the Orbits it belongs to (by their createdAt). */
    @Serializable
    data class Agent(
        val name: String, val backend: String, val color: String, val face: String, val purpose: String = "",
        val canReadTasks: Boolean = false, val createdAt: Long, val config: String = "", val orbits: List<Long> = emptyList()
    )

    fun encodeAgents(agents: List<io.github.salex27.lumi.data.local.AgentEntity>, members: List<io.github.salex27.lumi.data.local.OrbitMemberEntity>, sessions: List<ChatSessionEntity>): String {
        val created = sessions.associate { it.id to it.createdAt }
        return json.encodeToString(agents.map { a ->
            Agent(a.name, a.backend, a.color, a.face, a.purpose, a.canReadTasks, a.createdAt, a.config,
                members.filter { it.agentId == a.id }.mapNotNull { created[it.sessionId] })
        })
    }

    fun decodeAgents(text: String): List<Agent> = json.decodeFromString(text)

    fun toEntity(a: Agent) = io.github.salex27.lumi.data.local.AgentEntity(
        name = a.name, backend = a.backend, color = a.color, face = a.face, purpose = a.purpose, canReadTasks = a.canReadTasks,
        createdAt = a.createdAt, config = a.config
    )
}
