package io.github.salex27.lumi.presentation.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.data.ai.GemmaModelManager
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.presentation.components.EngineBadge
import io.github.salex27.lumi.presentation.components.LumiCard
import io.github.salex27.lumi.presentation.components.LumiMark
import io.github.salex27.lumi.presentation.components.LumiState
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.SmartBar
import io.github.salex27.lumi.presentation.components.TypewriterText
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.presentation.theme.color
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import io.github.salex27.lumi.domain.assistant.FreeTimeFinder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Inicio: saludo, cifra del día, resumen de Lumi y lo de hoy. Todo lo demás vive en otras pestañas. */
@Composable
fun HomeScreen(
    uiState: MainUiState,
    todayEvents: List<AgendaEvent>,
    onRefreshBriefing: () -> Unit,
    onDownloadGemma: () -> Unit,
    onOpenAssistant: (listen: Boolean, prompt: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgenda: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onStartTask: (Task) -> Unit = {},
    onCompleteTask: (Task) -> Unit = {},
    weather: io.github.salex27.lumi.domain.weather.WeatherReport? = null
) {
    val c = Lumi.colors
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now()
    val active = uiState.allTasks.filter { it.isActive }
    val overdue = active.filter { t -> t.dueAt?.let { DueDateFormatter.isOverdue(it, t.dueHasTime) } == true }
    val dueToday = active.filter { t -> t !in overdue && t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true }
    val doneToday = uiState.allTasks.count { t -> t.status == TaskStatus.COMPLETED && t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(onOpenSettings, weather) { onOpenAssistant(false, "¿Qué tiempo hace hoy?") }
            HeroNumber(remaining = dueToday.size + overdue.size, overdue = overdue.size, done = doneToday, onClick = onOpenAgenda)
            LumiBriefCard(uiState.briefing, onRefreshBriefing) { onOpenAssistant(false, "¿Qué hago ahora?") }
            FreeTimeCard(uiState.allTasks, todayEvents, onStartTask, onCompleteTask)
            if (uiState.gemmaState != GemmaModelManager.State.Ready) GemmaRow(uiState.gemmaState, onDownloadGemma)
            TodaySection(overdue + dueToday, todayEvents, onOpenAgenda, onTaskClick)
        }

        SmartBar(
            onSubmit = { onOpenAssistant(false, it) },
            onVoice = { onOpenAssistant(true, null) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

/**
 * «Tienes 40 min libres hasta las 16:00 · ¿adelantas «Informe»?» — Lumi mira el calendario y tus tareas con hora
 * y propone qué hacer en el hueco. Se recalcula cada minuto; «Otra» cambia de sugerencia.
 */
@Composable
private fun FreeTimeCard(tasks: List<Task>, events: List<AgendaEvent>, onStartTask: (Task) -> Unit, onCompleteTask: (Task) -> Unit) {
    val c = Lumi.colors
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); tick++ } }
    val slot = remember(tasks, events, tick) {
        val now = LocalDateTime.now()
        val plan = io.github.salex27.lumi.data.ai.DayPlanner.plan(tasks, now, maxSuggestions = 6)
        FreeTimeFinder.find(now, events, tasks, plan.overdue + plan.dueToday + plan.suggestions)
    } ?: return
    // «Ahora no» oculta la tarjeta hasta que cambie el hueco (sobrevive a girar la pantalla)
    var hiddenSlot by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableLongStateOf(0L) }
    if (hiddenSlot == slot.end) return
    val options = listOfNotNull(slot.suggestion) + slot.alternatives
    if (options.isEmpty()) return
    // Se sigue la tarea por id: al empezarla cambia su estado pero no se pierde de vista
    var chosenId by remember(slot.end) { androidx.compose.runtime.mutableLongStateOf(options.first().id) }
    val task = tasks.firstOrNull { it.id == chosenId && it.isActive } ?: options.first()
    val started = task.status == TaskStatus.IN_PROGRESS
    val until = DueDateFormatter.format(slot.end, true).removePrefix("hoy ").removePrefix("a las ")

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.accentContainer).padding(18.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            (if (started) "En marcha · libre hasta las $until" else "Tienes ${humanDuration(slot.minutes)} libres · hasta las $until").uppercase(),
            style = MaterialTheme.typography.labelSmall, color = c.accentText
        )
        Text(if (started) "A por «${task.title}»" else "¿Adelantas «${task.title}»?", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (started) {
                PillButton("Hecho") { onCompleteTask(task) }
                PillButton("Ahora no", style = PillStyle.GHOST) { hiddenSlot = slot.end }
            } else {
                PillButton("Empezar") { chosenId = task.id; onStartTask(task) }
                if (options.size > 1) PillButton("Otra", style = PillStyle.GHOST) {
                    chosenId = options[(options.indexOfFirst { it.id == task.id } + 1) % options.size].id
                }
                PillButton("Ahora no", style = PillStyle.GHOST) { hiddenSlot = slot.end }
            }
        }
    }
}

/** 296 → «4 h 56 min», 40 → «40 min». */
private fun humanDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

