package com.antigravity.gemininanotaskmanager.domain.weather

import com.antigravity.gemininanotaskmanager.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Previsión hora a hora (hora local del sitio). */
data class HourForecast(val time: LocalDateTime, val tempC: Double, val precipProb: Int, val code: Int)

data class DayForecast(val date: LocalDate, val code: Int, val maxC: Double, val minC: Double, val precipProbMax: Int)

/** Previsión de Open-Meteo ya parseada. [place] es el nombre para mostrar («Madrid», «casa»). */
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

/** Qué quiere saber el usuario del tiempo. */
data class WeatherQuery(
    val date: LocalDate,
    val part: PartOfDay? = null,
    val topic: Topic = Topic.GENERAL,
    /** Ciudad o lugar guardado («en Madrid», «en casa»); null = donde estoy. */
    val place: String? = null
) {
    enum class Topic { GENERAL, RAIN, COLD, HEAT, SNOW }
}

enum class PartOfDay(val label: String, val from: Int, val to: Int) {
    MORNING("por la mañana", 7, 13), AFTERNOON("por la tarde", 14, 20), NIGHT("por la noche", 20, 24)
}

/** Códigos WMO de Open-Meteo → texto en español. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "despejado"
        1 -> "casi despejado"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "niebla"
        51, 53, 55 -> "llovizna"
        56, 57 -> "llovizna helada"
        61 -> "lluvia débil"
        63 -> "lluvia"
        65 -> "lluvia fuerte"
        66, 67 -> "lluvia helada"
        71, 73, 75, 77 -> "nieve"
        80, 81 -> "chubascos"
        82 -> "chubascos fuertes"
        85, 86 -> "chubascos de nieve"
        95 -> "tormenta"
        96, 99 -> "tormenta con granizo"
        else -> "variable"
    }

    fun isRain(code: Int) = code in 51..67 || code in 80..82 || code >= 95
    fun isSnow(code: Int) = code in 71..77 || code in 85..86
}

/**
 * Convierte la previsión en respuestas y avisos (puro, testeado). La IA no inventa el tiempo: los números salen
 * de Open-Meteo y las frases de aquí.
 */
object WeatherAdvisor {

    private val ES = Locale.forLanguageTag("es-ES")
    private val HM = DateTimeFormatter.ofPattern("H:mm")
    /** A partir de esta probabilidad se habla de «lluvia probable». */
    const val RAIN_LIKELY = 50

    fun deg(t: Double) = "${t.roundToInt()}°"

