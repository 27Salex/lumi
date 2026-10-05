package io.github.salex27.lumi.data.chat

import android.util.Log
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.domain.assistant.ConversationContext
import io.github.salex27.lumi.domain.chat.ChatContextBuilder
import io.github.salex27.lumi.domain.chat.ChatTurn
import io.github.salex27.lumi.domain.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Keeps the LLM's view of the active session small: restores a resumed session into the [ConversationContext] and,
 * when older turns no longer fit the token budget, folds them into the session's rolling summary (written by the
 * active LLM, or by a rule-based fallback when there is none). Runs after the reply, never in front of it.
 */
class ChatMemory(
    private val store: ChatStore,
    private val conversation: ConversationContext,
    private val getTask: suspend (Long) -> Task?,
    /** (system, user, maxTokens) → text from the first available LLM, or null when only rules are available. */
    private val ask: suspend (String, String, Int) -> String?,
    private val scope: CoroutineScope
) {
    private val summarizing = Mutex()

    /** Loads [session] (null = a new chat) into the conversation memory. */
    suspend fun resume(session: ChatSessionEntity?) {
        if (session == null) { conversation.clear(); return }
        val turns = store.turns(session)
        val withTask = turns.lastOrNull { it.taskIds.isNotEmpty() }
        val lastTask = withTask?.taskIds?.lastOrNull()?.let { runCatching { getTask(it) }.getOrNull() }
        conversation.restore(
            turns.map { ConversationContext.Turn(it.user, it.reply, it.action, it.at) },
            session.summary, lastTask, withTask?.at ?: 0L
        )
    }

    /** After a turn: summarizes the overflow when enough of it piled up (batched, in the background). */
    fun afterTurn(handle: ChatStore.Handle) {
        scope.launch {
            if (!summarizing.tryLock()) return@launch
            try {
                val overflow = conversation.overflow()
                if (overflow.size < MIN_OVERFLOW) return@launch
                val sessionId = handle.sessionId ?: return@launch
                val chatTurns = overflow.map { ChatTurn(it.user, it.reply, it.at) }
                val previous = conversation.summary
                val llm = runCatching { ask(ChatContextBuilder.SUMMARY_SYSTEM, ChatContextBuilder.summaryUser(previous, chatTurns), 160) }
                    .getOrNull()?.trim()?.takeIf { it.length in 10..900 }
                val summary = llm ?: ChatContextBuilder.ruleSummary(previous, chatTurns)
                conversation.fold(overflow.last().at, summary)
                store.saveSummary(sessionId, summary, overflow.last().at)
                Log.i("LumiChat", "Session $sessionId: ${overflow.size} turns folded into the summary (${if (llm != null) "LLM" else "rules"})")
            } finally {
                summarizing.unlock()
            }
        }
    }

    private companion object {
        const val MIN_OVERFLOW = 3
    }
}
