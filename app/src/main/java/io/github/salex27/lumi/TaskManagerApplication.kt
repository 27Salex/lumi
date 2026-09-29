package io.github.salex27.lumi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.salex27.lumi.data.ai.AssistantOrchestrator
import io.github.salex27.lumi.data.ai.CloudGeminiEngine
import io.github.salex27.lumi.data.ai.GemmaLocalEngine
import io.github.salex27.lumi.data.ai.GemmaModelManager
import io.github.salex27.lumi.data.ai.GeminiNanoEngine
import io.github.salex27.lumi.data.ai.RuleBasedEngine
import io.github.salex27.lumi.data.local.AppDatabase
import io.github.salex27.lumi.data.local.BriefStore
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.repository.TaskRepositoryImpl
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.data.sync.CalendarTaskSync
import io.github.salex27.lumi.data.sync.DeviceCalendar
import io.github.salex27.lumi.data.sync.GoogleTasksAuth
import io.github.salex27.lumi.data.sync.GoogleTasksSync
import io.github.salex27.lumi.domain.repository.TaskChangeListener
import io.github.salex27.lumi.domain.repository.TaskRepository
import io.github.salex27.lumi.presentation.widget.WidgetUpdater
import io.github.salex27.lumi.service.reminder.AlarmReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Service Locator de la app (sin framework de DI). */
class TaskManagerApplication : Application() {

