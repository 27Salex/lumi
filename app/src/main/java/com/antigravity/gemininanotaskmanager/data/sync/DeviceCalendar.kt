package com.antigravity.gemininanotaskmanager.data.sync

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Acceso al calendario del sistema (CalendarContract). En un móvil con cuenta de Google, los
 * calendarios de Google Calendar ya están aquí y el sistema los sincroniza solo → integración
 * gratuita y sin configurar nada en Google Cloud. Requiere READ/WRITE_CALENDAR.
 */
class DeviceCalendar(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

    fun canRead() = granted(Manifest.permission.READ_CALENDAR)
    fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Calendarios visibles en los que podemos escribir (p.ej. el principal de tu cuenta de Google). */
    suspend fun writableCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3)))
                }
            }
        }.getOrNull().orEmpty()
    }

    /** Eventos (instancias, incluye recurrentes) de un día, de todos los calendarios visibles. */
    suspend fun eventsOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AgendaEvent> =
        eventsBetween(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    /** Eventos (instancias) entre dos instantes, p.ej. las reuniones de los próximos 14 días para vincular tareas. */
    suspend fun eventsBetween(start: Long, end: Long): List<AgendaEvent> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION
        )
        runCatching {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, CalendarContract.Instances.BEGIN)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        AgendaEvent(
                            id = c.getLong(0),
                            title = c.getString(1).orEmpty().ifBlank { "(Sin título)" },
                            begin = c.getLong(2),
                            end = c.getLong(3),
                            allDay = c.getInt(4) == 1,
                            color = c.getInt(5),
                            calendarName = c.getString(6).orEmpty(),
                            location = c.getString(7).orEmpty().trim()
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "No se pudieron leer eventos: ${it.message}") }.getOrNull().orEmpty()
    }

    /** Crea o actualiza el evento de una tarea con hora. Devuelve el id del evento o null si falla. */
    suspend fun upsertTaskEvent(task: Task, calendarId: Long, existingEventId: Long?): Long? = withContext(Dispatchers.IO) {
        val dueAt = task.dueAt ?: return@withContext null
        if (!canWrite()) return@withContext null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "${task.category.emoji} ${task.title}")
            put(CalendarContract.Events.DESCRIPTION, listOf(task.description, "Creado con Lumi").filter { it.isNotBlank() }.joinToString("\n\n"))
            put(CalendarContract.Events.DTSTART, dueAt)
            put(CalendarContract.Events.DTEND, dueAt + EVENT_DURATION_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 0) // los avisos los pone Lumi (evita notificaciones dobles)
        }
        runCatching {
            if (existingEventId != null) {
                val rows = context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId), values, null, null
                )
                if (rows > 0) return@runCatching existingEventId
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        }.onFailure { Log.w(TAG, "No se pudo guardar el evento: ${it.message}") }.getOrNull()
    }

    suspend fun deleteEvent(eventId: Long) = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext
        runCatching { context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) }
    }

    companion object {
        private const val TAG = "DeviceCalendar"
        const val EVENT_DURATION_MS = 30 * 60_000L
    }
}
