package io.github.salex27.lumi.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Use Gemini in the cloud as an extra engine (off by default: privacy). */
    val cloudEnabled: Boolean = false,
    val cloudApiKey: String = "",
    /** The "latest" alias, so we don't depend on specific versions Google retires. */
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    /** Use Gemma on-device once the model is downloaded. */
    val gemmaEnabled: Boolean = true,
    val workStartHour: Int = 9,
    val workEndHour: Int = 18,
    /** Minutes before a timed appointment/deadline when the reminder arrives. */
    val reminderLeadMinutes: Int = 30,
    /** Reminder hour for tasks with a date but no time. */
    val dateOnlyReminderHour: Int = 9,
    /** Speech recognition language (BCP-47). Follows the phone language on first run. */
    val voiceLanguage: String = DEFAULT_VOICE_LANGUAGE,
    /** Create events in the phone calendar for timed tasks. */
    val calendarSyncEnabled: Boolean = false,
    /** Target calendar (CalendarContract id); -1 = none chosen. */
    val calendarId: Long = -1L,
    /** Two-way sync with Google Tasks (needs OAuth, see docs/GOOGLE_TASKS_SETUP.md). */
    val googleTasksEnabled: Boolean = false,
    /** Last successful Google Tasks sync (epoch millis, 0 = never). */
    val googleTasksLastSync: Long = 0L,
    /** Id of the "Lumi" list in Google Tasks. */
    val googleTasksListId: String = "",
    /** "Oye Lumi": offline wake word. */
    val wakeWordEnabled: Boolean = false,
    /** Listen only while the screen is on (saves battery). */
    val wakeWordScreenOnly: Boolean = true,
    /** "SYSTEM" | "LIGHT" | "DARK" */
    val themeMode: String = "SYSTEM",
    /** Read the reply aloud when talking to Lumi by voice (hands-free). */
    val speakReplies: Boolean = true,
    /** Next task or meeting on the lock screen / status bar chip (live update). */
    val liveUpdates: Boolean = true,
    /** Maps app package for "Directions" ("" = ask every time). */
    val mapsApp: String = "",
    /** Evening check-in: a notification with what got done and what is left today. */
    val checkInEnabled: Boolean = true,
    val checkInHour: Int = 20,
    /** Morning summary: weather + first appointment + today's tasks, in a notification. */
    val morningEnabled: Boolean = true,
    /** Morning summary time in minutes from midnight (8:00 = 480). */
    val morningMinutes: Int = 8 * 60,
    /** Daily "time to go to bed" reminder (minutes from midnight; days = bit mask, bit 0 = Monday). */
    val bedtimeEnabled: Boolean = false,
    val bedtimeMinutes: Int = 23 * 60,
    val bedtimeDays: Int = 127,
    /** Smart alarm: minutes to get ready and to travel. */
    val alarmPrepMinutes: Int = 60,
    val alarmTravelMinutes: Int = 30,
    /** Count the work start time (Monday to Friday) as the first thing of the day. */
    val alarmUsesWorkHours: Boolean = true,
    /** In the evening check-in, suggest tomorrow's alarm from the schedule. */
    val alarmSuggest: Boolean = true,
    /** Silence that ends a sentence said to Lumi (ms). */
    val voicePauseMs: Int = 1_500,
    /** After answering by voice, Lumi listens again for a moment (continuous conversation). */
    val continueConversation: Boolean = true
) {
    val cloudReady: Boolean get() = cloudEnabled && cloudApiKey.isNotBlank()

    val alarmConfig: io.github.salex27.lumi.domain.assistant.AlarmPlanner.Config
        get() = io.github.salex27.lumi.domain.assistant.AlarmPlanner.Config(
            prepMinutes = alarmPrepMinutes, travelMinutes = alarmTravelMinutes,
            workStartHour = workStartHour.takeIf { alarmUsesWorkHours }
        )

    companion object {
        const val DEFAULT_CLOUD_MODEL = "gemini-flash-lite-latest"
        /** Phone language on first run: Spanish phones get es-ES, everything else en-US. */
        val DEFAULT_VOICE_LANGUAGE: String get() = if (java.util.Locale.getDefault().language == "es") "es-ES" else "en-US"
        val VOICE_LANGUAGES = listOf("es-ES" to "Español (España)", "es-US" to "Español (Latinoamérica)", "en-US" to "English (US)", "en-GB" to "English (UK)")
    }
}

