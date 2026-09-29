package com.antigravity.gemininanotaskmanager.presentation.tasks

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
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Place
import com.antigravity.gemininanotaskmanager.domain.model.NavDestination
import com.antigravity.gemininanotaskmanager.data.ai.TaskPhraseParser
import com.antigravity.gemininanotaskmanager.data.places.PlaceSearch
import com.antigravity.gemininanotaskmanager.data.places.PlacesStore
import com.antigravity.gemininanotaskmanager.domain.model.LinkedMeeting
import com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.presentation.components.PriorityFlag
import com.antigravity.gemininanotaskmanager.domain.model.Recurrence
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskReminder
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import com.antigravity.gemininanotaskmanager.domain.reminder.ReminderPlanner
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
import com.antigravity.gemininanotaskmanager.presentation.components.PillButton
import com.antigravity.gemininanotaskmanager.presentation.components.PillStyle
import com.antigravity.gemininanotaskmanager.presentation.components.SectionHeader
import com.antigravity.gemininanotaskmanager.presentation.theme.Lumi
import com.antigravity.gemininanotaskmanager.presentation.theme.color
import com.antigravity.gemininanotaskmanager.presentation.theme.icon
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
 * Editor de tarea (nueva si `task.id == 0`). La descripción es opcional y nunca se rellena sola.
 * Los avisos AUTO los decide Lumi (se muestran como información); los personalizados se añaden/quitan aquí.
 */
