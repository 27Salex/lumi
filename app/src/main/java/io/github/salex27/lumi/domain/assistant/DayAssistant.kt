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
 * Smart alarm (pure, tested): when to wake up based on the first thing you have that day (a meeting, a timed task or
 * your work start time), leaving time to get ready and to travel.
 */
object AlarmPlanner {

    data class Config(
        /** Minutes to get ready once the alarm rings. */
        val prepMinutes: Int = 60,
        /** Travel time when you have to go somewhere (a meeting with an address, or going to work). */
        val travelMinutes: Int = 30,
        /** Work start hour (Monday to Friday only); null = don't count work. */
        val workStartHour: Int? = 9,
        val earliest: LocalTime = LocalTime.of(5, 0),
        /** Anything starting after this time doesn't need an alarm. */
        val latestAnchor: LocalTime = LocalTime.of(13, 0)
    )

    data class Plan(val date: LocalDate, val wake: LocalTime, val anchorTitle: String, val anchorTime: LocalTime, val travel: Boolean, val reason: String)

    private val HM = DateTimeFormatter.ofPattern("H:mm")

    /** The day the alarm is for: tomorrow, except in the small hours (at 2:00 "tomorrow" means today). */
    fun targetDate(now: LocalDateTime): LocalDate = if (now.hour < 4) now.toLocalDate() else now.toLocalDate().plusDays(1)

    fun plan(
        date: LocalDate,
        events: List<AgendaEvent>,
        tasks: List<Task>,
        config: Config = Config(),
        zone: ZoneId = ZoneId.systemDefault(),
        lang: Lang = ReplyLanguage.current
    ): Plan? {
        val en = lang == Lang.EN
        data class Anchor(val title: String, val time: LocalTime, val travel: Boolean, val isWork: Boolean)
        fun Long.local() = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
        val anchors = mutableListOf<Anchor>()
        events.filter { !it.allDay }.forEach { e ->
            val at = e.begin.local()
            if (at.toLocalDate() == date) anchors += Anchor(e.title, at.toLocalTime(), e.location.isNotBlank(), false)
        }
        tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null }.forEach { t ->
            val at = t.dueAt!!.local()
            if (at.toLocalDate() == date) anchors += Anchor(t.title, at.toLocalTime(), t.placeTrigger != null, false)
        }
        val weekday = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        config.workStartHour?.takeIf { weekday }?.let {
            anchors += Anchor(if (en) "Start work" else "Empezar a trabajar", LocalTime.of(it, 0), true, true)
        }

        val first = anchors.filter { !it.time.isBefore(config.earliest) && it.time.isBefore(config.latestAnchor) }
            .minByOrNull { it.time } ?: return null
        val travel = if (first.travel) config.travelMinutes else 0
        var wake = first.time.minusMinutes((config.prepMinutes + travel).toLong())
        wake = wake.withMinute(wake.minute / 5 * 5) // round down to 5 min
        if (wake.isBefore(config.earliest) || wake.isAfter(first.time)) wake = config.earliest
        val at = first.time.format(HM)
        val reason = if (en) {
            val what = if (first.isWork) "You start work at $at" else "First up is «${first.title}» at $at"
            val margin = "${config.prepMinutes} min to get ready" + if (travel > 0) " and $travel to get there" else ""
            "$what: alarm at ${wake.format(HM)} ($margin)."
        } else {
            val what = if (first.isWork) "Empiezas a trabajar a las $at" else "Lo primero es «${first.title}» a las $at"
            val margin = "${config.prepMinutes} min para arreglarte" + if (travel > 0) " y $travel de trayecto" else ""
            "$what: alarma a las ${wake.format(HM)} ($margin)."
        }
        return Plan(date, wake, first.title, first.time, travel > 0, reason)
    }
}

