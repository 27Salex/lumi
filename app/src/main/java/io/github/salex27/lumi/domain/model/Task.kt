package io.github.salex27.lumi.domain.model

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import kotlinx.serialization.Serializable

enum class TaskCategory(private val es: String, private val en: String, val emoji: String) {
    PERSONAL("Personal", "Personal", "👤"),
    WORK("Trabajo", "Work", "💼"),
    STUDY("Estudios", "Study", "📚"),
    HEALTH("Salud", "Health", "🏃"),
    OTHER("Otro", "Other", "📌");

    /** Display name in [lang] (screens pass the app language; replies use the language of the conversation). */
    fun label(lang: Lang): String = if (lang == Lang.EN) en else es
    val label: String get() = label(ReplyLanguage.current)

    companion object {
        fun fromString(value: String?): TaskCategory? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Priority. NONE by default so older tasks are unchanged. A higher [rank] means more important. */
enum class TaskPriority(private val es: String, private val en: String, val rank: Int) {
    NONE("Ninguna", "None", 0), LOW("Baja", "Low", 1), MEDIUM("Media", "Medium", 2), HIGH("Alta", "High", 3);

    fun label(lang: Lang): String = if (lang == Lang.EN) en else es
    val label: String get() = label(ReplyLanguage.current)

    companion object {
        fun fromString(value: String?): TaskPriority? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * A task's place. [place] is the key of a saved place ("casa") or, when it carries coordinates, the name of a
 * one-off address found in the editor. [notify] = remind on arrival/departure ([onArrive]); when false the place is
 * just a reference ("Directions", grouping).
 * Serialized as "casa|ARRIVE" or "Mercadona Gran Vía|ARRIVE|40.42|-3.70|Gran Vía 12, Madrid".
 * Saved-place keys are Spanish words ("casa", "trabajo") because that is how the first users named them;
 * [displayName] translates them for English replies.
 */
data class PlaceTrigger(
    val place: String,
    val onArrive: Boolean = true,
    val notify: Boolean = true,
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null
) {
    /** A one-off address (not a saved place): carries its own coordinates. */
    val isAdHoc: Boolean get() = lat != null && lng != null

    fun serialize(): String {
        val mode = when { !notify -> "NONE"; onArrive -> "ARRIVE"; else -> "LEAVE" }
        val base = "${place.replace('|', '/')}|$mode"
        return if (isAdHoc) "$base|$lat|$lng|${address.orEmpty().replace('|', '/')}" else base
    }

    /** "al llegar a casa" / "when you get home", "al salir del trabajo" / "when you leave work". */
    fun describe(lang: Lang = ReplyLanguage.current): String = if (lang == Lang.EN) {
        val name = displayName(place, Lang.EN)
        if (onArrive) (if (place == "casa") "when you get home" else "when you arrive at $name") else "when you leave $name"
    } else {
        if (onArrive) "al llegar ${withArticle("a", place)}" else "al salir ${withArticle("de", place)}"
    }

    companion object {
        private val ARTICLES = mapOf(
            "casa" to "", "trabajo" to "el", "gimnasio" to "el", "universidad" to "la", "supermercado" to "el"
        )
        private val ENGLISH = mapOf(
            "casa" to "home", "trabajo" to "work", "gimnasio" to "the gym", "universidad" to "university",
            "supermercado" to "the supermarket", "oficina" to "the office", "colegio" to "school"
        )

        /** Name of a place key in [lang]: "trabajo" → "work". Unknown names are returned as they are. */
        fun displayName(place: String, lang: Lang = ReplyLanguage.current): String =
            if (lang == Lang.EN) ENGLISH[place] ?: place else place

        /** Spanish contraction: "a" + "trabajo" → "al trabajo"; "de" + "casa" → "de casa"; own places without article. */
        fun withArticle(preposition: String, place: String): String = when (val article = ARTICLES[place] ?: "") {
            "el" -> when (preposition) {
                "a" -> "al $place"
                "de" -> "del $place"
                else -> "$preposition el $place"
            }
            "" -> "$preposition $place"
            else -> "$preposition $article $place"
        }

        fun parse(value: String?): PlaceTrigger? {
            val parts = value?.split('|') ?: return null
            if (parts.size < 2 || parts[0].isBlank()) return null
            val mode = parts[1]
            return PlaceTrigger(
                place = parts[0],
                onArrive = mode != "LEAVE",
                notify = mode != "NONE",
                lat = parts.getOrNull(2)?.toDoubleOrNull(),
                lng = parts.getOrNull(3)?.toDoubleOrNull(),
                address = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

data class Task(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.TODO,
    val category: TaskCategory = TaskCategory.PERSONAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Due date / appointment time in epoch millis, or null. */
    val dueAt: Long? = null,
    /** True when the user gave a specific time ("at 17:00"); false for a day only ("on Friday"). */
    val dueHasTime: Boolean = false,
    /** When it was completed (used by the stats). */
    val completedAt: Long? = null,
    /** Repetition; completing it creates the next occurrence. */
    val recurrence: Recurrence? = null,
    /** Linked calendar meeting (preparation, follow-up…). */
    val meeting: LinkedMeeting? = null,
    /** Whether Lumi picks reminders automatically (on top of custom ones). */
    val autoReminders: Boolean = true,
    val priority: TaskPriority = TaskPriority.NONE,
    val placeTrigger: PlaceTrigger? = null
) {
    val isActive: Boolean get() = status == TaskStatus.TODO || status == TaskStatus.IN_PROGRESS
}

/**
 * Structured intent returned by any [io.github.salex27.lumi.domain.ai.AssistantEngine].
 * It is the contract between the AI engine (LLM or rules) and the repository.
 */
@Serializable
data class TaskAICommand(
    val action: String, // one of the constants below
    val targetTitle: String? = null,
    val newStatus: String? = null,
    val category: String? = null, // "PERSONAL" | "WORK" | "STUDY" | "HEALTH" | "OTHER"
    val dueDate: String? = null,   // local ISO: "2026-10-03T17:00" or "2026-10-03"
    val hasTime: Boolean = false,
    /** Only when the user dictated explicit details. Never invented (see AssistantOrchestrator.reconcile). */
    val description: String? = null,
    /** Serialized repetition (see [Recurrence.serialize]): "DAILY", "WEEKLY:MO,TH"… */
    val recurrence: String? = null,
    /** Extra reminders the user asked for, in minutes before the due time ("remind me 2 hours before" → 120). */
    val remindBeforeMinutes: List<Int> = emptyList(),
    /** Relative RESCHEDULE: "postpone it a week" → 10080. When null, [dueDate] is used. */
    val postponeMinutes: Int? = null,
    /** Hint of a meeting to link: "the sprint meeting", "the call with Ana". */
    val meeting: String? = null,
    /** "NONE" | "LOW" | "MEDIUM" | "HIGH". Also used by SET_PRIORITY. */
    val priority: String? = null,
    /** Place reminder: the place name ("casa", "trabajo") and whether it fires on arrival or departure. */
    val place: String? = null,
    val placeOnArrive: Boolean = true,
    /** EDIT: new title. Other changes use dueDate, category, priority, description, place. */
    val newTitle: String? = null,
    /** Rename: the literal "X to Y" (ambiguous when X or Y contain the separator); the repository picks the split with RenameSplitter. */
    val renameSpec: String? = null,
    /** Task already picked by the user ("Did you mean…?" → yes): skips the search by title. */
    val targetId: Long? = null,
    /** The command is about the task we were just talking about ("move it to 5pm"). */
    val refersToLast: Boolean = false,
    /** DEVICE: serialized action (see DeviceCommand.serialize), e.g. "ALARM|7|0|". */
    val device: String? = null,
    /** CREATE_MANY (brain dump): one CREATE entry per task. */
    val items: List<TaskAICommand> = emptyList()
) {
    companion object {
        const val CREATE = "CREATE"
        const val CREATE_MANY = "CREATE_MANY"
        const val UPDATE_STATUS = "UPDATE_STATUS"
        const val RESCHEDULE = "RESCHEDULE"
        const val SUMMARIZE = "SUMMARIZE"
        const val PLAN_DAY = "PLAN_DAY"
        const val SET_PRIORITY = "SET_PRIORITY"
        /** "Take me home", "how do I get to the meeting?": [targetTitle] = destination. */
        const val NAVIGATE = "NAVIGATE"
        /** Edit an existing task; with no concrete change it opens the editor. */
        const val EDIT = "EDIT"
        /** Personal memory: save [targetTitle], answer a question, or forget. */
        const val REMEMBER = "REMEMBER"
        const val RECALL = "RECALL"
        const val FORGET = "FORGET"
        /** Action on the phone: open an app, alarm, timer, call, message, music, search… */
        const val DEVICE = "DEVICE"
        /** Weather: [targetTitle] = the question as said (the repository parses it again). */
        const val WEATHER = "WEATHER"
        /** General question or request (not about your tasks): answered by the LLM, or searched on the web. */
        const val ASK = "ASK"
        /** Summary of a day ("good morning", "what do I have tomorrow?"): [dueDate] = the day. */
        const val DAY_BRIEF = "DAY_BRIEF"
        /** Alarm based on tomorrow's schedule. [newStatus] = "ASK" when it was a question (confirm before setting it). */
        const val SMART_ALARM = "SMART_ALARM"
        /** Read unread messages (notifications). [targetTitle] = from whom ("" = everyone). */
        const val NOTIFICATIONS = "NOTIFICATIONS"
    }
}

/**
 * Result of a command. [reply] is the conversational answer shown to the user and [engine] the name of the AI engine
 * that produced it (shown as a badge in the UI).
 */
sealed interface AIProcessingResult {
    val reply: String
    val engine: String

    data class Created(val task: Task, override val reply: String, override val engine: String) : AIProcessingResult
    data class CreatedMany(val tasks: List<Task>, override val reply: String, override val engine: String) : AIProcessingResult
    data class Updated(val task: Task, override val reply: String, override val engine: String) : AIProcessingResult
    data class Summary(override val reply: String, override val engine: String) : AIProcessingResult
    data class Plan(val suggestions: List<Task>, override val reply: String, override val engine: String) : AIProcessingResult
    data class Error(override val reply: String, override val engine: String = "") : AIProcessingResult
    /** "Did you mean…?": the UI shows [options]; picking one runs [command] with targetId. */
    data class Choose(val options: List<Task>, val command: TaskAICommand, override val reply: String, override val engine: String) : AIProcessingResult
    /** Something is missing to finish the command ("What should I tell Víctor?"): the next sentence fills [slot]. */
    data class AskFollowUp(val command: TaskAICommand, val slot: String, override val reply: String, override val engine: String) : AIProcessingResult
    /** Open this task's editor ("edit the dentist one"). */
    data class OpenTask(val task: Task, override val reply: String, override val engine: String) : AIProcessingResult
    /** Answer from personal memory (or confirmation of remember/forget). */
    data class Memory(override val reply: String, override val engine: String) : AIProcessingResult
    /** Action on the phone (run by the UI). */
    data class Device(val command: io.github.salex27.lumi.domain.assistant.DeviceCommand, override val reply: String, override val engine: String) : AIProcessingResult
    /** Information answer (weather, general question, day summary, messages). */
    data class Answer(override val reply: String, override val engine: String) : AIProcessingResult

    /** An answer built from web results (#7): [sources] are shown as links under it (never read aloud). */
    data class WebAnswer(
        override val reply: String, val sources: List<io.github.salex27.lumi.domain.search.WebHit>, override val engine: String
    ) : AIProcessingResult

    /** The request is for an AI agent ([agent], e.g. "claude"): the assistant hands [request] over through Lumi Hub. */
    data class Agent(val agent: String, val request: String, override val reply: String, override val engine: String) : AIProcessingResult

    /** Lumi isn't sure what kind of request [text] is: it offers [options] (e.g. task or agent) instead of guessing. */
    data class Clarify(
        val text: String, val options: List<io.github.salex27.lumi.domain.assistant.IntentRoute>,
        override val reply: String, override val engine: String
    ) : AIProcessingResult
    /** Messages read out; when they come from one person, [replyTo] enables "reply that…". */
    data class Messages(val replyTo: io.github.salex27.lumi.domain.assistant.IncomingMessage?, override val reply: String, override val engine: String) : AIProcessingResult
    /** Routine or several commands: phone actions in order (the ones that stay in Lumi first) and, at the end, a route. */
    data class Routine(
        val devices: List<io.github.salex27.lumi.domain.assistant.DeviceCommand>,
        val navigate: NavDestination?,
        override val reply: String,
        override val engine: String,
        val tasks: List<Task> = emptyList()
    ) : AIProcessingResult
    /** Open the route in the chosen maps app (the UI fires the intent). */
    data class Navigate(val destination: NavDestination, override val reply: String, override val engine: String) : AIProcessingResult
}
