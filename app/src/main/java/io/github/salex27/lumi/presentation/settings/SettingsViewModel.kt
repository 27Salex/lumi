package io.github.salex27.lumi.presentation.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.ai.GemmaLocalEngine
import io.github.salex27.lumi.data.ai.GemmaModelManager
import io.github.salex27.lumi.data.ai.GeminiNanoEngine
import io.github.salex27.lumi.data.places.PlacesStore
import io.github.salex27.lumi.service.live.LiveUpdateManager
import io.github.salex27.lumi.data.settings.AppSettings
import io.github.salex27.lumi.data.sync.DeviceCalendar
import io.github.salex27.lumi.data.sync.GoogleTasksSync
import io.github.salex27.lumi.service.wakeword.VoiceEnroller
import io.github.salex27.lumi.service.wakeword.WakePhrase
import io.github.salex27.lumi.service.wakeword.VoskModelManager
import io.github.salex27.lumi.service.wakeword.WakePhrases
import io.github.salex27.lumi.service.wakeword.WakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

sealed interface CloudTest {
    data object Idle : CloudTest
    data object Running : CloudTest
    data object Ok : CloudTest
    data class Failed(val message: String) : CloudTest
}

data class SystemStatus(
    val cloudTest: CloudTest = CloudTest.Idle,
    val notificationsGranted: Boolean = true,
    val exactAlarmsGranted: Boolean = true,
    val isDefaultAssistant: Boolean = false,
    val calendarGranted: Boolean = false,
    val calendars: List<DeviceCalendar.CalendarInfo> = emptyList(),
    val googleTasksError: String? = null,
    val wakeWordError: String? = null,
    val wakeWordRunning: Boolean = false,
    /** "Display over other apps": needed so "Oye Lumi" opens the assistant on top of any app. */
    val overlayGranted: Boolean = false,
    /** Status bar chip (Android 16) for the next task. */
    val chipStatus: LiveUpdateManager.ChipStatus = LiveUpdateManager.ChipStatus.IDLE,
    /** SHA-1 of the signing certificate, needed for the Google Cloud Android OAuth client. */
    val signingSha1: String = ""
)

/** Groups secondary states so `combine` never takes more than 5 flows (see AGENTS.md). */
data class SettingsExtras(
    val system: SystemStatus = SystemStatus(),
    val gemmaDiagnostics: GemmaLocalEngine.Diagnostics = GemmaLocalEngine.Diagnostics(),
    val googleTasksStatus: GoogleTasksSync.Status = GoogleTasksSync.Status.Idle,
    val wakeModel: VoskModelManager.State = VoskModelManager.State.Missing,
    val voice: VoiceUi = VoiceUi(),
    val places: PlacesUi = PlacesUi()
)

/** Settings → Places (place reminders). */
data class PlacesUi(
    val saved: List<PlacesStore.SavedPlace> = emptyList(),
    /** Places your tasks use that aren't saved yet (e.g. "gym"). */
    val missing: List<String> = emptyList(),
    val locationGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
    /** Key of the place being saved (waiting for GPS). */
    val saving: String? = null,
    val error: String? = null
)

/** One phrase's training: how many samples its print has, and whether it is the old 3-sample training. */
data class PhraseVoice(val samples: Int, val legacy: Boolean)

/** "Train my voice": saved profile (per phrase), sensitivity and training progress. */
data class VoiceUi(
    val trained: Boolean = false,
    val phrases: Map<WakePhrase, PhraseVoice> = emptyMap(),
    /** Prints learned from confirmed wakes and dismissed wakes kept for calibration (adaptive Voice Match). */
    val learned: Int = 0,
    val dismissed: Int = 0,
    val sensitivity: WakePhrases.Sensitivity = WakePhrases.Sensitivity.NORMAL,
    val training: VoiceEnroller.State = VoiceEnroller.State.Idle,
    /** The last short phrase "Oye Lumi" heard and why it was accepted or not. */
    val lastHeard: WakeWordService.Heard? = null,
    /** How close the last sound was to "Oye Lumi" (0..1, openWakeWord detector). */
    val lastScore: Float = 0f
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val nanoStatus: GeminiNanoEngine.Status = GeminiNanoEngine.Status.UNKNOWN,
    val gemmaState: GemmaModelManager.State = GemmaModelManager.State.NotDownloaded,
    val gemmaLoad: GemmaLocalEngine.LoadState = GemmaLocalEngine.LoadState.IDLE,
    val extras: SettingsExtras = SettingsExtras()
) {
    val system: SystemStatus get() = extras.system
}

