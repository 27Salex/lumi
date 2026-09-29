package io.github.salex27.lumi.presentation.assistant

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
 * Asistente flotante. Es una Activity translúcida (no un overlay de Service) porque:
 *  1. AICore/Gemini Nano solo permite inferencia con la app en primer plano.
 *  2. Así puede registrarse como "Asistente digital" del sistema (intent ASSIST): pulsación larga
 *     del botón lateral o gesto desde la esquina abre este panel sobre cualquier app.
 */
class AssistantActivity : ComponentActivity() {

    private val app get() = application as TaskManagerApplication
    private val viewModel: AssistantViewModel by viewModels {
        AssistantViewModel.Factory(app.repository, app.assistant) { app.speaker.speak(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb())
        )
        super.onCreate(savedInstanceState)
        // Con el móvil bloqueado Lumi aparece encima (como Gemini/Google Assistant); lo que necesite otra app
        // pide desbloquear primero (ver whenUnlocked)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Invocado como asistente del sistema (o con EXTRA_START_LISTENING) → empezar a escuchar, como Gemini
        // Solo en el primer arranque, no al recrear la Activity (rotación): evita reenviar/re-escuchar
        val firstLaunch = savedInstanceState == null
        val autoListen = firstLaunch && (intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.getBooleanExtra(EXTRA_START_LISTENING, false))
        val initialPrompt = if (!firstLaunch) null else intent.getStringExtra(EXTRA_PROMPT) ?: sharedPrompt(intent)
        // Fuera de la app (botón lateral, «Oye Lumi», widget, tile) → píldora compacta; desde la app → conversación
        val compact = intent.getBooleanExtra(EXTRA_COMPACT, intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND)
        // Abierta sola por «Oye Lumi»: más cautela (confirmación y cierre automático si no hay orden clara)
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

                fun listen(quiet: Boolean = false) {
                    app.speaker.stop() // si Lumi estaba hablando, se calla para escucharte
                    viewModel.setVoiceError(null)
                    voice.startListening(
                        onFinalResult = { viewModel.sendVoice(it, fromWakeWord && !quiet) },
                        onError = { viewModel.setVoiceError(it) }, // visible en la píldora / barra
                        quiet = quiet,
                        // Seguía escuchando y no dijiste nada más: la píldora (fuera de la app) se retira sola
                        onSilence = { if (compact) lifecycleScope.launch { kotlinx.coroutines.delay(1_200); if (!voice.isListening.value) finish() } }
                    )
                }

                // Conversación seguida: al acabar de responder por voz (y de hablar), Lumi vuelve a escuchar
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
                    if (granted) listen() else viewModel.setVoiceError("Sin permiso de micrófono: escribe o concédelo en Ajustes")
                }