    /** «hoy», «mañana», «el sábado», «el 12». */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "hoy"
        today.plusDays(1) -> "mañana"
        else -> if (date.isBefore(today.plusDays(7))) "el " + date.dayOfWeek.getDisplayName(TextStyle.FULL, ES) else "el ${date.dayOfMonth}"
    }

    private fun hoursOf(report: WeatherReport, date: LocalDate, part: PartOfDay?, now: LocalDateTime): List<HourForecast> =
        report.hours.filter { h ->
            h.time.toLocalDate() == date && (part == null || h.time.hour in part.from until part.to) &&
                // Hoy solo cuenta lo que queda del día
                (date != now.toLocalDate() || !h.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)))
        }

    /** Primera hora con lluvia probable en [hours] (o null). */
    fun firstRain(hours: List<HourForecast>): HourForecast? =
        hours.firstOrNull { it.precipProb >= RAIN_LIKELY && (WeatherCodes.isRain(it.code) || WeatherCodes.isSnow(it.code) || it.precipProb >= 65) }

    /** Respuesta a una pregunta sobre el tiempo. */
    fun answer(q: WeatherQuery, report: WeatherReport, now: LocalDateTime): String {
        val today = now.toLocalDate()
        if (q.date.isBefore(today)) return "Solo sé la previsión de hoy en adelante."
        val day = report.day(q.date) ?: return "Solo tengo la previsión de los próximos ${report.days.size} días."
        val hours = hoursOf(report, q.date, q.part, now)
        val whenText = dayLabel(q.date, today) + (q.part?.let { " ${it.label}" } ?: "")
        val where = if (q.place != null) " en ${report.place}" else ""
        val lo = hours.minOfOrNull { it.tempC } ?: day.minC
        val hi = hours.maxOfOrNull { it.tempC } ?: day.maxC
        val maxProb = hours.maxOfOrNull { it.precipProb } ?: day.precipProbMax
        val rain = firstRain(hours)
        val rainText = rain?.let { "lluvia probable desde las ${it.time.format(HM)} (${it.precipProb} %)" }
        return when (q.topic) {
            WeatherQuery.Topic.RAIN -> when {
                rain != null -> "Sí, coge paraguas: ${whenText}$where hay $rainText.".cap()
                maxProb >= 30 -> "Puede que caiga algo ${whenText}$where, pero es poco probable (${maxProb} % como mucho).".cap()
                else -> "No, ${whenText}$where no se espera lluvia (${maxProb} % como mucho)."
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
                    append("Ahora ${deg(report.currentTempC)}${where}, ${WeatherCodes.describe(report.currentCode)}. ")
                    append("Hoy entre ${deg(day.minC)} y ${deg(day.maxC)}")
                } else {
                    append("${whenText}$where: ${WeatherCodes.describe(dominantCode(hours) ?: day.code)}, entre ${deg(lo)} y ${deg(hi)}".cap())
                }
                append(if (rainText != null) "; $rainText." else ".")
            }
        }
    }

    /** El código «más llamativo» de un tramo (lluvia > nubes > sol) para describirlo en una palabra. */
    private fun dominantCode(hours: List<HourForecast>): Int? =
        hours.maxByOrNull { h -> when { h.code >= 95 -> 5; WeatherCodes.isSnow(h.code) -> 4; WeatherCodes.isRain(h.code) && h.precipProb >= RAIN_LIKELY -> 3; else -> if (h.code > 3) 0 else h.code } }?.code

    /** Una línea para el resumen de la mañana: «18–24°, soleado. Lluvia probable desde las 18:00». */
    fun dayLine(report: WeatherReport, date: LocalDate, now: LocalDateTime): String? {
        val day = report.day(date) ?: return null
        val rain = firstRain(hoursOf(report, date, null, now))
        return "${day.minC.roundToInt()}–${deg(day.maxC)}, ${WeatherCodes.describe(day.code)}" +
            (rain?.let { ". Lluvia probable desde las ${it.time.format(HM)}" } ?: "")
    }

    // ── Avisos para tus tareas ──────────────────────────────────────────────

    data class TaskWarning(val task: Task, val message: String)

    private val OUTDOOR = Regex(
        "(?iu)\\b(?:correr|running|footing|pasear|paseo|caminar|andar|bici|bicicleta|ciclismo|playa|piscina|picnic|barbacoa|" +
            "senderismo|excursi[oó]n|monte|ruta|tender|lavar\\s+el\\s+coche|jard[ií]n|regar|terraza|parque|perro|f[uú]tbol|p[aá]del|" +
            "tenis|partido|golf|moto|mudanza|mercadillo|concierto|festival|al\\s+aire\\s+libre|fuera)\\b"
    )

    fun isOutdoor(task: Task): Boolean = OUTDOOR.containsMatchIn(task.title) || OUTDOOR.containsMatchIn(task.description)

    /**
     * Tareas al aire libre con hora en las que se espera lluvia (o calor extremo) a esa hora.
     * «Tienes «Correr» a las 19:00 y hay lluvia probable (70 %)».
     */
    fun taskWarnings(tasks: List<Task>, report: WeatherReport, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): List<TaskWarning> =
        tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null && isOutdoor(it) }.mapNotNull { t ->
            val at = Instant.ofEpochMilli(t.dueAt!!).atZone(zone).toLocalDateTime()
            if (at.isBefore(now)) return@mapNotNull null
            val slot = report.hours.filter { !it.time.isBefore(at.withMinute(0)) && it.time.isBefore(at.plusHours(2)) }
            if (slot.isEmpty()) return@mapNotNull null
            val prob = slot.maxOf { it.precipProb }
            val hot = slot.maxOf { it.tempC }
            val whenText = dayLabel(at.toLocalDate(), now.toLocalDate()) + " a las " + at.format(HM)
            when {
                prob >= RAIN_LIKELY -> TaskWarning(t, "«${t.title}» es $whenText y hay lluvia probable ($prob %).")
                hot >= 33 -> TaskWarning(t, "«${t.title}» es $whenText con ${deg(hot)}: mucho calor.")
                else -> null
            }
        }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}
