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
        val action: String? = null, val agentId: Long? = null, val payload: String? = null
    )

    @Serializable
    data class Session(
        val kind: String, val title: String, val titleCustom: Boolean = false, val createdAt: Long, val updatedAt: Long,
        val summary: String = "", val summaryUntil: Long = 0L, val messages: List<Message> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(sessions: List<ChatSessionEntity>, messages: List<ChatMessageEntity>): String {
        val bySession = messages.groupBy { it.sessionId }
        return json.encodeToString(sessions.map { s ->
            Session(
                s.kind, s.title, s.titleCustom, s.createdAt, s.updatedAt, s.summary, s.summaryUntil,
                bySession[s.id].orEmpty().map { m -> Message(m.role, m.text, m.createdAt, m.engine, m.isError, m.action, m.agentId, m.payload) }
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

    fun toEntity(m: Message, sessionId: Long) = ChatMessageEntity(
        sessionId = sessionId, role = m.role, text = m.text, createdAt = m.createdAt, engine = m.engine, isError = m.isError,
        action = m.action, agentId = m.agentId, payload = m.payload
    )
}
