package io.github.salex27.lumi.presentation.settings

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
 * Ajustes del asistente (v3.6): resumen de la mañana, alarma inteligente, accesos (mensajes, No molestar),
 * el tiempo y las rutinas. Vive aparte de SettingsScreen para no hacer aún más largo ese fichero.
 */
@Composable
fun AssistantSettings(app: TaskManagerApplication) {
    val c = Lumi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.settings.collectAsStateWithLifecycle()
    val weather by app.weather.latest.collectAsStateWithLifecycle()
    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // Los accesos se dan en pantallas del sistema: se vuelven a mirar al volver a Lumi
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val messagesOn = remember(resumed) { LumiNotificationListener.isEnabled(context) }
    val dndOn = remember(resumed) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }

    var alarmPreview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.alarmPrepMinutes, s.alarmTravelMinutes, s.alarmUsesWorkHours, s.workStartHour, resumed) {
        val date = AlarmPlanner.targetDate(LocalDateTime.now())
        alarmPreview = runCatching { app.repository.smartAlarmPlan(date) }.getOrNull()?.reason
            ?: "Mañana no tienes nada antes de las 13:00: no hace falta alarma."
    }

    SectionHeader("Asistente", Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        ListRow("Resumen de la mañana", "El tiempo, lo primero del día y lo pendiente, en una notificación que puedes escuchar",
            trailing = { Toggle(s.morningEnabled) { on -> app.settings.update { it.copy(morningEnabled = on) }; app.morning.schedule() } })
        if (s.morningEnabled) {
            ListDivider()
            Stepper("Hora del resumen", "%d:%02d".format(s.morningMinutes / 60, s.morningMinutes % 60),
                { app.settings.update { it.copy(morningMinutes = it.morningMinutes - 15) }; app.morning.schedule() },
                { app.settings.update { it.copy(morningMinutes = it.morningMinutes + 15) }; app.morning.schedule() })
        }
        ListDivider()
        ListRow("El tiempo", weather?.let { w ->
            "${WeatherAdvisor.deg(w.currentTempC)} en ${w.place}, ${WeatherCodes.describe(w.currentCode)} · " +
                "actualizado a las ${java.time.Instant.ofEpochMilli(w.fetchedAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm"))}"
        } ?: "Pregúntale «¿qué tiempo hace?». Usa tu ubicación aproximada o «Casa» · Open-Meteo, gratis",
            onClick = { scope.launch { app.weather.forecast(force = true) } },
            trailing = { Text("Actualizar", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
    }

    SectionHeader("Conversación por voz", Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        Column(Modifier.padding(16.dp)) {
            Text("Pausa antes de enviar", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text("Cuánto silencio espera Lumi antes de dar por terminada la frase", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(900 to "Corta", 1_500 to "Normal", 2_500 to "Larga").forEach { (ms, label) ->
                    Segment(label, s.voicePauseMs == ms, Modifier.weight(1f)) { app.settings.update { it.copy(voicePauseMs = ms) } }
                }
            }
        }
        ListDivider()
        ListRow("Seguir escuchando", "Tras responderte por voz, Lumi escucha otra vez unos segundos para que sigas hablando",
            trailing = { Toggle(s.continueConversation) { on -> app.settings.update { it.copy(continueConversation = on) } } })
    }

    SectionHeader("Alarma inteligente", Modifier.padding(start = 4.dp, top = 12.dp))
    Text("Di «buenas noches» o «pon la alarma para mañana» y Lumi la pone según tu primera reunión, tarea con hora o tu hora de entrar a trabajar.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        alarmPreview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.padding(16.dp))
            ListDivider()
        }
        Stepper("Para arreglarme", "${s.alarmPrepMinutes} min",
            { app.settings.update { it.copy(alarmPrepMinutes = it.alarmPrepMinutes - 10) } },
            { app.settings.update { it.copy(alarmPrepMinutes = it.alarmPrepMinutes + 10) } })
        ListDivider()
        Stepper("Trayecto", "${s.alarmTravelMinutes} min",
            { app.settings.update { it.copy(alarmTravelMinutes = it.alarmTravelMinutes - 5) } },
            { app.settings.update { it.copy(alarmTravelMinutes = it.alarmTravelMinutes + 5) } })
        ListDivider()
        ListRow("Contar mi horario de trabajo", "De lunes a viernes, entrar a las ${s.workStartHour}:00 cuenta como lo primero",
            trailing = { Toggle(s.alarmUsesWorkHours) { on -> app.settings.update { it.copy(alarmUsesWorkHours = on) } } })
        ListDivider()
        ListRow("Proponerla en el repaso de la tarde", "Con un botón para ponerla sin abrir Lumi",
            trailing = { Toggle(s.alarmSuggest) { on -> app.settings.update { it.copy(alarmSuggest = on) } } })
    }

    SectionHeader("Accesos", Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        ListRow("Leer mis mensajes", if (messagesOn) "Activado · «¿qué me han escrito?», «respóndele que ya voy»"
        else "Para «¿qué me han escrito?» y responder sin abrir WhatsApp. Todo se queda en el móvil",
            onClick = { open(LumiNotificationListener.settingsIntent(context)) },
            trailing = { Text(if (messagesOn) "Activado" else "Activar", style = MaterialTheme.typography.labelLarge, color = if (messagesOn) c.textTertiary else c.accentText) })
        ListDivider()
        ListRow("No molestar", if (dndOn) "Activado · las rutinas pueden silenciar el móvil" else "Para que «buenas noches» active No molestar",
            onClick = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            trailing = { Text(if (dndOn) "Activado" else "Activar", style = MaterialTheme.typography.labelLarge, color = if (dndOn) c.textTertiary else c.accentText) })
    }

    RoutinesSection(app)
    BackupSection(app)
}

