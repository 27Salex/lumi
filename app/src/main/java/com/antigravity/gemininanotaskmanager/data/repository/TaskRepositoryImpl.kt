package com.antigravity.gemininanotaskmanager.data.repository

import com.antigravity.gemininanotaskmanager.data.ai.AssistantOrchestrator
import com.antigravity.gemininanotaskmanager.data.ai.CategoryHeuristics
import com.antigravity.gemininanotaskmanager.data.ai.DayPlanner
import com.antigravity.gemininanotaskmanager.data.ai.MeetingMatcher
import com.antigravity.gemininanotaskmanager.data.ai.SpanishDateParser
import com.antigravity.gemininanotaskmanager.data.ai.TaskPhraseParser
import com.antigravity.gemininanotaskmanager.data.local.TaskDao
import com.antigravity.gemininanotaskmanager.data.local.TaskEntity
import com.antigravity.gemininanotaskmanager.data.local.mergeFrom
import com.antigravity.gemininanotaskmanager.data.local.toDomain
import com.antigravity.gemininanotaskmanager.data.local.toEntity
import com.antigravity.gemininanotaskmanager.data.settings.SettingsRepository
import com.antigravity.gemininanotaskmanager.data.sync.DeviceCalendar
import com.antigravity.gemininanotaskmanager.domain.ai.ReplyRequest
import com.antigravity.gemininanotaskmanager.domain.repository.MemoryStore
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommandParser
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommand
import com.antigravity.gemininanotaskmanager.domain.assistant.MemoryRetriever
import com.antigravity.gemininanotaskmanager.domain.assistant.RenameSplitter
import com.antigravity.gemininanotaskmanager.domain.assistant.TaskMatcher
import com.antigravity.gemininanotaskmanager.domain.model.AIProcessingResult
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.LinkedMeeting
import com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger
import com.antigravity.gemininanotaskmanager.domain.model.NavDestination
import com.antigravity.gemininanotaskmanager.domain.assistant.FreeTimeFinder
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.Recurrence
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskReminder
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import com.antigravity.gemininanotaskmanager.domain.reminder.ReminderScheduler
import com.antigravity.gemininanotaskmanager.domain.repository.TaskChangeListener
import com.antigravity.gemininanotaskmanager.domain.repository.TaskRepository
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
import com.antigravity.gemininanotaskmanager.data.ai.AssistantIntents
import com.antigravity.gemininanotaskmanager.data.ai.AssistantPrompts
import com.antigravity.gemininanotaskmanager.data.ai.QuickMath
import com.antigravity.gemininanotaskmanager.data.weather.WeatherService
import com.antigravity.gemininanotaskmanager.domain.assistant.AlarmPlanner
import com.antigravity.gemininanotaskmanager.domain.assistant.DayBriefComposer
import com.antigravity.gemininanotaskmanager.domain.assistant.MessageDigest
import com.antigravity.gemininanotaskmanager.domain.weather.WeatherAdvisor
import com.antigravity.gemininanotaskmanager.domain.weather.WeatherQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val assistant: AssistantOrchestrator,
    private val reminders: ReminderScheduler,
    private val settings: SettingsRepository,
    private val listeners: List<TaskChangeListener> = emptyList(),
    private val calendar: DeviceCalendar? = null,
    /** ¿El lugar ("casa", "trabajo") está guardado en Ajustes → Lugares? */
    private val isPlaceKnown: (String) -> Boolean = { true },
    /** Coordenadas de un lugar guardado (para «llévame a casa»). */
    private val placeCoordinates: (String) -> Pair<Double, Double>? = { null },
    /** Memoria personal (bajo demanda): null en tests. */
    private val memory: MemoryStore? = null,
    /** v3.6: tiempo, rutinas y mensajes. */
    private val extras: AssistantExtras = AssistantExtras()
) : TaskRepository {

    /** Lo que Lumi sabe del mundo además de tus tareas. Todo opcional (null/vacío en tests). */
    class AssistantExtras(
        val weather: com.antigravity.gemininanotaskmanager.data.weather.WeatherService? = null,
        val routines: () -> List<com.antigravity.gemininanotaskmanager.domain.assistant.Routine> = { emptyList() },
        /** Mensajes sin leer; null = Lumi no tiene acceso a las notificaciones. */
        val unreadMessages: () -> List<com.antigravity.gemininanotaskmanager.domain.assistant.IncomingMessage>? = { null },
        /** Sitio no guardado («el Mercadona», «la farmacia») → el más cercano con sus coordenadas, o null. */
        val resolvePlace: suspend (String) -> PlaceTrigger? = { null },
        /** «Al llegar a casa tendrás un botón para enviar un WhatsApp a Roberto Pérez…» (contacto ya buscado). */
        val describeAction: suspend (Task) -> String = { "" }
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun getAllTasks(): Flow<List<Task>> = taskDao.getAllTasks().map { list -> list.map { it.toDomain() } }

    override fun getTasksByStatus(status: TaskStatus): Flow<List<Task>> =
        taskDao.getTasksByStatus(status).map { list -> list.map { it.toDomain() } }

    override fun getPendingCount(): Flow<Int> = taskDao.getPendingCount()

    override suspend fun getTask(id: Long): Task? = taskDao.getTaskById(id)?.toDomain()

    // ── Escritura (único punto: sella completedAt, avisos, recurrencia y listeners) ──

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
        // mergeFrom conserva los ids de Google Tasks / Calendario
        taskDao.updateTask(existing?.mergeFrom(updated) ?: updated.toEntity())
        reminders.schedule(updated) // recalcula los avisos (o los cancela si ya no está activa)
        notifySaved(task.id)

        // Recurrente recién completada → crear la siguiente ocurrencia
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
        reminders.cancel(id) // antes de borrar: las filas de avisos caen en cascada con la tarea
        taskDao.deleteTaskById(id)
        listeners.forEach { runCatching { it.onTaskDeleted(id, existing?.googleTaskId, existing?.calendarEventId) } }
    }

    /** Sella/borra la fecha de finalización según el estado (lo usan las estadísticas). */
    private fun withCompletion(task: Task): Task = when {
        task.status == TaskStatus.COMPLETED -> task.copy(completedAt = task.completedAt ?: System.currentTimeMillis())
        else -> task.copy(completedAt = null)
    }

    private suspend fun notifySaved(id: Long) = listeners.forEach { runCatching { it.onTaskSaved(id) } }

    // ── Avisos ────────────────────────────────────────────────────────────────

    override suspend fun remindersFor(taskId: Long): List<TaskReminder> = reminders.remindersFor(taskId)

    override suspend fun addCustomReminder(task: Task, offsetMinutes: Int) = reminders.addCustom(task, offsetMinutes)

    override suspend fun removeReminder(reminderId: Long, task: Task) = reminders.remove(reminderId, task)

    override suspend fun rescheduleAllReminders() {
        taskDao.getActiveTasksSnapshot().forEach { reminders.schedule(it.toDomain()) }
    }

    // ── Reuniones ─────────────────────────────────────────────────────────────

    override suspend fun upcomingMeetings(days: Int): List<AgendaEvent> {
        val now = System.currentTimeMillis()
        val linked = taskDao.linkedCalendarEventIds().toSet() // eventos creados por Lumi para sus tareas
        return calendar?.eventsBetween(now, now + days * 24 * 3_600_000L).orEmpty()
            .filter { !it.allDay && it.id !in linked }
    }

    private suspend fun findMeeting(hint: String): LinkedMeeting? {
        val event = MeetingMatcher.match(hint, upcomingMeetings(14), System.currentTimeMillis()) ?: return null
        return LinkedMeeting(event.id, event.title, event.begin)
    }

    // ── Lenguaje natural ─────────────────────────────────────────────────────

    override suspend fun processNaturalLanguageCommand(
        prompt: String,
        defaultCategory: TaskCategory?
    ): AIProcessingResult = try {
        val now = LocalDateTime.now()
        val routine = com.antigravity.gemininanotaskmanager.domain.assistant.RoutineMatcher.match(prompt, extras.routines())
        val parts = if (routine == null) com.antigravity.gemininanotaskmanager.domain.assistant.CommandSplitter.split(prompt) else emptyList()
        if (routine != null) runRoutine(routine, now)
        else if (parts.size > 1) runSteps(parts, now, useLlm = true, name = null)
        else {
            val (command, engine) = assistant.interpret(prompt, now)
            execute(command, prompt, defaultCategory, now, engine)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AIProcessingResult.Error("Algo ha fallado al procesar tu mensaje: ${e.localizedMessage}")
    }

    override suspend fun executeCommand(command: TaskAICommand, defaultCategory: TaskCategory?): AIProcessingResult = try {
        execute(command, command.targetTitle.orEmpty(), defaultCategory, LocalDateTime.now(), assistant.rulesName)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AIProcessingResult.Error("Algo ha fallado: ${e.localizedMessage}")
    }

    private suspend fun execute(
        command: TaskAICommand, prompt: String, defaultCategory: TaskCategory?, now: LocalDateTime, engine: String
    ): AIProcessingResult = when (command.action) {
        TaskAICommand.CREATE -> createTask(command, prompt, defaultCategory, now, engine)
        TaskAICommand.CREATE_MANY -> createMany(command, defaultCategory, now, engine)
        TaskAICommand.UPDATE_STATUS -> updateStatus(command, now, engine)
        TaskAICommand.RESCHEDULE -> reschedule(command, prompt, defaultCategory, now, engine)
        TaskAICommand.SET_PRIORITY -> setPriority(command, now, engine)
        TaskAICommand.EDIT -> edit(command, engine)
        TaskAICommand.NAVIGATE -> navigate(command.targetTitle.orEmpty(), engine)
        TaskAICommand.REMEMBER -> remember(command.targetTitle.orEmpty(), engine)
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
        else -> AIProcessingResult.Error("No he entendido «$prompt». Prueba con «recuérdame…» o «¿qué hago hoy?»", engine)
    }

    /** Construye y guarda la tarea de un CREATE (sin redactar respuesta). */
    private suspend fun buildAndSave(command: TaskAICommand, prompt: String, defaultCategory: TaskCategory?): Task {
        // Última red: ningún motor deja «Que se llama…» / «Una tarea llamada…» en el título
        val title = TaskPhraseParser.cleanTitle(command.targetTitle?.takeIf { it.isNotBlank() } ?: prompt.trim())
        // Prioridad: categoría de la IA > palabras clave > filtro activo en la UI > PERSONAL
        val category = TaskCategory.fromString(command.category)
            ?: CategoryHeuristics.infer(title)
            ?: defaultCategory
            ?: TaskCategory.PERSONAL
        var dueAt = command.dueDate?.let { runCatching { AssistantOrchestrator.parseIso(it) }.getOrNull() }
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
        var hasTime = dueAt != null && command.hasTime

        // Vincular a una reunión mencionada; sin fecha propia → 1 h antes de la reunión
        val meeting = command.meeting?.let { findMeeting(it) }
        if (meeting != null && dueAt == null) {
            dueAt = meeting.start - 3_600_000L
            hasTime = true
        }

        val task = Task(
            title = title,
            description = command.description?.trim().orEmpty(), // solo si el usuario la dio
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

    /** Tarea al aire libre a una hora con lluvia prevista (solo con la previsión ya descargada: no se espera a la red). */
    private fun weatherHint(task: Task, now: LocalDateTime): String {
        val report = extras.weather?.cachedFresh() ?: return ""
        val warning = WeatherAdvisor.taskWarnings(listOf(task), report, now).firstOrNull() ?: return ""
        // «… y hay lluvia probable (70 %).» / «… con 34°: mucho calor.» → solo la parte del tiempo
        val what = warning.message.substringAfter(" y hay ", "").ifBlank { warning.message.substringAfter(" con ") }
        return " Ojo: a esa hora ${if (warning.message.contains(" y hay ")) "hay " else "habrá "}$what"
    }

    /**
     * «Cuando llegue al Mercadona»: si no es un lugar guardado ni uno «tuyo» (casa, trabajo… que hay que guardar
     * estando allí), se busca el más cercano y la tarea lleva sus coordenadas.
     */
    private suspend fun resolveTrigger(place: String, onArrive: Boolean): PlaceTrigger {
        val key = TaskPhraseParser.normalizePlace(place)
        if (isPlaceKnown(key) || key in PERSONAL_PLACES) return PlaceTrigger(key, onArrive)
        val found = runCatching { extras.resolvePlace(place) }.getOrNull() ?: return PlaceTrigger(key, onArrive)
        return found.copy(onArrive = onArrive)
    }

    /** Aviso por lugar a un sitio que Lumi aún no conoce → se explica cómo guardarlo. */
    private fun placeHint(task: Task): String {
        val trigger = task.placeTrigger ?: return ""
        // Sitio buscado en el mapa: se dice cuál para que puedas cambiarlo si no es ese
        if (trigger.isAdHoc) return trigger.address?.takeIf { it.isNotBlank() }
            ?.let { " Es el de $it (el más cercano que he encontrado; puedes cambiarlo en la tarea)." }.orEmpty()
        val place = trigger.place
        return if (isPlaceKnown(place)) "" else
            " Aún no sé dónde está «$place»: cuando estés allí, guárdalo en Ajustes → Lugares y el aviso se activará."
    }

    private sealed interface Target {
        data class Found(val task: Task) : Target
        data class Ask(val result: AIProcessingResult.Choose) : Target
        data object Missing : Target
    }

    /**
     * ¿Qué tarea quiere el usuario? targetId (ya elegida) > coincidencia clara > preguntar «¿Te refieres a…?».
     * Busca primero entre las activas; si no hay nada, también entre las cerradas (p.ej. para reabrir).
     */
    private suspend fun resolve(command: TaskAICommand, engine: String): Target {
        command.targetId?.let { id -> getTask(id)?.let { return Target.Found(it) } }
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
                val question = if (decision.candidates.size == 1) "¿Te refieres a «${decision.candidates.first().title}»?"
                else "¿A cuál te refieres?"
                Target.Ask(AIProcessingResult.Choose(decision.candidates, command, question, engine))
            }
            TaskMatcher.Decision.NotFound -> Target.Missing
        }
    }

    private fun notFound(command: TaskAICommand, engine: String) =
        AIProcessingResult.Error("No encuentro ninguna tarea parecida a «${command.targetTitle.orEmpty()}».", engine)

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
     * Editar: aplica lo que se haya dicho (título, fecha, área, prioridad, nota, lugar). Sin cambios concretos
     * («edita lo del dentista») → abre el editor de esa tarea.
     */
    private suspend fun edit(command: TaskAICommand, engine: String): AIProcessingResult {
        // Renombrar con «a» ambiguo: se elige el corte cuyo lado izquierdo encaja con una tarea real
        val cmd = command.renameSpec?.let { spec ->
            val all = taskDao.getAllTasksSnapshot().map { it.toDomain() }
            RenameSplitter.split(spec, all)?.let { (target, title) ->
                command.copy(targetTitle = target.replace(Regex("(?iu)^(?:la|el|lo\\s+de(?:l)?)\\s+"), ""), newTitle = title.replaceFirstChar { it.uppercase() })
            }
        } ?: command
        val task = when (val t = resolve(cmd, engine)) {
            is Target.Found -> t.task
            is Target.Ask -> return t.result
            Target.Missing -> return notFound(cmd, engine)
        }
        val changes = mutableListOf<String>()
        var updated = task
        cmd.newTitle?.takeIf { it.isNotBlank() }?.let { updated = updated.copy(title = it); changes += "se llama «$it»" }
        cmd.dueDate?.let { runCatching { AssistantOrchestrator.parseIso(it) }.getOrNull() }?.let { dt ->
            updated = updated.copy(dueAt = dt.atZone(zone).toInstant().toEpochMilli(), dueHasTime = cmd.hasTime)
            changes += "queda para ${DueDateFormatter.format(updated.dueAt!!, updated.dueHasTime)}"
        }
        TaskCategory.fromString(cmd.category)?.let { updated = updated.copy(category = it); changes += "pasa a ${it.label}" }
        TaskPriority.fromString(cmd.priority)?.let { updated = updated.copy(priority = it); changes += "prioridad ${it.label.lowercase()}" }
        cmd.description?.takeIf { it.isNotBlank() }?.let { updated = updated.copy(description = it); changes += "con la nota «$it»" }
        cmd.place?.takeIf { it.isNotBlank() }?.let {
            updated = updated.copy(placeTrigger = PlaceTrigger(TaskPhraseParser.normalizePlace(it), cmd.placeOnArrive))
            changes += "aviso ${updated.placeTrigger!!.describe()}"
        }
        if (changes.isEmpty()) return AIProcessingResult.OpenTask(task, "Te abro «${task.title}» para que la edites.", engine)
        updateTask(updated)
        return AIProcessingResult.Updated(updated, "Hecho: «${task.title}» ${changes.joinToString(", ")}.", engine)
    }

    // ── Memoria personal (bajo demanda) ─────────────────────────────────────

    private suspend fun remember(fact: String, engine: String): AIProcessingResult {
        val store = memory ?: return AIProcessingResult.Error("La memoria no está disponible.", engine)
        if (fact.isBlank()) return AIProcessingResult.Error("¿Qué quieres que recuerde?", engine)
        store.add(fact)
        return AIProcessingResult.Memory("Lo recordaré: «$fact».", engine)
    }

    private suspend fun forget(query: String, engine: String): AIProcessingResult {
        val store = memory ?: return AIProcessingResult.Error("La memoria no está disponible.", engine)
        val hit = MemoryRetriever.relevant(query, store.all(), 1).firstOrNull()
            ?: return AIProcessingResult.Memory("No tenía nada apuntado sobre eso.", engine)
        store.delete(hit.id)
        return AIProcessingResult.Memory("Olvidado: «${hit.text}».", engine)
    }

    /**
     * Pregunta: 1) memoria personal (solo los 2-3 recuerdos relacionados), 2) tus tareas («¿cuándo es lo del
     * dentista?»), 3) no lo sé. El LLM (si hay) redacta la respuesta usando SOLO esos datos.
     */
    private suspend fun recall(question: String, now: LocalDateTime, engine: String): AIProcessingResult {
        val facts = memory?.let { MemoryRetriever.relevant(question, it.all(), 3) }.orEmpty().map { it.text }
        val task = (TaskMatcher.decide(question, taskDao.getAllTasksSnapshot().map { it.toDomain() }) as? TaskMatcher.Decision.Sure)?.task
        if (facts.isEmpty() && task == null) return answer(question, now, engine)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.Recall(question, facts, task, now))
        return AIProcessingResult.Memory(reply, preferLlm(engine, replyEngine))
    }

    // ── Acciones del móvil ──────────────────────────────────────────────────

    private suspend fun device(command: TaskAICommand, prompt: String, engine: String): AIProcessingResult {
        var cmd = DeviceCommand.parse(command.device) ?: DeviceCommandParser.parse(prompt)
            ?: return AIProcessingResult.Error("No sé hacer eso en el móvil todavía.", engine)
        var usedEngine = engine
        if (cmd is DeviceCommand.Message) {
            // 1) El LLM (si hay) separa destinatario y texto y lo reescribe como mensaje directo
            if (prompt.isNotBlank()) refineMessage(cmd, prompt)?.let { (refined, llm) -> cmd = refined; usedEngine = llm }
            val msg = cmd as DeviceCommand.Message
            // 2) Si aún falta algo, se pregunta (la siguiente frase del usuario lo rellena)
            if (msg.contact.isBlank()) {
                return AIProcessingResult.AskFollowUp(command.copy(device = msg.serialize()), "contact", "¿A quién se lo envío?", usedEngine)
            }
            if (msg.text.isBlank()) {
                return AIProcessingResult.AskFollowUp(command.copy(device = msg.serialize()), "message", "¿Qué le digo a ${msg.contact}?", usedEngine)
            }
            // ¿Tiene un mensaje sin leer con «Responder»? → se contesta ahí mismo (tras confirmar), sin abrir la app
            extras.unreadMessages()?.let { unread ->
                MessageDigest.filter(unread, msg.contact).lastOrNull { it.canReply && !it.isGroup }?.let { m ->
                    val reply = DeviceCommand.ReplyMessage(m.key, m.conversation, msg.text, m.app)
                    return AIProcessingResult.AskFollowUp(
                        command.copy(device = reply.serialize()), "confirm",
                        "Le respondo a ${m.conversation} por ${m.app}: «${msg.text}». ¿Lo envío?", usedEngine
                    )
                }
            }
        }
        val engineName = usedEngine
        val reply = when (cmd) {
            is DeviceCommand.OpenApp -> "Abriendo ${cmd.name}."
            is DeviceCommand.Alarm -> "Alarma a las %02d:%02d.".format(cmd.hour, cmd.minute)
            is DeviceCommand.Timer -> "Temporizador de ${ReminderPlannerText.duration(cmd.seconds)}."
            is DeviceCommand.Call -> "Llamando a ${cmd.contact}."
            is DeviceCommand.Message -> "Te preparo el ${if (cmd.whatsapp) "WhatsApp" else "mensaje"} para ${cmd.contact}; solo tienes que enviarlo."
            is DeviceCommand.PlayMusic -> if (cmd.query.isBlank()) "Poniendo música." else "Poniendo ${cmd.query}."
            is DeviceCommand.WebSearch -> "Buscando «${cmd.query}»."
            is DeviceCommand.OpenSettings -> "Abriendo ${cmd.panel.label}."
            is DeviceCommand.Flashlight -> if (cmd.on) "Linterna encendida." else "Linterna apagada."
            is DeviceCommand.DoNotDisturb -> if (cmd.on) "No molestar activado." else "No molestar desactivado."
            is DeviceCommand.ReplyMessage -> "Enviado a ${cmd.contact}."
        }
        return AIProcessingResult.Device(cmd, reply, engineName)
    }

    /**
     * WhatsApp / SMS con el LLM: extrae destinatario y texto de cualquier forma de decirlo y convierte el estilo
     * indirecto en el mensaje tal cual («dile que si viene a cenar» → «¿Vienes a cenar?»). Si el LLM falla o
     * devuelve algo raro, se quedan los datos de las reglas.
     */
    private suspend fun refineMessage(rules: DeviceCommand.Message, prompt: String): Pair<DeviceCommand.Message, String>? {
        val (raw, llm) = assistant.ask(MESSAGE_SYSTEM, prompt, 160) ?: return null
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "").takeIf { it.isNotBlank() }?.let { "{$it}" } ?: return null
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val contact = obj.optString("contact").trim().takeIf { it.isNotBlank() && it != "null" }
        val text = obj.optString("message").trim().takeIf { it.isNotBlank() && it != "null" }
        // El texto del LLM solo vale si no se inventa: longitud razonable respecto a la frase del usuario
        val safeText = text?.takeIf { it.length <= prompt.length + 20 }
        val refined = rules.copy(
            contact = rules.contact.ifBlank { contact.orEmpty() },
            text = safeText ?: rules.text
        )
        return refined to llm
    }

    /**
     * Destino de «llévame a…»: 1) lugar guardado (casa, trabajo…), 2) «la reunión» / «la cita» → la próxima reunión
     * con dirección, 3) una reunión por su nombre, 4) el texto tal cual (dirección o sitio).
     */
    private suspend fun navigate(target: String, engine: String): AIProcessingResult {
        val query = target.trim()
        if (query.isBlank()) return AIProcessingResult.Error("¿A dónde quieres ir?", engine)
        val placeKey = TaskPhraseParser.normalizePlace(query)
        placeCoordinates(placeKey)?.let { (lat, lng) ->
            val label = PlaceTrigger.withArticle("a", placeKey)
            return AIProcessingResult.Navigate(NavDestination(placeKey, placeKey, lat, lng), "Abriendo la ruta $label.", engine)
        }
        val now = System.currentTimeMillis()
        val upcoming = calendar?.eventsBetween(now - 3_600_000L, now + 3 * 24 * 3_600_000L).orEmpty()
            .filter { !it.allDay && it.location.isNotBlank() && it.end > now }
        val generic = Regex("(?iu)^(?:pr[oó]xima\\s+|siguiente\\s+)?(?:reuni[oó]n|cita|meeting|evento)$").matches(query)
        val event = if (generic) upcoming.minByOrNull { it.begin } else MeetingMatcher.match(query, upcoming, now)
        if (event != null) {
            return AIProcessingResult.Navigate(
                NavDestination(event.title, event.location),
                "Abriendo la ruta a «${event.title}» (${event.location}).", engine
            )
        }
        if (generic) return AIProcessingResult.Error("Tu próxima reunión no tiene dirección en el calendario.", engine)
        return AIProcessingResult.Navigate(NavDestination(query, query), "Abriendo la ruta a $query.", engine)
    }

    // ── Asistente (v3.6): tiempo, preguntas, resumen del día, alarma, mensajes, rutinas ──

    /** Mientras se ejecuta una rutina (sus pasos no repiten lo que ya dice otro paso). */
    @Volatile private var inRoutine = false

    private suspend fun weather(question: String, now: LocalDateTime, engine: String): AIProcessingResult {
        val service = extras.weather ?: return AIProcessingResult.Error("El tiempo no está disponible.", engine)
        val query = AssistantIntents.weather(question, now) ?: WeatherQuery(now.toLocalDate())
        return when (val r = service.forecast(query.place)) {
            is WeatherService.Result.Ok -> AIProcessingResult.Answer(WeatherAdvisor.answer(query, r.report, now), "Open-Meteo")
            is WeatherService.Result.Failed -> AIProcessingResult.Error(r.message, engine)
        }
    }

    /**
     * Pregunta general: 1) cuentas (sin IA), 2) Gemini online con Google si necesita datos actuales,
     * 3) el LLM disponible, 4) sin LLM o si no lo sabe → se busca en Google.
     */
    private suspend fun answer(question: String, now: LocalDateTime, engine: String): AIProcessingResult {
        QuickMath.answer(question)?.let { return AIProcessingResult.Answer(it, assistant.rulesName) }
        val context = memory?.let { MemoryRetriever.relevant(question, it.all(), 3) }.orEmpty().map { it.text }
        val result = assistant.answer(AssistantPrompts.generalSystem(now, context), question, web = AssistantIntents.needsFreshData(question))
        val text = result?.first?.let(AssistantPrompts::cleanReply)
        android.util.Log.i("LumiInterpret", "Respuesta a «$question» (${result?.second}): ${text?.take(200)}")
        if (text == null || text.contains("NO_LO_SE")) {
            val query = question.trim().trimEnd('?', '.').replace(Regex("^¿"), "")
            val why = if (result == null) "No tengo un modelo de IA activo para responder eso" else "No lo sé con seguridad"
            return AIProcessingResult.Device(DeviceCommand.WebSearch(query), "$why; te lo busco en Google.", result?.second ?: engine)
        }
        return AIProcessingResult.Answer(text, result.second)
    }

    override suspend fun dayBrief(date: LocalDate): AIProcessingResult {
        val now = LocalDateTime.now()
        val tasks = taskDao.getAllTasksSnapshot().map { it.toDomain() }
        val linked = taskDao.linkedCalendarEventIds().toSet()
        val events = calendar?.eventsOn(date).orEmpty().filter { it.id !in linked }
        val report = (extras.weather?.forecast() as? WeatherService.Result.Ok)?.report
        // En «buenas noches» la alarma ya la dice su propio paso: no se repite
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
                "${WeatherAdvisor.dayLabel(date, now.toLocalDate()).replaceFirstChar { it.uppercase() }} no tienes nada temprano, así que no hace falta alarma. Si quieres una, dime la hora.",
                engine
            )
        val alarm = DeviceCommand.Alarm(plan.wake.hour, plan.wake.minute, "Lumi · ${plan.anchorTitle}")
        if (ask) {
            return AIProcessingResult.AskFollowUp(
                TaskAICommand(action = TaskAICommand.DEVICE, device = alarm.serialize()), "confirm", plan.reason + " ¿Te la pongo?", engine
            )
        }
        return AIProcessingResult.Device(alarm, plan.reason, engine)
    }

    private suspend fun readMessages(who: String, now: LocalDateTime, engine: String): AIProcessingResult {
        val unread = extras.unreadMessages()
            ?: return AIProcessingResult.Device(
                DeviceCommand.OpenSettings(DeviceCommand.SettingsPanel.NOTIFICATION_ACCESS),
                "Para leer tus mensajes necesito acceso a las notificaciones. Te abro los ajustes: activa Lumi.", engine
            )
        val list = MessageDigest.filter(unread, who)
        val digest = MessageDigest.compose(list, who)
        if (list.isEmpty()) return AIProcessingResult.Messages(null, digest, engine)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.Messages(list, digest, now))
        val target = MessageDigest.replyTarget(list)
        val invite = if (target != null) " ¿Le respondo?" else ""
        return AIProcessingResult.Messages(target, reply + invite, replyEngine)
    }

    /**
     * Rutina: cada paso es una frase normal que se interpreta con reglas (rápido y predecible). Lo informativo
     * se junta en la respuesta; las acciones del móvil y la ruta las lanza la UI en orden.
     */
    private suspend fun runRoutine(routine: com.antigravity.gemininanotaskmanager.domain.assistant.Routine, now: LocalDateTime): AIProcessingResult {
        val r = runSteps(routine.steps, now, useLlm = false, name = routine.name) as AIProcessingResult.Routine
        val opener = when (routine.id) {
            "night" -> "Buenas noches."
            "morning" -> DueDateFormatter.greeting(now) + "."
            else -> "Hecho."
        }
        return r.copy(reply = "$opener ${r.reply}".trim())
    }

    /**
     * Secuencia de órdenes (rutina, o varias cosas dichas en una frase). Lo informativo se junta en la respuesta;
     * las acciones del móvil y la ruta las lanza la UI en orden. En una frase del usuario ([useLlm]), si una orden
     * necesita que elijas o respondas («¿Te refieres a…?»), se para ahí y se pregunta.
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
            val (cmd, eng) = if (useLlm) assistant.interpret(step, now).let { it.command to it.engineName }
            else assistant.rulesInterpret(step, now) to assistant.rulesName
            if (eng != assistant.rulesName) engineUsed = eng
            // En una rutina, un paso que no se entiende acabaría creando una tarea: se avisa en su lugar
            if (!useLlm && (cmd.action == TaskAICommand.CREATE || cmd.action == TaskAICommand.CREATE_MANY)) {
                texts += "No sé hacer «$step»."
                continue
            }
            when (val r = execute(cmd, step, null, now, eng)) {
                is AIProcessingResult.Choose -> if (useLlm) return r.copy(reply = (texts + r.reply).joinToString(" ")) else texts += r.reply
                is AIProcessingResult.AskFollowUp -> if (useLlm) return r.copy(reply = (texts + r.reply).joinToString(" ")) else texts += r.reply.substringBefore(" ¿")
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
        return AIProcessingResult.Routine(ordered, navigate, body, if (name != null) "Rutina · $name" else engineUsed, tasks)
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
            // No hay tarea que mover: probablemente era una tarea nueva ("pasa por el banco mañana")
            Target.Missing -> return createTask(assistant.rulesCreate(prompt, now), prompt, defaultCategory, now, engine)
        }
        val (newDue, newHasTime) = when {
            command.postponeMinutes != null -> {
                val base = task.dueAt ?: System.currentTimeMillis()
                (base + command.postponeMinutes * 60_000L) to (task.dueHasTime || command.postponeMinutes % (60 * 24) != 0)
            }
            command.dueDate != null -> {
                val parsed = AssistantOrchestrator.parseIso(command.dueDate)
                // Solo cambia el día y la tarea tenía hora → se conserva la hora
                val keepTime = !command.hasTime && task.dueHasTime && task.dueAt != null
                val dt = if (keepTime) parsed.toLocalDate().atTime(Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalTime()) else parsed
                dt.atZone(zone).toInstant().toEpochMilli() to (command.hasTime || keepTime)
            }
            else -> return AIProcessingResult.Error("¿A cuándo quieres mover «${task.title}»?", engine)
        }
        val updated = task.copy(dueAt = newDue, dueHasTime = newHasTime)
        updateTask(updated)
        val (reply, replyEngine) = assistant.reply(ReplyRequest.TaskRescheduled(updated, task.dueAt, now))
        return AIProcessingResult.Updated(updated, reply, preferLlm(engine, replyEngine))
    }

    private suspend fun updateStatus(command: TaskAICommand, now: LocalDateTime, engine: String): AIProcessingResult {
        val targetStatus = command.newStatus?.let { TaskStatus.fromString(it) } ?: TaskStatus.COMPLETED
        // Antes se creaba una tarea nueva si no encontraba ninguna (demasiado literal); ahora se pregunta o se avisa
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
     * Respuesta rápida desde la notificación de un aviso (sin abrir la app):
     * "hecho" → completar · "en 20 minutos" / "avísame en una hora" → volver a avisar ·
     * "pospón una hora" → mover · "mañana a las 10" → mover a esa fecha · otra cosa → comando normal.
     */
    override suspend fun applyQuickReply(taskId: Long, text: String): String {
        val task = getTask(taskId) ?: return "La tarea ya no existe"
        val lower = text.trim().lowercase()
        val now = LocalDateTime.now()

        if (Regex("^(hecho|hecha|listo|lista|terminad[oa]|completad[oa]|ya est[aá]|done|ok)\\b").containsMatchIn(lower)) {
            updateTask(task.copy(status = TaskStatus.COMPLETED))
            return "«${task.title}» completada ✓"
        }
        val relative = TaskPhraseParser.findRelativeAmount(lower)
        if (relative != null && Regex("^(en|dentro de|av[ií]same|recu[eé]rdamelo|recu[eé]rdame)\\b").containsMatchIn(lower)) {
            reminders.snooze(task.id, relative.first) // no se mueve la tarea, solo otro aviso
            return "Te vuelvo a avisar en ${com.antigravity.gemininanotaskmanager.domain.reminder.ReminderPlanner.humanMinutes(relative.first)}"
        }
        if (relative != null) {
            val base = task.dueAt ?: System.currentTimeMillis()
            val updated = task.copy(dueAt = base + relative.first * 60_000L, dueHasTime = true)
            updateTask(updated)
            return "Movida a ${DueDateFormatter.format(updated.dueAt!!, true)}"
        }
        SpanishDateParser.parse(text, now)?.let { d ->
            val updated = task.copy(dueAt = d.dateTime.atZone(zone).toInstant().toEpochMilli(), dueHasTime = d.hasTime)
            updateTask(updated)
            return "Movida a ${DueDateFormatter.format(updated.dueAt!!, updated.dueHasTime)}"
        }
        return processNaturalLanguageCommand(text).reply
    }

    // ── Plan y resumen ───────────────────────────────────────────────────────

    override suspend fun planMyDay(): AIProcessingResult {
        val now = LocalDateTime.now()
        val s = settings.current
        val tasks = taskDao.getAllTasksSnapshot().map { it.toDomain() }
        val plan = DayPlanner.plan(tasks, now, DayPlanner.WorkSchedule(s.workStartHour, s.workEndHour))
        // Eventos de hoy del calendario, sin los que creó Lumi para sus propias tareas (saldrían duplicados)
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

    /** Si la intención la entendió un LLM pero la respuesta la redactaron las reglas, se muestra el LLM. */
    private fun preferLlm(interpretEngine: String, replyEngine: String) =
        if (replyEngine == assistant.rulesName) interpretEngine else replyEngine

    /**
     * Fallback cuando LIKE no encuentra nada: elige la tarea que comparte más palabras
     * significativas (≥ 4 letras) con la consulta, p.ej. "terminé la presentación del sprint"
     * → "Preparar presentación del sprint". Requiere al menos una palabra en común.
     */
    private fun findBestTokenMatch(query: String, tasks: List<TaskEntity>): TaskEntity? {
        fun tokens(text: String) = CategoryHeuristics.normalize(text)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 }
            .toSet()

        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return null
        return tasks
            .map { it to (tokens(it.title) intersect queryTokens).size }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}

