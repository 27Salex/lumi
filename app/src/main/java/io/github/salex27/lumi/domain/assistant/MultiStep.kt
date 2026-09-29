package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.Task

/**
 * Several commands in one sentence (pure, tested): "apunta comprar pan y pon una alarma a las 7 y luego dile a Ana que
 * llego tarde" → 3 commands. It only splits right before a known command verb (imperative), so "comprar pan y leche"
 * or "dile a Ana que compre pan y que me espere" stay whole. Works the same in English ("add milk and call mom").
 * Pieces that only refine the previous command ("…y avísame cuando llegue a casa", "…and make it urgent") are glued
 * back onto it.
 */
object CommandSplitter {

    private const val VERBS = "cr[eé]a(?:me)?|ap[uú]nta(?:me)?|a[ñn][aá]de(?:me)?|an[oó]ta(?:me)?|recu[eé]rda(?:me|melo)?|pon(?:me|le)?|p[oó]n(?:me|le)|" +
        "ll[aá]ma(?:le)?|escr[ií]be(?:le)?|d[ií]le|av[ií]sa(?:le|me)?|m[aá]nda(?:le)?|env[ií]a(?:le)?|[aá]bre(?:me)?|mu[eé]ve(?:la|lo)?|" +
        "p[aá]sa(?:la|lo)?|posp[oó]n(?:la|lo)?|m[aá]rca(?:la|lo)?|cancela|borra|b[uú]sca(?:me)?|ll[eé]vame|activa|desactiva|d[ií]me|" +
        "l[eé]e(?:me)?|ed[ií]ta(?:la|me)?|renombra|programa|reproduce|enciende|apaga|qu[eé]\\s+tiempo|c[oó]mo\\s+llego|" +
        // English
        "remind\\s+me|add|create|set|call|text|tell|send|open|play|move|postpone|mark|cancel|delete|search|take\\s+me|" +
        "turn\\s+(?:on|off)|read|wake\\s+me|what'?s\\s+the\\s+weather|navigate"

    private val SPLIT = Regex(
        "(?iu)\\s*(?:[.;]|,?\\s+y\\s+(?:luego\\s+|despu[eé]s\\s+|tambi[eé]n\\s+|adem[aá]s\\s+)?|,?\\s+(?:luego|despu[eé]s|y\\s+tambi[eé]n)\\s+|" +
            ",?\\s+and\\s+(?:then\\s+|also\\s+)?|,?\\s+then\\s+|,\\s*)\\s*(?=(?:$VERBS)\\b)"
    )

    /** A piece that refines the previous command: "avísame cuando llegue a casa", "ponle prioridad alta", "make it urgent". */
    private val MODIFIER = Regex(
        "(?iu)^(?:av[ií]same|recu[eé]rdamelo|recu[eé]rdame|p[oó]nle|m[aá]rcala|m[aá]rcalo|remind\\s+me|make\\s+it|set\\s+it)\\b\\s*(.*)$"
    )
    private val MODIFIER_REST = Regex(
        "(?iu)^(?:$|cuando\\b|al\\s|en\\s+cuanto\\b|nada\\s+m[aá]s\\b|antes\\b|ma[ñn]ana\\b|hoy\\b|pasado\\b|el\\s|la\\s+semana|a\\s+las?\\b|dentro\\b|en\\s+\\d|" +
            "con\\s+prioridad|como\\s|prioridad\\b|urgente|\\d|lo\\s+antes|" +
            "when\\b|at\\s|on\\s|in\\s+\\d|tomorrow\\b|today\\b|tonight\\b|next\\b|before\\b|urgent|high\\s+priority|a\\s+priority|important)"
    )

    fun split(text: String): List<String> {
        val raw = text.trim().split(SPLIT).map { it.trim().trimEnd('.', ',') }.filter { it.isNotBlank() }
        if (raw.size < 2) return listOf(text.trim())
        val merged = mutableListOf<String>()
        for (part in raw) {
            val m = MODIFIER.find(part)
            if (merged.isNotEmpty() && m != null && MODIFIER_REST.containsMatchIn(m.groupValues[1].trim())) {
                merged[merged.lastIndex] = (merged.last() + " " + m.groupValues[1].trim()).trim()
            } else merged += part
        }
        return merged
    }
}

