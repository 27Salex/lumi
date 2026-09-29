package io.github.salex27.lumi.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.salex27.lumi.domain.repository.TaskRepository
import io.github.salex27.lumi.domain.stats.StatsCalculator
import io.github.salex27.lumi.domain.stats.StatsRange
import io.github.salex27.lumi.domain.stats.StatsSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage

class StatsViewModel(repository: TaskRepository) : ViewModel() {

    private val _range = MutableStateFlow(StatsRange.WEEK)

    val summary: StateFlow<StatsSummary?> = combine(repository.getAllTasks(), _range) { tasks, range ->
        StatsCalculator.compute(tasks, range)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setRange(range: StatsRange) { _range.value = range }

    class Factory(private val repository: TaskRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(repository) as T
    }
}

/**
 * Lumi's comment on the stats. Generated locally (no AI) on purpose: opening the tab shouldn't spend Gemini
 * requests or Gemma time. In the app's language.
 */
object StatsInsight {

    fun text(s: StatsSummary, lang: Lang = ReplyLanguage.app): String {
        val en = lang == Lang.EN
        val locale = if (en) Locale.ENGLISH else Locale.forLanguageTag("es-ES")
        val week = s.range == StatsRange.WEEK
        val period = if (en) (if (week) "this week" else "this month") else (if (week) "esta semana" else "este mes")
        val prev = if (en) (if (week) "last week" else "last month") else (if (week) "la anterior" else "el mes anterior")
        if (s.completed == 0 && s.created == 0) return if (en) "No activity $period yet. Tell me what you have pending and we'll light it up together!"
        else "Aún no hay actividad $period. ¡Cuéntame qué tienes pendiente y lo iluminamos juntos!"

        val done = "${s.completed} ${plural(s.completed, en)} $period"
        val parts = mutableListOf<String>()
        parts += if (en) when {
            s.delta > 0 -> "You completed $done, ${s.delta} more than $prev"
            s.delta < 0 -> "You completed $done, ${-s.delta} fewer than $prev. No stress, let's go for it"
            else -> "You completed $done, the same as $prev"
        } else when {
            s.delta > 0 -> "Has completado $done, ${s.delta} más que $prev"
            s.delta < 0 -> "Has completado $done, ${-s.delta} menos que $prev. Sin agobios, vamos a por ello"
            else -> "Has completado $done, igual que $prev"
        }
        s.bestDay?.let {
            val day = if (week) it.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else it.date.format(DateTimeFormatter.ofPattern(if (en) "MMMM d" else "d 'de' MMMM", locale))
            parts += if (en) "your best day was $day (${it.completed})" else "tu mejor día fue el $day (${it.completed})"
        }
        if (s.streak >= 2) parts += if (en) "you've closed tasks ${s.streak} days in a row" else "llevas ${s.streak} días seguidos cerrando tareas"
        s.byCategory.firstOrNull()?.let { (cat, n) ->
            parts += if (en) "most were ${cat.label(lang)} ($n)" else "la mayoría fueron de ${cat.label(lang)} ($n)"
        }
        if (s.overdueNow > 0) parts += if (en) "heads up: you have ${s.overdueNow} overdue ${plural(s.overdueNow, true)}"
        else "ojo: tienes ${s.overdueNow} ${if (s.overdueNow == 1) "tarea vencida" else "tareas vencidas"}"

        return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun plural(n: Int, en: Boolean) = if (en) (if (n == 1) "task" else "tasks") else (if (n == 1) "tarea" else "tareas")
}
