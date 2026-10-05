package io.github.salex27.lumi.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A conversation (v8). The overlay pill and the chat inside the app share the same sessions; Orbit threads (kind
 * ORBIT) reuse the same tables. [summary] is the rolling summary of the turns up to [summaryUntil] (the createdAt of
 * the last message folded into it), so only the newer turns are sent to the local model.
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "ASSISTANT" (Lumi's chat) | "ORBIT" (group thread) | "HUB" (messages from PC agents). */
    @ColumnInfo(name = "kind") val kind: String = KIND_ASSISTANT,
    @ColumnInfo(name = "title") val title: String,
    /** The user renamed it: the automatic title no longer touches it. */
    @ColumnInfo(name = "title_custom") val titleCustom: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "summary") val summary: String = "",
    @ColumnInfo(name = "summary_until") val summaryUntil: Long = 0L
) {
    companion object {
        const val KIND_ASSISTANT = "ASSISTANT"
        const val KIND_ORBIT = "ORBIT"
        const val KIND_HUB = "HUB"
    }
}

/**
 * One message of a session. [taskIds] are the tasks the turn created or touched (comma separated) so follow-ups
 * ("move it to Friday") still work after resuming; [payload] is JSON for attachments (sources, links, proposals).
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    /** "USER" | "ASSISTANT" (Lumi) | "AGENT" (an Orbit agent, see [agentId]) */
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "engine") val engine: String = "",
    @ColumnInfo(name = "is_error") val isError: Boolean = false,
    /** Action of the command this reply executed (TaskAICommand constant), for follow-ups. */
    @ColumnInfo(name = "action") val action: String? = null,
    @ColumnInfo(name = "task_ids") val taskIds: String = "",
    @ColumnInfo(name = "agent_id") val agentId: Long? = null,
    @ColumnInfo(name = "payload") val payload: String? = null
) {
    val taskIdList: List<Long> get() = taskIds.split(',').mapNotNull { it.trim().toLongOrNull() }

    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val ROLE_AGENT = "AGENT"

        fun joinIds(ids: List<Long>): String = ids.filter { it > 0 }.distinct().joinToString(",")
    }
}

/** A session with its last message, for the session list. */
data class ChatSessionRow(
    @Embedded val session: ChatSessionEntity,
    @ColumnInfo(name = "preview") val preview: String?,
    @ColumnInfo(name = "message_count") val messageCount: Int
)

@Dao
interface ChatDao {
    @Query(
        "SELECT s.*, (SELECT m.text FROM chat_messages m WHERE m.session_id = s.id ORDER BY m.id DESC LIMIT 1) AS preview, " +
            "(SELECT COUNT(*) FROM chat_messages m WHERE m.session_id = s.id) AS message_count " +
            "FROM chat_sessions s WHERE s.kind = :kind ORDER BY s.updated_at DESC"
    )
    fun observeSessions(kind: String): Flow<List<ChatSessionRow>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun session(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_sessions WHERE kind = :kind ORDER BY updated_at DESC LIMIT 1")
    suspend fun latest(kind: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY id")
    suspend fun allSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE kind = :kind AND title = :title ORDER BY id DESC LIMIT 1")
    suspend fun sessionNamed(kind: String, title: String): ChatSessionEntity?

    /** Newest message whose JSON payload contains [fragment] (e.g. a Hub question id). */
    @Query("SELECT * FROM chat_messages WHERE payload LIKE '%' || :fragment || '%' ORDER BY id DESC LIMIT 1")
    suspend fun messageWithPayload(fragment: String): ChatMessageEntity?

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("UPDATE chat_sessions SET title = :title, title_custom = :custom WHERE id = :id")
    suspend fun setTitle(id: Long, title: String, custom: Boolean)

    @Query("UPDATE chat_sessions SET updated_at = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE chat_sessions SET summary = :summary, summary_until = :until WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, until: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessages(sessionId: Long)

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY id")
    suspend fun messages(sessionId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY id")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun message(id: Long): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages ORDER BY id")
    suspend fun allMessages(): List<ChatMessageEntity>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET text = :text, payload = :payload, is_error = :isError WHERE id = :id")
    suspend fun updateMessage(id: Long, text: String, payload: String?, isError: Boolean)

    @Query("UPDATE chat_messages SET engine = :engine WHERE id = :id")
    suspend fun setEngine(id: Long, engine: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)
}
