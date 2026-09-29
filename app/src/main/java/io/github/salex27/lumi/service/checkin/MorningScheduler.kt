package io.github.salex27.lumi.service.checkin

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.data.weather.WeatherService
import io.github.salex27.lumi.domain.assistant.AlarmPlanner
import io.github.salex27.lumi.domain.assistant.DayBriefComposer
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.presentation.assistant.AssistantActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Morning summary (8:00 by default): the weather, the first thing of the day, what's pending and weather warnings for
 * your tasks. Written by rules (not the AI): it arrives even if Gemma isn't loaded and uses no requests.
 */
class MorningScheduler(private val context: Context, private val settings: SettingsRepository) {

    fun schedule() {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        val s = settings.current
        if (!s.morningEnabled) { am.cancel(pi); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(s.morningMinutes / 60, s.morningMinutes % 60))
        if (!next.isAfter(now)) next = next.plusDays(1)
        am.setWindow(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 10 * 60_000L, pi)
    }

    companion object {
        private fun pending(context: Context) = PendingIntent.getBroadcast(
            context, 40, Intent(context, MorningReceiver::class.java).setAction(MorningReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Notification button that sets the alarm directly (the clock saves it without opening). It has to be an
         * Activity PendingIntent: from a receiver Android won't let the clock open.
         */
        fun alarmAction(context: Context, plan: AlarmPlanner.Plan): NotificationCompat.Action {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, plan.wake.hour).putExtra(AlarmClock.EXTRA_MINUTES, plan.wake.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Lumi · ${plan.anchorTitle}")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            val pi = PendingIntent.getActivity(context, 41, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action(0, context.getString(R.string.action_alarm_at, plan.wake.hour, plan.wake.minute), pi)
        }
    }
}

class MorningReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val app = context.applicationContext as TaskManagerApplication
        val pending = goAsync()
        scope.launch {
            try {
                app.morning.schedule() // tomorrow's one
                val now = LocalDateTime.now()
                val tasks = app.repository.getAllTasks().first()
                // Without the events Lumi created for its own tasks (they'd be duplicated)
                val linked = app.database.taskDao().linkedCalendarEventIds().toSet()
                val events = app.deviceCalendar.eventsOn(now.toLocalDate()).filter { it.id !in linked }
                // goAsync gives ~10 s: if the network is slow, the summary goes out without the weather
                val weather = withTimeoutOrNull(6_000) { (app.weather.forecast() as? WeatherService.Result.Ok)?.report }
                val brief = DayBriefComposer.compose(now.toLocalDate(), now, tasks, events, weather, DueDateFormatter.greeting(now))
                show(context, brief)
            } finally {
                pending.finish()
            }
        }
    }

    private fun show(context: Context, brief: DayBriefComposer.Brief) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return
        val listen = PendingIntent.getActivity(
            context, 42,
            AssistantActivity.intent(context, prompt = context.getString(R.string.prompt_today_summary), compact = true).putExtra(AssistantActivity.EXTRA_SPEAK, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, TaskManagerApplication.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(brief.title)
            .setContentText(brief.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(brief.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(listen)
            .addAction(0, context.getString(R.string.action_listen), listen)
            .addAction(0, context.getString(R.string.action_talk_to_lumi), AssistantActivity.talkPendingIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_FIRE = "io.github.salex27.lumi.MORNING"
        private const val NOTIFICATION_ID = 7_003
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
