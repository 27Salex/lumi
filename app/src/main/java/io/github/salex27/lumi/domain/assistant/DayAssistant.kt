package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.weather.WeatherAdvisor
import io.github.salex27.lumi.domain.weather.WeatherReport
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Alarma inteligente (puro, testeado): a qué hora despertarte según lo primero que tienes ese día
 * (reunión, tarea con hora o tu hora de entrar a trabajar), dejando tiempo para prepararte y para el trayecto.
 */
object AlarmPlanner {

    data class Config(
        /** Minutos para arreglarte desde que suena la alarma. */
        val prepMinutes: Int = 60,
        /** Trayecto cuando hay que ir a algún sitio (reunión con dirección o ir a trabajar). */
        val travelMinutes: Int = 30,
        /** Hora de entrar a trabajar (solo lunes a viernes); null = no contar el trabajo. */
        val workStartHour: Int? = 9,
        val earliest: LocalTime = LocalTime.of(5, 0),
        /** Lo que empiece después de esta hora no necesita alarma. */
        val latestAnchor: LocalTime = LocalTime.of(13, 0)
    )

    data class Plan(val date: LocalDate, val wake: LocalTime, val anchorTitle: String, val anchorTime: LocalTime, val travel: Boolean, val reason: String)

    private val HM = DateTimeFormatter.ofPattern("H:mm")

    /** Día para el que se pone la alarma: mañana, salvo de madrugada (a las 2:00 «mañana» es hoy). */
    fun targetDate(now: LocalDateTime): LocalDate = if (now.hour < 4) now.toLocalDate() else now.toLocalDate().plusDays(1)

    fun plan(
        date: LocalDate,
        events: List<AgendaEvent>,
        tasks: List<Task>,
        config: Config = Config(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Plan? {
        data class Anchor(val title: String, val time: LocalTime, val travel: Boolean, val kind: String)
        fun Long.local() = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
        val anchors = mutableListOf<Anchor>()
        events.filter { !it.allDay }.forEach { e ->
            val at = e.begin.local()
            if (at.toLocalDate() == date) anchors += Anchor(e.title, at.toLocalTime(), e.location.isNotBlank(), "reunión")
        }
        tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null }.forEach { t ->
            val at = t.dueAt!!.local()
            if (at.toLocalDate() == date) anchors += Anchor(t.title, at.toLocalTime(), t.placeTrigger != null, "tarea")
        }
        val weekday = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        config.workStartHour?.takeIf { weekday }?.let { anchors += Anchor("Empezar a trabajar", LocalTime.of(it, 0), true, "trabajo") }

        val first = anchors.filter { !it.time.isBefore(config.earliest) && it.time.isBefore(config.latestAnchor) }
            .minByOrNull { it.time } ?: return null
        val travel = if (first.travel) config.travelMinutes else 0
        var wake = first.time.minusMinutes((config.prepMinutes + travel).toLong())
        wake = wake.withMinute(wake.minute / 5 * 5) // redondeo hacia abajo a 5 min
        if (wake.isBefore(config.earliest) || wake.isAfter(first.time)) wake = config.earliest
        val what = if (first.kind == "trabajo") "Empiezas a trabajar a las ${first.time.format(HM)}"
        else "Lo primero es «${first.title}» a las ${first.time.format(HM)}"
        val margin = buildString {
            append("${config.prepMinutes} min para arreglarte")
            if (travel > 0) append(" y $travel de trayecto")
        }
        return Plan(date, wake, first.title, first.time, travel > 0, "$what: alarma a las ${wake.format(HM)} ($margin).")
    }
}

/**
 * Resumen de un día (puro, testeado): lo que dice Lumi con «buenos días», «¿qué tengo mañana?» y en la
 * notificación de la mañana. Tiempo + primera cita + lo pendiente + avisos del tiempo para tus tareas.
 */
object DayBriefComposer {

    data class Brief(val title: String, val body: String)

