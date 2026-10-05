package io.github.salex27.lumi.domain.orbit

import kotlinx.serialization.Serializable
import java.text.Normalizer

/** An agent as the leader sees it: who it is, what it runs on and what it is for. */
data class LeaderAgent(val id: Long, val name: String, val backend: AgentBackendKind?, val purpose: String = "")

/** A routing choice the user made (accepted or redirected), stored locally and shown in Orbit so it can be deleted. */
@Serializable
data class RoutingExample(val text: String, val agentName: String, val at: Long)

/**
 * Lumi as the team leader of an Orbit (pure, tested). For a group message without a mention it decides who should
 * answer: Lumi itself, or one agent. Signals, strongest first:
 * 1. the user's past choices for similar messages ([RoutingExample]s, word overlap);
 * 2. the agents' stated purposes (word overlap with the message);
 * 3. the kind of request: code / repo / long multi-step work goes to a capable agent (Claude Code on the PC), small
 *    talk and quick questions stay with Lumi (cheapest first: on-device before PC/API).
 * An optional LLM pick (Lumi's brain) is used only when the rules have no opinion.
 *
 * The decision is always visible ("Passing this to Claude: …") and, unless the Orbit is on automatic, only proposed.
 */
object OrbitLeader {

    enum class Why { LEARNED, PURPOSE, CODE, COMPLEX, LLM, NONE }

    data class Decision(
        /** null = Lumi answers. */
        val agent: LeaderAgent?,
        val why: Why,
        /** True when the request looks too big for Lumi itself (a delegation, always worth proposing). */
        val complex: Boolean
    )

    fun decide(text: String, agents: List<LeaderAgent>, examples: List<RoutingExample>, llmPick: String? = null): Decision {
        val complex = isComplex(text)
        if (agents.isEmpty()) return Decision(null, Why.NONE, complex)
        learned(text, agents, examples)?.let { return Decision(it.agent, Why.LEARNED, complex) }
        byPurpose(text, agents)?.let { return Decision(it, Why.PURPOSE, complex) }
        val capable = agents.firstOrNull { it.backend == AgentBackendKind.CLAUDE_PC }
        if (capable != null && isCode(text)) return Decision(capable, Why.CODE, complex)
        if (capable != null && complex) return Decision(capable, Why.COMPLEX, true)
        llmPick?.let { pick -> agents.firstOrNull { fold(it.name) == fold(pick).trim().trim('.', '"', '«', '»') } }
            ?.let { return Decision(it, Why.LLM, complex) }
        return Decision(null, Why.NONE, complex)
    }

    /** Prompt for the optional LLM pick: one name or "Lumi". */
    fun llmPrompt(text: String, agents: List<LeaderAgent>): Pair<String, String> {
        val system = "You route a message in a group chat to the best participant. Reply with ONE name only, exactly as written."
        val list = (listOf("Lumi: the user's personal assistant (quick questions, chat, tasks)") +
            agents.map { a -> "${a.name}: " + (a.purpose.ifBlank { describe(a.backend) }) }).joinToString("\n")
        return system to "PARTICIPANTS:\n$list\n\nMESSAGE: ${text.take(500)}\n\nBest participant:"
    }

    private fun describe(kind: AgentBackendKind?) = when (kind) {
        AgentBackendKind.CLAUDE_PC -> "Claude Code on the user's PC (code, repositories, long technical work)"
        AgentBackendKind.LUMI -> "an assistant running on the phone"
        null -> "an AI agent"
    }

    // ── Signals ──────────────────────────────────────────────────────────────────────────────────────────────

    private const val LEARNED_MIN = 0.34
    /** Name stored in a [RoutingExample] when the user chose Lumi itself. */
    const val LUMI = "lumi"
    private const val PURPOSE_MIN = 1

    /** A learned choice; [agent] null = the user wanted Lumi itself for messages like this. */
    private class Learned(val agent: LeaderAgent?)

    private fun learned(text: String, agents: List<LeaderAgent>, examples: List<RoutingExample>): Learned? {
        val words = words(text)
        if (words.isEmpty()) return null
        // Newest examples win ties: a recent correction beats an old habit
        val best = examples.sortedByDescending { it.at }
            .map { ex -> ex to jaccard(words, words(ex.text)) }
            .filter { it.second >= LEARNED_MIN }
            .maxByOrNull { it.second } ?: return null
        val name = best.first.agentName
        if (fold(name) == LUMI) return Learned(null)
        return agents.firstOrNull { fold(it.name) == fold(name) }?.let { Learned(it) }
    }

    private fun byPurpose(text: String, agents: List<LeaderAgent>): LeaderAgent? {
        val words = words(text)
        val scored = agents.map { a -> a to (words intersect words(a.purpose)).size }.filter { it.second >= PURPOSE_MIN }
        val top = scored.maxByOrNull { it.second } ?: return null
        // A tie between agents is no opinion
        return top.first.takeIf { scored.count { it.second == top.second } == 1 }
    }

    private val CODE = Regex(
        "(?iu)\\b(code|c[oó]digo|bug|build|compil\\w*|repo\\w*|commit\\w*|pull request|\\bpr\\b|merge|refactor\\w*|deploy\\w*|" +
            "despleg\\w*|test(?:s|ea\\w*)?|kotlin|python|javascript|typescript|gradle|api|endpoint|stack ?trace|crash\\w*|" +
            "programa(?:r|me|lo)?|script|function|funci[oó]n|clase|class|branch|rama|git(?:hub)?|claude code)\\b"
    )

    /** Code / repository work: Claude Code's ground. */
    fun isCode(text: String): Boolean = CODE.containsMatchIn(text)

    private val COMPLEX = Regex(
        "(?iu)\\b(investiga\\w*|research|analy[sz]e|anali[zc]a\\w*|step by step|paso a paso|write (?:a|an|the) (?:report|document|essay|plan|spec)|" +
            "escribe (?:un|una|el|la) (?:informe|documento|ensayo|plan)|build (?:a|an|me)|hazme (?:un|una)|crea(?:me)? (?:un|una) (?:web|app|aplicaci[oó]n|programa)|" +
            "(?:a|an|the) (?:website|app|application)|nueva web|migra\\w*|compar[ae]\\w* .{0,40} (?:and|y) )"
    )

    /** Multi-step or long work that Lumi's small on-device brain would answer poorly. */
    fun isComplex(text: String): Boolean {
        val t = text.trim()
        val steps = Regex("(?iu)\\b(then|despu[eé]s|luego)\\b").findAll(t).count()
        return t.length > 280 || COMPLEX.containsMatchIn(t) || steps >= 2
    }

    // ── Text helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private val STOP = setOf(
        "the", "and", "for", "you", "your", "with", "that", "this", "what", "how", "can", "please", "about", "una", "uno",
        "los", "las", "del", "que", "por", "para", "con", "como", "esto", "este", "esta", "mis", "tus", "hay", "pero", "más", "mas"
    )

    internal fun words(text: String): Set<String> =
        fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
