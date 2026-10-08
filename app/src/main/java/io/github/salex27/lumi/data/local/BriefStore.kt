package io.github.salex27.lumi.data.local

import android.content.Context
import java.time.LocalDate

/**
 * Stores Lumi's last summary so it isn't regenerated every time the app opens
 * (each summary is a Gemini cloud request or several seconds of Gemma).
 * Regenerated only when there is none, it is from another day or in another app language, or the user taps "refresh".
 */
class BriefStore(context: Context) {

    data class SavedBrief(val text: String, val engine: String, val date: LocalDate, val lang: String, val savedAt: Long = 0L, val calendarSig: Int = 0) {
        /** Fresh = today, in the app language, younger than [TTL_MS] and the calendar has not changed since. */
        fun isFresh(today: LocalDate, lang: String, nowMs: Long, calendarSig: Int) =
            date == today && this.lang == lang && nowMs - savedAt in 0..TTL_MS && this.calendarSig == calendarSig
    }

    private val prefs = context.getSharedPreferences("daily_brief", Context.MODE_PRIVATE)

    fun load(): SavedBrief? {
        val text = prefs.getString(K_TEXT, null) ?: return null
        val date = prefs.getString(K_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return SavedBrief(text, prefs.getString(K_ENGINE, "").orEmpty(), date, prefs.getString(K_LANG, "").orEmpty(), prefs.getLong(K_AT, 0L), prefs.getInt(K_SIG, 0))
    }

    fun save(text: String, engine: String, lang: String, date: LocalDate = LocalDate.now(), calendarSig: Int = 0, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putString(K_TEXT, text).putString(K_ENGINE, engine).putString(K_DATE, date.toString()).putString(K_LANG, lang)
            .putLong(K_AT, nowMs).putInt(K_SIG, calendarSig).apply()
    }

    companion object {
        /** A cached brief older than this is rewritten (meetings can change without a signal). */
        const val TTL_MS = 30 * 60_000L

        const val K_TEXT = "text"
        const val K_ENGINE = "engine"
        const val K_DATE = "date"
        const val K_LANG = "lang"
        const val K_AT = "saved_at"
        const val K_SIG = "calendar_sig"
    }
}
