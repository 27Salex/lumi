package io.github.salex27.lumi.data.server

import android.content.Context
import io.github.salex27.lumi.R
import io.github.salex27.lumi.domain.server.ServerLogic
import io.github.salex27.lumi.domain.server.ServerProfile
import io.github.salex27.lumi.domain.server.ServerService
import io.github.salex27.lumi.domain.server.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The servers the user defined and which one each PC service uses. Stored in its own SharedPreferences file ("servers",
 * not part of the backup: it holds tokens). The first time it is read, the old separate settings (Lumi Hub address and
 * token, SearXNG address) are copied into it; the old preferences stay where they are, so nothing is lost.
 */
class ServerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ServerState> = _state.asStateFlow()
    val current: ServerState get() = _state.value

    fun update(transform: (ServerState) -> ServerState) {
        val next = transform(_state.value)
        if (next == _state.value) return
        prefs.edit().putString(KEY, ServerLogic.encode(next)).apply()
        _state.value = next
        listeners.forEach { it() }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** Called after every change (used by the Hub settings to follow the assigned server). */
    fun addListener(l: () -> Unit) { listeners += l }

    fun upsert(server: ServerProfile) = update { ServerLogic.upsert(it, server) }
    fun remove(id: String) = update { ServerLogic.remove(it, id) }
    fun assign(service: ServerService, id: String?) = update { ServerLogic.assign(it, service, id) }
    fun useForAll(id: String) = update { ServerLogic.useForAll(it, id) }

    private fun load(): ServerState {
        prefs.getString(KEY, null)?.let { text -> ServerLogic.decode(text)?.let { return it } }
        val hub = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
        val web = context.getSharedPreferences("web_search", Context.MODE_PRIVATE)
        val migrated = ServerLogic.migrate(
            hubAddress = hub.getString("address", "").orEmpty(), hubToken = hub.getString("token", "").orEmpty(),
            searxUrl = web.getString("searx_url", "").orEmpty(), searxWasChosen = web.getString("backend", null) == "SEARXNG",
            defaultName = context.getString(R.string.server_default_name), searxName = context.getString(R.string.server_searx_name)
        )
        // commit() so the copy is stored before the old one goes: the legacy token must not stay behind in plain prefs
        if (prefs.edit().putString(KEY, ServerLogic.encode(migrated)).commit()) hub.edit().remove("token").remove("address").apply()
        return migrated
    }

    private companion object { const val KEY = "state" }
}
