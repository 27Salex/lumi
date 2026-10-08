package io.github.salex27.lumi.data.orbit

import android.content.Context
import android.util.Log
import io.github.salex27.lumi.data.hub.AgentOptions
import io.github.salex27.lumi.data.hub.HubClient
import io.github.salex27.lumi.data.hub.ModelChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which model and reasoning level Claude on the PC uses: a default (Settings) and an optional choice per Orbit thread.
 * The hub says what exists ([options]); on an older hub there is no endpoint, [options] stays null and the pickers are
 * hidden. Stored in SharedPreferences ("agent_choices"); nothing is sent unless the user chose something.
 */
class AgentChoices(context: Context, private val hub: HubClient) {
    private val prefs = context.getSharedPreferences("agent_choices", Context.MODE_PRIVATE)

    private val _options = MutableStateFlow<AgentOptions?>(null)
    val options: StateFlow<AgentOptions?> = _options.asStateFlow()

    private val _default = MutableStateFlow(ModelChoice(prefs.getString(K_MODEL, null), prefs.getString(K_EFFORT, null)))
    val default: StateFlow<ModelChoice> = _default.asStateFlow()

    /** Bumped on every per-thread change so open screens re-read [thread]. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Asks the hub (best effort): a failure keeps what was known, an old hub (404) clears it. */
    suspend fun refresh() {
        try {
            _options.value = hub.agentOptions()
        } catch (e: Exception) {
            Log.i(TAG, "agent options: ${e.message}")
        }
    }

    fun setDefault(choice: ModelChoice) {
        prefs.edit().putString(K_MODEL, choice.model).putString(K_EFFORT, choice.effort).apply()
        _default.value = choice
    }

    fun thread(sessionId: Long) = ModelChoice(prefs.getString("t${sessionId}_model", null), prefs.getString("t${sessionId}_effort", null))

    fun setThread(sessionId: Long, choice: ModelChoice) {
        prefs.edit().putString("t${sessionId}_model", choice.model).putString("t${sessionId}_effort", choice.effort).apply()
        _version.value++
    }

    /** What to send with the next turn of [sessionId] (empty on a hub without options). */
    fun effective(sessionId: Long): ModelChoice = ModelChoice.resolve(thread(sessionId), _default.value, _options.value)

    @Volatile private var lastTry = 0L

    /** Like [effective], but loads the options first when something was chosen and they are not known yet. */
    suspend fun forTurn(sessionId: Long): ModelChoice {
        val wanted = thread(sessionId).let { it.model != null || it.effort != null } || _default.value.let { it.model != null || it.effort != null }
        if (!wanted) return ModelChoice()
        if (_options.value == null && System.currentTimeMillis() - lastTry > 300_000L) { lastTry = System.currentTimeMillis(); refresh() }
        return effective(sessionId)
    }

    private companion object {
        const val TAG = "LumiChoices"
        const val K_MODEL = "default_model"
        const val K_EFFORT = "default_effort"
    }
}
