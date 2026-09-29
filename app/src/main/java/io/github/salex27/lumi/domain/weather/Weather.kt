package io.github.salex27.lumi.domain.weather

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Hourly forecast (the location's local time). */
data class HourForecast(val time: LocalDateTime, val tempC: Double, val precipProb: Int, val code: Int)

data class DayForecast(val date: LocalDate, val code: Int, val maxC: Double, val minC: Double, val precipProbMax: Int)

/** Parsed Open-Meteo forecast. [place] is the display name ("Madrid", "casa"). */
data class WeatherReport(
    val place: String,
    val fetchedAt: Long,
    val currentTempC: Double,
    val currentCode: Int,
    val hours: List<HourForecast>,
    val days: List<DayForecast>
) {
    fun day(date: LocalDate): DayForecast? = days.firstOrNull { it.date == date }
}

/** What the user wants to know about the weather. */
data class WeatherQuery(
    val date: LocalDate,
    val part: PartOfDay? = null,
    val topic: Topic = Topic.GENERAL,
    /** City or saved place ("in Madrid", "at home"); null = where I am. */
    val place: String? = null
) {
    enum class Topic { GENERAL, RAIN, COLD, HEAT, SNOW }
}

enum class PartOfDay(private val es: String, private val en: String, val from: Int, val to: Int) {
    MORNING("por la mañana", "in the morning", 7, 13),
    AFTERNOON("por la tarde", "in the afternoon", 14, 20),
    NIGHT("por la noche", "tonight", 20, 24);

    fun label(lang: Lang): String = if (lang == Lang.EN) en else es
}

/** Open-Meteo WMO codes → text. */
object WeatherCodes {
    fun describe(code: Int, lang: Lang = ReplyLanguage.current): String {
        val en = lang == Lang.EN
        return when (code) {
            0 -> if (en) "clear" else "despejado"
            1 -> if (en) "mostly clear" else "casi despejado"
            2 -> if (en) "partly cloudy" else "parcialmente nublado"
            3 -> if (en) "cloudy" else "nublado"
            45, 48 -> if (en) "fog" else "niebla"
            51, 53, 55 -> if (en) "drizzle" else "llovizna"
            56, 57 -> if (en) "freezing drizzle" else "llovizna helada"
            61 -> if (en) "light rain" else "lluvia débil"
            63 -> if (en) "rain" else "lluvia"
            65 -> if (en) "heavy rain" else "lluvia fuerte"
            66, 67 -> if (en) "freezing rain" else "lluvia helada"
            71, 73, 75, 77 -> if (en) "snow" else "nieve"
            80, 81 -> if (en) "showers" else "chubascos"
            82 -> if (en) "heavy showers" else "chubascos fuertes"
            85, 86 -> if (en) "snow showers" else "chubascos de nieve"
            95 -> if (en) "thunderstorm" else "tormenta"
            96, 99 -> if (en) "thunderstorm with hail" else "tormenta con granizo"
            else -> if (en) "changeable" else "variable"
        }
    }

    fun isRain(code: Int) = code in 51..67 || code in 80..82 || code >= 95
    fun isSnow(code: Int) = code in 71..77 || code in 85..86
}

/**
 * Turns the forecast into answers and warnings (pure, tested). The AI never makes up the weather: the numbers come
 * from Open-Meteo and the sentences from here, in Spanish or English.
 */
object WeatherAdvisor {

    private val ES = Locale.forLanguageTag("es-ES")
    private val EN = Locale.forLanguageTag("en-US")
    private val HM = DateTimeFormatter.ofPattern("H:mm")
    /** From this probability on we talk about "rain likely". */
    const val RAIN_LIKELY = 50

    fun deg(t: Double) = "${t.roundToInt()}°"

    /** "hoy" / "today", "mañana" / "tomorrow", "el sábado" / "on Saturday", "el 12" / "on the 12th". */
    fun dayLabel(date: LocalDate, today: LocalDate, lang: Lang = ReplyLanguage.current): String {
        val en = lang == Lang.EN
        return when (date) {
            today -> if (en) "today" else "hoy"
            today.plusDays(1) -> if (en) "tomorrow" else "mañana"
            else -> if (date.isBefore(today.plusDays(7))) {
                if (en) "on " + date.dayOfWeek.getDisplayName(TextStyle.FULL, EN) else "el " + date.dayOfWeek.getDisplayName(TextStyle.FULL, ES)
            } else if (en) "on the ${date.dayOfMonth}" else "el ${date.dayOfMonth}"
        }
    }

    private fun hoursOf(report: WeatherReport, date: LocalDate, part: PartOfDay?, now: LocalDateTime): List<HourForecast> =
        report.hours.filter { h ->
            h.time.toLocalDate() == date && (part == null || h.time.hour in part.from until part.to) &&
                // Today only the rest of the day counts
                (date != now.toLocalDate() || !h.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)))
        }

    /** First hour with likely rain in [hours] (or null). */
    fun firstRain(hours: List<HourForecast>): HourForecast? =
        hours.firstOrNull { it.precipProb >= RAIN_LIKELY && (WeatherCodes.isRain(it.code) || WeatherCodes.isSnow(it.code) || it.precipProb >= 65) }

    /** Answer to a weather question. */
    fun answer(q: WeatherQuery, report: WeatherReport, now: LocalDateTime, lang: Lang = ReplyLanguage.current): String {
        val en = lang == Lang.EN
        val today = now.toLocalDate()
        if (q.date.isBefore(today)) return if (en) "I only know the forecast from today on." else "Solo sé la previsión de hoy en adelante."
        val day = report.day(q.date) ?: return if (en) "I only have the forecast for the next ${report.days.size} days."
            else "Solo tengo la previsión de los próximos ${report.days.size} días."
        val hours = hoursOf(report, q.date, q.part, now)
        val whenText = dayLabel(q.date, today, lang) + (q.part?.let { " ${it.label(lang)}" } ?: "")
        val where = if (q.place != null) " ${if (en) "in" else "en"} ${report.place}" else ""
        val lo = hours.minOfOrNull { it.tempC } ?: day.minC
        val hi = hours.maxOfOrNull { it.tempC } ?: day.maxC
        val maxProb = hours.maxOfOrNull { it.precipProb } ?: day.precipProbMax
        val rain = firstRain(hours)
        val rainText = rain?.let {
            if (en) "rain likely from ${it.time.format(HM)} (${it.precipProb} %)" else "lluvia probable desde las ${it.time.format(HM)} (${it.precipProb} %)"
        }
        val describe = { code: Int -> WeatherCodes.describe(code, lang) }
        return if (en) when (q.topic) {
            WeatherQuery.Topic.RAIN -> when {
                rain != null -> "Yes, take an umbrella: ${whenText}$where there's $rainText."
                maxProb >= 30 -> "A few drops are possible ${whenText}$where, but it's unlikely ($maxProb % at most)."
                else -> "No, no rain expected ${whenText}$where ($maxProb % at most)."
            }
            WeatherQuery.Topic.COLD -> when {
                lo < 10 -> "Yes, wrap up: ${whenText}$where it will drop to ${deg(lo)} (high of ${deg(hi)})."
                lo < 16 -> "A light jacket: ${whenText}$where it will be between ${deg(lo)} and ${deg(hi)}."
                else -> "No coat needed: ${whenText}$where it will be between ${deg(lo)} and ${deg(hi)}."
            }
            WeatherQuery.Topic.HEAT -> when {
                hi >= 32 -> "Yes, ${whenText}$where it will be very hot: up to ${deg(hi)}. Drink water."
                hi >= 26 -> "It will be warm ${whenText}$where: up to ${deg(hi)}."
                else -> "Not really: ${whenText}$where ${deg(hi)} at most."
            }
            WeatherQuery.Topic.SNOW -> if (hours.any { WeatherCodes.isSnow(it.code) } || WeatherCodes.isSnow(day.code))
                "Yes, it may snow ${whenText}$where (low of ${deg(lo)})." else "No, no snow expected ${whenText}$where."
            WeatherQuery.Topic.GENERAL -> buildString {
                if (q.date == today && q.part == null) {
                    append("Now ${deg(report.currentTempC)}$where, ${describe(report.currentCode)}. ")
                    append("Today between ${deg(day.minC)} and ${deg(day.maxC)}")
                } else {
                    append("${whenText}$where: ${describe(dominantCode(hours) ?: day.code)}, between ${deg(lo)} and ${deg(hi)}".cap())
                }
                append(if (rainText != null) "; $rainText." else ".")
            }
        } else when (q.topic) {
            WeatherQuery.Topic.RAIN -> when {
                rain != null -> "Sí, coge paraguas: ${whenText}$where hay $rainText.".cap()
                maxProb >= 30 -> "Puede que caiga algo ${whenText}$where, pero es poco probable ($maxProb % como mucho).".cap()
                else -> "No, ${whenText}$where no se espera lluvia ($maxProb % como mucho)."
            }
            WeatherQuery.Topic.COLD -> when {
                lo < 10 -> "Sí, abrígate: ${whenText}$where bajará hasta ${deg(lo)} (máxima de ${deg(hi)}).".cap()
                lo < 16 -> "Una chaqueta ligera: ${whenText}$where estará entre ${deg(lo)} y ${deg(hi)}.".cap()
                else -> "No hace falta abrigo: ${whenText}$where estará entre ${deg(lo)} y ${deg(hi)}.".cap()
            }
            WeatherQuery.Topic.HEAT -> when {
                hi >= 32 -> "Sí, ${whenText}$where hará mucho calor: hasta ${deg(hi)}. Bebe agua.".cap()
                hi >= 26 -> "Hará calor ${whenText}$where: hasta ${deg(hi)}.".cap()
                else -> "No mucho: ${whenText}$where como máximo ${deg(hi)}.".cap()
            }
            WeatherQuery.Topic.SNOW -> if (hours.any { WeatherCodes.isSnow(it.code) } || WeatherCodes.isSnow(day.code))
                "Sí, ${whenText}$where puede nevar (mínima de ${deg(lo)}).".cap() else "No, ${whenText}$where no se espera nieve."
            WeatherQuery.Topic.GENERAL -> buildString {
                if (q.date == today && q.part == null) {
                    append("Ahora ${deg(report.currentTempC)}$where, ${describe(report.currentCode)}. ")
                    append("Hoy entre ${deg(day.minC)} y ${deg(day.maxC)}")
                } else {
                    append("${whenText}$where: ${describe(dominantCode(hours) ?: day.code)}, entre ${deg(lo)} y ${deg(hi)}".cap())
                }
                append(if (rainText != null) "; $rainText." else ".")
            }
        }
    }

    /** The "loudest" code of a period (rain > clouds > sun) to describe it in a word. */
    private fun dominantCode(hours: List<HourForecast>): Int? =
        hours.maxByOrNull { h -> when { h.code >= 95 -> 5; WeatherCodes.isSnow(h.code) -> 4; WeatherCodes.isRain(h.code) && h.precipProb >= RAIN_LIKELY -> 3; else -> if (h.code > 3) 0 else h.code } }?.code

    /** Short line for titles: "18–24°, soleado" / "18–24°, clear". */
    fun dayShort(report: WeatherReport, date: LocalDate, lang: Lang = ReplyLanguage.current): String? {
        val day = report.day(date) ?: return null
        return "${day.minC.roundToInt()}–${deg(day.maxC)}, ${WeatherCodes.describe(day.code, lang)}"
    }

    /** Line for the morning summary: "18–24°, soleado. Lluvia probable desde las 18:00" (or in English). */
    fun dayLine(report: WeatherReport, date: LocalDate, now: LocalDateTime, lang: Lang = ReplyLanguage.current): String? {
        val short = dayShort(report, date, lang) ?: return null
        val rain = firstRain(hoursOf(report, date, null, now))
        return short + (rain?.let { if (lang == Lang.EN) ". Rain likely from ${it.time.format(HM)}" else ". Lluvia probable desde las ${it.time.format(HM)}" } ?: "")
    }

    // ── Warnings for your tasks ─────────────────────────────────────────────

    /** [rainProb] or [hotC] tells which problem it is, so callers can phrase their own hint. */
    data class TaskWarning(val task: Task, val message: String, val rainProb: Int? = null, val hotC: Double? = null)

    private val OUTDOOR = Regex(
        "(?iu)\\b(?:correr|running|footing|pasear|paseo|caminar|andar|bici|bicicleta|ciclismo|playa|piscina|picnic|barbacoa|" +
            "senderismo|excursi[oó]n|monte|ruta|tender|lavar\\s+el\\s+coche|jard[ií]n|regar|terraza|parque|perro|f[uú]tbol|p[aá]del|" +
            "tenis|partido|golf|moto|mudanza|mercadillo|concierto|festival|al\\s+aire\\s+libre|fuera|" +
            // English
            "run|jog|jogging|walk|hike|hiking|bike|cycling|beach|pool|barbecue|bbq|garden|park|dog|football|soccer|tennis|" +
            "match|outdoors?|outside|wash\\s+the\\s+car|laundry)\\b"
    )

    fun isOutdoor(task: Task): Boolean = OUTDOOR.containsMatchIn(task.title) || OUTDOOR.containsMatchIn(task.description)

    /**
     * Timed outdoor tasks for which rain (or extreme heat) is expected at that time.
     * "«Correr» es hoy a las 19:00 y hay lluvia probable (70 %)" / "«Run» is today at 19:00 and rain is likely (70 %)".
     */
    fun taskWarnings(
        tasks: List<Task>,
        report: WeatherReport,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
        lang: Lang = ReplyLanguage.current
    ): List<TaskWarning> =
        tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null && isOutdoor(it) }.mapNotNull { t ->
            val en = lang == Lang.EN
            val at = Instant.ofEpochMilli(t.dueAt!!).atZone(zone).toLocalDateTime()
            if (at.isBefore(now)) return@mapNotNull null
            val slot = report.hours.filter { !it.time.isBefore(at.withMinute(0)) && it.time.isBefore(at.plusHours(2)) }
            if (slot.isEmpty()) return@mapNotNull null
            val prob = slot.maxOf { it.precipProb }
            val hot = slot.maxOf { it.tempC }
            val whenText = dayLabel(at.toLocalDate(), now.toLocalDate(), lang) + (if (en) " at " else " a las ") + at.format(HM)
            when {
                prob >= RAIN_LIKELY -> TaskWarning(t,
                    if (en) "«${t.title}» is $whenText and rain is likely ($prob %)." else "«${t.title}» es $whenText y hay lluvia probable ($prob %).",
                    rainProb = prob)
                hot >= 33 -> TaskWarning(t,
                    if (en) "«${t.title}» is $whenText at ${deg(hot)}: very hot." else "«${t.title}» es $whenText con ${deg(hot)}: mucho calor.",
                    hotC = hot)
                else -> null
            }
        }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}
