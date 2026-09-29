package com.antigravity.gemininanotaskmanager.service.place

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
import com.antigravity.gemininanotaskmanager.data.local.TaskDao
import com.antigravity.gemininanotaskmanager.data.local.toDomain
import com.antigravity.gemininanotaskmanager.data.places.PlacesStore
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.repository.TaskChangeListener
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Avisos por lugar («cuando llegue a casa…») con geovallas de Google Play Services: una por tarea activa
 * cuyo lugar esté guardado. Cada aviso salta UNA vez (como Recordatorios de Apple); si se cambia el lugar
 * o la tarea se repite, vuelve a armarse. Android borra las geovallas al reiniciar → [resyncAll] en el arranque.
 */
class PlaceReminderManager(
    private val context: Context,
    private val dao: TaskDao,
    private val places: PlacesStore
) : TaskChangeListener {

    private val client by lazy { LocationServices.getGeofencingClient(context) }
    /** Avisos ya disparados: "id|lugar|ARRIVE". Al cambiar el lugar cambia la clave y se rearma. */
    private val fired = context.getSharedPreferences("place_reminders", Context.MODE_PRIVATE)

    private val _error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Último fallo al registrar geovallas, explicado (se muestra en Ajustes → Lugares). */
    val error: kotlinx.coroutines.flow.StateFlow<String?> = _error

    fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Sin «Permitir todo el tiempo» las geovallas no saltan con la app cerrada. */
    fun hasBackgroundLocation(): Boolean = hasLocation() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    override suspend fun onTaskSaved(taskId: Long) {
        val task = dao.getTaskById(taskId)?.toDomain() ?: return remove(taskId)
        arm(task)
    }

    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) = remove(taskId)

    /** Vuelve a registrar todas (arranque del móvil, lugar nuevo, permiso recién concedido). */
    suspend fun resyncAll() {
        if (!hasLocation()) return
        dao.getActiveTasksSnapshot().forEach { arm(it.toDomain()) }
    }

    @SuppressLint("MissingPermission") // comprobado en hasLocation()
    private fun arm(task: Task) {
        val trigger = task.placeTrigger
        // Coordenadas: las de la dirección suelta de la tarea o las del lugar guardado
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
        // Sin disparo inicial: si ya estás en casa al crear «cuando llegue a casa», no salta hasta la próxima llegada
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
        client.addGeofences(request, pendingIntent())
            .addOnSuccessListener { _error.value = null }
            .addOnFailureListener {
                Log.w(TAG, "No se pudo registrar la geovalla de ${task.id}: ${it.message}")
                _error.value = explain((it as? com.google.android.gms.common.api.ApiException)?.statusCode)
            }
    }

    private fun explain(code: Int?): String = when (code) {
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
            "Los avisos por lugar necesitan «Precisión de la ubicación de Google» activada (Ajustes del móvil → Ubicación)."
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
            "Falta el permiso de ubicación «Todo el tiempo»."
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES ->
            "Demasiados avisos por lugar activos (máximo 100)."
        else -> "No se pudo activar el aviso por lugar (código ${code ?: "?"})."
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

    /** Ubicación actual precisa (para «Guardar aquí»). null si no hay permiso o no se pudo obtener. */
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

    // Geofencing exige un PendingIntent MUTABLE (el sistema rellena los datos del evento)
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
