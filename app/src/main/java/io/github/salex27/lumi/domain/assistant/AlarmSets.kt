package io.github.salex27.lumi.domain.assistant

import java.text.Normalizer

/** Pure helpers shared by the phone-control parsers: accent-free text, numbers, clock times, fuzzy words. */
internal object Say {
    fun plain(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9:.% ]"), " ")
        .replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val WORDS = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "one" to 1, "a" to 1, "dos" to 2, "two" to 2, "tres" to 3, "three" to 3,
        "cuatro" to 4, "four" to 4, "cinco" to 5, "five" to 5, "seis" to 6, "six" to 6, "siete" to 7, "seven" to 7,
        "ocho" to 8, "eight" to 8, "nueve" to 9, "nine" to 9, "diez" to 10, "ten" to 10, "once" to 11, "eleven" to 11,
        "doce" to 12, "twelve" to 12, "quince" to 15, "fifteen" to 15, "veinte" to 20, "twenty" to 20,
        "treinta" to 30, "thirty" to 30
    )

    fun number(s: String): Int? = s.trim().toIntOrNull() ?: WORDS[s.trim()]

    /** Edit distance where swapping two neighbouring letters ("bateira") counts as one typo. */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** True when some word of [text] is one of [targets] up to one typo (two for long words). */
    fun has(text: String, vararg targets: String): Boolean {
        val words = text.split(' ')
        return targets.any { t -> words.any { w -> w == t || (w.length >= 4 && t.length >= 4 && distance(w, t) <= (if (t.length >= 8) 2 else 1)) } }
    }

    /** A clock time found in the text. [end] = it follows "until / hasta". */
    data class Clock(val minutes: Int, val start: Int, val endIdx: Int, val end: Boolean)

    private val MARKED = Regex("((?:\\b(?:hasta|until|till|to|desde|from|at|para|a|las?|el)\\s+)+)(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm|a m|p m|de la manana|de la tarde|de la noche|de la madrugada|in the morning|in the evening|at night)?(?![\\d:%])")
    private val COLON = Regex("(?<![\\d:.])(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?(?![\\d])")

    /** Hours from [pmFrom] to 11 with no am/pm are taken as pm (a bedtime); 13 = never. */
    fun clocks(n: String, pmFrom: Int = 13): List<Clock> {
        val out = mutableListOf<Clock>()
        for (m in MARKED.findAll(n)) {
            val after = n.substring(m.range.last + 1).trimStart()
            // "a las 3 alarmas" / "3 veces" is a count, not a time
            if (after.startsWith("alarm") || after.startsWith("veces") || after.startsWith("min") || after.startsWith("hora")) continue
            val c = build(m.groupValues[2].toInt(), m.groupValues[3].ifBlank { "0" }.toInt(), m.groupValues[4], pmFrom) ?: continue
            val end = Regex("\\b(hasta|until|till)\\b").containsMatchIn(m.groupValues[1])
            out += Clock(c, m.range.first, m.range.last, end)
        }
        for (m in COLON.findAll(n)) {
            if (out.any { m.range.first in it.start..it.endIdx }) continue
            val c = build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], pmFrom) ?: continue
            out += Clock(c, m.range.first, m.range.last, false)
        }
        return out.sortedBy { it.start }
    }

    private fun build(h0: Int, min: Int, suffix: String, pmFrom: Int): Int? {
        var h = h0
        if (min !in 0..59) return null
        val s = suffix.trim()
        val pm = s.startsWith("p") || s == "de la tarde" || s == "de la noche" || s == "in the evening" || s == "at night"
        val am = s.startsWith("a") || s == "de la manana" || s == "de la madrugada" || s == "in the morning"
        if (h !in 0..23 || (s.isNotEmpty() && h > 12)) return null
        if (pm && h < 12) h += 12
        else if (am && h == 12) h = 0
        else if (s.isEmpty() && h in pmFrom..11) h += 12
        return h * 60 + min
    }

    fun fmt(minutes: Int) = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
}

/** A set of wake-up alarms asked in one sentence. [snooze] is null when the user did not say. */
data class AlarmSetRequest(val times: List<Int>, val snooze: Boolean?)