@Composable
fun TaskEditSheet(
    task: Task,
    loadReminders: suspend (Long) -> List<TaskReminder>,
    loadMeetings: suspend () -> List<AgendaEvent>,
    onSave: (Task, addOffsets: List<Int>, removeIds: List<Long>) -> Unit,
    onDelete: (Task) -> Unit,
    onDismiss: () -> Unit,
    /** Lugares guardados (clave, nombre) para los avisos por lugar. */
    places: List<PlacesStore.SavedPlace> = emptyList(),
    /** Busca direcciones / sitios (Geocoder). */
    searchPlaces: suspend (String) -> List<PlaceSearch.Result> = { emptyList() },
    /** Guarda una dirección como lugar («Guardar como lugar»). */
    onSavePlace: (PlacesStore.SavedPlace) -> Unit = {},
    /** «Cómo llegar» con la app de mapas elegida. */
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

    /** ¿Hay algo que se perdería al cerrar? Una tarea nueva sin título se cierra sin preguntar. */
    fun isDirty(): Boolean {
        if (task.id == 0L && title.isBlank()) return false
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

    // Deslizar hacia abajo pasa por confirmValueChange; «atrás» (y tocar fuera en algunas versiones de Material)
    // llega directamente a onDismissRequest → las dos rutas preguntan si hay cambios.
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
            // Título grande editable, estilo nota de Apple
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (title.isEmpty()) Text("Nueva tarea", style = MaterialTheme.typography.headlineMedium, color = c.textTertiary)
                BasicTextField(
                    value = title, onValueChange = { title = it },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.fillMaxWidth()) {
                if (description.isEmpty()) Text("Añadir descripción (opcional)", style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
                BasicTextField(
                    value = description, onValueChange = { description = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textSecondary),
                    cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth()
                )
            }

            SectionHeader("Área")
            ChipRow(TaskCategory.entries, category, { it.label }, leading = { cat ->
                Icon(cat.icon(), null, tint = cat.color(c.isDark), modifier = Modifier.size(16.dp))
            }) { category = it }

            SectionHeader("Prioridad")
            ChipRow(TaskPriority.entries, priority, { it.label }, leading = { p ->
                if (p != TaskPriority.NONE) PriorityFlag(p, Modifier.size(16.dp))
            }) { priority = it }

            SectionHeader("Estado")
            ChipRow(TaskStatus.entries, status, { statusLabel(it) }) { status = it }

            SectionHeader("Cuándo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(date?.let { DueDateFormatter.format(it.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), false).replaceFirstChar { ch -> ch.uppercase() } } ?: "Sin fecha") { showDate = true }
                if (date != null) {
                    Pill(time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "Añadir hora") { showTime = true }
                    Pill("Quitar", subtle = true) { date = null; time = null }
                }
            }

            SectionHeader("Repetir")
            RecurrenceEditor(recurrence, date) { recurrence = it }

            if (meetings.isNotEmpty() || meeting != null) {
                SectionHeader("Reunión")
                MeetingPicker(meeting, meetings) { meeting = it }
            }

            SectionHeader("Lugar")
            PlaceEditor(placeTrigger, places, searchPlaces, onSavePlace, onDirections) { placeTrigger = it }
            // «Escribir a Roberto», «Llamar a mamá»: a quién está vinculada y botón para probarlo
            TaskActionCard(title, placeTrigger, places)

            SectionHeader("Avisos")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = c.accentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Avisos automáticos de Lumi", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    Text("Lumi decide cuándo avisarte según el tipo de tarea", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Switch(autoReminders, { autoReminders = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
            }
            // Vista previa de lo que programará Lumi con los valores actuales
            val preview = remember(date, time, meeting, autoReminders, category, status) {
                ReminderPlanner.plan(buildTask().copy(id = task.id), System.currentTimeMillis())
            }
            if (autoReminders) preview.forEach { p ->
                ReminderLine(DueDateFormatter.format(p.triggerAt, true).replaceFirstChar { it.uppercase() }, p.label, removable = false) {}
            }
            existingReminders.filter { it.kind == TaskReminder.Kind.CUSTOM && it.id !in removedIds }.forEach { r ->
                ReminderLine(DueDateFormatter.format(r.triggerAt, true).replaceFirstChar { it.uppercase() }, r.label, removable = true) { removedIds += r.id }
            }
            addedOffsets.forEach { off ->
                ReminderLine("${ReminderPlanner.humanMinutes(off)} antes", "Nuevo", removable = true) { addedOffsets.remove(off) }
            }
            if (date != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60, 120, 1440).forEach { off ->
                        Pill("+ ${ReminderPlanner.humanMinutes(off)}", subtle = true) { if (off !in addedOffsets) addedOffsets += off }
                    }
                }
            } else {
                Text("Pon una fecha para añadir avisos.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.id != 0L) PillButton("Eliminar", style = PillStyle.GHOST) { onDelete(task); onDismiss() }
                Spacer(Modifier.weight(1f))
                PillButton("Guardar", enabled = title.isNotBlank()) { save() }
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            containerColor = c.elevated,
            title = { Text("¿Descartar cambios?", color = c.textPrimary) },
            text = { Text("Has modificado esta tarea. Si cierras ahora, se perderán los cambios.", color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; save() }, enabled = title.isNotBlank()) {
                    Text("Guardar", color = if (title.isNotBlank()) c.accentText else c.textTertiary)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askDiscard = false; onDismiss() }) { Text("Descartar", color = c.danger) }
                    // Si el sistema ya bajó la hoja, se vuelve a subir
                    TextButton(onClick = { askDiscard = false; scope.launch { sheetState.show() } }) { Text("Seguir editando", color = c.textSecondary) }
                }
            }
        )
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; showDate = false }) { Text("Aceptar") } },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancelar") } }
        ) { DatePicker(state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); showTime = false }) { Text("Aceptar") } },
            dismissButton = { TextButton(onClick = { time = null; showTime = false }) { Text("Sin hora") } },
            text = { TimePicker(state) }
        )
    }
}

