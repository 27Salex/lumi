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

/** The app's service locator (no DI framework). */
class TaskManagerApplication : Application() {

    companion object {
        const val REMINDER_CHANNEL_ID = "task_reminders"
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val briefStore: BriefStore by lazy { BriefStore(this) }

    // ── AI engines (order = priority) ────────────────────────────────────────
    val nanoEngine: GeminiNanoEngine by lazy { GeminiNanoEngine() }
    val gemmaModel: GemmaModelManager by lazy { GemmaModelManager(this, appScope) }
    val gemmaEngine: GemmaLocalEngine by lazy { GemmaLocalEngine(this, gemmaModel, settings) }
    val cloudEngine: CloudGeminiEngine by lazy { CloudGeminiEngine(settings) }

    val assistant: AssistantOrchestrator by lazy {
        AssistantOrchestrator(
            llmEngines = listOf(
                nanoEngine to 20_000L,
                // No timeout (user decision): Gemma gets all the time it needs; the cloud is only used if it fails
                // or returns nothing. If it is too slow, turn it off in Settings.
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

    /** Voice profile for "Oye Lumi" (local Voice Match). */
    val voiceProfile: io.github.salex27.lumi.service.wakeword.VoiceProfileStore by lazy {
        io.github.salex27.lumi.service.wakeword.VoiceProfileStore(this)
    }

    /** Personal memory (`memories` table). */
    val memoryStore: io.github.salex27.lumi.domain.repository.MemoryStore by lazy {
        io.github.salex27.lumi.data.local.RoomMemoryStore(database.memoryDao())
    }

    /** Chat sessions (v8), shared by the overlay pill and the chat inside the app. */
    val chatStore: io.github.salex27.lumi.data.chat.ChatStore by lazy {
        io.github.salex27.lumi.data.chat.ChatStore(this, database.chatDao(), appScope)
    }
    val chatMemory: io.github.salex27.lumi.data.chat.ChatMemory by lazy {
        io.github.salex27.lumi.data.chat.ChatMemory(
            chatStore, repository.conversation, { repository.getTask(it) },
            { system, user, max -> assistant.ask(system, user, max)?.first }, appScope
        )
    }

    val reminderScheduler: AlarmReminderScheduler by lazy { AlarmReminderScheduler(this, settings, database.reminderDao()) }

    // ── Places, live chip and voice ─────────────────────────────────────────
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
    /** Weather (Open-Meteo, no API key). */
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

    // ── Sync ─────────────────────────────────────────────────────────────────
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
                // A task coming from Google Tasks also needs a reminder, an event and the widget
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
        updateAppLanguage()
        createNotificationChannels()

        appScope.launch {
            nanoEngine.refreshStatus()
            gemmaEngine.warmUp() // loads Gemma into memory if it is already downloaded
            assistant.refreshActiveEngine()
        }
        appScope.launch { googleTasksSync.syncNow() } // does nothing if it isn't turned on
        // When the Gemma download finishes → load it and update the active engine
        gemmaModel.state.onEach {
            if (it == GemmaModelManager.State.Ready) gemmaEngine.warmUp()
            assistant.refreshActiveEngine()
        }.launchIn(appScope)
        settings.settings.onEach { assistant.refreshActiveEngine() }.launchIn(appScope)
        appScope.launch { liveUpdates.refresh() }
        checkIn.schedule()
        morning.schedule()
    }

    /** Keeps [ReplyLanguage.app] in sync with the language the app is shown in (system or per-app setting). */
    fun updateAppLanguage(config: android.content.res.Configuration = resources.configuration) {
        val lang = io.github.salex27.lumi.domain.assistant.Lang.of(config.locales[0].language)
        if (lang == io.github.salex27.lumi.domain.assistant.ReplyLanguage.app) return
        io.github.salex27.lumi.domain.assistant.ReplyLanguage.app = lang
        createNotificationChannels() // channel names follow the language
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateAppLanguage(newConfig)
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.reminder_channel_description)
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
