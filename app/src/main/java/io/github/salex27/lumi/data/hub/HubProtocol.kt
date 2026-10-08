package io.github.salex27.lumi.data.hub

import io.github.salex27.lumi.data.ai.EnglishDateParser
import io.github.salex27.lumi.data.ai.SpanishDateParser
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wire format of Lumi Hub (`tools/lumi-hub/lumi_hub.py`), pure and unit-tested.
 *
 * Everything in a [HubEvent] comes from an AI agent and is UNTRUSTED: it is shown as plain text, never interpreted
 * as a command, never handed to the LLM's command interpreter, and a proposed task is only added after the user taps.
 */
@Serializable
data class HubEvent(
    val id: Long = 0,
    /** message | ask | ask_closed | task | notify | reply */
    val type: String = "",
    val source: String = "",
    val at: Long = 0,
    val text: String? = null,
    val link: String? = null,
    // ask / ask_closed
    @SerialName("ask_id") val askId: String? = null,
    val question: String? = null,
    val options: List<String> = emptyList(),
    val answered: Boolean = false,
    // task proposal / notify
    val title: String? = null,
    val due: String? = null,
    val notes: String? = null,
    // reply (wake-per-message turn of an agent, streamed)
    val thread: String? = null,
    val agent: String? = null,
    val turn: String? = null,
    val done: Boolean = false,
    val error: String? = null
) {
    companion object {
        const val MESSAGE = "message"
        const val ASK = "ask"
        const val ASK_CLOSED = "ask_closed"
        const val TASK = "task"
        const val NOTIFY = "notify"
        const val REPLY = "reply"
        /** Incremental text of a streaming turn (newer hubs): appended in order, replaced by the final reply. */
        const val TURN_DELTA = "turn_delta"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        fun parse(data: String): HubEvent? = runCatching { json.decodeFromString<HubEvent>(data) }.getOrNull()?.sanitized()
    }

    /** Defensive copy: the hub already caps sizes, but the phone does not trust the hub's agents either. */
    fun sanitized(): HubEvent = copy(
        source = clean(source, 60).ifBlank { "Agent" },
        text = text?.let { if (type == TURN_DELTA) HubSafety.stripControl(it).take(16_000) else clean(it, 16_000) },
        link = link?.takeIf { HubSafety.isSafeLink(it) },
        question = question?.let { clean(it, 1_000) },
        options = options.map { clean(it, 80) }.filter { it.isNotBlank() }.distinct().take(6),
        title = title?.let { clean(it, 200) },
        due = due?.let { clean(it, 60) },
        notes = notes?.let { clean(it, 1_000) },
        error = error?.let { clean(it, 300) }
    )

    private fun clean(s: String, max: Int): String = HubSafety.plain(s).let { if (it.length > max) it.take(max - 1) + "…" else it }
}

/** Server-Sent Events framing: feed lines, get (id, data) on each blank line. */
class SseParser {
    data class Frame(val id: Long?, val event: String?, val data: String)

    private var id: Long? = null
    private var event: String? = null
    private val data = StringBuilder()

    fun feed(line: String): Frame? {
        if (line.isEmpty()) {
            if (data.isEmpty()) { event = null; id = null; return null }
            val frame = Frame(id, event, data.toString().removeSuffix("\n"))
            data.clear(); event = null; id = null
            return frame
        }
        if (line.startsWith(":")) return null // comment / keepalive
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "id" -> id = value.toLongOrNull()
            "event" -> event = value
            "data" -> data.append(value).append('\n')
        }
        return null
    }
}

object HubSafety {
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]")

    /** Text without control characters or bidi overrides (they can disguise what a message says). */
    fun plain(s: String): String = CONTROL.replace(s, "").trim()

    /** Like [plain] but keeps the edges: a streamed chunk may start or end with the space between two words. */
    fun stripControl(s: String): String = CONTROL.replace(s, "")

    /** Only plain http(s) links without credentials can be opened from an agent message. */
    fun isSafeLink(link: String): Boolean =
        link.length <= 2_000 && Regex("^https?://[^\\s/@]+(/\\S*)?$", RegexOption.IGNORE_CASE).matches(link.trim())

    /**
     * The hub's address as typed by the user → a base URL, or null when it isn't acceptable. HTTPS always (the hub is
     * published with `tailscale serve`); plain HTTP only for the emulator's host loopback during development.
     */
    fun normalizeAddress(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:\\d{1,5})?$").matchEntire(s) ?: return null
        val (scheme, host) = m.destructured
        if (scheme.equals("http", true) && host != "10.0.2.2" && host != "127.0.0.1" && host != "localhost") return null
        return s
    }
}

