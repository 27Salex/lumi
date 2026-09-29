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
 * Comentario de Lumi sobre las estadísticas. Se genera en local (sin IA) a propósito:
 * abrir la pestaña no debe gastar peticiones a Gemini ni tiempo de Gemma.
 */
object StatsInsight {
    private val ES = Locale.forLanguageTag("es-ES")

    fun text(s: StatsSummary): String {
        val period = if (s.range == StatsRange.WEEK) "esta semana" else "este mes"
        val prev = if (s.range == StatsRange.WEEK) "la anterior" else "el mes anterior"
        if (s.completed == 0 && s.created == 0) return "Aún no hay actividad $period. ¡Cuéntame qué tienes pendiente y lo iluminamos juntos!"

        val parts = mutableListOf<String>()
        parts += when {
            s.delta > 0 -> "Has completado ${s.completed} ${plural(s.completed)} $period, ${s.delta} más que $prev"
            s.delta < 0 -> "Has completado ${s.completed} ${plural(s.completed)} $period, ${-s.delta} menos que $prev. Sin agobios, vamos a por ello"
            else -> "Has completado ${s.completed} ${plural(s.completed)} $period, igual que $prev"
        }
        s.bestDay?.let {
            val day = if (s.range == StatsRange.WEEK) it.date.dayOfWeek.getDisplayName(TextStyle.FULL, ES)
            else it.date.format(DateTimeFormatter.ofPattern("d 'de' MMMM", ES))
            parts += "tu mejor día fue el $day (${it.completed})"
        }
        if (s.streak >= 2) parts += "llevas ${s.streak} días seguidos cerrando tareas"
        s.byCategory.firstOrNull()?.let { (cat, n) -> parts += "la mayoría fueron de ${cat.label} ($n)" }
        if (s.overdueNow > 0) parts += "ojo: tienes ${s.overdueNow} ${if (s.overdueNow == 1) "tarea vencida" else "tareas vencidas"}"

        return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun plural(n: Int) = if (n == 1) "tarea" else "tareas"
}
