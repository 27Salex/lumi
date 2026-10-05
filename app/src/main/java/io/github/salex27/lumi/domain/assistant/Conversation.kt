package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.chat.ChatContextBuilder
import io.github.salex27.lumi.domain.chat.ChatTurn
import java.text.Normalizer

/** Language of a sentence and of its reply. Lumi started Spanish-only; English was added for the public 1.0. */
enum class Lang {
    ES, EN;

    companion object {
        /** From a locale language code ("es", "en", "fr"…): anything that isn't Spanish gets English. */
        fun of(languageCode: String): Lang = if (languageCode.lowercase().startsWith("es")) ES else EN
    }
}

/**
 * Languages in use. [current] = the reply in progress: the repository sets it from each sentence and rule replies and
 * prompts read it. [app] = the app's display language (notifications, reminder labels, screens).
 * Global on purpose: Lumi serves one person, one sentence at a time.
 */
object ReplyLanguage {
    @Volatile var current: Lang = Lang.ES
    @Volatile var app: Lang = Lang.ES

    /** Text in the language of the reply in progress. */
    fun <T> t(es: T, en: T): T = if (current == Lang.EN) en else es

    /** Text in the app's display language (settings messages, notifications, seed data). */
    fun <T> ui(es: T, en: T): T = if (app == Lang.EN) en else es
}

/**
 * Is the sentence English or Spanish? (pure, tested). Counts words typical of each language; when unsure
 * (1-2 word sentences without clues) returns [fallback].
 */
object LanguageDetector {

    private val EN = setOf(
        "the", "to", "my", "is", "are", "what", "whats", "how", "when", "where", "who", "why", "remind", "tomorrow", "today",
        "tonight", "please", "and", "i", "im", "ive", "you", "it", "for", "with", "at", "on", "set", "call", "text", "play",
        "open", "add", "move", "mark", "delete", "cancel", "show", "read", "take", "wake", "turn", "need", "have", "should",
        "can", "do", "does", "did", "will", "weather", "rain", "alarm", "timer", "meeting", "task", "tasks", "done", "finished",
        "this", "next", "every", "morning", "afternoon", "evening", "am", "pm", "hey", "tell", "send", "message", "give",
        "about", "of", "in", "an", "be", "get", "me", "go", "going", "want", "there", "that", "your", "from", "hi", "hello",
        "make", "wait", "actually", "urgent", "important", "later", "sorry", "yes", "thanks", "buy", "remember", "list", "week"
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
        // Letters only Spanish uses: almost certainly Spanish
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
 * Conversation memory of the active chat session: its turns (+ the rolling [summary] of older ones) for the LLM, and
 * the last task / last action for follow-ups like "move it to 5pm", "make it high priority", "and tomorrow?". The
 * follow-up memory expires after [ttlMillis]; the turns belong to the session (persisted by ChatStore) and are trimmed
 * to a token budget by [ChatContextBuilder] before reaching the LLM.
 */
class ConversationContext(private val ttlMillis: Long = 10 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {

    data class Turn(val user: String, val reply: String, val action: String?, val at: Long)

    private val turns = ArrayDeque<Turn>()
    private var lastTaskId: Long? = null
    private var lastTaskTitle: String? = null
    private var lastTaskAt = 0L

    /** Rolling summary of the session's older turns (already folded out of the turn list). */
    @Volatile var summary: String = ""
        private set

    @Synchronized
    fun record(user: String, reply: String, action: String?, task: Task?) {
        val now = clock()
        turns.addLast(Turn(user, reply, action, now))
        while (turns.size > MAX_TURNS) turns.removeFirst()
        if (task != null && task.id > 0) { lastTaskId = task.id; lastTaskTitle = task.title; lastTaskAt = now }
    }

    /** Loads a resumed session: its summary, its newest turns and the last task it mentioned (still subject to the TTL). */
    @Synchronized
    fun restore(sessionTurns: List<Turn>, sessionSummary: String, lastTask: Task?, lastTaskAt: Long) {
        clear()
        summary = sessionSummary
        sessionTurns.takeLast(MAX_TURNS).forEach { turns.addLast(it) }
        if (lastTask != null && lastTask.id > 0) { lastTaskId = lastTask.id; lastTaskTitle = lastTask.title; this.lastTaskAt = lastTaskAt }
    }

    /** Id of the last task mentioned (created, changed or picked), while the conversation is alive. */
    @Synchronized
    fun lastTaskId(): Long? = lastTaskId?.takeIf { clock() - lastTaskAt < ttlMillis }

    @Synchronized
    fun lastTaskTitle(): String? = lastTaskTitle?.takeIf { clock() - lastTaskAt < ttlMillis }

    /** Recent turns (not expired), oldest first: for follow-ups ("and tomorrow?", the previous question). */
    @Synchronized
    fun recent(): List<Turn> = turns.filter { clock() - it.at < ttlMillis }

    /** Every turn of the session still in memory (not folded into the summary), oldest first. */
    @Synchronized
    fun sessionTurns(): List<Turn> = turns.toList()

    /** Action of the last sentence ("WEATHER", "ASK"…), to follow the thread ("and tomorrow?"). */
    fun lastAction(): String? = recent().lastOrNull()?.action

    /**
     * Note for the LLM: summary + the newest session turns that fit the token budget + the last task (only while it is
     * fresh, so "move it" never lands on a task from hours ago). Empty when there is no conversation.
     */
    fun promptNote(budgetTokens: Int = ChatContextBuilder.DEFAULT_BUDGET_TOKENS): String =
        ChatContextBuilder.note(sessionTurns().map { it.toChatTurn() }, summary, budgetTokens, lastTaskTitle())

    /** Turns that no longer fit the budget (oldest first): what [fold] should summarize. */
    fun overflow(budgetTokens: Int = ChatContextBuilder.DEFAULT_BUDGET_TOKENS): List<Turn> {
        val all = sessionTurns()
        val plan = ChatContextBuilder.plan(all.map { it.toChatTurn() }, summary, budgetTokens)
        return all.take(plan.overflow.size)
    }

    /**
     * Replaces the turns up to [until] (their time, inclusive) with [newSummary]: they were summarized. By time and not
     * by count, so turns recorded while the summary was being written are never dropped by mistake.
     */
    @Synchronized
    fun fold(until: Long, newSummary: String) {
        while (turns.isNotEmpty() && turns.first().at <= until) turns.removeFirst()
        summary = newSummary
    }

    private fun Turn.toChatTurn() = ChatTurn(user, reply, at)

    @Synchronized
    fun clear() { turns.clear(); lastTaskId = null; lastTaskTitle = null; lastTaskAt = 0L; summary = "" }

    companion object {
        /** Turns kept in memory; the token budget decides how many actually reach the LLM. */
        const val MAX_TURNS = 16
    }
}

