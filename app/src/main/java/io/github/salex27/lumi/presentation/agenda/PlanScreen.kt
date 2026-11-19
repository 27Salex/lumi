package io.github.salex27.lumi.presentation.agenda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskKind
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.schedule.PlanBuilder
import io.github.salex27.lumi.presentation.components.uiLocale
import io.github.salex27.lumi.presentation.components.uiLabel
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.presentation.theme.color
import io.github.salex27.lumi.presentation.theme.icon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

enum class PlanMode { DAY, WEEK, HOURS }

/**
 * Lumi's main screen: the plan of the day or of the week, day by day in a compact vertical list (each day folds), or
 * the hour-by-hour timeline. Shows schedule blocks, reminders, tasks and the phone calendar's events together.
 */
@Composable
fun PlanScreen(
    plan: PlanState,
    agenda: AgendaUiState,
    onSelectDate: (LocalDate) -> Unit,
    onShiftDays: (Long) -> Unit,
    onShiftWeeks: (Long) -> Unit,
    onRequestCalendar: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
    onNewBlock: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val c = Lumi.colors
    var modeName by rememberSaveable { mutableStateOf(PlanMode.WEEK.name) }
    val mode = PlanMode.valueOf(modeName)
    val today = LocalDate.now()

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── Header: title, range and navigation ─────────────────────────
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                    Text(
                        if (mode == PlanMode.WEEK) weekRange(plan.weekStart) else longDay(plan.selected),
                        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary
                    )
                }
                val isCurrent = if (mode == PlanMode.WEEK) plan.weekStart == today.minusDays(today.dayOfWeek.value - 1L) else plan.selected == today
                if (!isCurrent) {
                    Text(
                        stringResource(R.string.today), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.muted).clickable { onSelectDate(today) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                IconButton(onClick = { if (mode == PlanMode.WEEK) onShiftWeeks(-1) else onShiftDays(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day), tint = c.textSecondary)
                }
                IconButton(onClick = { if (mode == PlanMode.WEEK) onShiftWeeks(1) else onShiftDays(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day), tint = c.textSecondary)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = c.textSecondary)
                }
            }
            ModeTabs(mode, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { modeName = it.name }

            when (mode) {
                PlanMode.HOURS -> Box(Modifier.weight(1f)) {
                    AgendaScreen(
                        state = agenda, onShiftDays = onShiftDays, onSelectDate = onSelectDate, onRequestCalendar = onRequestCalendar,
                        onTaskClick = onTaskClick, onToggleDone = onToggleDone, onNewBlock = onNewBlock, embedded = true
                    )
                }
                PlanMode.DAY -> {
                    val day = plan.days.firstOrNull { it.date == plan.selected }
                    LazyColumn(
                        Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (plan.selected == today && plan.overdue.isNotEmpty()) item("overdue") {
                            OverdueCard(plan.overdue, onTaskClick, onToggleDone)
                        }
                        if (day != null) item(day.date.toString()) {
                            DayCard(day, expanded = true, collapsible = false, onToggle = {}, onTaskClick = onTaskClick, onToggleDone = onToggleDone, onAdd = onNewBlock)
                        }
                        if (!agenda.calendarAllowed) item("cal") { CalendarHint(onRequestCalendar) }
                    }
                }
                PlanMode.WEEK -> {
                    // Folded by default: past days and empty days. Each tap flips that default for the day.
                    var flipped by rememberSaveable { mutableStateOf(listOf<String>()) }
                    LazyColumn(
                        Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (plan.overdue.isNotEmpty() && plan.days.any { it.date == today }) item("overdue") {
                            OverdueCard(plan.overdue, onTaskClick, onToggleDone)
                        }
                        items(plan.days, key = { it.date.toString() }) { day ->
                            val key = day.date.toString()
                            val default = !day.date.isBefore(today) && day.entries.isNotEmpty()
                            val expanded = default xor (key in flipped)
                            DayCard(
                                day, expanded = expanded, collapsible = true,
                                onToggle = { flipped = if (key in flipped) flipped - key else flipped + key },
                                onTaskClick = onTaskClick, onToggleDone = onToggleDone, onAdd = onNewBlock
                            )
                        }
                        if (!agenda.calendarAllowed) item("cal") { CalendarHint(onRequestCalendar) }
                    }
                }
            }
        }

        if (mode != PlanMode.HOURS) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
                    .shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.accent)
                    .clickable { onNewBlock(if (plan.selected.isBefore(today)) today else plan.selected) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.agenda_new_block), color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModeTabs(selected: PlanMode, modifier: Modifier = Modifier, onSelect: (PlanMode) -> Unit) {
    val c = Lumi.colors
    val labels = mapOf(PlanMode.DAY to R.string.plan_mode_day, PlanMode.WEEK to R.string.plan_mode_week, PlanMode.HOURS to R.string.plan_mode_hours)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        PlanMode.entries.forEach { m ->
            val sel = m == selected
            Text(
                stringResource(labels.getValue(m)), style = MaterialTheme.typography.labelLarge,
                color = if (sel) c.textPrimary else c.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) c.background else Color.Transparent)
                    .clickable { onSelect(m) }.padding(vertical = 8.dp)
            )
        }
    }
}

