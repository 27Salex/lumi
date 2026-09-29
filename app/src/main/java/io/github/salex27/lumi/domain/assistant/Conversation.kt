package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.Task
import java.text.Normalizer

/** Idioma de una frase y de la respuesta. Lumi es español primero; el inglés se añadió para la versión pública 1.0. */
enum class Lang { ES, EN }

/**
 * Idioma de la respuesta en curso. Lo fija el repositorio al recibir cada frase y lo leen las respuestas por reglas y
 * los prompts. Es global a propósito: Lumi atiende a una sola persona y una frase cada vez.
 */
object ReplyLanguage {
    @Volatile var current: Lang = Lang.ES

    /** Texto en el idioma actual. */
    fun t(es: String, en: String) = if (current == Lang.EN) en else es
}

/**
 * ¿La frase está en inglés o en español? (puro, testeado). Cuenta palabras típicas de cada idioma; en caso de duda
 * (frases de 1-2 palabras sin pistas) devuelve [fallback].
 */
object LanguageDetector {

    private val EN = setOf(
        "the", "to", "my", "is", "are", "what", "whats", "how", "when", "where", "who", "why", "remind", "tomorrow", "today",
        "tonight", "please", "and", "i", "im", "ive", "you", "it", "for", "with", "at", "on", "set", "call", "text", "play",
        "open", "add", "move", "mark", "delete", "cancel", "show", "read", "take", "wake", "turn", "need", "have", "should",
        "can", "do", "does", "did", "will", "weather", "rain", "alarm", "timer", "meeting", "task", "tasks", "done", "finished",
        "this", "next", "every", "morning", "afternoon", "evening", "am", "pm", "hey", "tell", "send", "message", "give",
        "about", "of", "in", "an", "be", "get", "me", "go", "going", "want", "there", "that", "your", "from", "hi", "hello"
    )
    private val ES = setOf(
        "el", "la", "de", "que", "y", "en", "los", "las", "un", "una", "es", "por", "para", "con", "mi", "mis", "me", "te", "se",
        "lo", "le", "al", "del", "mañana", "manana", "hoy", "qué", "que", "cómo", "como", "cuándo", "dónde", "quién", "recuérdame",
        "recuerdame", "tengo", "hay", "pon", "ponme", "llama", "abre", "apunta", "mueve", "marca", "borra", "dime", "lee", "llévame",
        "llevame", "despiértame", "activa", "oye", "tiempo", "tarea", "tareas", "hecho", "hecha", "esta", "este", "tarde", "noche",
        "semana", "cuando", "llegue", "puedo", "puedes", "quiero", "necesito", "hola", "gracias", "sí", "si", "no", "ya", "todo"
    )

    fun detect(text: String, fallback: Lang = Lang.ES): Lang {
        val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
        // Letras propias del español: casi seguro español
        if (Regex("[ñ¿¡áéíóú]").containsMatchIn(t)) return Lang.ES
        val words = t.replace("'", "").split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return fallback
        val en = words.count { it in EN && it !in ES }
        val es = words.count { it in ES && it !in EN }
        return when {
            en > es -> Lang.EN
            es > en -> Lang.ES
            else -> fallback
        }
    }
}

/**
 * Memoria corta de la conversación (v1.0): las últimas frases y la última tarea de la que se habló, para entender
 * «muévela a las 5», «ponle prioridad alta», «¿y mañana?». Caduca a los [ttlMillis] (una conversación nueva empieza
 * de cero). Solo vive en memoria.
 */
class ConversationContext(private val ttlMillis: Long = 10 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {

    data class Turn(val user: String, val reply: String, val action: String?, val at: Long)

    private val turns = ArrayDeque<Turn>()
    private var lastTaskId: Long? = null
    private var lastTaskTitle: String? = null
    private var lastTaskAt = 0L

    @Synchronized
    fun record(user: String, reply: String, action: String?, task: Task?) {
        val now = clock()
        turns.addLast(Turn(user, reply, action, now))
        while (turns.size > MAX_TURNS) turns.removeFirst()
        if (task != null && task.id > 0) { lastTaskId = task.id; lastTaskTitle = task.title; lastTaskAt = now }
    }

    /** Id de la última tarea mencionada (creada, cambiada o elegida), si la conversación sigue viva. */
    @Synchronized
    fun lastTaskId(): Long? = lastTaskId?.takeIf { clock() - lastTaskAt < ttlMillis }

    @Synchronized
    fun lastTaskTitle(): String? = lastTaskTitle?.takeIf { clock() - lastTaskAt < ttlMillis }

    /** Turnos recientes (los que no han caducado), del más antiguo al más nuevo. */
    @Synchronized
    fun recent(): List<Turn> = turns.filter { clock() - it.at < ttlMillis }

    /** Acción de la última frase («WEATHER», «ASK»…), para seguir el hilo («¿y mañana?»). */
    fun lastAction(): String? = recent().lastOrNull()?.action

    /** Resumen para el LLM: «Usuario: … / Lumi: …» + la última tarea. Vacío si no hay conversación reciente. */
    fun promptNote(): String {
        val r = recent()
        if (r.isEmpty()) return ""
        return buildString {
            append("[CONVERSACIÓN RECIENTE: ")
            append(r.takeLast(2).joinToString(" | ") { "Usuario: «${it.user.take(120)}» → Lumi: «${it.reply.take(120)}»" })
            lastTaskTitle()?.let { append(" | ÚLTIMA TAREA: «$it»") }
            append("]")
        }
    }

    @Synchronized
    fun clear() { turns.clear(); lastTaskId = null; lastTaskTitle = null }

    companion object {
        const val MAX_TURNS = 4
    }
}
