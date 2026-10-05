package io.github.salex27.lumi.data.chat

import android.content.Context
import io.github.salex27.lumi.data.local.ChatDao
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.data.local.ChatSessionRow
import io.github.salex27.lumi.domain.chat.SessionPolicy
import io.github.salex27.lumi.domain.chat.SessionTurn
import io.github.salex27.lumi.domain.chat.SessionTurns
import io.github.salex27.lumi.domain.chat.StoredMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Chat sessions on Room (v8). The overlay pill and the chat inside the app write through the same store, so a
 * conversation started from the side button can be continued from the app.
 *
 * Writes go through one queue consumed in the app scope: they keep their order, and they finish even if the Activity
 * that started them closes right away (the pill often closes after a phone action).
 */
class ChatStore(context: Context, private val dao: ChatDao, scope: CoroutineScope) {

    /** The session a screen writes to. [sessionId] is null until its first message creates it. */
    class Handle(@Volatile var sessionId: Long? = null, val kind: String = ChatSessionEntity.KIND_ASSISTANT)

    private val prefs = context.getSharedPreferences("chat_sessions", Context.MODE_PRIVATE)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var lastStamp = 0L

    init {
        scope.launch { for (op in queue) runCatching { op() } }
    }

    /** Strictly increasing timestamps: turn rebuilding and the summary marker compare them. */
    @Synchronized
    fun stamp(): Long {
        val now = System.currentTimeMillis()
        lastStamp = if (now > lastStamp) now else lastStamp + 1
        return lastStamp
    }

    fun observeSessions(kind: String = ChatSessionEntity.KIND_ASSISTANT): Flow<List<ChatSessionRow>> = dao.observeSessions(kind)

    fun observeAllSessions(): Flow<List<ChatSessionRow>> = dao.observeAllSessions()

    suspend fun session(id: Long): ChatSessionEntity? = dao.session(id)
    suspend fun messages(sessionId: Long): List<ChatMessageEntity> = dao.messages(sessionId)

    /**
     * The session the assistant should show when it opens: the one the user last picked (or the newest), unless it went
     * stale (long idle gap) or the user asked for a new chat → null (a new session starts with the first message).
     */
    suspend fun sessionToResume(now: Long = System.currentTimeMillis()): ChatSessionEntity? {
        val active = prefs.getLong(K_ACTIVE, -1L)
        if (active == 0L) return null // "New chat" was tapped
        // Picked in the Chats screen: it opens even if it is old (consumed once)
        if (active > 0 && prefs.getBoolean(K_FORCED, false)) {
            prefs.edit().putBoolean(K_FORCED, false).apply()
            dao.session(active)?.let { return it }
        }
        val candidate = (if (active > 0) dao.session(active) else null) ?: dao.latest(ChatSessionEntity.KIND_ASSISTANT) ?: return null
        return candidate.takeIf { !SessionPolicy.isStale(it.updatedAt, now) }
    }

    /** The Chats screen picked [sessionId]: the assistant opens exactly that one next, however old it is. */
    fun requestResume(sessionId: Long) = prefs.edit().putLong(K_ACTIVE, sessionId).putBoolean(K_FORCED, true).apply()

    /** Remembers the session on screen (0 = a new, still empty chat) so the next opening resumes it. */
    fun setActive(sessionId: Long?) = prefs.edit().putLong(K_ACTIVE, sessionId ?: 0L).apply()

    /**
     * Appends a message (ordered, in the background). The first message creates the session; the first user message
     * names it. [onSaved] gets the row id.
     */
    fun append(handle: Handle, message: ChatMessageEntity, onSaved: (Long) -> Unit = {}) {
        queue.trySend {
            val id = handle.sessionId ?: dao.insertSession(
                ChatSessionEntity(kind = handle.kind, title = "", createdAt = message.createdAt, updatedAt = message.createdAt)
            ).also {
                handle.sessionId = it
                if (handle.kind == ChatSessionEntity.KIND_ASSISTANT) setActive(it)
            }
            val rowId = dao.insertMessage(message.copy(sessionId = id))
            dao.touch(id, message.createdAt)
            if (message.role == ChatMessageEntity.ROLE_USER) {
                val s = dao.session(id)
                if (s != null && !s.titleCustom && s.title.isBlank()) dao.setTitle(id, SessionPolicy.titleFrom(message.text), false)
            }
            onSaved(rowId)
        }
    }

    /**
     * Appends to the session of [kind] named [title], created on first use (Hub messages are grouped per agent). The
     * name is matched exactly; a session the user renamed starts a new one on the next message.
     */
    fun appendNamed(kind: String, title: String, message: ChatMessageEntity, onSaved: suspend (Long) -> Unit = {}) {
        queue.trySend {
            val id = dao.sessionNamed(kind, title)?.id ?: dao.insertSession(
                ChatSessionEntity(kind = kind, title = title, createdAt = message.createdAt, updatedAt = message.createdAt)
            )
            val rowId = dao.insertMessage(message.copy(sessionId = id))
            dao.touch(id, message.createdAt)
            onSaved(rowId)
        }
    }

    suspend fun message(id: Long): ChatMessageEntity? = dao.message(id)
    suspend fun messageWithPayload(fragment: String): ChatMessageEntity? = dao.messageWithPayload(fragment)

    /** Updates a message in place (a streamed agent reply). */
    fun update(messageId: Long, text: String, payload: String?, isError: Boolean = false) {
        queue.trySend { dao.updateMessage(messageId, text, payload, isError) }
    }

    fun updateWithEngine(messageId: Long, text: String, payload: String?, engine: String) {
        queue.trySend { dao.updateMessage(messageId, text, payload, false); dao.setEngine(messageId, engine) }
    }

    fun deleteMessage(messageId: Long) {
        queue.trySend { dao.deleteMessage(messageId) }
    }

    fun rename(sessionId: Long, title: String) {
        val clean = title.trim()
        queue.trySend { if (clean.isNotEmpty()) dao.setTitle(sessionId, clean.take(80), true) }
    }

    fun delete(sessionId: Long) {
        if (prefs.getLong(K_ACTIVE, -1L) == sessionId) setActive(null)
        queue.trySend { dao.deleteMessages(sessionId); dao.deleteSession(sessionId) }
    }

    fun saveSummary(sessionId: Long, summary: String, until: Long) {
        queue.trySend { dao.setSummary(sessionId, summary, until) }
    }

    /** Waits until every queued write is done (tests, backup export). */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        queue.trySend { done.complete(Unit) }
        done.await()
    }

    /** Turns of a stored session not folded into its summary, oldest first. */
    suspend fun turns(session: ChatSessionEntity): List<SessionTurn> =
        SessionTurns.rebuild(dao.messages(session.id).map { it.toStored() }, session.summaryUntil)

    private companion object {
        const val K_ACTIVE = "active_session"
        const val K_FORCED = "force_resume"
    }
}

fun ChatMessageEntity.toStored() = StoredMessage(role == ChatMessageEntity.ROLE_USER, text, createdAt, action, taskIdList)
