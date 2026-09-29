package io.github.salex27.lumi.domain.assistant

/**
 * Actions on the phone that Lumi can launch (standard Android intents, no special permissions except contacts to call
 * or text someone by name). Executed by the UI (it needs an Activity).
 */
sealed interface DeviceCommand {
    data class OpenApp(val name: String) : DeviceCommand
    data class Alarm(val hour: Int, val minute: Int, val label: String?) : DeviceCommand
    data class Timer(val seconds: Int, val label: String?) : DeviceCommand
    data class Call(val contact: String) : DeviceCommand
    data class Message(val contact: String, val text: String, val whatsapp: Boolean) : DeviceCommand
    data class PlayMusic(val query: String, val app: String?) : DeviceCommand
    data class WebSearch(val query: String) : DeviceCommand
    data class OpenSettings(val panel: SettingsPanel) : DeviceCommand
    data class Flashlight(val on: Boolean) : DeviceCommand
    /** Do Not Disturb (needs the "Do Not Disturb" access granted once). */
    data class DoNotDisturb(val on: Boolean) : DeviceCommand
    /** Reply to a message from its notification without opening the app (like Android Auto). */
    data class ReplyMessage(val key: String, val contact: String, val text: String, val app: String) : DeviceCommand

    enum class SettingsPanel(private val es: String, private val en: String) {
        WIFI("el wifi", "Wi-Fi"), BLUETOOTH("el Bluetooth", "Bluetooth"), VOLUME("el volumen", "volume"), GENERAL("los ajustes", "settings"),
        NOTIFICATION_ACCESS("el acceso a notificaciones", "notification access"), DND_ACCESS("el acceso a No molestar", "Do Not Disturb access");

        val label: String get() = ReplyLanguage.t(es, en)
    }

    /** Stays in Lumi (doesn't open another app): in a routine these go first. */
    val staysInLumi: Boolean get() = this is Alarm || this is Timer || this is Flashlight || this is DoNotDisturb || this is ReplyMessage

    /** Stable key (stored in TaskAICommand.device) → "OPEN_APP|Spotify". */
    fun serialize(): String = when (this) {
        is OpenApp -> "OPEN_APP|$name"
        is Alarm -> "ALARM|$hour|$minute|${label.orEmpty()}"
        is Timer -> "TIMER|$seconds|${label.orEmpty()}"
        is Call -> "CALL|$contact"
        is Message -> "MESSAGE|$contact|$whatsapp|$text"
        is PlayMusic -> "MUSIC|${app.orEmpty()}|$query"
        is WebSearch -> "SEARCH|$query"
        is OpenSettings -> "SETTINGS|${panel.name}"
        is Flashlight -> "FLASHLIGHT|$on"
        is DoNotDisturb -> "DND|$on"
        // A notification key contains "|" (0|com.whatsapp|1|…), so it is URL-encoded
        is ReplyMessage -> "REPLY|${enc(key)}|${enc(contact)}|${enc(app)}|$text"
    }

    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = java.net.URLDecoder.decode(s, "UTF-8")

