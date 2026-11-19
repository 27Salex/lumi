package io.github.salex27.lumi.domain.relay

import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskKind
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.schedule.ScheduleSweep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RelayItemsTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val today = LocalDate.of(2026, 11, 19)
    private val now = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun at(d: LocalDateTime) = d.atZone(zone).toInstant().toEpochMilli()

    @Test fun `each created item keeps its own date and time`() {
        val a = RelayItems.newTask(obj("""{"title":"Dentist","date":"2026-11-24","time":"17:30"}"""), zone, today, now) { null }
        val b = RelayItems.newTask(obj("""{"title":"Report","date":"2026-11-27"}"""), zone, today, now) { null }
        assertEquals(at(LocalDateTime.of(2026, 11, 24, 17, 30)), a.dueAt)
        assertTrue(a.dueHasTime)
        assertEquals(LocalDate.of(2026, 11, 27).atStartOfDay(zone).toInstant().toEpochMilli(), b.dueAt)
        assertFalse(b.dueHasTime)
    }

    @Test fun `a block gets its length from end_time, also across midnight`() {
        val work = RelayItems.newTask(obj("""{"title":"Work","kind":"block","date":"2026-11-20","time":"09:00","end_time":"17:00","category":"work"}"""), zone, today, now) { null }
        assertEquals(TaskKind.BLOCK, work.kind)
        assertEquals(480, work.durationMinutes)
        assertEquals(TaskCategory.WORK, work.category)
        val night = RelayItems.newTask(obj("""{"title":"Shift","kind":"block","date":"2026-11-20","time":"22:00","end_time":"06:00"}"""), zone, today, now) { null }
        assertEquals(480, night.durationMinutes)
    }

    @Test fun `a recurring item without a date starts on its first day`() {
        val gym = RelayItems.newTask(obj("""{"title":"Gym","kind":"block","time":"19:00","duration_minutes":60,"repeat":"weekly:mo,we"}"""), zone, today, now) { null }
        // 2026-11-19 is a Thursday → next Monday
        assertEquals(at(LocalDateTime.of(2026, 11, 23, 19, 0)), gym.dueAt)
        assertEquals("weekly:mo,we", RelayItems.repeatOut(gym.recurrence!!))
    }

    @Test(expected = RelayItems.Invalid::class) fun `bad dates are rejected`() {
        RelayItems.newTask(obj("""{"title":"X","date":"tomorrow"}"""), zone, today, now) { null }
    }

    @Test fun `update moves the date but keeps the time`() {
        val t = Task(id = 3, title = "Call", dueAt = at(LocalDateTime.of(2026, 11, 20, 18, 0)), dueHasTime = true)
        val moved = RelayItems.applyUpdate(t, obj("""{"id":3,"date":"2026-11-25"}"""), zone, today, now)
        assertEquals(at(LocalDateTime.of(2026, 11, 25, 18, 0)), moved.dueAt)
        val done = RelayItems.applyUpdate(t, obj("""{"id":3,"status":"done"}"""), zone, today, now)
        assertEquals(TaskStatus.COMPLETED, done.status)
        val cleared = RelayItems.applyUpdate(t, obj("""{"id":3,"clear_date":true}"""), zone, today, now)
        assertNull(cleared.dueAt)
    }

    @Test fun `snapshot json carries local date, time and range`() {
        val b = Task(id = 7, title = "Work", kind = TaskKind.BLOCK, dueAt = at(LocalDateTime.of(2026, 11, 20, 9, 0)), dueHasTime = true, durationMinutes = 480)
        val j = RelayItems.toJson(b, zone)
        assertEquals("2026-11-20", j["date"]!!.jsonPrimitive.content)
        assertEquals("09:00", j["time"]!!.jsonPrimitive.content)
        assertEquals("17:00", j["end_time"]!!.jsonPrimitive.content)
        assertEquals("block", j["kind"]!!.jsonPrimitive.content)
    }

    @Test fun `reminders and blocks close themselves, tasks never`() {
        val block = Task(id = 1, title = "Work", kind = TaskKind.BLOCK, dueAt = at(LocalDateTime.of(2026, 11, 19, 8, 0)), dueHasTime = true, durationMinutes = 60)
        val running = block.copy(durationMinutes = 180)
        val reminder = Task(id = 2, title = "Pill", kind = TaskKind.REMINDER, dueAt = at(LocalDateTime.of(2026, 11, 19, 9, 50)), dueHasTime = true)
        val task = Task(id = 3, title = "Report", dueAt = at(LocalDateTime.of(2026, 11, 18, 9, 0)), dueHasTime = true)
        assertTrue(ScheduleSweep.isOver(block, now, zone))
        assertFalse(ScheduleSweep.isOver(running, now, zone))
        assertTrue(ScheduleSweep.isOver(reminder, now, zone))
        assertFalse(ScheduleSweep.isOver(task, now, zone))
    }
}
