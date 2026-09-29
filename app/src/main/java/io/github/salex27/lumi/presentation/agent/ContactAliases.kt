package io.github.salex27.lumi.presentation.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

/**
 * Contact aliases: "mum", "my mother", "the boss" → one specific contact. Learned automatically when choosing between
 * several contacts ("Which one?") and managed in Settings → Quick contacts. Stored only on the phone.
 */
class ContactAliases(context: Context) {

    @Serializable
    data class Alias(val alias: String, val name: String, val number: String)

    private val prefs = context.getSharedPreferences("contact_aliases", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _aliases = MutableStateFlow(load())
    val aliases: StateFlow<List<Alias>> = _aliases.asStateFlow()

    fun find(spoken: String): Alias? {
        val key = normalize(spoken)
        return _aliases.value.firstOrNull { it.alias == key }
    }

    fun save(spoken: String, contact: DeviceActions.Contact) {
        val key = normalize(spoken)
        if (key.isBlank()) return
        persist(_aliases.value.filterNot { it.alias == key } + Alias(key, contact.name, contact.number))
    }

    fun remove(alias: String) = persist(_aliases.value.filterNot { it.alias == alias })

    private fun persist(list: List<Alias>) {
        prefs.edit().putString(K_LIST, json.encodeToString(list)).apply()
        _aliases.value = list
    }

    private fun load(): List<Alias> = runCatching {
        json.decodeFromString<List<Alias>>(prefs.getString(K_LIST, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    companion object {
        private const val K_LIST = "list"

        /** "a mi Madre" → "mi madre": no accents, no leading "a/al"/"to". */
        fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("a ").removePrefix("al ").trim()
    }
}