/**
 * "Me levanto a las 7, pon 3 alarmas cada 10 minutos", "wake me at 6:30 with 4 alarms every 5 minutes, no snooze",
 * "pon alarmas desde las 7 cada 15 minutos hasta las 8" (pure, tested; Spanish and English, typo tolerant).
 */
object AlarmSetParser {
    const val MAX_ALARMS = 12
    const val DEFAULT_INTERVAL = 10
    const val DEFAULT_COUNT = 3

    /** Times (minutes from midnight) from [start]: [count] of them, or up to [end] (inclusive), every [interval] minutes. */
    fun schedule(start: Int, interval: Int, count: Int?, end: Int?): List<Int> {
        val step = interval.coerceIn(1, 180)
        val out = mutableListOf<Int>()
        var t = start
        val limit = end?.let { if (it < start) it + 24 * 60 else it }
        while (out.size < MAX_ALARMS) {
            if (count != null && out.size >= count) break
            if (count == null && (limit == null || t > limit)) break
            out += t % (24 * 60)
            t += step
        }
        return out
    }

    fun parse(input: String): AlarmSetRequest? {
        val n = Say.plain(input)
        if (!Say.has(n, "alarma", "alarmas", "alarm", "alarms", "despiertame", "despertarme", "wake", "levanto", "despierta")) return null
        // Not a cancel / bedtime reminder
        if (Cancel.looksLikeCancel(n) || Regex("\\b(recuerd\\w*|remind\\w*|acuest\\w*|bedtime|dormir)\\b").containsMatchIn(n)) return null
        val clocks = Say.clocks(n)
        val startClock = clocks.firstOrNull { !it.end } ?: return null
        val endClock = clocks.firstOrNull { it.end }
        val count = Regex("\\b(\\d+|[a-z]+)\\s+(?:alarm\\w*|despertador\\w*)").findAll(n).mapNotNull { Say.number(it.groupValues[1]) }.firstOrNull()
        val interval = interval(n)
        // A single alarm is the plain "set an alarm" command
        if (interval == null && endClock == null && (count == null || count < 2)) return null
        if (count != null && count < 1) return null
        val times = schedule(startClock.minutes, interval ?: DEFAULT_INTERVAL, count ?: if (endClock == null) DEFAULT_COUNT else null, endClock?.minutes)
        if (times.isEmpty()) return null
        val snooze = when {
            Regex("\\b(sin|no|without|disable)\\s+(snooze|repetic\\w*|repetir|posponer|aplazar|dormitar)").containsMatchIn(n) -> false
            Regex("\\b(con|with)\\s+(snooze|repetic\\w*|repetir|posponer|aplazar|dormitar)").containsMatchIn(n) -> true
            else -> null
        }
        return AlarmSetRequest(times, snooze)
    }

    private fun interval(n: String): Int? {
        Regex("\\b(?:cada|every|each)\\s+(media hora|half an hour|half hour|cuarto de hora|quarter of an hour|hora|hour)\\b").find(n)?.let {
            val w = it.groupValues[1]
            return when { w.startsWith("media") || w.startsWith("half") -> 30; w.startsWith("cuarto") || w.startsWith("quarter") -> 15; else -> 60 }
        }
        Regex("\\b(?:cada|every|each|de)\\s+(\\d+|[a-z]+)(?:\\s+en\\s+\\1)?\\s*(min\\w*|hora\\w*|hour\\w*)\\b").findAll(n).forEach {
            val v = Say.number(it.groupValues[1]) ?: return@forEach
            return if (it.groupValues[2].startsWith("h")) v * 60 else v
        }
        Regex("\\b(\\d+)\\s*min\\w*\\s+(?:de\\s+)?(?:apart|separad\\w*)").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("\\b(?:separad\\w+|espaciad\\w+|apart)\\s+(?:por\\s+|de\\s+)?(\\d+)\\s*min").find(n)?.let { return it.groupValues[1].toInt() }
        return null
    }
}

/** "Quita las alarmas de mañana", "cancel the alarms". */
object Cancel {
    fun looksLikeCancel(n: String): Boolean =
        Regex("^(?:(?:por favor|please|lumi)\\s+)?(?:quita\\w*|borra\\w*|cancela\\w*|elimina\\w*|desactiva\\w*|apaga\\w*|cancel|delete|remove|clear|undo|deshaz|deshacer)\\b").containsMatchIn(n) &&
            Say.has(n, "alarma", "alarmas", "alarm", "alarms", "despertador") && !Regex("\\b(tarea|tareas|task|tasks|recordatorio)\\b").containsMatchIn(n)
}

