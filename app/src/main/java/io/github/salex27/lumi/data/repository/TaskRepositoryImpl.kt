package io.github.salex27.lumi.data.repository

import io.github.salex27.lumi.data.ai.AssistantIntents
import io.github.salex27.lumi.data.ai.AssistantOrchestrator
import io.github.salex27.lumi.data.ai.AssistantPrompts
import io.github.salex27.lumi.data.ai.CategoryHeuristics
import io.github.salex27.lumi.data.ai.DayPlanner
import io.github.salex27.lumi.data.ai.EnglishCommands
import io.github.salex27.lumi.data.ai.EnglishDateParser
import io.github.salex27.lumi.data.ai.MeetingMatcher
import io.github.salex27.lumi.data.ai.QuickMath
import io.github.salex27.lumi.data.ai.SpanishDateParser
import io.github.salex27.lumi.data.ai.TaskPhraseParser
import io.github.salex27.lumi.data.local.TaskDao
import io.github.salex27.lumi.data.local.mergeFrom
import io.github.salex27.lumi.data.local.toDomain
import io.github.salex27.lumi.data.local.toEntity
import io.github.salex27.lumi.data.settings.SettingsRepository
import io.github.salex27.lumi.data.sync.DeviceCalendar
import io.github.salex27.lumi.data.weather.WeatherService
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.assistant.AlarmPlanner
import io.github.salex27.lumi.domain.assistant.CommandSplitter
import io.github.salex27.lumi.domain.assistant.ConversationContext
import io.github.salex27.lumi.domain.assistant.DayBriefComposer
import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.domain.assistant.DeviceCommandParser
import io.github.salex27.lumi.domain.assistant.FreeTimeFinder
import io.github.salex27.lumi.domain.assistant.IncomingMessage
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.IntentRoute
import io.github.salex27.lumi.domain.assistant.LanguageDetector
import io.github.salex27.lumi.data.ai.IntentRouter
import io.github.salex27.lumi.domain.search.WebAnswers
import io.github.salex27.lumi.domain.assistant.MemoryRetriever
import io.github.salex27.lumi.domain.assistant.MessageDigest
import io.github.salex27.lumi.domain.assistant.RenameSplitter
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.assistant.Routine
import io.github.salex27.lumi.domain.assistant.RoutineMatcher
import io.github.salex27.lumi.domain.assistant.TaskMatcher
import io.github.salex27.lumi.domain.model.AIProcessingResult
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.LinkedMeeting
import io.github.salex27.lumi.domain.model.NavDestination
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskReminder
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.reminder.ReminderPlanner
import io.github.salex27.lumi.domain.reminder.ReminderScheduler
import io.github.salex27.lumi.domain.repository.MemoryStore
import io.github.salex27.lumi.domain.repository.TaskChangeListener
import io.github.salex27.lumi.domain.repository.TaskRepository
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.domain.weather.WeatherAdvisor
import io.github.salex27.lumi.domain.weather.WeatherQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The only writer of tasks and the place where a sentence becomes an action.
 * Writing: stamps completedAt, schedules reminders, spawns the next occurrence of recurring tasks and notifies the
 * [TaskChangeListener]s (calendar, Google Tasks, geofences, live chip, widget).
 * Understanding: routine → several commands → one command (rules/LLM) → execute → reply, in the language of the
 * sentence (Spanish or English), remembering the last few turns ("move it to 5pm").
 */
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val assistant: AssistantOrchestrator,
    private val reminders: ReminderScheduler,
    private val settings: SettingsRepository,
    private val listeners: List<TaskChangeListener> = emptyList(),
    private val calendar: DeviceCalendar? = null,
    /** Is the place ("casa", "trabajo") saved in Settings → Places? */
    private val isPlaceKnown: (String) -> Boolean = { true },
    /** Coordinates of a saved place (for "take me home"). */
    private val placeCoordinates: (String) -> Pair<Double, Double>? = { null },
    /** Personal memory (on demand): null in tests. */
    private val memory: MemoryStore? = null,
    /** Weather, routines and messages. */
    private val extras: AssistantExtras = AssistantExtras()
) : TaskRepository {

    /** What Lumi knows about the world beyond your tasks. All optional (null/empty in tests). */
    class AssistantExtras(
        val weather: WeatherService? = null,
        val routines: () -> List<Routine> = { emptyList() },
        /** Unread messages; null = Lumi has no notification access. */
        val unreadMessages: () -> List<IncomingMessage>? = { null },
        /** An unsaved place ("the Mercadona", "the pharmacy") → the nearest one with coordinates, or null. */
        val resolvePlace: suspend (String) -> PlaceTrigger? = { null },
        /** "Then you'll get a button to send Roberto Pérez a WhatsApp…" (contact already looked up). */
        val describeAction: suspend (Task) -> String = { "" },
        /** Web search (#7, opt-in): results for a question, or null when it is off. */
        val webSearch: suspend (String, io.github.salex27.lumi.domain.assistant.Lang) -> List<io.github.salex27.lumi.domain.search.WebHit>? = { _, _ -> null }
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Short-term memory of the conversation ("move it to 5pm", "and tomorrow?"). */
    override val conversation = ConversationContext()
    /** Action of the command being executed, recorded in the conversation. */
    @Volatile private var lastAction: String? = null

    override fun getAllTasks(): Flow<List<Task>> = taskDao.getAllTasks().map { list -> list.map { it.toDomain() } }

    override fun getTasksByStatus(status: TaskStatus): Flow<List<Task>> =
        taskDao.getTasksByStatus(status).map { list -> list.map { it.toDomain() } }

    override fun getPendingCount(): Flow<Int> = taskDao.getPendingCount()

    override suspend fun getTask(id: Long): Task? = taskDao.getTaskById(id)?.toDomain()

    // ── Writing (single entry point: stamps completedAt, reminders, recurrence and listeners) ──

    override suspend fun insertTask(task: Task): Long {
        val stamped = withCompletion(task)
        val id = taskDao.insertTask(stamped.toEntity())
        reminders.schedule(stamped.copy(id = id))
        notifySaved(id)
        return id
    }

    override suspend fun updateTask(task: Task) {
        val existing = taskDao.getTaskById(task.id)
        val updated = withCompletion(task).copy(updatedAt = System.currentTimeMillis())
        // mergeFrom keeps the Google Tasks / Calendar ids
        taskDao.updateTask(existing?.mergeFrom(updated) ?: updated.toEntity())
        reminders.schedule(updated) // recomputes the reminders (or cancels them if no longer active)
        notifySaved(task.id)

        // A recurring task just completed → create the next occurrence
        val justCompleted = existing != null && existing.status != TaskStatus.COMPLETED && updated.status == TaskStatus.COMPLETED
        if (justCompleted) updated.recurrence?.let { spawnNext(updated, it) }
    }

    private suspend fun spawnNext(done: Task, recurrence: Recurrence) {
        val baseDate = done.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now()
        val nextDate = recurrence.next(maxOf(baseDate, LocalDate.now().minusDays(1)))
        val time = done.dueAt?.takeIf { done.dueHasTime }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        val nextDue = (time?.let { nextDate.atTime(it) } ?: nextDate.atTime(9, 0)).atZone(zone).toInstant().toEpochMilli()
        val customOffsets = reminders.remindersFor(done.id).filter { it.kind == TaskReminder.Kind.CUSTOM }.mapNotNull { it.offsetMinutes }

        val next = done.copy(
            id = 0, status = TaskStatus.TODO, completedAt = null, dueAt = nextDue,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), meeting = null
        )
        val id = insertTask(next)
        customOffsets.forEach { reminders.addCustom(next.copy(id = id), it) }
    }

    override suspend fun deleteTask(task: Task) = deleteTaskById(task.id)

    override suspend fun deleteTaskById(id: Long) {
        val existing = taskDao.getTaskById(id)
        reminders.cancel(id) // before deleting: reminder rows cascade with the task
        taskDao.deleteTaskById(id)
        listeners.forEach { runCatching { it.onTaskDeleted(id, existing?.googleTaskId, existing?.calendarEventId) } }
    }

    /** Stamps/clears the completion time from the status (the stats rely on it). */
    private fun withCompletion(task: Task): Task = when {
        task.status == TaskStatus.COMPLETED -> task.copy(completedAt = task.completedAt ?: System.currentTimeMillis())
        else -> task.copy(completedAt = null)
    }

    private suspend fun notifySaved(id: Long) = listeners.forEach { runCatching { it.onTaskSaved(id) } }

    // ── Reminders ─────────────────────────────────────────────────────────────

    override suspend fun remindersFor(taskId: Long): List<TaskReminder> = reminders.remindersFor(taskId)

    override suspend fun addCustomReminder(task: Task, offsetMinutes: Int) = reminders.addCustom(task, offsetMinutes)

    override suspend fun removeReminder(reminderId: Long, task: Task) = reminders.remove(reminderId, task)

    override suspend fun rescheduleAllReminders() {
        taskDao.getActiveTasksSnapshot().forEach { reminders.schedule(it.toDomain()) }
    }

    // ── Meetings ──────────────────────────────────────────────────────────────

    override suspend fun upcomingMeetings(days: Int): List<AgendaEvent> {
        val now = System.currentTimeMillis()
        val linked = taskDao.linkedCalendarEventIds().toSet() // events Lumi created for its own tasks
        return calendar?.eventsBetween(now, now + days * 24 * 3_600_000L).orEmpty()
            .filter { !it.allDay && it.id !in linked }
    }

    private suspend fun findMeeting(hint: String): LinkedMeeting? {
        val event = MeetingMatcher.match(hint, upcomingMeetings(14), System.currentTimeMillis()) ?: return null
        return LinkedMeeting(event.id, event.title, event.begin)
    }

    // ── Natural language ─────────────────────────────────────────────────────

    private val en: Boolean get() = ReplyLanguage.current == Lang.EN
    private fun t(es: String, en: String) = ReplyLanguage.t(es, en)

    override suspend fun processNaturalLanguageCommand(
        prompt: String,
        defaultCategory: TaskCategory?,
        route: IntentRoute?
    ): AIProcessingResult = try {
        val now = LocalDateTime.now()
        // The reply follows the language of the sentence; short ambiguous ones keep the app language
        ReplyLanguage.current = LanguageDetector.detect(prompt, ReplyLanguage.app)
        lastAction = null
        val routine = if (route == null) RoutineMatcher.match(prompt, extras.routines()) else null
        val sentence = followUpWeather(prompt) ?: prompt
        // Top-level route first (#1): only unambiguous cases are decided here, the rest goes on as before
        val top = route ?: if (routine == null) IntentRouter.classify(sentence, now) else null
        if (top != null) android.util.Log.i("LumiInterpret", "route=$top for «$sentence»")
        val result = if (routine != null) runRoutine(routine, now) else when (top) {
            IntentRoute.AGENT -> {
                lastAction = ACTION_AGENT
                val request = IntentRouter.agentRequest(sentence) ?: sentence
                AIProcessingResult.Agent("claude", request, t("Se lo paso a Claude en tu PC.", "Passing it to Claude on your PC."), assistant.rulesName)
            }
            IntentRoute.OPINION -> { lastAction = TaskAICommand.ASK; answer(sentence, now, assistant.rulesName) }
            IntentRoute.UNSURE -> {
                lastAction = ACTION_CLARIFY
                AIProcessingResult.Clarify(
                    sentence, listOf(IntentRoute.TASK, IntentRoute.AGENT),
                    t("¿Lo apunto como tarea o se lo paso a Claude?", "Should I add it as a task or send it to Claude?"), assistant.rulesName
                )
            }
            IntentRoute.TASK -> {
                // The user said "task": whatever the interpretation, it becomes one (date and category still parsed)
                val (command, engine) = assistant.interpret(sentence, now, conversation.promptNote())
                val forced = if (command.action == TaskAICommand.CREATE || command.action == TaskAICommand.CREATE_MANY) command
                else command.copy(action = TaskAICommand.CREATE, targetTitle = IntentRouter.wishObject(sentence))
                execute(forced, sentence, defaultCategory, now, engine)
            }
            else -> {
            val parts = CommandSplitter.split(sentence)
            if (parts.size > 1) runSteps(parts, now, useLlm = true, name = null)
            else {
                val (command, engine) = assistant.interpret(sentence, now, conversation.promptNote())
                execute(command, sentence, defaultCategory, now, engine)
            }
            }
        }
        remember(prompt, result)
        result
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AIProcessingResult.Error(t("Algo ha fallado al procesar tu mensaje: ", "Something went wrong processing your message: ") + e.localizedMessage)
    }

    override suspend fun executeCommand(command: TaskAICommand, defaultCategory: TaskCategory?): AIProcessingResult = try {
        execute(command, command.targetTitle.orEmpty(), defaultCategory, LocalDateTime.now(), assistant.rulesName)
            .also { remember(command.targetTitle.orEmpty(), it) }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AIProcessingResult.Error(t("Algo ha fallado: ", "Something went wrong: ") + e.localizedMessage)
    }

    /** Records the turn so the next sentence can refer to it ("move it", "and tomorrow?"). */
    private fun remember(prompt: String, result: AIProcessingResult) {
        val task = when (result) {
            is AIProcessingResult.Created -> result.task
            is AIProcessingResult.Updated -> result.task
            is AIProcessingResult.OpenTask -> result.task
            is AIProcessingResult.CreatedMany -> result.tasks.lastOrNull()
            is AIProcessingResult.Routine -> result.tasks.lastOrNull()
            else -> null
        }
        conversation.record(prompt, result.reply, lastAction, task)
    }

    /** "¿Y mañana?" / "and tomorrow?" right after a weather answer → a weather question for that day. */
    private fun followUpWeather(prompt: String): String? {
        if (conversation.lastAction() != TaskAICommand.WEATHER) return null
        Regex("(?iu)^\\s*¿?\\s*y\\s+(.{2,40}?)\\s*\\??\\s*$").find(prompt)?.let { return "¿qué tiempo hace ${it.groupValues[1]}?" }
        Regex("(?i)^\\s*(?:and|what\\s+about|how\\s+about)\\s+(.{2,40}?)\\s*\\??\\s*$").find(prompt)?.let { return "what's the weather ${it.groupValues[1]}?" }
        return null
    }

    private suspend fun execute(
        command: TaskAICommand, prompt: String, defaultCategory: TaskCategory?, now: LocalDateTime, engine: String
    ): AIProcessingResult {
        lastAction = command.action
        return when (command.action) {
            TaskAICommand.CREATE -> createTask(command, prompt, defaultCategory, now, engine)
            TaskAICommand.CREATE_MANY -> createMany(command, defaultCategory, now, engine)
            TaskAICommand.UPDATE_STATUS -> updateStatus(command, now, engine)
            TaskAICommand.RESCHEDULE -> reschedule(command, prompt, defaultCategory, now, engine)
            TaskAICommand.SET_PRIORITY -> setPriority(command, now, engine)
            TaskAICommand.EDIT -> edit(command, engine)
            TaskAICommand.NAVIGATE -> navigate(command.targetTitle.orEmpty(), engine)
            TaskAICommand.REMEMBER -> rememberFact(command.targetTitle.orEmpty(), engine)
            TaskAICommand.FORGET -> forget(command.targetTitle.orEmpty(), engine)
            TaskAICommand.RECALL -> recall(command.targetTitle?.ifBlank { null } ?: prompt, now, engine)
            TaskAICommand.ASK -> answer(command.targetTitle?.ifBlank { null } ?: prompt, now, engine)
            TaskAICommand.WEATHER -> weather(command.targetTitle?.ifBlank { null } ?: prompt, now, engine)
            TaskAICommand.DAY_BRIEF -> dayBrief(command.dueDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: now.toLocalDate())
            TaskAICommand.SMART_ALARM -> smartAlarm(ask = command.newStatus == "ASK", now = now, engine = engine)
            TaskAICommand.NOTIFICATIONS -> readMessages(command.targetTitle.orEmpty(), now, engine)
            TaskAICommand.DEVICE -> device(command, prompt, engine)
            TaskAICommand.PLAN_DAY -> planMyDay()
            TaskAICommand.SUMMARIZE -> generateDailyBriefing()
            else -> AIProcessingResult.Error(
                t("No he entendido «$prompt». Prueba con «recuérdame…» o «¿qué hago hoy?»", "I didn't understand «$prompt». Try «remind me to…» or «what should I do today?»"),
                engine
            )
        }
    }

    /** Builds and saves the task of a CREATE (without writing the reply). */
    private suspend fun buildAndSave(command: TaskAICommand, prompt: String, defaultCategory: TaskCategory?): Task {
        // Last safety net: no engine leaves "Que se llama…" / "A task called…" in the title
        val title = TaskPhraseParser.cleanTitle(command.targetTitle?.takeIf { it.isNotBlank() } ?: prompt.trim())
        // Category: the AI's > keywords > the filter active in the UI > PERSONAL
        val category = TaskCategory.fromString(command.category)
            ?: CategoryHeuristics.infer(title)
            ?: defaultCategory
            ?: TaskCategory.PERSONAL
        var dueAt = command.dueDate?.let { runCatching { AssistantOrchestrator.parseIso(it) }.getOrNull() }
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
        var hasTime = dueAt != null && command.hasTime

        // Link to a mentioned meeting; with no own date → 1 h before the meeting
        val meeting = command.meeting?.let { findMeeting(it) }
        if (meeting != null && dueAt == null) {
            dueAt = meeting.start - 3_600_000L
            hasTime = true
        }

        val task = Task(
            title = title,
            description = command.description?.trim().orEmpty(), // only if the user gave one
            status = command.newStatus?.let { TaskStatus.fromString(it) } ?: TaskStatus.TODO,
            category = category,
            dueAt = dueAt,
            dueHasTime = hasTime,
            recurrence = Recurrence.parse(command.recurrence),
            meeting = meeting,
            priority = TaskPriority.fromString(command.priority) ?: TaskPriority.NONE,
            placeTrigger = command.place?.takeIf { it.isNotBlank() }?.let { resolveTrigger(it, command.placeOnArrive) }
        )
        val saved = task.copy(id = insertTask(task))
        command.remindBeforeMinutes.forEach { reminders.addCustom(saved, it) }
        return saved
    }

    private suspend fun createTask(
        command: TaskAICommand, prompt: String, defaultCategory: TaskCategory?, now: LocalDateTime, engine: String
    ): AIProcessingResult {
        val saved = buildAndSave(command, prompt, defaultCategory)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.TaskCreated(saved, now))
        val action = runCatching { extras.describeAction(saved) }.getOrDefault("")
        return AIProcessingResult.Created(saved, reply + action + placeHint(saved) + weatherHint(saved, now), preferLlm(engine, replyEngine))
    }

    /** Outdoor task at a time with rain forecast (only with an already downloaded forecast: never waits for the network). */
    private fun weatherHint(task: Task, now: LocalDateTime): String {
        val report = extras.weather?.cachedFresh() ?: return ""
        val warning = WeatherAdvisor.taskWarnings(listOf(task), report, now).firstOrNull() ?: return ""
        warning.rainProb?.let { return t(" Ojo: a esa hora hay lluvia probable ($it %).", " Heads up: rain is likely at that time ($it %).") }
        warning.hotC?.let { return t(" Ojo: a esa hora hará ${WeatherAdvisor.deg(it)}.", " Heads up: it will be ${WeatherAdvisor.deg(it)} at that time.") }
        return ""
    }

    /**
     * "When I get to the Mercadona": if it isn't a saved place nor one of "yours" (home, work… which have to be saved
     * while there), the nearest one is looked up and the task carries its coordinates.
     */
    private suspend fun resolveTrigger(place: String, onArrive: Boolean): PlaceTrigger {
        val key = TaskPhraseParser.normalizePlace(place)
        if (isPlaceKnown(key) || key in PERSONAL_PLACES) return PlaceTrigger(key, onArrive)
        val found = runCatching { extras.resolvePlace(place) }.getOrNull() ?: return PlaceTrigger(key, onArrive)
        return found.copy(onArrive = onArrive)
    }

    /** A place reminder for a place Lumi doesn't know yet → explain how to save it. */
    private fun placeHint(task: Task): String {
        val trigger = task.placeTrigger ?: return ""
        // A place found on the map: say which one so the user can change it if it's the wrong one
        if (trigger.isAdHoc) return trigger.address?.takeIf { it.isNotBlank() }?.let {
            t(" Es el de $it (el más cercano que he encontrado; puedes cambiarlo en la tarea).", " It's the one at $it (the nearest I found; you can change it in the task).")
        }.orEmpty()
        val place = trigger.place
        if (isPlaceKnown(place)) return ""
        val name = PlaceTrigger.displayName(place)
        return t(
            " Aún no sé dónde está «$place»: cuando estés allí, guárdalo en Ajustes → Lugares y el aviso se activará.",
            " I don't know where «$name» is yet: when you're there, save it in Settings → Places and the reminder will turn on."
        )
    }

    private sealed interface Target {
        data class Found(val task: Task) : Target
        data class Ask(val result: AIProcessingResult.Choose) : Target
        data object Missing : Target
    }

    /**
     * Which task does the user mean? targetId (already picked) > the one we were talking about ("move it") >
     * a clear match > ask "Did you mean…?". Searches the active ones first; if nothing, also closed ones (to reopen).
     */
    private suspend fun resolve(command: TaskAICommand, engine: String): Target {
        command.targetId?.let { id -> getTask(id)?.let { return Target.Found(it) } }
        if (command.refersToLast) {
            return conversation.lastTaskId()?.let { getTask(it) }?.let { Target.Found(it) } ?: Target.Missing
        }
        val query = command.targetTitle.orEmpty()
        if (query.isBlank()) return Target.Missing
        val active = taskDao.getActiveTasksSnapshot().map { it.toDomain() }
        var decision = TaskMatcher.decide(query, active)
        if (decision == TaskMatcher.Decision.NotFound) {
            decision = TaskMatcher.decide(query, taskDao.getAllTasksSnapshot().map { it.toDomain() })
        }
        return when (decision) {
            is TaskMatcher.Decision.Sure -> Target.Found(decision.task)
            is TaskMatcher.Decision.Ask -> {
                val question = if (decision.candidates.size == 1) t("¿Te refieres a «${decision.candidates.first().title}»?", "Did you mean «${decision.candidates.first().title}»?")
                else t("¿A cuál te refieres?", "Which one do you mean?")
                Target.Ask(AIProcessingResult.Choose(decision.candidates, command, question, engine))
            }
            TaskMatcher.Decision.NotFound -> Target.Missing
        }
    }

    private fun notFound(command: TaskAICommand, engine: String) = AIProcessingResult.Error(
        if (command.refersToLast) t("¿De qué tarea hablas? Dime su nombre.", "Which task do you mean? Tell me its name.")
        else t("No encuentro ninguna tarea parecida a «${command.targetTitle.orEmpty()}».", "I can't find any task like «${command.targetTitle.orEmpty()}»."),
        engine
    )

    private suspend fun setPriority(command: TaskAICommand, now: LocalDateTime, engine: String): AIProcessingResult {
        val priority = TaskPriority.fromString(command.priority) ?: TaskPriority.HIGH
        val task = when (val t = resolve(command, engine)) {
            is Target.Found -> t.task
            is Target.Ask -> return t.result
            Target.Missing -> return notFound(command, engine)
        }
        val updated = task.copy(priority = priority)
        updateTask(updated)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.PriorityChanged(updated, now))
        return AIProcessingResult.Updated(updated, reply, preferLlm(engine, replyEngine))
    }

    /**
     * Edit: applies whatever was said (title, date, category, priority, note, place). With no concrete change
     * ("edit the dentist one") → opens that task's editor.
     */
    private suspend fun edit(command: TaskAICommand, engine: String): AIProcessingResult {
        // Rename with an ambiguous separator: pick the split whose left side matches a real task
        val cmd = command.renameSpec?.let { spec ->
            val all = taskDao.getAllTasksSnapshot().map { it.toDomain() }
            RenameSplitter.split(spec, all)?.let { (target, title) ->
                command.copy(targetTitle = target.replace(Regex("(?iu)^(?:la|el|lo\\s+de(?:l)?|the)\\s+"), ""), newTitle = title.replaceFirstChar { it.uppercase() })
            }
        } ?: command
        val task = when (val t = resolve(cmd, engine)) {
            is Target.Found -> t.task
            is Target.Ask -> return t.result
            Target.Missing -> return notFound(cmd, engine)
        }
        val changes = mutableListOf<String>()
        var updated = task
        cmd.newTitle?.takeIf { it.isNotBlank() }?.let { updated = updated.copy(title = it); changes += t("se llama «$it»", "is now called «$it»") }
        cmd.dueDate?.let { runCatching { AssistantOrchestrator.parseIso(it) }.getOrNull() }?.let { dt ->
            updated = updated.copy(dueAt = dt.atZone(zone).toInstant().toEpochMilli(), dueHasTime = cmd.hasTime)
            changes += t("queda para ", "is set for ") + DueDateFormatter.format(updated.dueAt!!, updated.dueHasTime)
        }
        TaskCategory.fromString(cmd.category)?.let { updated = updated.copy(category = it); changes += t("pasa a ${it.label}", "moves to ${it.label}") }
        TaskPriority.fromString(cmd.priority)?.let { updated = updated.copy(priority = it); changes += t("prioridad ${it.label.lowercase()}", "${it.label.lowercase()} priority") }
        cmd.description?.takeIf { it.isNotBlank() }?.let { updated = updated.copy(description = it); changes += t("con la nota «$it»", "with the note «$it»") }
        cmd.place?.takeIf { it.isNotBlank() }?.let {
            updated = updated.copy(placeTrigger = PlaceTrigger(TaskPhraseParser.normalizePlace(it), cmd.placeOnArrive))
            changes += t("aviso ", "reminder ") + updated.placeTrigger!!.describe()
        }
        if (changes.isEmpty()) return AIProcessingResult.OpenTask(task, t("Te abro «${task.title}» para que la edites.", "Opening «${task.title}» so you can edit it."), engine)
        updateTask(updated)
        return AIProcessingResult.Updated(updated, t("Hecho: «${task.title}» ", "Done: «${task.title}» ") + changes.joinToString(", ") + ".", engine)
    }

    // ── Personal memory (on demand) ─────────────────────────────────────────

    private suspend fun rememberFact(fact: String, engine: String): AIProcessingResult {
        val store = memory ?: return AIProcessingResult.Error(t("La memoria no está disponible.", "Memory isn't available."), engine)
        if (fact.isBlank()) return AIProcessingResult.Error(t("¿Qué quieres que recuerde?", "What do you want me to remember?"), engine)
        store.add(fact)
        return AIProcessingResult.Memory(t("Lo recordaré: «$fact».", "I'll remember: «$fact»."), engine)
    }

    private suspend fun forget(query: String, engine: String): AIProcessingResult {
        val store = memory ?: return AIProcessingResult.Error(t("La memoria no está disponible.", "Memory isn't available."), engine)
        val hit = MemoryRetriever.relevant(query, store.all(), 1).firstOrNull()
            ?: return AIProcessingResult.Memory(t("No tenía nada apuntado sobre eso.", "I had nothing saved about that."), engine)
        store.delete(hit.id)
        return AIProcessingResult.Memory(t("Olvidado: «${hit.text}».", "Forgotten: «${hit.text}»."), engine)
    }

    /**
     * Question: 1) personal memory (only the 2-3 related memories), 2) your tasks ("when is the dentist thing?"),
     * 3) general knowledge. The LLM (if any) phrases the answer using ONLY that data.
     */
    private suspend fun recall(question: String, now: LocalDateTime, engine: String): AIProcessingResult {
        val facts = memory?.let { MemoryRetriever.relevant(question, it.all(), 3) }.orEmpty().map { it.text }
        val task = (TaskMatcher.decide(question, taskDao.getAllTasksSnapshot().map { it.toDomain() }) as? TaskMatcher.Decision.Sure)?.task
        if (facts.isEmpty() && task == null) return answer(question, now, engine)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.Recall(question, facts, task, now))
        return AIProcessingResult.Memory(reply, preferLlm(engine, replyEngine))
    }

    // ── Phone actions ───────────────────────────────────────────────────────

    private suspend fun device(command: TaskAICommand, prompt: String, engine: String): AIProcessingResult {
        var cmd = DeviceCommand.parse(command.device)
            ?: (if (en) EnglishCommands.device(prompt) else DeviceCommandParser.parse(prompt))
            ?: return AIProcessingResult.Error(t("No sé hacer eso en el móvil todavía.", "I can't do that on the phone yet."), engine)
        // An explicit "search for…" answers from the web with sources when search is on (#13); otherwise it opens the browser
        (cmd as? DeviceCommand.WebSearch)?.let { search -> webAnswer(search.query)?.let { return it } }
        var usedEngine = engine
        if (cmd is DeviceCommand.Message) {
            // 1) The LLM (if any) separates recipient and text and rewrites it as a direct message
            if (prompt.isNotBlank()) refineMessage(cmd, prompt)?.let { (refined, llm) -> cmd = refined; usedEngine = llm }
            val msg = cmd as DeviceCommand.Message
            // 2) If something is still missing, ask (the user's next sentence fills it in)
            if (msg.contact.isBlank()) {
                return AIProcessingResult.AskFollowUp(command.copy(device = msg.serialize()), "contact", t("¿A quién se lo envío?", "Who should I send it to?"), usedEngine)
            }
            if (msg.text.isBlank()) {
                return AIProcessingResult.AskFollowUp(command.copy(device = msg.serialize()), "message", t("¿Qué le digo a ${msg.contact}?", "What should I tell ${msg.contact}?"), usedEngine)
            }
            // Does the contact have an unread message with "Reply"? → answer right there (after confirming), without opening the app
            extras.unreadMessages()?.let { unread ->
                MessageDigest.filter(unread, msg.contact).lastOrNull { it.canReply && !it.isGroup }?.let { m ->
                    val reply = DeviceCommand.ReplyMessage(m.key, m.conversation, msg.text, m.app)
                    return AIProcessingResult.AskFollowUp(
                        command.copy(device = reply.serialize()), "confirm",
                        t("Le respondo a ${m.conversation} por ${m.app}: «${msg.text}». ¿Lo envío?", "I'll reply to ${m.conversation} on ${m.app}: «${msg.text}». Send it?"), usedEngine
                    )
                }
            }
        }
        val engineName = usedEngine
        val reply = when (val c = cmd) {
            is DeviceCommand.OpenApp -> t("Abriendo ${c.name}.", "Opening ${c.name}.")
            is DeviceCommand.Alarm -> t("Alarma a las %02d:%02d.", "Alarm set for %02d:%02d.").format(c.hour, c.minute)
            is DeviceCommand.Timer -> t("Temporizador de ", "Timer for ") + ReminderPlannerText.duration(c.seconds) + "."
            is DeviceCommand.Call -> t("Llamando a ${c.contact}.", "Calling ${c.contact}.")
            is DeviceCommand.Message -> if (en) "I've prepared the ${if (c.whatsapp) "WhatsApp" else "message"} for ${c.contact}; you just have to send it."
                else "Te preparo el ${if (c.whatsapp) "WhatsApp" else "mensaje"} para ${c.contact}; solo tienes que enviarlo."
            is DeviceCommand.PlayMusic -> if (c.query.isBlank()) t("Poniendo música.", "Playing music.") else t("Poniendo ${c.query}.", "Playing ${c.query}.")
            is DeviceCommand.WebSearch -> t("Buscando «${c.query}».", "Searching «${c.query}».")
            is DeviceCommand.OpenSettings -> t("Abriendo ${c.panel.label}.", "Opening ${c.panel.label}.")
            is DeviceCommand.Flashlight -> if (c.on) t("Linterna encendida.", "Flashlight on.") else t("Linterna apagada.", "Flashlight off.")
            is DeviceCommand.DoNotDisturb -> if (c.on) t("No molestar activado.", "Do Not Disturb is on.") else t("No molestar desactivado.", "Do Not Disturb is off.")
            is DeviceCommand.ReplyMessage -> t("Enviado a ${c.contact}.", "Sent to ${c.contact}.")
        }
        return AIProcessingResult.Device(cmd, reply, engineName)
    }

    /**
     * WhatsApp / SMS with the LLM: extracts recipient and text however it was said and turns reported speech into the
     * message itself ("dile que si viene a cenar" → "¿Vienes a cenar?", "tell her I'll be late" → "I'll be late").
     * If the LLM fails or returns something odd, the rules' data is kept.
     */
    private suspend fun refineMessage(rules: DeviceCommand.Message, prompt: String): Pair<DeviceCommand.Message, String>? {
        val (raw, llm) = assistant.ask(MESSAGE_SYSTEM, prompt, 160) ?: return null
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").takeIf { it.isNotBlank() }?.let { "{$it}" } ?: return null
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val contact = obj.optString("contact").trim().takeIf { it.isNotBlank() && it != "null" }
        val text = obj.optString("message").trim().takeIf { it.isNotBlank() && it != "null" }
        // The LLM's text only counts if it doesn't invent: a reasonable length compared with the user's sentence
        val safeText = text?.takeIf { it.length <= prompt.length + 20 }
        val refined = rules.copy(
            contact = rules.contact.ifBlank { contact.orEmpty() },
            text = safeText ?: rules.text
        )
        return refined to llm
    }

    /**
     * Destination of "take me to…": 1) a saved place (home, work…), 2) "the meeting" → the next meeting with an
     * address, 3) a meeting by name, 4) the text as it is (an address or place).
     */
    private suspend fun navigate(target: String, engine: String): AIProcessingResult {
        val query = target.trim()
        if (query.isBlank()) return AIProcessingResult.Error(t("¿A dónde quieres ir?", "Where do you want to go?"), engine)
        val placeKey = TaskPhraseParser.normalizePlace(query)
        placeCoordinates(placeKey)?.let { (lat, lng) ->
            val label = if (en) (if (placeKey == "casa") "home" else "to ${PlaceTrigger.displayName(placeKey)}") else PlaceTrigger.withArticle("a", placeKey)
            return AIProcessingResult.Navigate(NavDestination(placeKey, placeKey, lat, lng), t("Abriendo la ruta $label.", "Opening directions $label."), engine)
        }
        val now = System.currentTimeMillis()
        val upcoming = calendar?.eventsBetween(now - 3_600_000L, now + 3 * 24 * 3_600_000L).orEmpty()
            .filter { !it.allDay && it.location.isNotBlank() && it.end > now }
        val generic = Regex("(?iu)^(?:pr[oó]xima\\s+|siguiente\\s+|next\\s+)?(?:reuni[oó]n|cita|meeting|evento|event|appointment)$").matches(query)
        val event = if (generic) upcoming.minByOrNull { it.begin } else MeetingMatcher.match(query, upcoming, now)
        if (event != null) {
            return AIProcessingResult.Navigate(
                NavDestination(event.title, event.location),
                t("Abriendo la ruta a «${event.title}» (${event.location}).", "Opening directions to «${event.title}» (${event.location})."), engine
            )
        }
        if (generic) return AIProcessingResult.Error(t("Tu próxima reunión no tiene dirección en el calendario.", "Your next meeting has no address in the calendar."), engine)
        return AIProcessingResult.Navigate(NavDestination(query, query), t("Abriendo la ruta a $query.", "Opening directions to $query."), engine)
    }

    // ── Assistant: weather, questions, day summary, alarm, messages, routines ──

    /** While a routine runs (its steps don't repeat what another step already says). */
    @Volatile private var inRoutine = false

    private suspend fun weather(question: String, now: LocalDateTime, engine: String): AIProcessingResult {
        val service = extras.weather ?: return AIProcessingResult.Error(t("El tiempo no está disponible.", "Weather isn't available."), engine)
        val query = if (en) EnglishCommands.weatherQuery(question, now) else AssistantIntents.weather(question, now) ?: WeatherQuery(now.toLocalDate())
        return when (val r = service.forecast(query.place)) {
            is WeatherService.Result.Ok -> AIProcessingResult.Answer(WeatherAdvisor.answer(query, r.report, now), "Open-Meteo")
            is WeatherService.Result.Failed -> AIProcessingResult.Error(r.message, engine)
        }
    }

    /**
     * General question: 1) maths (no AI), 2) Gemini online with Google when it needs current data,
     * 3) the available LLM, 4) no LLM or it doesn't know → search Google.
     */
    private suspend fun answer(question: String, now: LocalDateTime, engine: String): AIProcessingResult {
        QuickMath.answer(question)?.let { return AIProcessingResult.Answer(it, assistant.rulesName) }
        val context = memory?.let { MemoryRetriever.relevant(question, it.all(), 3) }.orEmpty().map { it.text }
        // Previous questions and answers, so "and if I heat it up?" makes sense
        val history = conversation.recent().filter { it.action == TaskAICommand.ASK || it.action == TaskAICommand.RECALL }.takeLast(2)
            .joinToString(" | ") { "Q: ${it.user.take(150)} A: ${it.reply.take(200)}" }
            .let { if (it.isBlank()) "" else "RECENT Q&A (for follow-up questions): $it" }
        val fresh = AssistantIntents.needsFreshData(question)
        // Recent facts (news, prices, results…) go to the web first when web search is on: a small model would guess
        if (fresh) webAnswer(question)?.let { return it }
        val result = assistant.answer(AssistantPrompts.generalSystem(now, context, history), question, web = fresh)
        val text = result?.first?.let(AssistantPrompts::cleanReply)
        android.util.Log.i("LumiInterpret", "Answer to «$question» (${result?.second}): ${text?.take(200)}")
        if (text == null || text.contains("NO_LO_SE")) {
            if (!fresh) webAnswer(question)?.let { return it }
            val query = question.trim().trimEnd('?', '.').replace(Regex("^¿"), "")
            val why = if (result == null) t("No tengo un modelo de IA activo para responder eso", "I don't have an AI model on to answer that")
            else t("No lo sé con seguridad", "I'm not sure")
            return AIProcessingResult.Device(DeviceCommand.WebSearch(query), why + t("; te lo busco en Google.", "; I'll search Google for you."), result?.second ?: engine)
        }
        return AIProcessingResult.Answer(text, result.second)
    }

    /**
     * Searches the web and answers from the results (#7). Null when web search is off or found nothing (the caller
     * falls back to its old behaviour). The results are untrusted data: the prompt says so and only text comes out.
     */
    private suspend fun webAnswer(question: String): AIProcessingResult? {
        val hits = extras.webSearch(question, ReplyLanguage.current)?.takeIf { it.isNotEmpty() } ?: return null
        val sources = hits.take(3)
        val llm = assistant.answer(WebAnswers.SYSTEM, WebAnswers.prompt(question, sources), web = false)
        val text = llm?.first?.let(AssistantPrompts::cleanReply)?.takeIf { !it.contains("NO_LO_SE") && it.isNotBlank() }
        android.util.Log.i("LumiInterpret", "Web answer to «$question» (${sources.size} results, ${llm?.second}): ${text?.take(200)}")
        val reply = text ?: WebAnswers.fallback(sources)?.let { t("Según la web: ", "From the web: ") + it } ?: return null
        return AIProcessingResult.WebAnswer(reply, sources, llm?.second ?: assistant.rulesName)
    }

    override suspend fun dayBrief(date: LocalDate): AIProcessingResult {
        val now = LocalDateTime.now()
        val tasks = taskDao.getAllTasksSnapshot().map { it.toDomain() }
        val linked = taskDao.linkedCalendarEventIds().toSet()
        val events = calendar?.eventsOn(date).orEmpty().filter { it.id !in linked }
        val report = (extras.weather?.forecast() as? WeatherService.Result.Ok)?.report
        // In "good night" the alarm is already announced by its own step: don't repeat it
        val alarm = if (!inRoutine && date == AlarmPlanner.targetDate(now) && date != now.toLocalDate()) smartAlarmPlan(date) else null
        val brief = DayBriefComposer.compose(date, now, tasks, events, report, DueDateFormatter.greeting(now), alarm)
        return AIProcessingResult.Answer(brief.body, assistant.rulesName)
    }

    override suspend fun smartAlarmPlan(date: LocalDate): AlarmPlanner.Plan? {
        val tasks = taskDao.getAllTasksSnapshot().map { it.toDomain() }
        val linked = taskDao.linkedCalendarEventIds().toSet()
        val events = calendar?.eventsOn(date).orEmpty().filter { it.id !in linked }
        return AlarmPlanner.plan(date, events, tasks, settings.current.alarmConfig)
    }

    private suspend fun smartAlarm(ask: Boolean, now: LocalDateTime, engine: String): AIProcessingResult {
        val date = AlarmPlanner.targetDate(now)
        val plan = smartAlarmPlan(date)
            ?: return AIProcessingResult.Answer(
                WeatherAdvisor.dayLabel(date, now.toLocalDate()).replaceFirstChar { it.uppercase() } +
                    t(" no tienes nada temprano, así que no hace falta alarma. Si quieres una, dime la hora.",
                        " you have nothing early, so no alarm is needed. If you want one, tell me the time."),
                engine
            )
        val alarm = DeviceCommand.Alarm(plan.wake.hour, plan.wake.minute, "Lumi · ${plan.anchorTitle}")
        if (ask) {
            return AIProcessingResult.AskFollowUp(
                TaskAICommand(action = TaskAICommand.DEVICE, device = alarm.serialize()), "confirm", plan.reason + t(" ¿Te la pongo?", " Shall I set it?"), engine
            )
        }
        return AIProcessingResult.Device(alarm, plan.reason, engine)
    }

    private suspend fun readMessages(who: String, now: LocalDateTime, engine: String): AIProcessingResult {
        val unread = extras.unreadMessages()
            ?: return AIProcessingResult.Device(
                DeviceCommand.OpenSettings(DeviceCommand.SettingsPanel.NOTIFICATION_ACCESS),
                t("Para leer tus mensajes necesito acceso a las notificaciones. Te abro los ajustes: activa Lumi.",
                    "To read your messages I need notification access. I'm opening the settings: turn Lumi on."), engine
            )
        val list = MessageDigest.filter(unread, who)
        val digest = MessageDigest.compose(list, who)
        if (list.isEmpty()) return AIProcessingResult.Messages(null, digest, engine)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.Messages(list, digest, now))
        val target = MessageDigest.replyTarget(list)
        val invite = if (target != null) t(" ¿Le respondo?", " Shall I reply?") else ""
        return AIProcessingResult.Messages(target, reply + invite, replyEngine)
    }

    /**
     * Routine: each step is a normal sentence interpreted with rules (fast and predictable). Information is gathered
     * into the reply; phone actions and the route are launched by the UI in order.
     */
    private suspend fun runRoutine(routine: Routine, now: LocalDateTime): AIProcessingResult {
        val r = runSteps(routine.steps, now, useLlm = false, name = routine.name) as AIProcessingResult.Routine
        val opener = when (routine.id) {
            "night" -> t("Buenas noches.", "Good night.")
            "morning" -> DueDateFormatter.greeting(now) + "."
            else -> t("Hecho.", "Done.")
        }
        return r.copy(reply = "$opener ${r.reply}".trim())
    }

    /**
     * A sequence of commands (a routine, or several things said in one sentence). Information is gathered into the
     * reply; phone actions and the route are launched by the UI in order. In a user sentence ([useLlm]), if a
     * command needs a choice or an answer ("Did you mean…?"), it stops there and asks.
     */
    private suspend fun runSteps(steps: List<String>, now: LocalDateTime, useLlm: Boolean, name: String?): AIProcessingResult {
        val texts = mutableListOf<String>()
        val tasks = mutableListOf<Task>()
        val devices = mutableListOf<DeviceCommand>()
        var navigate: NavDestination? = null
        var engineUsed = assistant.rulesName
        inRoutine = !useLlm
        try {
            for (step in steps) {
                val (cmd, eng) = if (useLlm) assistant.interpret(step, now, conversation.promptNote()).let { it.command to it.engineName }
                else assistant.rulesInterpret(step, now) to assistant.rulesName
                if (eng != assistant.rulesName) engineUsed = eng
                // In a routine, a step that isn't understood would end up creating a task: say so instead
                if (!useLlm && (cmd.action == TaskAICommand.CREATE || cmd.action == TaskAICommand.CREATE_MANY)) {
                    texts += t("No sé hacer «$step».", "I don't know how to do «$step».")
                    continue
                }
                when (val r = execute(cmd, step, null, now, eng)) {
                    is AIProcessingResult.Choose -> if (useLlm) return r.copy(reply = (texts + r.reply).joinToString(" ")) else texts += r.reply
                    is AIProcessingResult.AskFollowUp -> if (useLlm) return r.copy(reply = (texts + r.reply).joinToString(" ")) else texts += r.reply.substringBefore(" ¿").substringBefore(" Shall")
                    is AIProcessingResult.Created -> { tasks += r.task; texts += r.reply }
                    is AIProcessingResult.CreatedMany -> { tasks += r.tasks; texts += r.reply }
                    is AIProcessingResult.Updated -> { tasks += r.task; texts += r.reply }
                    is AIProcessingResult.Device -> {
                        devices += r.command
                        if (r.command is DeviceCommand.Alarm || useLlm) texts += r.reply
                    }
                    is AIProcessingResult.Navigate -> { navigate = r.destination; if (useLlm) texts += r.reply }
                    is AIProcessingResult.Error -> texts += r.reply
                    else -> texts += r.reply
                }
            }
        } finally {
            inRoutine = false
        }
        val ordered = devices.sortedBy { if (it.staysInLumi) 0 else 1 }
        val body = texts.filter { it.isNotBlank() }.joinToString(if (useLlm) "\n" else " ")
        return AIProcessingResult.Routine(ordered, navigate, body, if (name != null) t("Rutina · $name", "Routine · $name") else engineUsed, tasks)
    }

    private suspend fun createMany(
        command: TaskAICommand, defaultCategory: TaskCategory?, now: LocalDateTime, engine: String
    ): AIProcessingResult {
        val saved = command.items.map { buildAndSave(it, it.targetTitle.orEmpty(), defaultCategory) }
        val (reply, replyEngine) = assistant.reply(ReplyRequest.TasksCreated(saved, now))
        return AIProcessingResult.CreatedMany(saved, reply, preferLlm(engine, replyEngine))
    }

    private suspend fun reschedule(
        command: TaskAICommand, prompt: String, defaultCategory: TaskCategory?, now: LocalDateTime, engine: String
    ): AIProcessingResult {
        val task = when (val t = resolve(command, engine)) {
            is Target.Found -> t.task
            is Target.Ask -> return t.result
            // "Move it" without a recent task → ask which one
            Target.Missing -> if (command.refersToLast) return notFound(command, engine)
            // No task to move: probably a new task ("pasa por el banco mañana")
            else return createTask(assistant.rulesCreate(prompt, now), prompt, defaultCategory, now, engine)
        }
        val (newDue, newHasTime) = when {
            command.postponeMinutes != null -> {
                val base = task.dueAt ?: System.currentTimeMillis()
                (base + command.postponeMinutes * 60_000L) to (task.dueHasTime || command.postponeMinutes % (60 * 24) != 0)
            }
            command.dueDate != null -> {
                val parsed = AssistantOrchestrator.parseIso(command.dueDate)
                // Only the day changes and the task had a time → keep the time
                val keepTime = !command.hasTime && task.dueHasTime && task.dueAt != null
                val dt = if (keepTime) parsed.toLocalDate().atTime(Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalTime()) else parsed
                dt.atZone(zone).toInstant().toEpochMilli() to (command.hasTime || keepTime)
            }
            else -> return AIProcessingResult.Error(t("¿A cuándo quieres mover «${task.title}»?", "When do you want to move «${task.title}» to?"), engine)
        }
        val updated = task.copy(dueAt = newDue, dueHasTime = newHasTime)
        updateTask(updated)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.TaskRescheduled(updated, task.dueAt, now))
        return AIProcessingResult.Updated(updated, reply, preferLlm(engine, replyEngine))
    }

    private suspend fun updateStatus(command: TaskAICommand, now: LocalDateTime, engine: String): AIProcessingResult {
        val targetStatus = command.newStatus?.let { TaskStatus.fromString(it) } ?: TaskStatus.COMPLETED
        // A new task used to be created when none was found (too literal); now Lumi asks or says so
        val task = when (val t = resolve(command, engine)) {
            is Target.Found -> t.task
            is Target.Ask -> return t.result
            Target.Missing -> return notFound(command, engine)
        }
        val updated = task.copy(status = targetStatus)
        updateTask(updated)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.TaskUpdated(updated, now))
        return AIProcessingResult.Updated(updated, reply, preferLlm(engine, replyEngine))
    }

    /**
     * Quick reply typed into a reminder notification (without opening the app):
     * "hecho"/"done" → complete · "en 20 minutos"/"in 20 minutes" → remind again ·
     * "pospón una hora" → move · "mañana a las 10"/"tomorrow at 10" → move to that date · anything else → normal command.
     */
    override suspend fun applyQuickReply(taskId: Long, text: String): String {
        ReplyLanguage.current = LanguageDetector.detect(text, ReplyLanguage.app)
        val task = getTask(taskId) ?: return t("La tarea ya no existe", "The task no longer exists")
        val lower = text.trim().lowercase()
        val now = LocalDateTime.now()

        if (Regex("^(hecho|hecha|listo|lista|terminad[oa]|completad[oa]|ya est[aá]|done|ok|finished|completed)\\b").containsMatchIn(lower)) {
            updateTask(task.copy(status = TaskStatus.COMPLETED))
            return t("«${task.title}» completada ✓", "«${task.title}» done ✓")
        }
        val relativeMinutes = TaskPhraseParser.findRelativeAmount(lower)?.first
            ?: Regex("\\b(?:in|by|for)\\s+(.+)$").find(lower)?.let { EnglishCommands.amountMinutes(it.groupValues[1]) }
        if (relativeMinutes != null && Regex("^(en|dentro de|av[ií]same|recu[eé]rdamelo|recu[eé]rdame|in|remind me)\\b").containsMatchIn(lower)) {
            reminders.snooze(task.id, relativeMinutes) // the task doesn't move, just another reminder
            return t("Te vuelvo a avisar en ", "I'll remind you again in ") + ReminderPlanner.humanMinutes(relativeMinutes)
        }
        if (relativeMinutes != null) {
            val base = task.dueAt ?: System.currentTimeMillis()
            val updated = task.copy(dueAt = base + relativeMinutes * 60_000L, dueHasTime = true)
            updateTask(updated)
            return t("Movida a ", "Moved to ") + DueDateFormatter.format(updated.dueAt!!, true)
        }
        (if (en) EnglishDateParser.parse(text, now) else SpanishDateParser.parse(text, now))?.let { d ->
            val updated = task.copy(dueAt = d.dateTime.atZone(zone).toInstant().toEpochMilli(), dueHasTime = d.hasTime)
            updateTask(updated)
            return t("Movida a ", "Moved to ") + DueDateFormatter.format(updated.dueAt!!, updated.dueHasTime)
        }
        return processNaturalLanguageCommand(text).reply
    }

    // ── Plan and summary ─────────────────────────────────────────────────────

    override suspend fun planMyDay(): AIProcessingResult {
        val now = LocalDateTime.now()
        val s = settings.current
        val tasks = taskDao.getAllTasksSnapshot().map { it.toDomain() }
        val plan = DayPlanner.plan(tasks, now, DayPlanner.WorkSchedule(s.workStartHour, s.workEndHour))
        // Today's calendar events, without the ones Lumi created for its own tasks (they'd be duplicated)
        val linked = taskDao.linkedCalendarEventIds().toSet()
        val events = calendar?.eventsOn(now.toLocalDate()).orEmpty().filter { it.id !in linked }
        val free = FreeTimeFinder.find(now, events, tasks, plan.overdue + plan.dueToday + plan.suggestions)
        val (reply, engine) = assistant.reply(ReplyRequest.DayPlan(plan, now, events, free))
        return AIProcessingResult.Plan(plan.overdue + plan.dueToday + plan.suggestions, reply, engine)
    }

    override suspend fun generateDailyBriefing(): AIProcessingResult {
        val tasks = taskDao.getAllTasksSnapshot().map { it.toDomain() }
        val (reply, engine) = assistant.reply(ReplyRequest.Briefing(tasks, LocalDateTime.now()))
        return AIProcessingResult.Summary(reply, engine)
    }

    /** When an LLM understood the intent but the rules wrote the reply, show the LLM. */
    private fun preferLlm(interpretEngine: String, replyEngine: String) =
        if (replyEngine == assistant.rulesName) interpretEngine else replyEngine
}

