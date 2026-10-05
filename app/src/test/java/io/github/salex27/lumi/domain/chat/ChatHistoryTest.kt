package io.github.salex27.lumi.domain.chat

import io.github.salex27.lumi.domain.chat.ChatHistory.DayLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChatHistoryTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: String, hour: Int) = LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun e(id: Long, title: String, ts: Long, snippet: String = "", kind: String = "ASSISTANT") = ChatEntry(id, kind, title, snippet, ts)

    @Test fun groupsByDayNewestFirst() {
        val groups = ChatHistory.group(
            listOf(e(1, "a", at("2026-10-03", 9)), e(2, "b", at("2026-10-05", 8)), e(3, "c", at("2026-10-05", 20)), e(4, "d", at("2026-10-04", 1))), zone
        )
        assertEquals(listOf("2026-10-05", "2026-10-04", "2026-10-03"), groups.map { it.day.toString() })
        assertEquals(listOf(3L, 2L), groups[0].entries.map { it.id })
    }

    @Test fun searchMatchesTitleAndSnippetIgnoringCase() {
        val l = listOf(e(1, "Dentist", 1, "call at 5"), e(2, "Trip", 2, "Flights to ROME"), e(3, "Other", 3))
        assertEquals(listOf(1L), ChatHistory.search(l, " dent ").map { it.id })
        assertEquals(listOf(2L), ChatHistory.search(l, "rome").map { it.id })
        assertEquals(3, ChatHistory.search(l, "  ").size)
        assertEquals(0, ChatHistory.search(l, "zzz").size)
    }

    @Test fun dayLabels() {
        val today = LocalDate.parse("2026-10-05")
        assertEquals(DayLabel.TODAY, ChatHistory.dayLabel(today, today))
        assertEquals(DayLabel.YESTERDAY, ChatHistory.dayLabel(today.minusDays(1), today))
        assertEquals(DayLabel.WEEKDAY_DATE, ChatHistory.dayLabel(LocalDate.parse("2026-03-01"), today))
        assertEquals(DayLabel.FULL_DATE, ChatHistory.dayLabel(LocalDate.parse("2025-12-31"), today))
    }

    @Test fun snippetCollapsesWhitespaceAndCaps() {
        assertEquals("a b c", ChatHistory.snippet("  a\n\nb   c "))
        assertEquals(5, ChatHistory.snippet("x".repeat(50), 5).length)
        assertEquals("", ChatHistory.snippet(null))
    }
}
