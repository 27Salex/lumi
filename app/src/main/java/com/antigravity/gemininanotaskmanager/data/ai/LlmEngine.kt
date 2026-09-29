package com.antigravity.gemininanotaskmanager.data.ai

import android.util.Log
import com.antigravity.gemininanotaskmanager.domain.ai.AssistantEngine
import com.antigravity.gemininanotaskmanager.domain.ai.ReplyRequest
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import java.time.LocalDateTime

/**
 * Base común de los motores LLM: prompts y parseo viven en [AssistantPrompts];
 * cada proveedor solo implementa [complete]. Cualquier fallo devuelve null → el orquestador
 * pasa al siguiente motor, así la app nunca se bloquea.
 */
abstract class LlmEngine : AssistantEngine {

    /** Llamada cruda al modelo. Devuelve el texto o null si falla. */
    protected abstract suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String?

    override suspend fun interpret(prompt: String, now: LocalDateTime): TaskAICommand? {
        val raw = safeComplete(AssistantPrompts.INTERPRET_SYSTEM, AssistantPrompts.interpretUser(prompt, now), 256, 0.1f)
            ?: return null
        Log.i("LumiInterpret", "$displayName (crudo): ${raw.replace('\n', ' ').take(400)}")
        return AssistantPrompts.parseCommand(raw).also {
            if (it == null) Log.w(TAG, "$displayName devolvió JSON inválido: $raw")
        }
    }

    override suspend fun writeReply(request: ReplyRequest): String? =
        safeComplete(AssistantPrompts.REPLY_SYSTEM, AssistantPrompts.replyUser(request), 400, 0.7f)
            ?.let(AssistantPrompts::cleanReply)

    override suspend fun ask(system: String, user: String, maxTokens: Int): String? =
        safeComplete(system, user, maxTokens, 0.1f)

    private suspend fun safeComplete(system: String, user: String, maxTokens: Int, temperature: Float): String? =
        try {
            complete(system, user, maxTokens, temperature)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "$displayName falló (${e.javaClass.simpleName}): ${e.message}")
            null
        }

    private companion object {
        const val TAG = "LlmEngine"
    }
}
