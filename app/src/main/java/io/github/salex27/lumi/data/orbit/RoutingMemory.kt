package io.github.salex27.lumi.data.orbit

import android.content.Context
import io.github.salex27.lumi.domain.orbit.RoutingExample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * What the Orbit leader learned (the user's explicit routing choices) and which Orbits route on their own. Local,
 * explicit and inspectable: the list is shown in Orbit and each example can be deleted. Kept in the backup.
 */
class RoutingMemory(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _examples = MutableStateFlow(load())
    val examples: StateFlow<List<RoutingExample>> = _examples.asStateFlow()

    private fun load(): List<RoutingExample> =
        runCatching { json.decodeFromString<List<RoutingExample>>(prefs.getString(K_EXAMPLES, "[]") ?: "[]") }.getOrDefault(emptyList())

    @Synchronized
    fun remember(text: String, agentName: String, now: Long = System.currentTimeMillis()) {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(200).ifBlank { return }
        // The same sentence keeps only its latest choice
        val list = (_examples.value.filterNot { it.text.equals(clean, ignoreCase = true) } + RoutingExample(clean, agentName, now)).takeLast(MAX)
        save(list)
    }

    @Synchronized
    fun forget(example: RoutingExample) = save(_examples.value - example)

    private fun save(list: List<RoutingExample>) {
        prefs.edit().putString(K_EXAMPLES, json.encodeToString(list)).apply()
        _examples.value = list
    }

    /** Orbits on "Lumi routes on its own" (proposals otherwise), by the Orbit's creation time (stable across backups). */
    fun isAuto(orbitCreatedAt: Long): Boolean = prefs.getBoolean("auto_$orbitCreatedAt", false)
    fun setAuto(orbitCreatedAt: Long, on: Boolean) = prefs.edit().putBoolean("auto_$orbitCreatedAt", on).apply()

    companion object {
        const val FILE = "orbit_routing"
        private const val K_EXAMPLES = "examples"
        private const val MAX = 200
    }
}