@Composable
private fun Header(
    onOpenSettings: () -> Unit,
    weather: io.github.salex27.lumi.domain.weather.WeatherReport? = null,
    onWeatherClick: () -> Unit = {}
) {
    val now = remember { LocalDateTime.now() }
    val date = remember { now.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-ES"))) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(date.uppercase(), style = MaterialTheme.typography.labelSmall, color = Lumi.colors.textTertiary)
            Spacer(Modifier.height(4.dp))
            Text(DueDateFormatter.greeting(now), style = MaterialTheme.typography.displaySmall, color = Lumi.colors.textPrimary)
            // El tiempo de un vistazo (toca para preguntarle a Lumi); solo si la previsión es de hoy
            weather?.takeIf { System.currentTimeMillis() - it.fetchedAt < 6 * 3_600_000L }?.let { w ->
                val line = io.github.salex27.lumi.domain.weather.WeatherAdvisor.dayLine(w, now.toLocalDate(), LocalDateTime.now())
                Spacer(Modifier.height(4.dp))
                Text(
                    "${io.github.salex27.lumi.domain.weather.WeatherAdvisor.deg(w.currentTempC)} · ${line ?: io.github.salex27.lumi.domain.weather.WeatherCodes.describe(w.currentCode)}",
                    style = MaterialTheme.typography.bodyMedium, color = Lumi.colors.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onWeatherClick)
                )
            }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.clip(CircleShape).background(Lumi.colors.muted).size(40.dp)) {
            Icon(Icons.Outlined.Settings, "Ajustes", tint = Lumi.colors.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Cifra protagonista estilo Revolut: lo que queda por hacer hoy. */
@Composable
private fun HeroNumber(remaining: Int, overdue: Int, done: Int, onClick: () -> Unit) {
    val c = Lumi.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$remaining", style = MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 1.6f), color = c.textPrimary)
            Spacer(Modifier.width(10.dp))
            Text(if (remaining == 1) "tarea para hoy" else "tareas para hoy", style = MaterialTheme.typography.titleMedium, color = c.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (overdue > 0) Chip("$overdue ${if (overdue == 1) "vencida" else "vencidas"}", c.danger)
            Chip("$done ${if (done == 1) "hecha" else "hechas"} hoy", c.success)
        }
    }
}

@Composable
private fun Chip(text: String, dot: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Lumi.colors.muted).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Lumi.colors.textSecondary)
    }
}

/** Resumen de Lumi: guardado; solo se anima cuando es nuevo. */
@Composable
private fun LumiBriefCard(briefing: BriefingState, onRefresh: () -> Unit, onPlanDay: () -> Unit) {
    val c = Lumi.colors
    val animate = briefing.version > 0
    var typing by remember(briefing.version) { mutableStateOf(animate) }
    val state = when {
        briefing.isLoading -> LumiState.THINKING
        typing && briefing.text != null -> LumiState.SPEAKING
        else -> LumiState.IDLE
    }
    LumiCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LumiMark(state, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text("Lumi", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            EngineBadge(briefing.engine)
            IconButton(onClick = onRefresh, enabled = !briefing.isLoading, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Refresh, "Nuevo resumen", tint = c.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        val text = briefing.text
        if (text == null) {
            Text("Preparando tu resumen…", style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
        } else {
            TypewriterText(text, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, animate = animate, onFinished = { typing = false })
        }
        AnimatedVisibility(!typing || text == null) {
            Row(Modifier.padding(top = 14.dp)) { PillButton("¿Qué hago ahora?", onClick = onPlanDay) }
        }
    }
}

@Composable
private fun GemmaRow(state: GemmaModelManager.State, onDownload: () -> Unit) {
    val c = Lumi.colors
    LumiCard {
        Text("IA local", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        when (state) {
            is GemmaModelManager.State.Downloading -> {
                Text("Descargando Gemma… ${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = c.accent, trackColor = c.muted)
            }
            else -> {
                Text(
                    if (state is GemmaModelManager.State.Failed) "La descarga falló: ${state.reason}"
                    else "Descarga Gemma (2,6 GB, Wi-Fi) para que Lumi piense sin internet y en privado.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                PillButton("Descargar", style = PillStyle.SECONDARY, onClick = onDownload)
            }
        }
    }
}

/** Hoy: tareas (y vencidas) + eventos del calendario, en orden de hora. */
@Composable
private fun TodaySection(tasks: List<Task>, events: List<AgendaEvent>, onOpenAgenda: () -> Unit, onTaskClick: (Task) -> Unit) {
    val c = Lumi.colors
    val zone = remember { ZoneId.systemDefault() }
    val hm = remember { DateTimeFormatter.ofPattern("HH:mm") }

    data class Item(val sort: Long, val time: String, val title: String, val color: Color, val task: Task?, val sub: String?)
    val items = (tasks.map { t ->
        val overdue = t.dueAt?.let { DueDateFormatter.isOverdue(it, t.dueHasTime) } == true
        Item(
            t.dueAt ?: 0, if (overdue) "Vencida" else if (t.dueHasTime) Instant.ofEpochMilli(t.dueAt!!).atZone(zone).format(hm) else "Hoy",
            t.title, if (overdue) c.danger else t.category.color(c.isDark), t, t.meeting?.let { "Para «${it.title}»" }
        )
    } + events.map { e ->
        Item(e.begin, if (e.allDay) "Todo el día" else Instant.ofEpochMilli(e.begin).atZone(zone).format(hm), e.title, Color(e.color), null, e.calendarName)
    }).sortedBy { it.sort }

    Column {
        SectionHeader("Hoy") {
            Text("Agenda", style = MaterialTheme.typography.labelLarge, color = c.accentText, modifier = Modifier.clickable(onClick = onOpenAgenda).padding(4.dp))
        }
        if (items.isEmpty()) {
            Text("Nada programado. Buen momento para adelantar algo.", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        items.take(8).forEach { item ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .then(if (item.task != null) Modifier.clickable { onTaskClick(item.task) } else Modifier)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.time, style = MaterialTheme.typography.labelMedium, color = if (item.time == "Vencida") c.danger else c.textSecondary,
                    modifier = Modifier.width(72.dp))
                Box(Modifier.size(width = 3.dp, height = 32.dp).clip(RoundedCornerShape(2.dp)).background(item.color))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.sub?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