/** One day: a header (weekday, date, summary) that folds, and its entries in time order. */
@Composable
private fun DayCard(
    day: PlanBuilder.Day,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
    onAdd: (LocalDate) -> Unit
) {
    val c = Lumi.colors
    val today = LocalDate.now()
    val isToday = day.date == today
    val past = day.date.isBefore(today)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "fold")

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .then(if (isToday) Modifier.border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp)) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, uiLocale).trimEnd('.').uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = if (isToday) c.accentText else c.textTertiary
                )
                Text(
                    "${day.date.dayOfMonth}", fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = when { isToday -> c.accentText; past -> c.textTertiary; else -> c.textPrimary }
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(summary(day), style = MaterialTheme.typography.labelLarge, color = if (past) c.textSecondary else c.textPrimary, maxLines = 1)
                if (!expanded && day.entries.isNotEmpty()) {
                    Text(
                        day.entries.take(4).joinToString(" · ") { entryTitle(it) }, style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (collapsible) Icon(Icons.Default.KeyboardArrowDown, null, tint = c.textTertiary, modifier = Modifier.rotate(rotation))
        }

        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                if (day.entries.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onAdd(day.date) }.padding(start = 68.dp, end = 14.dp, top = 2.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.plan_nothing), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Add, stringResource(R.string.agenda_new_block), tint = c.accentText, modifier = Modifier.size(18.dp))
                    }
                }
                day.entries.forEach { e ->
                    Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.outline))
                    EntryRow(e, onTaskClick, onToggleDone)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: PlanBuilder.Entry, onTaskClick: (Task) -> Unit, onToggleDone: (Task) -> Unit) {
    val c = Lumi.colors
    val zone = remember { ZoneId.systemDefault() }
    val hm = remember { DateTimeFormatter.ofPattern("HH:mm") }
    fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(hm)

    val item = entry as? PlanBuilder.Entry.Item
    val task = item?.task
    val done = task != null && !item.projected && task.status == TaskStatus.COMPLETED
    val faded = done || item?.projected == true || (task != null && !task.isActive)
    val bar = when {
        entry is PlanBuilder.Entry.Event -> Color(entry.event.color)
        task!!.kind == TaskKind.BLOCK -> task.category.color(c.isDark)
        task.kind == TaskKind.REMINDER -> c.textTertiary
        else -> c.accent
    }

    Row(
        Modifier.fillMaxWidth().then(if (task != null) Modifier.clickable { onTaskClick(task) } else Modifier)
            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val start = entry.start
            if (start != null) {
                Text(clock(start), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (faded) c.textTertiary else c.textPrimary)
                entry.end?.let { Text(clock(it), fontSize = 11.sp, color = c.textTertiary) }
            } else {
                Text(stringResource(R.string.plan_all_day), fontSize = 10.sp, color = c.textTertiary, maxLines = 2, lineHeight = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(2.dp)).background(bar.copy(alpha = if (faded) 0.4f else 1f)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entryTitle(entry), fontSize = 14.sp, color = if (faded) c.textTertiary else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    entry is PlanBuilder.Entry.Event -> Icons.Outlined.Event
                    item?.projected == true -> Icons.Outlined.Repeat
                    else -> task!!.kind.icon()
                }
                Icon(icon, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(meta(entry), fontSize = 11.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (task != null && task.kind == TaskKind.TASK && item.projected.not()) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { onToggleDone(task) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .then(if (done) Modifier.background(c.accent) else Modifier.border(1.5.dp, c.textTertiary, CircleShape)),
                    contentAlignment = Alignment.Center
                ) { if (done) Icon(Icons.Default.Check, null, tint = c.onAccent, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}

@Composable
private fun OverdueCard(tasks: List<Task>, onTaskClick: (Task) -> Unit, onToggleDone: (Task) -> Unit) {
    val c = Lumi.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)) {
        Text(
            stringResource(R.string.plan_overdue, tasks.size), style = MaterialTheme.typography.labelLarge, color = c.danger,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
        )
        tasks.take(6).forEach { t ->
            Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.outline))
            EntryRow(PlanBuilder.Entry.Item(t, null, null), onTaskClick, onToggleDone)
        }
    }
}

@Composable
private fun CalendarHint(onRequestCalendar: () -> Unit) {
    val c = Lumi.colors
    Text(
        stringResource(R.string.agenda_connect_calendar), color = c.textPrimary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentContainer)
            .clickable(onClick = onRequestCalendar).padding(12.dp)
    )
}

// ── Labels ─────────────────────────────────────────────────────────────────

@Composable
private fun summary(day: PlanBuilder.Day): String {
    val parts = mutableListOf<String>()
    if (day.plannedMinutes > 0) parts += stringResource(R.string.plan_planned, duration(day.plannedMinutes))
    val tasks = day.openTasks + day.doneTasks
    if (tasks > 0) parts += stringResource(R.string.plan_tasks_done, day.doneTasks, tasks)
    val others = day.entries.count { it is PlanBuilder.Entry.Event || (it as PlanBuilder.Entry.Item).task.kind == TaskKind.REMINDER }
    if (others > 0 && parts.isEmpty()) parts += stringResource(R.string.plan_items, day.entries.size)
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.plan_free) }
}

