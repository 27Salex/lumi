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
 * «Actualización en directo»: la próxima tarea con hora o reunión, con cuenta atrás, en la pantalla de
 * bloqueo y — en Android 16 / One UI 8 — en la Now Bar del S25 (notificación continua «promocionada»).
 * Se recalcula al cambiar una tarea, al abrir la app y con una alarma en el siguiente momento relevante.
 */
class LiveUpdateManager(
    private val context: Context,
    private val dao: TaskDao,
    private val calendar: DeviceCalendar,
    private val settings: SettingsRepository
) : TaskChangeListener {

    /** Estado del chip de la barra de estado (Android 16), para explicarlo en Ajustes. */
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

    /** ¿El sistema convirtió la notificación en chip? (FLAG_PROMOTED_ONGOING lo pone Android si la acepta). */
    private fun chipStatusFor(showing: Boolean): ChipStatus {
        if (Build.VERSION.SDK_INT < 36) return ChipStatus.UNSUPPORTED
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.canPostPromotedNotifications()) return ChipStatus.BLOCKED
        if (!showing) return ChipStatus.IDLE
        val posted = nm.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID } ?: return ChipStatus.NOT_PROMOTED
        return if (posted.notification.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0) ChipStatus.ACTIVE else ChipStatus.NOT_PROMOTED
    }

    /** Abre la pantalla del sistema donde se permite el chip («Actualizaciones en directo») para Lumi. */
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
            LiveUpdatePlanner.Item.Kind.MEETING -> if (ongoing) "Reunión en curso · desde las $time" else "Reunión a las $time"
            LiveUpdatePlanner.Item.Kind.TASK -> if (ongoing) "Era a las $time" else "Tarea a las $time"
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
            // Cuenta atrás nativa hasta la hora (el sistema la actualiza sin despertar la app)
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
            // Texto corto del chip de la barra de estado / Now Bar
            .setShortCriticalText(if (ongoing) "Ahora" else time)
            .setRequestPromotedOngoing(true)
        if (item.kind == LiveUpdatePlanner.Item.Kind.TASK) {
            builder.addAction(0, "Hecho", action(ACTION_DONE, item.id))
        }
        directionsIntent(item)?.let { builder.addAction(0, "Cómo llegar", it) }
        builder.addAction(0, "Hablar", io.github.salex27.lumi.presentation.assistant.AssistantActivity.talkPendingIntent(context))
        return builder.build()
    }

    /**
     * Android 16: notificación «promocionada» como la de Maps. Requisitos que pide el sistema para el chip:
     * continua, con título, sin colorear ni vistas propias, estilo ProgressStyle y petición de promoción.
     * El chip muestra la cuenta atrás (cronómetro) o «Ahora».
     */
    @android.annotation.TargetApi(36)
    private fun buildChip(item: LiveUpdatePlanner.Item, now: Long): android.app.Notification {
        val time = Instant.ofEpochMilli(item.start).atZone(ZoneId.systemDefault()).format(hm)
        val ongoing = item.isOngoing(now)
        val meeting = item.kind == LiveUpdatePlanner.Item.Kind.MEETING
        val text = when {
            meeting && ongoing -> "Reunión en curso · desde las $time"
            meeting -> "Reunión a las $time" + if (item.location.isNotBlank()) " · ${item.location}" else ""
            ongoing -> "Era a las $time"
            else -> "Tarea a las $time"
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
        // En curso → «Ahora» en el chip; si no, el chip enseña la cuenta atrás del cronómetro
        if (ongoing) builder.setShortCriticalText("Ahora")
        platformActions(item).forEach { builder.addAction(it) }
        // Petición de promoción (EXTRA_REQUEST_PROMOTED_ONGOING)
        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        return builder.build()
    }

    private fun platformActions(item: LiveUpdatePlanner.Item): List<android.app.Notification.Action> {
        val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_assistant)
        fun act(label: String, pi: PendingIntent) = android.app.Notification.Action.Builder(icon, label, pi).build()
        return buildList {
            if (item.kind == LiveUpdatePlanner.Item.Kind.TASK) add(act("Hecho", action(ACTION_DONE, item.id)))
            directionsIntent(item)?.let { add(act("Cómo llegar", it)) }
            add(act("Hablar", io.github.salex27.lumi.presentation.assistant.AssistantActivity.talkPendingIntent(context)))
            if (size < 3) add(act("Ocultar", action(ACTION_HIDE, item.id, item.key)))
        }
    }

    /** «Cómo llegar» con la app de mapas elegida, si la reunión tiene dirección. */
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

    /** «Ocultar»: no vuelve a salir este elemento hasta que cambie. */
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
        // Importancia por defecto (no MIN): requisito para que Android la promocione a la Now Bar
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Próxima tarea (en directo)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "La próxima tarea o reunión con cuenta atrás en la pantalla de bloqueo y la Now Bar"
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
                        // updateTask notifica a los listeners → la actualización en directo pasa a lo siguiente
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