/** "Own" places: not looked up on the map, they have to be saved (while there, or with their address). */
private val PERSONAL_PLACES = setOf("casa", "trabajo", "oficina", "universidad", "colegio", "clase", "gimnasio")

private const val MESSAGE_SYSTEM = """You extract a WhatsApp/SMS message the user wants to send. Reply ONLY with JSON:
{"contact":"who (as they say it: «Víctor», «my mom», «mamá»)","message":"the text to send"}
Rules: write the message as the user would send it to the recipient, first person, direct speech, in the user's language.
Turn reported speech into the message: "dile que llego tarde" → "Llego tarde"; "pregúntale si viene a cenar" → "¿Vienes a cenar?";
"tell her I'll be late" → "I'll be late"; "ask him if he's coming to dinner" → "Are you coming to dinner?".
Don't add greetings or anything they didn't say. If something is missing, use null.
Examples:
"envíale un wasap a Víctor diciéndole que ya estoy abajo" → {"contact":"Víctor","message":"Ya estoy abajo"}
"text my mom asking if she needs anything from the store" → {"contact":"my mom","message":"Do you need anything from the store?"}
"manda un whatsapp a Ana" → {"contact":"Ana","message":null}"""

/** 90 → "1 min 30 s", 600 → "10 min", 3600 → "1 h". */
internal object ReminderPlannerText {
    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds % 60 == 0 && seconds < 3600 -> "${seconds / 60} min"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

/** Conversation actions of the top-level router (recorded with the turn, not TaskAICommand actions). */
private const val ACTION_AGENT = "AGENT"
private const val ACTION_CLARIFY = "CLARIFY"
