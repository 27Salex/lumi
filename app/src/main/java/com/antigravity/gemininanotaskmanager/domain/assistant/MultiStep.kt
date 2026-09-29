package com.antigravity.gemininanotaskmanager.domain.assistant

import com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger
import com.antigravity.gemininanotaskmanager.domain.model.Task

/**
 * Varias órdenes en una frase (puro, testeado): «apunta comprar pan y pon una alarma a las 7 y luego dile a Ana que
 * llego tarde» → 3 órdenes. Solo se corta justo antes de un verbo de orden conocido (imperativo), así
 * «comprar pan y leche» o «dile a Ana que compre pan y que me espere» no se rompen.
 * Los trozos que solo matizan la orden anterior («…y avísame cuando llegue a casa», «…y ponle prioridad alta»)
 * se vuelven a pegar a ella.
 */
object CommandSplitter {

    private const val VERBS = "cr[eé]a(?:me)?|ap[uú]nta(?:me)?|a[ñn][aá]de(?:me)?|an[oó]ta(?:me)?|recu[eé]rda(?:me|melo)?|pon(?:me|le)?|p[oó]n(?:me|le)|" +
        "ll[aá]ma(?:le)?|escr[ií]be(?:le)?|d[ií]le|av[ií]sa(?:le|me)?|m[aá]nda(?:le)?|env[ií]a(?:le)?|[aá]bre(?:me)?|mu[eé]ve(?:la|lo)?|" +
        "p[aá]sa(?:la|lo)?|posp[oó]n(?:la|lo)?|m[aá]rca(?:la|lo)?|cancela|borra|b[uú]sca(?:me)?|ll[eé]vame|activa|desactiva|d[ií]me|" +
        "l[eé]e(?:me)?|ed[ií]ta(?:la|me)?|renombra|programa|reproduce|enciende|apaga|qu[eé]\\s+tiempo|c[oó]mo\\s+llego"

    private val SPLIT = Regex(
        "(?iu)\\s*(?:[.;]|,?\\s+y\\s+(?:luego\\s+|despu[eé]s\\s+|tambi[eé]n\\s+|adem[aá]s\\s+)?|,?\\s+(?:luego|despu[eé]s|y\\s+tambi[eé]n)\\s+|,\\s*)\\s*(?=(?:$VERBS)\\b)"
    )

    /** Trozo que matiza la orden anterior: «avísame cuando llegue a casa», «ponle prioridad alta», «recuérdamelo mañana». */
    private val MODIFIER = Regex(
        "(?iu)^(?:av[ií]same|recu[eé]rdamelo|recu[eé]rdame|p[oó]nle|m[aá]rcala|m[aá]rcalo)\\b\\s*(.*)$"
    )
    private val MODIFIER_REST = Regex(
        "(?iu)^(?:$|cuando\\b|al\\s|en\\s+cuanto\\b|nada\\s+m[aá]s\\b|antes\\b|ma[ñn]ana\\b|hoy\\b|pasado\\b|el\\s|la\\s+semana|a\\s+las?\\b|dentro\\b|en\\s+\\d|" +
            "con\\s+prioridad|como\\s|prioridad\\b|urgente|\\d|lo\\s+antes)"
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
 * Qué se puede HACER con una tarea desde su aviso (puro, testeado): «Llamar a mamá» → botón «Llamar»,
 * «Escribir a Roberto» / «Avisar a Roberto» → botón que abre WhatsApp con el mensaje escrito.
 * «Avisa a Roberto cuando llegue a casa» → al llegar: «Ya he llegado a casa».
 */
object TaskActions {

    data class Action(val label: String, val command: DeviceCommand)

    private val CALL = Regex("(?iu)^(?:llamar|ll[aá]ma(?:le)?|telefonear)\\s+(?:a\\s+|al\\s+)(.+)$")
    private val MESSAGE = Regex(
        "(?iu)^(avisar|av[ií]sa(?:le)?|escribir(?:le)?|escr[ií]be(?:le)?|mandar(?:le)?|m[aá]nda(?:le)?|enviar(?:le)?|env[ií]a(?:le)?|decir(?:le)?|d[ií]le|contestar(?:le)?|responder(?:le)?)" +
            "\\s+(?:un\\s+(?:mensaje|whatsapp|wasap|sms)\\s+)?(?:a\\s+|al\\s+)(.+?)" +
            "(?:\\s+(?:por\\s+(?:whatsapp|wasap|mensaje|sms)\\s+)?(?:que|diciendo(?:le)?\\s+que|diciendo)\\s+(.+))?$"
    )

    fun detect(task: Task): Action? {
        val title = task.title.trim().trimEnd('.', '!')
        CALL.find(title)?.let { m ->
            val who = cleanContact(m.groupValues[1])
            // «Llamar al banco» también vale: el marcador buscará el contacto; si no existe, Lumi lo dirá
            return Action("Llamar", DeviceCommand.Call(who))
        }
        MESSAGE.find(title)?.let { m ->
            val who = cleanContact(m.groupValues[2])
            if (who.isBlank()) return null
            val sms = Regex("(?iu)\\b(?:sms|mensaje de texto)\\b").containsMatchIn(title)
            val said = m.groupValues[3].trim().takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            val verb = m.groupValues[1].lowercase()
            // «Avisar a X» sin texto + aviso por lugar → el mensaje es «ya he llegado/salido»
            val text = said ?: task.placeTrigger?.takeIf { verb.startsWith("avis") }?.let { p ->
                if (p.onArrive) "Ya he llegado ${PlaceTrigger.withArticle("a", p.place)}" else "Ya he salido ${PlaceTrigger.withArticle("de", p.place)}"
            }.orEmpty()
            val label = if (text.isBlank()) "Escribir a ${who.substringBefore(' ')}" else "Enviar a ${who.substringBefore(' ')}"
            return Action(label, DeviceCommand.Message(who, text, whatsapp = !sms))
        }
        return null
    }

    private fun cleanContact(s: String) = s.trim()
        .replace(Regex("(?iu)\\s+(?:por\\s+(?:whatsapp|wasap|mensaje|sms|tel[eé]fono)|al\\s+m[oó]vil)$"), "")
        .replace(Regex("(?iu)^(?:mi\\s+)"), "mi ")
        .trim()
}
