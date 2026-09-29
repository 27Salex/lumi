package com.antigravity.gemininanotaskmanager.data.places

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lugares guardados por el usuario para los avisos por lugar («cuando llegue a casa»).
 * [key] es la clave normalizada que usan las tareas (ver `TaskPhraseParser.normalizePlace`).
 * Se guardan solo en el móvil.
 */
class PlacesStore(context: Context) {

    @Serializable
    data class SavedPlace(
        val key: String,
        val label: String,
        val lat: Double,
        val lng: Double,
        val radiusMeters: Float = DEFAULT_RADIUS,
        /** Dirección (si se añadió buscándola); vacío si se guardó con «Guardar aquí». */
        val address: String = ""
    )

    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _places = MutableStateFlow(load())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    fun get(key: String): SavedPlace? = _places.value.firstOrNull { it.key == key }

    fun isKnown(key: String): Boolean = get(key) != null

    fun save(place: SavedPlace) = persist(_places.value.filterNot { it.key == place.key } + place)

    fun remove(key: String) = persist(_places.value.filterNot { it.key == key })

    private fun persist(list: List<SavedPlace>) {
        prefs.edit().putString(K_LIST, json.encodeToString(list)).apply()
        _places.value = list
    }

    private fun load(): List<SavedPlace> =
        runCatching { json.decodeFromString<List<SavedPlace>>(prefs.getString(K_LIST, null) ?: return emptyList()) }
            .getOrDefault(emptyList())

    companion object {
        private const val K_LIST = "list"
        /** Radio de la geovalla. Por debajo de ~100 m Android es poco fiable. */
        const val DEFAULT_RADIUS = 150f
    }
}
