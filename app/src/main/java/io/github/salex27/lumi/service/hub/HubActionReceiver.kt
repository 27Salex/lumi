package io.github.salex27.lumi.service.hub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.hub.HubInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Buttons of the Lumi Hub notifications: answer an agent's question, add or dismiss a task it proposed. Not exported;
 * the notification only carries the message id (the content is read back from the database).
 */
class HubActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskManagerApplication
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, 0L).takeIf { it > 0 } ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = when (intent.action) {
                    ACTION_ANSWER -> {
                        val text = intent.getStringExtra(EXTRA_ANSWER)
                            ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
                        if (text.isNullOrBlank()) null else app.hubInbox.answerAsk(messageId, text)
                    }
                    ACTION_TASK_ADD -> app.hubInbox.acceptTask(messageId)
                    ACTION_TASK_DISMISS -> app.hubInbox.dismissTask(messageId)
                    else -> null
                }
                val toast = when (outcome) {
                    HubInbox.Outcome.Closed -> R.string.hub_question_closed
                    is HubInbox.Outcome.Failed -> R.string.hub_unreachable
                    HubInbox.Outcome.Done -> if (intent.action == ACTION_TASK_ADD) R.string.hub_task_added else null
                    null -> null
                }
                if (toast != null) withContext(Dispatchers.Main) { Toast.makeText(context, toast, Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "io.github.salex27.lumi.HUB_ANSWER"
        const val ACTION_TASK_ADD = "io.github.salex27.lumi.HUB_TASK_ADD"
        const val ACTION_TASK_DISMISS = "io.github.salex27.lumi.HUB_TASK_DISMISS"
        const val KEY_TEXT = "hub_answer_text"
        private const val EXTRA_MESSAGE = "message_id"
        private const val EXTRA_ANSWER = "answer"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun intent(context: Context, action: String, messageId: Long) =
            Intent(context, HubActionReceiver::class.java).setAction(action).putExtra(EXTRA_MESSAGE, messageId)
                // Distinct data so each notification keeps its own PendingIntents
                .setData(android.net.Uri.parse("lumi-hub://message/$messageId"))

        /** One option button: [index] keeps the PendingIntents of the same notification apart. */
        fun answer(context: Context, messageId: Long, option: String, index: Int): PendingIntent = PendingIntent.getBroadcast(
            context, index, intent(context, ACTION_ANSWER, messageId).putExtra(EXTRA_ANSWER, option),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Typed reply (RemoteInput needs a mutable PendingIntent). */
        fun typedAnswer(context: Context, messageId: Long): PendingIntent = PendingIntent.getBroadcast(
            context, 10, intent(context, ACTION_ANSWER, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        fun task(context: Context, messageId: Long, add: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context, if (add) 20 else 21, intent(context, if (add) ACTION_TASK_ADD else ACTION_TASK_DISMISS, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
