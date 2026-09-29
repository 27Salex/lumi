package io.github.salex27.lumi.domain.assistant

import java.text.Normalizer

/** Un mensaje sin leer que llegó como notificación (WhatsApp, Telegram, SMS, correo…). Solo vive en memoria. */
data class IncomingMessage(
    /** Clave de la notificación (para responder desde Lumi). */
    val key: String,
    val packageName: String,
    val app: String,
    /** Chat o grupo; en chats individuales coincide con [sender]. */
    val conversation: String,
    val sender: String,
    val text: String,
    val time: Long,
    val isGroup: Boolean = false,
    /** La notificación trae «Responder» → Lumi puede contestar sin abrir la app. */
    val canReply: Boolean = false
)

/** Resumen de mensajes (puro, testeado). La IA, si hay, lo redacta; si no, se lee esto. */
object MessageDigest {

    /** Mensajes de [who] (sin tildes ni mayúsculas; vale una parte del nombre). Vacío = todos. */
    fun filter(messages: List<IncomingMessage>, who: String): List<IncomingMessage> {
        val q = plain(who)
        if (q.isBlank()) return messages
        return messages.filter { m -> plain(m.sender).contains(q) || plain(m.conversation).contains(q) || plain(m.sender).split(" ").any { it.isNotBlank() && q.startsWith(it) } }
    }

    /** Conversaciones en orden (la más reciente primero). */
    fun conversations(messages: List<IncomingMessage>): List<Pair<String, List<IncomingMessage>>> =
        messages.groupBy { "${it.app}|${it.conversation}" }.values
            .map { list -> list.first().conversation to list.sortedBy { it.time } }
            .sortedByDescending { (_, list) -> list.last().time }

    fun compose(messages: List<IncomingMessage>, who: String = ""): String {
        if (messages.isEmpty()) return if (who.isBlank()) "No tienes mensajes sin leer." else "No tienes mensajes sin leer de $who."
        val convs = conversations(messages)
        return buildString {
            append(if (messages.size == 1) "Tienes 1 mensaje" else "Tienes ${messages.size} mensajes")
            if (convs.size > 1) append(" de ${convs.size} conversaciones")
            append(". ")
            convs.take(5).forEach { (name, list) ->
                val via = list.first().app.takeIf { !it.equals("WhatsApp", true) }?.let { " ($it)" } ?: ""
                append(name).append(via)
                if (list.size > 1) append(" (${list.size})")
                append(": ")
                append(list.takeLast(2).joinToString(" ") { m ->
                    val prefix = if (m.isGroup && m.sender != name) "${m.sender.substringBefore(' ')}: " else ""
                    "«$prefix${m.text.take(90).trim()}${if (m.text.length > 90) "…" else ""}»"
                })
                append(". ")
            }
            if (convs.size > 5) append("Y ${convs.size - 5} conversaciones más.")
        }.trim()
    }

    /** A quién se podría responder ahora (una sola conversación individual). */
    fun replyTarget(messages: List<IncomingMessage>): IncomingMessage? {
        val convs = conversations(messages)
        if (convs.size != 1) return null
        return convs.first().second.lastOrNull()?.takeIf { !it.isGroup }
    }

    fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Rutina: una frase («buenas noches») que lanza varias órdenes de Lumi seguidas. Cada paso es una frase normal
 * («alarma inteligente», «activa no molestar», «llévame a casa»), así el usuario las crea sin aprender nada nuevo.
 */
@kotlinx.serialization.Serializable
data class Routine(
    val id: String,
    val name: String,
    val triggers: List<String>,
    val steps: List<String>,
    val enabled: Boolean = true
)

object RoutineMatcher {

    /** Rutina cuya frase coincide (sin tildes, signos, «oye lumi» ni «lumi» al final). */
    fun match(text: String, routines: List<Routine>): Routine? {
        val t = normalize(text)
        if (t.isBlank()) return null
        return routines.filter { it.enabled }.firstOrNull { r -> r.triggers.any { normalize(it) == t } }
    }

    fun normalize(s: String): String = MessageDigest.plain(s)
        .replace(Regex("^(?:oye\\s+|hola\\s+)?lumi\\s+"), "")
        .replace(Regex("\\s+lumi$"), "")
        .trim()

    val DEFAULTS = listOf(
        Routine(
            "night", "Buenas noches",
            listOf("buenas noches", "me voy a dormir", "me voy a la cama", "hasta mañana"),
            listOf("alarma inteligente", "activa no molestar", "qué tengo mañana")
        ),
        Routine(
            "morning", "Buenos días",
            listOf("buenos días", "buen día", "ya estoy despierto", "ya estoy despierta", "ya me he levantado"),
            listOf("desactiva no molestar", "resumen de hoy")
        ),
        Routine(
            "home", "Me voy a casa",
            listOf("me voy a casa", "vuelvo a casa", "voy para casa", "me voy para casa"),
            listOf("llévame a casa")
        ),
        Routine(
            "work", "Me voy al trabajo",
            listOf("me voy al trabajo", "me voy a trabajar", "voy al trabajo", "voy a trabajar"),
            listOf("qué tiempo hace", "llévame al trabajo")
        )
    )
}