/**
 * Copia de seguridad: exportar a un fichero (Drive, Descargas…) e importarla en otro móvil o en otra instalación.
 * Tras importar, la app se reinicia para volver a leer ajustes, lugares y rutinas.
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
                { "Copia guardada: ${it.tasks} tareas, ${it.memories} recuerdos, ajustes, lugares y rutinas." },
                { "No se pudo guardar: ${it.message}" }
            )
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.backup.import(uri) }.fold({ s ->
                status = "Importado: ${s.tasks} tareas y ${s.memories} recuerdos nuevos. Reiniciando Lumi…"
                kotlinx.coroutines.delay(1_500)
                // Reinicio limpio: ajustes, lugares y rutinas se vuelven a leer del disco
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            }, { status = "No se pudo importar: ${it.message}" })
        }
    }

    SectionHeader("Copia de seguridad", Modifier.padding(start = 4.dp, top = 12.dp))
    Text("Guarda tus tareas, memoria, lugares, rutinas, contactos rápidos y ajustes en un fichero (por ejemplo en Drive) para cambiar de móvil o reinstalar. La API key de Gemini no se incluye.",
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        ListRow("Exportar copia", "Crea un fichero .json con todo", onClick = {
            exportLauncher.launch("lumi-copia-${java.time.LocalDate.now()}.json")
        }, trailing = { Text("Exportar", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        ListDivider()
        ListRow("Importar copia", "Añade lo de una copia a lo que ya tienes (no borra nada)", onClick = {
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }, trailing = { Text("Importar", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
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

    SectionHeader("Rutinas", Modifier.padding(start = 4.dp, top = 12.dp))
    Text("Una frase que lanza varias órdenes. Cada paso es algo que le dirías a Lumi: «alarma inteligente», «activa no molestar», «llévame a casa», «pon música relajante», «qué tengo mañana»…",
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
            Text("Nueva rutina", style = MaterialTheme.typography.labelLarge, color = c.accentText, modifier = Modifier.clickable { editing = newId })
            Text("Restaurar predefinidas", style = MaterialTheme.typography.labelLarge, color = c.textTertiary, modifier = Modifier.clickable { app.routines.restoreDefaults() })
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
        Field(name, { name = it }, "Nombre")
        Field(triggers, { triggers = it }, "Cuando diga… (separa varias frases con comas)")
        Column {
            Text("Lumi hará… (un paso por línea)", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
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
            Text("Guardar", style = MaterialTheme.typography.labelLarge, color = if (valid) c.accentText else c.textTertiary,
                modifier = Modifier.clickable(enabled = valid) { onSave(routine.copy(name = name.trim(), triggers = t, steps = st)) })
            Text("Cancelar", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.clickable(onClick = onCancel))
            onDelete?.let { Text("Borrar", style = MaterialTheme.typography.labelLarge, color = c.danger, modifier = Modifier.clickable(onClick = it)) }
        }
    }
}
