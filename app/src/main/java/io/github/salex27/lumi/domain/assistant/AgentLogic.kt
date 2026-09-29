package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.Task
import java.text.Normalizer

/** Lower-case text without accents or punctuation. */
internal fun plain(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace(Regex("[^a-z0-9ñ ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Meaningful words: no articles or filler, with minimal stemming (plurals). */
internal fun keywords(text: String, stop: Set<String>): List<String> =
    plain(text).split(" ").filter { it.length >= 2 && it !in stop }.map { w ->
        when {
            w.length > 5 && w.endsWith("es") -> w.dropLast(2)
            w.length > 4 && w.endsWith("s") -> w.dropLast(1)
            else -> w
        }
    }

private fun similar(a: String, b: String): Boolean = a == b ||
    (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) ||
    (a.length >= 5 && b.length >= 5 && editDistance(a, b) <= 1)

private fun editDistance(a: String, b: String): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = d[0]; d[0] = i
        for (j in 1..b.length) {
            val tmp = d[j]
            d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = tmp
        }
    }
    return d[b.length]
}

/** Filler words ignored when matching (Spanish and English). */
private val COMMON_STOP = setOf(
    // Spanish
    "la", "el", "los", "las", "de", "del", "a", "al", "mi", "mis", "tu", "que", "se", "un", "una", "y", "en", "para",
    "con", "por", "lo", "le", "me", "esa", "ese", "esta", "este",
    // English
    "the", "my", "to", "of", "an", "and", "in", "on", "for", "with", "it", "that", "this", "your", "at", "is"
)

/**
 * Which task does the user mean? (pure, tested). It does not need the exact title: "my task about taking Víctor to
 * work" finds "Take Víctor". When unsure it returns candidates so Lumi can ask "Did you mean…?".
 */
object TaskMatcher {

    data class Match(val task: Task, val score: Double)

    sealed interface Decision {
        data class Sure(val task: Task) : Decision
        data class Ask(val candidates: List<Task>) : Decision
        data object NotFound : Decision
    }

    private val STOP = COMMON_STOP + setOf("tarea", "tareas", "llama", "sobre", "task", "tasks", "called", "about", "named")

    fun rank(query: String, tasks: List<Task>): List<Match> {
        val q = keywords(query, STOP).distinct()
        if (q.isEmpty()) return emptyList()
        return tasks.mapNotNull { task ->
            val t = keywords(task.title, STOP).distinct()
            if (t.isEmpty()) return@mapNotNull null
            val common = t.count { tw -> q.any { similar(it, tw) } }
            if (common == 0) return@mapNotNull null
            val recall = common.toDouble() / t.size          // how much of the title was mentioned
            val precision = q.count { qw -> t.any { similar(it, qw) } }.toDouble() / q.size
            val score = 0.6 * recall + 0.4 * precision + if (task.isActive) 0.05 else 0.0
            Match(task, score)
        }.sortedByDescending { it.score }
    }

    /** Sure when one clearly stands out; otherwise ask with the top 3; when nothing is similar, not found. */
    fun decide(query: String, tasks: List<Task>): Decision {
        val ranked = rank(query, tasks)
        val top = ranked.firstOrNull() ?: return Decision.NotFound
        val second = ranked.getOrNull(1)
        val clear = second == null || top.score - second.score >= 0.15
        return when {
            top.score >= 0.7 && clear -> Decision.Sure(top.task)
            top.score >= 0.3 -> Decision.Ask(ranked.take(3).map { it.task })
            else -> Decision.NotFound
        }
    }
}

/**
 * "Rename take Víctor to take Víctor to work": tries every " a / por / como / to / as " as the split point and keeps the
 * one whose left side best matches a real task.
 */
object RenameSplitter {
    fun split(spec: String, tasks: List<Task>): Pair<String, String>? {
        val seps = Regex("(?iu)\\s+(?:a|por|como|to|as)\\s+").findAll(spec).toList()
        if (seps.isEmpty()) return null
        return seps.map { m -> spec.substring(0, m.range.first).trim() to spec.substring(m.range.last + 1).trim() }
            .filter { (l, r) -> l.isNotBlank() && r.isNotBlank() }
            .maxWithOrNull(compareBy({ (l, _) -> TaskMatcher.rank(l, tasks).firstOrNull()?.score ?: 0.0 }, { (l, _) -> -l.length }))
    }
}

/**
 * ON-DEMAND personal memory (pure, tested): never loaded into the model in full; for each request only the 2-3 memories
 * sharing words with it are picked (a simplified BM25: rare words weigh more).
 */
object MemoryRetriever {

    data class Memory(val id: Long, val text: String)

    private val STOP = COMMON_STOP + setOf(
        "es", "son", "cual", "como", "donde", "cuando", "quien", "llama", "tengo", "recuerda", "recuerdas", "dime", "sabes",
        "acuerdate", "oye", "lumi", "what", "whats", "where", "when", "who", "which", "how", "do", "does", "remember", "tell",
        "know", "hey", "are", "was", "have"
    )

    fun relevant(query: String, memories: List<Memory>, limit: Int = 3): List<Memory> {
        val q = keywords(query, STOP).distinct()
        if (q.isEmpty() || memories.isEmpty()) return emptyList()
        val docs = memories.associateWith { keywords(it.text, STOP).distinct() }
        // How many memories contain each word → inverse weight (idf)
        val df = HashMap<String, Int>()
        docs.values.forEach { words -> words.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = memories.size.toDouble()
        return docs.mapNotNull { (m, words) ->
            val score = q.sumOf { qw ->
                val hit = words.firstOrNull { similar(it, qw) } ?: return@sumOf 0.0
                kotlin.math.ln(1 + n / (df[hit] ?: 1))
            }
            if (score > 0) m to score else null
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
