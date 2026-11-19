package io.github.salex27.lumi.domain.relay

import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskKind
import io.github.salex27.lumi.domain.schedule.PlanBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class PlanBuilderTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val monday = LocalDate.of(2026, 11, 16)
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun `recurring blocks are projected onto the rest of the week`() {
        val gym = Task(id = 1, title = "Gym", kind = TaskKind.BLOCK, dueAt = at(monday, 19), dueHasTime = true, durationMinutes = 60,
            recurrence = Recurrence(Recurrence.Frequency.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        val days = PlanBuilder.week(listOf(gym), emptyMap(), monday, zone = zone)
        assertEquals(listOf(1, 0, 1, 0, 1, 0, 0), days.map { it.entries.size })
        assertTrue((days[2].entries[0] as PlanBuilder.Entry.Item).projected)
        assertEquals(60, days[4].plannedMinutes)
    }

    @Test fun `untimed items come first, then by time`() {
        val a = Task(id = 1, title = "Late", dueAt = at(monday, 18), dueHasTime = true)
        val b = Task(id = 2, title = "Early", dueAt = at(monday, 8), dueHasTime = true)
        val c = Task(id = 3, title = "Any time", dueAt = monday.atStartOfDay(zone).toInstant().toEpochMilli())
        val day = PlanBuilder.week(listOf(a, b, c), emptyMap(), monday, zone = zone)[0]
        assertEquals(listOf("Any time", "Early", "Late"), day.entries.map { (it as PlanBuilder.Entry.Item).task.title })
        assertEquals(3, day.openTasks)
    }
}
