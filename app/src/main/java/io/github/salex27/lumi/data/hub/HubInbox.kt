package io.github.salex27.lumi.data.hub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.R
import io.github.salex27.lumi.data.chat.ChatStore
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.domain.repository.TaskRepository
import io.github.salex27.lumi.presentation.main.MainActivity
import io.github.salex27.lumi.service.hub.HubActionReceiver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * Turns Hub events into chat messages (one HUB session per agent) and notifications, and carries out what the user
 * decides about them: answering a question, adding or dismissing a proposed task.
 *
 * Agent content is untrusted: it is stored and shown as text only. Nothing here runs a command, opens an app or calls
 * the LLM with it; a task is added only from an explicit user tap ([acceptTask]).
 */
class HubInbox(
    private val context: Context,
    private val store: ChatStore,
    private val client: HubClient,
    private val repository: TaskRepository
) {
    /** One decision at a time, on fresh data: a tap in the chat and one on the notification can't both act. */
    private val decide = Mutex()

    /** Stores [e] and notifies. Called in order, one event at a time, by [HubConnection]. */
    suspend fun handle(e: HubEvent) {
        when (e.type) {
            HubEvent.MESSAGE, HubEvent.ASK, HubEvent.TASK, HubEvent.NOTIFY -> store(e)
            HubEvent.ASK_CLOSED -> e.askId?.let { closeAsk(it, answeredElsewhere = e.answered) }
        }
    }

    private suspend fun store(e: HubEvent) {
        val payload = HubPayload.of(e) ?: return
        if (e.type == HubEvent.ASK) {
            val askId = e.askId ?: return
            store.flush()
            if (store.messageWithPayload(HubPayload.askFragment(askId)) != null) return // resent after a reconnection
        }
        val text = textOf(e).ifBlank { return }
        val message = ChatMessageEntity(
            sessionId = 0, role = ChatMessageEntity.ROLE_AGENT, text = text, createdAt = store.stamp(),
            engine = e.source, payload = payload.encode()
        )
        store.appendNamed(ChatSessionEntity.KIND_HUB, e.source, message) { id -> notify(id, e, text) }
    }

    private fun textOf(e: HubEvent): String = when (e.type) {
        HubEvent.MESSAGE -> listOfNotNull(e.text, e.link).joinToString("\n")
        HubEvent.ASK -> e.question.orEmpty()
        HubEvent.TASK -> {
            val title = e.title.orEmpty()
            val due = e.due?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            context.getString(R.string.hub_task_proposed, title + due)
        }
        HubEvent.NOTIFY -> listOfNotNull(e.title, e.text).joinToString("\n")
        else -> ""
    }

    // ── What the user decides ────────────────────────────────────────────────────────────────────────────────

    sealed interface Outcome {
        data object Done : Outcome
        /** The question was already answered, timed out or the agent gave up. */
        data object Closed : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Answers the question of message [messageId] (from a chat button, a notification action or a typed reply). */
    suspend fun answerAsk(messageId: Long, answer: String): Outcome = decide.withLock { store.flush(); answerLocked(messageId, answer) }

    private suspend fun answerLocked(messageId: Long, answer: String): Outcome {
        val clean = HubSafety.plain(answer).take(300).ifBlank { return Outcome.Failed("empty") }
        val m = store.message(messageId) ?: return Outcome.Failed("missing")
        val p = HubPayload.decode(m.payload)?.takeIf { it.hub == HubEvent.ASK && it.askId != null } ?: return Outcome.Failed("not a question")
        if (p.state != HubPayload.STATE_OPEN) return Outcome.Closed
        val ok = try {
            client.answer(p.askId!!, clean)
        } catch (e: Exception) {
            Log.w(TAG, "answer failed", e)
            return Outcome.Failed(e.message ?: "network")
        }
        store.update(m.id, m.text, p.copy(state = if (ok) HubPayload.STATE_ANSWERED else HubPayload.STATE_CLOSED, answer = clean.takeIf { ok }).encode())
        if (ok) {
            store.appendNamed(
                ChatSessionEntity.KIND_HUB, p.source ?: m.engine,
                ChatMessageEntity(sessionId = 0, role = ChatMessageEntity.ROLE_USER, text = clean, createdAt = store.stamp())
            )
        }
        cancel(m.id)
        return if (ok) Outcome.Done else Outcome.Closed
    }

    /** Adds the task an agent proposed. Rules only: its text never reaches the command interpreter. */
    suspend fun acceptTask(messageId: Long): Outcome = decide.withLock { store.flush(); acceptLocked(messageId) }

    private suspend fun acceptLocked(messageId: Long): Outcome {
        val m = store.message(messageId) ?: return Outcome.Failed("missing")
        val p = HubPayload.decode(m.payload)?.takeIf { it.hub == HubEvent.TASK && !it.title.isNullOrBlank() } ?: return Outcome.Failed("not a task")
        if (p.state != HubPayload.STATE_OPEN) return Outcome.Closed
        // Marked first: a double tap (chat + notification) can't add it twice
        store.update(m.id, m.text, p.copy(state = HubPayload.STATE_ADDED).encode())
        store.flush()
        repository.insertTask(HubTaskProposal.toTask(p.title!!, p.due, p.notes, p.source ?: m.engine, LocalDateTime.now()))
        cancel(m.id)
        return Outcome.Done
    }

    suspend fun dismissTask(messageId: Long): Outcome = decide.withLock { store.flush(); dismissLocked(messageId) }

    private suspend fun dismissLocked(messageId: Long): Outcome {
        val m = store.message(messageId) ?: return Outcome.Failed("missing")
        val p = HubPayload.decode(m.payload)?.takeIf { it.hub == HubEvent.TASK } ?: return Outcome.Failed("not a task")
        if (p.state != HubPayload.STATE_OPEN) return Outcome.Closed
        store.update(m.id, m.text, p.copy(state = HubPayload.STATE_DISMISSED).encode())
        cancel(m.id)
        return Outcome.Done
    }

    /** Under [decide]: an answer being sent from this phone is stored before the hub's "closed" echo is looked at. */
    private suspend fun closeAsk(askId: String, answeredElsewhere: Boolean) = decide.withLock { closeAskLocked(askId, answeredElsewhere) }

    private suspend fun closeAskLocked(askId: String, answeredElsewhere: Boolean) {
        store.flush()
        val m = store.messageWithPayload(HubPayload.askFragment(askId)) ?: return
        val p = HubPayload.decode(m.payload) ?: return
        if (p.state == HubPayload.STATE_OPEN) {
            store.update(m.id, m.text, p.copy(state = if (answeredElsewhere) HubPayload.STATE_ANSWERED else HubPayload.STATE_CLOSED).encode())
        }
        cancel(m.id)
    }

    // ── Notifications ────────────────────────────────────────────────────────────────────────────────────────

    private fun canNotify() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(messageId: Long) = NOTIFICATION_BASE + (messageId % 100_000).toInt()

    private fun cancel(messageId: Long) = NotificationManagerCompat.from(context).cancel(notificationId(messageId))

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(messageId: Long, e: HubEvent, text: String) {
        if (!canNotify()) return
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, notificationId(messageId),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_HUB_MESSAGE, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (e.type == HubEvent.NOTIFY) e.title.orEmpty() else e.source
        val body = if (e.type == HubEvent.NOTIFY) e.text.orEmpty() else text
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(title)
            .setSubText(if (e.type == HubEvent.NOTIFY) e.source else null)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (e.type == HubEvent.ASK) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
        when (e.type) {
            HubEvent.ASK -> {
                // Android shows 3 actions at most: the first options, or a typed reply when there are none / too many
                val options = e.options
                if (options.isNotEmpty() && options.size <= 3) {
                    options.forEachIndexed { i, option ->
                        b.addAction(0, option, HubActionReceiver.answer(context, messageId, option, i))
                    }
                } else {
                    val input = RemoteInput.Builder(HubActionReceiver.KEY_TEXT).setLabel(context.getString(R.string.hub_answer_hint))
                        .apply { if (options.isNotEmpty()) setChoices(options.toTypedArray()) }.build()
                    b.addAction(
                        NotificationCompat.Action.Builder(0, context.getString(R.string.action_reply), HubActionReceiver.typedAnswer(context, messageId))
                            .addRemoteInput(input).build()
                    )
                }
            }
            HubEvent.TASK -> {
                b.addAction(0, context.getString(R.string.hub_task_add), HubActionReceiver.task(context, messageId, add = true))
                b.addAction(0, context.getString(R.string.hub_task_dismiss), HubActionReceiver.task(context, messageId, add = false))
            }
        }
        NotificationManagerCompat.from(context).notify(notificationId(messageId), b.build())
    }

    companion object {
        const val CHANNEL_ID = "lumi_hub"
        private const val NOTIFICATION_BASE = 40_000
        private const val TAG = "LumiHub"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.hub_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.hub_channel_description)
                }
            )
        }
    }
}
