package io.github.salex27.lumi.presentation.settings

import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource

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
import io.github.salex27.lumi.data.ai.GemmaLocalEngine
import io.github.salex27.lumi.data.ai.TaskPhraseParser
import io.github.salex27.lumi.service.live.LiveUpdateManager
import androidx.compose.foundation.horizontalScroll
import io.github.salex27.lumi.data.ai.GemmaModelManager
import io.github.salex27.lumi.data.ai.GeminiNanoEngine
import io.github.salex27.lumi.data.settings.AppSettings
import io.github.salex27.lumi.data.sync.GoogleTasksSync
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.StatusText
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.service.wakeword.VoiceEnroller
import io.github.salex27.lumi.service.wakeword.VoskModelManager
import io.github.salex27.lumi.service.wakeword.WakePhrases
import io.github.salex27.lumi.service.wakeword.WakePhrase

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
    memories: List<io.github.salex27.lumi.domain.assistant.MemoryRetriever.Memory> = emptyList(),
    aliases: List<io.github.salex27.lumi.presentation.agent.ContactAliases.Alias> = emptyList(),
    onRemoveAlias: (String) -> Unit = {},
    /** Add an alias: (alias, contact name to look up) → the Activity searches the address book (with permission). */
    onAddAlias: (String, String) -> Unit = { _, _ -> },
    /** Assistant, alarm, access and routines (v3.6, see AssistantSettings). */
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.textPrimary) }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }

        // ── Appearance ──────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.appearance), Modifier.padding(start = 4.dp))
        ListGroup {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SYSTEM" to stringResource(R.string.theme_system), "LIGHT" to stringResource(R.string.theme_light), "DARK" to stringResource(R.string.theme_dark)).forEach { (mode, label) ->
                    Segment(label, s.themeMode == mode, Modifier.weight(1f)) { actions.setThemeMode(mode) }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ListDivider()
                AppLanguagePicker()
            }
        }

        // ── Voice ───────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.voice), Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            ListRow(stringResource(R.string.wake_title), stringResource(R.string.wake_sub), trailing = {
                Toggle(s.wakeWordEnabled) { if (it) onEnableWakeWord() else actions.disableWakeWord() }
            })
            when (val m = state.extras.wakeModel) {
                is VoskModelManager.State.Downloading -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.wake_model_downloading, (m.progress * 100).toInt()) + if (m.attempt > 1) stringResource(R.string.wake_model_retry_n, m.attempt - 1) else "",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                    )
                    Spacer(Modifier.height(6.dp)); Progress(m.progress)
                    Text(stringResource(R.string.wake_model_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                VoskModelManager.State.Installing -> Box(Modifier.padding(16.dp)) { StatusText(stringResource(R.string.installing_model), c.warning) }
                is VoskModelManager.State.Failed -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StatusText(stringResource(R.string.download_failed, m.reason), c.danger) }
                    PillButton(stringResource(R.string.retry), style = PillStyle.SECONDARY, onClick = onRetryWakeWord)
                }
                else -> Unit
            }
            if (s.wakeWordEnabled && !state.system.overlayGranted) {
                ListDivider()
                ListRow(stringResource(R.string.overlay_title), stringResource(R.string.overlay_sub),
                    onClick = onRequestOverlay, trailing = { Text(stringResource(R.string.allow), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
            }
            if (s.wakeWordEnabled) {
                ListDivider()
                VoiceTraining(state.extras.voice, actions)
            }
            if (s.wakeWordEnabled) {
                ListDivider()
                ListRow(stringResource(R.string.wake_screen_on), stringResource(R.string.saves_battery), trailing = { Toggle(s.wakeWordScreenOnly, actions::setWakeWordScreenOnly) })
                ListDivider()
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    StatusText(
                        when {
                            state.system.wakeWordRunning -> stringResource(R.string.wake_listening)
                            else -> stringResource(R.string.wake_paused)
                        },
                        if (state.system.wakeWordRunning) c.success else c.warning
                    )
                }
            }
            state.system.wakeWordError?.let { Box(Modifier.padding(16.dp)) { StatusText(it, c.danger) } }
            ListDivider()
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.voice_language), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.VOICE_LANGUAGES.forEach { (tag, label) ->
                        // "Español (España)" → "España", "English (UK)" → "UK", Latin America → "Latam" (fits the segment)
                        val short = if (tag == "es-US") "Latam" else label.substringAfter("(", label).removeSuffix(")").substringBefore(" (")
                        Segment(short, s.voiceLanguage == tag, Modifier.weight(1f)) { actions.setVoiceLanguage(tag) }
                    }
                }
            }
            ListDivider()
            ListRow(stringResource(R.string.spoken_replies), stringResource(R.string.spoken_replies_sub),
                trailing = { Toggle(s.speakReplies, actions::setSpeakReplies) })
        }

        // ── Intelligence ────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.intelligence), Modifier.padding(start = 4.dp, top = 12.dp))
        Text(stringResource(R.string.intelligence_sub),
            style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
        ListGroup {
            ListRow(stringResource(R.string.gemma_title), stringResource(R.string.gemma_sub), trailing = { Toggle(s.gemmaEnabled, actions::setGemmaEnabled) })
            ListDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val g = state.gemmaState) {
                    GemmaModelManager.State.Ready -> {
                        StatusText(
                            when (state.gemmaLoad) {
                                GemmaLocalEngine.LoadState.READY -> stringResource(R.string.gemma_ready)
                                GemmaLocalEngine.LoadState.LOADING -> stringResource(R.string.gemma_loading)
                                GemmaLocalEngine.LoadState.ERROR -> stringResource(R.string.gemma_load_failed)
                                GemmaLocalEngine.LoadState.IDLE -> stringResource(R.string.gemma_downloaded)
                            },
                            if (state.gemmaLoad == GemmaLocalEngine.LoadState.ERROR) c.danger else c.success
                        )
                        val d = state.extras.gemmaDiagnostics
                        Text(
                            listOfNotNull(stringResource(R.string.gemma_backend, d.backend ?: "—"), d.loadMillis?.let { stringResource(R.string.gemma_load_time, "${it / 1000.0}") },
                                d.lastLatencyMillis?.let { stringResource(R.string.gemma_last_latency, "${it / 1000.0}") }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = c.textTertiary
                        )
                        d.lastError?.let { StatusText(stringResource(R.string.last_error, it), c.danger) }
                        d.selfTestResult?.let { StatusText(it, if (it.startsWith("«")) c.success else c.danger) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (d.selfTestRunning) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = c.accentText, strokeWidth = 2.dp)
                                Text(stringResource(R.string.gemma_thinking), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            } else PillButton(stringResource(R.string.gemma_test), style = PillStyle.SECONDARY, onClick = actions::testGemma)
                            PillButton(stringResource(R.string.delete), style = PillStyle.GHOST, onClick = actions::deleteGemma)
                        }
                    }
                    is GemmaModelManager.State.Downloading -> {
                        StatusText(stringResource(R.string.gemma_downloading_wifi, (g.progress * 100).toInt()), c.warning)
                        Progress(g.progress)
                        PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST, onClick = actions::cancelGemmaDownload)
                    }
                    is GemmaModelManager.State.Failed -> {
                        StatusText(g.reason, c.danger)
                        PillButton(stringResource(R.string.retry), style = PillStyle.SECONDARY, onClick = actions::downloadGemma)
                    }
                    GemmaModelManager.State.NotDownloaded -> PillButton(stringResource(R.string.gemma_download), onClick = actions::downloadGemma)
                }
            }
            ListDivider()
            ListRow(stringResource(R.string.cloud_title), stringResource(R.string.cloud_sub), trailing = { Toggle(s.cloudEnabled, actions::setCloudEnabled) })
            if (s.cloudEnabled) {
                ListDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var showKey by remember { mutableStateOf(false) }
                    Field(s.cloudApiKey, actions::setApiKey, "API key", secret = !showKey) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.show), tint = c.textTertiary,
                            modifier = Modifier.size(18.dp).clickable { showKey = !showKey })
                    }
                    Field(s.cloudModel, actions::setCloudModel, stringResource(R.string.model))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.cloud_test), style = PillStyle.SECONDARY, onClick = actions::testCloud)
                        PillButton(stringResource(R.string.cloud_get_key), style = PillStyle.GHOST) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                    }
                    when (val t = state.system.cloudTest) {
                        CloudTest.Running -> StatusText(stringResource(R.string.testing), c.warning)
                        CloudTest.Ok -> StatusText(stringResource(R.string.cloud_ok), c.success)
                        is CloudTest.Failed -> StatusText(t.message, c.danger)
                        CloudTest.Idle -> Unit
                    }
                    Text(stringResource(R.string.cloud_free_tier), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
            ListDivider()
            ListRow("Gemini Nano · AICore", when (state.nanoStatus) {
                GeminiNanoEngine.Status.AVAILABLE -> stringResource(R.string.nano_available)
                GeminiNanoEngine.Status.DOWNLOADABLE -> stringResource(R.string.nano_downloadable)
                GeminiNanoEngine.Status.DOWNLOADING -> stringResource(R.string.nano_downloading)
                GeminiNanoEngine.Status.UNAVAILABLE -> stringResource(R.string.nano_unavailable)
                GeminiNanoEngine.Status.UNKNOWN -> stringResource(R.string.nano_checking)
            }, onClick = actions::checkNano)
        }

        // ── Integrations ────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.integrations), Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            if (!state.system.calendarGranted) {
                ListRow("Google Calendar", stringResource(R.string.calendar_sub), onClick = onRequestCalendar, trailing = {
                    Text(stringResource(R.string.connect), style = MaterialTheme.typography.labelLarge, color = c.accentText)
                })
            } else {
                ListRow(stringResource(R.string.calendar_create_events), stringResource(R.string.calendar_create_events_sub), trailing = { Toggle(s.calendarSyncEnabled, actions::setCalendarSync) })
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
                    GoogleTasksSync.Status.Running -> stringResource(R.string.gtasks_syncing)
                    is GoogleTasksSync.Status.Done -> stringResource(R.string.gtasks_synced, gt.pulled, gt.pushed)
                    is GoogleTasksSync.Status.Error -> stringResource(R.string.gtasks_error, gt.message)
                    GoogleTasksSync.Status.NeedsConsent -> stringResource(R.string.gtasks_needs_consent)
                    GoogleTasksSync.Status.Idle -> stringResource(R.string.gtasks_connected)
                }, onClick = if (gt == GoogleTasksSync.Status.NeedsConsent) onConnectGoogleTasks else actions::syncGoogleTasksNow, trailing = {
                    Text(stringResource(R.string.disconnect), style = MaterialTheme.typography.labelLarge, color = c.danger, modifier = Modifier.clickable(onClick = actions::disconnectGoogleTasks))
                })
            } else {
                ListRow("Google Tasks", stringResource(R.string.gtasks_sub), onClick = onConnectGoogleTasks, trailing = {
                    Text(stringResource(R.string.connect), style = MaterialTheme.typography.labelLarge, color = c.accentText)
                })
            }
            state.system.googleTasksError?.let { Box(Modifier.padding(16.dp)) { StatusText(it, c.danger) } }
            var showGuide by remember { mutableStateOf(false) }
            ListDivider()
            ListRow(stringResource(R.string.gtasks_setup), stringResource(R.string.gtasks_setup_sub), onClick = { showGuide = !showGuide })
            if (showGuide) Box(Modifier.padding(16.dp)) { GoogleTasksGuide(context.packageName, state.system.signingSha1) }
        }

        // ── System assistant ────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.open_anywhere), Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            ListRow(
                stringResource(R.string.digital_assistant),
                stringResource(if (state.system.isDefaultAssistant) R.string.digital_assistant_on else R.string.digital_assistant_off),
                onClick = { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            )
            ListDivider()
            Text(stringResource(R.string.open_anywhere_note),
                style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(16.dp))
        }

        assistantSection()

        // ── Memory ──────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.memory), Modifier.padding(start = 4.dp, top = 12.dp))
        Text(stringResource(R.string.memory_sub),
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        MemorySection(memories, actions)

        // ── Quick contacts ──────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.quick_contacts), Modifier.padding(start = 4.dp, top = 12.dp))
        Text(stringResource(R.string.quick_contacts_sub),
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        AliasSection(aliases, onRemoveAlias, onAddAlias)

        // ── Places ──────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.places), Modifier.padding(start = 4.dp, top = 12.dp))
        Text(stringResource(R.string.places_sub),
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        PlacesSection(state.extras.places, actions, onSavePlaceHere, onRequestBackgroundLocation)
        MapsAppPicker(s.mapsApp, actions::setMapsApp)

        // ── Schedule and reminders ────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.schedule_reminders), Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            Stepper(stringResource(R.string.work_start), "${s.workStartHour}:00",
                { actions.setWorkHours((s.workStartHour - 1).coerceAtLeast(0), s.workEndHour) },
                { actions.setWorkHours((s.workStartHour + 1).coerceAtMost(s.workEndHour - 1), s.workEndHour) })
            ListDivider()
            Stepper(stringResource(R.string.work_end), "${s.workEndHour}:00",
                { actions.setWorkHours(s.workStartHour, (s.workEndHour - 1).coerceAtLeast(s.workStartHour + 1)) },
                { actions.setWorkHours(s.workStartHour, (s.workEndHour + 1).coerceAtMost(24)) })
            ListDivider()
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.lead_title), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(stringResource(R.string.lead_sub), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        Segment(if (m < 60) "$m min" else "${m / 60} h", s.reminderLeadMinutes == m, Modifier.weight(1f)) { actions.setReminderLead(m) }
                    }
                }
            }
            ListDivider()
            ListRow(stringResource(R.string.live_title), stringResource(R.string.live_sub),
                trailing = { Toggle(s.liveUpdates, actions::setLiveUpdates) })
            if (s.liveUpdates) ChipStatusRow(state.system.chipStatus) {
                open(
                    if (Build.VERSION.SDK_INT >= 36) Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
            ListDivider()
            ListRow(stringResource(R.string.checkin_title), stringResource(R.string.checkin_sub),
                trailing = { Toggle(s.checkInEnabled, actions::setCheckIn) })
            if (s.checkInEnabled) {
                ListDivider()
                Stepper(stringResource(R.string.checkin_time), "${s.checkInHour}:00",
                    { actions.setCheckInHour((s.checkInHour - 1).coerceAtLeast(17)) },
                    { actions.setCheckInHour((s.checkInHour + 1).coerceAtMost(23)) })
            }
            if (!state.system.notificationsGranted) {
                ListDivider()
                ListRow(stringResource(R.string.notifications_off), stringResource(R.string.notifications_off_sub), onClick = {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, trailing = { Text(stringResource(R.string.enable), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
            }
            if (!state.system.exactAlarmsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListDivider()
                ListRow(stringResource(R.string.exact_alarms), stringResource(R.string.exact_alarms_sub), onClick = {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }, trailing = { Text(stringResource(R.string.allow), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * "Train my voice" (like Google's Voice Match): 12 samples of each phrase you use, in varied conditions. After that Lumi only wakes up for your voice.
 * The voice print stays on the phone.
 */
@Composable
private fun VoiceTraining(voice: VoiceUi, actions: SettingsActions) {
    val c = Lumi.colors
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.my_voice), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(
                    stringResource(if (voice.trained) R.string.my_voice_on else R.string.my_voice_off),
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                )
            }
            if (voice.trained) StatusText(stringResource(R.string.trained), c.success)
        }
        if (voice.lastScore > 0f) {
            StatusText(stringResource(R.string.voice_last_score, (voice.lastScore * 100).toInt(), (voice.sensitivity.detectorThreshold * 100).toInt()), c.textTertiary)
        }
        voice.lastHeard?.let { h ->
            val sim = h.similarity?.let { stringResource(R.string.voice_similarity, (it * 100).toInt()) }.orEmpty()
            StatusText(stringResource(R.string.voice_last_heard, h.text, sim, h.reason), if (h.accepted) c.success else c.warning)
        }
        // The app language's phrase first ("Oye Lumi" in Spanish, "Hey Lumi" otherwise)
        val order = if (io.github.salex27.lumi.domain.assistant.ReplyLanguage.app == io.github.salex27.lumi.domain.assistant.Lang.ES) listOf(WakePhrase.OYE, WakePhrase.HEY)
            else listOf(WakePhrase.HEY, WakePhrase.OYE)
        var lastPhrase by remember { mutableStateOf(order.first()) }
        when (val t = voice.training) {
            VoiceEnroller.State.Loading -> StatusText(stringResource(R.string.voice_preparing), c.warning)
            is VoiceEnroller.State.Listening, is VoiceEnroller.State.Retry -> {
                val (phrase, collected, condition) = when (t) {
                    is VoiceEnroller.State.Listening -> Triple(t.phrase, t.collected, t.condition)
                    else -> (t as VoiceEnroller.State.Retry).let { Triple(it.phrase, it.collected, it.condition) }
                }
                val name = phraseName(phrase)
                Text(stringResource(R.string.voice_say_it, name, collected + 1, VoiceEnroller.SAMPLES),
                    style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(stringResource(conditionText(condition)), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Progress(collected / VoiceEnroller.SAMPLES.toFloat())
                if (t is VoiceEnroller.State.Retry) StatusText(stringResource(R.string.voice_heard_repeat, t.heard, name), c.warning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (condition == VoiceEnroller.Condition.MUSIC && collected >= VoiceEnroller.MIN_SAMPLES) {
                        PillButton(stringResource(R.string.voice_skip_music), style = PillStyle.SECONDARY, onClick = actions::skipVoiceRound)
                    }
                    PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST, onClick = actions::cancelVoiceTraining)
                }
            }
            is VoiceEnroller.State.Failed -> {
                StatusText(t.reason, c.danger)
                PillButton(stringResource(R.string.retry), style = PillStyle.SECONDARY, onClick = { actions.startVoiceTraining(lastPhrase) })
            }
            else -> {
                if (t is VoiceEnroller.State.Done) StatusText(stringResource(R.string.voice_phrase_done, phraseName(t.phrase), t.samples), c.success)
                Text(stringResource(R.string.voice_train_intro), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                order.forEach { phrase ->
                    val p = voice.phrases[phrase]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                p == null -> stringResource(R.string.voice_phrase_untrained, phraseName(phrase))
                                p.legacy -> stringResource(R.string.voice_phrase_legacy, phraseName(phrase))
                                else -> stringResource(R.string.voice_phrase_trained, phraseName(phrase), p.samples)
                            },
                            style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f)
                        )
                        PillButton(stringResource(if (p == null) R.string.voice_train else R.string.voice_retrain),
                            style = if (p == null) PillStyle.PRIMARY else PillStyle.SECONDARY,
                            onClick = { lastPhrase = phrase; actions.startVoiceTraining(phrase) })
                    }
                }
                if (voice.trained) PillButton(stringResource(R.string.voice_delete_all), style = PillStyle.GHOST, onClick = actions::deleteVoiceProfile)
            }
        }
        if (voice.trained) {
            Text(stringResource(R.string.voice_strictness), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakePhrases.Sensitivity.entries.forEach { level ->
                    Segment(stringResource(when (level) {
                        WakePhrases.Sensitivity.STRICT -> R.string.sens_strict
                        WakePhrases.Sensitivity.NORMAL -> R.string.sens_normal
                        else -> R.string.sens_relaxed
                    }), voice.sensitivity == level, Modifier.weight(1f)) { actions.setVoiceSensitivity(level) }
                }
            }
            Text(stringResource(R.string.voice_strictness_tip),
                style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}

private fun phraseName(phrase: WakePhrase) = if (phrase == WakePhrase.HEY) "Hey Lumi" else "Oye Lumi"

private fun conditionText(condition: VoiceEnroller.Condition) = when (condition) {
    VoiceEnroller.Condition.CLOSE -> R.string.voice_cond_close
    VoiceEnroller.Condition.FAR -> R.string.voice_cond_far
    VoiceEnroller.Condition.SOFT -> R.string.voice_cond_soft
    VoiceEnroller.Condition.LOUD -> R.string.voice_cond_loud
    VoiceEnroller.Condition.MUSIC -> R.string.voice_cond_music
}

/** "Add by searching the address": name + search (Geocoder) → choose a result. */
@Composable
private fun AddPlaceBySearch(actions: SettingsActions) {
    val c = Lumi.colors
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<io.github.salex27.lumi.data.places.PlaceSearch.Result>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(450)
        results = actions.searchPlaces(query)
    }
    if (!open) {
        PillButton(stringResource(R.string.place_add_by_address), style = PillStyle.GHOST) { open = true }
        return
    }
    Field(name, { name = it }, stringResource(R.string.place_name_hint))
    Field(query, { query = it }, stringResource(R.string.place_address_hint))
    results.forEach { res ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted)
                .clickable { actions.addPlace(name, res); open = false; name = ""; query = "" }.padding(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(res.name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(res.address, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = c.accentText)
        }
    }
    if (results.isNotEmpty()) Text(stringResource(R.string.place_attribution), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
}

@Composable
private fun AliasSection(
    aliases: List<io.github.salex27.lumi.presentation.agent.ContactAliases.Alias>,
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
                Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onRemove(a.alias) }.padding(8.dp))
            })
        }
        if (aliases.isNotEmpty()) ListDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(alias, { alias = it }, stringResource(R.string.alias_when_i_say))
            Field(contact, { contact = it }, stringResource(R.string.alias_call_contact), trailing = {
                if (alias.isNotBlank() && contact.isNotBlank()) Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = c.accentText,
                    modifier = Modifier.clickable { onAdd(alias, contact); alias = ""; contact = "" }.padding(8.dp))
            })
        }
    }
}

