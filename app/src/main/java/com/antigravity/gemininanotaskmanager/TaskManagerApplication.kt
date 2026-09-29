package com.antigravity.gemininanotaskmanager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.antigravity.gemininanotaskmanager.data.ai.AssistantOrchestrator
import com.antigravity.gemininanotaskmanager.data.ai.CloudGeminiEngine
import com.antigravity.gemininanotaskmanager.data.ai.GemmaLocalEngine
import com.antigravity.gemininanotaskmanager.data.ai.GemmaModelManager
import com.antigravity.gemininanotaskmanager.data.ai.GeminiNanoEngine
import com.antigravity.gemininanotaskmanager.data.ai.RuleBasedEngine
import com.antigravity.gemininanotaskmanager.data.local.AppDatabase
import com.antigravity.gemininanotaskmanager.data.local.BriefStore
import com.antigravity.gemininanotaskmanager.data.local.toDomain
import com.antigravity.gemininanotaskmanager.data.repository.TaskRepositoryImpl
import com.antigravity.gemininanotaskmanager.data.settings.SettingsRepository
import com.antigravity.gemininanotaskmanager.data.sync.CalendarTaskSync
import com.antigravity.gemininanotaskmanager.data.sync.DeviceCalendar
import com.antigravity.gemininanotaskmanager.data.sync.GoogleTasksAuth
import com.antigravity.gemininanotaskmanager.data.sync.GoogleTasksSync
import com.antigravity.gemininanotaskmanager.domain.repository.TaskChangeListener
import com.antigravity.gemininanotaskmanager.domain.repository.TaskRepository
import com.antigravity.gemininanotaskmanager.presentation.widget.WidgetUpdater
import com.antigravity.gemininanotaskmanager.service.reminder.AlarmReminderScheduler
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
                com.antigravity.gemininanotaskmanager.domain.assistant.MemoryRetriever.relevant(query, memoryStore.all(), 3).map { it.text }
            }
        )
    }

    val wakeWordModel: com.antigravity.gemininanotaskmanager.service.wakeword.VoskModelManager by lazy {
        com.antigravity.gemininanotaskmanager.service.wakeword.VoskModelManager(this, appScope)
    }

    /** Perfil de voz para «Oye Lumi» (Voice Match local). */
    val voiceProfile: com.antigravity.gemininanotaskmanager.service.wakeword.VoiceProfileStore by lazy {
        com.antigravity.gemininanotaskmanager.service.wakeword.VoiceProfileStore(this)
    }

    /** Memoria personal (tabla `memories`). */
    val memoryStore: com.antigravity.gemininanotaskmanager.domain.repository.MemoryStore by lazy {
        com.antigravity.gemininanotaskmanager.data.local.RoomMemoryStore(database.memoryDao())
    }

    val reminderScheduler: AlarmReminderScheduler by lazy { AlarmReminderScheduler(this, settings, database.reminderDao()) }

    // ── Lugares, Now Bar y voz ───────────────────────────────────────────────
    val places: com.antigravity.gemininanotaskmanager.data.places.PlacesStore by lazy {
        com.antigravity.gemininanotaskmanager.data.places.PlacesStore(this)
    }
    val placeSearch: com.antigravity.gemininanotaskmanager.data.places.PlaceSearch by lazy {
        com.antigravity.gemininanotaskmanager.data.places.PlaceSearch(this, places)
    }
    val placeReminders: com.antigravity.gemininanotaskmanager.service.place.PlaceReminderManager by lazy {
        com.antigravity.gemininanotaskmanager.service.place.PlaceReminderManager(this, database.taskDao(), places)
    }
    val liveUpdates: com.antigravity.gemininanotaskmanager.service.live.LiveUpdateManager by lazy {
        com.antigravity.gemininanotaskmanager.service.live.LiveUpdateManager(this, database.taskDao(), deviceCalendar, settings)
    }
    val checkIn: com.antigravity.gemininanotaskmanager.service.checkin.CheckInScheduler by lazy {
        com.antigravity.gemininanotaskmanager.service.checkin.CheckInScheduler(this, settings)
    }
    val morning: com.antigravity.gemininanotaskmanager.service.checkin.MorningScheduler by lazy {
        com.antigravity.gemininanotaskmanager.service.checkin.MorningScheduler(this, settings)
    }
    /** El tiempo (Open-Meteo, sin API key). */
    val weather: com.antigravity.gemininanotaskmanager.data.weather.WeatherService by lazy {
        com.antigravity.gemininanotaskmanager.data.weather.WeatherService(this, places)
    }
    val routines: com.antigravity.gemininanotaskmanager.data.routines.RoutinesStore by lazy {
        com.antigravity.gemininanotaskmanager.data.routines.RoutinesStore(this)
    }
    val contactAliases: com.antigravity.gemininanotaskmanager.presentation.agent.ContactAliases by lazy {
        com.antigravity.gemininanotaskmanager.presentation.agent.ContactAliases(this)
    }
    val speaker: com.antigravity.gemininanotaskmanager.presentation.ai.LumiSpeaker by lazy {
        com.antigravity.gemininanotaskmanager.presentation.ai.LumiSpeaker(this, settings)
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
                    val listener = com.antigravity.gemininanotaskmanager.service.notify.LumiNotificationListener
                    if (listener.isEnabled(this)) listener.unread() else null
                },
                describeAction = { task ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.antigravity.gemininanotaskmanager.presentation.agent.ActionPreview.resolve(this@TaskManagerApplication, contactAliases, task)
                            ?.let { com.antigravity.gemininanotaskmanager.presentation.agent.ActionPreview.sentence(it, task) }.orEmpty()
                    }
                },
                resolvePlace = { name ->
                    placeSearch.nearest(name)?.let {
                        com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger(it.name, true, true, it.lat, it.lng, it.address)
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
