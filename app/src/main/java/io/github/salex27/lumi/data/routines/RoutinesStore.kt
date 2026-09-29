package io.github.salex27.lumi.data.routines

import android.content.Context
import io.github.salex27.lumi.domain.assistant.Routine
import io.github.salex27.lumi.domain.assistant.RoutineMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** The user's routines ("Good night", "I'm going home"…). The built-in ones can be edited, turned off or restored. */
class RoutinesStore(context: Context) {

    private val prefs = context.getSharedPreferences("routines", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _routines = MutableStateFlow(load())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    fun save(routine: Routine) = persist(
        if (_routines.value.any { it.id == routine.id }) _routines.value.map { if (it.id == routine.id) routine else it }
        else _routines.value + routine
    )

    fun remove(id: String) = persist(_routines.value.filterNot { it.id == id })

    fun restoreDefaults() = persist(RoutineMatcher.DEFAULTS + _routines.value.filter { r -> RoutineMatcher.DEFAULTS.none { it.id == r.id } })

    private fun persist(list: List<Routine>) {
        prefs.edit().putString(K_LIST, json.encodeToString(list)).apply()
        _routines.value = list
    }

    private fun load(): List<Routine> =
        prefs.getString(K_LIST, null)?.let { runCatching { json.decodeFromString<List<Routine>>(it) }.getOrNull() }
            ?: RoutineMatcher.DEFAULTS

    private companion object {
        const val K_LIST = "list"
    }
}
