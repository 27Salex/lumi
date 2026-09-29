package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.AssistantEngine
import io.github.salex27.lumi.domain.ai.DayMode
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.assistant.DeviceCommandParser
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.time.DueDateFormatter
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Motor determinista: siempre disponible, instantáneo y sin red. Es el último eslabón de la cadena
 * y también la red de seguridad cuando un LLM devuelve algo inválido.
 * Kotlin puro (sin android.*) → testeable en JVM.
 */
class RuleBasedEngine(private val random: Random = Random.Default) : AssistantEngine {

    override val displayName = "Motor local de reglas"

    override suspend fun isAvailable() = true

    override suspend fun interpret(prompt: String, now: LocalDateTime): TaskAICommand = parse(prompt, now)

    override suspend fun writeReply(request: ReplyRequest): String = compose(request).replace(Regex(" {2,}"), " ").trim()

    private fun compose(request: ReplyRequest): String = when (request) {
        is ReplyRequest.TaskCreated -> replyCreated(request.task, request.now)
        is ReplyRequest.TaskUpdated -> replyUpdated(request.task)
        is ReplyRequest.TaskRescheduled -> replyRescheduled(request.task, request.now)
        is ReplyRequest.TasksCreated -> replyMany(request.tasks, request.now)
        is ReplyRequest.Recall -> buildString {
            if (request.facts.isNotEmpty()) append("Me dijiste: ").append(request.facts.joinToString(" · ") { "«$it»" }).append(".")
            request.task?.let { t ->
                if (isNotEmpty()) append(" ")
                append("«${t.title}»")
                append(t.dueAt?.let { " es ${DueDateFormatter.format(it, t.dueHasTime, request.now)}" } ?: " no tiene fecha")
                if (t.status == TaskStatus.COMPLETED) append(" (ya está hecha)")
                append(".")
            }
        }
        is ReplyRequest.PriorityChanged -> when (request.task.priority) {
            io.github.salex27.lumi.domain.model.TaskPriority.HIGH -> "Hecho. «${request.task.title}» pasa a prioridad alta; la pondré por delante."
            io.github.salex27.lumi.domain.model.TaskPriority.NONE -> "Hecho. «${request.task.title}» ya no tiene prioridad."
            else -> "Hecho. «${request.task.title}» queda con prioridad ${request.task.priority.label.lowercase()}."
        }
        is ReplyRequest.DayPlan -> replyPlan(request)
        is ReplyRequest.Briefing -> replyBriefing(request.tasks, request.now)
        is ReplyRequest.Messages -> request.digest
    }

    // ── Interpretación ────────────────────────────────────────────────────────

