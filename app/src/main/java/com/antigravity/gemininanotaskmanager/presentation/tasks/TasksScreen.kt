package com.antigravity.gemininanotaskmanager.presentation.tasks

import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
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
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
import com.antigravity.gemininanotaskmanager.presentation.components.LumiMark
import com.antigravity.gemininanotaskmanager.presentation.components.LumiState
import com.antigravity.gemininanotaskmanager.presentation.components.SectionHeader
import com.antigravity.gemininanotaskmanager.presentation.components.TaskRow
import com.antigravity.gemininanotaskmanager.presentation.main.Filters
import com.antigravity.gemininanotaskmanager.presentation.theme.Lumi
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
    onNew: () -> Unit
) {
    val c = Lumi.colors
    var showDone by rememberSaveable { mutableStateOf(false) }
    val groups = remember(tasks) { group(tasks) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Text("Tareas", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            item {
                Row(Modifier.padding(vertical = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterDropdown(
                        label = filters.category?.label ?: "Todas las áreas",
                        active = filters.category != null,
                        options = listOf<Pair<String, TaskCategory?>>("Todas las áreas" to null) + TaskCategory.entries.map { it.label to it },
                        onSelect = onCategoryFilter
                    )
                    FilterDropdown(
                        label = filters.status?.let { statusLabel(it) } ?: "Cualquier estado",
                        active = filters.status != null,
                        options = listOf<Pair<String, TaskStatus?>>("Cualquier estado" to null) + TaskStatus.entries.map { statusLabel(it) to it },
                        onSelect = onStatusFilter
                    )
                    FilterDropdown(
                        label = filters.priority?.let { "Prioridad ${it.label.lowercase()}" } ?: "Cualquier prioridad",
                        active = filters.priority != null,
                        options = listOf<Pair<String, TaskPriority?>>("Cualquier prioridad" to null) +
                            TaskPriority.entries.reversed().map { (if (it == TaskPriority.NONE) "Sin prioridad" else "Prioridad ${it.label.lowercase()}") to it },
                        onSelect = onPriorityFilter
                    )
                    FilterDropdown(
                        label = if (filters.sortByPriority) "Orden: prioridad" else "Orden: fecha",
                        active = false,
                        options = listOf<Pair<String, Boolean?>>("Orden: prioridad" to true, "Orden: fecha" to false),
                        onSelect = { onSortByPriority(it ?: true) }
                    )
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
                        LumiMark(LumiState.IDLE, size = 56.dp)
                    }
                    Text(
                        if (filters.copy(sortByPriority = true) != Filters()) "Nada con estos filtros" else "Sin tareas. Toca + o háblale a Lumi.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    )
                }
            }

            groups.forEach { (title, list) ->
                val isDone = title == DONE_GROUP
                item(key = "h_$title") {
                    SectionHeader(title, Modifier.padding(start = 4.dp, top = 10.dp).then(if (isDone) Modifier.clickable { showDone = !showDone } else Modifier)) {
                        Text(if (isDone) "${list.size} ${if (showDone) "▲" else "▼"}" else "${list.size}",
                            style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                    }
                }
                if (!isDone || showDone) {
                    items(list, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggleDone = { onToggleDone(task) },
                            onTomorrow = { onTomorrow(task) },
                            onClick = { onEdit(task) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).size(56.dp)
                .shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.accent).clickable(onClick = onNew),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "Nueva tarea", tint = c.onAccent) }
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

private const val DONE_GROUP = "Hechas"

/** Agrupa por urgencia: Vencidas → Hoy → Próximas → Sin fecha → Hechas. */
private fun group(tasks: List<Task>): List<Pair<String, List<Task>>> {
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
        "Vencidas" to overdue,
        "Hoy" to todayList,
        "Próximas" to upcoming,
        "Sin fecha" to noDate,
        DONE_GROUP to closed
    ).filter { it.second.isNotEmpty() }
}