/**
 * What you can DO with a task from its reminder (pure, tested): "Llamar a mamá" / "Call mom" → a "Call" button,
 * "Escribir a Roberto" / "Text Roberto" / "Avisar a Roberto" → a button that opens WhatsApp with the message written.
 * "Avisa a Roberto cuando llegue a casa" → on arrival: "Ya he llegado a casa" ("I just got home").
 */
object TaskActions {

    data class Action(val label: String, val command: DeviceCommand)

    private val CALL = Regex("(?iu)^(?:llamar|ll[aá]ma(?:le)?|telefonear|call|phone|ring)\\s+(?:a\\s+|al\\s+)?(.+)$")
    private val MESSAGE = Regex(
        "(?iu)^(avisar|av[ií]sa(?:le)?|escribir(?:le)?|escr[ií]be(?:le)?|mandar(?:le)?|m[aá]nda(?:le)?|enviar(?:le)?|env[ií]a(?:le)?|decir(?:le)?|d[ií]le|contestar(?:le)?|responder(?:le)?)" +
            "\\s+(?:un\\s+(?:mensaje|whatsapp|wasap|sms)\\s+)?(?:a\\s+|al\\s+)(.+?)" +
            "(?:\\s+(?:por\\s+(?:whatsapp|wasap|mensaje|sms)\\s+)?(?:que|diciendo(?:le)?\\s+que|diciendo)\\s+(.+))?$"
    )
    private val MESSAGE_EN = Regex(
        "(?iu)^(let|text|message|tell|notify|whatsapp|write\\s+to|send\\s+a\\s+(?:message|text|whatsapp)\\s+to)\\s+(.+?)" +
            "(?:\\s+know)?(?:\\s+(?:on\\s+whatsapp\\s+)?(?:that|saying)\\s+(.+))?$"
    )

    fun detect(task: Task): Action? {
        val title = task.title.trim().trimEnd('.', '!')
        val en = LanguageDetector.detect(title) == Lang.EN
        CALL.find(title)?.let { m ->
            val who = cleanContact(m.groupValues[1])
            // "Llamar al banco" also works: the dialer looks the contact up; if it doesn't exist, Lumi says so
            return Action(if (en) "Call" else "Llamar", DeviceCommand.Call(who))
        }
        (MESSAGE.find(title) ?: MESSAGE_EN.find(title))?.let { m ->
            val who = cleanContact(m.groupValues[2])
            if (who.isBlank()) return null
            val sms = Regex("(?iu)\\b(?:sms|mensaje de texto|text message)\\b").containsMatchIn(title)
            val said = m.groupValues[3].trim().takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            val verb = m.groupValues[1].lowercase()
            // "Avisar a X" / "Let X know" without text + a place reminder → the message is "I've arrived / left"
            val text = said ?: task.placeTrigger?.takeIf { verb.startsWith("avis") || verb == "let" || verb == "notify" }?.let { p ->
                if (en) (if (p.onArrive) "I just got ${if (p.place == "casa") "home" else "to ${PlaceTrigger.displayName(p.place, Lang.EN)}"}" else "I just left ${PlaceTrigger.displayName(p.place, Lang.EN)}")
                else if (p.onArrive) "Ya he llegado ${PlaceTrigger.withArticle("a", p.place)}" else "Ya he salido ${PlaceTrigger.withArticle("de", p.place)}"
            }.orEmpty()
            val first = who.substringBefore(' ')
            val label = if (en) (if (text.isBlank()) "Text $first" else "Send to $first") else if (text.isBlank()) "Escribir a $first" else "Enviar a $first"
            return Action(label, DeviceCommand.Message(who, text, whatsapp = !sms))
        }
        return null
    }

    private fun cleanContact(s: String) = s.trim()
        .replace(Regex("(?iu)\\s+(?:por\\s+(?:whatsapp|wasap|mensaje|sms|tel[eé]fono)|al\\s+m[oó]vil|on\\s+whatsapp|by\\s+text)$"), "")
        .replace(Regex("(?iu)^(?:mi\\s+)"), "mi ")
        .trim()
}
