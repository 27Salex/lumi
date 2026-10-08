package io.github.salex27.lumi.domain.server

import kotlin.math.min

/** Retry pauses shared by the hub event stream and the My PC viewer (pure, tested). */
object Backoff {
    /** [baseMs] doubling per attempt up to [capMs]. [jitter] in 0..1 shaves up to 25% so retries do not align. */
    fun delayMs(attempt: Int, baseMs: Long = 1_000L, capMs: Long = 30_000L, jitter: Double = 0.0): Long {
        val shift = min(attempt.coerceAtLeast(0), 20)
        val raw = min(baseMs shl shift, capMs)
        return (raw * (1.0 - 0.25 * jitter.coerceIn(0.0, 1.0))).toLong().coerceAtLeast(1L)
    }
}

/** Joins streamed text chunks of an agent turn (`turn_delta` events) so the chat bubble grows live (pure, tested). */
class TurnStreams(private val maxTurns: Int = 16, private val maxChars: Int = 16_000) {
    private val buffers = LinkedHashMap<String, StringBuilder>()

    /** Appends [chunk] to [turn] and returns the whole text so far. */
    fun append(turn: String, chunk: String): String {
        val b = buffers.getOrPut(turn) { StringBuilder() }
        if (b.length < maxChars) b.append(chunk.take(maxChars - b.length))
        while (buffers.size > maxTurns) buffers.remove(buffers.keys.first())
        return b.toString()
    }

    fun current(turn: String): String? = buffers[turn]?.toString()?.takeIf { it.isNotEmpty() }

    /** The final message replaces the stream. */
    fun finish(turn: String) { buffers.remove(turn) }
}