    fun parse(input: String, now: LocalDateTime): TaskAICommand {
        val text = input.trim()
        if (text.isBlank()) return TaskAICommand(action = TaskAICommand.PLAN_DAY)
        val lower = text.lowercase()

        // Antes que resumen/plan: «¿cómo voy a la reunión?» es una ruta, «¿cómo voy?» un resumen
        NAVIGATE_REGEX.find(text)?.let { m ->
            val destination = m.groupValues[1].trim().trimEnd('?', '.', '!')
                .replace(Regex("(?iu)^(?:la|el|mi|mis|los|las)\\s+"), "")
            if (destination.isNotBlank()) return TaskAICommand(action = TaskAICommand.NAVIGATE, targetTitle = destination)
        }
        // Editar antes que las acciones del móvil: «abre la tarea X» edita, «abre Spotify» abre una app
        edit(text, now)?.let { return it }
        DeviceCommandParser.parse(text)?.let { return TaskAICommand(action = TaskAICommand.DEVICE, device = it.serialize()) }

        // v3.6: asistente (tiempo, resumen del día, alarma según la agenda, mensajes) antes que plan/resumen
        if (AssistantIntents.isWeather(text)) return TaskAICommand(action = TaskAICommand.WEATHER, targetTitle = text)
        AssistantIntents.dayBrief(text, now)?.let { return TaskAICommand(action = TaskAICommand.DAY_BRIEF, dueDate = it.toString()) }
        if (AssistantIntents.isSmartAlarm(text)) {
            return TaskAICommand(action = TaskAICommand.SMART_ALARM, newStatus = if (AssistantIntents.isQuestion(text)) "ASK" else null)
        }
        AssistantIntents.notifications(text)?.let { return TaskAICommand(action = TaskAICommand.NOTIFICATIONS, targetTitle = it) }

        if (PLAN_REGEX.containsMatchIn(lower)) return TaskAICommand(action = TaskAICommand.PLAN_DAY)
        if (SUMMARY_REGEX.containsMatchIn(lower)) return TaskAICommand(action = TaskAICommand.SUMMARIZE)
        // «Dame ideas para cenar», «explícame…», «¿cuánto es el 15 % de 80?»: no son tareas
        if (AssistantIntents.isGeneralAsk(text) || QuickMath.answer(text) != null) return TaskAICommand(action = TaskAICommand.ASK, targetTitle = text)
        // Pregunta «escondida» sin «¿?» («…me la puedo comer», «es malo…»): se responde, no se guarda ni se apunta
        if (!AssistantIntents.asksToRemember(text) && AssistantIntents.looksLikeQuestion(text) && !QUESTION_REGEX.containsMatchIn(text)) {
            return TaskAICommand(action = TaskAICommand.ASK, targetTitle = text)
        }

        memory(text, now)?.let { return it }

        setPriority(text)?.let { return it }
        reschedule(text, now)?.let { return it }

        statusChange(COMPLETED_REGEX, lower, text, TaskStatus.COMPLETED)?.let { return it }
        statusChange(IN_PROGRESS_REGEX, lower, text, TaskStatus.IN_PROGRESS)?.let { return it }
        statusChange(CANCEL_REGEX, lower, text, TaskStatus.CANCELLED)?.let { return it }

        // Una pregunta no es una tarea (antes «¿cuál es el wifi de la oficina?» creaba una tarea): se responde
        if (QUESTION_REGEX.containsMatchIn(text)) return TaskAICommand(action = TaskAICommand.RECALL, targetTitle = text.trim())

        // Brain dump: varias tareas en una frase
        val chunks = TaskPhraseParser.splitBrainDump(text)
        if (chunks.size > 1) {
            val shared = SpanishDateParser.parse(text, now) // "mañana tengo que A, B y C" → mañana para todas
            val items = chunks.map { chunk ->
                val item = parseCreate(chunk, now)
                if (item.dueDate == null && shared != null) item.copy(dueDate = shared.isoDate(), hasTime = shared.hasTime) else item
            }
            return TaskAICommand(action = TaskAICommand.CREATE_MANY, items = items)
        }

        return parseCreate(text, now)
    }

    /**
     * CREATE: 1) descripción explícita, 2) avisos extra, 3) recurrencia, 4) fecha, 5) prefijos ("recuérdame"...).
     * La reunión mencionada se deja en el título y se pasa como pista para vincularla.
     */
    fun parseCreate(text: String, now: LocalDateTime): TaskAICommand {
        val (withoutDescription, description) = extractDescription(text)
        val reminders = TaskPhraseParser.extractReminders(withoutDescription)
        val priority = TaskPhraseParser.extractPriority(reminders.remaining)
        val afterPriority = priority?.remaining ?: reminders.remaining
        val place = TaskPhraseParser.extractPlace(afterPriority)
        val afterPlace = place?.remaining ?: afterPriority
        val recurrence = TaskPhraseParser.extractRecurrence(afterPlace)
        val body = recurrence?.remaining ?: afterPlace
        val date = SpanishDateParser.parse(body, now)
        val withoutDate = date?.remainingText ?: body
        val title = infinitive(TaskPhraseParser.cleanTitle(withoutDate.replace(TASK_NAMED_REGEX, "")).replace(CREATE_PREFIX_REGEX, "").trim().trimEnd('.', ',')
            .ifBlank { withoutDate })
            .replaceFirstChar { it.uppercase() }

        // Recurrente sin fecha: primera ocurrencia a partir de hoy (con la hora dicha, si la hay)
        val due = date?.isoDate() ?: recurrence?.recurrence?.let { r ->
            val first = r.firstOnOrAfter(now.toLocalDate())
            if (date?.hasTime == true) first.atTime(date.dateTime.toLocalTime()).toString() else first.toString()
        }

        return TaskAICommand(
            action = TaskAICommand.CREATE,
            targetTitle = title,
            newStatus = TaskStatus.TODO.name,
            category = CategoryHeuristics.infer(text)?.name,
            dueDate = due,
            hasTime = date?.hasTime ?: false,
            description = description,
            recurrence = recurrence?.recurrence?.serialize(),
            remindBeforeMinutes = reminders.offsetsMinutes,
            meeting = TaskPhraseParser.extractMeetingHint(text),
            priority = priority?.priority?.name,
            place = place?.place,
            placeOnArrive = place?.onArrive ?: true
        )
    }

