package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.IntentRoute
import java.time.LocalDateTime

/**
 * Top-level routing (pure, tested, Spanish + English). The rules only decide the unambiguous cases; everything else
 * returns null and goes through the normal pipeline (rules + LLM interpretation):
 * - AGENT: the request names an agent as the doer ("…con Claude", «dile a Claude que…», "ask Claude to…",
 *   "Claude, …"). [agentRequest] extracts what the agent should do.
 * - OPINION: asking for an opinion («¿qué opinas de…?», "do you think…") → answered, never turned into a task.
 * - UNSURE: a wish to build something with no date («me gustaría programar una web», "I'd like to build an app") →
 *   Lumi asks: task or agent?
 */
object IntentRouter {

    /** Agents the router knows by name (Claude Code on the PC for now). */
    val DEFAULT_AGENTS = listOf("Claude")

    fun classify(text: String, now: LocalDateTime = LocalDateTime.now(), agents: List<String> = DEFAULT_AGENTS): IntentRoute? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (agentRequest(t, agents) != null) return IntentRoute.AGENT
        if (OPINION.containsMatchIn(t)) return IntentRoute.OPINION
        if (WISH.containsMatchIn(t) && !hasDate(t, now)) return IntentRoute.UNSURE
        return null
    }

    /**
     * What the agent should do, or null when [text] doesn't hand anything to an agent.
     * "Quiero hacer una nueva web con Claude" → "Quiero hacer una nueva web"; "Claude, fix the build" → "fix the build".
     */
    fun agentRequest(text: String, agents: List<String> = DEFAULT_AGENTS): String? {
        val t = text.trim().trimEnd('.', '!')
        for (name in agents) {
            val n = Regex.escape(name)
            val code = "(?:\\s+code)?"
            val patterns = listOf(
                // "Claude, …" / "Oye Claude: …" / "Hey Claude …"
                Regex("(?iu)^(?:oye\\s+|hey\\s+|ok\\s+)?$n$code\\s*[,:]\\s*(.+)$"),
                // «dile / pídele / pregúntale a Claude (que) …»
                Regex("(?iu)^(?:por\\s+favor\\s+)?(?:dile|p[ií]dele|preg[uú]ntale|encarga(?:le)?|m[aá]nda(?:le)?|p[aá]sa(?:le)?)\\s+a\\s+$n$code\\s+(?:que\\s+|esto:?\\s*)?(.+)$"),
                // "ask / tell / have / get Claude (to) …"
                Regex("(?iu)^(?:please\\s+)?(?:ask|tell|have|get|let)\\s+$n$code\\s+(?:to\\s+)?(.+)$"),
                // "… with / using / via Claude", «… con / en / a través de Claude» at the end
                Regex("(?iu)^(.+?)\\s+(?:with|using|via|through|in|con|en|a\\s+trav[eé]s\\s+de|usando)\\s+$n$code\\s*[?!.]*$"),
                // «… que lo haga Claude» / "… and let Claude do it"
                Regex("(?iu)^(.+?)[,;]?\\s+(?:y\\s+)?que\\s+lo\\s+haga\\s+$n$code\\s*[?!.]*$"),
                Regex("(?iu)^(.+?)[,;]?\\s+(?:and\\s+)?let\\s+$n$code\\s+(?:do|handle)\\s+it\\s*[?!.]*$"),
                // «abre una sesión de Claude (para) …» / "open / start a Claude session (to) …"
                Regex("(?iu)^(?:abre|inicia|empieza)\\s+una\\s+sesi[oó]n\\s+(?:de|con)\\s+$n$code(?:\\s+(?:para|y)\\s+(.+))?$"),
                Regex("(?iu)^(?:open|start)\\s+(?:a\\s+)?(?:new\\s+)?$n$code\\s+session(?:\\s+(?:to|and)\\s+(.+))?$"),
                // "send / pass this to Claude: …" / «pásale esto a Claude: …»
                Regex("(?iu)^(?:send|pass|forward|m[aá]nda|p[aá]sa|env[ií]a)(?:le)?\\s+(?:this|it|esto|eso)?\\s*(?:to|a)\\s+$n$code\\s*[:,]?\\s*(.*)$")
            )
            for ((i, p) in patterns.withIndex()) {
                val m = p.find(t) ?: continue
                val task = m.groupValues.getOrNull(1).orEmpty().trim().trimEnd(',', ';', ':')
                // "… con Claude" needs a real request before it («confío en Claude» isn't one)
                if (i == WITH_PATTERN && task.split(Regex("\\s+")).size < 3) continue
                return task.ifBlank { t }
            }
        }
        return null
    }

    private const val WITH_PATTERN = 3

    private val OPINION = Regex(
        "(?iu)(?:qu[eé]\\s+opinas|qu[eé]\\s+piensas\\s+(?:de|sobre)|\\bcrees\\s+que\\b|qu[eé]\\s+te\\s+parece|\\bt[uú]\\s+qu[eé]\\s+har[ií]as|" +
            "me\\s+recomiendas|\\bes\\s+(?:una\\s+)?buena\\s+idea|\\bvale\\s+la\\s+pena|" +
            "what\\s+do\\s+you\\s+think|\\bdo\\s+you\\s+think\\b|your\\s+opinion|what'?s\\s+your\\s+take|would\\s+you\\s+recommend|" +
            "\\bis\\s+it\\s+(?:a\\s+)?good\\s+idea|is\\s+it\\s+worth|what\\s+would\\s+you\\s+do|should\\s+i\\s+(?:buy|choose|pick|go\\s+with))"
    )

    private val WISH = Regex(
        "(?iu)^(?:yo\\s+)?(?:quiero|quisiera|me\\s+gustar[ií]a|tengo\\s+ganas\\s+de|estoy\\s+pensando\\s+en|i'?d\\s+like\\s+to|i\\s+would\\s+like\\s+to|i\\s+want\\s+to|i'?m\\s+thinking\\s+(?:of|about))\\s+" +
            "(?:hacer(?:me)?|programar|crear|montar|construir|desarrollar|dise[nñ]ar|escribir|build|make|create|program|code|develop|design|write|start|building|making|creating|coding|developing|designing|writing|starting)\\b"
    )

    /** "Me gustaría programar una nueva web" → "Programar una nueva web" (the task title when the user says "task"). */
    fun wishObject(text: String): String {
        val m = Regex("(?iu)^(?:yo\\s+)?(?:quiero|quisiera|me\\s+gustar[ií]a|tengo\\s+ganas\\s+de|estoy\\s+pensando\\s+en|i'?d\\s+like\\s+to|i\\s+would\\s+like\\s+to|i\\s+want\\s+to|i'?m\\s+thinking\\s+(?:of|about))\\s+(.+)$").find(text.trim()) ?: return text.trim()
        return m.groupValues[1].trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }
    }

    /** A date or time in the sentence makes it a plan for the calendar, not a vague wish. */
    private fun hasDate(text: String, now: LocalDateTime): Boolean =
        (SpanishDateParser.parse(text, now) ?: EnglishDateParser.parse(text, now)) != null
}
