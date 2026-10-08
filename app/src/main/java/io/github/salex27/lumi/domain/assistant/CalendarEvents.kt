package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.AgendaEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Pure clean-up of phone calendar events before the brief, the day plan and the smart alarm use them (tested). */
object CalendarEvents {

    /**
     * The events that really belong to [date]: all-day events are stored as UTC midnight to midnight, so they are
     * compared by their UTC date (a local-time window would pull in the neighbouring day's all-day event); timed events
     * must overlap the local day. Copies of one event shown by several calendars (same title and times) count once.
     * All-day first, then by start time.
     */
    fun forDay(events: List<AgendaEvent>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AgendaEvent> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return events.filter { e ->
            if (e.allDay) {
                val first = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
                val lastExclusive = Instant.ofEpochMilli(maxOf(e.end, e.begin + 1)).atZone(ZoneOffset.UTC).toLocalDate()
                !date.isBefore(first) && date.isBefore(maxOf(lastExclusive, first.plusDays(1)))
            } else e.begin < dayEnd && maxOf(e.end, e.begin + 1) > dayStart
        }.let(::dedupe).sortedWith(compareBy({ !it.allDay }, { it.begin }))
    }

    /** Same title (ignoring case/spaces), start, end and all-day flag = the same event copied across calendars. */
    fun dedupe(events: List<AgendaEvent>): List<AgendaEvent> =
        events.distinctBy { listOf(it.title.trim().lowercase(), it.begin, it.end, it.allDay) }

    /** Changes when the set of events (what the brief mentions) changes; used to refresh a cached brief. */
    fun signature(events: List<AgendaEvent>): Int =
        events.sortedWith(compareBy({ it.begin }, { it.id })).fold(7) { h, e -> 31 * h + listOf(e.title, e.begin, e.end, e.allDay, e.location).hashCode() }
}
