package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.TaskCategory
import java.text.Normalizer

/**
 * Deterministic keyword category classifier (ES/EN).
 * Used as a fallback when the LLM returns no category or none is available.
 * Pure Kotlin (no Android dependencies) so it can be tested on the JVM.
 */
object CategoryHeuristics {

    // Order matters: the first category with a match wins.
    private val keywords: List<Pair<TaskCategory, List<String>>> = listOf(
        TaskCategory.STUDY to listOf(
            "estudiar", "estudio", "examen", "parcial", "clase", "universidad",
            "facultad", "colegio", "deberes", "apuntes", "tfg", "tfm", "practica",
            "asignatura", "repasar", "curso", "leer capitulo", "study", "exam", "homework", "lecture"
        ),
        TaskCategory.HEALTH to listOf(
            "gimnasio", "gym", "correr", "entrenar", "entreno", "medico", "doctor", "dentista",
            "cita medica", "pastilla", "medicina", "yoga", "cardio", "dieta", "salud", "fisio",
            "caminar", "workout"
        ),
        TaskCategory.WORK to listOf(
            "trabajo", "reunion", "meeting", "cliente", "jefe", "oficina", "informe", "presentacion",
            "sprint", "deploy", "email", "correo", "proyecto", "factura", "entrega",
            "llamada con", "work", "standup", "code review", "jira"
        ),
        TaskCategory.PERSONAL to listOf(
            "comprar", "supermercado", "mercado", "familia", "mama", "papa", "cumpleanos", "regalo",
            "casa", "limpiar", "cocinar", "lavar", "banco", "pagar", "amigo", "cena", "personal"
        )
    )

    /** Area name said as it is ("trabajo", "work", "estudios"…) → category. */
    fun fromWord(word: String): TaskCategory? = when (normalize(word.trim())) {
        "trabajo", "work" -> TaskCategory.WORK
        "personal" -> TaskCategory.PERSONAL
        "estudio", "estudios", "study", "studies", "school" -> TaskCategory.STUDY
        "salud", "health" -> TaskCategory.HEALTH
        "otro", "otros", "other" -> TaskCategory.OTHER
        else -> null
    }

    /** The inferred category, or `null` without clear signals. */
    fun infer(text: String): TaskCategory? {
        val normalized = normalize(text)
        if (normalized.isBlank()) return null
        val padded = " $normalized "
        return keywords.firstOrNull { (_, words) ->
            words.any { w -> padded.contains(" ${w.trim()}") }
        }?.first
    }

    /** Lower case without accents, so "Reunión" and "reunion" match. */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()
}
