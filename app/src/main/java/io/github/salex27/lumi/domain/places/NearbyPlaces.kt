package io.github.salex27.lumi.domain.places

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place near the phone. All text comes from OpenStreetMap, so it is UNTRUSTED: shown, never interpreted. */
@Serializable
data class NearbyPlace(
    val name: String, val lat: Double, val lng: Double, val distanceM: Int,
    /** True only when OSM says "24/7"; null = unknown (opening hours aren't evaluated). */
    val open: Boolean? = null,
    val kind: String? = null
) {
    val distanceLabel: String get() = if (distanceM < 1000) "$distanceM m" else "%.1f km".format(java.util.Locale.US, distanceM / 1000.0)
}

/** What kind of place the user is after, with the OpenStreetMap filters that find it. */
enum class NearbyCategory(val osm: List<String>, val es: String, val en: String) {
    FOOD(listOf("amenity=restaurant", "amenity=fast_food"), "sitios para comer", "places to eat"),
    COFFEE(listOf("amenity=cafe"), "cafeterías", "cafes"),
    BAR(listOf("amenity=bar", "amenity=pub"), "bares", "bars"),
    PHARMACY(listOf("amenity=pharmacy"), "farmacias", "pharmacies"),
    SUPERMARKET(listOf("shop=supermarket", "shop=convenience"), "supermercados", "supermarkets"),
    BAKERY(listOf("shop=bakery"), "panaderías", "bakeries"),
    GAS(listOf("amenity=fuel"), "gasolineras", "gas stations"),
    ATM(listOf("amenity=atm", "amenity=bank"), "cajeros", "ATMs"),
    HOSPITAL(listOf("amenity=hospital", "amenity=clinic"), "centros de salud", "clinics and hospitals"),
    PARKING(listOf("amenity=parking"), "aparcamientos", "parking")
}

/** [cheap]: the user asked for cheap places (OSM has no prices: it only widens to fast food and simple cuisines). */
data class NearbyQuery(val category: NearbyCategory, val cheap: Boolean, val text: String)

/**
 * Understands "where can I eat cheap near me", «dónde puedo comer barato», «busca una farmacia cerca» (pure, tested,
 * Spanish + English) with typo tolerance: accents and capitals are ignored and each word is matched to the vocabulary
 * with a small edit distance («donde pudo comer barato», «dnde puedo comer»).
 */
object NearbyIntent {

    private val WHERE = setOf("donde", "where", "dnde")
    private val NEAR = setOf("cerca", "cercano", "cercana", "cercanos", "proximo", "alrededor", "aqui", "near", "nearby", "nearest", "closest", "around")
    private val SEARCH = setOf("busca", "buscame", "encuentra", "find", "search", "look")
    private val CHEAP = setOf("barato", "barata", "baratos", "economico", "cheap", "inexpensive", "budget", "affordable")
    /** Words that make a question about "where" a places question even with no category noun ("where can I eat"). */
    private val FOOD_WORDS = setOf("comer", "cenar", "almorzar", "comida", "restaurante", "restaurantes", "eat", "dinner", "lunch", "food", "restaurant", "restaurants", "hamburguesa", "pizza")
    private val CATEGORY_WORDS: Map<String, NearbyCategory> = buildMap {
        FOOD_WORDS.forEach { put(it, NearbyCategory.FOOD) }
        listOf("cafe", "cafeteria", "coffee", "cafes", "cafeterias").forEach { put(it, NearbyCategory.COFFEE) }
        listOf("bar", "bares", "pub", "tapas", "copas").forEach { put(it, NearbyCategory.BAR) }
        listOf("farmacia", "farmacias", "pharmacy", "pharmacies", "chemist", "drugstore").forEach { put(it, NearbyCategory.PHARMACY) }
        listOf("supermercado", "supermercados", "super", "supermarket", "supermarkets", "groceries", "grocery").forEach { put(it, NearbyCategory.SUPERMARKET) }
        listOf("panaderia", "panaderias", "bakery", "bread", "pan").forEach { put(it, NearbyCategory.BAKERY) }
        listOf("gasolinera", "gasolineras", "petrol", "fuel", "gasolina").forEach { put(it, NearbyCategory.GAS) }
        listOf("cajero", "cajeros", "atm", "banco").forEach { put(it, NearbyCategory.ATM) }
        listOf("hospital", "urgencias", "clinica", "clinic", "doctor", "medico").forEach { put(it, NearbyCategory.HOSPITAL) }
        listOf("parking", "aparcamiento", "aparcar", "estacionamiento", "garaje").forEach { put(it, NearbyCategory.PARKING) }
    }
    /** Where-questions with these verbs are about being somewhere ("where can I eat"), not about a fact. */
    private val CAN = setOf("puedo", "podria", "hay", "can", "could", "is", "are", "se", "encuentro", "get", "buy", "comprar", "conseguir")
    private val VOCAB: Set<String> = WHERE + NEAR + SEARCH + CHEAP + CATEGORY_WORDS.keys + CAN

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun tokens(text: String): List<String> =
        normalize(text).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map(::fix)

