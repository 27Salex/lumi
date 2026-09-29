package com.antigravity.gemininanotaskmanager.domain.model

/** Evento del calendario del móvil (Google Calendar sincronizado por el sistema). */
data class AgendaEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** Color ARGB del calendario/evento. */
    val color: Int,
    val calendarName: String,
    /** Dirección / lugar del evento (vacío si no tiene). Para «Cómo llegar». */
    val location: String = ""
)
