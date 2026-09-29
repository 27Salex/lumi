package io.github.salex27.lumi.service.place

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.data.local.TaskDao
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.places.PlacesStore
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.repository.TaskChangeListener
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Place reminders ("when I get home…") with Google Play Services geofences: one per active task whose place is saved.
 * Each reminder fires ONCE (like Apple Reminders); it re-arms if the place changes or the task repeats.
 * Android clears geofences on reboot → [resyncAll] at startup.
 */
class PlaceReminderManager(
    private val context: Context,
    private val dao: TaskDao,
    private val places: PlacesStore
) : TaskChangeListener {

    private val client by lazy { LocationServices.getGeofencingClient(context) }
    /** Reminders already fired: "id|place|ARRIVE". Changing the place changes the key and re-arms it. */
    private val fired = context.getSharedPreferences("place_reminders", Context.MODE_PRIVATE)

    private val _error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Last failure registering geofences, explained (shown in Settings → Places). */
    val error: kotlinx.coroutines.flow.StateFlow<String?> = _error

    fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Without "Allow all the time" geofences don't fire with the app closed. */
    fun hasBackgroundLocation(): Boolean = hasLocation() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    override suspend fun onTaskSaved(taskId: Long) {
        val task = dao.getTaskById(taskId)?.toDomain() ?: return remove(taskId)
        arm(task)
    }

    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) = remove(taskId)

    /** Registers them all again (phone boot, new place, permission just granted). */
    suspend fun resyncAll() {
        if (!hasLocation()) return
        dao.getActiveTasksSnapshot().forEach { arm(it.toDomain()) }
    }

    @SuppressLint("MissingPermission") // comprobado en hasLocation()
    private fun arm(task: Task) {
        val trigger = task.placeTrigger
        // Coordinates: the task's one-off address or the saved place
        val coords = when {
            trigger == null -> null
            trigger.isAdHoc -> Triple(trigger.lat!!, trigger.lng!!, PlacesStore.DEFAULT_RADIUS)
            else -> places.get(trigger.place)?.let { Triple(it.lat, it.lng, it.radiusMeters) }
        }
        if (!task.isActive || trigger == null || !trigger.notify || coords == null || !hasLocation() || isFired(task)) {
            remove(task.id)
            return
        }
        val geofence = Geofence.Builder()
            .setRequestId(requestId(task.id))
            .setCircularRegion(coords.first, coords.second, coords.third)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(if (trigger.onArrive) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        // No initial trigger: if you are already home when creating "when I get home", it waits for the next arrival
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
        client.addGeofences(request, pendingIntent())
            .addOnSuccessListener { _error.value = null }
            .addOnFailureListener {
                Log.w(TAG, "Could not register the geofence of ${task.id}: ${it.message}")
                _error.value = explain((it as? com.google.android.gms.common.api.ApiException)?.statusCode)
            }
    }
    private fun explain(code: Int?): String = when (code) {
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> context.getString(io.github.salex27.lumi.R.string.geofence_not_available)
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION -> context.getString(io.github.salex27.lumi.R.string.geofence_no_background)
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> context.getString(io.github.salex27.lumi.R.string.geofence_too_many)
        else -> context.getString(io.github.salex27.lumi.R.string.geofence_failed, code?.toString() ?: "?")
    }

    private fun remove(taskId: Long) {
        client.removeGeofences(listOf(requestId(taskId)))
    }

    fun markFired(task: Task) {
        fired.edit().putBoolean(firedKey(task), true).apply()
        remove(task.id)
    }

    private fun isFired(task: Task) = fired.getBoolean(firedKey(task), false)
    private fun firedKey(task: Task) = "${task.id}|${task.placeTrigger?.serialize()}"

    /** Precise current location (for "Save here"). Null without permission or if it couldn't be obtained. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!hasLocation()) return null
        val cts = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
            cont.invokeOnCancellation { cts.cancel() }
        }
    }

    // Geofencing needs a MUTABLE PendingIntent (the system fills in the event data)
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PlaceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    companion object {
        private const val TAG = "PlaceReminders"
        private const val PREFIX = "task-"
        fun requestId(taskId: Long) = "$PREFIX$taskId"
        fun taskIdOf(requestId: String) = requestId.removePrefix(PREFIX).toLongOrNull()
    }
}