    /** The vocabulary word [w] was most likely meant to be, or [w] itself. Short words (up to 4 letters) must be exact. */
    internal fun fix(w: String): String {
        if (w in VOCAB || w.length <= 4) return w
        val max = if (w.length >= 9) 2 else 1
        return VOCAB.filter { it.length > 4 && kotlin.math.abs(it.length - w.length) <= max && distance(w, it) <= max }
            .minByOrNull { distance(w, it) } ?: w
    }

    /** Damerau-Levenshtein (adjacent swaps count as one edit). */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Null when [text] isn't a request for places near the user. */
    fun parse(text: String): NearbyQuery? {
        val tk = tokens(text)
        if (tk.isEmpty() || tk.size > 14) return null
        val category = tk.firstNotNullOfOrNull { CATEGORY_WORDS[it] } ?: return null
        val asksWhere = tk.any { it in WHERE }
        val near = tk.any { it in NEAR } || (tk.zipWithNext().any { (a, b) -> (a == "cerca" && b == "de") })
        val search = tk.first() in SEARCH || (tk.size > 1 && tk[0] in setOf("pues", "oye", "porfa", "please", "can", "could") && tk.any { it in SEARCH })
        // "donde puedo comer": a where-question about a place type. "busca una farmacia": a place search.
        val ok = near || (asksWhere && tk.any { it in CAN || it in FOOD_WORDS }) || (search && tk.size <= 6)
        // A bare "pan" or "bar" with no where/near/search shape is not a request (handled above by `ok`)
        if (!ok) return null
        return NearbyQuery(category, cheap = tk.any { it in CHEAP }, text = text.trim())
    }

    /** Overpass QL for [q] around ([lat], [lng]) within [radius] meters. */
    fun overpass(q: NearbyQuery, lat: Double, lng: Double, radius: Int): String {
        val at = "(around:$radius,${"%.5f".format(java.util.Locale.US, lat)},${"%.5f".format(java.util.Locale.US, lng)})"
        val filters = if (q.category == NearbyCategory.FOOD && q.cheap) {
            listOf("[amenity=fast_food]", "[amenity=restaurant][cuisine~\"pizza|kebab|burger|sandwich|chinese|noodle|tapas|bocadillo|bakery|sushi\"]", "[amenity=cafe]")
        } else q.category.osm.map { f -> f.split("=").let { "[${it[0]}=${it[1]}]" } }
        return "[out:json][timeout:15];(" + filters.joinToString("") { "nwr$it$at;" } + ");out center tags 60;"
    }

    /** Overpass JSON → places sorted by distance (named ones only, deduplicated, at most [max]). */
    fun parseOverpass(body: String, lat: Double, lng: Double, max: Int = 6): List<NearbyPlace> {
        val elements = runCatching { Json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<NearbyPlace>()
        for (el in elements) {
            val e = el as? JsonObject ?: continue
            val tags = e["tags"] as? JsonObject ?: continue
            fun tag(k: String) = (tags[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = cleanName(tag("name"))
            if (name.isEmpty()) continue
            val c = e["center"] as? JsonObject
            val pLat = ((e["lat"] ?: c?.get("lat")) as? JsonPrimitive)?.doubleOrNull ?: continue
            val pLng = ((e["lon"] ?: c?.get("lon")) as? JsonPrimitive)?.doubleOrNull ?: continue
            val kind = cleanName(tag("cuisine").ifBlank { tag("amenity").ifBlank { tag("shop") } }.substringBefore(';')).ifEmpty { null }
            out += NearbyPlace(name, pLat, pLng, distanceM(lat, lng, pLat, pLng), if (tag("opening_hours").trim() == "24/7") true else null, kind)
        }
        return out.sortedBy { it.distanceM }.distinctBy { it.name.lowercase() }.take(max)
    }

    /** Google Maps link for a text search. */
    fun mapsUrl(query: String): String = "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(query, "UTF-8")

    /** Google Maps link for a found place: its name plus exact coordinates. */
    fun mapsUrl(p: NearbyPlace): String = mapsUrl("${p.name} ${p.lat},${p.lng}")

    /** Removes control characters and caps the length of an untrusted name. */
    fun cleanName(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(60)

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return (r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
    }
}
