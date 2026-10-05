package io.github.salex27.lumi.domain.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of the unified chat history: an assistant chat, an Orbit thread or an agent inbox. */
data class ChatEntry(
    val id: Long,
    /** ChatSessionEntity.KIND_* */
    val kind: String,
    val title: String,
    val snippet: String,
    val updatedAt: Long
)

/** Entries of one calendar day, newest first. */
data class ChatDayGroup(val day: LocalDate, val entries: List<ChatEntry>)

/** Pure helpers for the Chats screen (grouping by day, search); unit-tested. */
object ChatHistory {

    /** Newest first, grouped by local calendar day. */
    fun group(entries: List<ChatEntry>, zone: ZoneId = ZoneId.systemDefault()): List<ChatDayGroup> =
        entries.sortedByDescending { it.updatedAt }
            .groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() }
            .map { (day, list) -> ChatDayGroup(day, list) }
            .sortedByDescending { it.day }

    /** Case-insensitive match on title and last message; a blank query keeps everything. */
    fun search(entries: List<ChatEntry>, query: String): List<ChatEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { it.title.lowercase().contains(q) || it.snippet.lowercase().contains(q) }
    }

    enum class DayLabel { TODAY, YESTERDAY, WEEKDAY_DATE, FULL_DATE }

    /** How a group header is written: Today, Yesterday, "Monday 5 Oct" within this year, with the year otherwise. */
    fun dayLabel(day: LocalDate, today: LocalDate): DayLabel = when {
        day == today -> DayLabel.TODAY
        day == today.minusDays(1) -> DayLabel.YESTERDAY
        day.year == today.year -> DayLabel.WEEKDAY_DATE
        else -> DayLabel.FULL_DATE
    }

    /** Single-line snippet: newlines collapsed, trimmed, capped. */
    fun snippet(text: String?, max: Int = 120): String =
        text.orEmpty().replace(Regex("\\s+"), " ").trim().take(max)
}
