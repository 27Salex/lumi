package io.github.salex27.lumi.data.ai

import android.util.Log
import io.github.salex27.lumi.domain.ai.AssistantEngine
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.model.TaskAICommand
import java.time.LocalDateTime

/**
 * Shared base for the LLM engines: prompts and parsing live in [AssistantPrompts]; each provider only implements
 * [complete]. Any failure returns null → the orchestrator moves on to the next engine, so the app never gets stuck.
 */
abstract class LlmEngine : AssistantEngine {

    /** Raw call to the model. Returns the text, or null on failure. */
    protected abstract suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String?

    /** What a call is for: engines with a fast and a strong model (Anthropic) pick per purpose. */
    enum class Purpose { INTERPRET, REPLY, ASK }

    protected open suspend fun completeFor(purpose: Purpose, system: String, user: String, maxTokens: Int, temperature: Float): String? =
        complete(system, user, maxTokens, temperature)

    override suspend fun interpret(prompt: String, now: LocalDateTime): TaskAICommand? {
        val raw = safeComplete(Purpose.INTERPRET, AssistantPrompts.INTERPRET_SYSTEM, AssistantPrompts.interpretUser(prompt, now), 256, 0.1f)
            ?: return null
        Log.i("LumiInterpret", "$displayName (raw): ${raw.replace('\n', ' ').take(400)}")
        return AssistantPrompts.parseCommand(raw).also {
            if (it == null) Log.w(TAG, "$displayName returned invalid JSON: $raw")
        }
    }

    override suspend fun writeReply(request: ReplyRequest): String? =
        safeComplete(Purpose.REPLY, AssistantPrompts.REPLY_SYSTEM, AssistantPrompts.replyUser(request), 400, 0.7f)
            ?.let(AssistantPrompts::cleanReply)

    override suspend fun ask(system: String, user: String, maxTokens: Int): String? =
        safeComplete(Purpose.ASK, system, user, maxTokens, 0.1f)

    private suspend fun safeComplete(purpose: Purpose, system: String, user: String, maxTokens: Int, temperature: Float): String? =
        try {
            completeFor(purpose, system, user, maxTokens, temperature)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "$displayName failed (${e.javaClass.simpleName}): ${e.message}")
            null
        }

    private companion object {
        const val TAG = "LlmEngine"
    }
}
