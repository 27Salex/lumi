package io.github.salex27.lumi.presentation.tasks

import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource
import io.github.salex27.lumi.presentation.components.uiLabel
import io.github.salex27.lumi.presentation.components.uiLocale

import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.domain.model.AgendaEvent
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Place
import io.github.salex27.lumi.domain.model.NavDestination
import io.github.salex27.lumi.data.ai.TaskPhraseParser
import io.github.salex27.lumi.data.places.PlaceSearch
import io.github.salex27.lumi.data.places.PlacesStore
import io.github.salex27.lumi.domain.model.LinkedMeeting
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.presentation.components.PriorityFlag
import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskReminder
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.reminder.ReminderPlanner
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.presentation.theme.color
import io.github.salex27.lumi.presentation.theme.icon
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Task editor (new if `task.id == 0`). The description is optional and never filled in automatically.
 * AUTO reminders are decided by Lumi (shown as information); custom ones are added/removed here.
 */
@Composable
fun TaskEditSheet(
    task: Task,
    loadReminders: suspend (Long) -> List<TaskReminder>,
    loadMeetings: suspend () -> List<AgendaEvent>,
    onSave: (Task, addOffsets: List<Int>, removeIds: List<Long>) -> Unit,
    onDelete: (Task) -> Unit,
    onDismiss: () -> Unit,
    /** Saved places (key, name) for place reminders. */
    places: List<PlacesStore.SavedPlace> = emptyList(),
    /** Searches addresses / places (Geocoder). */
    searchPlaces: suspend (String) -> List<PlaceSearch.Result> = { emptyList() },
    /** Saves an address as a place ("Save as a place"). */
    onSavePlace: (PlacesStore.SavedPlace) -> Unit = {},
    /** "Directions" with the chosen maps app. */
    onDirections: (NavDestination) -> Unit = {}
) {
    val c = Lumi.colors
    val zone = remember { ZoneId.systemDefault() }
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description) }
    var category by remember { mutableStateOf(task.category) }
    var status by remember { mutableStateOf(task.status) }
    var date by remember { mutableStateOf(task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }) }
    var time by remember { mutableStateOf(task.dueAt?.takeIf { task.dueHasTime }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }) }
    var recurrence by remember { mutableStateOf(task.recurrence) }
    var meeting by remember { mutableStateOf(task.meeting) }
    var autoReminders by remember { mutableStateOf(task.autoReminders) }
    var priority by remember { mutableStateOf(task.priority) }
    var placeTrigger by remember { mutableStateOf(task.placeTrigger) }
    var askDiscard by remember { mutableStateOf(false) }
    val initialDate = remember { date }
    val initialTime = remember { time }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val existingReminders = remember { mutableStateListOf<TaskReminder>() }
    val removedIds = remember { mutableStateListOf<Long>() }
    val addedOffsets = remember { mutableStateListOf<Int>() }
    var meetings by remember { mutableStateOf<List<AgendaEvent>>(emptyList()) }
    LaunchedEffect(task.id) {
        existingReminders.clear(); existingReminders.addAll(loadReminders(task.id))
        meetings = loadMeetings()
    }

    fun buildTask(): Task {
        val dueAt = date?.atTime(time ?: LocalTime.of(9, 0))?.atZone(zone)?.toInstant()?.toEpochMilli()
        return task.copy(
            title = title.trim(), description = description.trim(), category = category, status = status,
            dueAt = dueAt, dueHasTime = dueAt != null && time != null, recurrence = recurrence,
            meeting = meeting, autoReminders = autoReminders, priority = priority, placeTrigger = placeTrigger
        )
    }

    /** Would anything be lost on closing? Also for a new task: only if something was changed. */
    fun isDirty(): Boolean {
        return title.trim() != task.title.trim() || description.trim() != task.description.trim() ||
            category != task.category || status != task.status || date != initialDate || time != initialTime ||
            recurrence != task.recurrence || meeting != task.meeting || autoReminders != task.autoReminders ||
            priority != task.priority || placeTrigger != task.placeTrigger ||
            addedOffsets.isNotEmpty() || removedIds.isNotEmpty()
    }

    fun save() {
        onSave(buildTask(), addedOffsets.toList(), removedIds.toList())
        onDismiss()
    }

    // Swiping down goes through confirmValueChange; "back" (and tapping outside on some Material versions) goes straight
    // to onDismissRequest → both paths ask if there are changes.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && isDirty()) { askDiscard = true; false } else true
        }
    )
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { if (isDirty()) askDiscard = true else onDismiss() },
        sheetState = sheetState,
        containerColor = c.background,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline)) }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Big editable title, Apple Notes style
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (title.isEmpty()) Text(stringResource(R.string.edit_title_hint), style = MaterialTheme.typography.headlineMedium, color = c.textTertiary)
                BasicTextField(
                    value = title, onValueChange = { title = it },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.fillMaxWidth()) {
                if (description.isEmpty()) Text(stringResource(R.string.edit_description_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
                BasicTextField(
                    value = description, onValueChange = { description = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textSecondary),
                    cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth()
                )
            }

            SectionHeader(stringResource(R.string.edit_area))
            ChipRow(TaskCategory.entries, category, { it.uiLabel }, leading = { cat ->
                Icon(cat.icon(), null, tint = cat.color(c.isDark), modifier = Modifier.size(16.dp))
            }) { category = it }

            SectionHeader(stringResource(R.string.edit_priority))
            ChipRow(TaskPriority.entries, priority, { it.uiLabel }, leading = { p ->
                if (p != TaskPriority.NONE) PriorityFlag(p, Modifier.size(16.dp))
            }) { priority = it }

            SectionHeader(stringResource(R.string.edit_status))
            ChipRow(TaskStatus.entries, status, { statusLabel(it) }) { status = it }

            SectionHeader(stringResource(R.string.edit_when))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(date?.let { DueDateFormatter.format(it.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), false, lang = ReplyLanguage.app).replaceFirstChar { ch -> ch.uppercase() } } ?: stringResource(R.string.edit_no_date)) { showDate = true }
                if (date != null) {
                    Pill(time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: stringResource(R.string.edit_add_time)) { showTime = true }
                    Pill(stringResource(R.string.edit_remove), subtle = true) { date = null; time = null }
                }
            }

            SectionHeader(stringResource(R.string.edit_repeat))
            RecurrenceEditor(recurrence, date) { recurrence = it }

            if (meetings.isNotEmpty() || meeting != null) {
                SectionHeader(stringResource(R.string.edit_meeting))
                MeetingPicker(meeting, meetings) { meeting = it }
            }

            SectionHeader(stringResource(R.string.edit_place))
            PlaceEditor(placeTrigger, places, searchPlaces, onSavePlace, onDirections) { placeTrigger = it }
            // "Text Roberto", "Call mum": who it is linked to and a button to try it
            TaskActionCard(title, placeTrigger, places)

            SectionHeader(stringResource(R.string.edit_reminders))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = c.accentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.edit_auto_reminders), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    Text(stringResource(R.string.edit_auto_reminders_sub), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Switch(autoReminders, { autoReminders = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
            }
            // Preview of what Lumi will schedule with the current values
            val preview = remember(date, time, meeting, autoReminders, category, status) {
                ReminderPlanner.plan(buildTask().copy(id = task.id), System.currentTimeMillis())
            }
            if (autoReminders) preview.forEach { p ->
                ReminderLine(DueDateFormatter.format(p.triggerAt, true, lang = ReplyLanguage.app).replaceFirstChar { it.uppercase() }, p.label, removable = false) {}
            }
            existingReminders.filter { it.kind == TaskReminder.Kind.CUSTOM && it.id !in removedIds }.forEach { r ->
                ReminderLine(DueDateFormatter.format(r.triggerAt, true, lang = ReplyLanguage.app).replaceFirstChar { it.uppercase() }, r.label, removable = true) { removedIds += r.id }
            }
            addedOffsets.forEach { off ->
                ReminderLine(stringResource(R.string.edit_before, ReminderPlanner.humanMinutes(off)), stringResource(R.string.edit_new), removable = true) { addedOffsets.remove(off) }
            }
            if (date != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60, 120, 1440).forEach { off ->
                        Pill("+ ${ReminderPlanner.humanMinutes(off)}", subtle = true) { if (off !in addedOffsets) addedOffsets += off }
                    }
                }
            } else {
                Text(stringResource(R.string.edit_set_date_first), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.id != 0L) PillButton(stringResource(R.string.delete), style = PillStyle.GHOST) { onDelete(task); onDismiss() }
                Spacer(Modifier.weight(1f))
                PillButton(stringResource(R.string.save), enabled = title.isNotBlank()) { save() }
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            containerColor = c.elevated,
            title = { Text(stringResource(R.string.discard_title), color = c.textPrimary) },
            text = { Text(stringResource(if (task.id == 0L) R.string.discard_text_new else R.string.discard_text), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; save() }, enabled = title.isNotBlank()) {
                    Text(stringResource(R.string.save), color = if (title.isNotBlank()) c.accentText else c.textTertiary)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askDiscard = false; onDismiss() }) { Text(stringResource(R.string.discard), color = c.danger) }
                    // If the system already lowered the sheet, it comes back up
                    TextButton(onClick = { askDiscard = false; scope.launch { sheetState.show() } }) { Text(stringResource(R.string.keep_editing), color = c.textSecondary) }
                }
            }
        )
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; showDate = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) } }
        ) { DatePicker(state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); showTime = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { time = null; showTime = false }) { Text(stringResource(R.string.no_time)) } },
            text = { TimePicker(state) }
        )
    }
}

