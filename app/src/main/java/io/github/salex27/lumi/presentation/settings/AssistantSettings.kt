package io.github.salex27.lumi.presentation.settings
import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource
import io.github.salex27.lumi.domain.assistant.ReplyLanguage

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.domain.assistant.AlarmPlanner
import io.github.salex27.lumi.domain.assistant.Routine
import io.github.salex27.lumi.domain.weather.WeatherCodes
import io.github.salex27.lumi.domain.weather.WeatherAdvisor
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.service.notify.LumiNotificationListener
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Assistant settings (v3.6): morning summary, smart alarm, access (messages, Do Not Disturb), weather and routines.
 * Lives apart from SettingsScreen so that file doesn't grow even longer.
 */
@Composable
fun AssistantSettings(app: TaskManagerApplication) {
    val c = Lumi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.settings.collectAsStateWithLifecycle()
    val weather by app.weather.latest.collectAsStateWithLifecycle()
    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // Access is granted on system screens: checked again when coming back to Lumi
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val messagesOn = remember(resumed) { LumiNotificationListener.isEnabled(context) }
    val dndOn = remember(resumed) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }

    var alarmPreview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.alarmPrepMinutes, s.alarmTravelMinutes, s.alarmUsesWorkHours, s.workStartHour, resumed) {
        val date = AlarmPlanner.targetDate(LocalDateTime.now())
        alarmPreview = runCatching { app.repository.smartAlarmPlan(date) }.getOrNull()?.reason
            ?: context.getString(R.string.set_no_alarm_needed)
    }

    SectionHeader(stringResource(R.string.set_assistant), Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        ListRow(stringResource(R.string.set_morning), stringResource(R.string.set_morning_sub),
            trailing = { Toggle(s.morningEnabled) { on -> app.settings.update { it.copy(morningEnabled = on) }; app.morning.schedule() } })
        if (s.morningEnabled) {
            ListDivider()
            Stepper(stringResource(R.string.set_morning_time), "%d:%02d".format(s.morningMinutes / 60, s.morningMinutes % 60),
                { app.settings.update { it.copy(morningMinutes = it.morningMinutes - 15) }; app.morning.schedule() },
                { app.settings.update { it.copy(morningMinutes = it.morningMinutes + 15) }; app.morning.schedule() })
        }
        ListDivider()
        ListRow(stringResource(R.string.set_weather), weather?.let { w ->
            stringResource(R.string.set_weather_now, WeatherAdvisor.deg(w.currentTempC), w.place, WeatherCodes.describe(w.currentCode, ReplyLanguage.app),
                java.time.Instant.ofEpochMilli(w.fetchedAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm")))
        } ?: stringResource(R.string.set_weather_hint),
            onClick = { scope.launch { app.weather.forecast(force = true) } },
            trailing = { Text(stringResource(R.string.refresh), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
    }

    SectionHeader(stringResource(R.string.set_voice_conversation), Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.set_pause), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(stringResource(R.string.set_pause_sub), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(900 to stringResource(R.string.set_pause_short), 1_500 to stringResource(R.string.set_pause_normal), 2_500 to stringResource(R.string.set_pause_long)).forEach { (ms, label) ->
                    Segment(label, s.voicePauseMs == ms, Modifier.weight(1f)) { app.settings.update { it.copy(voicePauseMs = ms) } }
                }
            }
        }
        ListDivider()
        ListRow(stringResource(R.string.set_keep_listening), stringResource(R.string.set_keep_listening_sub),
            trailing = { Toggle(s.continueConversation) { on -> app.settings.update { it.copy(continueConversation = on) } } })
    }

    SectionHeader(stringResource(R.string.set_smart_alarm), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.set_smart_alarm_sub),
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        alarmPreview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.padding(16.dp))
            ListDivider()
        }
        Stepper(stringResource(R.string.set_get_ready), stringResource(R.string.n_min, s.alarmPrepMinutes),
            { app.settings.update { it.copy(alarmPrepMinutes = it.alarmPrepMinutes - 10) } },
            { app.settings.update { it.copy(alarmPrepMinutes = it.alarmPrepMinutes + 10) } })
        ListDivider()
        Stepper(stringResource(R.string.set_travel), stringResource(R.string.n_min, s.alarmTravelMinutes),
            { app.settings.update { it.copy(alarmTravelMinutes = it.alarmTravelMinutes - 5) } },
            { app.settings.update { it.copy(alarmTravelMinutes = it.alarmTravelMinutes + 5) } })
        ListDivider()
        ListRow(stringResource(R.string.set_work_hours), stringResource(R.string.set_work_hours_sub, s.workStartHour),
            trailing = { Toggle(s.alarmUsesWorkHours) { on -> app.settings.update { it.copy(alarmUsesWorkHours = on) } } })
        ListDivider()
        ListRow(stringResource(R.string.set_alarm_suggest), stringResource(R.string.set_alarm_suggest_sub),
            trailing = { Toggle(s.alarmSuggest) { on -> app.settings.update { it.copy(alarmSuggest = on) } } })
    }

    SectionHeader(stringResource(R.string.set_access), Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        ListRow(stringResource(R.string.set_read_messages), stringResource(if (messagesOn) R.string.set_read_messages_on else R.string.set_read_messages_off),
            onClick = { open(LumiNotificationListener.settingsIntent(context)) },
            trailing = { Text(stringResource(if (messagesOn) R.string.enabled else R.string.enable), style = MaterialTheme.typography.labelLarge, color = if (messagesOn) c.textTertiary else c.accentText) })
        ListDivider()
        ListRow(stringResource(R.string.set_dnd), stringResource(if (dndOn) R.string.set_dnd_on else R.string.set_dnd_off),
            onClick = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            trailing = { Text(stringResource(if (dndOn) R.string.enabled else R.string.enable), style = MaterialTheme.typography.labelLarge, color = if (dndOn) c.textTertiary else c.accentText) })
    }

    RoutinesSection(app)
    BackupSection(app)
}

