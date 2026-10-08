package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.plain
import java.time.LocalDateTime

/**
 * Secretary requests (pure, tested, Spanish + English): jobs that need real reasoning or work (write or answer an email,
 * summarise, plan a trip, research, check the inbox, decide what to prioritise) go to Claude on the PC. Conservative on
 * purpose: the sentence must START with a work verb (after polite filler) and contain a matching object, so tasks
 * ("tengo que escribir un correo"), phone actions ("escribe a Víctor que llego tarde") and quick questions never match.
 * Tolerates accents, plurals, clitics («redáctame») and one-letter typos.
 */
object SecretaryRouter {

    enum class Verdict {
        /** A clear secretary job: hand it to Claude. */
        DELEGATE,
        /** Could be a task for later or a job for Claude (a date is involved): Lumi asks. */
        AMBIGUOUS
    }

    fun judge(text: String, now: LocalDateTime): Verdict? {
        val all = plain(text).split(" ").filter { it.isNotEmpty() }
        if (all.size < 2) return null
        val joined = all.joinToString(" ")
        if (PRIORITIZE.containsMatchIn(joined)) return Verdict.DELEGATE
        var tokens = all.dropWhile { it in FILLER }
        // «ayúdame a / help me» + a work verb
        if (tokens.size >= 3 && (tokens[0].startsWith("ayud") && tokens[1] == "a" || tokens[0] == "help" && tokens[1] == "me")) {
            tokens = tokens.drop(2).dropWhile { it == "to" }
        }
        val head = tokens.firstOrNull() ?: return null
        val rest = tokens.drop(1).take(10)
        if (rest.isEmpty() || head.startsWith("compr")) return null // "comprar" is not "comparar" (typo tolerance)
        val nearby = rest.any { w -> NEARBY.any { w.startsWith(it) } }

        fun has(objects: Set<String>) = rest.any { w -> objects.any { isWord(w, it) } }

        // Write / prepare a document or message
        if (matchesVerb(head, DRAFT_VERBS)) {
            val strictHead = STRICT_HEADS.any { head.startsWith(it) }
            val objects = DRAFT_OBJECTS + if (strictHead) DRAFT_STRICT_OBJECTS else emptySet()
            if (has(objects)) {
                if (rest.any { it in TODAY_WORDS } && rest.any { isWord(it, "resumen") || isWord(it, "summary") }) return null // the daily brief
                val dated = !strictHead && hasDate(text, now)
                return if (dated) Verdict.AMBIGUOUS else Verdict.DELEGATE
            }
        }
        if ((head == "haz" || head == "hazme" || head == "make") && has(setOf("borrador", "draft"))) return Verdict.DELEGATE
        // Summarise something that is not today's task list
        if (matchesVerb(head, SUMMARY_VERBS) && has(SUMMARY_OBJECTS)) return Verdict.DELEGATE
        // Research / find / compare
        if (matchesVerb(head, RESEARCH_VERBS)) {
            if (!nearby && (has(RESEARCH_OBJECTS) || rest.size >= 2)) return Verdict.DELEGATE
        }
        if (matchesVerb(head, SEARCH_VERBS) && !nearby && has(RESEARCH_OBJECTS)) return Verdict.DELEGATE
        // Compare
        if (matchesVerb(head, COMPARE_VERBS) && rest.size >= 2) return Verdict.DELEGATE
        // Organise / plan something big (a day stays Lumi's own planner)
        if (matchesVerb(head, PLAN_VERBS) && rest.none { it == "dia" || it == "day" || it == "hoy" || it == "today" } && has(PLAN_OBJECTS)) return Verdict.DELEGATE
        // Inbox
        if (matchesVerb(head, INBOX_VERBS) && has(MAIL) && has(INBOX_WORDS)) return Verdict.DELEGATE
        // Answer an email
        if (matchesVerb(head, REPLY_VERBS) && has(MAIL)) return Verdict.DELEGATE
        return null
    }

    private fun matchesVerb(token: String, stems: Set<String>): Boolean = stems.any { stem ->
        if (stem.endsWith("$")) token == stem.dropLast(1)
        else token.startsWith(stem) || (stem.length >= 5 && token.length >= stem.length && (near(token.take(stem.length), stem) || near(token.take(stem.length + 1), stem)))
    }

    private fun isWord(token: String, word: String): Boolean =
        token == word || (word.length >= 4 && token.startsWith(word)) || (word.length >= 5 && token.length >= 5 && near(token, word))