/**
 * The task's place (v3.3): suggestions from your saved places, or any searched address. With a reminder on arrival /
 * on leaving, or without one (just a reference + "Directions"). A searched address can be saved as a place.
 */
@Composable
private fun PlaceEditor(
    current: PlaceTrigger?,
    places: List<PlacesStore.SavedPlace>,
    search: suspend (String) -> List<PlaceSearch.Result>,
    onSavePlace: (PlacesStore.SavedPlace) -> Unit,
    onDirections: (NavDestination) -> Unit,
    onChange: (PlaceTrigger?) -> Unit
) {
    val c = Lumi.colors
    val saved = places.associateBy { it.key }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceSearch.Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    // Search with a 450 ms pause while typing
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        loading = true
        kotlinx.coroutines.delay(450)
        results = search(query)
        loading = false
    }

    // Suggestions: None + saved places + the current place if it isn't a saved one (loose or dictated address)
    val noneLabel = stringResource(R.string.place_none)
    val otherLabel = stringResource(R.string.place_other_address)
    val keys = (listOf<String?>(null) + places.map { it.key } + listOfNotNull(current?.place?.takeIf { it !in saved })).distinct()
    ChipRow(keys + listOf("__buscar__"), if (searching) "__buscar__" else current?.place, { key ->
        when (key) {
            null -> noneLabel
            "__buscar__" -> otherLabel
            else -> saved[key]?.label ?: key.replaceFirstChar { ch -> ch.uppercase() }
        }
    }, leading = { key ->
        if (key != null) Icon(if (key == "__buscar__") Icons.Outlined.Search else Icons.Outlined.Place, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
    }) { key ->
        when (key) {
            "__buscar__" -> { searching = true; if (current != null && current.place !in saved) query = current.address ?: current.place }
            null -> { searching = false; onChange(null) }
            else -> {
                searching = false
                // Saved place: its key is used (its coordinates live in Settings → Places)
                onChange(if (key in saved) PlaceTrigger(key, current?.onArrive ?: true, current?.notify ?: true) else current)
            }
        }
    }

    if (searching) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.place_search_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth()
            )
        }
        if (loading) Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        results.forEach { res ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.outline, RoundedCornerShape(14.dp))
                    .clickable {
                        onChange(PlaceTrigger(res.name, current?.onArrive ?: true, current?.notify ?: true, res.lat, res.lng, res.address))
                        searching = false; query = ""
                    }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Place, null, tint = c.accentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(res.name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(res.address, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!loading && query.trim().length >= 3 && results.isEmpty()) {
            Text(stringResource(R.string.place_no_results), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }

    if (current != null && !searching) {
        current.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        // Reminder: on arrival / on leaving / none
        val mode = when { !current.notify -> 2; current.onArrive -> 0; else -> 1 }
        val modeLabels = listOf(stringResource(R.string.place_on_arrive), stringResource(R.string.place_on_leave), stringResource(R.string.place_no_reminder))
        ChipRow(listOf(0, 1, 2), mode, { modeLabels[it] }) { m ->
            onChange(current.copy(onArrive = m != 1, notify = m != 2))
        }
        val destination = when {
            current.isAdHoc -> NavDestination(current.place, current.address ?: current.place, current.lat, current.lng)
            current.place in saved -> saved.getValue(current.place).let { NavDestination(it.label, it.label, it.lat, it.lng) }
            else -> null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            destination?.let { d -> Pill(stringResource(R.string.action_directions)) { onDirections(d) } }
            if (current.isAdHoc) {
                Pill(stringResource(R.string.place_save_as)) {
                    val key = TaskPhraseParser.normalizePlace(current.place)
                    onSavePlace(PlacesStore.SavedPlace(key, current.place, current.lat!!, current.lng!!, address = current.address.orEmpty()))
                    onChange(current.copy(place = key, lat = null, lng = null, address = null))
                }
            }
        }
        if (!current.isAdHoc && current.place !in saved) {
            Text(stringResource(R.string.place_unknown, current.place),
                style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
    } else if (!searching && places.isEmpty()) {
        Text(stringResource(R.string.place_empty_hint),
            style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
    }
}

fun statusLabel(s: TaskStatus) = when (s) {
    TaskStatus.TODO -> ReplyLanguage.ui("Pendiente", "To do")
    TaskStatus.IN_PROGRESS -> ReplyLanguage.ui("En marcha", "In progress")
    TaskStatus.COMPLETED -> ReplyLanguage.ui("Hecha", "Done")
    TaskStatus.CANCELLED -> ReplyLanguage.ui("Cancelada", "Cancelled")
}

@Composable
private fun RecurrenceEditor(current: Recurrence?, date: LocalDate?, onChange: (Recurrence?) -> Unit) {
    val c = Lumi.colors
    val options = listOf<Pair<String, Recurrence?>>(
        stringResource(R.string.repeat_never) to null,
        stringResource(R.string.repeat_daily) to Recurrence(Recurrence.Frequency.DAILY),
        stringResource(R.string.repeat_weekdays) to Recurrence(Recurrence.Frequency.WEEKDAYS),
        stringResource(R.string.repeat_weekly) to Recurrence(Recurrence.Frequency.WEEKLY, setOf((date ?: LocalDate.now()).dayOfWeek)),
        stringResource(R.string.repeat_monthly) to Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = (date ?: LocalDate.now()).dayOfMonth)
    )
    ChipRow(options.map { it.first }, options.firstOrNull { it.second?.frequency == current?.frequency }?.first ?: options.first().first, { it }) { label ->
        onChange(options.first { it.first == label }.second)
    }
    if (current?.frequency == Recurrence.Frequency.WEEKLY) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                val on = d in current.daysOfWeek
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (on) c.accent else c.muted)
                        .clickable {
                            val days = if (on) current.daysOfWeek - d else current.daysOfWeek + d
                            if (days.isNotEmpty()) onChange(current.copy(daysOfWeek = days))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(d.getDisplayName(TextStyle.NARROW, uiLocale).uppercase(), style = MaterialTheme.typography.labelLarge, color = if (on) c.onAccent else c.textSecondary)
                }
            }
        }
    }
    current?.let { Text(it.label(ReplyLanguage.app), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
}

@Composable
private fun MeetingPicker(current: LinkedMeeting?, meetings: List<AgendaEvent>, onChange: (LinkedMeeting?) -> Unit) {
    val c = Lumi.colors
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d · HH:mm", uiLocale) }
    val zone = remember { ZoneId.systemDefault() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        current?.let {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Instant.ofEpochMilli(it.start).atZone(zone).format(fmt), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Icon(Icons.Default.Close, stringResource(R.string.meeting_unlink), tint = c.textSecondary, modifier = Modifier.size(18.dp).clickable { onChange(null) })
            }
        }
        if (current == null) meetings.take(5).forEach { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.outline, RoundedCornerShape(14.dp))
                    .clickable { onChange(LinkedMeeting(e.id, e.title, e.begin)) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(e.color)))
                Spacer(Modifier.width(10.dp))
                Text(e.title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun ReminderLine(whenText: String, label: String, removable: Boolean, onRemove: () -> Unit) {
    val c = Lumi.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.NotificationsNone, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(whenText, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        if (removable) Icon(Icons.Default.Close, stringResource(R.string.reminder_remove), tint = c.textSecondary,
            modifier = Modifier.padding(start = 8.dp).size(16.dp).clickable(onClick = onRemove))
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, leading: (@Composable (T) -> Unit)? = null, onSelect: (T) -> Unit) {
    val c = Lumi.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) c.accentContainer else c.muted)
                    .border(1.dp, if (sel) c.accent else c.muted, RoundedCornerShape(50))
                    .clickable { onSelect(o) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leading?.let { it(o); Spacer(Modifier.width(6.dp)) }
                Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (sel) c.accentText else c.textPrimary)
            }
        }
    }
}

@Composable
private fun Pill(label: String, subtle: Boolean = false, onClick: () -> Unit) {
    val c = Lumi.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (subtle) c.textSecondary else c.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.muted).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
