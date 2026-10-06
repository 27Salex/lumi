package io.github.salex27.lumi.data.search

import android.content.Context
import android.util.Log
import io.github.salex27.lumi.data.hub.HubClient
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.search.WebAnswers
import io.github.salex27.lumi.domain.search.WebHit
import io.github.salex27.lumi.domain.search.WebSearchBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Web search settings (opt-in, off by default). The Brave key is a secret: this prefs file is not in the backup. */
data class WebSearchConfig(
    val enabled: Boolean = false,
    val backend: WebSearchBackend = WebSearchBackend.WIKIPEDIA,
    val searxUrl: String = "",
    val braveKey: String = ""
)

/**
 * Searches the web for questions Lumi can't answer from its own model (#7). Only the query leaves the phone. While a
 * search runs, [searching] is true so the chat can say "Searching the web…".
 */
class WebSearchService(context: Context, private val hub: HubClient) {

    private val prefs = context.getSharedPreferences("web_search", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<WebSearchConfig> = _config.asStateFlow()

    /** Why the last search failed or was empty (null when it worked): shown to the user instead of a silent fallback. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private fun load() = WebSearchConfig(
        prefs.getBoolean("enabled", false),
        runCatching { WebSearchBackend.valueOf(prefs.getString("backend", null) ?: "") }.getOrDefault(WebSearchBackend.WIKIPEDIA),
        prefs.getString("searx_url", "").orEmpty(),
        prefs.getString("brave_key", "").orEmpty()
    )

    fun save(c: WebSearchConfig) {
        prefs.edit().putBoolean("enabled", c.enabled).putString("backend", c.backend.name)
            .putString("searx_url", c.searxUrl.trim()).putString("brave_key", c.braveKey.trim()).apply()
        _config.value = c.copy(searxUrl = c.searxUrl.trim(), braveKey = c.braveKey.trim())
    }

    /** Results for [question], or null when web search is off (so the caller keeps its old behaviour). */
    suspend fun search(question: String, lang: Lang): List<WebHit>? {
        val c = _config.value
        if (!c.enabled) return null
        val query = WebAnswers.query(question).ifBlank { return emptyList() }
        _searching.value = true
        _lastError.value = null
        return try {
            val raw = withContext(Dispatchers.IO) { fetch(c, query, lang) }
            WebAnswers.clean(raw).also { if (it.isEmpty()) _lastError.value = "no results (${c.backend})" }
        } catch (e: Exception) {
            Log.w("LumiSearch", "search failed (${c.backend}): ${e.message}")
            _lastError.value = describe(c.backend, e)
            // A configured backend that is down must not leave Lumi blind: Wikipedia needs no key and no server
            if (c.backend != WebSearchBackend.WIKIPEDIA) fallbackWikipedia(query, lang) else emptyList()
        } finally {
            _searching.value = false
        }
    }

    private suspend fun fetch(c: WebSearchConfig, query: String, lang: Lang): List<WebHit> = when (c.backend) {
        WebSearchBackend.WIKIPEDIA -> SearchParsers.wikipedia(get(SearchParsers.wikipediaUrl(query, lang)))
        WebSearchBackend.SEARXNG -> {
            val url = SearchParsers.searxUrl(c.searxUrl, query, lang) ?: error("SearXNG address missing or invalid")
            val hits = SearchParsers.searx(get(url))
            if (hits.isEmpty()) {
                Log.w("LumiSearch", "SearXNG returned no results for «$query»; trying Wikipedia")
                _lastError.value = "SearXNG returned no results"
                runCatching { SearchParsers.wikipedia(get(SearchParsers.wikipediaUrl(query, lang))) }.getOrDefault(emptyList())
            } else hits
        }
        WebSearchBackend.BRAVE -> if (c.braveKey.isBlank()) error("Brave key missing")
            else SearchParsers.brave(get(SearchParsers.braveUrl(query, lang), mapOf("X-Subscription-Token" to c.braveKey)))
        WebSearchBackend.HUB -> hub.search(query)
    }

    private fun fallbackWikipedia(query: String, lang: Lang): List<WebHit> = try {
        WebAnswers.clean(SearchParsers.wikipedia(get(SearchParsers.wikipediaUrl(query, lang))))
    } catch (e: Exception) {
        Log.w("LumiSearch", "Wikipedia fallback failed: ${e.message}")
        emptyList()
    }

    private fun describe(b: WebSearchBackend, e: Exception) = "${b.name}: ${e.message ?: e.javaClass.simpleName}"

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            // Wikimedia asks API clients to identify themselves
            conn.setRequestProperty("User-Agent", "Lumi/1.0 (https://github.com/27Salex/lumi)")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText().take(400_000) }
        } finally {
            conn.disconnect()
        }
    }
}

/** URLs and response parsers of the search backends (pure, tested with recorded responses). */
object SearchParsers {
    private val json = Json { ignoreUnknownKeys = true }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun code(lang: Lang) = if (lang == Lang.ES) "es" else "en"

    fun wikipediaUrl(query: String, lang: Lang) = "https://${code(lang)}.wikipedia.org/w/api.php?action=query&format=json" +
        "&formatversion=2&generator=search&gsrlimit=3&gsrsearch=${enc(query)}&prop=extracts%7Cinfo&exintro=1&explaintext=1" +
        "&exsentences=3&exlimit=3&inprop=url&redirects=1"

    fun wikipedia(body: String): List<WebHit> {
        val pages = (obj(json.parseToJsonElement(body))?.get("query") as? JsonObject)?.get("pages") as? JsonArray ?: return emptyList()
        return pages.mapNotNull { obj(it) }
            .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            .mapNotNull { p -> hit(p["title"], p["fullurl"], p["extract"]) }
    }

    /** null when the address isn't an http(s) base URL. */
    fun searxUrl(base: String, query: String, lang: Lang): String? {
        val b = base.trim().trimEnd('/')
        if (!Regex("^https?://[^\\s/@]+(/\\S*)?$", RegexOption.IGNORE_CASE).matches(b)) return null
        return "$b/search?q=${enc(query)}&format=json&language=${code(lang)}&safesearch=1&categories=general"
    }

    fun searx(body: String): List<WebHit> =
        ((obj(json.parseToJsonElement(body))?.get("results")) as? JsonArray).orEmpty().mapNotNull { r ->
            obj(r)?.let { hit(it["title"], it["url"], it["content"]) }
        }

    fun braveUrl(query: String, lang: Lang) =
        "https://api.search.brave.com/res/v1/web/search?q=${enc(query)}&count=4&search_lang=${code(lang)}&safesearch=moderate"

    fun brave(body: String): List<WebHit> =
        (((obj(json.parseToJsonElement(body))?.get("web") as? JsonObject)?.get("results")) as? JsonArray).orEmpty().mapNotNull { r ->
            obj(r)?.let { hit(it["title"], it["url"], it["description"]) }
        }

    /** Lumi Hub's /search answer: {"hits":[{"title","url","snippet"}]}. */
    fun hub(body: String): List<WebHit> =
        ((obj(json.parseToJsonElement(body))?.get("hits")) as? JsonArray).orEmpty().mapNotNull { r ->
            obj(r)?.let { hit(it["title"], it["url"], it["snippet"]) }
        }

    private fun obj(e: JsonElement?) = e as? JsonObject
    private fun str(e: JsonElement?) = runCatching { e?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun hit(title: JsonElement?, url: JsonElement?, snippet: JsonElement?): WebHit? {
        val u = str(url)?.takeIf { it.isNotBlank() } ?: return null
        return WebHit(str(title).orEmpty(), u, str(snippet).orEmpty())
    }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