/**
 * Settings persisted in SharedPreferences (private to the app). The API key never leaves the device except in calls
 * to the Gemini API. `allowBackup` is off in the manifest so it doesn't travel in system backups either.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_settings.value).let {
            it.copy(
                cloudApiKey = it.cloudApiKey.trim(),
                cloudModel = it.cloudModel.trim().ifBlank { AppSettings.DEFAULT_CLOUD_MODEL },
                workStartHour = it.workStartHour.coerceIn(0, 23),
                workEndHour = it.workEndHour.coerceIn(1, 24),
                reminderLeadMinutes = it.reminderLeadMinutes.coerceIn(0, 24 * 60),
                checkInHour = it.checkInHour.coerceIn(17, 23),
                morningMinutes = it.morningMinutes.coerceIn(5 * 60, 11 * 60 + 45),
                bedtimeMinutes = it.bedtimeMinutes.coerceIn(0, 24 * 60 - 1),
                bedtimeDays = it.bedtimeDays and 127,
                alarmPrepMinutes = it.alarmPrepMinutes.coerceIn(10, 180),
                alarmTravelMinutes = it.alarmTravelMinutes.coerceIn(0, 180),
                voicePauseMs = it.voicePauseMs.coerceIn(700, 4_000)
            )
        }
        prefs.edit()
            .putBoolean(K_CLOUD_ENABLED, new.cloudEnabled)
            .putString(K_API_KEY, new.cloudApiKey)
            .putString(K_MODEL, new.cloudModel)
            .putBoolean(K_GEMMA_ENABLED, new.gemmaEnabled)
            .putInt(K_WORK_START, new.workStartHour)
            .putInt(K_WORK_END, new.workEndHour)
            .putInt(K_REMINDER_LEAD, new.reminderLeadMinutes)
            .putInt(K_DATE_ONLY_HOUR, new.dateOnlyReminderHour)
            .putString(K_VOICE_LANGUAGE, new.voiceLanguage)
            .putBoolean(K_CAL_SYNC, new.calendarSyncEnabled)
            .putLong(K_CAL_ID, new.calendarId)
            .putBoolean(K_GTASKS, new.googleTasksEnabled)
            .putLong(K_GTASKS_LAST, new.googleTasksLastSync)
            .putString(K_GTASKS_LIST, new.googleTasksListId)
            .putBoolean(K_WAKE, new.wakeWordEnabled)
            .putBoolean(K_WAKE_SCREEN, new.wakeWordScreenOnly)
            .putString(K_THEME, new.themeMode)
            .putBoolean(K_SPEAK, new.speakReplies)
            .putBoolean(K_LIVE, new.liveUpdates)
            .putString(K_MAPS, new.mapsApp)
            .putBoolean(K_CHECKIN, new.checkInEnabled)
            .putInt(K_CHECKIN_HOUR, new.checkInHour)
            .putBoolean(K_MORNING, new.morningEnabled)
            .putInt(K_MORNING_MIN, new.morningMinutes)
            .putBoolean(K_BEDTIME, new.bedtimeEnabled)
            .putInt(K_BEDTIME_MIN, new.bedtimeMinutes)
            .putInt(K_BEDTIME_DAYS, new.bedtimeDays)
            .putInt(K_ALARM_PREP, new.alarmPrepMinutes)
            .putInt(K_ALARM_TRAVEL, new.alarmTravelMinutes)
            .putBoolean(K_ALARM_WORK, new.alarmUsesWorkHours)
            .putBoolean(K_ALARM_SUGGEST, new.alarmSuggest)
            .putInt(K_VOICE_PAUSE, new.voicePauseMs)
            .putBoolean(K_CONTINUE, new.continueConversation)
            .apply()
        _settings.value = new
    }

    private fun load() = AppSettings(
        cloudEnabled = prefs.getBoolean(K_CLOUD_ENABLED, false),
        cloudApiKey = prefs.getString(K_API_KEY, "").orEmpty(),
        cloudModel = prefs.getString(K_MODEL, null) ?: AppSettings.DEFAULT_CLOUD_MODEL,
        gemmaEnabled = prefs.getBoolean(K_GEMMA_ENABLED, true),
        workStartHour = prefs.getInt(K_WORK_START, 9),
        workEndHour = prefs.getInt(K_WORK_END, 18),
        reminderLeadMinutes = prefs.getInt(K_REMINDER_LEAD, 30),
        dateOnlyReminderHour = prefs.getInt(K_DATE_ONLY_HOUR, 9),
        voiceLanguage = prefs.getString(K_VOICE_LANGUAGE, null) ?: AppSettings.DEFAULT_VOICE_LANGUAGE,
        calendarSyncEnabled = prefs.getBoolean(K_CAL_SYNC, false),
        calendarId = prefs.getLong(K_CAL_ID, -1L),
        googleTasksEnabled = prefs.getBoolean(K_GTASKS, false),
        googleTasksLastSync = prefs.getLong(K_GTASKS_LAST, 0L),
        googleTasksListId = prefs.getString(K_GTASKS_LIST, "").orEmpty(),
        wakeWordEnabled = prefs.getBoolean(K_WAKE, false),
        wakeWordScreenOnly = prefs.getBoolean(K_WAKE_SCREEN, true),
        themeMode = prefs.getString(K_THEME, null) ?: "SYSTEM",
        speakReplies = prefs.getBoolean(K_SPEAK, true),
        liveUpdates = prefs.getBoolean(K_LIVE, true),
        mapsApp = prefs.getString(K_MAPS, null).orEmpty(),
        checkInEnabled = prefs.getBoolean(K_CHECKIN, true),
        checkInHour = prefs.getInt(K_CHECKIN_HOUR, 20),
        morningEnabled = prefs.getBoolean(K_MORNING, true),
        morningMinutes = prefs.getInt(K_MORNING_MIN, 8 * 60),
        bedtimeEnabled = prefs.getBoolean(K_BEDTIME, false),
        bedtimeMinutes = prefs.getInt(K_BEDTIME_MIN, 23 * 60),
        bedtimeDays = prefs.getInt(K_BEDTIME_DAYS, 127),
        alarmPrepMinutes = prefs.getInt(K_ALARM_PREP, 60),
        alarmTravelMinutes = prefs.getInt(K_ALARM_TRAVEL, 30),
        alarmUsesWorkHours = prefs.getBoolean(K_ALARM_WORK, true),
        alarmSuggest = prefs.getBoolean(K_ALARM_SUGGEST, true),
        voicePauseMs = prefs.getInt(K_VOICE_PAUSE, 1_500),
        continueConversation = prefs.getBoolean(K_CONTINUE, true)
    )

    private companion object {
        const val K_CLOUD_ENABLED = "cloud_enabled"
        const val K_API_KEY = "cloud_api_key"
        const val K_MODEL = "cloud_model"
        const val K_GEMMA_ENABLED = "gemma_enabled"
        const val K_WORK_START = "work_start_hour"
        const val K_WORK_END = "work_end_hour"
        const val K_REMINDER_LEAD = "reminder_lead_minutes"
        const val K_DATE_ONLY_HOUR = "date_only_reminder_hour"
        const val K_VOICE_LANGUAGE = "voice_language"
        const val K_CAL_SYNC = "calendar_sync"
        const val K_CAL_ID = "calendar_id"
        const val K_GTASKS = "google_tasks"
        const val K_GTASKS_LAST = "google_tasks_last_sync"
        const val K_GTASKS_LIST = "google_tasks_list_id"
        const val K_WAKE = "wake_word"
        const val K_WAKE_SCREEN = "wake_word_screen_only"
        const val K_THEME = "theme_mode"
        const val K_SPEAK = "speak_replies"
        const val K_LIVE = "live_updates"
        const val K_MAPS = "maps_app"
        const val K_CHECKIN = "check_in"
        const val K_CHECKIN_HOUR = "check_in_hour"
        const val K_MORNING = "morning_brief"
        const val K_MORNING_MIN = "morning_minutes"
        const val K_BEDTIME = "bedtime_enabled"
        const val K_BEDTIME_MIN = "bedtime_minutes"
        const val K_BEDTIME_DAYS = "bedtime_days"
        const val K_ALARM_PREP = "alarm_prep"
        const val K_ALARM_TRAVEL = "alarm_travel"
        const val K_ALARM_WORK = "alarm_work"
        const val K_ALARM_SUGGEST = "alarm_suggest"
        const val K_VOICE_PAUSE = "voice_pause_ms"
        const val K_CONTINUE = "continue_conversation"
    }
}
