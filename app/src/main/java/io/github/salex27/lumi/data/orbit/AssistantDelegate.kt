package io.github.salex27.lumi.data.orbit

import android.util.Log
import io.github.salex27.lumi.data.chat.ChatStore
import io.github.salex27.lumi.data.hub.HubClient
import io.github.salex27.lumi.data.hub.HubEvent
import io.github.salex27.lumi.data.hub.HubSettings
import io.github.salex27.lumi.data.hub.ModelChoice
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.domain.assistant.Delegation
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Secretary hand-off from the assistant chat to Claude on the PC, and the way back: the answer of the turn is stored in
 * the SAME chat session (even when the screen was closed meanwhile) and announced on [answers] so an open chat shows it
 * at once. Only the final reply is used (streamed chunks are ignored). The text is shown, never executed.
 */
class AssistantDelegate(
    private val client: HubClient,
    private val settings: HubSettings,
    private val choices: AgentChoices?,
    private val store: ChatStore,
    scope: CoroutineScope,
    replies: SharedFlow<HubEvent>,
    /** Whether the Claude agent may read tasks (the existing can_read_tasks setting). */
    private val canReadTasks: suspend () -> Boolean,
    /** Today's open tasks and meetings as short lines. */
    private val todayLines: suspend () -> List<String>
) {
    class Answer(val handle: ChatStore.Handle, val text: String, val isError: Boolean)

    sealed interface Outcome {
        data object Sent : Outcome
        /** Hub not set up or not reachable: the caller offers to save it as a task. */
        data class Unreachable(val reason: String) : Outcome
    }

    private val _answers = MutableSharedFlow<Answer>(extraBufferCapacity = 16)
    val answers: SharedFlow<Answer> = _answers.asSharedFlow()

    private val pending = ConcurrentHashMap<String, ChatStore.Handle>()
    private val early = ConcurrentHashMap<String, HubEvent>() // a final reply that beat the turn id

    init {
        scope.launch { replies.collect { e -> runCatching { onReply(e) }.onFailure { Log.w(TAG, "reply", it) } } }
    }

    suspend fun send(request: String, recentTurns: List<Pair<String, String>>, handle: ChatStore.Handle): Outcome {
        if (!settings.config.value.isConfigured) return Outcome.Unreachable("not configured")
        val lines = if (runCatching { canReadTasks() }.getOrDefault(false)) runCatching { todayLines() }.getOrNull() else null
        val text = Delegation.message(
            request, LocalDateTime.now(), if (ReplyLanguage.current == Lang.ES) "Spanish" else "English", recentTurns, lines
        )
        return try {
            val pick = choices?.forTurn(DELEGATE_KEY) ?: ModelChoice()
            val turn = client.chat(Delegation.THREAD, "claude", text, pick.model, pick.effort)
            pending[turn] = handle
            early.remove(turn)?.let { finish(it) }
            Outcome.Sent
        } catch (e: HubClient.HubException) {
            Outcome.Unreachable(
                if (e.code == 409) ReplyLanguage.ui("Claude aún está con el encargo anterior.", "Claude is still on the previous job.")
                else e.message.orEmpty()
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Outcome.Unreachable(e.message.orEmpty())
        }
    }

    private fun onReply(e: HubEvent) {
        if (e.thread != Delegation.THREAD || e.type == HubEvent.TURN_DELTA || !e.done) return
        val turn = e.turn ?: return
        if (pending.containsKey(turn)) finish(e) else { if (early.size > 8) early.clear(); early[turn] = e }
    }

    private fun finish(e: HubEvent) {
        val handle = pending.remove(e.turn ?: return) ?: return
        val failed = e.error != null && e.text.isNullOrBlank()
        val text = if (failed) ReplyLanguage.ui("Claude no ha podido responder: ", "Claude couldn't answer: ") + e.error
        else e.text.orEmpty().trim().ifBlank { return }
        val entity = ChatMessageEntity(
            sessionId = 0, role = ChatMessageEntity.ROLE_ASSISTANT, text = text, createdAt = store.stamp(),
            engine = "Claude · PC", isError = failed
        )
        store.append(handle, entity) // stored in the chat it came from, open or not
        _answers.tryEmit(Answer(handle, text, failed))
    }

    private companion object {
        const val TAG = "LumiDelegate"
        const val DELEGATE_KEY = -1L // no per-thread choice: the Settings default applies
    }
}
