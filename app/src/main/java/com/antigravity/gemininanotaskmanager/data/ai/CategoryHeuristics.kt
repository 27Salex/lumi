package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import java.text.Normalizer

/**
 * Clasificador determinista de categorías por palabras clave (ES/EN).
 * Se usa como fallback cuando Gemini Nano no devuelve categoría o no está disponible.
 * Es Kotlin puro (sin dependencias Android) para poder testearlo en JVM.
 */
object CategoryHeuristics {

    // Orden importante: la primera categoría con coincidencia gana.
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

    /** Devuelve la categoría inferida, o `null` si no hay señales claras. */
    /** Nombre de un área dicho tal cual («trabajo», «estudios»…) → categoría. */
    fun fromWord(word: String): TaskCategory? = when (normalize(word.trim())) {
        "trabajo" -> TaskCategory.WORK
        "personal" -> TaskCategory.PERSONAL
        "estudio", "estudios" -> TaskCategory.STUDY
        "salud" -> TaskCategory.HEALTH
        "otro", "otros" -> TaskCategory.OTHER
        else -> null
    }

    fun infer(text: String): TaskCategory? {
        val normalized = normalize(text)
        if (normalized.isBlank()) return null
        val padded = " $normalized "
        return keywords.firstOrNull { (_, words) ->
            words.any { w -> padded.contains(" ${w.trim()}") }
        }?.first
    }

    /** Minúsculas y sin tildes, para que "Reunión" y "reunion" coincidan. */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()
}
