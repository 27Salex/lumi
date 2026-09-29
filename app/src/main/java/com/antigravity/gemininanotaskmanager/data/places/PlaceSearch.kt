package com.antigravity.gemininanotaskmanager.data.places

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Búsqueda de direcciones y sitios con el Geocoder del sistema (gratis, sin API key). Se sesga hacia la zona de tus
 * lugares guardados para que «Mercadona» encuentre el de tu barrio y no uno de otra ciudad.
 */
class PlaceSearch(private val context: Context, private val places: PlacesStore) {

    data class Result(val name: String, val address: String, val lat: Double, val lng: Double)

    val available: Boolean get() = Geocoder.isPresent()

    /**
     * v3.7: primero Photon (OpenStreetMap, gratis y sin clave) cerca de ti: encuentra SITIOS por nombre
     * («Mercadona», «gimnasio», «Hospital La Paz»), no solo calles. Después el Geocoder del sistema para direcciones.
     */
    suspend fun search(query: String, max: Int = 5): List<Result> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val near = anchor()
        val pois = runCatching { photon(q, max, near) }.getOrDefault(emptyList())
        val addresses = if (available) geocoder(q, max) else emptyList()
        return (pois + addresses).distinctBy { "%.3f,%.3f".format(java.util.Locale.US, it.lat, it.lng) }.take(max)
    }

    /**
     * El sitio más cercano con ese nombre, a menos de 60 km de ti. null si Lumi no sabe dónde estás (sin ubicación ni
     * lugares guardados): mejor preguntar que elegir una farmacia de otro país.
     */
    suspend fun nearest(query: String): Result? {
        val near = anchor() ?: return null
        return runCatching { photon(query.trim(), 3, near) }.getOrDefault(emptyList()).firstOrNull()
    }

    /** Dónde buscar cerca: tu última ubicación conocida → tu primer lugar guardado. */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun anchor(): Pair<Double, Double>? {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            val loc = suspendCancellableCoroutine<android.location.Location?> { cont ->
                com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
            }
            if (loc != null) return loc.latitude to loc.longitude
        }
        return places.places.value.firstOrNull()?.let { it.lat to it.lng }
    }

    private suspend fun photon(q: String, max: Int, near: Pair<Double, Double>?): List<Result> = withContext(Dispatchers.IO) {
        val bias = near?.let { "&lat=${"%.4f".format(java.util.Locale.US, it.first)}&lon=${"%.4f".format(java.util.Locale.US, it.second)}" }.orEmpty()
        val url = java.net.URL("https://photon.komoot.io/api/?q=${android.net.Uri.encode(q)}&limit=${max + 3}$bias")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "Lumi-Android/3.7 (asistente personal)")
        }
        val json = try {
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val features = org.json.JSONObject(json).optJSONArray("features") ?: return@withContext emptyList()
        (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = listOfNotNull(p.optString("street").ifBlank { null }, p.optString("housenumber").ifBlank { null }).joinToString(" ")
            val line = listOf(street, p.optString("city"), p.optString("state")).filter { it.isNotBlank() }.distinct().joinToString(", ")
            val name = p.optString("name").ifBlank { street }.ifBlank { return@mapNotNull null }
            Result(name, line.ifBlank { name }, c.getDouble(1), c.getDouble(0))
        }.let { list ->
            // Lo más cercano primero (Photon ya sesga, pero mezcla ciudades)
            if (near == null) list else list.sortedBy { distanceKm(near.first, near.second, it.lat, it.lng) }.filter { distanceKm(near.first, near.second, it.lat, it.lng) < 60 }
        }.take(max)
    }

    private fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0] / 1000.0
    }

    private suspend fun geocoder(q: String, max: Int): List<Result> {
        val geocoder = Geocoder(context, Locale.forLanguageTag("es-ES"))
        // Caja de ~50 km alrededor de tus lugares (si hay); si no, búsqueda sin sesgo
        val anchor = places.places.value.firstOrNull()
        val addresses = runCatching {
            if (anchor != null) find(geocoder, q, max, anchor.lat - BOX, anchor.lng - BOX, anchor.lat + BOX, anchor.lng + BOX)
                .ifEmpty { find(geocoder, q, max) }
            else find(geocoder, q, max)
        }.getOrDefault(emptyList())
        return addresses.map { a ->
            val line = a.getAddressLine(0).orEmpty()
            val name = a.featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: line.substringBefore(',')
            Result(name.ifBlank { q }, line, a.latitude, a.longitude)
        }.distinctBy { "%.4f,%.4f".format(it.lat, it.lng) }
    }

    private suspend fun find(g: Geocoder, q: String, max: Int, vararg box: Double): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                    override fun onError(errorMessage: String?) = cont.resume(emptyList())
                }
                if (box.size == 4) g.getFromLocationName(q, max, box[0], box[1], box[2], box[3], listener)
                else g.getFromLocationName(q, max, listener)
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            (if (box.size == 4) g.getFromLocationName(q, max, box[0], box[1], box[2], box[3]) else g.getFromLocationName(q, max)).orEmpty()
        }

    private companion object {
        const val BOX = 0.45 // grados ≈ 50 km
    }
}
