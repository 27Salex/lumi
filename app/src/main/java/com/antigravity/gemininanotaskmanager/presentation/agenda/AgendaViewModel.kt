package com.antigravity.gemininanotaskmanager.presentation.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antigravity.gemininanotaskmanager.data.sync.DeviceCalendar
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AgendaUiState(
    val date: LocalDate = LocalDate.now(),
    /** Tareas con hora de ese día (bloques en la línea de tiempo). */
    val timedTasks: List<Task> = emptyList(),
    /** Tareas de ese día sin hora (fila superior). */
    val dayTasks: List<Task> = emptyList(),
    val events: List<AgendaEvent> = emptyList(),
    /** Días de la semana visible con algo programado (puntito en la tira de días). */
    val busyDays: Set<LocalDate> = emptySet(),
    val calendarAllowed: Boolean = false,
    /** Eventos de HOY (para la pantalla de Inicio). */
    val todayEvents: List<AgendaEvent> = emptyList()
)

class AgendaViewModel(
    repository: TaskRepository,
    private val calendar: DeviceCalendar,
    /** Ids de eventos creados por Lumi para sus tareas (no se muestran dos veces). */
    private val linkedEventIds: suspend () -> Set<Long>
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val _date = MutableStateFlow(LocalDate.now())
    private val _events = MutableStateFlow<List<AgendaEvent>>(emptyList())
    private val _todayEvents = MutableStateFlow<List<AgendaEvent>>(emptyList())
    private val _allowed = MutableStateFlow(calendar.canRead())

    val state: StateFlow<AgendaUiState> = combine(
        repository.getAllTasks(), _date, _events, _todayEvents, _allowed
    ) { tasks, date, events, today, allowed ->
        fun Task.dueDate() = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val ofDay = tasks.filter { it.dueDate() == date && it.status.name != "CANCELLED" }
        val weekStart = date.minusDays(date.dayOfWeek.value - 1L)
        AgendaUiState(
            date = date,
            timedTasks = ofDay.filter { it.dueHasTime }.sortedBy { it.dueAt },
            dayTasks = ofDay.filter { !it.dueHasTime },
            events = events,
            busyDays = tasks.mapNotNull { it.dueDate() }.filter { !it.isBefore(weekStart) && it.isBefore(weekStart.plusDays(7)) }.toSet(),
            calendarAllowed = allowed,
            todayEvents = today
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgendaUiState())

    init { refresh() }

    fun selectDate(date: LocalDate) {
        _date.value = date
        loadEvents()
    }

    fun shiftDays(days: Long) = selectDate(_date.value.plusDays(days))

    /** Recargar al volver a la app o tras conceder el permiso de calendario. */
    fun refresh() {
        _allowed.value = calendar.canRead()
        loadEvents()
        viewModelScope.launch { _todayEvents.value = eventsFor(LocalDate.now()) }
    }

    private fun loadEvents() {
        val date = _date.value
        viewModelScope.launch { _events.update { eventsFor(date) } }
    }

    private suspend fun eventsFor(date: LocalDate): List<AgendaEvent> {
        if (!calendar.canRead()) return emptyList()
        val linked = linkedEventIds()
        return calendar.eventsOn(date, zone).filter { it.id !in linked }
    }

    class Factory(
        private val repository: TaskRepository,
        private val calendar: DeviceCalendar,
        private val linkedEventIds: suspend () -> Set<Long>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgendaViewModel(repository, calendar, linkedEventIds) as T
    }
}
