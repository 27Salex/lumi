package io.github.salex27.lumi.presentation.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.sync.GoogleTasksAuth
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.presentation.agenda.AgendaScreen
import io.github.salex27.lumi.presentation.agenda.AgendaViewModel
import io.github.salex27.lumi.presentation.assistant.AssistantActivity
import io.github.salex27.lumi.presentation.settings.SettingsScreen
import io.github.salex27.lumi.presentation.settings.SettingsViewModel
import io.github.salex27.lumi.presentation.stats.StatsScreen
import io.github.salex27.lumi.presentation.stats.StatsViewModel
import io.github.salex27.lumi.presentation.tasks.TaskEditSheet
import io.github.salex27.lumi.presentation.tasks.TasksScreen
import io.github.salex27.lumi.presentation.theme.LumiAppTheme
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.service.wakeword.WakeWordService
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Inicio", Icons.Outlined.Home, Icons.Filled.Home),
    TASKS("Tareas", Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    AGENDA("Agenda", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    STATS("Progreso", Icons.Outlined.Insights, Icons.Filled.Insights)
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Abrir el editor de esta tarea al entrar («edita lo del dentista» desde el asistente). */
        const val EXTRA_EDIT_TASK_ID = "edit_task_id"
    }

    /** Tarea que el asistente pidió editar (se consume al abrir el editor). */
    private val pendingEditId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra(EXTRA_EDIT_TASK_ID, -1L).takeIf { it > 0 }?.let { pendingEditId.value = it }
    }

    private val app get() = application as TaskManagerApplication

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(app.repository, app.assistant, app.gemmaModel, app.briefStore)
    }
    private val agendaViewModel: AgendaViewModel by viewModels {
        AgendaViewModel.Factory(app.repository, app.deviceCalendar) { app.database.taskDao().linkedCalendarEventIds().toSet() }
    }
    private val statsViewModel: StatsViewModel by viewModels { StatsViewModel.Factory(app.repository) }
    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory(app) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        settingsViewModel.refreshPermissions()
    }

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        agendaViewModel.refresh()
        settingsViewModel.refreshPermissions()
    }

    /** «Oye Lumi»: micrófono → (opcional) mostrar sobre otras apps → descargar modelo → arrancar servicio. */
    private val wakeMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) continueWakeWordSetup() else settingsViewModel.onWakeWordError("Lumi necesita el micrófono para oír «Oye Lumi»")
    }

    /** Lugar que se guardará en cuanto se conceda la ubicación. */
    private var pendingPlace: Pair<String, String>? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val place = pendingPlace
        pendingPlace = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            place?.let { settingsViewModel.savePlaceHere(it.first, it.second) }
        } else {
            settingsViewModel.onPlaceError("Sin permiso de ubicación precisa no puedo guardar lugares")
        }
    }

    // Android 11+: «Todo el tiempo» solo se puede elegir en la pantalla de permisos del sistema
    private val backgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        settingsViewModel.refreshPermissions()
        lifecycleScope.launch { app.placeReminders.resyncAll() }
    }

    private fun savePlaceHere(key: String, label: String) {
        if (app.placeReminders.hasLocation()) {
            settingsViewModel.savePlaceHere(key, label)
        } else {
            pendingPlace = key to label
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    /** Alias manual: busca el contacto en la agenda (pide permiso si hace falta) y lo guarda. */
    private var pendingAlias: Pair<String, String>? = null
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingAlias ?: return@registerForActivityResult
        pendingAlias = null
        if (granted) addAlias(p.first, p.second)
    }

    private fun addAlias(alias: String, contactName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingAlias = alias to contactName
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        val found = io.github.salex27.lumi.presentation.agent.DeviceActions.findContacts(this, contactName)
        val contact = found.firstOrNull()
        if (contact == null) {
            android.widget.Toast.makeText(this, "No encuentro «$contactName» en tus contactos", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            app.contactAliases.save(alias, contact)
            android.widget.Toast.makeText(this, "«$alias» → ${contact.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        when (val r = app.googleTasksAuth.resultFromIntent(result.data)) {
            is GoogleTasksAuth.Result.Token -> settingsViewModel.onGoogleTasksAuthorized()
            is GoogleTasksAuth.Result.Failed -> settingsViewModel.onGoogleTasksError(r.message)
            is GoogleTasksAuth.Result.NeedsConsent -> settingsViewModel.onGoogleTasksError("No se completó el permiso")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.getLongExtra(EXTRA_EDIT_TASK_ID, -1L)?.takeIf { it > 0 }?.let { pendingEditId.value = it }

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            LumiAppTheme(themeMode = settings.themeMode) {
                val dark = Lumi.colors.isDark
                // Barras del sistema transparentes con iconos oscuros en modo claro y claros en modo oscuro
                DisposableEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
                    else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }

                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var editing by remember { mutableStateOf<Task?>(null) }
                // El asistente pidió editar una tarea → se abre su editor
                val editRequest by pendingEditId.collectAsStateWithLifecycle()
                LaunchedEffect(editRequest) {
                    val id = editRequest ?: return@LaunchedEffect
                    pendingEditId.value = null
                    app.repository.getTask(id)?.let { showSettings = false; tab = Tab.TASKS; editing = it }
                }
                BackHandler(enabled = showSettings || tab != Tab.HOME) {
                    if (showSettings) showSettings = false else tab = Tab.HOME
                }

                Box(Modifier.fillMaxSize().background(Lumi.colors.background)) {
                    if (showSettings) {
                        val state by settingsViewModel.state.collectAsStateWithLifecycle()
                        SettingsScreen(
                            state = state,
                            actions = settingsViewModel,
                            onBack = { showSettings = false },
                            onRequestCalendar = ::requestCalendar,
                            onConnectGoogleTasks = ::connectGoogleTasks,
                            onEnableWakeWord = ::startWakeWordSetup,
                            onRetryWakeWord = ::continueWakeWordSetup,
                            onRequestOverlay = ::requestOverlay,
                            onSavePlaceHere = ::savePlaceHere,
                            onRequestBackgroundLocation = ::requestBackgroundLocation,
                            memories = settingsViewModel.memories.collectAsStateWithLifecycle().value,
                            aliases = app.contactAliases.aliases.collectAsStateWithLifecycle().value,
                            onRemoveAlias = app.contactAliases::remove,
                            onAddAlias = ::addAlias,
                            assistantSection = { io.github.salex27.lumi.presentation.settings.AssistantSettings(app) }
                        )
                    } else {
                        Scaffold(containerColor = Lumi.colors.background, bottomBar = { BottomBar(tab) { tab = it } }) { padding ->
                            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                            val agenda by agendaViewModel.state.collectAsStateWithLifecycle()
                            AnimatedContent(
                                targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() },
                                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()), label = "tab"
                            ) { current ->
                                when (current) {
                                    Tab.HOME -> HomeScreen(
                                        uiState = uiState,
                                        todayEvents = agenda.todayEvents,
                                        onRefreshBriefing = viewModel::generateDailyBriefing,
                                        onDownloadGemma = viewModel::startGemmaDownload,
                                        onOpenAssistant = { listen, prompt -> startActivity(AssistantActivity.intent(this@MainActivity, listen, prompt)) },
                                        onOpenSettings = { showSettings = true },
                                        onOpenAgenda = { tab = Tab.AGENDA },
                                        onTaskClick = { editing = it },
                                        onStartTask = { viewModel.updateTaskStatus(it, io.github.salex27.lumi.domain.model.TaskStatus.IN_PROGRESS) },
                                        onCompleteTask = { viewModel.updateTaskStatus(it, io.github.salex27.lumi.domain.model.TaskStatus.COMPLETED) },
                                        weather = app.weather.latest.collectAsStateWithLifecycle().value
                                    )
                                    Tab.TASKS -> TasksScreen(
                                        tasks = uiState.tasks,
                                        filters = uiState.filters,
                                        onCategoryFilter = viewModel::setCategoryFilter,
                                        onStatusFilter = viewModel::setStatusFilter,
                                        onPriorityFilter = viewModel::setPriorityFilter,
                                        onSortByPriority = viewModel::setSortByPriority,
                                        onToggleDone = viewModel::toggleDone,
                                        onTomorrow = viewModel::moveToTomorrow,
                                        onEdit = { editing = it },
                                        onNew = { editing = Task(title = "") }
                                    )
                                    Tab.AGENDA -> AgendaScreen(
                                        state = agenda,
                                        onShiftDays = agendaViewModel::shiftDays,
                                        onSelectDate = agendaViewModel::selectDate,
                                        onRequestCalendar = ::requestCalendar,
                                        onTaskClick = { editing = it },
                                        onToggleDone = viewModel::toggleDone
                                    )
                                    Tab.STATS -> {
                                        val summary by statsViewModel.summary.collectAsStateWithLifecycle()
                                        StatsScreen(summary, statsViewModel::setRange)
                                    }
                                }
                            }
                        }
                    }

                    editing?.let { task ->
                        val places by app.places.places.collectAsStateWithLifecycle()
                        TaskEditSheet(
                            task = task,
                            places = places,
                            searchPlaces = { app.placeSearch.search(it) },
                            onSavePlace = { place ->
                                app.places.save(place)
                                lifecycleScope.launch { app.placeReminders.resyncAll() }
                            },
                            onDirections = { d ->
                                io.github.salex27.lumi.presentation.nav.MapsLauncher.open(this@MainActivity, d, app.settings.current.mapsApp)
                            },
                            loadReminders = viewModel::remindersFor,
                            loadMeetings = viewModel::upcomingMeetings,
                            onSave = viewModel::saveTask,
                            onDelete = viewModel::deleteTask,
                            onDismiss = { editing = null }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settingsViewModel.refreshPermissions()
        agendaViewModel.refresh()
        // Reuniones nuevas del calendario / permiso de ubicación recién concedido
        app.appScope.launch { app.liveUpdates.refresh(); app.placeReminders.resyncAll() }
        app.appScope.launch { app.weather.forecast() } // usa la guardada si tiene < 30 min
        // El micrófono en segundo plano solo puede arrancarse con la app visible (Android 14+)
        val s = app.settings.current
        // v3.5: el detector «Oye Lumi» va dentro de la app → se escucha ya; Vosk (huella de voz) se descarga aparte
        if (s.wakeWordEnabled && hasMic()) startWakeWordIfPossible()
    }

    private fun requestCalendar() {
        calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }

    private fun connectGoogleTasks() {
        lifecycleScope.launch {
            when (val r = app.googleTasksAuth.authorize()) {
                is GoogleTasksAuth.Result.Token -> settingsViewModel.onGoogleTasksAuthorized()
                is GoogleTasksAuth.Result.NeedsConsent -> googleConsent.launch(IntentSenderRequest.Builder(r.pendingIntent.intentSender).build())
                is GoogleTasksAuth.Result.Failed -> settingsViewModel.onGoogleTasksError(r.message)
            }
        }
    }

    // ── «Oye Lumi» ──────────────────────────────────────────────────────────

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWakeWordSetup() {
        if (hasMic()) continueWakeWordSetup() else wakeMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Se marca como activado ANTES de descargar: la descarga corre en el scope de la app (sobrevive a esta
     * pantalla) y, si la Activity se recrea, onResume arranca la escucha en cuanto el modelo esté listo.
     * El permiso «Mostrar sobre otras apps» ya no se abre a la vez (cerraba la pantalla y cancelaba la descarga):
     * es un botón aparte en Ajustes.
     */
    private fun continueWakeWordSetup() {
        app.settings.update { it.copy(wakeWordEnabled = true) }
        settingsViewModel.onWakeWordError(null)
        // El detector no necesita descargas; el modelo de Vosk solo hace falta para «Entrenar mi voz»
        startWakeWordIfPossible()
        app.appScope.launch { app.wakeWordModel.download() }
    }

    private fun startWakeWordIfPossible() {
        if (!WakeWordService.running.value) {
            runCatching { WakeWordService.start(this) }
                .onFailure { settingsViewModel.onWakeWordError("No se pudo iniciar la escucha: ${it.message}") }
        }
    }

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = Lumi.colors
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.outline))
        // Barra propia (no NavigationBarItem): la de Material anima a la vez el tamaño del indicador, la
        // posición de la etiqueta y el cambio de icono, y en el S25 los iconos parecían temblar. Aquí la
        // geometría es fija y solo se animan colores (v3.1).
        Row(
            Modifier.fillMaxWidth().background(c.background).navigationBarsPadding().height(64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.entries.forEach { t -> LumiNavItem(t, t == current, Modifier.weight(1f)) { onSelect(t) } }
        }
    }
}

@Composable
private fun LumiNavItem(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Lumi.colors
    val iconColor by animateColorAsState(if (selected) c.accentText else c.textTertiary, tween(180), label = "navIcon")
    val textColor by animateColorAsState(if (selected) c.textPrimary else c.textTertiary, tween(180), label = "navText")
    val pill by animateColorAsState(if (selected) c.accentContainer else Color.Transparent, tween(180), label = "navPill")
    Column(
        modifier.fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 30.dp).clip(RoundedCornerShape(15.dp)).background(pill),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = textColor, maxLines = 1)
    }
}
