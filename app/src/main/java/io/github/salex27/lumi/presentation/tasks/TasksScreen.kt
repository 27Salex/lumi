package io.github.salex27.lumi.presentation.tasks

import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.presentation.components.uiLabel

import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.TaskPriority
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextAlign
import io.github.salex27.lumi.domain.model.TaskKind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.presentation.components.LumiMark
import io.github.salex27.lumi.presentation.components.LumiState
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.TaskRow
import io.github.salex27.lumi.presentation.main.Filters
import io.github.salex27.lumi.presentation.theme.Lumi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TasksScreen(
    tasks: List<Task>,
    filters: Filters,
    onCategoryFilter: (TaskCategory?) -> Unit,
    onStatusFilter: (TaskStatus?) -> Unit,
    onPriorityFilter: (TaskPriority?) -> Unit,
    onSortByPriority: (Boolean) -> Unit,
    onToggleDone: (Task) -> Unit,
    onTomorrow: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    onNew: (TaskKind) -> Unit
) {
    val c = Lumi.colors
    var showDone by rememberSaveable { mutableStateOf(false) }
    // null = everything; otherwise only tasks, reminders or schedule blocks
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindName?.let { TaskKind.fromString(it) }
    val shown = remember(tasks, kind) { if (kind == null) tasks else tasks.filter { it.kind == kind } }
    val groups = remember(shown) { group(shown) }
    val today = remember(tasks) { todayStats(tasks) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Column(Modifier.padding(start = 4.dp, top = 8.dp)) {
                    Text(stringResource(R.string.tab_tasks), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                    Text(
                        stringResource(R.string.tasks_summary, today.pending, today.dueToday),
                        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary
                    )
                }
            }
            if (today.total > 0) item { TodayProgress(today.done, today.total, Modifier.padding(top = 14.dp)) }
            item {
                KindTabs(kind, tasks, Modifier.padding(top = 14.dp)) { kindName = it?.name }
            }
            item {
                val allAreas = stringResource(R.string.filter_all_areas)
                val anyStatus = stringResource(R.string.filter_any_status)
                val anyPriority = stringResource(R.string.filter_any_priority)
                val noPriority = stringResource(R.string.filter_no_priority)
                val priorityX = stringResource(R.string.filter_priority_x)
                val byPriority = stringResource(R.string.sort_by_priority)
                val byDate = stringResource(R.string.sort_by_date)
                fun priorityName(p: TaskPriority) = if (p == TaskPriority.NONE) noPriority
                    else priorityX.format(p.uiLabel.let { if (ReplyLanguage.app == Lang.EN) it else it.lowercase() })
                Row(Modifier.padding(top = 10.dp, bottom = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterDropdown(
                        label = filters.category?.uiLabel ?: allAreas,
                        active = filters.category != null,
                        options = listOf<Pair<String, TaskCategory?>>(allAreas to null) + TaskCategory.entries.map { it.uiLabel to it },
                        onSelect = onCategoryFilter
                    )
                    FilterDropdown(
                        label = filters.status?.let { statusLabel(it) } ?: anyStatus,
                        active = filters.status != null,
                        options = listOf<Pair<String, TaskStatus?>>(anyStatus to null) + TaskStatus.entries.map { statusLabel(it) to it },
                        onSelect = onStatusFilter
                    )
                    FilterDropdown(
                        label = filters.priority?.let { priorityName(it) } ?: anyPriority,
                        active = filters.priority != null,
                        options = listOf<Pair<String, TaskPriority?>>(anyPriority to null) +
                            TaskPriority.entries.reversed().map { priorityName(it) to it },
                        onSelect = onPriorityFilter
                    )
                    FilterDropdown(
                        label = if (filters.sortByPriority) byPriority else byDate,
                        active = false,
                        options = listOf<Pair<String, Boolean?>>(byPriority to true, byDate to false),
                        onSelect = { onSortByPriority(it ?: true) }
                    )
                }
            }

            if (shown.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
                        LumiMark(LumiState.IDLE, size = 56.dp)
                    }
                    Text(
                        stringResource(
                            when {
                                filters.copy(sortByPriority = true) != Filters() -> R.string.tasks_empty_filtered
                                kind == TaskKind.REMINDER -> R.string.tasks_empty_reminders
                                kind == TaskKind.BLOCK -> R.string.tasks_empty_schedule
                                else -> R.string.tasks_empty
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    )
                }
            }

            groups.forEach { (title, list) ->
                val isDone = title == DONE_GROUP
                item(key = "h_$title") {
                    SectionHeader(stringResource(title), Modifier.padding(start = 4.dp, top = 10.dp).then(if (isDone) Modifier.clickable { showDone = !showDone } else Modifier)) {
                        Text(if (isDone) "${list.size} ${if (showDone) "▲" else "▼"}" else "${list.size}",
                            style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                    }
                }
                if (!isDone || showDone) {
                    // Each group is one rounded card: the first and last rows carry the corners
                    itemsIndexed(list, key = { _, t -> t.id }) { i, task ->
                        val shape = cardShape(i, list.size)
                        Column(Modifier.animateItem().clip(shape).background(c.surface)) {
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(0.5.dp).background(c.outline))
                            TaskRow(
                                task = task,
                                onToggleDone = { onToggleDone(task) },
                                onTomorrow = { onTomorrow(task) },
                                onClick = { onEdit(task) },
                                container = c.surface
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).size(56.dp)
                .shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.accent).clickable { onNew(kind ?: TaskKind.TASK) },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, stringResource(R.string.new_task), tint = c.onAccent) }
    }
}

@Composable
private fun <T> FilterDropdown(label: String, active: Boolean, options: List<Pair<String, T?>>, onSelect: (T?) -> Unit) {
    val c = Lumi.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (active) c.accentContainer else c.muted)
                .border(if (active) 1.dp else 0.dp, if (active) c.accent else c.muted, RoundedCornerShape(50))
                .clickable { open = true }.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) c.accentText else c.textPrimary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(c.elevated)) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text, color = c.textPrimary) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

