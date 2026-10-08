package io.github.salex27.lumi.service.checkin

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.domain.assistant.AlarmLog
import io.github.salex27.lumi.domain.assistant.BedtimeParser
import io.github.salex27.lumi.presentation.assistant.AssistantActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Daily "time to go to bed" notification. Inexact window alarm (no exact-alarm permission needed on Android 12+),
 * rescheduled after each fire, on app start and on boot (ReminderReceiver).
 */
class BedtimeScheduler(private val context: Context, private val settings: SettingsRepository) {

    fun schedule() {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        val s = settings.current
        if (!s.bedtimeEnabled) { am.cancel(pi); return }
        val now = LocalDateTime.now()
        val offset = BedtimeParser.nextDayOffset(s.bedtimeDays, now.dayOfWeek.value - 1, now.hour * 60 + now.minute, s.bedtimeMinutes)
        if (offset == null) { am.cancel(pi); return }
        val at = now.toLocalDate().plusDays(offset.toLong()).atTime(s.bedtimeMinutes / 60, s.bedtimeMinutes % 60)
        am.setWindow(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 5 * 60_000L, pi)
    }

    companion object {
        private fun pending(context: Context) = PendingIntent.getBroadcast(
            context, 43, Intent(context, BedtimeReceiver::class.java).setAction(BedtimeReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class BedtimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val app = context.applicationContext as TaskManagerApplication
        app.bedtime.schedule() // the next one
        if (!app.settings.current.bedtimeEnabled) return
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return
        // "Start good night" runs the routine (same phrase as saying it): smart alarm, Do Not Disturb, tomorrow's agenda
        val start = PendingIntent.getActivity(
            context, 44,
            AssistantActivity.intent(context, prompt = context.getString(R.string.prompt_good_night), compact = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, TaskManagerApplication.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(context.getString(R.string.bedtime_title))
            .setContentText(context.getString(R.string.bedtime_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(start)
            .addAction(0, context.getString(R.string.bedtime_action), start)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_FIRE = "io.github.salex27.lumi.BEDTIME"
        private const val NOTIFICATION_ID = 7_004
    }
}

/** The wake-up alarms Lumi asked the clock app for, so they can be listed and removed (see [AlarmLog]). */
class AlarmSetStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("alarm_sets", Context.MODE_PRIVATE)

    fun live(now: Long = System.currentTimeMillis()): List<AlarmLog.Entry> =
        AlarmLog.live(AlarmLog.decode(prefs.getString(KEY, null)), now).sortedBy { it.minutes }

    fun add(times: List<Int>, now: Long = System.currentTimeMillis()) {
        val all = AlarmLog.live(AlarmLog.decode(prefs.getString(KEY, null)), now).filter { it.minutes !in times } + times.map { AlarmLog.Entry(it, now) }
        prefs.edit().putString(KEY, AlarmLog.encode(all)).apply()
    }

    fun remove(minutes: Int) {
        val all = AlarmLog.decode(prefs.getString(KEY, null)).filter { it.minutes != minutes }
        prefs.edit().putString(KEY, AlarmLog.encode(all)).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "entries"

        /** Intent that makes the clock app save one alarm without opening (SET_ALARM permission, declared). */
        fun setIntent(minutes: Int, label: String): Intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, minutes / 60).putExtra(AlarmClock.EXTRA_MINUTES, minutes % 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        /**
         * Asks the clock app to delete the alarm at that time: the DELETE_ALARM action (Android 12+ clocks) first, then
         * DISMISS_ALARM by time. Returns false if no clock app handled either (the user deletes it there).
         */
        fun delete(context: Context, minutes: Int): Boolean {
            for (action in listOf("android.intent.action.DELETE_ALARM", AlarmClock.ACTION_DISMISS_ALARM)) {
                val intent = Intent(action)
                    .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    .putExtra(AlarmClock.EXTRA_HOUR, minutes / 60)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minutes % 60)
                    .putExtra(AlarmClock.EXTRA_IS_PM, minutes / 60 >= 12)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return true
            }
            return false
        }
    }
}