/** Daily bedtime reminder asked by voice or chat. [days] is a mask: bit 0 = Monday ... bit 6 = Sunday. */
data class BedtimeRequest(val minutes: Int, val days: Int)

object BedtimeParser {
    const val ALL_DAYS = 127
    private val DAY_NAMES = listOf(
        "lunes|monday", "martes|tuesday", "miercoles|wednesday", "jueves|thursday", "viernes|friday", "sabado|saturday", "domingo|sunday"
    )

    fun parse(input: String): BedtimeRequest? {
        val n = Say.plain(input)
        val remind = Say.has(n, "recuerdame", "recuerda", "avisame", "recordatorio", "remind", "reminder", "notify", "notificame") ||
            Regex("^(?:pon|programa|crea|set)\\b").containsMatchIn(n)
        val bed = Regex("\\b(acuest\\w*|dormir|cama|bed|bedtime|sleep|buenas noches|good night)\\b").containsMatchIn(n)
        if (!remind || !bed) return null
        val recurring = Regex("\\b(cada|todos|todas|diari\\w*|every|daily|each|entre semana|weekdays?|weekends?|fines? de semana|nightly)\\b").containsMatchIn(n)
        if (!recurring) return null
        val clock = Say.clocks(n, pmFrom = 6).firstOrNull() ?: return null
        var days = 0
        if (Regex("\\b(entre semana|weekdays?|laborables|de lunes a viernes|monday to friday)\\b").containsMatchIn(n)) days = 0b0011111
        else if (Regex("\\b(fines? de semana|weekends?)\\b").containsMatchIn(n)) days = 0b1100000
        else DAY_NAMES.forEachIndexed { i, names -> if (Regex("\\b($names)s?\\b").containsMatchIn(n)) days = days or (1 shl i) }
        return BedtimeRequest(clock.minutes, if (days == 0) ALL_DAYS else days)
    }

    fun daysLabel(mask: Int, es: Boolean): String = when (mask and ALL_DAYS) {
        ALL_DAYS -> if (es) "todos los días" else "every day"
        0b0011111 -> if (es) "de lunes a viernes" else "on weekdays"
        0b1100000 -> if (es) "los fines de semana" else "on weekends"
        else -> DAY_NAMES.indices.filter { includes(mask, it) }.joinToString(", ") { i ->
            if (es) listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")[i] else listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[i]
        }
    }

    /** Day-of-week index 0 = Monday (java.time DayOfWeek.value - 1). */
    fun includes(mask: Int, dayIndex: Int) = mask and (1 shl dayIndex) != 0

    /** Next fire time (epoch-day offset 0 = today) as a day offset >= 0 for a reminder at [minutes], or null if no day is set. */
    fun nextDayOffset(mask: Int, todayIndex: Int, nowMinutes: Int, minutes: Int): Int? {
        if (mask and ALL_DAYS == 0) return null
        for (off in 0..7) {
            if (!includes(mask, (todayIndex + off) % 7)) continue
            if (off == 0 && minutes <= nowMinutes) continue
            return off
        }
        return null
    }
}

/** Alarms Lumi created, remembered so the user can list and delete them ("minutes|createdAtEpochMs" per line). */
object AlarmLog {
    const val KEEP_MS = 25L * 3_600_000L
    data class Entry(val minutes: Int, val createdAt: Long)

    fun encode(list: List<Entry>) = list.joinToString("\n") { "${it.minutes}|${it.createdAt}" }
    fun decode(s: String?): List<Entry> = s.orEmpty().lines().mapNotNull { l ->
        val p = l.split('|'); val m = p.getOrNull(0)?.toIntOrNull(); val c = p.getOrNull(1)?.toLongOrNull()
        if (m != null && c != null) Entry(m, c) else null
    }
    /** Entries that can still ring (an alarm without a date rings within 24 h). */
    fun live(list: List<Entry>, now: Long) = list.filter { now - it.createdAt < KEEP_MS }
    fun summary(times: List<Int>) = times.joinToString(", ") { Say.fmt(it) }
}