/** Lugares propios: no se buscan en el mapa, hay que guardarlos (estando allí o con su dirección). */
private val PERSONAL_PLACES = setOf("casa", "trabajo", "oficina", "universidad", "colegio", "clase", "gimnasio")

private const val MESSAGE_SYSTEM = """Extraes un mensaje de WhatsApp/SMS que el usuario quiere enviar. Responde SOLO con JSON:
{"contact":"a quién (tal cual lo dice: «Víctor», «mi madre», «mamá»)","message":"el texto a enviar"}
Reglas: el mensaje va escrito como lo mandaría el usuario al destinatario, en primera persona y en estilo directo.
Convierte el estilo indirecto: "dile que llego tarde" → "Llego tarde"; "pregúntale si viene a cenar" → "¿Vienes a cenar?";
"dile que le quiero" → "Te quiero". No añadas saludos ni nada que no haya dicho. Si falta un dato, ponlo como null.
Ejemplos:
"envíale un wasap a Víctor diciéndole que ya estoy abajo" → {"contact":"Víctor","message":"Ya estoy abajo"}
"escribe a mi madre por whatsapp que si necesita algo del súper" → {"contact":"mi madre","message":"¿Necesitas algo del súper?"}
"manda un whatsapp a Ana" → {"contact":"Ana","message":null}"""

/** «90 s» → «1 min 30 s», «600» → «10 min», «3600» → «1 h». */
internal object ReminderPlannerText {
    fun duration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds % 60 == 0 && seconds < 3600 -> "${seconds / 60} min"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}
