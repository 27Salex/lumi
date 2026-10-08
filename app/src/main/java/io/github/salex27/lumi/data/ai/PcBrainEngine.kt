package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.ReplyLanguage

/** One round trip to Claude on the PC (Lumi Hub). Null = offline, refused, timed out or empty: the next engine answers. */
interface PcBrainTransport {
    /** True when a Hub server is set up (no network call). */
    fun isConfigured(): Boolean

    /** Sends [text] and waits at most [timeoutMs] for the final answer. */
    suspend fun complete(text: String, timeoutMs: Long): String?
}

/**
 * "Claude on my PC (via Hub)" as a brain (#2): the PC's Claude only interprets and phrases, the code still computes
 * dates and plans. Short timeout; after a failure it steps aside for [backoffMs] so a sleeping PC costs one wait, not
 * one per sentence. Uses the Hub server profile, nothing new to configure.
 */
class PcBrainEngine(
    private val transport: PcBrainTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val backoffMs: Long = BACKOFF_MS
) : LlmEngine() {
    @Volatile private var offlineUntil = 0L

    override val displayName: String get() = ReplyLanguage.ui("Claude en tu PC", "Claude on your PC")

    override suspend fun isAvailable() = transport.isConfigured() && clock() >= offlineUntil

    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? {
        val text = prompt(system, user)
        val answer = transport.complete(text, timeoutMs)?.trim()?.takeIf { it.isNotEmpty() }
        offlineUntil = if (answer == null) clock() + backoffMs else 0L
        return answer
    }

    companion object {
        /** The hub accepts 4000 characters per message. */
        private const val MAX = 3_900

        /**
         * Instructions + input in one message. The input is never cut (up to 1500 characters); a long instruction loses
         * its worked examples first (Claude does not need them), then its tail.
         */
        fun prompt(system: String, user: String): String {
            val input = user.take(1_500)
            val head = "[Each message is independent: answer ONLY what the instructions ask, no preamble, no tools, no files.]\n[INSTRUCTIONS]\n"
            val tail = "\n[INPUT]\n$input"
            val budget = MAX - head.length - tail.length
            var instructions = system
            if (instructions.length > budget) instructions = instructions.substringBefore("\nExamples").trimEnd()
            return head + instructions.take(budget) + tail
        }

        const val TIMEOUT_MS = 15_000L
        const val BACKOFF_MS = 120_000L
    }
}