    companion object {
        const val REMINDER_CHANNEL_ID = "task_reminders"
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val briefStore: BriefStore by lazy { BriefStore(this) }

    // ── Motores de IA (orden = prioridad) ────────────────────────────────────
    val nanoEngine: GeminiNanoEngine by lazy { GeminiNanoEngine() }
    val gemmaModel: GemmaModelManager by lazy { GemmaModelManager(this, appScope) }
    val gemmaEngine: GemmaLocalEngine by lazy { GemmaLocalEngine(this, gemmaModel, settings) }
    val cloudEngine: CloudGeminiEngine by lazy { CloudGeminiEngine(settings) }

    val assistant: AssistantOrchestrator by lazy {
        AssistantOrchestrator(
            llmEngines = listOf(
                nanoEngine to 20_000L,
                // Sin timeout (decisión del usuario): Gemma tiene el tiempo que necesite; solo se cae a la nube
                // si falla o no devuelve nada. Si resulta lento, se desactiva a mano en Ajustes.
                gemmaEngine to null,
                cloudEngine to 25_000L
            ),
            rules = RuleBasedEngine(),
            memoryFor = { query ->
                io.github.salex27.lumi.domain.assistant.MemoryRetriever.relevant(query, memoryStore.all(), 3).map { it.text }
            }
        )
    }

    val wakeWordModel: io.github.salex27.lumi.service.wakeword.VoskModelManager by lazy {
        io.github.salex27.lumi.service.wakeword.VoskModelManager(this, appScope)
    }

    /** Perfil de voz para «Oye Lumi» (Voice Match local). */
    val voiceProfile: io.github.salex27.lumi.service.wakeword.VoiceProfileStore by lazy {
        io.github.salex27.lumi.service.wakeword.VoiceProfileStore(this)
    }

    /** Memoria personal (tabla `memories`). */
    val memoryStore: io.github.salex27.lumi.domain.repository.MemoryStore by lazy {
        io.github.salex27.lumi.data.local.RoomMemoryStore(database.memoryDao())
    }

    val reminderScheduler: AlarmReminderScheduler by lazy { AlarmReminderScheduler(this, settings, database.reminderDao()) }

    // ── Lugares, Now Bar y voz ───────────────────────────────────────────────
    val places: io.github.salex27.lumi.data.places.PlacesStore by lazy {
        io.github.salex27.lumi.data.places.PlacesStore(this)
    }
    val placeSearch: io.github.salex27.lumi.data.places.PlaceSearch by lazy {
        io.github.salex27.lumi.data.places.PlaceSearch(this, places)
    }
    val placeReminders: io.github.salex27.lumi.service.place.PlaceReminderManager by lazy {
        io.github.salex27.lumi.service.place.PlaceReminderManager(this, database.taskDao(), places)
    }
    val liveUpdates: io.github.salex27.lumi.service.live.LiveUpdateManager by lazy {
        io.github.salex27.lumi.service.live.LiveUpdateManager(this, database.taskDao(), deviceCalendar, settings)
    }
    val checkIn: io.github.salex27.lumi.service.checkin.CheckInScheduler by lazy {
        io.github.salex27.lumi.service.checkin.CheckInScheduler(this, settings)
    }
    val morning: io.github.salex27.lumi.service.checkin.MorningScheduler by lazy {
        io.github.salex27.lumi.service.checkin.MorningScheduler(this, settings)
    }
    /** El tiempo (Open-Meteo, sin API key). */
    val weather: io.github.salex27.lumi.data.weather.WeatherService by lazy {
        io.github.salex27.lumi.data.weather.WeatherService(this, places)
    }
    val backup: io.github.salex27.lumi.data.backup.BackupManager by lazy {
        io.github.salex27.lumi.data.backup.BackupManager(this, database, repository)
    }
    val routines: io.github.salex27.lumi.data.routines.RoutinesStore by lazy {
        io.github.salex27.lumi.data.routines.RoutinesStore(this)
    }
    val contactAliases: io.github.salex27.lumi.presentation.agent.ContactAliases by lazy {
        io.github.salex27.lumi.presentation.agent.ContactAliases(this)
    }
    val speaker: io.github.salex27.lumi.presentation.ai.LumiSpeaker by lazy {
        io.github.salex27.lumi.presentation.ai.LumiSpeaker(this, settings)
    }

    // ── Sincronización ───────────────────────────────────────────────────────
    val deviceCalendar: DeviceCalendar by lazy { DeviceCalendar(this) }
    val calendarSync: CalendarTaskSync by lazy { CalendarTaskSync(deviceCalendar, database.taskDao(), settings) }
    val googleTasksAuth: GoogleTasksAuth by lazy { GoogleTasksAuth(this) }
    val googleTasksSync: GoogleTasksSync by lazy {
        GoogleTasksSync(
            auth = googleTasksAuth,
            dao = database.taskDao(),
            tombstones = database.tombstoneDao(),
            settings = settings,
            scope = appScope,
            onRemoteChange = { id ->
                // Una tarea que llega de Google Tasks también necesita aviso, evento y widget
                database.taskDao().getTaskById(id)?.let { reminderScheduler.schedule(it.toDomain()) }
                calendarSync.onTaskSaved(id)
                widgetUpdater.onTaskSaved(id)
                placeReminders.onTaskSaved(id)
                liveUpdates.onTaskSaved(id)
            }
        )
    }
    private val widgetUpdater: WidgetUpdater by lazy { WidgetUpdater(this) }

    val repository: TaskRepository by lazy {
        TaskRepositoryImpl(
            taskDao = database.taskDao(),
            assistant = assistant,
            reminders = reminderScheduler,
            settings = settings,
            listeners = listOf<TaskChangeListener>(calendarSync, googleTasksSync, widgetUpdater, placeReminders, liveUpdates),
            calendar = deviceCalendar,
            isPlaceKnown = places::isKnown,
            placeCoordinates = { key -> places.get(key)?.let { it.lat to it.lng } },
            memory = memoryStore,
            extras = TaskRepositoryImpl.AssistantExtras(
                weather = weather,
                routines = { routines.routines.value },
                unreadMessages = {
                    val listener = io.github.salex27.lumi.service.notify.LumiNotificationListener
                    if (listener.isEnabled(this)) listener.unread() else null
                },
                describeAction = { task ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.salex27.lumi.presentation.agent.ActionPreview.resolve(this@TaskManagerApplication, contactAliases, task)
                            ?.let { io.github.salex27.lumi.presentation.agent.ActionPreview.sentence(it, task) }.orEmpty()
                    }
                },
                resolvePlace = { name ->
                    placeSearch.nearest(name)?.let {
                        io.github.salex27.lumi.domain.model.PlaceTrigger(it.name, true, true, it.lat, it.lng, it.address)
                    }
                }
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        appScope.launch {
            nanoEngine.refreshStatus()
            gemmaEngine.warmUp() // carga Gemma en memoria si ya está descargado
            assistant.refreshActiveEngine()
        }
        appScope.launch { googleTasksSync.syncNow() } // no hace nada si no está activado
        // Cuando termina la descarga de Gemma → cargarlo y actualizar el motor activo
        gemmaModel.state.onEach {
            if (it == GemmaModelManager.State.Ready) gemmaEngine.warmUp()
            assistant.refreshActiveEngine()
        }.launchIn(appScope)
        settings.settings.onEach { assistant.refreshActiveEngine() }.launchIn(appScope)
        appScope.launch { liveUpdates.refresh() }
        checkIn.schedule()
        morning.schedule()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Recordatorios de tareas",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisos de Lumi antes de citas y fechas límite"
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
