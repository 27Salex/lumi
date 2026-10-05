package io.github.salex27.lumi.domain.chat

/**
 * Session rules (pure, tested): when a new session starts on its own and how sessions get their automatic title.
 */
object SessionPolicy {
    /** After this long without messages the next chat starts a new session (the old one stays in the list). */
    const val IDLE_GAP_MILLIS = 3 * 60 * 60_000L
    private const val TITLE_MAX = 42

    /** True when the last session is too old to continue silently (the user can still resume it from the list). */
    fun isStale(lastUpdatedAt: Long, now: Long, gapMillis: Long = IDLE_GAP_MILLIS): Boolean = now - lastUpdatedAt > gapMillis

    /** "remind me to call the dentist tomorrow at 5 please" → "Remind me to call the dentist tomorrow at…". */
    fun titleFrom(firstMessage: String): String {
        val clean = firstMessage.replace(Regex("\\s+"), " ").trim().trim('¿', '¡').trim()
        if (clean.isEmpty()) return ""
        val short = if (clean.length <= TITLE_MAX) clean.trimEnd('.', '?', '!', ',')
        else clean.take(TITLE_MAX).substringBeforeLast(' ').ifBlank { clean.take(TITLE_MAX) }.trimEnd('.', ',', ' ') + "…"
        return short.replaceFirstChar { it.uppercase() }
    }
}

/** One exchange of the conversation as the context builder sees it. */
data class ChatTurn(val user: String, val reply: String, val at: Long = 0L)

/** A stored message reduced to what turn rebuilding needs (pure mirror of the Room row). */
data class StoredMessage(val isUser: Boolean, val text: String, val createdAt: Long, val action: String? = null, val taskIds: List<Long> = emptyList())

/** A rebuilt turn: the user's sentence, Lumi's replies to it, and what it did. */
data class SessionTurn(val user: String, val reply: String, val at: Long, val action: String?, val taskIds: List<Long>)

/**
 * Rebuilds the turns of a stored session (pure, tested). A turn is a user message plus every non-user message after
 * it; replies before the first user message are ignored. Turns whose user message is at or before [foldedUntil] are
 * already inside the session summary and are skipped.
 */
object SessionTurns {
    fun rebuild(messages: List<StoredMessage>, foldedUntil: Long = 0L): List<SessionTurn> {
        val out = mutableListOf<SessionTurn>()
        var user: StoredMessage? = null
        val replies = mutableListOf<StoredMessage>()
        fun flush() {
            val u = user ?: return
            if (u.createdAt > foldedUntil) {
                val last = replies.lastOrNull()
                out += SessionTurn(
                    u.text, replies.joinToString(" ") { it.text }.trim(), last?.createdAt ?: u.createdAt,
                    replies.lastOrNull { it.action != null }?.action, replies.flatMap { it.taskIds }
                )
            }
        }
        for (m in messages) {
            if (m.isUser) { flush(); user = m; replies.clear() } else if (user != null) replies += m
        }
        flush()
        return out
    }
}

/**
 * Builds the conversation note sent to the LLM within a token budget (pure, tested). The local Gemma model has a small
 * context window and slows down with long prompts, so it only gets: the rolling summary of older turns + the newest
 * turns that fit. Turns that no longer fit are reported as [Plan.overflow] so they can be folded into the summary.
 */
object ChatContextBuilder {
    /** Default budget for the conversation note (the interpret prompt itself is ~1,300 tokens). */
    const val DEFAULT_BUDGET_TOKENS = 360
    private const val USER_CHARS = 200
    private const val REPLY_CHARS = 240
    private const val SUMMARY_CHARS = 600

    /** Rough token count: ~3.5 characters per token for Spanish/English text (no tokenizer on the JVM side). */
    fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length + 3) * 2 / 7

    data class Plan(
        /** Newest turns that fit, oldest first. */
        val included: List<ChatTurn>,
        /** Older turns left out (oldest first): candidates for the summary. */
        val overflow: List<ChatTurn>
    )

    fun line(turn: ChatTurn): String = "User: «${turn.user.take(USER_CHARS)}» → Lumi: «${turn.reply.take(REPLY_CHARS)}»"

    fun plan(turns: List<ChatTurn>, summary: String, budgetTokens: Int = DEFAULT_BUDGET_TOKENS): Plan {
        var left = budgetTokens - estimateTokens(summary.take(SUMMARY_CHARS)) - 12
        val kept = ArrayDeque<ChatTurn>()
        var i = turns.size - 1
        while (i >= 0) {
            val cost = estimateTokens(line(turns[i])) + 1
            // The newest turn always goes in (truncated lines are already short)
            if (cost > left && kept.isNotEmpty()) break
            kept.addFirst(turns[i])
            left -= cost
            i--
        }
        return Plan(kept.toList(), turns.subList(0, i + 1).toList())
    }

    /** "[EARLIER IN THIS CHAT: …] [RECENT CONVERSATION: …]" or "" when there is nothing. */
    fun note(turns: List<ChatTurn>, summary: String, budgetTokens: Int = DEFAULT_BUDGET_TOKENS, lastTask: String? = null): String {
        val p = plan(turns, summary, budgetTokens)
        if (p.included.isEmpty() && summary.isBlank() && lastTask == null) return ""
        return buildString {
            if (summary.isNotBlank()) append("[EARLIER IN THIS CHAT: ").append(summary.take(SUMMARY_CHARS)).append("]")
            if (p.included.isNotEmpty() || lastTask != null) {
                if (isNotEmpty()) append('\n')
                append("[RECENT CONVERSATION: ")
                append(p.included.joinToString(" | ") { line(it) })
                lastTask?.let { append(if (p.included.isEmpty()) "" else " | ").append("LAST TASK: «$it»") }
                append("]")
            }
        }
    }

    /** True when enough turns overflow to be worth a summary call (batched so the model isn't called every turn). */
    fun shouldSummarize(plan: Plan, minOverflow: Int = 3): Boolean = plan.overflow.size >= minOverflow

    /** Instructions for the LLM that folds old turns into the rolling summary. */
    const val SUMMARY_SYSTEM = "You keep a running summary of a chat between a user and their assistant Lumi. " +
        "Merge the PREVIOUS SUMMARY and the NEW TURNS into at most 3 short sentences, in the user's language. Keep names, " +
        "tasks, dates, decisions and open questions; drop greetings and small talk. Reply ONLY with the summary."

    fun summaryUser(previous: String, turns: List<ChatTurn>): String =
        "PREVIOUS SUMMARY: ${previous.ifBlank { "(none)" }}\nNEW TURNS:\n" + turns.joinToString("\n") { line(it) }

    /** Summary without an LLM: the user's requests, newest last, cut to fit. */
    fun ruleSummary(previous: String, turns: List<ChatTurn>): String {
        val asked = turns.joinToString("; ") { it.user.replace(Regex("\\s+"), " ").take(80) }
        val merged = listOf(previous.trim(), if (asked.isBlank()) "" else "User asked: $asked").filter { it.isNotBlank() }.joinToString(" ")
        return if (merged.length <= SUMMARY_CHARS) merged else "…" + merged.takeLast(SUMMARY_CHARS - 1)
    }
}