        fun parse(value: String?): DeviceCommand? {
            val p = value?.split('|') ?: return null
            return runCatching {
                when (p[0]) {
                    "OPEN_APP" -> OpenApp(p[1])
                    "ALARM" -> Alarm(p[1].toInt(), p[2].toInt(), p.getOrNull(3)?.ifBlank { null })
                    "TIMER" -> Timer(p[1].toInt(), p.getOrNull(2)?.ifBlank { null })
                    "CALL" -> Call(p[1])
                    "MESSAGE" -> Message(p[1], p.drop(3).joinToString("|"), p[2].toBoolean())
                    "MUSIC" -> PlayMusic(p.drop(2).joinToString("|"), p[1].ifBlank { null })
                    "SEARCH" -> WebSearch(p.drop(1).joinToString("|"))
                    "SETTINGS" -> OpenSettings(SettingsPanel.valueOf(p[1]))
                    "FLASHLIGHT" -> Flashlight(p[1].toBoolean())
                    "DND" -> DoNotDisturb(p[1].toBoolean())
                    "REPLY" -> ReplyMessage(dec(p[1]), dec(p[2]), p.drop(4).joinToString("|"), dec(p[3]))
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** Recognizes phone commands in Spanish (pure, tested). Returns null when the sentence is not one of them. English: [EnglishCommands]. */
object DeviceCommandParser {

    private const val I = "(?iu)"
    private val NUM = mapOf(
        "un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8,
        "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "quince" to 15, "veinte" to 20, "treinta" to 30, "media" to 30
    )

    fun parse(input: String): DeviceCommand? {
        val t = input.trim().trimEnd('.', '!', '?').replace(Regex("^¿"), "").trim()
        return timer(t) ?: alarm(t) ?: flashlight(t) ?: doNotDisturb(t) ?: settings(t) ?: message(t) ?: call(t) ?: music(t) ?: search(t) ?: openApp(t)
    }

    private fun num(s: String): Int? = s.toIntOrNull() ?: NUM[s.lowercase()]

    private fun timer(t: String): DeviceCommand? {
        val m = Regex("$I^(?:pon(?:me)?|programa|inicia|crea)?\\s*(?:un\\s+)?(?:temporizador|cron[oó]metro|cuenta\\s+atr[aá]s)\\s+(?:de\\s+)?(\\d+|\\p{L}+)\\s+(segundos?|minutos?|mins?|horas?)(?:\\s+(?:para|de)\\s+(.+))?$").find(t)
            ?: Regex("$I^avísame\\s+en\\s+(\\d+|\\p{L}+)\\s+(segundos?|minutos?|mins?|horas?)(?:\\s+(?:para|de)\\s+(.+))?$").find(t)
            ?: return null
        val n = num(m.groupValues[1]) ?: return null
        val unit = m.groupValues[2].lowercase()
        val seconds = when {
            unit.startsWith("seg") -> n
            unit.startsWith("h") -> n * 3600
            else -> n * 60
        }
        return DeviceCommand.Timer(seconds, m.groupValues[3].ifBlank { null })
    }

    private fun alarm(t: String): DeviceCommand? {
        val m = Regex("$I^(?:pon(?:me)?|programa|crea|ponme)\\s+(?:una\\s+)?alarma\\s+(?:para\\s+)?(?:a\\s+)?las?\\s+(\\d{1,2}|\\p{L}+)(?::(\\d{2})|\\s+y\\s+(media|cuarto))?(?:\\s+de\\s+la\\s+(mañana|tarde|noche))?(?:\\s+para\\s+(.+))?$").find(t)
            ?: return null
        var h = num(m.groupValues[1]) ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: when (m.groupValues[3]) { "media" -> 30; "cuarto" -> 15; else -> 0 }
        val part = m.groupValues[4].lowercase()
        if ((part == "tarde" || part == "noche") && h < 12) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        return DeviceCommand.Alarm(h, min, m.groupValues[5].ifBlank { null })
    }

    private fun flashlight(t: String): DeviceCommand? {
        if (Regex("$I^(?:enciende|activa|pon)\\s+(?:la\\s+)?linterna$").matches(t)) return DeviceCommand.Flashlight(true)
        if (Regex("$I^(?:apaga|desactiva|quita)\\s+(?:la\\s+)?linterna$").matches(t)) return DeviceCommand.Flashlight(false)
        return null
    }

    private fun doNotDisturb(t: String): DeviceCommand? {
        val target = "(?:el\\s+)?(?:modo\\s+)?(?:no\\s+molestar|silencio(?:so)?|descanso)"
        if (Regex("$I^(?:activa|pon(?:me)?|enciende|entra\\s+en)\\s+$target$").matches(t) || Regex("$I^(?:silencia\\s+el\\s+m[oó]vil|modo\\s+no\\s+molestar)$").matches(t)) return DeviceCommand.DoNotDisturb(true)
        if (Regex("$I^(?:desactiva|quita|apaga|sal\\s+de)\\s+$target$").matches(t)) return DeviceCommand.DoNotDisturb(false)
        return null
    }

    private fun settings(t: String): DeviceCommand? {
        val m = Regex("$I^(?:abre|activa|desactiva|muestra|ajusta|cambia)\\s+(?:los\\s+ajustes\\s+de(?:l)?\\s+|el\\s+|la\\s+)?(wifi|wi-fi|bluetooth|volumen|ajustes|configuraci[oó]n)$").find(t) ?: return null
        val panel = when (m.groupValues[1].lowercase().replace("-", "")) {
            "wifi" -> DeviceCommand.SettingsPanel.WIFI
            "bluetooth" -> DeviceCommand.SettingsPanel.BLUETOOTH
            "volumen" -> DeviceCommand.SettingsPanel.VOLUME
            else -> DeviceCommand.SettingsPanel.GENERAL
        }
        return DeviceCommand.OpenSettings(panel)
    }

    // ── Messages (before, only "envíale un WhatsApp a X diciendo…" worked and everything else became a task) ──

    private const val SEND = "(?:env[ií]a(?:le|me)?|enviar(?:le)?|m[aá]nda(?:le|me)?|mandar(?:le)?|escr[ií]be(?:le)?|escribir(?:le)?|contesta(?:le)?|responde(?:le)?|p[oó]nle)"
    /** "Recuérdame enviar un WhatsApp a Ana mañana" (remind me to text Ana tomorrow) is a TASK for later, not a send now. */
    private val LATER = Regex(
        "(?iu)^(?:recu[eé]rda(?:me|le)?|tengo\\s+que|hay\\s+que|debo|apunta|a[ñn]ade|no\\s+(?:me\\s+)?olvides)|" +
            "\\b(?:mañana|pasado\\s+mañana|luego|despu[eé]s|esta\\s+(?:tarde|noche)|el\\s+(?:lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)|a\\s+las\\s+\\d)\\b|" +
            "\\b(?:cuando|en\\s+cuanto)\\s+(?:llegue|vuelva|regrese|salga|est[eé])\\b|\\bal\\s+(?:llegar|salir|volver)\\b"
    )
    private const val CHANNEL = "(whatsapp|whats\\s*app|wasap|guasap|wassap|mensaje|mensajito|sms)"
    private const val SAYING = "(?:diciendo(?:le)?|dici[eé]ndole|que\\s+dice|que\\s+diga|que\\s+ponga|y\\s+dile|y\\s+ponle|con\\s+el\\s+texto|:|,|\\s+que(?=\\s))"

    /**
     * Message intent: names a channel (WhatsApp, mensaje, SMS) + a send/write verb, or "dile a X que…" (tell X that…).
     * When the intent is clear but recipient and text can't be separated, an incomplete Message is returned and the
     * repository completes it with the LLM (or asks the user). It must never end up as a task.
     */
    fun looksLikeMessage(t: String): Boolean = !LATER.containsMatchIn(t) && (
        Regex("$I\\b$CHANNEL\\b").containsMatchIn(t) && Regex("$I\\b(?:$SEND|dile|d[ií]gale|escr[ií]bele)\\b").containsMatchIn(t) ||
            Regex("$I^d[ií](?:le|gale)\\s+a\\s+\\p{L}").containsMatchIn(t))

    private fun message(t: String): DeviceCommand? {
        // For later ("recuérdame…", "mañana…") → nothing is sent: the task creator handles it
        if (Regex("(?iu)^(?:recu[eé]rda(?:me|le)?|tengo\\s+que|hay\\s+que|debo|apunta|a[ñn]ade|no\\s+(?:me\\s+)?olvides)").containsMatchIn(t)) return null
        fun channelIsWhatsapp(c: String) = !Regex("$I^(?:mensaje|mensajito|sms)$").matches(c.trim()) || Regex("$I\\bwhats|wasap|guasap").containsMatchIn(t)
        fun clean(m: String) = m.trim().removePrefix("que ").trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        val patterns = listOf(
            // "envíale un WhatsApp a Víctor diciendo que llego tarde", "manda un mensaje a mamá: ya estoy"
            Regex("$I^$SEND\\s+(?:un\\s+|una\\s+)?$CHANNEL\\s+(?:por\\s+whatsapp\\s+)?a\\s+(.+?)\\s*$SAYING\\s*(?:que\\s+)?(.+)$") to { m: MatchResult ->
                DeviceCommand.Message(m.groupValues[2].trim(), clean(m.groupValues[3]), channelIsWhatsapp(m.groupValues[1]))
            },
            // "escríbele a Víctor por WhatsApp que llego tarde", "manda a Ana un mensaje diciendo…"
            Regex("$I^$SEND\\s+a\\s+(.+?)\\s+(?:por|en|un|una)\\s+$CHANNEL\\s*$SAYING?\\s*(?:que\\s+)?(.+)$") to { m: MatchResult ->
                DeviceCommand.Message(m.groupValues[1].trim(), clean(m.groupValues[3]), channelIsWhatsapp(m.groupValues[2]))
            },
            // "dile a Víctor (por WhatsApp) que llego en 10 minutos" (tell Víctor I'll be there in 10)
            Regex("$I^d[ií](?:le|gale)\\s+a\\s+(.+?)\\s+(?:por\\s+$CHANNEL\\s+)?que\\s+(.+)$") to { m: MatchResult ->
                DeviceCommand.Message(m.groupValues[1].trim(), clean(m.groupValues[3]), m.groupValues[2].isBlank() || channelIsWhatsapp(m.groupValues[2]))
            },
            // "envía un WhatsApp a Víctor" (no text: Lumi asks for it)
            Regex("$I^$SEND\\s+(?:un\\s+|una\\s+)?$CHANNEL\\s+a\\s+(.+)$") to { m: MatchResult ->
                DeviceCommand.Message(m.groupValues[2].trim(), "", channelIsWhatsapp(m.groupValues[1]))
            }
        )
        for ((regex, build) in patterns) regex.find(t)?.let { m ->
            val msg = build(m)
            // "enviar un WhatsApp a Ana mañana": a date on the COMMAND means a task for later; inside the text it doesn't matter
            // "…que ya estoy cuando llegue a casa": sent on arrival → it is a task with a place reminder
            val onPlace = Regex("(?iu)\\b(?:cuando|en\\s+cuanto)\\s+(?:llegue|vuelva|regrese|salga)\\b|\\bal\\s+(?:llegar|salir|volver)\\b").containsMatchIn(msg.text)
            return if (LATER.containsMatchIn(msg.contact) || onPlace) null else msg
        }
        // Clear intent but an odd sentence → incomplete: the LLM completes it or Lumi asks
        return if (looksLikeMessage(t)) DeviceCommand.Message("", "", !Regex("$I\\bsms\\b").containsMatchIn(t)) else null
    }

    private fun call(t: String): DeviceCommand? {
        // Only the imperative "llama a…": "llamar al banco" (infinitive) is a task and "marca el informe…" is a priority change
        val m = Regex("$I^(?:ll[aá]ma(?:le)?|telefonea)\\s+(?:a\\s+)?(.+)$").find(t) ?: return null
        val who = m.groupValues[1].trim()
        // "llama al banco mañana" is a task, not a call now
        if (Regex("$I\\b(?:mañana|luego|después|esta\\s+tarde|el\\s+lunes|el\\s+martes|el\\s+mi[eé]rcoles|el\\s+jueves|el\\s+viernes|a\\s+las)\\b").containsMatchIn(who)) return null
        return DeviceCommand.Call(who)
    }

    private fun music(t: String): DeviceCommand? {
        val m = Regex("$I^(?:pon(?:me)?|reproduce|escuchar?|quiero\\s+escuchar)\\s+(?:m[uú]sica\\s+de\\s+|la\\s+canci[oó]n\\s+|el\\s+disco\\s+|algo\\s+de\\s+)?(.+?)(?:\\s+en\\s+(spotify|youtube\\s+music|youtube|amazon\\s+music|deezer|apple\\s+music))?$").find(t)
            ?: return null
        val query = m.groupValues[1].trim()
        // "pon la reunión a las 5" / "pon el informe como urgente" are not music
        if (!Regex("$I^(?:pon(?:me)?\\s+)?m[uú]sica|^(?:reproduce|escucha|quiero\\s+escuchar)").containsMatchIn(t) && m.groupValues[2].isBlank()) return null
        return DeviceCommand.PlayMusic(if (query.equals("música", true) || query.equals("musica", true)) "" else query, m.groupValues[2].ifBlank { null })
    }

    private fun search(t: String): DeviceCommand? {
        val m = Regex("$I^(?:busca|b[uú]scame|googlea|investiga)\\s+(?:en\\s+(?:google|internet)\\s+)?(.+?)(?:\\s+en\\s+(?:google|internet))?$").find(t) ?: return null
        return DeviceCommand.WebSearch(m.groupValues[1].trim())
    }

    private fun openApp(t: String): DeviceCommand? {
        val m = Regex("$I^(?:abre|abrir|lanza|inicia|ábreme|abreme)\\s+(?:la\\s+app\\s+(?:de\\s+)?|la\\s+aplicaci[oó]n\\s+(?:de\\s+)?)?(.+)$").find(t) ?: return null
        return DeviceCommand.OpenApp(m.groupValues[1].trim())
    }
}
