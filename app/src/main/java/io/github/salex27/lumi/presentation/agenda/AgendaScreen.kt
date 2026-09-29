package io.github.salex27.lumi.presentation.agenda
import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource
import io.github.salex27.lumi.presentation.components.uiLocale
import io.github.salex27.lumi.presentation.components.uiDayPattern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.salex27.lumi.presentation.theme.Lumi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.salex27.lumi.data.sync.DeviceCalendar
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private val HOUR_HEIGHT = 60.dp


/** Day view in the style of Google Calendar: timed tasks + events from the phone calendar. */
@Composable
fun AgendaScreen(
    state: AgendaUiState,
    onShiftDays: (Long) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onRequestCalendar: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggleDone: (Task) -> Unit
) {
    val c = Lumi.colors
    val zone = remember { ZoneId.systemDefault() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val isToday = state.date == LocalDate.now()

    // On open: scroll to the current time (or to 8:00 on other days)
    LaunchedEffect(state.date) {
        val hour = if (isToday) (LocalTime.now().hour - 1).coerceAtLeast(0) else 8
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * hour).roundToPx() })
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        // ── Header with day navigation ───────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.date.format(DateTimeFormatter.ofPattern(uiDayPattern, uiLocale)).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge, color = c.textPrimary
                )
                val taskCount = state.timedTasks.size + state.dayTasks.size
                Text(
                    stringResource(if (taskCount == 1) R.string.count_tasks_one else R.string.count_tasks_other, taskCount) + " · " +
                        stringResource(if (state.events.size == 1) R.string.count_events_one else R.string.count_events_other, state.events.size),
                    color = c.textSecondary, fontSize = 13.sp
                )
            }
            if (!isToday) {
                Text(
                    stringResource(R.string.today), color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.muted)
                        .clickable { onSelectDate(LocalDate.now()) }.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            IconButton(onClick = { onShiftDays(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day), tint = c.textSecondary) }
            IconButton(onClick = { onShiftDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day), tint = c.textSecondary) }
        }

        WeekStrip(state.date, state.busyDays, onSelectDate)

        if (!state.calendarAllowed) {
            Text(
                stringResource(R.string.agenda_connect_calendar),
                color = c.textPrimary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(c.accentContainer)
                    .clickable(onClick = onRequestCalendar).padding(12.dp)
            )
        }

        // ── All day / untimed ────────────────────────────────────────────────
        val allDay = state.events.filter { it.allDay }
        if (allDay.isNotEmpty() || state.dayTasks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allDay.forEach { e -> AllDayChip("🗓️ ${e.title}", Color(e.color)) {} }
                state.dayTasks.forEach { t -> AllDayChip("${t.category.emoji} ${t.title}", c.accent, done = t.status == TaskStatus.COMPLETED) { onTaskClick(t) } }
            }
        }

        // ── Timeline ─────────────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
                // Hour grid
                for (h in 0 until 24) {
                    Row(Modifier.offset(y = HOUR_HEIGHT * h).fillMaxWidth().height(HOUR_HEIGHT)) {
                        Text(
                            "%02d:00".format(h), color = c.textTertiary, fontSize = 11.sp,
                            modifier = Modifier.width(56.dp).padding(start = 12.dp).offset(y = (-7).dp)
                        )
                        Box(Modifier.weight(1f).height(1.dp).background(c.outline))
                    }
                }

                // Blocks (events + tasks), split into columns when they overlap
                val blocks = remember(state.events, state.timedTasks) {
                    layoutBlocks(
                        state.events.filter { !it.allDay }.map { Block.Event(it) } +
                            state.timedTasks.map { Block.TaskBlock(it) }
                    )
                }
                BoxWithConstraints(Modifier.fillMaxSize().padding(start = 56.dp, end = 12.dp)) {
                    val colWidth = maxWidth
                    blocks.forEach { placed ->
                        val startMin = minutesOfDay(placed.block.start, state.date, zone)
                        val endMin = minutesOfDay(placed.block.end, state.date, zone).coerceAtLeast(startMin + 30)
                        val width = colWidth / placed.columns
                        BlockView(
                            block = placed.block,
                            zone = zone,
                            modifier = Modifier
                                .offset(x = width * placed.column, y = HOUR_HEIGHT * (startMin / 60f))
                                .width(width - 2.dp)
                                .height((HOUR_HEIGHT * ((endMin - startMin) / 60f)) - 2.dp),
                            onTaskClick = onTaskClick,
                            onToggleDone = onToggleDone
                        )
                    }
                }

                // Línea de "ahora"
                if (isToday) {
                    val now = LocalTime.now()
                    val y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f)
                    Row(Modifier.offset(y = y - 4.dp).padding(start = 50.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.danger))
                        Box(Modifier.weight(1f).height(2.dp).background(c.danger))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(selected: LocalDate, busy: Set<LocalDate>, onSelect: (LocalDate) -> Unit) {
    val c = Lumi.colors
    val monday = selected.minusDays(selected.dayOfWeek.value - 1L)
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        (0 until 7).forEach { i ->
            val d = monday.plusDays(i.toLong())
            val isSel = d == selected
            Column(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .then(if (isSel) Modifier.background(c.accent) else Modifier)
                    .clickable { onSelect(d) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.dayOfWeek.getDisplayName(JTextStyle.SHORT, uiLocale).take(2).replaceFirstChar { it.uppercase() },
                    color = if (isSel) c.onAccent else c.textSecondary, fontSize = 11.sp)
                Text("${d.dayOfMonth}", color = if (isSel) c.onAccent else if (d == today) c.accentText else c.textPrimary,
                    fontSize = 16.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Medium)
                Box(Modifier.size(4.dp).clip(CircleShape).background(if (d in busy && !isSel) c.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun AllDayChip(label: String, color: Color, done: Boolean = false, onClick: () -> Unit) {
    val c = Lumi.colors
    Text(
        label, color = c.textPrimary, fontSize = 12.sp, maxLines = 1,
        textDecoration = if (done) TextDecoration.LineThrough else null,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.25f))
            .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun BlockView(block: Block, zone: ZoneId, modifier: Modifier, onTaskClick: (Task) -> Unit, onToggleDone: (Task) -> Unit) {
    val c = Lumi.colors
    val hm = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val time = Instant.ofEpochMilli(block.start).atZone(zone).format(hm)
    when (block) {
        is Block.Event -> Column(
            modifier.clip(RoundedCornerShape(8.dp)).background(Color(block.event.color).copy(alpha = 0.35f))
                .border(0.5.dp, Color(block.event.color).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(block.event.title, color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$time · ${block.event.calendarName}", color = c.textSecondary, fontSize = 10.sp, maxLines = 1)
        }
        is Block.TaskBlock -> {
            val t = block.task
            val done = t.status == TaskStatus.COMPLETED
            Row(
                modifier.clip(RoundedCornerShape(8.dp)).background(c.accentContainer)
                    .border(1.dp, c.accent, RoundedCornerShape(8.dp))
                    .clickable { onTaskClick(t) }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier.padding(top = 1.dp).size(16.dp).clip(CircleShape)
                        .then(if (done) Modifier.background(c.accent) else Modifier.border(1.5.dp, c.textSecondary, CircleShape))
                        .clickable { onToggleDone(t) }
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        "${t.category.emoji} ${t.title}", color = if (done) c.textTertiary else c.textPrimary, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (done) TextDecoration.LineThrough else null
                    )
                    Text(time, color = c.textSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

// ── Splitting overlapping blocks into columns ──────────────────────────────

private sealed interface Block {
    val start: Long
    val end: Long

    data class Event(val event: AgendaEvent) : Block {
        override val start get() = event.begin
        override val end get() = event.end
    }

    data class TaskBlock(val task: Task) : Block {
        override val start get() = task.dueAt!!
        override val end get() = task.dueAt!! + DeviceCalendar.EVENT_DURATION_MS
    }
}

private data class Placed(val block: Block, val column: Int, val columns: Int)

/** Groups overlapping blocks and splits them into columns within each group (like Google Calendar). */
private fun layoutBlocks(blocks: List<Block>): List<Placed> {
    val sorted = blocks.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
    val result = mutableListOf<Placed>()
    var group = mutableListOf<Pair<Block, Int>>()
    var groupEnd = Long.MIN_VALUE

    fun flush() {
        val cols = (group.maxOfOrNull { it.second } ?: -1) + 1
        group.forEach { (b, c) -> result += Placed(b, c, cols) }
        group = mutableListOf()
    }

    for (b in sorted) {
        if (b.start >= groupEnd && group.isNotEmpty()) flush()
        val used = group.filter { (other, _) -> other.end > b.start }.map { it.second }.toSet()
        val col = generateSequence(0) { it + 1 }.first { it !in used }
        group += b to col
        groupEnd = maxOf(groupEnd, b.end)
    }
    flush()
    return result
}

private fun minutesOfDay(millis: Long, day: LocalDate, zone: ZoneId): Int {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return when {
        dt.toLocalDate().isBefore(day) -> 0
        dt.toLocalDate().isAfter(day) -> 24 * 60
        else -> dt.hour * 60 + dt.minute
    }
}