private val DONE_GROUP = R.string.group_done

private fun cardShape(index: Int, size: Int): RoundedCornerShape {
    val r = 18.dp
    return when {
        size == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == size - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

private data class TodayStats(val pending: Int, val dueToday: Int, val done: Int, val total: Int)

/** Header numbers: active to-dos, how many are for today, and today's progress (to-dos only). */
private fun todayStats(tasks: List<Task>): TodayStats {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val todos = tasks.filter { it.kind == TaskKind.TASK && it.status != TaskStatus.CANCELLED }
    val ofToday = todos.filter { t ->
        val day = t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        day == today || (t.status == TaskStatus.COMPLETED && t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == today)
    }
    return TodayStats(
        pending = todos.count { it.isActive },
        dueToday = ofToday.count { it.isActive },
        done = ofToday.count { it.status == TaskStatus.COMPLETED },
        total = ofToday.size
    )
}

@Composable
private fun TodayProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val c = Lumi.colors
    val fraction by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, label = "today")
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.group_today), style = MaterialTheme.typography.labelLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.tasks_today_progress, done, total), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.muted)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (done == total) c.success else c.accent))
        }
    }
}

/** All / Tasks / Reminders / Schedule, with how many active items each has. */
@Composable
private fun KindTabs(selected: TaskKind?, tasks: List<Task>, modifier: Modifier = Modifier, onSelect: (TaskKind?) -> Unit) {
    val c = Lumi.colors
    val counts = remember(tasks) { tasks.filter { it.isActive }.groupingBy { it.kind }.eachCount() }
    val options = listOf<Pair<TaskKind?, Int>>(
        null to R.string.kind_all, TaskKind.TASK to R.string.kind_tasks,
        TaskKind.REMINDER to R.string.kind_reminders, TaskKind.BLOCK to R.string.kind_schedule
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (k, label) ->
            val sel = k == selected
            val n = if (k == null) counts.values.sum() else counts[k] ?: 0
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) c.background else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(k) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = if (sel) c.textPrimary else c.textSecondary, maxLines = 1)
                Text("$n", style = MaterialTheme.typography.labelSmall, color = if (sel) c.accentText else c.textTertiary)
            }
        }
    }
}

/** Groups by urgency: Overdue → Today → Upcoming → No date → Done. Titles are string resources. */
private fun group(tasks: List<Task>): List<Pair<Int, List<Task>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    fun Task.day() = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val active = tasks.filter { it.isActive }
    val overdue = active.filter { t -> t.dueAt?.let { DueDateFormatter.isOverdue(it, t.dueHasTime) } == true }
    val todayList = active.filter { it !in overdue && it.day() == today }
    val upcoming = active.filter { it !in overdue && it.day()?.isAfter(today) == true }
    val noDate = active.filter { it.dueAt == null }
    val closed = tasks.filter { !it.isActive }
    return listOf(
        R.string.group_overdue to overdue,
        R.string.group_today to todayList,
        R.string.group_upcoming to upcoming,
        R.string.group_no_date to noDate,
        DONE_GROUP to closed
    ).filter { it.second.isNotEmpty() }
}
