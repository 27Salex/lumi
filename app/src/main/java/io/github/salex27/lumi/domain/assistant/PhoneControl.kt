package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.data.ai.EnglishDateParser
import io.github.salex27.lumi.data.ai.SpanishDateParser
import java.time.LocalDateTime
import io.github.salex27.lumi.domain.assistant.DeviceCommand as D

/**
 * Phone control in Spanish AND English in one place (pure, tested): alarm sets, bedtime reminder, volume, brightness,
 * media keys, battery / storage / status, calendar event, stopwatch, share location and more settings panels.
 * Tolerant of accents, case and small typos ("alarmass", "bateira").
 */
object PhoneControl {

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): D? {
        val raw = input.trim().trimEnd('.', '!', '?').trim()
        val n = Say.plain(raw)
        if (n.isBlank()) return null
        if (Cancel.looksLikeCancel(n)) return D.CancelAlarms
        BedtimeParser.parse(raw)?.let { return D.BedtimeReminder(it.minutes, it.days) }
        AlarmSetParser.parse(raw)?.let { return D.AlarmSet(it.times, it.snooze) }
        // Reminders and tasks for later never trigger the phone controls below
        if (Regex("\\b(recuerd\\w*|remind\\w*|apunta\\w*|tarea|task)\\b").containsMatchIn(n)) return null
        return calendarEvent(raw, n, now) ?: volume(n) ?: brightness(n) ?: media(n) ?: info(n) ?: shareLocation(n) ?: stopwatch(n) ?: panel(n)
    }

    private const val LEVEL = "(?:volumen|volume|sonido|sound)"

    private fun percentOf(n: String): Int? = Regex("(\\d{1,3})\\s*(?:%|por ?ciento|percent)?").findAll(n).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 0..100 }

    private fun volume(n: String): D? {
        val up = Regex("\\b(sube|subir|subele|aumenta\\w*|turn up|raise|increase|louder|mas alto|volume up)\\b").containsMatchIn(n)
        val down = Regex("\\b(baja|bajar|bajale|reduce|reducir|disminuye|turn down|lower|decrease|quieter|mas bajo|volume down)\\b").containsMatchIn(n)
        val hasLevel = Say.has(n, "volumen", "volume", "sonido", "sound") || Regex("\\b$LEVEL\\b").containsMatchIn(n)
        if (hasLevel) {
            if (Regex("\\b(maximo|max|maximum|full|al maximo|a tope)\\b").containsMatchIn(n)) return D.Volume(D.VolumeAction.SET, 100)
            if (Regex("\\b(minimo|minimum|lowest)\\b").containsMatchIn(n)) return D.Volume(D.VolumeAction.SET, 10)
            Regex("\\b(?:al?|to|at)\\s+(\\d{1,3})\\b").find(n)?.let { m ->
                if (m.groupValues[1].toInt() in 0..100 && !up && !down) return D.Volume(D.VolumeAction.SET, m.groupValues[1].toInt())
            }
            if (up && !down) return D.Volume(D.VolumeAction.UP, null)
            if (down && !up) return D.Volume(D.VolumeAction.DOWN, null)
        }
        if (Regex("^(?:volume|volumen)\\s+(?:up|alto)$").matches(n)) return D.Volume(D.VolumeAction.UP, null)
        if (Regex("^(?:turn it up|mas alto|louder)$").matches(n)) return D.Volume(D.VolumeAction.UP, null)
        if (Regex("^(?:turn it down|mas bajo|quieter)$").matches(n)) return D.Volume(D.VolumeAction.DOWN, null)
        val phone = "(?:movil|telefono|phone|celular)"
        if (Regex("\\b(?:unmute|desmutea|desilencia|quita el mute|activa el sonido|reactiva el sonido|enciende el sonido)\\b").containsMatchIn(n)) return D.Volume(D.VolumeAction.UNMUTE, null)
        if (Regex("^(?:silencia|mutea|mute|quita)\\b.*\\b(?:volumen|volume|sonido|sound|musica|music|media)\\b").containsMatchIn(n) || Regex("^(?:mute|silencia\\w*)$").matches(n) ||
            Regex("^quita el sonido$").matches(n)) return D.Volume(D.VolumeAction.MUTE, null)
        if (Regex("\\b(?:pon|ponlo|ponme|mete|set|put|switch|activa)\\b.*\\b$phone\\b.*\\b(?:en silencio|silent|silenciad\\w*)\\b").containsMatchIn(n) ||
            Regex("^(?:pon|set|put)\\b.*\\b(?:ringer|timbre)\\b.*\\b(?:silent|silencio)\\b").containsMatchIn(n)) return D.Volume(D.VolumeAction.SILENT, null)
        if (Regex("\\b(?:vibracion|vibrar|vibrate|vibration)\\b").containsMatchIn(n) && Regex("^(?:pon\\w*|activa|mete|set|put|switch|enable|turn on|modo)\\b").containsMatchIn(n)) return D.Volume(D.VolumeAction.VIBRATE, null)
        if (Regex("^(?:pon|activa|quita el silencio|vuelve a|set|turn on|switch to)\\b.*\\b(?:timbre|ringer|sonido normal|modo sonido|sound mode|normal mode|modo normal)\\b").containsMatchIn(n) ||
            Regex("^(?:quita el silencio|ringer on|sound on)$").matches(n)) return D.Volume(D.VolumeAction.RINGER, null)
        return null
    }

    private fun brightness(n: String): D? {
        val noun = Say.has(n, "brillo", "brightness", "luminosidad") || Regex("\\b(?:oscurece|dim|illuminate|aclara)\\b").containsMatchIn(n) && Say.has(n, "pantalla", "screen")
        if (!noun) return null
        if (Regex("\\b(?:maximo|max|maximum|full|a tope)\\b").containsMatchIn(n)) return D.Brightness(D.VolumeAction.SET, 100)
        if (Regex("\\b(?:minimo|minimum|lowest)\\b").containsMatchIn(n)) return D.Brightness(D.VolumeAction.SET, 5)
        if (Regex("\\b(?:al?|to|at)\\s+(\\d{1,3})\\b").containsMatchIn(n) || Regex("\\b(\\d{1,3})\\s*(?:%|por ?ciento|percent)").containsMatchIn(n)) {
            return percentOf(n)?.let { D.Brightness(D.VolumeAction.SET, it) }
        }
        val down = Regex("\\b(baja\\w*|bajar|reduce|disminuye|oscurece|dim|turn down|lower|decrease|menos)\\b").containsMatchIn(n)
        val up = Regex("\\b(sube\\w*|subir|aumenta\\w*|turn up|raise|increase|brighter|mas|aclara)\\b").containsMatchIn(n)
        return when { down && !up -> D.Brightness(D.VolumeAction.DOWN, null); up && !down -> D.Brightness(D.VolumeAction.UP, null); else -> null }
    }

    private fun media(n: String): D? {
        val obj = "(?:\\s+(?:la|el|the))?(?:\\s+(?:musica|cancion|tema|pista|reproduccion|video|music|song|track|playback|podcast))?"
        return when {
            Regex("^(?:por favor )?(?:pausa\\w*|pausar|pause|detener|stop|para)$obj$").matches(n) && n != "para" && n != "stop" -> D.Media(D.MediaAction.PAUSE)
            Regex("^(?:para|stop|detener|detiene)\\s+(?:la\\s+|the\\s+)?(?:musica|cancion|reproduccion|music|song|playback)$").matches(n) -> D.Media(D.MediaAction.PAUSE)
            Regex("^(?:reanuda\\w*|continua\\w*|sigue|resume|play|reproduce|continue|unpause)$obj$").matches(n) -> D.Media(D.MediaAction.PLAY)
            Regex("^(?:siguiente|proxima|proximo|pasa|salta\\w*|next|skip)$obj(?:\\s+(?:siguiente|proxima|please))?$").matches(n) ||
                Regex("^(?:pon|play)\\s+(?:la\\s+|the\\s+)?(?:siguiente|proxima|next)\\s+\\w+$").matches(n) -> D.Media(D.MediaAction.NEXT)
            Regex("^(?:cancion|tema|pista|song|track)\\s+(?:anterior|previous|before)$").matches(n) ||
                Regex("^(?:anterior|previous|vuelve a la anterior|go back)$obj$").matches(n) ||
                Regex("^(?:pon|play)\\s+(?:la\\s+|the\\s+)?(?:anterior|previous)\\s+\\w+$").matches(n) -> D.Media(D.MediaAction.PREVIOUS)
            else -> null
        }
    }

    private fun info(n: String): D? {
        val question = Regex("^(?:cuanta|cuanto|cuantos|que|como|nivel|estado|dime|dame|mira|ver|how|what|whats|is|check|show|tell|battery|bateria|storage|phone|my phone)\\b").containsMatchIn(n) ||
            Regex("^(?:esta|estoy)\\b").containsMatchIn(n)
        if (!question) return null
        val phone = "(?:movil|telefono|phone|celular)"
        if (Say.has(n, "bateria", "battery") || Regex("\\b(?:cargando|cargandose|charging|enchufado|plugged)\\b").containsMatchIn(n)) return D.PhoneInfo(D.InfoKind.BATTERY)
        if (Say.has(n, "almacenamiento", "storage") || Regex("\\b(?:espacio (?:libre|disponible|me queda|queda|tengo)|free space|disk space|memoria interna|how much space|cuanto espacio)\\b").containsMatchIn(n)) return D.PhoneInfo(D.InfoKind.STORAGE)
        if (Regex("\\b(?:haciendo|doing|status|estado)\\b").containsMatchIn(n) && Regex("\\b$phone\\b").containsMatchIn(n) ||
            Regex("^(?:como esta mi $phone|how is my $phone)$").matches(n)) return D.PhoneInfo(D.InfoKind.STATUS)
        return null
    }

    private fun shareLocation(n: String): D? {
        val verb = Regex("^(?:comparte\\w*|compartir|manda\\w*|envia\\w*|share|send|dame)\\b").containsMatchIn(n)
        return if (verb && Say.has(n, "ubicacion", "localizacion", "posicion", "location")) D.ShareLocation(false) else null
    }

    private fun stopwatch(n: String): D? =
        if (Say.has(n, "cronometro", "stopwatch") && !Regex("\\d").containsMatchIn(n) && Regex("^(?:pon\\w*|inicia\\w*|abre|arranca|empieza|start|open|set|launch|begin)\\b").containsMatchIn(n)) D.Stopwatch else null

    private fun panel(n: String): D? {
        if (!Regex("^(?:por favor |please |lumi )?(?:abre\\w*|abrir|activa\\w*|desactiva\\w*|enciende\\w*|apaga\\w*|muestra\\w*|ajusta\\w*|cambia\\w*|conecta\\w*|desconecta\\w*|ve a|open|turn|switch|enable|disable|show|toggle|connect|disconnect|go to)\\b").containsMatchIn(n)) return null
        fun any(vararg w: String) = Say.has(n, *w)
        val p = when {
            any("avion", "airplane", "aeroplane") || Regex("\\b(?:modo vuelo|flight mode|airplane mode)\\b").containsMatchIn(n) -> D.SettingsPanel.AIRPLANE
            any("hotspot") || Regex("\\b(?:punto de acceso|zona wifi|zona wi fi|compartir internet|compartir datos|anclaje|tethering)\\b").containsMatchIn(n) -> D.SettingsPanel.HOTSPOT
            any("ubicacion", "gps", "location") -> D.SettingsPanel.LOCATION
            any("nfc") -> D.SettingsPanel.NFC
            Regex("\\b(?:datos moviles|mobile data|cellular|datos)\\b").containsMatchIn(n) -> D.SettingsPanel.MOBILE_DATA
            Regex("\\bwi ?fi\\b").containsMatchIn(n) || any("wifi") -> D.SettingsPanel.WIFI
            any("bluetooth", "blutu") -> D.SettingsPanel.BLUETOOTH
            else -> return null
        }
        return D.OpenSettings(p)
    }

    // ── Calendar event ("crea un evento mañana a las 5 con Ana", "add an event to my calendar tomorrow at 3") ──

    private fun calendarEvent(raw: String, n: String, now: LocalDateTime): D? {
        val verb = Regex("^(?:por favor |please |lumi )?(?:crea\\w*|crear|anade\\w*|anadir|agrega\\w*|programa\\w*|agenda\\w*|pon\\w*|anota\\w*|create|add|schedule|make|put|new)\\b").containsMatchIn(n)
        val noun = Regex("\\b(?:evento|event|calendario|calendar)\\b").containsMatchIn(n)
        if (!verb || !noun) return null
        val english = Regex("\\b(?:create|add|schedule|make|put|event|calendar|to my|tomorrow|at|on)\\b").containsMatchIn(n) && !Regex("\\b(?:evento|calendario|manana|crea\\w*|anade\\w*|agrega\\w*|las)\\b").containsMatchIn(n)
        var text = raw
            .replace(Regex("(?iu)^(?:por favor|please)[, ]+"), "")
            .replace(Regex("(?iu)^(?:crea(?:r|me)?|a[ñn]ade|a[ñn]adir|agrega(?:r)?|programa(?:r)?|agenda(?:r)?|pon(?:me)?|anota(?:r)?|create|add|schedule|make|put|new)\\s+"), "")
            .replace(Regex("(?iu)\\b(?:(?:un|una|an?|the)\\s+)?(?:nuevo\\s+|new\\s+)?(?:evento|event)\\b(?:\\s+(?:llamado|called|named|de|para|for|with|con))?"), " ")
            .replace(Regex("(?iu)\\b(?:en|al|a)\\s+(?:el\\s+|mi\\s+)?calendario\\b|\\b(?:in|to|on)\\s+(?:my\\s+|the\\s+)?calendar\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val parsed = (if (english) EnglishDateParser.parse(text, now) else SpanishDateParser.parse(text, now))
        val start = parsed?.takeIf { it.hasTime }?.dateTime?.toString() ?: parsed?.dateTime?.toLocalDate()?.atTime(9, 0)?.toString()
        text = (parsed?.remainingText ?: text).replace(Regex("(?iu)^(?:de|para|for|con|with|llamado|called)\\s+"), "").trim().trim(',', '.')
        return D.CalendarEvent(text.ifBlank { if (english) "Event" else "Evento" }.replaceFirstChar { it.uppercase() }, start)
    }
}
