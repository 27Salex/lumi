package io.github.salex27.lumi.domain.stats

import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class StatsRange(val days: Int, val label: String) {
    WEEK(7, "Semana"),
    MONTH(30, "Mes")
}

data class DayStat(val date: LocalDate, val completed: Int, val created: Int)

data class StatsSummary(
    val range: StatsRange,
    val days: List<DayStat>,
    val completed: Int,
    val created: Int,
    /** Completadas en el periodo anterior de igual duración (para la variación). */
    val previousCompleted: Int,
    /** Días seguidos (hasta hoy, o hasta ayer si hoy aún no hay ninguna) con al menos una tarea completada. */
    val streak: Int,
    val bestDay: DayStat?,
    val byCategory: List<Pair<TaskCategory, Int>>,
    val openNow: Int,
    val overdueNow: Int
) {
    /** % de lo creado en el periodo que ya está completado. null si no se creó nada. */
    val completionRate: Int? get() = if (created == 0) null else (completed * 100 / created).coerceAtMost(100)
    val delta: Int get() = completed - previousCompleted
}

/** Cálculo puro de estadísticas (testeable en JVM). */
object StatsCalculator {

    fun compute(
        tasks: List<Task>,
        range: StatsRange,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): StatsSummary {
        fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

        val start = today.minusDays(range.days - 1L)
        val prevStart = start.minusDays(range.days.toLong())

        val completedDates = tasks.mapNotNull { it.completedAt?.toDate() }
        val createdDates = tasks.map { it.createdAt.toDate() }

        val days = (0 until range.days).map { i ->
            val d = start.plusDays(i.toLong())
            DayStat(d, completed = completedDates.count { it == d }, created = createdDates.count { it == d })
        }

        val completedSet = completedDates.toSet()
        var streakDay = if (today in completedSet) today else today.minusDays(1)
        var streak = 0
        while (streakDay in completedSet) { streak++; streakDay = streakDay.minusDays(1) }

        val inRange = { d: LocalDate -> !d.isBefore(start) && !d.isAfter(today) }
        val byCategory = TaskCategory.entries
            .map { cat -> cat to tasks.count { t -> t.category == cat && t.completedAt?.toDate()?.let(inRange) == true } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        val active = tasks.filter { it.isActive }
        return StatsSummary(
            range = range,
            days = days,
            completed = days.sumOf { it.completed },
            created = days.sumOf { it.created },
            previousCompleted = completedDates.count { !it.isBefore(prevStart) && it.isBefore(start) },
            streak = streak,
            bestDay = days.filter { it.completed > 0 }.maxByOrNull { it.completed },
            byCategory = byCategory,
            openNow = active.size,
            overdueNow = active.count { t ->
                val due = t.dueAt ?: return@count false
                if (t.dueHasTime) due < nowMillis else due.toDate().isBefore(today)
            }
        )
    }
}
