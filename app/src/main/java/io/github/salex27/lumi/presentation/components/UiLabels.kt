package io.github.salex27.lumi.presentation.components

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import java.util.Locale

/*
 * Domain labels in the app's display language. Screens use these instead of `label` (which follows the language of the
 * last conversation), so an English chat doesn't turn the lists English in a Spanish app.
 */
val TaskCategory.uiLabel: String get() = label(ReplyLanguage.app)
val TaskPriority.uiLabel: String get() = label(ReplyLanguage.app)
val PlaceTrigger.uiLabel: String get() = describe(ReplyLanguage.app)

/** Locale for dates on screen ("Monday, March 3" / "lunes, 3 de marzo"). */
val uiLocale: Locale get() = if (ReplyLanguage.app == Lang.EN) Locale.ENGLISH else Locale.forLanguageTag("es-ES")

/** Long day header: "Monday, March 3" / "Lunes, 3 de marzo". */
val uiDayPattern: String get() = if (ReplyLanguage.app == Lang.EN) "EEEE, MMMM d" else "EEEE, d 'de' MMMM"
