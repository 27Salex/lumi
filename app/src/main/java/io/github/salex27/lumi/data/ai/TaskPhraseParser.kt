package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.TaskPriority
import java.time.DayOfWeek

/**
 * Spanish phrase extractors for creating tasks (pure, tested). Each returns the text without the recognized
 * expression so the title stays clean. English: [EnglishCommands].
 */
object TaskPhraseParser {

    private const val I = "(?iu)"

    private val WEEKDAYS = linkedMapOf(
        "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
        "miércoles" to DayOfWeek.WEDNESDAY, "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY, "sábado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY
    )
    private val DAY_ALT = WEEKDAYS.keys.joinToString("|")

    // ── Recurrence ──────────────────────────────────────────────────────────

    data class RecurrenceMatch(val recurrence: Recurrence, val remaining: String)

    fun extractRecurrence(text: String): RecurrenceMatch? {
        val rules: List<Pair<Regex, (MatchResult) -> Recurrence>> = listOf(
            Regex("$I\\s(?:todos\\s+los\\s+d[ií]as|cada\\s+d[ií]a|diariamente|a\\s+diario)\\b") to { _ -> Recurrence(Recurrence.Frequency.DAILY) },
            Regex("$I\\s(?:entre\\s+semana|(?:los\\s+|en\\s+)?d[ií]as\\s+laborables|de\\s+lunes\\s+a\\s+viernes)\\b") to { _ -> Recurrence(Recurrence.Frequency.WEEKDAYS) },
            // "cada lunes y jueves", "todos los martes", "los lunes, miércoles y viernes"
            Regex("$I\\s(?:cada|todos\\s+los|todas\\s+las|los)\\s+((?:$DAY_ALT)(?:\\s*(?:,|y)\\s*(?:el\\s+|los\\s+)?(?:$DAY_ALT))*)\\b") to { m ->
                Recurrence(
                    Recurrence.Frequency.WEEKLY,
                    daysOfWeek = Regex("$I$DAY_ALT").findAll(m.groupValues[1]).map { WEEKDAYS.getValue(it.value.lowercase()) }.toSet()
                )
            },
            Regex("$I\\s(?:cada\\s+semana|semanalmente|todas\\s+las\\s+semanas)\\b") to { _ -> Recurrence(Recurrence.Frequency.WEEKLY) },
            // "el día 1 de cada mes", "cada mes el día 15", "mensualmente"
            Regex("$I\\s(?:el\\s+)?d[ií]a\\s+(\\d{1,2})\\s+de\\s+cada\\s+mes\\b") to { m ->
                Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = m.groupValues[1].toInt().coerceIn(1, 31))
            },
            Regex("$I\\s(?:cada\\s+mes|mensualmente|todos\\s+los\\s+meses)(?:\\s+el\\s+(?:d[ií]a\\s+)?(\\d{1,2}))?\\b") to { m ->
                Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = m.groupValues[1].toIntOrNull()?.coerceIn(1, 31))
            },
            Regex("$I\\s(?:cada\\s+a[ñn]o|anualmente|todos\\s+los\\s+a[ñn]os)\\b") to { _ -> Recurrence(Recurrence.Frequency.YEARLY) }
        )
        val padded = " $text "
        for ((regex, build) in rules) {
            val m = regex.find(padded) ?: continue
            return RecurrenceMatch(build(m), padded.removeRange(m.range).replace(Regex("\\s+"), " ").trim())
        }
        return null
    }

    // ── Extra reminders ("avísame 2 horas antes y 10 minutos antes") ────────

    data class RemindersMatch(val offsetsMinutes: List<Int>, val remaining: String)

    private val AMOUNT = "(\\d+|un|una|dos|tres|cuatro|cinco|diez|quince|veinte|treinta|media)"
    private val UNIT = "(minutos?|mins?|horas?|d[ií]as?|semanas?)"

    fun extractReminders(text: String): RemindersMatch {
        val block = Regex("$I[,\\s]*(?:y\\s+)?(?:av[ií]same|recu[eé]rdamelo|recu[eé]rdame(?:lo)?|y)(?:\\s+tambi[eé]n)?\\s+$AMOUNT\\s+$UNIT\\s+antes\\b")
        val offsets = mutableListOf<Int>()
        var remaining = text
        while (true) {
            val m = block.find(remaining) ?: break
            // A bare "y" only counts after an earlier "avísame"
            if (m.value.trim().lowercase().startsWith("y") && offsets.isEmpty()) break
            offsets += toMinutes(m.groupValues[1], m.groupValues[2])
            remaining = remaining.removeRange(m.range)
        }
        return RemindersMatch(offsets, remaining.replace(Regex("\\s+"), " ").trim().trimEnd(',', ' '))
    }

    /** "una semana" → 10080, "2 horas" → 120, "media hora" → 30. */
    fun toMinutes(amount: String, unit: String): Int {
        val a = amount.lowercase()
        val n = a.toIntOrNull() ?: NUMBERS[a] ?: 1
        val u = unit.lowercase()
        return when {
            a == "media" -> if (u.startsWith("hora")) 30 else if (u.startsWith("d")) 720 else 30
            u.startsWith("min") -> n
            u.startsWith("hora") -> n * 60
            u.startsWith("d") -> n * 60 * 24
            else -> n * 60 * 24 * 7
        }
    }

    /** Finds a relative amount ("una semana", "2 horas") in the text. */
    fun findRelativeAmount(text: String): Pair<Int, IntRange>? {
        val m = Regex("$I\\b$AMOUNT\\s+$UNIT\\b").find(text) ?: return null
        return toMinutes(m.groupValues[1], m.groupValues[2]) to m.range
    }

    private val NUMBERS = mapOf(
        "un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "diez" to 10, "quince" to 15, "veinte" to 20, "treinta" to 30
    )

    // ── Mentioned meeting ("para la reunión del sprint", "antes de la llamada con Ana") ──

    fun extractMeetingHint(text: String): String? =
        Regex("$I\\b(?:para|antes\\s+de|de\\s+cara\\s+a)\\s+(?:la|el)\\s+((?:reuni[oó]n|llamada|meeting|call|junta|videollamada|entrevista|presentaci[oó]n)(?:\\s+(?:de|del|con|sobre)\\s+[^,.;]+)?)")
            .find(text)?.groupValues?.get(1)?.trim()

    // ── Priority ("es urgente", "sin prisa", "prioridad alta") ──────────────

    data class PriorityMatch(val priority: TaskPriority, val remaining: String)

    // Order matters: "no es urgente" before "urgente"
    private val PRIORITY_RULES: List<Pair<Regex, TaskPriority>> = listOf(
        Regex("$I[,\\s]*(?:que\\s+)?no\\s+es\\s+(?:urgente|importante|prioritari[oa])\\b") to TaskPriority.LOW,
        Regex("$I[,\\s]*(?:con\\s+)?prioridad\\s+(?:baja|m[ií]nima)\\b") to TaskPriority.LOW,
        Regex("$I[,\\s]*(?:sin\\s+prisa|cuando\\s+puedas?|alg[uú]n\\s+d[ií]a|poco\\s+importante)\\b") to TaskPriority.LOW,
        Regex("$I[,\\s]*(?:con\\s+)?prioridad\\s+(?:media|normal)\\b") to TaskPriority.MEDIUM,
        Regex("$I[,\\s]*(?:con\\s+)?prioridad\\s+(?:alta|m[aá]xima)\\b") to TaskPriority.HIGH,
        Regex("$I[,\\s]*(?:que\\s+)?(?:es\\s+)?(?:muy\\s+)?(?:urgente|importante|prioritari[oa])\\b[:,]?") to TaskPriority.HIGH,
        Regex("$I[,\\s]*(?:cuanto\\s+antes|lo\\s+antes\\s+posible|asap)\\b") to TaskPriority.HIGH,
        Regex("\\s*!{2,}") to TaskPriority.HIGH
    )

    fun extractPriority(text: String): PriorityMatch? {
        for ((regex, priority) in PRIORITY_RULES) {
            val m = regex.find(text) ?: continue
            val remaining = text.removeRange(m.range).replace(Regex("\\s+"), " ").trim().trim(',', ':', ' ')
            return PriorityMatch(priority, remaining)
        }
        return null
    }

    /** A bare priority word ("urgente", "alta", "sin prisa") → priority. For SET_PRIORITY. */
    fun priorityWord(word: String): TaskPriority? {
        val w = word.lowercase().trim()
        return when {
            Regex("^(?:sin\\s+prioridad|ninguna|normal\\s+sin)$").matches(w) -> TaskPriority.NONE
            Regex("^(?:baja|poco\\s+importante|sin\\s+prisa|no\\s+urgente)$").matches(w) -> TaskPriority.LOW
            Regex("^(?:media|normal)$").matches(w) -> TaskPriority.MEDIUM
            Regex("^(?:alta|m[aá]xima|urgente|importante|muy\\s+importante|prioritari[oa])$").matches(w) -> TaskPriority.HIGH
            else -> null
        }
    }

    // ── Place reminder ("cuando llegue a casa", "al salir del trabajo") ─────

    data class PlaceMatch(val place: String, val onArrive: Boolean, val remaining: String)

    private val PLACE_REGEX = Regex(
        "$I[,\\s]*(?:para\\s+)?(?:(cuando\\s+(?:llegue|vuelva|regrese|est[eé])|en\\s+cuanto\\s+(?:llegue|vuelva)|al\\s+(?:llegar|volver|regresar)|nada\\s+m[aá]s\\s+llegar)|" +
            "(cuando\\s+salga|al\\s+salir|en\\s+cuanto\\s+salga|cuando\\s+me\\s+vaya|al\\s+irme))" +
            "\\s+(?:a\\s+la|a\\s+mi|al|a|en\\s+el|en\\s+la|en\\s+mi|en|de\\s+la|de\\s+mi|del|de)\\s+(\\p{L}+)[,]?"
    )

    /** Synonyms → saved-place key (Spanish and English names map to the same key). */
    private val PLACE_ALIASES = mapOf(
        "casa" to "casa", "piso" to "casa", "hogar" to "casa", "home" to "casa",
        "trabajo" to "trabajo", "oficina" to "trabajo", "curro" to "trabajo", "work" to "trabajo", "office" to "trabajo",
        "gimnasio" to "gimnasio", "gym" to "gimnasio",
        "universidad" to "universidad", "uni" to "universidad", "facultad" to "universidad", "clase" to "universidad", "university" to "universidad",
        "super" to "supermercado", "súper" to "supermercado", "supermercado" to "supermercado", "supermarket" to "supermercado"
    )

    /**
     * Final title cleanup, whatever engine produced it (Gemma copied "Que se llama hacer X…"):
     * removes "(créame) una tarea", "que se llama/llame", "llamada", "titulada", "con el nombre de", "task called", ":".
     */
    fun cleanTitle(title: String): String {
        var t = title.trim().trim('"', '«', '»', '\'')
        t = t.replace(Regex("$I^(?:cr[eé]a(?:me|r)?|a[ñn][aá]de(?:me)?|ap[uú]nta(?:me)?|an[oó]ta(?:me)?|pon(?:me)?|haz(?:me)?|agrega|create|add|make)\\s+(?=(?:una\\s+|la\\s+|a\\s+|the\\s+)?(?:nueva\\s+|new\\s+)?(?:tarea|task)\\b)"), "")
        t = t.replace(Regex("$I^(?:una\\s+|la\\s+|a\\s+|the\\s+)?(?:nueva\\s+|new\\s+)?(?:tarea|task)\\b\\s*"), "")
        t = t.replace(Regex("$I^(?:que\\s+se\\s+(?:llam[ae]|titul[ae])|llamada|titulada|con\\s+el\\s+(?:nombre|t[ií]tulo)(?:\\s+de)?|que\\s+diga|de\\s+nombre|que\\s+ponga|called|named|titled|that\\s+says)\\b\\s*:?\\s*"), "")
        t = t.trimStart(':', ' ', ',').trim()
        t = t.replace(Regex("$I^the\\s+(?=\\S)"), "")
        Regex("$I^(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+(?:my\\s+|the\\s+)?(?:shopping|grocery)\\s+list$").find(t)?.let { t = "Buy " + it.groupValues[1] }
        Regex("$I^(?:a[ñn]adir|poner|apuntar)\\s+(.+?)\\s+(?:a|en)\\s+la\\s+lista\\s+de\\s+la\\s+compra$").find(t)?.let { t = "Comprar " + it.groupValues[1] }
        return (t.ifBlank { title.trim() }).replaceFirstChar { it.uppercase() }
    }

    fun normalizePlace(name: String): String {
        val n = name.lowercase().trim()
        return PLACE_ALIASES[n] ?: CategoryHeuristics.normalize(n)
    }

    fun extractPlace(text: String): PlaceMatch? {
        val m = PLACE_REGEX.find(text) ?: return null
        val onArrive = m.groupValues[1].isNotBlank()
        val remaining = text.removeRange(m.range).replace(Regex("\\s+"), " ").trim().trim(',', ' ')
        return PlaceMatch(normalizePlace(m.groupValues[3]), onArrive, remaining)
    }

    // ── Brain dump ──────────────────────────────────────────────────────────

    /**
     * Splits "comprar pan, llamar a Ana y acabar el informe" into 3 chunks. Only when EVERY chunk starts with a verb
     * (so "comprar pan y leche" stays one task).
     */
    fun splitBrainDump(text: String): List<String> {
        val parts = text.split(Regex("$I\\s*(?:[,;]|\\s+y\\s+|\\s+luego\\s+|\\s+adem[aá]s\\s+|\\s+tambi[eé]n\\s+|\\s+despu[eé]s\\s+)\\s*"))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return listOf(text)
        return if (parts.all { startsWithVerb(it) }) parts else listOf(text)
    }

    private val LEAD_IN = Regex("$I^(?:y\\s+)?(?:(?:ma[ñn]ana|hoy|luego|tambi[eé]n)\\s+)?(?:tengo\\s+que|hay\\s+que|debo|necesito|quiero|recu[eé]rdame|recordarme|ap[uú]ntame|que)?\\s*")

    fun startsWithVerb(chunk: String): Boolean {
        val first = chunk.replace(LEAD_IN, "").trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.trim(',', '.') ?: return false
        return first.length >= 4 && Regex("(ar|er|ir|arme|erme|irme|arle|erle|irle|arlo|erlo|irlo|arla|erla|irla)$").containsMatchIn(first)
    }
}