/**
 * Summary of a day (pure, tested): what Lumi says for "good morning", "what do I have tomorrow?" and in the morning
 * notification. Weather + first appointment + what's pending + weather warnings for your tasks.
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
        zone: ZoneId = ZoneId.systemDefault(),
        lang: Lang = ReplyLanguage.current
    ): Brief {
        val en = lang == Lang.EN
        val today = now.toLocalDate()
        val isToday = date == today
        fun Long.date(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
        fun Long.time(): String = Instant.ofEpochMilli(this).atZone(zone).toLocalTime().format(HM)
        fun list(items: List<String>, more: Int) = items.joinToString(", ") +
            if (more > 0) (if (en) " and $more more" else " y $more más") else ""
        val label = WeatherAdvisor.dayLabel(date, today, lang)
        val at = if (en) "at" else "a las"

        val dayTasks = tasks.filter { it.isActive && it.dueAt?.date() == date }
            .sortedWith(compareBy({ !it.dueHasTime }, { it.dueAt }))
        val overdue = if (isToday) tasks.filter { it.isActive && it.dueAt?.let { d -> d.date().isBefore(today) } == true } else emptyList()
        val dayEvents = events.filter { !it.allDay && it.begin.date() == date && (!isToday || it.end > now.atZone(zone).toInstant().toEpochMilli()) }
            .sortedBy { it.begin }
        val allDay = events.filter { it.allDay }

        val weatherLine = weather?.let { WeatherAdvisor.dayLine(it, date, now, lang) }
        val title = buildString {
            append(if (isToday) greeting else if (en) "Your day $label" else "Tu día $label")
            weather?.let { WeatherAdvisor.dayShort(it, date, lang) }?.let { append(" · ").append(it) }
        }

        val lines = mutableListOf<String>()
        weatherLine?.let {
            lines += (if (isToday) (if (en) "Today " else "Hoy ") else "${label.replaceFirstChar { c -> c.uppercase() }}: ") + it + "."
        }
        val first = listOfNotNull(
            dayEvents.firstOrNull()?.let { it.begin to "«${it.title}» $at ${it.begin.time()}" },
            dayTasks.firstOrNull { it.dueHasTime }?.let { it.dueAt!! to "«${it.title}» $at ${it.dueAt.time()}" }
        ).minByOrNull { it.first }
        first?.let { lines += (if (en) "First up: " else "Lo primero: ") + it.second + "." }
        if (dayEvents.size > 1) lines += if (en) "${dayEvents.size} meetings in total." else "${dayEvents.size} reuniones en total."
        if (allDay.isNotEmpty()) lines += (if (en) "All day: " else "Todo el día: ") + allDay.take(2).joinToString(", ") { "«${it.title}»" } + "."
        val onlyTheFirst = dayTasks.size == 1 && dayEvents.isEmpty() && first != null
        if (onlyTheFirst) {
            lines += if (en) "It's the only thing on your list for $label." else "Es lo único que tienes apuntado para $label."
        } else if (dayTasks.isNotEmpty()) {
            val urgent = dayTasks.count { it.priority == TaskPriority.HIGH }
            val names = list(dayTasks.take(3).map { "«${it.title}»" }, dayTasks.size - 3)
            lines += if (en) {
                "${dayTasks.size} ${if (dayTasks.size == 1) "task" else "tasks"} for $label" +
                    (if (urgent > 0) " ($urgent urgent)" else "") + ": $names."
            } else {
                "${dayTasks.size} ${if (dayTasks.size == 1) "tarea" else "tareas"} para $label" +
                    (if (urgent > 0) " ($urgent ${if (urgent == 1) "urgente" else "urgentes"})" else "") + ": $names."
            }
        } else if (dayEvents.isEmpty()) {
            lines += if (en) "Nothing on your list for $label." else if (isToday) "No tienes nada apuntado para hoy." else "No tienes nada apuntado para $label."
        }
        if (overdue.isNotEmpty()) {
            lines += (if (en) "${overdue.size} slipped past: " else "Se te han pasado ${overdue.size}: ") +
                overdue.take(2).joinToString(", ") { "«${it.title}»" } + "."
        }
        weather?.let { w -> WeatherAdvisor.taskWarnings(tasks.filter { it.dueAt?.date() == date }, w, now, zone, lang) }
            ?.firstOrNull()?.let { lines += if (en) "Heads up: ${it.message} Shall we move it?" else "Ojo: ${it.message} ¿La movemos?" }
        alarm?.let { lines += it.reason }
        if (isToday && tasks.none { it.status == TaskStatus.IN_PROGRESS } && dayTasks.isEmpty() && dayEvents.isEmpty() && overdue.isEmpty()) {
            lines += if (en) "A good day to get ahead on something, or to rest." else "Buen día para adelantar algo o descansar."
        }
        return Brief(title, lines.joinToString(" "))
    }
}
