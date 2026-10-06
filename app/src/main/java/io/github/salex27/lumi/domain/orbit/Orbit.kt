package io.github.salex27.lumi.domain.orbit

import java.text.Normalizer

/**
 * Who answers for an agent. v1 ships Lumi's own brain and Claude Code on the PC (through Lumi Hub); API backends
 * (Gemini, Anthropic, OpenAI, OpenAI-compatible/Ollama) and Codex arrive with the selectable brain (#2) and plug in
 * as new kinds plus an `AgentBackend` implementation.
 */
enum class AgentBackendKind(val defaultName: String, val defaultColor: AgentPalette, val defaultFace: AgentFaceStyle) {
    /** Lumi's brain (whatever engine is active: on-device Gemma today), as a named agent with its own purpose. */
    LUMI("Nova", AgentPalette.VIOLET, AgentFaceStyle.DOTS),
    /** Claude Code on the user's PC, one turn per message (`claude -p --resume`) through Lumi Hub. */
    CLAUDE_PC("Claude", AgentPalette.ORANGE, AgentFaceStyle.SPARK);

    companion object {
        fun of(name: String): AgentBackendKind? = entries.firstOrNull { it.name == name }
    }
}

/** Agent colours (light/dark values live in the theme, `agentColor`). */
enum class AgentPalette { SKY, MAGENTA, ORANGE, GREEN, VIOLET, TEAL;
    companion object { fun of(key: String) = entries.firstOrNull { it.name == key } ?: SKY }
}

/** Agent faces: simple shapes with two eyes, in the style of Lumi's mark. */
enum class AgentFaceStyle { DOTS, RING, SQUARE, SPARK;
    companion object { fun of(key: String) = entries.firstOrNull { it.name == key } ?: DOTS }
}

/** A participant as the router sees it. */
data class OrbitAgent(val id: Long, val name: String)

/**
 * Orbit routing v1 (pure, tested): the user pings agents explicitly with `@Name`. In a one-agent Orbit ("Chat with
 * Claude") every message goes to that agent; in a group, a message without a mention is answered by Lumi. Agents
 * never all answer at once unless each one is mentioned.
 */
object OrbitRouter {

    data class Route(
        /** Agents that must answer, in mention order; empty = Lumi answers. */
        val agents: List<OrbitAgent>,
        /** The message without the leading mentions (what the agents receive). */
        val text: String
    ) {
        val toLumi: Boolean get() = agents.isEmpty()
    }

    fun route(message: String, members: List<OrbitAgent>): Route {
        val text = message.trim()
        val mentioned = mentions(text, members)
        if (mentioned.isNotEmpty()) return Route(mentioned, stripLeadingMentions(text, members).ifBlank { text })
        if (members.size == 1) return Route(members, text)
        return Route(emptyList(), text)
    }

    /** Agents mentioned with @, longest name first so "@Claude Code" beats "@Claude", without duplicates. */
    fun mentions(text: String, members: List<OrbitAgent>): List<OrbitAgent> {
        val norm = fold(text)
        val byLength = members.sortedByDescending { it.name.length }
        val found = mutableListOf<Pair<Int, OrbitAgent>>()
        var i = norm.indexOf('@')
        while (i >= 0) {
            val after = norm.substring(i + 1)
            byLength.firstOrNull { a -> val n = fold(a.name); n.isNotEmpty() && after.startsWith(n) && boundary(after, n.length) }
                ?.let { a -> if (found.none { it.second.id == a.id }) found += i to a }
            i = norm.indexOf('@', i + 1)
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** "@Claude @Lumi fix the build" → "fix the build"; mentions inside the sentence stay (they read naturally). */
    fun stripLeadingMentions(text: String, members: List<OrbitAgent>): String {
        var rest = text.trim()
        while (rest.startsWith("@")) {
            val norm = fold(rest.substring(1))
            val a = members.sortedByDescending { it.name.length }.firstOrNull { val n = fold(it.name); n.isNotEmpty() && norm.startsWith(n) && boundary(norm, n.length) }
                ?: break
            rest = rest.substring(1 + a.name.length).trimStart(',', ':', ' ')
        }
        return rest.trim()
    }

    private fun boundary(s: String, end: Int) = end >= s.length || !s[end].isLetterOrDigit()

    /** Lowercase without accents, same length as the input for these names (NFD marks removed per char). */
    internal fun fold(s: String): String = s.map { c ->
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").firstOrNull()?.lowercaseChar() ?: c
    }.joinToString("")
}

/** Thread ids sent to Lumi Hub: one agent session per agent per Orbit. */
object OrbitThreads {
    fun id(sessionId: Long, agentId: Long) = "orbit-$sessionId-$agentId"

    /** "orbit-12-3" → (12, 3), or null for threads that aren't Orbit's. */
    fun parse(thread: String?): Pair<Long, Long>? {
        val m = Regex("^orbit-(\\d+)-(\\d+)$").matchEntire(thread ?: return null) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2].toLong()
    }
}

/** A message of the thread as the context builder sees it. */
data class OrbitLine(val speaker: String, val text: String, val agentId: Long? = null, val isUser: Boolean = false)

/**
 * What an agent gets besides the message itself (pure, tested). Claude keeps its own session, so it only needs what
 * happened in the Orbit since its last reply (other agents, Lumi, earlier user messages); Lumi's brain has no session,
 * so it gets the newest lines that fit. Both are capped in characters.
 */
object OrbitContext {
    /**
     * What a PC agent should know about the moment: date/time, the user's language and (only when the agent may read
     * tasks) a compact list of today's open tasks. Titles only: no descriptions, places or ids, nothing secret.
     */
    fun header(now: java.time.LocalDateTime, language: String, todayTasks: List<String>?, maxTasks: Int = 8): String = buildString {
        append("[Now: ").append(now.toLocalDate()).append(' ').append(now.toLocalTime().withSecond(0).withNano(0))
        append(" · user language: ").append(language).append("]")
        if (!todayTasks.isNullOrEmpty()) {
            append("\n[Today's open tasks: ")
            append(todayTasks.take(maxTasks).joinToString("; ") { it.replace(Regex("\\s+"), " ").take(60) })
            if (todayTasks.size > maxTasks) append("; +${todayTasks.size - maxTasks} more")
            append("]")
        }
    }

    fun sinceLastReply(lines: List<OrbitLine>, agentId: Long, maxChars: Int = 1_500): String {
        val start = lines.indexOfLast { it.agentId == agentId } + 1
        // The newest line is the message being sent: it isn't context
        return render(lines.subList(start.coerceAtMost(lines.size), (lines.size - 1).coerceAtLeast(start)), maxChars)
    }

    fun recent(lines: List<OrbitLine>, maxChars: Int = 1_200): String = render(lines.dropLast(1), maxChars)

    private fun render(lines: List<OrbitLine>, maxChars: Int): String {
        val out = ArrayDeque<String>()
        var used = 0
        for (l in lines.asReversed()) {
            val line = "${l.speaker}: ${l.text.replace(Regex("\\s+"), " ").take(400)}"
            if (used + line.length > maxChars && out.isNotEmpty()) break
            out.addFirst(line)
            used += line.length + 1
        }
        return out.joinToString("\n")
    }
}
