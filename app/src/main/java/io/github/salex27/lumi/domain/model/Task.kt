package io.github.salex27.lumi.domain.model

import kotlinx.serialization.Serializable

enum class TaskCategory(val label: String, val emoji: String) {
    PERSONAL("Personal", "👤"),
    WORK("Trabajo", "💼"),
    STUDY("Estudios", "📚"),
    HEALTH("Salud", "🏃"),
    OTHER("Otro", "📌");

    companion object {
        fun fromString(value: String?): TaskCategory? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Prioridad. NONE por defecto: las tareas antiguas no cambian. [rank] mayor = más importante. */
enum class TaskPriority(val label: String, val rank: Int) {
    NONE("Ninguna", 0), LOW("Baja", 1), MEDIUM("Media", 2), HIGH("Alta", 3);

    companion object {
        fun fromString(value: String?): TaskPriority? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Lugar de una tarea (v3.3). [place] es la clave de un lugar guardado («casa») o, si trae coordenadas, el nombre de una
 * dirección suelta buscada en el editor. [notify] = avisar al llegar/salir ([onArrive]); si es false el lugar solo
 * sirve de referencia («Cómo llegar», agrupar tareas).
 * Serializado: "casa|ARRIVE" (formato v3.1) o "Mercadona Gran Vía|ARRIVE|40.42|-3.70|Gran Vía 12, Madrid".
 */
data class PlaceTrigger(
    val place: String,
    val onArrive: Boolean = true,
    val notify: Boolean = true,
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null
) {
    /** Dirección suelta (no es un lugar guardado): lleva sus propias coordenadas. */
    val isAdHoc: Boolean get() = lat != null && lng != null

    fun serialize(): String {
        val mode = when { !notify -> "NONE"; onArrive -> "ARRIVE"; else -> "LEAVE" }
        val base = "${place.replace('|', '/')}|$mode"
        return if (isAdHoc) "$base|$lat|$lng|${address.orEmpty().replace('|', '/')}" else base
    }

    /** «al llegar a casa», «al salir del trabajo». */
    fun describe(): String = if (onArrive) "al llegar ${withArticle("a", place)}" else "al salir ${withArticle("de", place)}"

    companion object {
        private val ARTICLES = mapOf(
            "casa" to "", "trabajo" to "el", "gimnasio" to "el", "universidad" to "la", "supermercado" to "el"
        )

        /** "a" + "trabajo" → "al trabajo"; "de" + "casa" → "de casa"; lugares propios sin artículo. */
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
    /** Fecha límite / hora de la cita en epoch millis, o null si no tiene. */
    val dueAt: Long? = null,
    /** true si el usuario indicó hora concreta ("a las 17:00"); false si solo día ("el viernes"). */
    val dueHasTime: Boolean = false,
    /** Momento en que se completó (para las estadísticas). */
    val completedAt: Long? = null,
    /** Repetición; al completarla se crea la siguiente. */
    val recurrence: Recurrence? = null,
    /** Reunión del calendario vinculada (preparación, seguimiento…). */
    val meeting: LinkedMeeting? = null,
    /** Si Lumi decide los avisos automáticamente (además de los personalizados). */
    val autoReminders: Boolean = true,
    val priority: TaskPriority = TaskPriority.NONE,
    val placeTrigger: PlaceTrigger? = null
) {
    val isActive: Boolean get() = status == TaskStatus.TODO || status == TaskStatus.IN_PROGRESS
}

/**
 * Intención estructurada que devuelve cualquier [io.github.salex27.lumi.domain.ai.AssistantEngine].
 * Es el contrato entre el motor de IA (LLM o reglas) y el repositorio.
 */
@Serializable
data class TaskAICommand(
    val action: String, // "CREATE" | "UPDATE_STATUS" | "SUMMARIZE" | "PLAN_DAY"
    val targetTitle: String? = null,
    val newStatus: String? = null,
    val category: String? = null, // "PERSONAL" | "WORK" | "STUDY" | "HEALTH" | "OTHER"
    val dueDate: String? = null,   // ISO local "2026-10-03T17:00" o "2026-10-03"
    val hasTime: Boolean = false,
    /** Solo si el usuario dio detalles explícitos. Nunca se inventa (ver AssistantOrchestrator.reconcile). */
    val description: String? = null,
    /** Repetición serializada (ver [Recurrence.serialize]): "DAILY", "WEEKLY:MO,TH"… */
    val recurrence: String? = null,
    /** Avisos extra pedidos por el usuario: minutos antes del vencimiento ("avísame 2 horas antes" → 120). */
    val remindBeforeMinutes: List<Int> = emptyList(),
    /** RESCHEDULE relativo: "pospón una semana" → 10080. Si es null se usa [dueDate]. */
    val postponeMinutes: Int? = null,
    /** Pista de reunión a vincular: "la reunión del sprint", "la llamada con Ana". */
    val meeting: String? = null,
    /** "NONE" | "LOW" | "MEDIUM" | "HIGH". También en SET_PRIORITY. */
    val priority: String? = null,
    /** Aviso por lugar: nombre del lugar ("casa", "trabajo") y si es al llegar o al salir. */
    val place: String? = null,
    val placeOnArrive: Boolean = true,
    /** EDIT: nuevo título. Los demás cambios usan dueDate, category, priority, description, place. */
    val newTitle: String? = null,
    /** Renombrar: «X a Y» literal (ambiguo si X o Y contienen «a»); el repositorio elige el corte con RenameSplitter. */
    val renameSpec: String? = null,
    /** Tarea ya elegida por el usuario («¿Te refieres a…?» → sí): se salta la búsqueda por título. */
    val targetId: Long? = null,
    /** DEVICE: acción serializada (ver DeviceCommand.serialize), p.ej. "ALARM|7|0|". */
    val device: String? = null,
    /** CREATE_MANY (brain dump): una entrada CREATE por tarea. */
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
        /** «Llévame a casa», «¿cómo llego a la reunión?»: [targetTitle] = destino. */
        const val NAVIGATE = "NAVIGATE"
        /** Editar una tarea existente; sin cambios concretos → abre el editor. */
        const val EDIT = "EDIT"
        /** Memoria personal: guardar [targetTitle], responder una pregunta, u olvidar. */
        const val REMEMBER = "REMEMBER"
        const val RECALL = "RECALL"
        const val FORGET = "FORGET"
        /** Acción en el móvil: abrir app, alarma, temporizador, llamada, mensaje, música, búsqueda… */
        const val DEVICE = "DEVICE"
        /** El tiempo: [targetTitle] = la pregunta tal cual (el repositorio la vuelve a analizar). */
        const val WEATHER = "WEATHER"
        /** Pregunta o petición general (no es sobre tus tareas): la responde el LLM, o se busca en la web. */
        const val ASK = "ASK"
        /** Resumen de un día («buenos días», «¿qué tengo mañana?»): [dueDate] = el día. */
        const val DAY_BRIEF = "DAY_BRIEF"
        /** Alarma según tu agenda de mañana. [newStatus] = "ASK" si lo preguntó (se confirma antes de ponerla). */
        const val SMART_ALARM = "SMART_ALARM"
        /** Leer los mensajes sin leer (notificaciones). [targetTitle] = de quién ("" = todos). */
        const val NOTIFICATIONS = "NOTIFICATIONS"
    }
}

/**
 * Resultado de un comando. [reply] es la respuesta conversacional que se muestra al usuario
 * y [engine] el nombre del motor de IA que la generó (se muestra como "insignia" en la UI).
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
    /** «¿Te refieres a…?»: la UI muestra [options]; al elegir una se ejecuta [command] con targetId. */
    data class Choose(val options: List<Task>, val command: TaskAICommand, override val reply: String, override val engine: String) : AIProcessingResult
    /** Falta un dato para terminar la orden («¿Qué le digo a Víctor?»): la siguiente frase rellena [slot]. */
    data class AskFollowUp(val command: TaskAICommand, val slot: String, override val reply: String, override val engine: String) : AIProcessingResult
    /** Abrir el editor de esta tarea («edita lo del dentista»). */
    data class OpenTask(val task: Task, override val reply: String, override val engine: String) : AIProcessingResult
    /** Respuesta de la memoria personal (o confirmación de guardar/olvidar). */
    data class Memory(override val reply: String, override val engine: String) : AIProcessingResult
    /** Acción en el móvil (la ejecuta la UI). */
    data class Device(val command: io.github.salex27.lumi.domain.assistant.DeviceCommand, override val reply: String, override val engine: String) : AIProcessingResult
    /** Respuesta de información (tiempo, pregunta general, resumen del día, mensajes). */
    data class Answer(override val reply: String, override val engine: String) : AIProcessingResult
    /** Mensajes leídos; si son de una sola persona, [replyTo] permite «respóndele que…». */
    data class Messages(val replyTo: io.github.salex27.lumi.domain.assistant.IncomingMessage?, override val reply: String, override val engine: String) : AIProcessingResult
    /** Rutina: acciones del móvil en orden (primero las que no salen de Lumi) y, al final, una ruta. */
    data class Routine(
        val devices: List<io.github.salex27.lumi.domain.assistant.DeviceCommand>,
        val navigate: NavDestination?,
        override val reply: String,
        override val engine: String,
        val tasks: List<Task> = emptyList()
    ) : AIProcessingResult
    /** Abrir la ruta en la app de mapas elegida (la UI lanza el intent). */
    data class Navigate(val destination: NavDestination, override val reply: String, override val engine: String) : AIProcessingResult
}
