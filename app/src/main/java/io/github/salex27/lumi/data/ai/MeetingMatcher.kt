package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.AgendaEvent

/**
 * Picks the calendar meeting the user means (pure, tested).
 * - "reunión del sprint" / "sprint meeting" → the next event whose title shares words ("Sprint review").
 * - "la reunión" / "the meeting" (nothing more) → the next event that is a meeting/call.
 */
object MeetingMatcher {

    private val GENERIC = setOf(
        "reunion", "llamada", "meeting", "call", "junta", "videollamada", "entrevista", "presentacion",
        "de", "del", "con", "sobre", "la", "el", "para"
    )
    private val MEETING_WORDS = Regex("(?i)reuni[oó]n|meeting|call|llamada|junta|sync|daily|standup|review|entrevista|1:1|one on one")

    fun match(hint: String, events: List<AgendaEvent>, nowMillis: Long): AgendaEvent? {
        val upcoming = events.filter { !it.allDay && it.end > nowMillis }.sortedBy { it.begin }
        if (upcoming.isEmpty()) return null
        val keywords = tokens(hint) - GENERIC
        if (keywords.isEmpty()) return upcoming.firstOrNull { MEETING_WORDS.containsMatchIn(it.title) }
        return upcoming
            .map { it to (tokens(it.title) intersect keywords).size }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<AgendaEvent, Int>> { it.second }.thenByDescending { it.first.begin })
            ?.first
    }

    private fun tokens(text: String) = CategoryHeuristics.normalize(text)
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
}
