package io.github.salex27.lumi.data.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.salex27.lumi.domain.places.NearbyIntent
import io.github.salex27.lumi.domain.places.NearbyPlace
import io.github.salex27.lumi.domain.places.NearbyQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Places near the phone from OpenStreetMap through Overpass (free, no key). Only the rounded coordinates and the
 * place type leave the phone. Results are untrusted text.
 */
class NearbyService(private val context: Context) {

    sealed interface Result {
        data class Ok(val places: List<NearbyPlace>, val radiusM: Int) : Result
        /** No location permission yet: the UI can ask for it. */
        data object NoPermission : Result
        /** Permission but no position (location off, no fix). */
        data object NoLocation : Result
        data object Failed : Result
    }

    suspend fun search(q: NearbyQuery): Result = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext Result.NoPermission
        val here = location() ?: return@withContext Result.NoLocation
        try {
            for (radius in RADII) {
                val places = NearbyIntent.parseOverpass(post(NearbyIntent.overpass(q, here.latitude, here.longitude, radius)), here.latitude, here.longitude)
                if (places.isNotEmpty()) return@withContext Result.Ok(places, radius)
            }
            Result.Ok(emptyList(), RADII.last())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Failed
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun location(): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val last = suspendCancellableCoroutine<Location?> { cont ->
            client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
        }
        // "Near me" needs a recent position (10 min), unlike the weather
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L) return last
        return withTimeoutOrNull(8_000) {
            val cts = CancellationTokenSource()
            suspendCancellableCoroutine<Location?> { cont ->
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } ?: last
    }

    private fun post(query: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Lumi-Android/1.0 (nearby places)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        /** Widen the search when nothing is close. */
        val RADII = listOf(1500, 4000)
    }
}
