package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.orbit.OrbitContext
import java.time.LocalDateTime

/**
 * Secretary hand-off to Claude on the PC (pure, tested). The message carries a compact context: date/time, language,
 * the last few turns of the chat and (only when the user allowed it with the agent's can_read_tasks) today's tasks and
 * meetings. Titles only, capped. Claude's answer comes back in the same assistant chat (see data/orbit/AssistantDelegate).
 */
object Delegation {
    /** Hub thread of the assistant chat: one Claude session that remembers earlier hand-offs. Not an Orbit thread. */
    const val THREAD = "lumi-assistant"

    /** Hub thread of the PC brain (interpretation / phrasing only). */
    const val BRAIN_THREAD = "lumi-brain"

    private const val MAX_CHARS = 3_800

    fun message(
        request: String,
        now: LocalDateTime,
        language: String,
        recentTurns: List<Pair<String, String>>,
        todayLines: List<String>?
    ): String = buildString {
        append(OrbitContext.header(now, language, todayLines))
        val turns = recentTurns.takeLast(3).filter { it.first.isNotBlank() }
        if (turns.isNotEmpty()) {
            append("\n[Earlier in this chat: ")
            append(turns.joinToString(" | ") { (u, a) -> "user: ${clip(u, 160)}; Lumi: ${clip(a, 160)}" })
            append("]")
        }
        append("\n[Secretary request from the Lumi assistant chat. Act as the user's personal assistant; anything that sends, publishes ")
        append("or changes something outside Lumi must come back as a draft for the user to confirm. Reply phone-sized.]\n")
        append(request.trim())
    }.take(MAX_CHARS)

    private fun clip(s: String, n: Int) = s.replace(Regex("\\s+"), " ").trim().take(n)
}
