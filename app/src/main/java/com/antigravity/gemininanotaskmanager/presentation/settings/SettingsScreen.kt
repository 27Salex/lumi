package com.antigravity.gemininanotaskmanager.presentation.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.antigravity.gemininanotaskmanager.data.ai.GemmaLocalEngine
import com.antigravity.gemininanotaskmanager.data.ai.TaskPhraseParser
import com.antigravity.gemininanotaskmanager.service.live.LiveUpdateManager
import androidx.compose.foundation.horizontalScroll
import com.antigravity.gemininanotaskmanager.data.ai.GemmaModelManager
import com.antigravity.gemininanotaskmanager.data.ai.GeminiNanoEngine
import com.antigravity.gemininanotaskmanager.data.settings.AppSettings
import com.antigravity.gemininanotaskmanager.data.sync.GoogleTasksSync
import com.antigravity.gemininanotaskmanager.presentation.components.ListDivider
import com.antigravity.gemininanotaskmanager.presentation.components.ListGroup
import com.antigravity.gemininanotaskmanager.presentation.components.ListRow
import com.antigravity.gemininanotaskmanager.presentation.components.PillButton
import com.antigravity.gemininanotaskmanager.presentation.components.PillStyle
import com.antigravity.gemininanotaskmanager.presentation.components.SectionHeader
import com.antigravity.gemininanotaskmanager.presentation.components.StatusText
import com.antigravity.gemininanotaskmanager.presentation.theme.Lumi
import com.antigravity.gemininanotaskmanager.service.wakeword.VoiceEnroller
import com.antigravity.gemininanotaskmanager.service.wakeword.VoskModelManager
import com.antigravity.gemininanotaskmanager.service.wakeword.WakePhrases

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onRequestCalendar: () -> Unit,
    onConnectGoogleTasks: () -> Unit,
    onEnableWakeWord: () -> Unit,
    onRetryWakeWord: () -> Unit,
    onRequestOverlay: () -> Unit,
    onSavePlaceHere: (key: String, label: String) -> Unit = { _, _ -> },
    onRequestBackgroundLocation: () -> Unit = {},
    memories: List<com.antigravity.gemininanotaskmanager.domain.assistant.MemoryRetriever.Memory> = emptyList(),
    aliases: List<com.antigravity.gemininanotaskmanager.presentation.agent.ContactAliases.Alias> = emptyList(),
    onRemoveAlias: (String) -> Unit = {},
    /** Añadir alias: (alias, nombre del contacto a buscar) → la Activity busca en la agenda (con permiso). */
    onAddAlias: (String, String) -> Unit = { _, _ -> },
    /** Asistente, alarma, accesos y rutinas (v3.6, ver AssistantSettings). */
    assistantSection: @Composable () -> Unit = {}
) {
    val c = Lumi.colors
    val context = LocalContext.current
    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val s = state.settings

    Column(
        Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = c.textPrimary) }
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }

        // ── Apariencia ──────────────────────────────────────────────────────
        SectionHeader("Apariencia", Modifier.padding(start = 4.dp))
        ListGroup {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SYSTEM" to "Sistema", "LIGHT" to "Claro", "DARK" to "Oscuro").forEach { (mode, label) ->
                    Segment(label, s.themeMode == mode, Modifier.weight(1f)) { actions.setThemeMode(mode) }
                }
            }
        }

        // ── Voz ─────────────────────────────────────────────────────────────
        SectionHeader("Voz", Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            ListRow("«Oye Lumi»", "Di su nombre para abrirla, sin tocar el móvil. Escucha local y sin internet.", trailing = {
                Toggle(s.wakeWordEnabled) { if (it) onEnableWakeWord() else actions.disableWakeWord() }
            })
            when (val m = state.extras.wakeModel) {
                is VoskModelManager.State.Downloading -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Descargando el modelo para reconocer tu voz (53 MB)… ${(m.progress * 100).toInt()} %" + if (m.attempt > 1) " · reintento ${m.attempt - 1}" else "",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                    )
                    Spacer(Modifier.height(6.dp)); Progress(m.progress)
                    Text("«Oye Lumi» ya funciona; esto solo hace falta para «Entrenar mi voz». Puedes salir: la descarga continúa.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                VoskModelManager.State.Installing -> Box(Modifier.padding(16.dp)) { StatusText("Instalando modelo…", c.warning) }
                is VoskModelManager.State.Failed -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StatusText("No se pudo descargar: ${m.reason}", c.danger) }
                    PillButton("Reintentar", style = PillStyle.SECONDARY, onClick = onRetryWakeWord)
                }
                else -> Unit
            }
            if (s.wakeWordEnabled && !state.system.overlayGranted) {
                ListDivider()
                ListRow("Abrir sobre otras apps", "Para que Lumi aparezca encima de lo que estés usando al oír su nombre",
                    onClick = onRequestOverlay, trailing = { Text("Permitir", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
            }
            if (s.wakeWordEnabled) {
                ListDivider()
                VoiceTraining(state.extras.voice, actions)
            }
            if (s.wakeWordEnabled) {
                ListDivider()
                ListRow("Solo con la pantalla encendida", "Ahorra batería", trailing = { Toggle(s.wakeWordScreenOnly, actions::setWakeWordScreenOnly) })
                ListDivider()
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    StatusText(
                        when {
                            state.system.wakeWordRunning -> "Escuchando «Oye Lumi»"
                            else -> "En pausa (abre la app para reactivar)"
                        },
                        if (state.system.wakeWordRunning) c.success else c.warning
                    )
                }
            }
            state.system.wakeWordError?.let { Box(Modifier.padding(16.dp)) { StatusText(it, c.danger) } }
            ListDivider()
            Column(Modifier.padding(16.dp)) {
                Text("Idioma de la voz", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.VOICE_LANGUAGES.forEach { (tag, label) ->
                        // "Español (España)" → "España", "Español (Latinoamérica)" → "Latinoamérica"
                        val short = label.substringAfter("(", label).removeSuffix(")").substringBefore(" (")
                        Segment(short, s.voiceLanguage == tag, Modifier.weight(1f)) { actions.setVoiceLanguage(tag) }
                    }
                }
            }
            ListDivider()
            ListRow("Respuestas habladas", "Cuando le hablas, Lumi te contesta en voz alta. Prueba «Oye Lumi, ¿qué tengo hoy?»",
                trailing = { Toggle(s.speakReplies, actions::setSpeakReplies) })
        }

        // ── Inteligencia ────────────────────────────────────────────────────
        SectionHeader("Inteligencia", Modifier.padding(start = 4.dp, top = 12.dp))
        Text("Lumi usa el primer cerebro disponible: Gemini Nano → Gemma local → Gemini en la nube → reglas.",
            style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
        ListGroup {
            ListRow("Gemma · en el dispositivo", "Privada, sin internet y gratis. 2,6 GB.", trailing = { Toggle(s.gemmaEnabled, actions::setGemmaEnabled) })
            ListDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val g = state.gemmaState) {
                    GemmaModelManager.State.Ready -> {
                        StatusText(
                            when (state.gemmaLoad) {
                                GemmaLocalEngine.LoadState.READY -> "Cargado y listo"
                                GemmaLocalEngine.LoadState.LOADING -> "Cargando modelo…"
                                GemmaLocalEngine.LoadState.ERROR -> "No se pudo cargar"
                                GemmaLocalEngine.LoadState.IDLE -> "Descargado"
                            },
                            if (state.gemmaLoad == GemmaLocalEngine.LoadState.ERROR) c.danger else c.success
                        )
                        val d = state.extras.gemmaDiagnostics
                        Text(
                            listOfNotNull("Backend ${d.backend ?: "—"}", d.loadMillis?.let { "carga ${it / 1000.0} s" },
                                d.lastLatencyMillis?.let { "última respuesta ${it / 1000.0} s" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = c.textTertiary
                        )
                        d.lastError?.let { StatusText("Último error: $it", c.danger) }
                        d.selfTestResult?.let { StatusText(it, if (it.startsWith("Error") || it.startsWith("Sin")) c.danger else c.success) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (d.selfTestRunning) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = c.accentText, strokeWidth = 2.dp)
                                Text("Pensando… (sin límite de tiempo)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            } else PillButton("Probar Gemma", style = PillStyle.SECONDARY, onClick = actions::testGemma)
                            PillButton("Eliminar", style = PillStyle.GHOST, onClick = actions::deleteGemma)
                        }
                    }
                    is GemmaModelManager.State.Downloading -> {
                        StatusText("Descargando ${(g.progress * 100).toInt()} % · solo Wi-Fi", c.warning)
                        Progress(g.progress)
                        PillButton("Cancelar", style = PillStyle.GHOST, onClick = actions::cancelGemmaDownload)
                    }
                    is GemmaModelManager.State.Failed -> {
                        StatusText(g.reason, c.danger)
                        PillButton("Reintentar", style = PillStyle.SECONDARY, onClick = actions::downloadGemma)
                    }
                    GemmaModelManager.State.NotDownloaded -> PillButton("Descargar Gemma", onClick = actions::downloadGemma)
                }
            }
            ListDivider()
            ListRow("Gemini en la nube", "Más potente. Necesita internet y una API key gratuita.", trailing = { Toggle(s.cloudEnabled, actions::setCloudEnabled) })
            if (s.cloudEnabled) {
                ListDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var showKey by remember { mutableStateOf(false) }
                    Field(s.cloudApiKey, actions::setApiKey, "API key", secret = !showKey) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostrar", tint = c.textTertiary,
                            modifier = Modifier.size(18.dp).clickable { showKey = !showKey })
                    }
                    Field(s.cloudModel, actions::setCloudModel, "Modelo")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Probar conexión", style = PillStyle.SECONDARY, onClick = actions::testCloud)
                        PillButton("Conseguir key", style = PillStyle.GHOST) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                    }
                    when (val t = state.system.cloudTest) {
                        CloudTest.Running -> StatusText("Probando…", c.warning)
                        CloudTest.Ok -> StatusText("Conexión correcta", c.success)
                        is CloudTest.Failed -> StatusText(t.message, c.danger)
                        CloudTest.Idle -> Unit
                    }
                    Text("En el nivel gratuito Google puede usar tus textos para mejorar sus productos.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
            ListDivider()
            ListRow("Gemini Nano · AICore", when (state.nanoStatus) {
                GeminiNanoEngine.Status.AVAILABLE -> "Disponible"
                GeminiNanoEngine.Status.DOWNLOADABLE -> "Compatible, falta descargar"
                GeminiNanoEngine.Status.DOWNLOADING -> "Descargando…"
                GeminiNanoEngine.Status.UNAVAILABLE -> "No disponible en este móvil"
                GeminiNanoEngine.Status.UNKNOWN -> "Comprobando…"
            }, onClick = actions::checkNano)
        }

        // ── Integraciones ───────────────────────────────────────────────────
        SectionHeader("Integraciones", Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            if (!state.system.calendarGranted) {
                ListRow("Google Calendar", "Ver tus eventos en la Agenda y vincular tareas a reuniones", onClick = onRequestCalendar, trailing = {
                    Text("Conectar", style = MaterialTheme.typography.labelLarge, color = c.accentText)
                })
            } else {
                ListRow("Crear eventos para citas", "Las tareas con hora aparecen en tu calendario", trailing = { Toggle(s.calendarSyncEnabled, actions::setCalendarSync) })
                if (s.calendarSyncEnabled) state.system.calendars.forEach { cal ->
                    ListDivider()
                    ListRow(cal.name, cal.account, onClick = { actions.selectCalendar(cal.id) },
                        leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(cal.color))) },
                        trailing = { if (cal.id == s.calendarId) Text("✓", style = MaterialTheme.typography.titleMedium, color = c.accentText) })
                }
            }
            ListDivider()
            val gt = state.extras.googleTasksStatus
            if (s.googleTasksEnabled) {
                ListRow("Google Tasks", when (gt) {
                    GoogleTasksSync.Status.Running -> "Sincronizando…"
                    is GoogleTasksSync.Status.Done -> "Sincronizado (↓${gt.pulled} ↑${gt.pushed})"
                    is GoogleTasksSync.Status.Error -> "Error: ${gt.message}"
                    GoogleTasksSync.Status.NeedsConsent -> "Hay que volver a dar permiso"
                    GoogleTasksSync.Status.Idle -> "Conectado · lista «Lumi»"
                }, onClick = if (gt == GoogleTasksSync.Status.NeedsConsent) onConnectGoogleTasks else actions::syncGoogleTasksNow, trailing = {
                    Text("Desconectar", style = MaterialTheme.typography.labelLarge, color = c.danger, modifier = Modifier.clickable(onClick = actions::disconnectGoogleTasks))
                })
            } else {
                ListRow("Google Tasks", "Sincroniza en los dos sentidos (gratis)", onClick = onConnectGoogleTasks, trailing = {
                    Text("Conectar", style = MaterialTheme.typography.labelLarge, color = c.accentText)
                })
            }
            state.system.googleTasksError?.let { Box(Modifier.padding(16.dp)) { StatusText(it, c.danger) } }
            var showGuide by remember { mutableStateOf(false) }
            ListDivider()
            ListRow("Configuración de Google Tasks", "Una sola vez, 10 min", onClick = { showGuide = !showGuide })
            if (showGuide) Box(Modifier.padding(16.dp)) { GoogleTasksGuide(context.packageName, state.system.signingSha1) }
        }

        // ── Asistente del sistema ───────────────────────────────────────────
        SectionHeader("Abrir Lumi desde cualquier sitio", Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            ListRow(
                "Asistente digital",
                if (state.system.isDefaultAssistant) "Lumi es tu asistente ✓ (mantén pulsado el botón lateral)" else "Elige Lumi como asistente digital del móvil",
                onClick = { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            )
            ListDivider()
            Text("También: desliza hacia abajo → Ajustes rápidos → añade el tile «Lumi», o comparte cualquier texto o enlace con Lumi.",
                style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(16.dp))
        }

        assistantSection()

        // ── Memoria ─────────────────────────────────────────────────────────
        SectionHeader("Memoria", Modifier.padding(start = 4.dp, top = 12.dp))
        Text("Lo que le pides a Lumi que recuerde («recuerda que el wifi de la oficina es…»). Solo se usa lo relacionado con cada pregunta y no sale del móvil.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        MemorySection(memories, actions)

        // ── Contactos rápidos ───────────────────────────────────────────────
        SectionHeader("Contactos rápidos", Modifier.padding(start = 4.dp, top = 12.dp))
        Text("Cómo llamas a tus contactos («mamá», «el jefe»). Lumi los aprende sola cuando eliges entre varios, e ignora prefijos como «AA».",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        AliasSection(aliases, onRemoveAlias, onAddAlias)

        // ── Lugares ─────────────────────────────────────────────────────────
        SectionHeader("Lugares", Modifier.padding(start = 4.dp, top = 12.dp))
        Text("Para avisos como «cuando llegue a casa, recuérdame…». Guarda cada lugar estando allí.",
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        PlacesSection(state.extras.places, actions, onSavePlaceHere, onRequestBackgroundLocation)
        MapsAppPicker(s.mapsApp, actions::setMapsApp)

        // ── Horario y avisos ────────────────────────────────────────────────
        SectionHeader("Horario y avisos", Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            Stepper("Empiezo a trabajar", "${s.workStartHour}:00",
                { actions.setWorkHours((s.workStartHour - 1).coerceAtLeast(0), s.workEndHour) },
                { actions.setWorkHours((s.workStartHour + 1).coerceAtMost(s.workEndHour - 1), s.workEndHour) })
            ListDivider()
            Stepper("Termino de trabajar", "${s.workEndHour}:00",
                { actions.setWorkHours(s.workStartHour, (s.workEndHour - 1).coerceAtLeast(s.workStartHour + 1)) },
                { actions.setWorkHours(s.workStartHour, (s.workEndHour + 1).coerceAtMost(24)) })
            ListDivider()
            Column(Modifier.padding(16.dp)) {
                Text("Aviso antes de una cita", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text("Lumi añade además la víspera y 10 min antes cuando hace falta", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        Segment(if (m < 60) "$m min" else "${m / 60} h", s.reminderLeadMinutes == m, Modifier.weight(1f)) { actions.setReminderLead(m) }
                    }
                }
            }
            ListDivider()
            ListRow("Próxima tarea en directo", "Chip con cuenta atrás en la barra de estado (Android 16), como el de Maps",
                trailing = { Toggle(s.liveUpdates, actions::setLiveUpdates) })
            if (s.liveUpdates) ChipStatusRow(state.system.chipStatus) {
                open(
                    if (Build.VERSION.SDK_INT >= 36) Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
            ListDivider()
            ListRow("Repaso de la tarde", "Lo que hiciste y lo que queda, con «Pasar a mañana» y respuesta por voz",
                trailing = { Toggle(s.checkInEnabled, actions::setCheckIn) })
            if (s.checkInEnabled) {
                ListDivider()
                Stepper("Hora del repaso", "${s.checkInHour}:00",
                    { actions.setCheckInHour((s.checkInHour - 1).coerceAtLeast(17)) },
                    { actions.setCheckInHour((s.checkInHour + 1).coerceAtMost(23)) })
            }
            if (!state.system.notificationsGranted) {
                ListDivider()
                ListRow("Notificaciones desactivadas", "Sin ellas no hay avisos", onClick = {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, trailing = { Text("Activar", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
            }
            if (!state.system.exactAlarmsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListDivider()
                ListRow("Avisos puntuales", "Sin este permiso pueden llegar unos minutos tarde", onClick = {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }, trailing = { Text("Permitir", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * «Entrenar mi voz» (como Voice Match de Google): 3 veces «Oye Lumi». Después Lumi solo se activa con tu voz.
 * La huella se queda en el móvil.
 */
@Composable
private fun VoiceTraining(voice: VoiceUi, actions: SettingsActions) {
    val c = Lumi.colors
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mi voz", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(
                    if (voice.trained) "Lumi solo responde a tu voz" else "Entrénala para que Lumi no se active con otras personas",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                )
            }
            if (voice.trained) StatusText("Entrenada", c.success)
        }
        if (voice.lastScore > 0f) {
            StatusText("Lo último que se pareció a «Oye Lumi»: ${(voice.lastScore * 100).toInt()} % (se activa desde ${(voice.sensitivity.detectorThreshold * 100).toInt()} %)", c.textTertiary)
        }
        voice.lastHeard?.let { h ->
            val sim = h.similarity?.let { " · voz ${(it * 100).toInt()} %" }.orEmpty()
            StatusText("Última vez oí «${h.text}»$sim · ${h.reason}", if (h.accepted) c.success else c.warning)
        }
        when (val t = voice.training) {
            VoiceEnroller.State.Loading -> StatusText("Preparando el micrófono…", c.warning)
            is VoiceEnroller.State.Listening, is VoiceEnroller.State.Retry -> {
                val collected = if (t is VoiceEnroller.State.Listening) t.collected else (t as VoiceEnroller.State.Retry).collected
                Text("Di «Oye Lumi» y haz una pausa (${collected + 1} de ${VoiceEnroller.SAMPLES})",
                    style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Progress(collected / VoiceEnroller.SAMPLES.toFloat())
                if (t is VoiceEnroller.State.Retry) StatusText("He oído «${t.heard}». Repítelo: «Oye Lumi».", c.warning)
                PillButton("Cancelar", style = PillStyle.GHOST, onClick = actions::cancelVoiceTraining)
            }
            is VoiceEnroller.State.Failed -> {
                StatusText(t.reason, c.danger)
                PillButton("Reintentar", style = PillStyle.SECONDARY, onClick = actions::startVoiceTraining)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (voice.trained) "Volver a entrenar" else "Entrenar mi voz",
                    style = if (voice.trained) PillStyle.SECONDARY else PillStyle.PRIMARY, onClick = actions::startVoiceTraining)
                if (voice.trained) PillButton("Borrar", style = PillStyle.GHOST, onClick = actions::deleteVoiceProfile)
            }
        }
        if (voice.trained) {
            Text("Exigencia con tu voz", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakePhrases.Sensitivity.entries.forEach { level ->
                    Segment(level.label, voice.sensitivity == level, Modifier.weight(1f)) { actions.setVoiceSensitivity(level) }
                }
            }
            Text("Si no te reconoce, prueba «Relajada» y vuelve a entrenar: Lumi aprende cómo lo dices tú. Si salta con otras personas, «Estricta».",
                style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}

/** «Añadir buscando la dirección»: nombre + búsqueda (Geocoder) → elegir resultado. */
@Composable
private fun AddPlaceBySearch(actions: SettingsActions) {
    val c = Lumi.colors
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.antigravity.gemininanotaskmanager.data.places.PlaceSearch.Result>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(450)
        results = actions.searchPlaces(query)
    }
    if (!open) {
        PillButton("Añadir buscando la dirección", style = PillStyle.GHOST) { open = true }
        return
    }
    Field(name, { name = it }, "Nombre (p. ej. Gimnasio, Casa de mis padres)")
    Field(query, { query = it }, "Dirección o sitio")
    results.forEach { res ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted)
                .clickable { actions.addPlace(name, res); open = false; name = ""; query = "" }.padding(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(res.name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(res.address, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Text("Guardar", style = MaterialTheme.typography.labelLarge, color = c.accentText)
        }
    }
}

@Composable
private fun AliasSection(
    aliases: List<com.antigravity.gemininanotaskmanager.presentation.agent.ContactAliases.Alias>,
    onRemove: (String) -> Unit,
    onAdd: (String, String) -> Unit
) {
    val c = Lumi.colors
    var alias by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    ListGroup {
        aliases.forEachIndexed { i, a ->
            if (i > 0) ListDivider()
            ListRow("«${a.alias}»", "${a.name} · ${a.number}", trailing = {
                Text("Borrar", style = MaterialTheme.typography.labelLarge, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onRemove(a.alias) }.padding(8.dp))
            })
        }
        if (aliases.isNotEmpty()) ListDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(alias, { alias = it }, "Cuando diga… (p. ej. mi madre)")
            Field(contact, { contact = it }, "…llama a este contacto (p. ej. AA Mamá)", trailing = {
                if (alias.isNotBlank() && contact.isNotBlank()) Text("Guardar", style = MaterialTheme.typography.labelLarge, color = c.accentText,
                    modifier = Modifier.clickable { onAdd(alias, contact); alias = ""; contact = "" }.padding(8.dp))
            })
        }
    }
}

@Composable
private fun MemorySection(memories: List<com.antigravity.gemininanotaskmanager.domain.assistant.MemoryRetriever.Memory>, actions: SettingsActions) {
    val c = Lumi.colors
    var draft by remember { mutableStateOf("") }
    ListGroup {
        memories.forEachIndexed { i, m ->
            if (i > 0) ListDivider()
            ListRow(m.text, null, trailing = {
                Text("Olvidar", style = MaterialTheme.typography.labelLarge, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { actions.deleteMemory(m.id) }.padding(8.dp))
            })
        }
        if (memories.isNotEmpty()) ListDivider()
        Column(Modifier.padding(16.dp)) {
            Field(draft, { draft = it }, "Añadir un recuerdo (p. ej. «Mi dentista es la Dra. López»)", trailing = {
                if (draft.isNotBlank()) Text("Guardar", style = MaterialTheme.typography.labelLarge, color = c.accentText,
                    modifier = Modifier.clickable { actions.addMemory(draft); draft = "" }.padding(8.dp))
            })
        }
    }
}

/** Explica si Android está mostrando el chip y lleva al permiso si hace falta. */
@Composable
private fun ChipStatusRow(status: LiveUpdateManager.ChipStatus, onOpenSystemSettings: () -> Unit) {
    val c = Lumi.colors
    when (status) {
        LiveUpdateManager.ChipStatus.BLOCKED -> {
            ListDivider()
            ListRow("Permitir el chip", "Android tiene desactivadas las actualizaciones en directo de Lumi", onClick = onOpenSystemSettings,
                trailing = { Text("Permitir", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        }
        LiveUpdateManager.ChipStatus.NOT_PROMOTED -> {
            ListDivider()
            ListRow("El sistema no muestra el chip", "La notificación funciona, pero tu versión de Android / One UI no la convierte en chip. Revisa el permiso.",
                onClick = onOpenSystemSettings, trailing = { Text("Revisar", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        }
        LiveUpdateManager.ChipStatus.ACTIVE -> Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { StatusText("Chip activo en la barra de estado", c.success) }
        LiveUpdateManager.ChipStatus.IDLE -> Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            StatusText("Aparecerá 2 h antes de tu próxima tarea con hora o reunión", c.textTertiary)
        }
        LiveUpdateManager.ChipStatus.UNSUPPORTED -> Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            StatusText("El chip necesita Android 16; mientras, verás la notificación con cuenta atrás", c.textTertiary)
        }
    }
}

/** App de mapas para «Cómo llegar» y «llévame a…». */
@Composable
private fun MapsAppPicker(current: String, onSelect: (String) -> Unit) {
    val c = Lumi.colors
    val context = LocalContext.current
    val apps = remember { com.antigravity.gemininanotaskmanager.presentation.nav.MapsLauncher.installedApps(context) }
    Spacer(Modifier.height(6.dp))
    ListGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App para «Cómo llegar»", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text("Se usa al decir «llévame a casa» o al tocar una reunión con dirección.",
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segment("Preguntar", current.isBlank()) { onSelect("") }
                apps.forEach { app -> Segment(app.label, current == app.packageName) { onSelect(app.packageName) } }
            }
            if (apps.isEmpty()) Text("No hay apps de mapas instaladas.", style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
    }
}

@Composable
private fun PlacesSection(
    places: PlacesUi,
    actions: SettingsActions,
    onSavePlaceHere: (String, String) -> Unit,
    onRequestBackgroundLocation: () -> Unit
) {
    val c = Lumi.colors
    var customName by remember { mutableStateOf("") }
    var addingCustom by remember { mutableStateOf(false) }
    ListGroup {
        places.saved.forEachIndexed { i, place ->
            if (i > 0) ListDivider()
            ListRow(place.label, place.address.ifBlank { "Guardado aquí" } + " · radio de ${place.radiusMeters.toInt()} m", trailing = {
                Text("Borrar", style = MaterialTheme.typography.labelLarge, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { actions.removePlace(place.key) }.padding(8.dp))
            })
        }
        // Lugares que ya usan tus tareas pero aún no están guardados
        places.missing.forEach { key ->
            if (places.saved.isNotEmpty() || key != places.missing.first()) ListDivider()
            val label = key.replaceFirstChar { it.uppercase() }
            ListRow("«$label» sin guardar", "Lo usa alguna tarea. Pulsa cuando estés allí.", trailing = {
                PillButton("Guardar aquí", style = PillStyle.SECONDARY) { onSavePlaceHere(key, label) }
            })
        }
        if (places.saved.isNotEmpty() || places.missing.isNotEmpty()) ListDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Guardar mi ubicación actual como…", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("casa" to "Casa", "trabajo" to "Trabajo").filter { (k, _) -> places.saved.none { it.key == k } }.forEach { (key, label) ->
                    PillButton(label, style = PillStyle.SECONDARY) { onSavePlaceHere(key, label) }
                }
                PillButton("Otro…", style = PillStyle.GHOST) { addingCustom = !addingCustom }
            }
            if (addingCustom) {
                Field(customName, { customName = it }, "Nombre (gimnasio, universidad…)", trailing = {
                    if (customName.isNotBlank()) Text("Guardar", style = MaterialTheme.typography.labelLarge, color = c.accentText,
                        modifier = Modifier.clickable {
                            val name = customName.trim()
                            onSavePlaceHere(TaskPhraseParser.normalizePlace(name), name.replaceFirstChar { it.uppercase() })
                            customName = ""; addingCustom = false
                        }.padding(8.dp))
                })
            }
            AddPlaceBySearch(actions)
            places.saving?.let { StatusText("Buscando tu ubicación para «$it»…", c.warning) }
            places.error?.let { StatusText(it, c.danger) }
        }
        if (places.saved.isNotEmpty() && !places.backgroundGranted) {
            ListDivider()
            ListRow("Ubicación «Todo el tiempo»", "Sin ella, los avisos por lugar solo funcionan con Lumi abierta", onClick = onRequestBackgroundLocation,
                trailing = { Text("Permitir", style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        }
    }
}

@Composable
internal fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lumi.colors
    Switch(checked, onChange, colors = SwitchDefaults.colors(
        checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.muted, uncheckedBorderColor = c.outline
    ))
}

@Composable
internal fun Segment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Lumi.colors
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentContainer else c.muted).clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.accentText else c.textSecondary, maxLines = 1) }
}

@Composable
private fun Progress(p: Float) = LinearProgressIndicator(
    progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
    color = Lumi.colors.accent, trackColor = Lumi.colors.muted
)

@Composable
internal fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = Lumi.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
        Segment("−", false, Modifier.width(40.dp), onMinus)
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(horizontal = 12.dp))
        Segment("+", false, Modifier.width(40.dp), onPlus)
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = Lumi.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accentText),
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
    }
}

/** Pasos para crear el cliente OAuth (gratis). Paquete y SHA-1 con botón copiar. */
@Composable
private fun GoogleTasksGuide(packageName: String, sha1: String) {
    val c = Lumi.colors
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    fun copy(label: String, value: String) = clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "1. console.cloud.google.com → crea un proyecto (gratis, sin tarjeta).",
            "2. APIs y servicios → Biblioteca → activa «Google Tasks API».",
            "3. Pantalla de consentimiento de OAuth → Externo → añade tu cuenta en «Usuarios de prueba».",
            "4. Credenciales → Crear → ID de cliente de OAuth → Android → pega paquete y SHA-1.",
            "5. Vuelve y pulsa «Conectar»."
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        listOf("Paquete" to packageName, "SHA-1" to sha1.ifBlank { "No disponible" }).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).clickable { copy(label, value) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                    Text(value, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                }
                Text("Copiar", style = MaterialTheme.typography.labelLarge, color = c.accentText)
            }
        }
    }
}