/** A task an agent proposed (lumi_create_task): becomes a Task only when the user confirms it. */
object HubTaskProposal {
    /**
     * Builds the task with rules only (no LLM: the text is untrusted). The due text is parsed by the English and the
     * Spanish date parsers; if neither understands it, the task has no date and the due text goes in the description.
     */
    fun toTask(title: String, due: String?, notes: String?, source: String, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Task {
        val parsed = due?.takeIf { it.isNotBlank() }?.let { EnglishDateParser.parse(it, now) ?: SpanishDateParser.parse(it, now) }
        val description = listOfNotNull(
            notes?.takeIf { it.isNotBlank() },
            due?.takeIf { it.isNotBlank() && parsed == null }?.let { ReplyLanguage.ui("Fecha: $it", "Due: $it") },
            ReplyLanguage.ui("De $source (Lumi Hub)", "From $source (Lumi Hub)")
        ).joinToString("\n")
        return Task(
            title = HubSafety.plain(title).take(200),
            description = description,
            dueAt = parsed?.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli(),
            dueHasTime = parsed?.hasTime ?: false
        )
    }
}

/**
 * What a stored Hub message carries in `chat_messages.payload` (JSON): its kind and, for questions and task proposals,
 * their state, so the chat can show "Answered" / "Added" and nothing is acted on twice.
 */
@Serializable
data class HubPayload(
    /** message | ask | task | notify | reply | route */
    val hub: String,
    val link: String? = null,
    @SerialName("ask_id") val askId: String? = null,
    val options: List<String> = emptyList(),
    /** open | answered | closed (ask); open | added | dismissed (task) */
    val state: String = STATE_OPEN,
    val answer: String? = null,
    val title: String? = null,
    val due: String? = null,
    val notes: String? = null,
    val source: String? = null,
    /** Agent reply in a thread: its turn id (streamed updates go to the same message). */
    val turn: String? = null,
    // Orbit leader ("route"): the agent Lumi picked, the choices offered (agent ids, 0 = Lumi) and the question
    val routeTo: Long? = null,
    val choices: List<Long> = emptyList(),
    val question: String? = null
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        const val STATE_OPEN = "open"
        const val STATE_ANSWERED = "answered"
        const val STATE_CLOSED = "closed"
        const val STATE_ADDED = "added"
        const val STATE_DISMISSED = "dismissed"
        /** Orbit leader message (proposal or notice of a hand-off). */
        const val ROUTE = "route"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun decode(text: String?): HubPayload? = text?.let { runCatching { json.decodeFromString<HubPayload>(it) }.getOrNull() }

        /** LIKE fragment that finds the message of a question (ask ids are hex, safe inside LIKE). */
        fun askFragment(askId: String) = "\"ask_id\":\"$askId\""

        fun turnFragment(turn: String) = "\"turn\":\"$turn\""

        /** The payload of a Hub event as stored with its message, or null for events that aren't stored. */
        fun of(e: HubEvent): HubPayload? = when (e.type) {
            HubEvent.MESSAGE -> HubPayload(HubEvent.MESSAGE, link = e.link, source = e.source)
            HubEvent.ASK -> HubPayload(HubEvent.ASK, askId = e.askId, options = e.options, source = e.source)
            HubEvent.TASK -> HubPayload(HubEvent.TASK, title = e.title, due = e.due, notes = e.notes, source = e.source)
            HubEvent.NOTIFY -> HubPayload(HubEvent.NOTIFY, title = e.title, source = e.source)
            else -> null
        }
    }
}

/** One selectable model of `GET /agents/options`. */
data class AgentModel(val id: String, val label: String)

/** Models, reasoning levels and the hub's defaults. Parsed defensively: the hub's text is untrusted. */
data class AgentOptions(val models: List<AgentModel>, val efforts: List<String>, val defaultModel: String?, val defaultEffort: String?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val ID = Regex("^[A-Za-z0-9._:\\-\\[\\]]{1,80}$")

        /** Throws on a body that is not JSON; returns options with no models/efforts as an empty picker. */
        fun parse(body: String): AgentOptions {
            val o = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: throw IllegalArgumentException("not an object")
            fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.content
            val models = (o["models"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { m ->
                val mo = m as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = str(mo["id"])?.trim()?.takeIf { ID.matches(it) } ?: return@mapNotNull null
                AgentModel(id, HubSafety.plain(str(mo["label"]).orEmpty()).take(40).ifBlank { id })
            }.distinctBy { it.id }.take(20)
            val efforts = (o["efforts"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { str(it)?.trim()?.takeIf { e -> ID.matches(e) } }.distinct().take(10)
            val d = o["defaults"] as? kotlinx.serialization.json.JsonObject
            return AgentOptions(models, efforts, str(d?.get("model"))?.takeIf { m -> models.any { it.id == m } }, str(d?.get("effort"))?.takeIf { it in efforts })
        }

        private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}

/** A chosen model and/or reasoning level; null = the hub's default. */
data class ModelChoice(val model: String? = null, val effort: String? = null) {
    /** Drops what the hub no longer offers (a model retired since it was saved). */
    fun validated(options: AgentOptions?): ModelChoice = if (options == null) ModelChoice()
        else ModelChoice(model?.takeIf { m -> options.models.any { it.id == m } }, effort?.takeIf { it in options.efforts })

    companion object {
        /**
         * The fast, cheap end of what the hub offers (PC brain: interpretation and phrasing only): the lightest model
         * by name (haiku, then sonnet) and the lowest reasoning level. Empty on an older hub (nothing is sent).
         */
        fun fast(options: AgentOptions?): ModelChoice {
            if (options == null) return ModelChoice()
            val model = listOf("haiku", "sonnet").firstNotNullOfOrNull { k -> options.models.firstOrNull { it.id.contains(k, ignoreCase = true) } }
            val effort = listOf("low", "minimal").firstOrNull { it in options.efforts }
            return ModelChoice(model?.id, effort)
        }

        /** Thread choice wins field by field over the settings default. */
        fun resolve(thread: ModelChoice, default: ModelChoice, options: AgentOptions?): ModelChoice {
            if (options == null) return ModelChoice() // older hub: send nothing
            val t = thread.validated(options); val d = default.validated(options)
            return ModelChoice(t.model ?: d.model, t.effort ?: d.effort)
        }
    }
}