    private val HM = DateTimeFormatter.ofPattern("H:mm")

    fun compose(
        date: LocalDate,
        now: LocalDateTime,
        tasks: List<Task>,
        events: List<AgendaEvent>,
        weather: WeatherReport?,
        greeting: String,
        alarm: AlarmPlanner.Plan? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): Brief {
        val today = now.toLocalDate()
        val isToday = date == today
        fun Long.date(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
        fun Long.time(): String = Instant.ofEpochMilli(this).atZone(zone).toLocalTime().format(HM)
        val label = WeatherAdvisor.dayLabel(date, today)

        val dayTasks = tasks.filter { it.isActive && it.dueAt?.date() == date }
            .sortedWith(compareBy({ !it.dueHasTime }, { it.dueAt }))
        val overdue = if (isToday) tasks.filter { it.isActive && it.dueAt?.let { d -> d.date().isBefore(today) } == true } else emptyList()
        val dayEvents = events.filter { !it.allDay && it.begin.date() == date && (!isToday || it.end > now.atZone(zone).toInstant().toEpochMilli()) }
            .sortedBy { it.begin }
        val allDay = events.filter { it.allDay }

        val weatherLine = weather?.let { WeatherAdvisor.dayLine(it, date, now) }
        val title = buildString {
            append(if (isToday) greeting else "Tu día $label")
            weatherLine?.let { append(" · ").append(it.substringBefore(". Lluvia")) }
        }

        val lines = mutableListOf<String>()
        weatherLine?.let { lines += (if (isToday) "Hoy " else "${label.replaceFirstChar { c -> c.uppercase() }}: ") + it + "." }
        val first = listOfNotNull(
            dayEvents.firstOrNull()?.let { it.begin to "«${it.title}» a las ${it.begin.time()}" },
            dayTasks.firstOrNull { it.dueHasTime }?.let { it.dueAt!! to "«${it.title}» a las ${it.dueAt.time()}" }
        ).minByOrNull { it.first }
        first?.let { lines += "Lo primero: " + it.second + "." }
        if (dayEvents.size > 1) lines += "${dayEvents.size} reuniones en total."
        if (allDay.isNotEmpty()) lines += "Todo el día: " + allDay.take(2).joinToString(", ") { "«${it.title}»" } + "."
        val onlyTheFirst = dayTasks.size == 1 && dayEvents.isEmpty() && first != null
        if (onlyTheFirst) {
            lines += "Es lo único que tienes apuntado para $label."
        } else if (dayTasks.isNotEmpty()) {
            val urgent = dayTasks.count { it.priority == TaskPriority.HIGH }
            lines += "${dayTasks.size} ${if (dayTasks.size == 1) "tarea" else "tareas"} para $label" +
                (if (urgent > 0) " ($urgent ${if (urgent == 1) "urgente" else "urgentes"})" else "") + ": " +
                dayTasks.take(3).joinToString(", ") { "«${it.title}»" } + (if (dayTasks.size > 3) " y ${dayTasks.size - 3} más" else "") + "."
        } else if (dayEvents.isEmpty()) {
            lines += if (isToday) "No tienes nada apuntado para hoy." else "No tienes nada apuntado para $label."
        }
        if (overdue.isNotEmpty()) lines += "Se te han pasado ${overdue.size}: " + overdue.take(2).joinToString(", ") { "«${it.title}»" } + "."
        weather?.let { w -> WeatherAdvisor.taskWarnings(tasks.filter { it.dueAt?.date() == date }, w, now, zone) }
            ?.firstOrNull()?.let { lines += "Ojo: ${it.message} ¿La movemos?" }
        alarm?.let { lines += it.reason }
        if (isToday && tasks.none { it.status == TaskStatus.IN_PROGRESS } && dayTasks.isEmpty() && dayEvents.isEmpty() && overdue.isEmpty()) {
            lines += "Buen día para adelantar algo o descansar."
        }
        return Brief(title, lines.joinToString(" "))
    }
}
