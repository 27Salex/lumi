package io.github.salex27.lumi.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Usar Gemini en la nube como motor adicional (desactivado por defecto: privacidad). */
    val cloudEnabled: Boolean = false,
    val cloudApiKey: String = "",
    /** Alias "latest" para no depender de versiones concretas que Google retira. */
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    /** Usar Gemma on-device cuando el modelo esté descargado. */
    val gemmaEnabled: Boolean = true,
    val workStartHour: Int = 9,
    val workEndHour: Int = 18,
    /** Minutos antes de una cita/fecha con hora en que llega el recordatorio. */
    val reminderLeadMinutes: Int = 30,
    /** Hora del recordatorio para tareas con fecha pero sin hora. */
    val dateOnlyReminderHour: Int = 9,
    /** Idioma del reconocimiento de voz (BCP-47). Español por defecto aunque el móvil esté en inglés. */
    val voiceLanguage: String = DEFAULT_VOICE_LANGUAGE,
    /** Crear eventos en el calendario del móvil para tareas con hora. */
    val calendarSyncEnabled: Boolean = false,
    /** Calendario destino (id de CalendarContract); -1 = ninguno elegido. */
    val calendarId: Long = -1L,
    /** Sincronización bidireccional con Google Tasks (requiere OAuth, ver docs/GOOGLE_TASKS_SETUP.md). */
    val googleTasksEnabled: Boolean = false,
    /** Última sincronización correcta con Google Tasks (epoch millis, 0 = nunca). */
    val googleTasksLastSync: Long = 0L,
    /** Id de la lista «Lumi» en Google Tasks. */
    val googleTasksListId: String = "",
    /** «Oye Lumi»: palabra de activación offline. */
    val wakeWordEnabled: Boolean = false,
    /** Escuchar solo con la pantalla encendida (ahorra batería). */
    val wakeWordScreenOnly: Boolean = true,
    /** "SYSTEM" | "LIGHT" | "DARK" */
    val themeMode: String = "SYSTEM",
    /** Leer en voz alta la respuesta cuando se habla con Lumi por voz (manos libres). */
    val speakReplies: Boolean = true,
    /** Próxima tarea o reunión en la pantalla de bloqueo / Now Bar (actualización en directo). */
    val liveUpdates: Boolean = true,
    /** Paquete de la app de mapas para «Cómo llegar» ("" = preguntar cada vez). */
    val mapsApp: String = "",
    /** Repaso de la tarde: notificación con lo hecho y lo pendiente de hoy. */
    val checkInEnabled: Boolean = true,
    val checkInHour: Int = 20,
    /** Resumen de la mañana: tiempo + primera cita + lo de hoy, en una notificación. */
    val morningEnabled: Boolean = true,
    /** Hora del resumen de la mañana en minutos desde medianoche (8:00 = 480). */
    val morningMinutes: Int = 8 * 60,
    /** Alarma inteligente: minutos para arreglarse y de trayecto. */
    val alarmPrepMinutes: Int = 60,
    val alarmTravelMinutes: Int = 30,
    /** Contar la hora de entrar a trabajar (lunes a viernes) como lo primero del día. */
    val alarmUsesWorkHours: Boolean = true,
    /** En el repaso de la tarde, proponer la alarma de mañana según la agenda. */
    val alarmSuggest: Boolean = true,
    /** Silencio que da por terminada una frase dicha a Lumi (ms). */
    val voicePauseMs: Int = 1_500,
    /** Tras responder por voz, Lumi vuelve a escuchar un momento (conversación seguida). */
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
        const val DEFAULT_VOICE_LANGUAGE = "es-ES"
        val VOICE_LANGUAGES = listOf("es-ES" to "Español (España)", "es-US" to "Español (Latinoamérica)", "en-US" to "English (US)")
    }
}

/**
 * Ajustes persistidos en SharedPreferences (privadas de la app). La API key no sale del dispositivo
 * salvo en las llamadas a la API de Gemini. `allowBackup` está desactivado en el manifest para que
 * tampoco viaje en copias de seguridad.
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
        const val K_ALARM_PREP = "alarm_prep"
        const val K_ALARM_TRAVEL = "alarm_travel"
        const val K_ALARM_WORK = "alarm_work"
        const val K_ALARM_SUGGEST = "alarm_suggest"
        const val K_VOICE_PAUSE = "voice_pause_ms"
        const val K_CONTINUE = "continue_conversation"
    }
}