@Composable
private fun MemorySection(memories: List<io.github.salex27.lumi.domain.assistant.MemoryRetriever.Memory>, actions: SettingsActions) {
    val c = Lumi.colors
    var draft by remember { mutableStateOf("") }
    ListGroup {
        memories.forEachIndexed { i, m ->
            if (i > 0) ListDivider()
            ListRow(m.text, null, trailing = {
                Text(stringResource(R.string.forget), style = MaterialTheme.typography.labelLarge, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { actions.deleteMemory(m.id) }.padding(8.dp))
            })
        }
        if (memories.isNotEmpty()) ListDivider()
        Column(Modifier.padding(16.dp)) {
            Field(draft, { draft = it }, stringResource(R.string.memory_add_hint), trailing = {
                if (draft.isNotBlank()) Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = c.accentText,
                    modifier = Modifier.clickable { actions.addMemory(draft); draft = "" }.padding(8.dp))
            })
        }
    }
}

/** Explains whether Android is showing the chip and leads to the permission if needed. */
@Composable
private fun ChipStatusRow(status: LiveUpdateManager.ChipStatus, onOpenSystemSettings: () -> Unit) {
    val c = Lumi.colors
    when (status) {
        LiveUpdateManager.ChipStatus.BLOCKED -> {
            ListDivider()
            ListRow(stringResource(R.string.chip_allow), stringResource(R.string.chip_allow_sub), onClick = onOpenSystemSettings,
                trailing = { Text(stringResource(R.string.allow), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        }
        LiveUpdateManager.ChipStatus.NOT_PROMOTED -> {
            ListDivider()
            ListRow(stringResource(R.string.chip_not_shown), stringResource(R.string.chip_not_shown_sub),
                onClick = onOpenSystemSettings, trailing = { Text(stringResource(R.string.review), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
        }
        LiveUpdateManager.ChipStatus.ACTIVE -> Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { StatusText(stringResource(R.string.chip_active), c.success) }
        LiveUpdateManager.ChipStatus.IDLE -> Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            StatusText(stringResource(R.string.chip_waiting), c.textTertiary)
        }
        LiveUpdateManager.ChipStatus.UNSUPPORTED -> Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            StatusText(stringResource(R.string.chip_needs_16), c.textTertiary)
        }
    }
}

/** Maps app for "Directions" and "take me to…". */
@Composable
private fun MapsAppPicker(current: String, onSelect: (String) -> Unit) {
    val c = Lumi.colors
    val context = LocalContext.current
    val apps = remember { io.github.salex27.lumi.presentation.nav.MapsLauncher.installedApps(context) }
    Spacer(Modifier.height(6.dp))
    ListGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.maps_app), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(stringResource(R.string.maps_app_sub),
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segment(stringResource(R.string.ask), current.isBlank()) { onSelect("") }
                apps.forEach { app -> Segment(app.label, current == app.packageName) { onSelect(app.packageName) } }
            }
            if (apps.isEmpty()) Text(stringResource(R.string.no_maps_apps), style = MaterialTheme.typography.bodySmall, color = c.warning)
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
            ListRow(place.label, place.address.ifBlank { stringResource(R.string.place_saved_here) } + stringResource(R.string.place_radius, place.radiusMeters.toInt()), trailing = {
                Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, color = c.danger,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { actions.removePlace(place.key) }.padding(8.dp))
            })
        }
        // Places your tasks already use but that aren't saved yet
        places.missing.forEach { key ->
            if (places.saved.isNotEmpty() || key != places.missing.first()) ListDivider()
            val label = key.replaceFirstChar { it.uppercase() }
            ListRow(stringResource(R.string.place_unsaved, label), stringResource(R.string.place_unsaved_sub), trailing = {
                PillButton(stringResource(R.string.save_here), style = PillStyle.SECONDARY) { onSavePlaceHere(key, label) }
            })
        }
        if (places.saved.isNotEmpty() || places.missing.isNotEmpty()) ListDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.save_location_as), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("casa" to stringResource(R.string.place_home), "trabajo" to stringResource(R.string.place_work)).filter { (k, _) -> places.saved.none { it.key == k } }.forEach { (key, label) ->
                    PillButton(label, style = PillStyle.SECONDARY) { onSavePlaceHere(key, label) }
                }
                PillButton(stringResource(R.string.other), style = PillStyle.GHOST) { addingCustom = !addingCustom }
            }
            if (addingCustom) {
                Field(customName, { customName = it }, stringResource(R.string.place_custom_hint), trailing = {
                    if (customName.isNotBlank()) Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = c.accentText,
                        modifier = Modifier.clickable {
                            val name = customName.trim()
                            onSavePlaceHere(TaskPhraseParser.normalizePlace(name), name.replaceFirstChar { it.uppercase() })
                            customName = ""; addingCustom = false
                        }.padding(8.dp))
                })
            }
            AddPlaceBySearch(actions)
            places.saving?.let { StatusText(stringResource(R.string.place_locating, it), c.warning) }
            places.error?.let { StatusText(it, c.danger) }
        }
        if (places.saved.isNotEmpty() && !places.backgroundGranted) {
            ListDivider()
            ListRow(stringResource(R.string.bg_location), stringResource(R.string.bg_location_sub), onClick = onRequestBackgroundLocation,
                trailing = { Text(stringResource(R.string.allow), style = MaterialTheme.typography.labelLarge, color = c.accentText) })
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

/** Steps to create the OAuth client (free). Package and SHA-1 with a copy button. */
@Composable
private fun GoogleTasksGuide(packageName: String, sha1: String) {
    val c = Lumi.colors
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    fun copy(label: String, value: String) = clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            stringResource(R.string.gtasks_step1), stringResource(R.string.gtasks_step2), stringResource(R.string.gtasks_step3), stringResource(R.string.gtasks_step4), stringResource(R.string.gtasks_step5)
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        listOf(stringResource(R.string.package_name_label) to packageName, "SHA-1" to sha1.ifBlank { stringResource(R.string.not_available) }).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).clickable { copy(label, value) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                    Text(value, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                }
                Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelLarge, color = c.accentText)
            }
        }
    }
}

/**
 * App language (Android 13+ per-app language): System / English / Español. Android recreates the screens with the new
 * resources; Lumi's replies follow the language you talk to it in anyway.
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AppLanguagePicker() {
    val c = Lumi.colors
    val context = LocalContext.current
    val manager = remember { context.getSystemService(android.app.LocaleManager::class.java) }
    var current by remember { mutableStateOf(manager.applicationLocales.toLanguageTags().substringBefore('-')) }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.app_language), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to stringResource(R.string.theme_system), "en" to "English", "es" to "Español").forEach { (tag, label) ->
                Segment(label, current == tag, Modifier.weight(1f)) {
                    current = tag
                    manager.applicationLocales = android.os.LocaleList.forLanguageTags(tag)
                }
            }
        }
    }
}