/** Settings screen actions (an interface to keep the UI decoupled from the ViewModel). */
interface SettingsActions {
    fun setCloudEnabled(enabled: Boolean)
    fun setApiKey(key: String)
    fun setCloudModel(model: String)
    fun testCloud()
    fun setGemmaEnabled(enabled: Boolean)
    fun downloadGemma()
    fun cancelGemmaDownload()
    fun deleteGemma()
    fun testGemma()
    fun checkNano()
    fun downloadNano()
    fun setWorkHours(start: Int, end: Int)
    fun setReminderLead(minutes: Int)
    fun setVoiceLanguage(tag: String)
    fun setCalendarSync(enabled: Boolean)
    fun selectCalendar(id: Long)
    fun syncGoogleTasksNow()
    fun disconnectGoogleTasks()
    fun setThemeMode(mode: String)
    fun disableWakeWord()
    fun setWakeWordScreenOnly(enabled: Boolean)
    fun startVoiceTraining(phrase: WakePhrase)
    fun skipVoiceRound()
    fun resetLearnedVoice()
    fun cancelVoiceTraining()
    fun deleteVoiceProfile()
    fun setVoiceSensitivity(level: WakePhrases.Sensitivity)
    fun setSpeakReplies(enabled: Boolean)
    fun setLiveUpdates(enabled: Boolean)
    fun setMapsApp(packageName: String)
    fun setCheckIn(enabled: Boolean)
    fun setCheckInHour(hour: Int)
    fun removePlace(key: String)
    fun addMemory(text: String)
    fun deleteMemory(id: Long)
    suspend fun searchPlaces(query: String): List<io.github.salex27.lumi.data.places.PlaceSearch.Result>
    fun addPlace(name: String, result: io.github.salex27.lumi.data.places.PlaceSearch.Result)
    fun refreshPermissions()
}

class SettingsViewModel(private val app: TaskManagerApplication) : ViewModel(), SettingsActions {

    private val system = MutableStateFlow(SystemStatus(signingSha1 = signingSha1()))

    private val wakeStatus = combine(system, WakeWordService.running, app.liveUpdates.chipStatus) { sys, running, chip ->
        sys.copy(wakeWordRunning = running, chipStatus = chip)
    }

    private val enroller by lazy { VoiceEnroller(app.wakeWordModel.modelPath, app.wakeWordModel.speakerModelPath) }
    private val enrollState = MutableStateFlow<VoiceEnroller.State>(VoiceEnroller.State.Idle)

    private val voice = combine(
        app.voiceProfile.data, app.voiceProfile.sensitivityFlow, enrollState, WakeWordService.lastHeard, WakeWordService.lastScore
    ) { data, sens, training, heard, score ->
        VoiceUi(
            trained = data?.trained == true,
            phrases = data?.phrases.orEmpty().mapValues { (_, p) -> PhraseVoice(p.enrolled.size, p.legacy) },
            learned = data?.phrases?.values?.sumOf { it.learned.size } ?: 0, dismissed = data?.negatives?.size ?: 0,
            sensitivity = sens, training = training, lastHeard = heard, lastScore = score
        )
    }

    private val extras = combine(wakeStatus, app.gemmaEngine.diagnostics, app.googleTasksSync.status, app.wakeWordModel.state, voice) { sys, diag, gt, wake, v ->
        SettingsExtras(sys, diag, gt, wake, v)
    }

    private val placesStatus = MutableStateFlow(PlacesUi())

    private val places = combine(app.places.places, app.repository.getAllTasks(), placesStatus, app.placeReminders.error) { saved, tasks, status, geoError ->
        val known = saved.map { it.key }.toSet()
        status.copy(
            saved = saved,
            error = status.error ?: geoError,
            missing = tasks.filter { it.isActive }.mapNotNull { it.placeTrigger?.takeIf { p -> !p.isAdHoc }?.place }.filter { it !in known }.distinct()
        )
    }

    private val extrasWithPlaces = combine(extras, places) { ex, p -> ex.copy(places = p) }

