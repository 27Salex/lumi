package io.github.salex27.lumi.service.live

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.local.TaskDao
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.data.sync.DeviceCalendar
import io.github.salex27.lumi.domain.live.LiveUpdatePlanner
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.repository.TaskChangeListener
import io.github.salex27.lumi.presentation.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Live update": the next timed task or meeting, with a countdown, on the lock screen and — on Android 16 — as a
 * status bar chip (a "promoted" ongoing notification; Samsung shows it in the Now Bar).
 * Recomputed when a task changes, when the app opens and with an alarm at the next relevant moment.
 */
class LiveUpdateManager(
    private val context: Context,
    private val dao: TaskDao,
    private val calendar: DeviceCalendar,
    private val settings: SettingsRepository
) : TaskChangeListener {

    /** State of the status bar chip (Android 16), to explain it in Settings. */
    enum class ChipStatus { UNSUPPORTED, BLOCKED, IDLE, ACTIVE, NOT_PROMOTED }

    private val _chip = kotlinx.coroutines.flow.MutableStateFlow(ChipStatus.IDLE)
    val chipStatus: kotlinx.coroutines.flow.StateFlow<ChipStatus> = _chip

    private val mutex = Mutex()
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    override suspend fun onTaskSaved(taskId: Long) = refresh()
    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) = refresh()

    suspend fun refresh() = mutex.withLock {
        val manager = NotificationManagerCompat.from(context)
        if (!settings.current.liveUpdates || !canNotify()) {
            manager.cancel(NOTIFICATION_ID)
            cancelAlarm()
            return@withLock
        }
        val now = System.currentTimeMillis()
        val tasks = dao.getActiveTasksWithDue().map { it.toDomain() }
        val events = if (calendar.canRead()) runCatching { calendar.eventsBetween(now - 12 * HOUR, now + 24 * HOUR) }.getOrDefault(emptyList()) else emptyList()
        val hidden = hiddenPrefs().getString(K_HIDDEN, null)
        val result = LiveUpdatePlanner.plan(tasks, events, now, dao.linkedCalendarEventIds().toSet(), hidden)

        val item = result.item
        if (item == null) manager.cancel(NOTIFICATION_ID) else manager.notify(NOTIFICATION_ID, build(item, now))
        scheduleAlarm(result.nextRefreshAt)
        _chip.value = chipStatusFor(item != null)
    }

    /** Did the system turn the notification into a chip? (Android sets FLAG_PROMOTED_ONGOING when it accepts it). */
    private fun chipStatusFor(showing: Boolean): ChipStatus {
        if (Build.VERSION.SDK_INT < 36) return ChipStatus.UNSUPPORTED
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.canPostPromotedNotifications()) return ChipStatus.BLOCKED
        if (!showing) return ChipStatus.IDLE
        val posted = nm.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID } ?: return ChipStatus.NOT_PROMOTED
        return if (posted.notification.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0) ChipStatus.ACTIVE else ChipStatus.NOT_PROMOTED
    }

    /** Opens the system screen where the chip ("Live updates") is allowed for Lumi. */
    fun promotionSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 36) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    private fun build(item: LiveUpdatePlanner.Item, now: Long): android.app.Notification {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 36) return buildChip(item, now)
        val time = Instant.ofEpochMilli(item.start).atZone(ZoneId.systemDefault()).format(hm)
        val ongoing = item.isOngoing(now)
        val text = when (item.kind) {
            LiveUpdatePlanner.Item.Kind.MEETING -> context.getString(if (ongoing) R.string.live_meeting_ongoing else R.string.live_meeting_at, time)
            LiveUpdatePlanner.Item.Kind.TASK -> context.getString(if (ongoing) R.string.live_task_was_at else R.string.live_task_at, time)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(item.title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(if (item.kind == LiveUpdatePlanner.Item.Kind.MEETING) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            // Native countdown to the time (the system updates it without waking the app)
            .setWhen(item.start)
            .setShowWhen(true)
            .setUsesChronometer(!ongoing)
            .setChronometerCountDown(!ongoing)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            // Short text of the status bar chip / Now Bar
            .setShortCriticalText(if (ongoing) context.getString(R.string.live_now) else time)
            .setRequestPromotedOngoing(true)
        if (item.kind == LiveUpdatePlanner.Item.Kind.TASK) {
            builder.addAction(0, context.getString(R.string.action_done), action(ACTION_DONE, item.id))
        }
        directionsIntent(item)?.let { builder.addAction(0, context.getString(R.string.action_directions), it) }
        builder.addAction(0, context.getString(R.string.action_talk), io.github.salex27.lumi.presentation.assistant.AssistantActivity.talkPendingIntent(context))
        return builder.build()
    }

    /**
     * Android 16: a "promoted" notification like Maps'. What the system requires for the chip: ongoing, with a title,
     * not colorized, no custom views, ProgressStyle and a promotion request.
     * The chip shows the countdown (chronometer) or "Now".
     */
    @android.annotation.TargetApi(36)
    private fun buildChip(item: LiveUpdatePlanner.Item, now: Long): android.app.Notification {
        val time = Instant.ofEpochMilli(item.start).atZone(ZoneId.systemDefault()).format(hm)
        val ongoing = item.isOngoing(now)
        val meeting = item.kind == LiveUpdatePlanner.Item.Kind.MEETING
        val text = when {
            meeting && ongoing -> context.getString(R.string.live_meeting_ongoing, time)
            meeting -> context.getString(R.string.live_meeting_at, time) + if (item.location.isNotBlank()) " · ${item.location}" else ""
            ongoing -> context.getString(R.string.live_task_was_at, time)
            else -> context.getString(R.string.live_task_at, time)
        }
        val accent = android.graphics.Color.parseColor("#38A8F5")
        val style = android.app.Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgressSegments(listOf(android.app.Notification.ProgressStyle.Segment(100).setColor(accent)))
            .setProgress(LiveUpdatePlanner.progress(item, now))
            .setProgressTrackerIcon(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_assistant))
        val builder = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(item.title)
            .setContentText(text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setCategory(if (meeting) android.app.Notification.CATEGORY_EVENT else android.app.Notification.CATEGORY_REMINDER)
            .setWhen(item.start)
            .setShowWhen(true)
            .setUsesChronometer(!ongoing)
            .setChronometerCountDown(!ongoing)
            .setContentIntent(openApp())
        // Ongoing → "Now" on the chip; otherwise the chip shows the chronometer countdown
        if (ongoing) builder.setShortCriticalText("Ahora")
        platformActions(item).forEach { builder.addAction(it) }
        // Promotion request (EXTRA_REQUEST_PROMOTED_ONGOING)
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        return builder.build()
    }

    private fun platformActions(item: LiveUpdatePlanner.Item): List<android.app.Notification.Action> {
        val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_assistant)
        fun act(label: String, pi: PendingIntent) = android.app.Notification.Action.Builder(icon, label, pi).build()
        return buildList {
            if (item.kind == LiveUpdatePlanner.Item.Kind.TASK) add(act(context.getString(R.string.action_done), action(ACTION_DONE, item.id)))
            directionsIntent(item)?.let { add(act(context.getString(R.string.action_directions), it)) }
            add(act(context.getString(R.string.action_talk), io.github.salex27.lumi.presentation.assistant.AssistantActivity.talkPendingIntent(context)))
            if (size < 3) add(act(context.getString(R.string.action_hide), action(ACTION_HIDE, item.id, item.key)))
        }
    }

    /** "Directions" in the chosen maps app, when the meeting has an address. */
    private fun directionsIntent(item: LiveUpdatePlanner.Item): PendingIntent? {
        if (item.kind != LiveUpdatePlanner.Item.Kind.MEETING || item.location.isBlank()) return null
        val intent = io.github.salex27.lumi.presentation.nav.MapsLauncher.intent(
            context, io.github.salex27.lumi.domain.model.NavDestination(item.title, item.location), settings.current.mapsApp
        )
        return PendingIntent.getActivity(context, 7_101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp() = PendingIntent.getActivity(
        context, NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(action: String, id: Long, key: String? = null) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, LiveUpdateReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** "Hide": this item won't show again until it changes. */
    fun hide(key: String) {
        hiddenPrefs().edit().putString(K_HIDDEN, key).apply()
    }

    private fun hiddenPrefs() = context.getSharedPreferences("live_updates", Context.MODE_PRIVATE)

    private fun scheduleAlarm(at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = refreshIntent()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm() = context.getSystemService(AlarmManager::class.java).cancel(refreshIntent())

    private fun refreshIntent() = PendingIntent.getBroadcast(
        context, 1, Intent(context, LiveUpdateReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // Default importance (not MIN): required for Android to promote it to the chip / Now Bar
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.live_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.live_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "live_updates"
        private const val NOTIFICATION_ID = 7_001
        private const val HOUR = 3_600_000L
        const val ACTION_REFRESH = "io.github.salex27.lumi.LIVE_REFRESH"
        const val ACTION_DONE = "io.github.salex27.lumi.LIVE_DONE"
        const val ACTION_HIDE = "io.github.salex27.lumi.LIVE_HIDE"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
        private const val K_HIDDEN = "hidden_id"
    }
}

class LiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskManagerApplication
        val pending = goAsync()
        scope.launch {
            try {
                val id = intent.getLongExtra(LiveUpdateManager.EXTRA_ID, -1L)
                when (intent.action) {
                    LiveUpdateManager.ACTION_DONE ->
                        // updateTask notifies the listeners → the live update moves on to the next thing
                        app.repository.getTask(id)?.let { app.repository.updateTask(it.copy(status = TaskStatus.COMPLETED)) }
                    LiveUpdateManager.ACTION_HIDE -> {
                        intent.getStringExtra(LiveUpdateManager.EXTRA_KEY)?.let { app.liveUpdates.hide(it) }
                        app.liveUpdates.refresh()
                    }
                    else -> app.liveUpdates.refresh()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
