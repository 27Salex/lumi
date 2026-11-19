package io.github.salex27.lumi.domain.model

import io.github.salex27.lumi.domain.assistant.Lang

/**
 * What an item of the list is.
 * - [TASK]: something to do and check off.
 * - [REMINDER]: just a nudge at a moment ("take the pill at 22:00"); it closes itself once its time has passed.
 * - [BLOCK]: a slot of the weekly schedule with a start and an end ("work 9:00-17:00"); it closes itself when it ends.
 * Reminders and blocks never go to the phone calendar or to Google Tasks, so planning the week does not flood them.
 */
enum class TaskKind(private val es: String, private val en: String) {
    TASK("Tarea", "Task"), REMINDER("Recordatorio", "Reminder"), BLOCK("Horario", "Schedule");

    fun label(lang: Lang): String = if (lang == Lang.EN) en else es

    companion object {
        fun fromString(value: String?): TaskKind? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
