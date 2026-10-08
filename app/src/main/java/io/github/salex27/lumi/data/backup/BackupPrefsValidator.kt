package io.github.salex27.lumi.data.backup

import io.github.salex27.lumi.domain.assistant.Routine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * What a backup file may write into SharedPreferences. A backup is untrusted input: a wrong type would crash the app at
 * start, and an imported routine is a list of phrases Lumi will run, so everything is checked by key and type.
 * Pure (no Android) so it is unit tested.
 */
object BackupPrefsValidator {

    private val json = Json { ignoreUnknownKeys = true }

    /** Settings keys and the type each one holds ("b" Boolean, "i" Int, "l" Long, "s" String). Secrets/phone-tied keys are absent. */
    private val settings: Map<String, String> = mapOf(
        "cloud_enabled" to "b", "cloud_model" to "s", "gemma_enabled" to "b", "work_start_hour" to "i", "work_end_hour" to "i",
        "reminder_lead_minutes" to "i", "date_only_reminder_hour" to "i", "voice_language" to "s", "wake_word" to "b",
        "wake_word_screen_only" to "b", "theme_mode" to "s", "speak_replies" to "b", "live_updates" to "b", "maps_app" to "s",
        "check_in" to "b", "check_in_hour" to "i", "morning_brief" to "b", "morning_minutes" to "i", "bedtime_enabled" to "b",
        "bedtime_minutes" to "i", "bedtime_days" to "i", "alarm_prep" to "i", "alarm_travel" to "i", "alarm_work" to "b",
        "alarm_suggest" to "b", "voice_pause_ms" to "i", "continue_conversation" to "b"
    )

    private const val MAX_JSON = 200_000
    private const val MAX_ROUTINES = 50
    private const val MAX_STEPS = 20
    private const val MAX_PHRASE = 300

    /** True if [file]/[key] may be written with a value of [type] ("b", "i", "l", "f", "s", "ss") and that [value] (text form). */
    fun accept(file: String, key: String, type: String, value: String?): Boolean = when (file) {
        "assistant_settings" -> settings[key] == type && when (key) {
            "theme_mode" -> value in setOf("SYSTEM", "LIGHT", "DARK")
            "cloud_model", "voice_language", "maps_app" -> (value?.length ?: 0) <= 100
            else -> if (type == "i") value?.toIntOrNull()?.let { it in 0..100_000 } == true else true
        }
        "places", "contact_aliases" -> key == "list" && type == "s" && jsonArray(value)
        "routines" -> key == "list" && type == "s" && routinesOk(value)
        "orbit_routing" -> type == "s" && key.length <= 200 && (value?.length ?: 0) <= 2_000
        else -> false
    }

    private fun jsonArray(v: String?) = v != null && v.length <= MAX_JSON && runCatching { json.parseToJsonElement(v) is JsonArray }.getOrDefault(false)

    private fun routinesOk(v: String?): Boolean {
        if (v == null || v.length > MAX_JSON) return false
        val list = runCatching { json.decodeFromString<List<Routine>>(v) }.getOrNull() ?: return false
        return list.size <= MAX_ROUTINES && list.all { r ->
            r.id.length <= 100 && r.name.length <= 100 && r.triggers.size <= MAX_STEPS && r.steps.size <= MAX_STEPS &&
                (r.triggers + r.steps).all { it.length <= MAX_PHRASE }
        }
    }
}