/**
 * Backup: export to a file (Drive, Downloads…) and import it on another phone or another install.
 * After importing, the app restarts to read settings, places and routines again.
 */
@Composable
private fun BackupSection(app: TaskManagerApplication) {
    val c = Lumi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching { app.backup.export(uri) }.fold(
                { context.getString(R.string.backup_saved, it.tasks, it.memories) },
                { context.getString(R.string.backup_save_failed, it.message ?: "") }
            )
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.backup.import(uri) }.fold({ s ->
                status = context.getString(R.string.backup_imported, s.tasks, s.memories)
                kotlinx.coroutines.delay(1_500)
                // Clean restart: settings, places and routines are read from disk again
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            }, { status = context.getString(R.string.backup_import_failed, it.message ?: "") })
        }
    }

    SectionHeader(stringResource(R.string.backup), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.backup_sub),
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        ListRow(stringResource(R.string.backup_export), stringResource(R.string.backup_export_sub), onClick = {
            exportLauncher.launch(context.getString(R.string.backup_file_name, java.time.LocalDate.now().toString()))
        }, trailing = { Text(stringResource(R.string.action_export), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        ListDivider()
        ListRow(stringResource(R.string.backup_import), stringResource(R.string.backup_import_sub), onClick = {
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }, trailing = { Text(stringResource(R.string.action_import), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        status?.let {
            ListDivider()
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun RoutinesSection(app: TaskManagerApplication) {
    val c = Lumi.colors
    val routines by app.routines.routines.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }

    SectionHeader(stringResource(R.string.routines), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.routines_sub),
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        routines.forEachIndexed { i, r ->
            if (i > 0) ListDivider()
            if (editing == r.id) RoutineEditor(r, onSave = { app.routines.save(it); editing = null }, onDelete = { app.routines.remove(r.id); editing = null }, onCancel = { editing = null })
            else ListRow(r.name, "«${r.triggers.firstOrNull().orEmpty()}» → ${r.steps.joinToString(" · ")}",
                onClick = { editing = r.id },
                trailing = { Toggle(r.enabled) { on -> app.routines.save(r.copy(enabled = on)) } })
        }
        if (routines.isNotEmpty()) ListDivider()
        val newId = "custom-new"
        if (editing == newId) RoutineEditor(Routine(newId, "", emptyList(), emptyList()), onSave = {
            app.routines.save(it.copy(id = "custom-${System.currentTimeMillis()}")); editing = null
        }, onDelete = null, onCancel = { editing = null })
        else Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.routine_new), style = MaterialTheme.typography.labelLarge, color = c.accentText, modifier = Modifier.clickable { editing = newId })
            Text(stringResource(R.string.routine_restore), style = MaterialTheme.typography.labelLarge, color = c.textTertiary, modifier = Modifier.clickable { app.routines.restoreDefaults() })
        }
    }
}

@Composable
private fun RoutineEditor(routine: Routine, onSave: (Routine) -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit) {
    val c = Lumi.colors
    var name by remember { mutableStateOf(routine.name) }
    var triggers by remember { mutableStateOf(routine.triggers.joinToString(", ")) }
    var steps by remember { mutableStateOf(routine.steps.joinToString("\n")) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(name, { name = it }, stringResource(R.string.routine_name))
        Field(triggers, { triggers = it }, stringResource(R.string.routine_triggers))
        Column {
            Text(stringResource(R.string.routine_steps), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = steps, onValueChange = { steps = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accentText),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(12.dp)).background(c.muted).padding(12.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = triggers.split(',').map { it.trim() }.filter { it.isNotBlank() }
            val st = steps.lines().map { it.trim() }.filter { it.isNotBlank() }
            val valid = name.isNotBlank() && t.isNotEmpty() && st.isNotEmpty()
            Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = if (valid) c.accentText else c.textTertiary,
                modifier = Modifier.clickable(enabled = valid) { onSave(routine.copy(name = name.trim(), triggers = t, steps = st)) })
            Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.clickable(onClick = onCancel))
            onDelete?.let { Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, color = c.danger, modifier = Modifier.clickable(onClick = it)) }
        }
    }
}