                LaunchedEffect(Unit) {
                    // Botón de un aviso («Escribir a Roberto»): se ejecuta la acción directamente
                    if (firstLaunch) intent.getStringExtra(EXTRA_DEVICE)?.let { io.github.salex27.lumi.domain.assistant.DeviceCommand.parse(it) }?.let {
                        viewModel.runTaskAction(it, intent.getLongExtra(EXTRA_DONE_TASK, 0L))
                        return@LaunchedEffect
                    }
                    initialPrompt?.let { if (intent.getBooleanExtra(EXTRA_SPEAK, false)) viewModel.sendSpoken(it) else viewModel.send(it) }
                    if (autoListen && initialPrompt == null) {
                        if (hasMic) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                DisposableEffect(Unit) { onDispose { voice.stopListening() } }

                // Acción del móvil pedida por voz o texto («llama a mamá», «pon una alarma a las 7»…)
                var pendingDevice by remember { mutableStateOf<io.github.salex27.lumi.domain.assistant.DeviceCommand?>(null) }
                val devicePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    val cmd = pendingDevice ?: return@rememberLauncherForActivityResult
                    pendingDevice = null
                    when {
                        // Permiso de llamadas (concedido o no): se reintenta; si se denegó, abrirá el marcador
                        Manifest.permission.CALL_PHONE in result && Manifest.permission.READ_CONTACTS !in result -> viewModel.retryDevice(cmd)
                        result[Manifest.permission.READ_CONTACTS] == true -> viewModel.retryDevice(cmd)
                        else -> viewModel.say("Sin acceso a tus contactos no puedo buscar a quién llamar. Puedes darme el número.", isError = true)
                    }
                }
                LaunchedEffect(state.device) {
                    val cmd = state.device ?: return@LaunchedEffect
                    val contact = state.deviceContact
                    viewModel.deviceHandled()
                    // Bloqueado y la acción abre otra app (llamada, WhatsApp, Spotify…) → primero desbloquear
                    if (!cmd.staysInLumi && isLocked()) {
                        whenUnlocked(
                            onUnlocked = { viewModel.retryDevice(cmd, contact) },
                            onCancelled = { viewModel.say("Desbloquea el móvil y vuelve a pedírmelo.", isError = true); viewModel.nextDevice() }
                        )
                        return@LaunchedEffect
                    }
                    // Elegido entre varios → se aprende como alias («mamá» → AA Mamá) para la próxima vez
                    if (contact != null) {
                        val spoken = (cmd as? io.github.salex27.lumi.domain.assistant.DeviceCommand.Call)?.contact
                            ?: (cmd as? io.github.salex27.lumi.domain.assistant.DeviceCommand.Message)?.contact
                        spoken?.let { app.contactAliases.save(it, contact) }
                    }
                    when (val outcome = io.github.salex27.lumi.presentation.agent.DeviceActions.execute(this@AssistantActivity, cmd, contact, app.contactAliases)) {
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.Done -> {
                            // Rutina: siguiente acción. Si no queda nada y la acción abrió otra app (llamada, WhatsApp…),
                            // la píldora se retira. En lifecycleScope: limpiar el estado reinicia este efecto y cancelaría la espera.
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(if (cmd.staysInLumi) 250 else 700)
                                if (!viewModel.nextDevice() && !cmd.staysInLumi) finish()
                            }
                        }
                        is io.github.salex27.lumi.presentation.agent.DeviceActions.Outcome.NeedsAccess -> {
                            if (viewModel.routineActive) {
                                viewModel.say("No he podido activar No molestar: dale acceso en Ajustes → Accesos.", isError = true)
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
                            viewModel.nextDevice() // en una rutina, un paso fallido no para los demás
                        }
                    }
                }

                // «Edita lo del dentista» → la app con el editor de esa tarea abierto
                LaunchedEffect(state.openTaskId) {
                    val id = state.openTaskId ?: return@LaunchedEffect
                    viewModel.openTaskHandled()
                    // lifecycleScope y no el efecto: al limpiar el estado el efecto se reinicia y cancelaría la espera
                    whenUnlocked(onCancelled = { viewModel.say("Desbloquea el móvil para editar la tarea.", isError = true) }) { lifecycleScope.launch {
                        kotlinx.coroutines.delay(600)
                        startActivity(
                            Intent(this@AssistantActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra(MainActivity.EXTRA_EDIT_TASK_ID, id)
                        )
                        finish()
                    } }
                }

                // «Llévame a…» → app de mapas elegida en Ajustes (o el selector del sistema)
                LaunchedEffect(state.navigateTo) {
                    val destination = state.navigateTo ?: return@LaunchedEffect
                    viewModel.navigationHandled()
                    whenUnlocked(onCancelled = { viewModel.say("Desbloquea el móvil para abrir la ruta.", isError = true) }) { lifecycleScope.launch {
                        kotlinx.coroutines.delay(900) // que se lea la respuesta antes de salir
                        if (io.github.salex27.lumi.presentation.nav.MapsLauncher.open(this@AssistantActivity, destination, app.settings.current.mapsApp)) finish()
                        else viewModel.setVoiceError("No hay ninguna app de mapas instalada")
                    } }
                }

                // Activación por voz sin orden (no oyó nada o nadie confirma) → se cierra sola
                if (fromWakeWord) {
                    LaunchedEffect(state.voiceError, state.messages.size) {
                        if (state.voiceError != null && state.messages.size <= 1) { kotlinx.coroutines.delay(2_500); finish() }
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
                    micAvailable = voice.isAvailable(),
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
                    onStopVoice = { voice.finishNow() }, // lo dicho hasta ahora se envía
                    onRevealed = viewModel::markRevealed,
                    onConfirmPending = viewModel::confirmPending,
                    onDiscardPending = { viewModel.discardPending(); if (fromWakeWord) finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                    onDismiss = { finish() },
                    onPickOption = viewModel::pick,
                    onPickNone = viewModel::pickNone
                )
            }
        }
    }

    private fun isLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Ejecuta [onUnlocked] ya mismo si el móvil está desbloqueado; si no, pide huella/PIN y sigue al desbloquear. */
    private fun whenUnlocked(onCancelled: () -> Unit = {}, onUnlocked: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { onUnlocked(); return }
        km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = onUnlocked()
            override fun onDismissCancelled() = onCancelled()
            override fun onDismissError() = onCancelled()
        })
    }

    // «Oye Lumi» suelta el micrófono mientras el asistente está abierto
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
     * «Compartir → Lumi» desde cualquier app. Un enlace se guarda como «Revisar: título» con la URL como
     * nota (descripción explícita); un texto normal se interpreta como un comando más.
     */
    private fun sharedPrompt(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        if (text.isBlank()) return subject
        val url = Regex("https?://\\S+").find(text)?.value
        return if (url != null) {
            val title = subject ?: text.replace(url, "").trim().ifBlank { "enlace" }
            "Revisar: ${title.take(80)}, nota: $url"
        } else text.take(500)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_START_LISTENING = "start_listening"
        const val EXTRA_PROMPT = "prompt"

        const val EXTRA_COMPACT = "compact"
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"
        /** Leer en voz alta la respuesta al [EXTRA_PROMPT] (p.ej. «Escuchar» en el resumen de la mañana). */
        const val EXTRA_SPEAK = "speak"
        /** Acción del móvil a ejecutar nada más abrir (botón de un aviso) y tarea a dar por hecha. */
        const val EXTRA_DEVICE = "device"
        const val EXTRA_DONE_TASK = "done_task"

        /** «Hablar con Lumi» desde una notificación: píldora compacta escuchando. */
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
