package io.github.salex27.lumi.data.local

import android.content.Context
import java.time.LocalDate

/**
 * Stores Lumi's last summary so it isn't regenerated every time the app opens
 * (each summary is a Gemini cloud request or several seconds of Gemma).
 * Regenerated only when there is none, it is from another day or in another app language, or the user taps "refresh".
 */
class BriefStore(context: Context) {

    data class SavedBrief(val text: String, val engine: String, val date: LocalDate, val lang: String)

    private val prefs = context.getSharedPreferences("daily_brief", Context.MODE_PRIVATE)

    fun load(): SavedBrief? {
        val text = prefs.getString(K_TEXT, null) ?: return null
        val date = prefs.getString(K_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return SavedBrief(text, prefs.getString(K_ENGINE, "").orEmpty(), date, prefs.getString(K_LANG, "").orEmpty())
    }

    fun save(text: String, engine: String, lang: String, date: LocalDate = LocalDate.now()) {
        prefs.edit().putString(K_TEXT, text).putString(K_ENGINE, engine).putString(K_DATE, date.toString()).putString(K_LANG, lang).apply()
    }

    private companion object {
        const val K_TEXT = "text"
        const val K_ENGINE = "engine"
        const val K_DATE = "date"
        const val K_LANG = "lang"
    }
}