    val state: StateFlow<SettingsUiState> = combine(
        app.settings.settings,
        app.nanoEngine.status,
        app.gemmaModel.state,
        app.gemmaEngine.loadState,
        extrasWithPlaces
    ) { settings, nano, gemma, load, ex -> SettingsUiState(settings, nano, gemma, load, ex) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    init {
        refreshPermissions()
        checkNano()
    }

    // ── AI ──────────────────────────────────────────────────────────────────
    override fun setCloudEnabled(enabled: Boolean) = app.settings.update { it.copy(cloudEnabled = enabled) }
    override fun setApiKey(key: String) {
        app.settings.update { it.copy(cloudApiKey = key) }
        system.update { it.copy(cloudTest = CloudTest.Idle) }
    }
    override fun setCloudModel(model: String) = app.settings.update { it.copy(cloudModel = model) }

    override fun testCloud() {
        system.update { it.copy(cloudTest = CloudTest.Running) }
        viewModelScope.launch {
            val error = app.cloudEngine.testConnection()
            system.update { it.copy(cloudTest = if (error == null) CloudTest.Ok else CloudTest.Failed(error)) }
        }
    }

    override fun setGemmaEnabled(enabled: Boolean) {
        app.settings.update { it.copy(gemmaEnabled = enabled) }
        if (!enabled) app.gemmaEngine.release() // frees ~1-2 GB of RAM
        else viewModelScope.launch { app.gemmaEngine.warmUp() }
    }

    override fun downloadGemma() = app.gemmaModel.startDownload()
    override fun cancelGemmaDownload() = app.gemmaModel.cancelDownload()
    override fun deleteGemma() {
        app.gemmaEngine.release()
        app.gemmaModel.deleteModel()
    }
    override fun testGemma() {
        viewModelScope.launch {
            app.gemmaEngine.selfTest()
            app.assistant.refreshActiveEngine()
        }
    }

    override fun checkNano() {
        viewModelScope.launch {
            app.nanoEngine.refreshStatus()
            app.assistant.refreshActiveEngine()
        }
    }

    override fun downloadNano() {
        viewModelScope.launch {
            app.nanoEngine.download()
            app.assistant.refreshActiveEngine()
        }
    }

    // ── Preferences ─────────────────────────────────────────────────────────
    override fun setWorkHours(start: Int, end: Int) = app.settings.update { it.copy(workStartHour = start, workEndHour = end) }

    override fun setReminderLead(minutes: Int) {
        app.settings.update { it.copy(reminderLeadMinutes = minutes) }
        viewModelScope.launch { app.repository.rescheduleAllReminders() }
    }

    override fun setVoiceLanguage(tag: String) = app.settings.update { it.copy(voiceLanguage = tag) }

    // ── Calendar ────────────────────────────────────────────────────────────
    override fun setCalendarSync(enabled: Boolean) {
        app.settings.update { it.copy(calendarSyncEnabled = enabled) }
        if (enabled) viewModelScope.launch {
            loadCalendars()
            // If no calendar is chosen, use the first one (usually the main Google one)
            if (app.settings.current.calendarId < 0) system.value.calendars.firstOrNull()?.let { selectCalendar(it.id) }
            app.calendarSync.syncAll()
        }
    }

    override fun selectCalendar(id: Long) {
        app.settings.update { it.copy(calendarId = id) }
        viewModelScope.launch { app.calendarSync.syncAll() }
    }

    private suspend fun loadCalendars() {
        system.update { it.copy(calendars = app.deviceCalendar.writableCalendars()) }
    }

    // ── Google Tasks ────────────────────────────────────────────────────────
    fun onGoogleTasksAuthorized() {
        app.settings.update { it.copy(googleTasksEnabled = true) }
        system.update { it.copy(googleTasksError = null) }
        syncGoogleTasksNow()
    }

    fun onGoogleTasksError(message: String) = system.update { it.copy(googleTasksError = message) }

    override fun syncGoogleTasksNow() {
        viewModelScope.launch { app.googleTasksSync.syncNow() }
    }

    override fun disconnectGoogleTasks() {
        // Local tasks are kept; only syncing stops
        app.settings.update { it.copy(googleTasksEnabled = false) }
    }

    // ── Appearance and voice ────────────────────────────────────────────────
    override fun setThemeMode(mode: String) = app.settings.update { it.copy(themeMode = mode) }

    override fun disableWakeWord() {
        app.settings.update { it.copy(wakeWordEnabled = false) }
        WakeWordService.stop(app)
    }

    override fun setWakeWordScreenOnly(enabled: Boolean) = app.settings.update { it.copy(wakeWordScreenOnly = enabled) }

    fun onWakeWordError(message: String?) = system.update { it.copy(wakeWordError = message) }

    // ── "Train my voice" ────────────────────────────────────────────────────

    /** Needs the downloaded model and the microphone permission. "Oye Lumi" is paused to free the microphone. */
    override fun startVoiceTraining(phrase: WakePhrase) {
        if (!app.wakeWordModel.isReady()) {
            enrollState.value = VoiceEnroller.State.Failed(app.getString(io.github.salex27.lumi.R.string.wake_first_turn_on))
            return
        }
        WakeWordService.pause(app)
        enrollJob?.cancel()
        enrollJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            launch {
                enroller.state.collect {
                    enrollState.value = it
                    if (it is VoiceEnroller.State.Failed) WakeWordService.resume(app)
                }
            }
            enroller.start(phrase) { prints, names ->
                app.voiceProfile.savePhrase(phrase, prints, names)
                WakeWordService.resume(app)
            }
        }
    }

    private var enrollJob: kotlinx.coroutines.Job? = null

    override fun skipVoiceRound() = enroller.skipCondition()

