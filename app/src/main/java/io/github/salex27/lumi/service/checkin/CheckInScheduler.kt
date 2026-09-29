package io.github.salex27.lumi.service.checkin

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.domain.assistant.CheckInComposer
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.presentation.assistant.AssistantActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Evening check-in (20:00 by default): "Today: 3 done. 2 left for today…" with "Move to tomorrow", "Talk to Lumi" and a
 * typed/dictated reply from the notification itself.
 */
class CheckInScheduler(private val context: Context, private val settings: SettingsRepository) {

    /** Schedules (or cancels) the next check-in from the settings. Call on start, on settings changes and after each one. */
    fun schedule() {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending()
        val s = settings.current
        if (!s.checkInEnabled) { am.cancel(pi); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(s.checkInHour, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 10 min window: no exact alarm needed (saves battery)
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
    }

    private fun pending() = PendingIntent.getBroadcast(
        context, 0, Intent(context, CheckInReceiver::class.java).setAction(CheckInReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class CheckInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskManagerApplication
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> {
                        app.checkIn.schedule() // tomorrow's one
                        val tasks = app.repository.getAllTasks().first()
                        val now = LocalDateTime.now()
                        // …and tomorrow's alarm from the schedule ("Tomorrow: Dentist at 9:00 → alarm 7:30")
                        val alarm = if (app.settings.current.alarmSuggest) app.repository.smartAlarmPlan(io.github.salex27.lumi.domain.assistant.AlarmPlanner.targetDate(now)) else null
                        val checkIn = CheckInComposer.compose(tasks, now)
                        when {
                            checkIn != null -> show(context, checkIn, alarm)
                            alarm != null -> show(context, CheckInComposer.CheckIn(context.getString(R.string.checkin_tomorrow_title), "", emptyList()), alarm)
                        }
                    }
                    ACTION_TOMORROW -> {
                        val tasks = app.repository.getAllTasks().first()
                        val open = CheckInComposer.compose(tasks, LocalDateTime.now())?.openToday.orEmpty()
                        open.forEach { app.repository.updateTask(toTomorrow(it)) }
                        update(context, context.getString(R.string.checkin_moved, open.size))
                    }
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                        val answer = if (text.isBlank()) context.getString(R.string.reply_not_understood) else app.repository.processNaturalLanguageCommand(text).reply
                        update(context, answer)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Same time tomorrow; without a time, tomorrow without a time. */
    private fun toTomorrow(task: Task): Task {
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now().plusDays(1)
        val time = task.dueAt?.takeIf { task.dueHasTime }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)
        return task.copy(dueAt = tomorrow.atTime(time).atZone(zone).toInstant().toEpochMilli())
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun show(context: Context, checkIn: CheckInComposer.CheckIn, alarm: io.github.salex27.lumi.domain.assistant.AlarmPlanner.Plan? = null) {
        if (!canNotify(context)) return
        val body = listOfNotNull(checkIn.body.ifBlank { null }, alarm?.reason?.let { context.getString(R.string.checkin_tomorrow_prefix, it) }).joinToString("\n")
        val builder = base(context)
            .setContentTitle(checkIn.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        alarm?.let { builder.addAction(MorningScheduler.alarmAction(context, it)) }
        if (checkIn.openToday.isNotEmpty()) {
            builder.addAction(0, context.getString(R.string.action_move_to_tomorrow), broadcast(context, ACTION_TOMORROW, 1))
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.checkin_reply_hint)).build()
            val replyPi = PendingIntent.getBroadcast(
                context, 2, Intent(context, CheckInReceiver::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.action_reply), replyPi).addRemoteInput(input).build())
        }
        builder.addAction(0, context.getString(R.string.action_talk_to_lumi), AssistantActivity.talkPendingIntent(context))
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun update(context: Context, text: String) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            base(context).setContentTitle("Lumi").setContentText(text).setOnlyAlertOnce(true).setTimeoutAfter(10_000).build()
        )
    }

    private fun base(context: Context) = NotificationCompat.Builder(context, TaskManagerApplication.REMINDER_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_assistant)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(summaryIntent(context))

    companion object {
        const val ACTION_FIRE = "io.github.salex27.lumi.CHECKIN"
        const val ACTION_TOMORROW = "io.github.salex27.lumi.CHECKIN_TOMORROW"
        const val ACTION_REPLY = "io.github.salex27.lumi.CHECKIN_REPLY"
        private const val KEY_REPLY = "checkin_reply"
        private const val NOTIFICATION_ID = 7_002
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun broadcast(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, CheckInReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Tapping the check-in opens Lumi with the day summary. */
        private fun summaryIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 32, AssistantActivity.intent(context, compact = true, prompt = context.getString(R.string.prompt_how_am_i_doing)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
