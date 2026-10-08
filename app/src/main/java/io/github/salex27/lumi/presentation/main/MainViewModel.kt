package io.github.salex27.lumi.presentation.main

import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.salex27.lumi.data.ai.AssistantOrchestrator
import io.github.salex27.lumi.data.ai.GemmaModelManager
import io.github.salex27.lumi.data.local.BriefStore
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.repository.TaskRepository
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
    /** true = priority then date (default); false = date only. */
    val sortByPriority: Boolean = true
)

data class BriefingState(
    val text: String? = null,
    val engine: String = "",
    val isLoading: Boolean = false,
    /** Changes on each new briefing to replay the typing animation (0 = loaded from cache, no animation). */
    val version: Int = 0
)

data class MainUiState(
    /** All tasks, active first and sorted by due date. */
    val allTasks: List<Task> = emptyList(),
    /** Tasks after applying the Tasks tab filters. */
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

    // combine takes at most 5 typed flows (see AGENTS.md) → filters and briefing are grouped
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
                { if (filters.sortByPriority) -it.priority.rank else 0 }, // then the most important
                { it.dueAt ?: Long.MAX_VALUE },     // whatever is due first on top
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

    /** Language the summary on screen is written in. */
    private var briefLang = ReplyLanguage.app.name

    /** The app language changed while the app was running (Settings → Language): the summary is rewritten. */
    fun refreshIfLanguageChanged() {
        if (briefLang != ReplyLanguage.app.name) generateDailyBriefing()
    }

    /** Back on screen: rewrite the summary when it is older than the TTL or today's meetings changed (calendar edited, permission granted). */
    fun refreshIfStale() {
        if (_briefing.value.isLoading) return
        viewModelScope.launch {
            val sig = runCatching { repository.calendarSignature() }.getOrDefault(0)
            val saved = briefStore.load()
            if (saved == null || !saved.isFresh(LocalDate.now(), ReplyLanguage.app.name, System.currentTimeMillis(), sig)) generateDailyBriefing()
        }
    }

    init {
        // The summary is cached: only generated if there is none, it is from another day or in another language (saves AI requests)
        val saved = briefStore.load()
        if (saved != null && saved.date == LocalDate.now() && saved.lang == ReplyLanguage.app.name) {
            _briefing.value = BriefingState(text = saved.text, engine = saved.engine, version = 0)
        }
        // Show the cache at once, then check its age and today's meetings (generates when stale or missing)
        refreshIfStale()
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

    /** Swipe left: move to tomorrow (keeps the time if it had one). */
    fun moveToTomorrow(task: Task) {
        val zone = java.time.ZoneId.systemDefault()
        val time = task.dueAt?.takeIf { task.dueHasTime }?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        val due = LocalDate.now().plusDays(1).atTime(time ?: java.time.LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
        viewModelScope.launch { repository.updateTask(task.copy(dueAt = due, dueHasTime = time != null)) }
    }

    /**
     * Saves from the edit screen: creates if new (id 0) or updates, and applies the custom reminder changes (add
     * minutes-before / remove ids).
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

    /** Tapping "refresh": forces a new summary. */
    fun generateDailyBriefing() {
        if (_briefing.value.isLoading) return
        _briefing.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            ReplyLanguage.current = ReplyLanguage.app // the Home summary is in the app's language
            briefLang = ReplyLanguage.app.name
            val sig = runCatching { repository.calendarSignature() }.getOrDefault(0)
            val result = runCatching { repository.generateDailyBriefing() }.getOrNull()
            if (result != null) briefStore.save(result.reply, result.engine, ReplyLanguage.app.name, calendarSig = sig)
            _briefing.update {
                BriefingState(
                    text = result?.reply ?: it.text ?: ReplyLanguage.ui("No he podido preparar el resumen ahora mismo.", "I couldn't prepare the summary right now."),
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