    /** «Avisa a Roberto» → «Avisar a Roberto»: las tareas se titulan en infinitivo, como las demás. */
    private fun infinitive(title: String): String {
        val m = Regex("(?iu)^(av[ií]sa|escr[ií]be|ll[aá]ma|m[aá]nda|env[ií]a|compra|recoge|lleva)(le)?\\b").find(title) ?: return title
        val verb = java.text.Normalizer.normalize(m.groupValues[1].lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val inf = when (verb) { "escribe" -> "escribir"; else -> verb + "r" }
        return inf + m.groupValues[2] + title.substring(m.range.last + 1)
    }

    /**
     * Memoria: «recuerda que el wifi de la oficina es X», «mi dentista es la Dra. López», «olvida que…».
     * «Recuerda que tengo que llamar mañana» es una TAREA (tiene obligación o fecha), no un recuerdo.
     */
    private fun memory(text: String, now: LocalDateTime): TaskAICommand? {
        FORGET_REGEX.find(text)?.let { return TaskAICommand(action = TaskAICommand.FORGET, targetTitle = it.groupValues[1].trim()) }
        val fact = REMEMBER_REGEX.find(text)?.groupValues?.get(1)?.trim()
            ?: text.takeIf { FACT_REGEX.matches(it.trim()) && !QUESTION_REGEX.containsMatchIn(it) }?.trim()
            ?: return null
        val looksLikeTask = Regex("(?iu)\\b(?:tengo\\s+que|hay\\s+que|debo|he\\s+de)\\b").containsMatchIn(fact) ||
            SpanishDateParser.parse(fact, now) != null
        if (looksLikeTask) return parseCreate(fact, now)
        return TaskAICommand(action = TaskAICommand.REMEMBER, targetTitle = fact.trimEnd('.').replaceFirstChar { it.uppercase() })
    }

    /**
     * Editar: «renombra X a Y», «cambia el nombre de X a Y», «añade una nota a X: …», «pasa X a trabajo»,
     * «edita X» (abre el editor), «edita X y ponle prioridad alta / pásala al viernes».
     */
    private fun edit(text: String, now: LocalDateTime): TaskAICommand? {
        RENAME_REGEX.find(text)?.let { m ->
            // «de X a Y» es ambiguo si X o Y llevan «a»: se guarda la frase entera y el repositorio elige el corte
            // cuya parte izquierda encaja mejor con una tarea real (ver RenameSplitter)
            val spec = text.substring(m.groups[1]!!.range.first)
            return TaskAICommand(
                action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]),
                newTitle = m.groupValues[2].trim().replaceFirstChar { it.uppercase() }, renameSpec = spec
            )
        }
        NOTE_REGEX.find(text)?.let { m ->
            return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]), description = m.groupValues[2].trim().replaceFirstChar { it.uppercase() })
        }
        CATEGORY_EDIT_REGEX.find(text)?.let { m ->
            val category = CategoryHeuristics.fromWord(m.groupValues[2]) ?: return@let
            return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]), category = category.name)
        }
        val m = EDIT_REGEX.find(text) ?: return null
        val rest = m.groupValues[1].trim()
        // «edita X y ponle prioridad alta» → objetivo + cambios
        val split = EDIT_CHANGES_SPLIT.find(rest)
        val target = cleanTarget(if (split != null) rest.substring(0, split.range.first) else rest)
        if (split == null) return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = target)
        val changes = rest.substring(split.range.last + 1)
        val priority = TaskPhraseParser.extractPriority(changes)?.priority ?: Regex("(?iu)prioridad\\s+(\\p{L}+)").find(changes)?.let { TaskPhraseParser.priorityWord(it.groupValues[1]) }
        val date = SpanishDateParser.parse(changes, now)
        val category = Regex("(?iu)\\b(trabajo|personal|estudios?|salud)\\b").find(changes)?.let { CategoryHeuristics.fromWord(it.groupValues[1]) }
        val newTitle = Regex("(?iu)(?:de\\s+)?(?:nombre|t[ií]tulo)\\s+(?:a\\s+|por\\s+)?(.+)$").find(changes)?.groupValues?.get(1)
        return TaskAICommand(
            action = TaskAICommand.EDIT, targetTitle = target,
            priority = priority?.name, dueDate = date?.isoDate(), hasTime = date?.hasTime ?: false,
            category = category?.name, newTitle = newTitle?.trim()?.replaceFirstChar { it.uppercase() }
        )
    }

    /** "pon lo del dentista como urgente", "marca el informe con prioridad alta", "prioriza el informe". */
    private fun setPriority(text: String): TaskAICommand? {
        SET_PRIORITY_REGEX.find(text)?.let { m ->
            val priority = TaskPhraseParser.priorityWord(m.groupValues[2]) ?: return null
            return TaskAICommand(action = TaskAICommand.SET_PRIORITY, targetTitle = cleanTarget(m.groupValues[1].trim()), priority = priority.name)
        }
        PRIORITIZE_REGEX.find(text)?.let { m ->
            return TaskAICommand(action = TaskAICommand.SET_PRIORITY, targetTitle = cleanTarget(m.groupValues[1].trim()), priority = "HIGH")
        }
        return null
    }

    /**
     * "mueve la reunión al jueves", "pasa lo del dentista a mañana a las 10",
     * "pospón el informe una semana", "adelanta la llamada una hora".
     */
    private fun reschedule(text: String, now: LocalDateTime): TaskAICommand? {
        val m = RESCHEDULE_REGEX.find(text) ?: return null
        val verb = m.groupValues[1].lowercase()
        var rest = m.groupValues[2].trim()
        val sign = if (verb.startsWith("adelant")) -1 else 1

        val relative = TaskPhraseParser.findRelativeAmount(rest)
        if (relative != null && (verb.startsWith("pos") || verb.startsWith("apla") || verb.startsWith("retras") || verb.startsWith("adelant"))) {
            rest = rest.removeRange(relative.second).trim()
            return TaskAICommand(
                action = TaskAICommand.RESCHEDULE,
                targetTitle = cleanTarget(rest),
                postponeMinutes = relative.first * sign
            )
        }
        val date = SpanishDateParser.parse(rest, now) ?: return null
        return TaskAICommand(
            action = TaskAICommand.RESCHEDULE,
            targetTitle = cleanTarget(date.remainingText),
            dueDate = date.isoDate(),
            hasTime = date.hasTime
        )
    }

    /** "la reunión" → "reunión", "lo del dentista" → "dentista". */
    private fun cleanTarget(text: String) = text
        .replace(Regex("(?iu)^(?:lo\\s+de(?:l)?|la\\s+tarea\\s+de|la|el|los|las|mi|mis)\\s+"), "")
        .trim().trimEnd(',', '.').ifBlank { text }

    /**
     * Separa una descripción SOLO si el usuario la marca explícitamente:
     * "…, descripción: X", "… detalles X", "… nota: X", "… con la nota de que X".
     * "nota" sin dos puntos no cuenta ("sacar buena nota en el examen" es un título).
     */
    fun extractDescription(text: String): Pair<String, String?> {
        val m = DESCRIPTION_REGEX.find(text) ?: return text to null
        val description = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('.')
            ?.replaceFirstChar { it.uppercase() }
        val body = text.removeRange(m.range).trim().trimEnd(',', ';', '.')
        return if (description.isNullOrBlank() || body.isBlank()) text to null else body to description
    }

    private fun statusChange(regex: Regex, lower: String, original: String, status: TaskStatus): TaskAICommand? {
        val m = regex.find(lower) ?: return null
        val extracted = m.groupValues.getOrNull(1)?.trim()?.trimEnd('.', '!')
        val title = extracted?.takeIf { it.isNotBlank() }
            ?: original.replace(Regex("(?i)^(ya\\s+he|marcar|completar|terminar)\\s+"), "").trim()
        return TaskAICommand(
            action = TaskAICommand.UPDATE_STATUS,
            targetTitle = title.replaceFirstChar { it.uppercase() },
            newStatus = status.name
        )
    }

    // ── Respuestas conversacionales ──────────────────────────────────────────

    private fun replyCreated(task: Task, now: LocalDateTime): String {
        val opener = pick("Hecho.", "Anotado.", "Listo.", "Perfecto.")
        val whenPart = task.dueAt?.let { " para ${DueDateFormatter.format(it, task.dueHasTime, now)}" } ?: ""
        val repeat = task.recurrence?.let { " (${it.label().lowercase()})" } ?: ""
        val meeting = task.meeting?.let { " Lo he vinculado a «${it.title}»." } ?: ""
        val priority = if (task.priority == io.github.salex27.lumi.domain.model.TaskPriority.HIGH) " Prioridad alta." else ""
        task.placeTrigger?.let { p ->
            return "$opener «${task.title}» en ${task.category.label}$whenPart$repeat.$priority Te avisaré ${p.describe()}."
        }
        val reminder = when {
            task.dueAt == null && task.meeting == null -> pick(" Si quieres que te avise, dime cuándo.", "")
            task.dueHasTime -> " Te avisaré con tiempo."
            else -> " Te lo recordaré ese día."
        }
        return "$opener «${task.title}» en ${task.category.label}$whenPart$repeat.$priority$meeting$reminder"
    }

    private fun replyRescheduled(task: Task, now: LocalDateTime): String {
        val whenText = task.dueAt?.let { DueDateFormatter.format(it, task.dueHasTime, now) } ?: "sin fecha"
        return pick("Movido.", "Cambiado.", "Hecho.") + " «${task.title}» queda para $whenText. He ajustado los avisos."
    }

    private fun replyMany(tasks: List<Task>, now: LocalDateTime): String = buildString {
        append("He apuntado ${tasks.size} tareas:")
        tasks.forEachIndexed { i, t ->
            append("\n${i + 1}. ${t.title}")
            t.dueAt?.let { append(" — ${DueDateFormatter.format(it, t.dueHasTime, now)}") }
        }
    }

    private fun replyUpdated(task: Task): String = when (task.status) {
        TaskStatus.COMPLETED -> pick(
            "¡Uno menos! «${task.title}» completada.",
            "¡Bien hecho! He marcado «${task.title}» como terminada.",
            " «${task.title}» fuera de la lista. ¡Sigue así!"
        )
        TaskStatus.IN_PROGRESS -> pick(
            " A por ello: «${task.title}» ahora está en marcha.",
            "Vamos allá «${task.title}» pasa a en progreso."
        )
        TaskStatus.CANCELLED -> " De acuerdo, he cancelado «${task.title}»."
        TaskStatus.TODO -> "↩️ «${task.title}» vuelve a pendientes."
    }

    private fun replyPlan(request: ReplyRequest.DayPlan): String {
        val plan = request.plan
        val now = request.now
        val sb = StringBuilder()
        val greeting = DueDateFormatter.greeting(now)
        val weekday = DueDateFormatter.weekdayName(now)

        sb.append(
            when (plan.mode) {
                DayMode.WEEKEND -> "$greeting. Es $weekday, así que hoy toca vida personal"
                DayMode.WORK_HOURS -> "$greeting. Es $weekday en horario de trabajo, vamos a centrarnos"
                DayMode.AFTER_WORK -> "$greeting. La jornada ya terminó, hora de lo tuyo"
            }
        )
        if (plan.postponedWork > 0 && plan.mode != DayMode.WORK_HOURS) {
            sb.append(" (he aparcado ${plural(plan.postponedWork, "tarea", "tareas")} de trabajo que pueden esperar)")
        }
        sb.append(".")

        val upcoming = request.events.filter { it.allDay || it.end > System.currentTimeMillis() }
        if (upcoming.isNotEmpty()) {
            sb.append("\n\nEn tu calendario: ").append(upcoming.take(4).joinToString { AssistantPrompts.eventLine(it) }).append(".")
        }
        if (plan.overdue.isNotEmpty()) {
            sb.append("\n\nSe te ha pasado: ").append(plan.overdue.joinToString { "«${it.title}»" }).append(".")
        }
        if (plan.dueToday.isNotEmpty()) {
            sb.append("\n\nPara hoy: ").append(plan.dueToday.joinToString { t ->
                "«${t.title}»" + (t.dueAt?.takeIf { t.dueHasTime }?.let { " (${DueDateFormatter.format(it, true, now).removePrefix("hoy ")})" } ?: "")
            }).append(".")
        }
        request.freeSlot?.let { free ->
            val until = DueDateFormatter.format(free.end, true, now).removePrefix("hoy ")
            sb.append("\n\nAhora tienes ${free.minutes} min libres (hasta $until)")
            free.suggestion?.let { sb.append(": buen momento para «${it.title}»") }
            sb.append(".")
        }
        if (plan.suggestions.isNotEmpty()) {
            sb.append("\n\nTe propongo:")
            plan.suggestions.forEachIndexed { i, t ->
                sb.append("\n${i + 1}. ${t.title}")
                if (t.status == TaskStatus.IN_PROGRESS) sb.append(" — ya la empezaste")
                t.dueAt?.let { sb.append(" — vence ${DueDateFormatter.format(it, t.dueHasTime, now)}") }
            }
            sb.append("\n\n").append(pick("¿Empezamos por la primera?", "¿Te encaja este plan?", "Dime cuál atacas y la marco en marcha."))
        } else if (plan.overdue.isEmpty() && plan.dueToday.isEmpty()) {
            sb.append("\n\n").append(
                if (plan.mode == DayMode.WEEKEND) "No tienes nada pendiente para ahora. Descansa, te lo has ganado"
                else "No hay nada urgente. Buen momento para adelantar algo o para añadir nuevas tareas."
            )
        }
        return sb.toString()
    }

    private fun replyBriefing(tasks: List<Task>, now: LocalDateTime): String {
        val greeting = DueDateFormatter.greeting(now)
        if (tasks.isEmpty()) return "$greeting. Tu lista está vacía. Cuéntame qué tienes en mente y lo organizo por ti."

        val active = tasks.filter { it.isActive }
        val done = tasks.count { it.status == TaskStatus.COMPLETED }
        val inProgress = active.filter { it.status == TaskStatus.IN_PROGRESS }
        val overdue = active.filter { t -> t.dueAt?.let { DueDateFormatter.isOverdue(it, t.dueHasTime, now) } == true }
        val next = active.filter { it.dueAt != null && it !in overdue }.minByOrNull { it.dueAt!! }
        val progress = if (tasks.isNotEmpty()) done * 100 / tasks.size else 0
        val busiest = active.groupBy { it.category }.maxByOrNull { it.value.size }

        val parts = mutableListOf<String>()
        parts += when {
            active.isEmpty() -> "$greeting. Lo tienes todo cerrado. Día perfecto para descansar o planear lo siguiente."
            progress >= 60 -> "$greeting. Vas fuerte: $done de ${tasks.size} tareas cerradas ($progress %)."
            else -> "$greeting. Tienes ${plural(active.size, "tarea abierta", "tareas abiertas")} y llevas ${plural(done, "hecha", "hechas")} ($progress %)."
        }
        if (overdue.isNotEmpty()) parts += " Ojo: «${overdue.first().title}» ya ha vencido" +
            (if (overdue.size > 1) " (y ${overdue.size - 1} más)." else ".")
        active.filter { it.priority == io.github.salex27.lumi.domain.model.TaskPriority.HIGH && it !in overdue }
            .takeIf { it.isNotEmpty() }
            ?.let { high -> parts += " Lo prioritario: ${high.take(2).joinToString(" y ") { "«${it.title}»" }}." }
        next?.let { parts += " Lo próximo que vence es «${it.title}», ${DueDateFormatter.format(it.dueAt!!, it.dueHasTime, now)}." }
        if (inProgress.isNotEmpty()) parts += " Tienes en marcha «${inProgress.first().title}»; cerrarla hoy te dejaría la mente más libre."
        busiest?.takeIf { it.value.size >= 3 }?.let {
            parts += "La mayor carga está en ${it.key.label} (${it.value.size})."
        }
        return parts.joinToString(" ")
    }

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]
    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    companion object {
        private val PLAN_REGEX = Regex(
            // "que" sin tilde solo al inicio o tras "¿" → "recuérdame que tengo que..." NO es planificar
            "(?:(?:^|¿)\\s*qu[eé]|qué)\\s+(?:puedo|podr[ií]a|deber[ií]a|debo|tengo\\s+que|me\\s+toca|hago|hacer)|" +
                "planifica|plan\\s+(?:para\\s+)?(?:hoy|mañana|el\\s+d[ií]a)|organiza(?:me)?\\s+(?:el|mi)\\s+d[ií]a|" +
                "por\\s+d[oó]nde\\s+empiezo|qu[eé]\\s+me\\s+recomiendas|sugi[eé]reme|what\\s+should\\s+i\\s+do"
        )
        private val SUMMARY_REGEX = Regex(
            "\\b(?:resumen|res[uú]meme|resumir|briefing|sumario)\\b|c[oó]mo\\s+voy|(?:(?:^|¿)\\s*qu[eé]|qué)\\s+tengo\\s+pendiente|mis\\s+tareas\\b"
        )
        private val COMPLETED_REGEX = Regex(
            "(?:ya\\s+(?:he\\s+)?(?:termin(?:é|e|ado)|complet(?:é|e|ado)|hecho|finaliz(?:é|e|ado))|he\\s+terminado|" +
                "marcar?\\s+(?:como\\s+)?(?:hecha|terminada|completada)|\\blisto\\b|\\bdone\\b)\\s*(?:(?:el|la|los|las|de|con|en)\\s+)?(.*)"
        )
        private val IN_PROGRESS_REGEX = Regex(
            "(?:empez(?:ando|ar|ado)|inici(?:ando|ar)|trabajando\\s+en|en\\s+progreso|comenz(?:ando|ar)|estoy\\s+con)\\s*(?:(?:a|el|la|en|con)\\s+)?(.*)"
        )
        private val CANCEL_REGEX = Regex(
            "^(?:cancela(?:r)?|elimina(?:r)?|borra(?:r)?|descarta(?:r)?|olv[ií]da(?:te)?\\s+de)\\s*(?:(?:la|el|tarea)\\s+)?(.*)"
        )
        private val DESCRIPTION_REGEX = Regex(
            "(?iu)[\\s,;.]+(?:" +
                "(?:y\\s+)?(?:la\\s+|con\\s+(?:la\\s+)?)?(?:descripci[oó]n|detalles?)\\s*(?::|es|son)?\\s+(.+)" + "|" +
                "(?:nota|notas)\\s*:\\s*(.+)" + "|" +
                "con\\s+(?:la\\s+)?nota\\s+(?:de\\s+)?(?:que\\s+)?(.+)" +
                ")$"
        )
        // Solo imperativos: "pasar la ITV mañana" (infinitivo) es una tarea nueva, no reprogramar
        private val RESCHEDULE_REGEX = Regex(
            "(?iu)^(mueve|cambia|pasa|posp[oó]n|aplaza|retrasa|adelanta|reprograma)\\s+(.+)$"
        )
        private val SET_PRIORITY_REGEX = Regex(
            "(?iu)^(?:pon(?:le)?|marca|cambia|deja)\\s+(.+?)\\s+(?:como|a|con|en)\\s+(?:prioridad\\s+)?" +
                "(urgente|importante|muy\\s+importante|prioritari[oa]|alta|m[aá]xima|media|normal|baja|sin\\s+prisa|sin\\s+prioridad|ninguna)\\.?$"
        )
        private val REMEMBER_REGEX = Regex(
            "(?iu)^(?:oye\\s+)?(?:recuerda|acu[eé]rdate(?:\\s+de)?|(?:tengo\\s+que\\s+|hay\\s+que\\s+)?acordarme\\s+de|ten\\s+en\\s+cuenta|memoriza|guarda\\s+en\\s+(?:tu\\s+)?memoria|que\\s+sepas)\\s+que\\s+(.+)$"
        )
        // Afirmaciones sobre ti: «mi dentista es la Dra. López», «el wifi de la oficina es X»
        private val FACT_REGEX = Regex("(?iu)^(?:mi|mis|el|la)\\s+[\\p{L} ]{2,40}?\\s+(?:es|son|se\\s+llama|vive\\s+en|est[aá]\\s+en)\\s+.+$")
        private val FORGET_REGEX = Regex("(?iu)^(?:olvida|olv[ií]date\\s+de|borra\\s+de\\s+(?:tu\\s+)?memoria)\\s+que\\s+(.+)$")
        private val QUESTION_REGEX = Regex(
            // Sin tilde, «cuando/donde/quien» son conjunciones («cuando llegue a casa…»): solo cuentan con tilde o con «?»
            "(?iu)^\\s*¿|\\?\\s*$|^(?:cu[aá]l(?:es)?|c[oó]mo\\s+se\\s+llama|dónde|cuándo|quién|qu[eé]\\s+(?:es|era|dijo|d[ií]a)|sabes|recuerdas|te\\s+acuerdas)\\b"
        )
        private val RENAME_REGEX = Regex(
            "(?iu)^(?:renombra|(?:c[aá]mbia(?:le)?|pon(?:le)?)\\s+(?:el\\s+)?(?:nombre|t[ií]tulo)\\s+(?:de|a))\\s+(.+?)\\s+(?:a|por|como)\\s+(.+)$"
        )
        private val NOTE_REGEX = Regex(
            "(?iu)^(?:a[ñn]ade|pon|agrega|escribe)(?:le)?\\s+(?:una\\s+)?(?:nota|descripci[oó]n)\\s+(?:a|en)\\s+(.+?)\\s*(?:[:,]|que\\s+diga|diciendo)\\s*(.+)$"
        )
        private val CATEGORY_EDIT_REGEX = Regex(
            "(?iu)^(?:pasa|mueve|cambia|pon)\\s+(.+?)\\s+(?:a|en)\\s+(?:el\\s+[aá]rea\\s+(?:de\\s+)?)?(trabajo|personal|estudios?|salud)$"
        )
        private val EDIT_REGEX = Regex(
            "(?iu)^(?:ed[ií]ta(?:me)?|modifica(?:me)?|actualiza(?:me)?|abre(?:me)?(?=\\s+(?:la|mi)\\s+tarea))\\s+(?:la\\s+tarea|mi\\s+tarea)?\\s*(?:(?:que\\s+se\\s+llama|sobre|de|del|llamada)\\s+)?(.+)$"
        )
        private val EDIT_CHANGES_SPLIT = Regex("(?iu)\\s+(?:y|para)\\s+(?:p[oó]nle|pon|c[aá]mbiale|cambia|p[aá]sala|m[uú]evela|que\\s+sea|que)\\s+")
        // Imperativos de ruta; «ir al gimnasio» (infinitivo) sigue siendo una tarea
        private val NAVIGATE_REGEX = Regex(
            "(?iu)^\\s*¿?\\s*(?:ll[eé]vame|c[oó]mo\\s+(?:llego|voy|se\\s+llega)|navega|ruta|indicaciones|direcciones)\\s+" +
                "(?:a|al|hasta|hacia|para)\\s+(.+)$"
        )
        private val PRIORITIZE_REGEX = Regex("(?iu)^(?:prioriza|sube\\s+la\\s+prioridad\\s+(?:de|a))\\s+(.+?)\\.?$")
        /** «crea una tarea llamada X», «apunta una nueva tarea: X», «tarea que se llame X». */
        private val TASK_NAMED_REGEX = Regex(
            "(?iu)^(?:(?:cr[eé]a(?:me|r)?|a[ñn][aá]de(?:me)?|ap[uú]nta(?:me)?|pon(?:me)?|haz(?:me)?|agrega)\\s+)?(?:una\\s+|la\\s+)?(?:nueva\\s+)?tarea\\s*" +
                "(?:llamada|que\\s+se\\s+llam[ae]|titulada|con\\s+el\\s+nombre(?:\\s+de)?|que\\s+diga|de|para|:)?\\s*"
        )
        private val CREATE_PREFIX_REGEX = Regex(
            "(?iu)^(?:recu[eé]rdame|recordarme|recordar|ap[uú]ntame|apunta|agrega(?:r)?|a[ñn]ade|a[ñn]adir|crea(?:r)?|" +
                "anota(?:r)?|nueva\\s+tarea|tengo\\s+que|debo|hay\\s+que|necesito)\\s*(?:(?:a|que|de|para)\\s+)?"
        )
    }
}