    /** Equal, one edit apart, or one adjacent swap ("corero" ~ "correo"). */
    internal fun near(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            if (diff.size == 1) return true
            if (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]) return true
            return false
        }
        val (s, l) = if (a.length < b.length) a to b else b to a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        return s.substring(i) == l.substring(i + 1)
    }

    private fun hasDate(text: String, now: LocalDateTime): Boolean =
        (SpanishDateParser.parse(text, now) ?: EnglishDateParser.parse(text, now)) != null

    private val FILLER = setOf(
        "oye", "hey", "lumi", "ok", "vale", "pues", "y", "por", "favor", "please", "puedes", "podrias", "podria", "puede",
        "can", "could", "would", "will", "you", "necesito", "quiero", "que", "i", "need", "want", "to", "gustaria", "ahora",
        "entonces", "then", "so", "well", "a", "ver", "si", "me", "te", "claro", "okay"
    )

    private val PRIORITIZE = Regex(
        "\\b(?:que|what)\\s+(?:deberia|debo|debería|should\\s+i|do\\s+i\\s+need\\s+to)\\s+(?:priorizar|hacer\\s+primero|prioritize|prioritise|tackle\\s+first|do\\s+first|focus\\s+on)|" +
            "\\bayudame\\s+a\\s+priorizar|\\bhelp\\s+me\\s+prioriti[sz]e"
    )

    private val DRAFT_VERBS = setOf("redact", "escrib", "prepar", "draft", "writ", "compos", "elabor")
    private val STRICT_HEADS = listOf("redact", "draft", "compos")
    private val DRAFT_OBJECTS = setOf(
        "correo", "email", "mail", "carta", "letter", "informe", "report", "propuesta", "proposal", "presentacion", "presentation",
        "discurso", "speech", "resumen", "summary", "borrador", "respuesta", "reply", "curriculum", "cv", "cover", "articulo", "article"
    )
    private val DRAFT_STRICT_OBJECTS = setOf("mensaje", "message")
    private val TODAY_WORDS = setOf("hoy", "manana", "today", "tomorrow", "matinal", "dia", "day", "morning")

    private val SUMMARY_VERBS = setOf("resum", "summar")
    private val SUMMARY_OBJECTS = setOf(
        "articulo", "correo", "email", "mail", "reunion", "documento", "pdf", "texto", "noticias", "hilo", "conversacion", "semana", "mes",
        "libro", "pagina", "article", "thread", "document", "meeting", "notes", "news", "book", "week", "month", "report", "informe", "page"
    )

    private val RESEARCH_VERBS = setOf("investig", "averigu", "research")
    private val SEARCH_VERBS = setOf("busc", "encuentr", "find$", "search$")
    private val COMPARE_VERBS = setOf("compar")
    private val RESEARCH_OBJECTS = setOf(
        "vuelo", "billete", "hotel", "alojamiento", "airbnb", "oferta", "opcion", "tren", "alquiler", "apartamento", "piso", "regalo",
        "seguro", "flight", "ticket", "deal", "option", "train", "rental", "gift", "insurance", "viaje", "trip", "precio", "price"
    )
    private val NEARBY = listOf(
        "cerca", "near", "restaurante", "restaurant", "cafeteria", "cafe", "farmacia", "gasolinera", "sitio", "lugar", "place",
        "comer", "eat", "supermercado", "tienda"
    )

    private val PLAN_VERBS = setOf("organi", "planific", "plane", "plan$", "prepar", "arrang")
    private val PLAN_OBJECTS = setOf(
        "viaje", "vacacion", "escapada", "fiesta", "cumpleano", "evento", "boda", "mudanza", "semana", "agenda", "trip", "vacation",
        "holiday", "party", "birthday", "event", "wedding", "week", "itinerario", "itinerary", "entrevista", "interview", "reunion", "meeting"
    )

    private val INBOX_VERBS = setOf("mira$", "look$", "revis", "chequ", "compru", "check", "review", "scan")
    private val MAIL = setOf("correo", "email", "mail", "bandeja", "inbox")
    private val INBOX_WORDS = setOf("importante", "urgente", "pendiente", "algo", "important", "urgent", "anything", "pending", "contestar", "responder", "reply")

    private val REPLY_VERBS = setOf("contest", "respond", "repl", "answer$")
}
