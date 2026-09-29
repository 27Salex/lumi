package com.antigravity.gemininanotaskmanager.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antigravity.gemininanotaskmanager.data.ai.AssistantOrchestrator
import com.antigravity.gemininanotaskmanager.data.ai.GemmaModelManager
import com.antigravity.gemininanotaskmanager.data.local.BriefStore
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import com.antigravity.gemininanotaskmanager.domain.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class Filters(
    val status: TaskStatus? = null,
    val category: TaskCategory? = null,
    val priority: TaskPriority? = null,
    /** true = prioridad y luego fecha (por defecto); false = solo fecha. */
    val sortByPriority: Boolean = true
)

data class BriefingState(
    val text: String? = null,
    val engine: String = "",
    val isLoading: Boolean = false,
    /** Cambia en cada briefing nuevo para relanzar la animación de escritura (0 = cargado de caché, sin animar). */
    val version: Int = 0
)

data class MainUiState(
    /** Todas las tareas, activas primero y ordenadas por vencimiento. */
    val allTasks: List<Task> = emptyList(),
    /** Tareas tras aplicar los filtros de la pestaña Tareas. */
    val tasks: List<Task> = emptyList(),
    val filters: Filters = Filters(),
    val briefing: BriefingState = BriefingState(),
    val activeEngine: String = "",
    val gemmaState: GemmaModelManager.State = GemmaModelManager.State.NotDownloaded
)

class MainViewModel(
    private val repository: TaskRepository,
    orchestrator: AssistantOrchestrator,
    private val gemmaModel: GemmaModelManager,
    private val briefStore: BriefStore
) : ViewModel() {

    private val _filters = MutableStateFlow(Filters())
    private val _briefing = MutableStateFlow(BriefingState())

    // combine admite como máximo 5 flujos tipados (ver AGENTS.md) → filtros y briefing van agrupados
    val uiState: StateFlow<MainUiState> = combine(
        repository.getAllTasks(),
        _filters,
        _briefing,
        orchestrator.activeEngine,
        gemmaModel.state
    ) { allTasks, filters, briefing, engine, gemma ->
        val sorted = allTasks.sortedWith(
            compareBy<Task>(
                { if (it.isActive) 0 else 1 },      // activas primero
                { if (filters.sortByPriority) -it.priority.rank else 0 }, // luego las más importantes
                { it.dueAt ?: Long.MAX_VALUE },     // lo que vence antes arriba
                { -it.createdAt }
            )
        )
        MainUiState(
            allTasks = sorted,
            tasks = sorted
                .filter { t -> filters.status == null || t.status == filters.status }
                .filter { t -> filters.category == null || t.category == filters.category }
                .filter { t -> filters.priority == null || t.priority == filters.priority },
            filters = filters,
            briefing = briefing,
            activeEngine = engine,
            gemmaState = gemma
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainUiState())

    init {
        // El resumen se guarda: solo se genera si no hay ninguno o es de otro día (ahorra peticiones a la IA)
        val saved = briefStore.load()
        if (saved != null && saved.date == LocalDate.now()) {
            _briefing.value = BriefingState(text = saved.text, engine = saved.engine, version = 0)
        } else {
            generateDailyBriefing()
        }
    }

    fun setStatusFilter(status: TaskStatus?) = _filters.update { it.copy(status = status) }
    fun setCategoryFilter(cat: TaskCategory?) = _filters.update { it.copy(category = cat) }
    fun setPriorityFilter(priority: TaskPriority?) = _filters.update { it.copy(priority = priority) }
    fun setSortByPriority(enabled: Boolean) = _filters.update { it.copy(sortByPriority = enabled) }

    fun updateTaskStatus(task: Task, newStatus: TaskStatus) {
        viewModelScope.launch { repository.updateTask(task.copy(status = newStatus)) }
    }

    fun updateTaskCategory(task: Task, newCategory: TaskCategory) {
        viewModelScope.launch { repository.updateTask(task.copy(category = newCategory)) }
    }

    fun toggleDone(task: Task) =
        updateTaskStatus(task, if (task.status == TaskStatus.COMPLETED) TaskStatus.TODO else TaskStatus.COMPLETED)

    /** Deslizar a la izquierda: pasar a mañana (conserva la hora si la tenía). */
    fun moveToTomorrow(task: Task) {
        val zone = java.time.ZoneId.systemDefault()
        val time = task.dueAt?.takeIf { task.dueHasTime }?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        val due = LocalDate.now().plusDays(1).atTime(time ?: java.time.LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
        viewModelScope.launch { repository.updateTask(task.copy(dueAt = due, dueHasTime = time != null)) }
    }

    /**
     * Guarda desde la pantalla de edición: crea si es nueva (id 0) o actualiza, y aplica los cambios de avisos
     * personalizados (añadir minutos-antes / quitar ids).
     */
    fun saveTask(task: Task, addOffsets: List<Int> = emptyList(), removeReminderIds: List<Long> = emptyList()) {
        viewModelScope.launch {
            val id = if (task.id == 0L) repository.insertTask(task) else { repository.updateTask(task); task.id }
            val saved = task.copy(id = id)
            removeReminderIds.forEach { repository.removeReminder(it, saved) }
            addOffsets.forEach { repository.addCustomReminder(saved, it) }
        }
    }

    suspend fun remindersFor(taskId: Long) = if (taskId == 0L) emptyList() else repository.remindersFor(taskId)
    suspend fun upcomingMeetings() = repository.upcomingMeetings(14)

    fun deleteTask(task: Task) {
        viewModelScope.launch { repository.deleteTask(task) }
    }

    /** Pulsar «actualizar»: fuerza un resumen nuevo. */
    fun generateDailyBriefing() {
        if (_briefing.value.isLoading) return
        _briefing.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val result = runCatching { repository.generateDailyBriefing() }.getOrNull()
            if (result != null) briefStore.save(result.reply, result.engine)
            _briefing.update {
                BriefingState(
                    text = result?.reply ?: it.text ?: "No he podido preparar el resumen ahora mismo.",
                    engine = result?.engine ?: it.engine,
                    isLoading = false,
                    version = it.version + 1
                )
            }
        }
    }

    fun startGemmaDownload() = gemmaModel.startDownload()

    class Factory(
        private val repository: TaskRepository,
        private val orchestrator: AssistantOrchestrator,
        private val gemmaModel: GemmaModelManager,
        private val briefStore: BriefStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository, orchestrator, gemmaModel, briefStore) as T
    }
}
