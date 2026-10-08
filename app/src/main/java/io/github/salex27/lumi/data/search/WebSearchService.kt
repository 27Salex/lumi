package io.github.salex27.lumi.data.search

import android.content.Context
import android.util.Log
import io.github.salex27.lumi.data.hub.HubClient
import io.github.salex27.lumi.data.server.ServerStore
import io.github.salex27.lumi.domain.server.ServerLogic
import io.github.salex27.lumi.domain.server.ServerService
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Web search settings (opt-in, off by default). The Brave key is a secret: this prefs file is not in the backup. */
data class WebSearchConfig(
    val enabled: Boolean = false,
    val backend: WebSearchBackend = WebSearchBackend.DEVICE,
    /** SearXNG address: for the DEVICE backend an optional public instance, for SEARXNG the fallback when no search server is assigned. */
    val searxUrl: String = "",
    val braveKey: String = ""
)

/**
 * Searches the web for questions Lumi can't answer from its own model (#7). Only the query leaves the phone. While a
 * search runs, [searching] is true so the chat can say "Searching the web…".
 */
class WebSearchService(context: Context, private val hub: HubClient, private val servers: ServerStore? = null) {

    private val prefs = context.getSharedPreferences("web_search", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<WebSearchConfig> = _config.asStateFlow()

    /** Why the last search failed or was empty (null when it worked): shown to the user instead of a silent fallback. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private companion object { const val ENOUGH_HITS = 3 }

    private fun load(): WebSearchConfig {
        var backend = runCatching { WebSearchBackend.valueOf(prefs.getString("backend", null) ?: "") }.getOrDefault(WebSearchBackend.DEVICE)
        // The old default (Wikipedia only) becomes the on-device search, which includes Wikipedia; one time only
        if (!prefs.getBoolean("device_default_v1", false)) {
            if (backend == WebSearchBackend.WIKIPEDIA) backend = WebSearchBackend.DEVICE
            prefs.edit().putBoolean("device_default_v1", true).putString("backend", backend.name).apply()
        }
        return WebSearchConfig(prefs.getBoolean("enabled", false), backend, prefs.getString("searx_url", "").orEmpty(), prefs.getString("brave_key", "").orEmpty())
    }

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
            if (c.backend != WebSearchBackend.WIKIPEDIA && c.backend != WebSearchBackend.DEVICE) fallbackWikipedia(query, lang) else emptyList()
        } finally {
            _searching.value = false
        }
    }

    /** The SearXNG server of the Servers screen, or the address typed in this section (older setups). */
    private fun searxTarget(c: WebSearchConfig): Pair<String, Map<String, String>> {
        val server = servers?.current?.serverFor(ServerService.SEARCH)
        val base = servers?.current?.let { ServerLogic.baseUrl(it, ServerService.SEARCH) } ?: c.searxUrl
        val headers = server?.token?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        return base to headers
    }

    private suspend fun fetch(c: WebSearchConfig, query: String, lang: Lang): List<WebHit> = when (c.backend) {
        WebSearchBackend.DEVICE -> deviceSearch(c, query, lang)
        WebSearchBackend.WIKIPEDIA -> SearchParsers.wikipedia(get(SearchParsers.wikipediaUrl(query, lang)))
        WebSearchBackend.SEARXNG -> {
            val (base, headers) = searxTarget(c)
            val url = SearchParsers.searxUrl(base, query, lang) ?: error("SearXNG address missing or invalid")
            val hits = SearchParsers.searx(get(url, headers))
            if (hits.isEmpty()) {
                Log.w("LumiSearch", "SearXNG returned no results for «$query»; searching from the phone")
                _lastError.value = "SearXNG returned no results"
                runCatching { deviceSearch(c.copy(searxUrl = ""), query, lang) }.getOrDefault(emptyList())
            } else hits
        }
        WebSearchBackend.BRAVE -> if (c.braveKey.isBlank()) error("Brave key missing")
            else SearchParsers.brave(get(SearchParsers.braveUrl(query, lang), mapOf("X-Subscription-Token" to c.braveKey)))
        WebSearchBackend.HUB -> hub.search(query)
    }

    /**
     * Search with no PC: each source has short timeouts and the next one runs when it fails or finds too little.
     * Order: a public SearXNG instance the user chose, DuckDuckGo results, DuckDuckGo instant answer, Wikipedia.
     * Throws only when EVERY source failed (so "no results" and "no connection" stay different messages).
     */
    private fun deviceSearch(c: WebSearchConfig, query: String, lang: Lang): List<WebHit> {
        val browser = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 Lumi/1.0", "Accept" to "text/html,application/xhtml+xml")
        val sources = mutableListOf<Pair<String, () -> List<WebHit>>>()
        SearchParsers.searxUrl(c.searxUrl, query, lang)?.let { url -> sources += "SearXNG" to { SearchParsers.searx(get(url, quick = true)) } }
        sources += "DuckDuckGo" to { SearchParsers.ddgHtml(get(SearchParsers.ddgHtmlUrl(query, lang), browser, quick = true)) }
        sources += "DuckDuckGo instant" to { SearchParsers.ddgInstant(get(SearchParsers.ddgInstantUrl(query), quick = true)) }
        sources += "Wikipedia" to { SearchParsers.wikipedia(get(SearchParsers.wikipediaUrl(query, lang), quick = true)) }
        val found = mutableListOf<WebHit>()
        var failure: Exception? = null
        var answered = false
        for ((name, run) in sources) {
            if (WebAnswers.clean(found).size >= ENOUGH_HITS) break
            try {
                found += run()
                answered = true
            } catch (e: Exception) {
                failure = e
                Log.i("LumiSearch", "$name failed: ${e.message}")
            }
        }
        if (!answered && failure != null) throw failure
        return found
    }

    private fun fallbackWikipedia(query: String, lang: Lang): List<WebHit> = try {
        WebAnswers.clean(SearchParsers.wikipedia(get(SearchParsers.wikipediaUrl(query, lang))))
    } catch (e: Exception) {
        Log.w("LumiSearch", "Wikipedia fallback failed: ${e.message}")
        emptyList()
    }

    private fun describe(b: WebSearchBackend, e: Exception) = "${b.name}: ${e.message ?: e.javaClass.simpleName}"

    private fun get(url: String, headers: Map<String, String> = emptyMap(), quick: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = if (quick) 5_000 else 8_000
            conn.readTimeout = if (quick) 8_000 else 15_000
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


    // ── On-device sources (no PC, no key) ───────────────────────────────────────────────────────────────────────

    fun ddgHtmlUrl(query: String, lang: Lang) = "https://html.duckduckgo.com/html/?q=${enc(query)}&kl=${if (lang == Lang.ES) "es-es" else "us-en"}"

    fun ddgInstantUrl(query: String) = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1&t=lumi"

    private val ANCHOR = Regex("<a\\s([^>]*)>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAGS = Regex("<[^>]*>")

    /**
     * DuckDuckGo's HTML results page: title links (`result__a`) and the snippet link that follows each one
     * (`result__snippet`). The href is a redirect (`//duckduckgo.com/l/?uddg=<encoded url>`) that is unwrapped here;
     * ads (`y.js` redirects) are skipped. Pure and tolerant: unknown markup just yields fewer hits.
     */
    fun ddgHtml(body: String): List<WebHit> {
        val hits = mutableListOf<WebHit>()
        var title: String? = null; var url: String? = null
        fun flush(snippet: String) { val u = url; if (u != null) hits += WebHit(title.orEmpty(), u, snippet); title = null; url = null }
        for (m in ANCHOR.findAll(body)) {
            val attrs = m.groupValues[1]
            val cls = Regex("class=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1).orEmpty()
            when {
                "result__a" in cls -> {
                    flush("")
                    url = ddgTarget(Regex("href=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1).orEmpty())
                    title = htmlText(m.groupValues[2])
                }
                "result__snippet" in cls -> flush(htmlText(m.groupValues[2]))
            }
        }
        flush("")
        return hits
    }

    /** The real target of a DuckDuckGo result link, or null for ads and anything that is not http(s). */
    internal fun ddgTarget(href: String): String? {
        val raw = htmlText(href)
        val encoded = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1)
        val target = if (encoded != null) runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() else raw
        return target?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.takeIf { "duckduckgo.com/y.js" !in it }
    }

    internal fun htmlText(s: String): String = TAGS.replace(s, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    /** DuckDuckGo Instant Answer: the abstract (usually Wikipedia's), direct results and related topics. */
    fun ddgInstant(body: String): List<WebHit> {
        val o = obj(json.parseToJsonElement(body)) ?: return emptyList()
        val hits = mutableListOf<WebHit>()
        val abstract = str(o["AbstractText"]).orEmpty()
        if (abstract.isNotBlank()) hit(o["Heading"], o["AbstractURL"], JsonPrimitive(abstract))?.let { hits += it }
        fun topics(e: JsonElement?) {
            (e as? JsonArray).orEmpty().forEach { t ->
                val to = obj(t) ?: return@forEach
                val text = str(to["Text"])
                if (text != null) hit(JsonPrimitive(text.substringBefore(" - ").take(80)), to["FirstURL"], JsonPrimitive(text))?.let { hits += it }
                else topics(to["Topics"])
            }
        }
        topics(o["Results"]); topics(o["RelatedTopics"])
        return hits
    }

    private fun obj(e: JsonElement?) = e as? JsonObject
    private fun str(e: JsonElement?) = runCatching { e?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun hit(title: JsonElement?, url: JsonElement?, snippet: JsonElement?): WebHit? {
        val u = str(url)?.takeIf { it.isNotBlank() } ?: return null
        return WebHit(str(title).orEmpty(), u, str(snippet).orEmpty())
    }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