    override fun resetLearnedVoice() = app.voiceProfile.resetLearned()

    override fun cancelVoiceTraining() {
        enroller.stop()
        enrollJob?.cancel()
        enrollState.value = VoiceEnroller.State.Idle
        WakeWordService.resume(app)
    }

    override fun deleteVoiceProfile() {
        app.voiceProfile.clear()
        enrollState.value = VoiceEnroller.State.Idle
    }

    override fun setVoiceSensitivity(level: WakePhrases.Sensitivity) { app.voiceProfile.sensitivity = level }

    // ── Spoken replies and Now Bar ─────────────────────────────────────────
    override fun setSpeakReplies(enabled: Boolean) {
        app.settings.update { it.copy(speakReplies = enabled) }
        if (!enabled) app.speaker.stop()
    }

    override fun setLiveUpdates(enabled: Boolean) {
        app.settings.update { it.copy(liveUpdates = enabled) }
        viewModelScope.launch { app.liveUpdates.refresh() }
    }

    override fun setMapsApp(packageName: String) = app.settings.update { it.copy(mapsApp = packageName) }

    override fun setCheckIn(enabled: Boolean) {
        app.settings.update { it.copy(checkInEnabled = enabled) }
        app.checkIn.schedule()
    }

    override fun setCheckInHour(hour: Int) {
        app.settings.update { it.copy(checkInHour = hour) }
        app.checkIn.schedule()
    }

    // ── Places ──────────────────────────────────────────────────────────────

    /** Saves the current location as [key]. The Activity asks for the location permission first. */
    fun savePlaceHere(key: String, label: String) {
        viewModelScope.launch { app.liveUpdates.refresh() } // also recomputes the chip state
        placesStatus.update { it.copy(saving = key, error = null) }
        viewModelScope.launch {
            val location = app.placeReminders.currentLocation()
            if (location == null) {
                placesStatus.update { it.copy(saving = null, error = io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("No he podido obtener tu ubicación. Activa la ubicación del móvil y vuelve a intentarlo.", "I couldn't get your location. Turn on the phone's location and try again.")) }
                return@launch
            }
            app.places.save(PlacesStore.SavedPlace(key, label, location.latitude, location.longitude))
            placesStatus.update { it.copy(saving = null) }
            app.placeReminders.resyncAll()
            refreshPermissions()
        }
    }

    fun onPlaceError(message: String) = placesStatus.update { it.copy(saving = null, error = message) }

    override suspend fun searchPlaces(query: String) = app.placeSearch.search(query)

    /** Saves a place found by address (no need to be there). */
    override fun addPlace(name: String, result: io.github.salex27.lumi.data.places.PlaceSearch.Result) {
        val label = name.trim().ifBlank { result.name }.replaceFirstChar { it.uppercase() }
        app.places.save(
            PlacesStore.SavedPlace(
                io.github.salex27.lumi.data.ai.TaskPhraseParser.normalizePlace(label), label,
                result.lat, result.lng, address = result.address
            )
        )
        viewModelScope.launch { app.placeReminders.resyncAll() }
    }

    // ── Personal memory ─────────────────────────────────────────────────────
    /** Saved memories (Settings → Memory). Stored only on the phone. */
    val memories = app.memoryStore.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    override fun addMemory(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { app.memoryStore.add(text.trim().replaceFirstChar { it.uppercase() }) }
    }

    override fun deleteMemory(id: Long) {
        viewModelScope.launch { app.memoryStore.delete(id) }
    }

    override fun removePlace(key: String) {
        app.places.remove(key)
        viewModelScope.launch { app.placeReminders.resyncAll() }
    }

    override fun onCleared() {
        enroller.stop()
        super.onCleared()
    }

    // ── Permissions ─────────────────────────────────────────────────────────
    override fun refreshPermissions() {
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val roleManager = app.getSystemService(RoleManager::class.java)
        val isAssistant = runCatching { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
        val exact = app.reminderScheduler.canScheduleExact()
        system.update {
            it.copy(
                notificationsGranted = notifications, exactAlarmsGranted = exact,
                isDefaultAssistant = isAssistant, calendarGranted = app.deviceCalendar.canWrite(),
                overlayGranted = android.provider.Settings.canDrawOverlays(app)
            )
        }
        placesStatus.update {
            it.copy(locationGranted = app.placeReminders.hasLocation(), backgroundGranted = app.placeReminders.hasBackgroundLocation())
        }
        viewModelScope.launch {
            if (app.deviceCalendar.canRead()) loadCalendars()
            // If "exact alarms" was just granted, reschedule so they become exact
            app.repository.rescheduleAllReminders()
        }
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String = runCatching {
        val pm = app.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return@runCatching ""
        MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")

    class Factory(private val app: TaskManagerApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(app) as T
    }
}
