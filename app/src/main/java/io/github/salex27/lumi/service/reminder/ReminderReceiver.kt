package io.github.salex27.lumi.service.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.presentation.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Un único receptor para los avisos:
 * mostrar el aviso, acciones (Hecho / +1 h / responder a Lumi) y reprogramar tras reinicio o cambio de hora.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskManagerApplication
        val pending = goAsync()
        scope.launch {
            try {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                when (intent.action) {
                    ACTION_REMIND -> {
                        val reminder = app.database.reminderDao().byId(intent.getLongExtra(EXTRA_REMINDER_ID, -1L))
                        val task = app.repository.getTask(reminder?.taskId ?: taskId)
                        if (task != null && task.isActive) notify(context, task, reminder?.label)
                    }
                    ACTION_DONE -> {
                        app.repository.getTask(taskId)?.let { app.repository.updateTask(it.copy(status = TaskStatus.COMPLETED)) }
                        NotificationManagerCompat.from(context).cancel(taskId.toInt())
                    }
                    ACTION_SNOOZE -> {
                        app.reminderScheduler.snooze(taskId, SNOOZE_MINUTES)
                        NotificationManagerCompat.from(context).cancel(taskId.toInt())
                    }
                    ACTION_REPLY -> {
                        // Respuesta escrita/dictada desde la notificación: «hecho», «pospón una hora», «mañana a las 10»…
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                        val answer = if (text.isBlank()) null else app.repository.applyQuickReply(taskId, text)
                        app.repository.getTask(taskId)?.let { notifyAnswer(context, it, answer ?: "No he entendido la respuesta") }
                    }
                    // Las alarmas se pierden al reiniciar: se vuelven a programar todas
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                        app.repository.rescheduleAllReminders()
                        // Android también borra las geovallas al reiniciar
                        app.placeReminders.resyncAll()
                        app.liveUpdates.refresh()
                        app.checkIn.schedule()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun canNotify(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(context: Context, task: Task, label: String?) {
        if (!canNotify(context)) return
        val whenText = task.dueAt?.let { DueDateFormatter.format(it, task.dueHasTime) }
        // p.ej. "En 1 hora · hoy a las 17:00 · Para «Reunión del sprint»"
        val body = listOfNotNull(label, whenText, task.meeting?.let { "Para «${it.title}»" })
            .distinct().joinToString(" · ").replaceFirstChar { it.uppercase() }

        val builder = base(context, task)
            .setContentTitle(task.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(body, task.description).filter { it.isNotBlank() }.joinToString("\n")))
        // v3.7: si la tarea es «llamar a…» / «escribir a…» / «avisar a…», el primer botón lo hace (y la da por hecha)
        val taskAction = io.github.salex27.lumi.domain.assistant.TaskActions.detect(task)
        if (taskAction != null) {
            val pi = PendingIntent.getActivity(
                context, (task.id * 10 + 8).toInt(),
                io.github.salex27.lumi.presentation.assistant.AssistantActivity.intent(context, compact = true)
                    .putExtra(io.github.salex27.lumi.presentation.assistant.AssistantActivity.EXTRA_DEVICE, taskAction.command.serialize())
                    .putExtra(io.github.salex27.lumi.presentation.assistant.AssistantActivity.EXTRA_DONE_TASK, task.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, taskAction.label, pi)
                .addAction(0, "Hecho", action(context, task.id, ACTION_DONE))
                .addAction(0, "+1 h", action(context, task.id, ACTION_SNOOZE))
        } else {
            builder.addAction(0, "Hecho", action(context, task.id, ACTION_DONE))
                .addAction(0, "+1 h", action(context, task.id, ACTION_SNOOZE))
                .addAction(replyAction(context, task.id))
        }
        NotificationManagerCompat.from(context).notify(task.id.toInt(), builder.build())
    }

    /** Tras responder: se actualiza la misma notificación con lo que ha hecho Lumi. */
    private fun notifyAnswer(context: Context, task: Task, answer: String) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(
            task.id.toInt(),
            base(context, task).setContentTitle("Lumi").setContentText(answer).setTimeoutAfter(8_000).setOnlyAlertOnce(true).build()
        )
    }

    private fun base(context: Context, task: Task) = NotificationCompat.Builder(context, TaskManagerApplication.REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_assistant)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, task.id.toInt(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

    private fun action(context: Context, taskId: Long, action: String) = PendingIntent.getBroadcast(
        context, (taskId * 10 + action.length).toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun replyAction(context: Context, taskId: Long): NotificationCompat.Action {
        // RemoteInput necesita un PendingIntent MUTABLE para recibir el texto
        val pi = PendingIntent.getBroadcast(
            context, (taskId * 10 + 7).toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Hecho, pospón 1 hora, mañana a las 10…").build()
        return NotificationCompat.Action.Builder(0, "Responder", pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    companion object {
        const val ACTION_REMIND = "io.github.salex27.lumi.REMIND"
        const val ACTION_DONE = "io.github.salex27.lumi.REMINDER_DONE"
        const val ACTION_SNOOZE = "io.github.salex27.lumi.REMINDER_SNOOZE"
        const val ACTION_REPLY = "io.github.salex27.lumi.REMINDER_REPLY"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_REMINDER_ID = "reminder_id"
        private const val KEY_REPLY = "lumi_reply"
        private const val SNOOZE_MINUTES = 60
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Muestra el aviso de una tarea (también lo usan los avisos por lugar). */
        fun show(context: Context, task: Task, label: String?) = ReminderReceiver().notify(context, task, label)
    }
}