/**
 * Lugar de la tarea (v3.3): sugerencias de tus lugares guardados, o cualquier dirección buscada. Con aviso al llegar /
 * al salir, o sin aviso (solo referencia + «Cómo llegar»). Una dirección buscada se puede guardar como lugar.
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
    // Búsqueda con pausa de 450 ms mientras escribes
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        loading = true
        kotlinx.coroutines.delay(450)
        results = search(query)
        loading = false
    }

    // Sugerencias: Ninguno + lugares guardados + el lugar actual si no es uno guardado (dirección suelta o dictado)
    val keys = (listOf<String?>(null) + places.map { it.key } + listOfNotNull(current?.place?.takeIf { it !in saved })).distinct()
    ChipRow(keys + listOf("__buscar__"), if (searching) "__buscar__" else current?.place, { key ->
        when (key) {
            null -> "Ninguno"
            "__buscar__" -> "Otra dirección…"
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
                // Lugar guardado: se usa la clave (sus coordenadas viven en Ajustes → Lugares)
                onChange(if (key in saved) PlaceTrigger(key, current?.onArrive ?: true, current?.notify ?: true) else current)
            }
        }
    }

    if (searching) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (query.isEmpty()) Text("Busca una dirección o un sitio…", style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentText), modifier = Modifier.fillMaxWidth()
            )
        }
        if (loading) Text("Buscando…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
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
            Text("Sin resultados. Prueba con la calle y la ciudad.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }

    if (current != null && !searching) {
        current.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        // Aviso: al llegar / al salir / sin aviso
        val mode = when { !current.notify -> 2; current.onArrive -> 0; else -> 1 }
        ChipRow(listOf(0, 1, 2), mode, { listOf("Avisar al llegar", "Avisar al salir", "Sin aviso")[it] }) { m ->
            onChange(current.copy(onArrive = m != 1, notify = m != 2))
        }
        val destination = when {
            current.isAdHoc -> NavDestination(current.place, current.address ?: current.place, current.lat, current.lng)
            current.place in saved -> saved.getValue(current.place).let { NavDestination(it.label, it.label, it.lat, it.lng) }
            else -> null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            destination?.let { d -> Pill("Cómo llegar") { onDirections(d) } }
            if (current.isAdHoc) {
                Pill("Guardar como lugar") {
                    val key = TaskPhraseParser.normalizePlace(current.place)
                    onSavePlace(PlacesStore.SavedPlace(key, current.place, current.lat!!, current.lng!!, address = current.address.orEmpty()))
                    onChange(current.copy(place = key, lat = null, lng = null, address = null))
                }
            }
        }
        if (!current.isAdHoc && current.place !in saved) {
            Text("Aún no sé dónde está «${current.place}». Toca «Otra dirección…» para buscarla, o guárdala en Ajustes → Lugares.",
                style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
    } else if (!searching && places.isEmpty()) {
        Text("Busca una dirección con «Otra dirección…» o guarda tus lugares (casa, trabajo…) en Ajustes → Lugares.",
            style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
    }
}

fun statusLabel(s: TaskStatus) = when (s) {
    TaskStatus.TODO -> "Pendiente"
    TaskStatus.IN_PROGRESS -> "En marcha"
    TaskStatus.COMPLETED -> "Hecha"
    TaskStatus.CANCELLED -> "Cancelada"
}

@Composable
private fun RecurrenceEditor(current: Recurrence?, date: LocalDate?, onChange: (Recurrence?) -> Unit) {
    val c = Lumi.colors
    val options = listOf<Pair<String, Recurrence?>>(
        "Nunca" to null,
        "Cada día" to Recurrence(Recurrence.Frequency.DAILY),
        "Laborables" to Recurrence(Recurrence.Frequency.WEEKDAYS),
        "Semanal" to Recurrence(Recurrence.Frequency.WEEKLY, setOf((date ?: LocalDate.now()).dayOfWeek)),
        "Mensual" to Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = (date ?: LocalDate.now()).dayOfMonth)
    )
    ChipRow(options.map { it.first }, options.firstOrNull { it.second?.frequency == current?.frequency }?.first ?: "Nunca", { it }) { label ->
        onChange(options.first { it.first == label }.second)
    }
    if (current?.frequency == Recurrence.Frequency.WEEKLY) {
        val es = Locale.forLanguageTag("es-ES")
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
                    Text(d.getDisplayName(TextStyle.NARROW, es).uppercase(), style = MaterialTheme.typography.labelLarge, color = if (on) c.onAccent else c.textSecondary)
                }
            }
        }
    }
    current?.let { Text(it.label(), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
}

@Composable
private fun MeetingPicker(current: LinkedMeeting?, meetings: List<AgendaEvent>, onChange: (LinkedMeeting?) -> Unit) {
    val c = Lumi.colors
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d · HH:mm", Locale.forLanguageTag("es-ES")) }
    val zone = remember { ZoneId.systemDefault() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        current?.let {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Instant.ofEpochMilli(it.start).atZone(zone).format(fmt), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Icon(Icons.Default.Close, "Desvincular", tint = c.textSecondary, modifier = Modifier.size(18.dp).clickable { onChange(null) })
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
        if (removable) Icon(Icons.Default.Close, "Quitar aviso", tint = c.textSecondary,
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
