package io.github.salex27.lumi.data.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.data.ai.TaskPhraseParser
import io.github.salex27.lumi.data.places.PlacesStore
import io.github.salex27.lumi.domain.weather.DayForecast
import io.github.salex27.lumi.domain.weather.HourForecast
import io.github.salex27.lumi.domain.weather.WeatherReport
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Weather from Open-Meteo: free, no API key or account (non-commercial use). The phone's approximate location →
 * without permission, the saved "home" place → the last known one. Kept for 30 min to avoid repeating requests.
 */
class WeatherService(private val context: Context, private val places: PlacesStore) {

    sealed interface Result {
        data class Ok(val report: WeatherReport) : Result
        data class Failed(val message: String) : Result
    }

    /** Original JSON of each forecast (to store it as is). Declared before _latest: loadCached() already uses it. */
    private val rawCache = java.util.WeakHashMap<WeatherReport, String>()
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val _latest = MutableStateFlow(loadCached())
    /** Latest forecast for where you are (for Home and warnings, without hitting the network). */
    val latest: StateFlow<WeatherReport?> = _latest.asStateFlow()

    /** A recent forecast (< 3 h) if there is one, without network. */
    fun cachedFresh(): WeatherReport? = _latest.value?.takeIf { System.currentTimeMillis() - it.fetchedAt < 3 * HOUR }

    /** [placeQuery] = a city ("Madrid") or a saved place ("casa"); null = where you are. */
    suspend fun forecast(placeQuery: String? = null, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        try {
            if (placeQuery == null) {
                _latest.value?.takeIf { !force && System.currentTimeMillis() - it.fetchedAt < CACHE_MS }?.let { return@withContext Result.Ok(it) }
                val (lat, lng, label) = here() ?: return@withContext Result.Failed(
                    io.github.salex27.lumi.domain.assistant.ReplyLanguage.t("No sé dónde estás: dale a Lumi permiso de ubicación o guarda «Casa» en Ajustes → Lugares.", "I don't know where you are: give Lumi location permission or save «Home» in Settings → Places.")
                )
                val report = fetch(lat, lng, label)
                _latest.value = report
                prefs.edit().putString(K_LAST, serialize(report)).putString(K_LAT, "$lat").putString(K_LNG, "$lng").apply()
                Result.Ok(report)
            } else {
                val key = TaskPhraseParser.normalizePlace(placeQuery)
                places.get(key)?.let { return@withContext Result.Ok(fetch(it.lat, it.lng, it.label)) }
                val (lat, lng, name) = geocode(placeQuery) ?: return@withContext Result.Failed(io.github.salex27.lumi.domain.assistant.ReplyLanguage.t("No encuentro «$placeQuery».", "I can't find «$placeQuery»."))
                Result.Ok(fetch(lat, lng, name))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Failed(io.github.salex27.lumi.domain.assistant.ReplyLanguage.t("No he podido consultar el tiempo (¿sin conexión?).", "I couldn't get the weather (no connection?)."))
        }
    }

    // ── Location ────────────────────────────────────────────────────────────

    private suspend fun here(): Triple<Double, Double, String>? {
        location()?.let { loc -> return Triple(loc.latitude, loc.longitude, cityName(loc.latitude, loc.longitude) ?: "tu zona") }
        places.get("casa")?.let { return Triple(it.lat, it.lng, io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("casa", "home")) }
        val lat = prefs.getString(K_LAT, null)?.toDoubleOrNull()
        val lng = prefs.getString(K_LNG, null)?.toDoubleOrNull()
        return if (lat != null && lng != null) Triple(lat, lng, _latest.value?.place ?: "tu zona") else null
    }

    @SuppressLint("MissingPermission")
    private suspend fun location(): Location? {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val last = suspendCancellableCoroutine<Location?> { cont ->
            client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
        }
        // For the weather a position from a few hours ago is enough
        if (last != null && System.currentTimeMillis() - last.time < 6 * HOUR) return last
        return withTimeoutOrNull(6_000) {
            val cts = CancellationTokenSource()
            suspendCancellableCoroutine<Location?> { cont ->
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } ?: last
    }

    @Suppress("DEPRECATION")
    private fun cityName(lat: Double, lng: Double): String? = runCatching {
        Geocoder(context, Locale.forLanguageTag("es-ES")).getFromLocation(lat, lng, 1)?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea }
    }.getOrNull()

    /** City → coordinates with Open-Meteo's geocoder (also free). */
    private fun geocode(name: String): Triple<Double, Double, String>? {
        val json = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=${Uri.encode(name.trim())}")
        val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        return Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", name))
    }

    // ── Open-Meteo ──────────────────────────────────────────────────────────

    private fun fetch(lat: Double, lng: Double, label: String): WeatherReport {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${"%.3f".format(Locale.US, lat)}&longitude=${"%.3f".format(Locale.US, lng)}" +
            "&current=temperature_2m,weather_code" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        return parse(get(url), label, System.currentTimeMillis())
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 10_000 }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun serialize(r: WeatherReport): String = JSONObject()
        .put("place", r.place).put("at", r.fetchedAt).put("raw", rawCache[r] ?: "").toString()

    private fun loadCached(): WeatherReport? = runCatching {
        val o = JSONObject(prefs.getString(K_LAST, null) ?: return null)
        parse(o.getString("raw"), o.getString("place"), o.getLong("at"))
    }.getOrNull()

    private fun parse(json: String, label: String, at: Long): WeatherReport {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            HourForecast(
                LocalDateTime.parse(ht.getString(i)), h.getJSONArray("temperature_2m").optDouble(i),
                h.getJSONArray("precipitation_probability").optInt(i, 0), h.getJSONArray("weather_code").optInt(i, 0)
            )
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            DayForecast(
                LocalDate.parse(dt.getString(i)), d.getJSONArray("weather_code").optInt(i, 0),
                d.getJSONArray("temperature_2m_max").optDouble(i), d.getJSONArray("temperature_2m_min").optDouble(i),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0)
            )
        }
        return WeatherReport(label, at, cur.optDouble("temperature_2m"), cur.optInt("weather_code"), hours, days)
            .also { rawCache[it] = json }
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val CACHE_MS = 30 * 60_000L
        const val K_LAST = "last"
        const val K_LAT = "lat"
        const val K_LNG = "lng"
    }
}
