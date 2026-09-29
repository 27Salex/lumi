package io.github.salex27.lumi.domain.model

/** An event from the phone's calendar (Google Calendar synced by the system). */
data class AgendaEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** ARGB color of the calendar/event. */
    val color: Int,
    val calendarName: String,
    /** Event address/location (empty if none). Used for "Directions". */
    val location: String = ""
)
