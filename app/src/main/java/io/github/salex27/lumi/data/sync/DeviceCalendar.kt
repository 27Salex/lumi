package io.github.salex27.lumi.data.sync

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Access to the system calendar (CalendarContract). On a phone with a Google account, Google Calendar calendars are
 * already here and the system syncs them → a free integration with nothing to set up in Google Cloud.
 * Needs READ/WRITE_CALENDAR.
 */
class DeviceCalendar(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

    fun canRead() = granted(Manifest.permission.READ_CALENDAR)
    fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Visible calendars we can write to (e.g. the main one of your Google account). */
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

    /** Events (instances, recurring ones included) of a day, from every visible calendar. */
    suspend fun eventsOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AgendaEvent> =
        io.github.salex27.lumi.domain.assistant.CalendarEvents.forDay(
            eventsBetween(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()), date, zone
        )

    /** Events (instances) between two instants, e.g. meetings in the next 14 days to link tasks to. */
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
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS
        )
        runCatching {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, CalendarContract.Instances.BEGIN)?.use { c ->
                buildList {
                    while (c.moveToNext()) if (!c.isNull(8) && c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED ||
                        !c.isNull(9) && c.getInt(9) == CalendarContract.Events.STATUS_CANCELED) continue else add(
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
        }.onFailure { Log.w(TAG, "Could not read events: ${it.message}") }.getOrNull().orEmpty()
    }

    /** Creates or updates a timed task's event. Returns the event id, or null on failure. */
    suspend fun upsertTaskEvent(task: Task, calendarId: Long, existingEventId: Long?): Long? = withContext(Dispatchers.IO) {
        val dueAt = task.dueAt ?: return@withContext null
        if (!canWrite()) return@withContext null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "${task.category.emoji} ${task.title}")
            put(CalendarContract.Events.DESCRIPTION, listOf(task.description, io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Creado con Lumi", "Created with Lumi")).filter { it.isNotBlank() }.joinToString("\n\n"))
            put(CalendarContract.Events.DTSTART, dueAt)
            put(CalendarContract.Events.DTEND, dueAt + EVENT_DURATION_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 0) // Lumi sets the reminders (avoids double notifications)
        }
        runCatching {
            if (existingEventId != null) {
                val rows = context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId), values, null, null
                )
                if (rows > 0) return@runCatching existingEventId
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        }.onFailure { Log.w(TAG, "Could not save the event: ${it.message}") }.getOrNull()
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