@Composable
private fun meta(entry: PlanBuilder.Entry): String = when (entry) {
    is PlanBuilder.Entry.Event -> entry.event.calendarName
    is PlanBuilder.Entry.Item -> {
        val t = entry.task
        // Compact: the kind is already the icon; reminders say so, blocks give their length, the rest their area
        listOfNotNull(
            if (t.kind == TaskKind.REMINDER) stringResource(R.string.kind_one_reminder) else t.category.uiLabel,
            t.durationMinutes?.takeIf { t.kind == TaskKind.BLOCK }?.let { duration(it) },
            if (entry.projected) stringResource(R.string.plan_repeats) else null
        ).joinToString(" · ")
    }
}

private fun entryTitle(e: PlanBuilder.Entry) = when (e) {
    is PlanBuilder.Entry.Item -> e.task.title
    is PlanBuilder.Entry.Event -> e.event.title
}

private fun duration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

private fun weekRange(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    val dm = DateTimeFormatter.ofPattern("d MMM", uiLocale)
    return if (monday.month == sunday.month) "${monday.dayOfMonth}–${sunday.format(dm)}" else "${monday.format(dm)} – ${sunday.format(dm)}"
}

private fun longDay(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(io.github.salex27.lumi.presentation.components.uiDayPattern, uiLocale)).replaceFirstChar { it.uppercase() }
