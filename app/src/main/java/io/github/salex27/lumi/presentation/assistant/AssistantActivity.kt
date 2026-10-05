package io.github.salex27.lumi.presentation.assistant

import io.github.salex27.lumi.domain.assistant.ReplyLanguage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.presentation.ai.VoiceSpeechManager
import io.github.salex27.lumi.presentation.main.MainActivity
import io.github.salex27.lumi.service.wakeword.WakeWordService
import io.github.salex27.lumi.presentation.theme.LumiAppTheme

/**
 * Floating assistant. It is a translucent Activity (not a Service overlay) because:
 *  1. AICore/Gemini Nano only allows inference with the app in the foreground.
 *  2. That way it can register as the system "Digital assistant" (ASSIST intent): a long press of the side button or
 *     the corner gesture opens this panel over any app.
 */
class AssistantActivity : ComponentActivity() {

    private val app get() = application as TaskManagerApplication
    private val viewModel: AssistantViewModel by viewModels {
        AssistantViewModel.Factory(app.repository, app.assistant, { app.speaker.speak(it) }, app.chatStore, app.chatMemory)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb())
        )
        super.onCreate(savedInstanceState)
        (application as io.github.salex27.lumi.TaskManagerApplication).updateAppLanguage(resources.configuration)
        // With the phone locked Lumi appears on top (like Gemini/Google Assistant); anything that needs another app
        // asks to unlock first (see whenUnlocked)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Android only lets an OPAQUE activity cover ("occlude") the lock screen. A translucent one is drawn on top but the
        // keyguard keeps focus, so Lumi wasn't really in front and the recognizer got silence. Over the lock screen the
        // assistant becomes opaque, on its own dark background.
        if (keyguardLocked() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF07080D.toInt()))
            setTranslucent(false)
        }

        // Invoked as the system assistant (or with EXTRA_START_LISTENING) → start listening, like Gemini.
        // Only on the first start, not when the Activity is recreated (rotation): avoids resending / listening again
        val firstLaunch = savedInstanceState == null
        val autoListen = firstLaunch && (intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.getBooleanExtra(EXTRA_START_LISTENING, false))
        val initialPrompt = if (!firstLaunch) null else intent.getStringExtra(EXTRA_PROMPT) ?: sharedPrompt(intent)
        // Outside the app (side button, "Oye Lumi", widget, tile) → compact pill; from the app → conversation
        val compact = intent.getBooleanExtra(EXTRA_COMPACT, intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND)
        // Opened on its own by "Oye Lumi": more caution (confirmation and auto-close if there is no clear command)
        val fromWakeWord = intent.getBooleanExtra(EXTRA_FROM_WAKE_WORD, false)

        setContent {
            val theme by app.settings.settings.collectAsStateWithLifecycle()
            LumiAppTheme(themeMode = theme.themeMode) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val engine by viewModel.activeEngine.collectAsStateWithLifecycle()

                val voice = remember {
                    VoiceSpeechManager(this, { app.settings.current.voiceLanguage }, { app.settings.current.voicePauseMs.toLong() })
                }
                val isListening by voice.isListening.collectAsStateWithLifecycle()
                val rms by voice.rmsAmplitude.collectAsStateWithLifecycle()
                val transcript by voice.liveTranscript.collectAsStateWithLifecycle()
                val speaking by app.speaker.speaking.collectAsStateWithLifecycle()
                var hasMic by remember { mutableStateOf(hasMicPermission()) }
                // Queried once, not on every frame (the logo animation recomposes ~30 times per second)
                val micAvailable = remember { voice.isAvailable() }

                fun listen(quiet: Boolean = false) {
                    app.speaker.stop() // if Lumi was speaking, it goes quiet to listen to you
                    viewModel.setVoiceError(null)
                    voice.startListening(
                        onFinalResult = { viewModel.sendVoice(it, fromWakeWord && !quiet) },
                        onError = { viewModel.setVoiceError(it) }, // shown in the pill / bar
                        quiet = quiet,
                        // It kept listening and you said nothing else: the pill (outside the app) goes away on its own
                        onSilence = { if (compact) lifecycleScope.launch { kotlinx.coroutines.delay(1_200); if (!voice.isListening.value) finish() } },
                        // A light tick when the microphone is really open: words said before that are lost
                        onReady = { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
                    )
                }

                // Continuous conversation: once the voice reply finishes (and Lumi stops speaking), Lumi listens again
                val listenAgain by viewModel.listenAgain.collectAsStateWithLifecycle()
                LaunchedEffect(listenAgain) {
                    if (listenAgain == 0 || !app.settings.current.continueConversation || !hasMic) return@LaunchedEffect
                    kotlinx.coroutines.delay(400)
                    androidx.compose.runtime.snapshotFlow { app.speaker.speaking.value }.first { !it }
                    kotlinx.coroutines.delay(250)
                    val st = viewModel.state.value
                    if (!voice.isListening.value && st.device == null && st.navigateTo == null && st.openTaskId == null) listen(quiet = true)
                }

                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    hasMic = granted
                    if (granted) listen() else viewModel.setVoiceError(ReplyLanguage.ui("Sin permiso de micrófono: escribe o concédelo en Ajustes", "No microphone permission: type, or grant it in Settings"))
                }

                LaunchedEffect(Unit) {
                    // A reminder's button ("Text Roberto"): the action runs directly
                    if (firstLaunch) intent.getStringExtra(EXTRA_DEVICE)?.let { io.github.salex27.lumi.domain.assistant.DeviceCommand.parse(it) }?.let {
                        viewModel.runTaskAction(it, intent.getLongExtra(EXTRA_DONE_TASK, 0L))
                        return@LaunchedEffect
                    }
                    initialPrompt?.let { if (intent.getBooleanExtra(EXTRA_SPEAK, false)) viewModel.sendSpoken(it) else viewModel.send(it) }
                    if (autoListen && initialPrompt == null) {
                        // Only once the window is really in front: over the lock screen (and when the screen was off)
                        // the Activity is created during the keyguard/turn-on transition, and a recognizer started
                        // then gets SILENT audio from Android (no error: "Listening…" that never hears anything).
                        // The short wait also lets the "Oye Lumi" detector release the microphone.
                        kotlinx.coroutines.withTimeoutOrNull(1_500) { windowFocused.first { it } }
                        kotlinx.coroutines.delay(if (keyguardLocked()) 600 else 250)
                        if (hasMic) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                DisposableEffect(Unit) { onDispose { voice.stopListening() } }

                // Phone action asked for by voice or text ("call mum", "set an alarm at 7"…)
                var pendingDevice by remember { mutableStateOf<io.github.salex27.lumi.domain.assistant.DeviceCommand?>(null) }
                val devicePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    val cmd = pendingDevice ?: return@rememberLauncherForActivityResult
                    pendingDevice = null
                    when {
                        // Call permission (granted or not): retried; if it was denied, the dialer opens
                        Manifest.permission.CALL_PHONE in result && Manifest.permission.READ_CONTACTS !in result -> viewModel.retryDevice(cmd)
                        result[Manifest.permission.READ_CONTACTS] == true -> viewModel.retryDevice(cmd)
                        else -> viewModel.say(ReplyLanguage.t("Sin acceso a tus contactos no puedo buscar a quién llamar. Puedes darme el número.", "Without access to your contacts I can't look up who to call. You can give me the number."), isError = true)
                    }
                }
                LaunchedEffect(state.device) {
                    val cmd = state.device ?: return@LaunchedEffect
                    val contact = state.deviceContact
                    viewModel.deviceHandled()
                    // Locked and the action opens another app (call, WhatsApp, Spotify…) → unlock first
                    if (!cmd.staysInLumi && isLocked()) {
                        whenUnlocked(
                            onUnlocked = { viewModel.retryDevice(cmd, contact) },
                            onCancelled = { viewModel.say(ReplyLanguage.t("Desbloquea el móvil y vuelve a pedírmelo.", "Unlock the phone and ask me again."), isError = true); viewModel.nextDevice() }
                        )
                        return@LaunchedEffect
                    }
                    // Chosen among several → learned as an alias ("mamá" → AA Mamá) for next time
                    if (contact != null) {
                        val spoken = (cmd as? io.github.salex27.lumi.domain.assistant.DeviceCommand.Call)?.contact
                            ?: (cmd as? io.github.salex27.lumi.domain.assistant.DeviceCommand.Message)?.contact
                        spoken?.let { app.contactAliases.save(it, contact) }
                    }
                    when (val outcome = io.github.salex27.lumi.presentation.agent.DeviceActions.execute(this@AssistantActivity, cmd, contact, app.contactAliases)) {
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.Done -> {
                            // Routine: next action. If nothing is left and the action opened another app (call, WhatsApp…),
                            // the pill goes away. In lifecycleScope: clearing the state restarts this effect and would cancel the wait.
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(if (cmd.staysInLumi) 250 else 700)
                                if (!viewModel.nextDevice() && !cmd.staysInLumi) finish()
                            }
                        }
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.NeedsAccess -> {
                            if (viewModel.routineActive) {
                                viewModel.say(ReplyLanguage.t("No he podido activar No molestar: dale acceso en Ajustes → Accesos.", "I couldn't turn on Do Not Disturb: give me access in Settings → Access."), isError = true)
                                viewModel.nextDevice()
                            } else {
                                viewModel.say(outcome.message, isError = true)
                                runCatching { startActivity(outcome.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.NeedsPermission -> {
                            pendingDevice = cmd
                            devicePermissions.launch(outcome.permissions)
                        }
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.ChooseContact -> viewModel.askContact(outcome.command, outcome.options)
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.Failed -> {
                            viewModel.say(outcome.message, isError = true)
                            viewModel.nextDevice() // in a routine, one failed step doesn't stop the rest
                        }
                    }
                }

                // "Edit the dentist one" → the app with that task's editor open
                LaunchedEffect(state.openTaskId) {
                    val id = state.openTaskId ?: return@LaunchedEffect
                    viewModel.openTaskHandled()
                    // lifecycleScope and not the effect: clearing the state restarts the effect and would cancel the wait
                    whenUnlocked(onCancelled = { viewModel.say(ReplyLanguage.t("Desbloquea el móvil para editar la tarea.", "Unlock the phone to edit the task."), isError = true) }) { lifecycleScope.launch {
                        kotlinx.coroutines.delay(600)
                        startActivity(
                            Intent(this@AssistantActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra(MainActivity.EXTRA_EDIT_TASK_ID, id)
                        )
                        finish()
                    } }
                }

                // "Take me to…" → the maps app chosen in Settings (or the system chooser)
                LaunchedEffect(state.navigateTo) {
                    val destination = state.navigateTo ?: return@LaunchedEffect
                    viewModel.navigationHandled()
                    whenUnlocked(onCancelled = { viewModel.say(ReplyLanguage.t("Desbloquea el móvil para abrir la ruta.", "Unlock the phone to open the route."), isError = true) }) { lifecycleScope.launch {
                        kotlinx.coroutines.delay(900) // let the reply be read before leaving
                        if (io.github.salex27.lumi.presentation.nav.MapsLauncher.open(this@AssistantActivity, destination, app.settings.current.mapsApp)) finish()
                        else viewModel.setVoiceError(ReplyLanguage.ui("No hay ninguna app de mapas instalada", "There's no maps app installed"))
                    } }
                }

                // Voice activation without a command (heard nothing or nobody confirms) → closes on its own
                if (fromWakeWord) {
                    LaunchedEffect(state.voiceError, state.messages.size) {
                        // Only messages of this opening count (a resumed chat brings its history)
                        if (state.voiceError != null && state.messages.none { it.id >= LIVE_ID_BASE }) { kotlinx.coroutines.delay(2_500); finish() }
                    }
                    LaunchedEffect(state.pendingConfirmation) {
                        if (state.pendingConfirmation != null) {
                            kotlinx.coroutines.delay(8_000)
                            if (viewModel.state.value.pendingConfirmation != null) finish()
                        }
                    }
                }

                AssistantScreen(
                    state = state,
                    activeEngine = engine,
                    startCompact = compact,
                    isListening = isListening,
                    isSpeaking = speaking,
                    audioLevel = rms,
                    liveTranscript = transcript,
                    micAvailable = micAvailable,
                    onSend = { voice.stopListening(); app.speaker.stop(); viewModel.send(it) },
                    onPlanDay = viewModel::planMyDay,
                    onBriefing = viewModel::briefing,
                    onStartVoice = {
                        when {
                            isListening -> Unit
                            hasMic -> listen()
                            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopVoice = { voice.finishNow() }, // what was said so far is sent
                    onRevealed = viewModel::markRevealed,
                    onConfirmPending = viewModel::confirmPending,
                    onDiscardPending = { viewModel.discardPending(); if (fromWakeWord) finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                    onDismiss = { finish() },
                    onPickOption = viewModel::pick,
                    onPickNone = viewModel::pickNone,
                    sessionActions = SessionActions(
                        onNew = viewModel::newSession, onOpen = viewModel::openSession,
                        onRename = viewModel::renameSession, onDelete = viewModel::deleteSession
                    )
                )
            }
        }
    }

    private fun isLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Runs [onUnlocked] right away if the phone is unlocked; otherwise asks for fingerprint/PIN and continues once unlocked. */
    private fun whenUnlocked(onCancelled: () -> Unit = {}, onUnlocked: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { onUnlocked(); return }
        km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = onUnlocked()
            override fun onDismissCancelled() = onCancelled()
            override fun onDismissError() = onCancelled()
        })
    }

    /** True once the window has focus (the Activity is really in front, also over the lock screen). */
    private val windowFocused = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) windowFocused.value = true
    }

    private fun keyguardLocked() = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    // "Oye Lumi" releases the microphone while the assistant is open
    override fun onStart() {
        super.onStart()
        WakeWordService.pause(this)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) app.speaker.stop()
        WakeWordService.resume(this)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    /**
     * "Share → Lumi" from any app. A link is saved as "Read later: title" with the URL as a note (explicit
     * description); plain text is interpreted as one more command.
     */
    private fun sharedPrompt(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        if (text.isBlank()) return subject
        val url = Regex("https?://\\S+").find(text)?.value
        return if (url != null) {
            val title = (subject ?: text.replace(url, "").trim().ifBlank { ReplyLanguage.ui("enlace", "link") }).take(80)
            ReplyLanguage.ui("Revisar: $title, nota: $url", "add a task Read later: $title, note: $url")
        } else text.take(500)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_START_LISTENING = "start_listening"
        const val EXTRA_PROMPT = "prompt"

        const val EXTRA_COMPACT = "compact"
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"
        /** Read the reply to [EXTRA_PROMPT] aloud (e.g. "Listen" in the morning summary). */
        const val EXTRA_SPEAK = "speak"
        /** Phone action to run right after opening (a reminder's button) and the task to mark done. */
        const val EXTRA_DEVICE = "device"
        const val EXTRA_DONE_TASK = "done_task"

        /** "Talk to Lumi" from a notification: compact pill, listening. */
        fun talkPendingIntent(context: Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(
            context, 31, intent(context, startListening = true, compact = true),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        fun intent(context: Context, startListening: Boolean = false, prompt: String? = null, compact: Boolean = false, fromWakeWord: Boolean = false) =
            Intent(context, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_START_LISTENING, startListening)
                .putExtra(EXTRA_COMPACT, compact)
                .putExtra(EXTRA_FROM_WAKE_WORD, fromWakeWord)
                .apply { prompt?.let { putExtra(EXTRA_PROMPT, it) } }
    }
}
