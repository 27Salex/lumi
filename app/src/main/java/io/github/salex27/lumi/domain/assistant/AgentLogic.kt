package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.Task
import java.text.Normalizer

/** Texto sin acentos ni signos, en minúsculas. */
internal fun plain(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace(Regex("[^a-z0-9ñ ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Palabras con significado: sin artículos ni muletillas, y con un «stemming» mínimo (plurales). */
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

/**
 * ¿A qué tarea se refiere el usuario? (puro, testeado). No exige el título exacto: «mi tarea sobre llevar a Víctor al
 * trabajo» encuentra «Llevar a Víctor». Si hay dudas, devuelve candidatas para preguntar «¿Te refieres a…?».
 */
object TaskMatcher {

    data class Match(val task: Task, val score: Double)

    sealed interface Decision {
        data class Sure(val task: Task) : Decision
        data class Ask(val candidates: List<Task>) : Decision
        data object NotFound : Decision
    }

    private val STOP = setOf(
        "la", "el", "los", "las", "de", "del", "a", "al", "mi", "mis", "tu", "tarea", "tareas", "que", "se", "llama",
        "sobre", "un", "una", "y", "en", "para", "con", "por", "lo", "le", "me", "esa", "ese", "esta", "este"
    )

    fun rank(query: String, tasks: List<Task>): List<Match> {
        val q = keywords(query, STOP).distinct()
        if (q.isEmpty()) return emptyList()
        return tasks.mapNotNull { task ->
            val t = keywords(task.title, STOP).distinct()
            if (t.isEmpty()) return@mapNotNull null
            val common = t.count { tw -> q.any { similar(it, tw) } }
            if (common == 0) return@mapNotNull null
            val recall = common.toDouble() / t.size          // cuánto del título has mencionado
            val precision = q.count { qw -> t.any { similar(it, qw) } }.toDouble() / q.size
            val score = 0.6 * recall + 0.4 * precision + if (task.isActive) 0.05 else 0.0
            Match(task, score)
        }.sortedByDescending { it.score }
    }

    /** Segura si destaca claramente; si no, pregunta con las 3 mejores; si nada se parece, no encontrada. */
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
 * «Cambia el nombre de llevar a Víctor a llevar a Víctor al trabajo»: prueba cada « a / por / como » como corte y se
 * queda con el que deja a la izquierda el texto que mejor encaja con una tarea real.
 */
object RenameSplitter {
    fun split(spec: String, tasks: List<Task>): Pair<String, String>? {
        val seps = Regex("(?iu)\\s+(?:a|por|como)\\s+").findAll(spec).toList()
        if (seps.isEmpty()) return null
        return seps.map { m -> spec.substring(0, m.range.first).trim() to spec.substring(m.range.last + 1).trim() }
            .filter { (l, r) -> l.isNotBlank() && r.isNotBlank() }
            .maxWithOrNull(compareBy({ (l, _) -> TaskMatcher.rank(l, tasks).firstOrNull()?.score ?: 0.0 }, { (l, _) -> -l.length }))
    }
}

/**
 * Memoria personal BAJO DEMANDA (puro, testeado): nunca se carga entera en el modelo; para cada petición se eligen
 * solo los 2-3 recuerdos que comparten palabras con ella (tipo BM25 simplificado: palabras raras pesan más).
 */
object MemoryRetriever {

    data class Memory(val id: Long, val text: String)

    private val STOP = setOf(
        "la", "el", "los", "las", "de", "del", "a", "al", "mi", "mis", "que", "es", "son", "un", "una", "y", "en", "se",
        "cual", "como", "donde", "cuando", "quien", "llama", "esta", "tengo", "lo", "le", "me", "por", "para", "con",
        "recuerda", "recuerdas", "dime", "sabes", "acuerdate", "oye", "lumi"
    )

    fun relevant(query: String, memories: List<Memory>, limit: Int = 3): List<Memory> {
        val q = keywords(query, STOP).distinct()
        if (q.isEmpty() || memories.isEmpty()) return emptyList()
        val docs = memories.associateWith { keywords(it.text, STOP).distinct() }
        // Frecuencia de cada palabra en la memoria → peso inverso (idf)
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
