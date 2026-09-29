package com.antigravity.gemininanotaskmanager.data.local

import android.content.Context
import java.time.LocalDate

/**
 * Guarda el último resumen de Lumi para no regenerarlo cada vez que se abre la app
 * (cada resumen es una petición a Gemini cloud o varios segundos de Gemma).
 * Se regenera solo si no hay ninguno, si es de otro día, o si el usuario pulsa «actualizar».
 */
class BriefStore(context: Context) {

    data class SavedBrief(val text: String, val engine: String, val date: LocalDate)

    private val prefs = context.getSharedPreferences("daily_brief", Context.MODE_PRIVATE)

    fun load(): SavedBrief? {
        val text = prefs.getString(K_TEXT, null) ?: return null
        val date = prefs.getString(K_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return SavedBrief(text, prefs.getString(K_ENGINE, "").orEmpty(), date)
    }

    fun save(text: String, engine: String, date: LocalDate = LocalDate.now()) {
        prefs.edit().putString(K_TEXT, text).putString(K_ENGINE, engine).putString(K_DATE, date.toString()).apply()
    }

    private companion object {
        const val K_TEXT = "text"
        const val K_ENGINE = "engine"
        const val K_DATE = "date"
    }
}
