package io.github.salex27.lumi.data.hub

import io.github.salex27.lumi.data.ai.PcBrainTransport
import io.github.salex27.lumi.data.orbit.AgentChoices
import io.github.salex27.lumi.domain.assistant.Delegation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** [PcBrainTransport] over the Hub: one chat turn on its own thread, fast model and low effort, final reply only. */
class HubBrainTransport(
    private val client: HubClient,
    private val settings: HubSettings,
    private val choices: AgentChoices?,
    private val replies: SharedFlow<HubEvent>
) : PcBrainTransport {
    private val one = Mutex() // the thread runs one turn at a time

    override fun isConfigured() = settings.config.value.isConfigured

    override suspend fun complete(text: String, timeoutMs: Long): String? {
        if (!isConfigured()) return null
        return try {
            one.withLock {
                withTimeoutOrNull(timeoutMs) {
                    coroutineScope {
                        val seen = Channel<HubEvent>(Channel.UNLIMITED)
                        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                            replies.collect { if (it.thread == Delegation.BRAIN_THREAD) seen.trySend(it) }
                        }
                        try {
                            if (choices != null && choices.options.value == null) runCatching { choices.refresh() }
                            val pick = ModelChoice.fast(choices?.options?.value)
                            val turn = client.chat(Delegation.BRAIN_THREAD, "claude", text, pick.model, pick.effort)
                            var answer: String? = null
                            while (true) {
                                val e = seen.receive()
                                if (e.turn != turn || e.type == HubEvent.TURN_DELTA || !e.done) continue
                                if (e.error == null) answer = e.text
                                break
                            }
                            answer
                        } finally {
                            listener.cancel()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }
}
