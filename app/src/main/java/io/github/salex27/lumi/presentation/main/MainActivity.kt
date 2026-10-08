package io.github.salex27.lumi.presentation.main

import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource

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
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.foundation.layout.ime
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
private enum class Tab(@androidx.annotation.StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    TASKS(R.string.tab_tasks, Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    AGENDA(R.string.agenda, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    STATS(R.string.tab_progress, Icons.Outlined.Insights, Icons.Filled.Insights),
    CHATS(R.string.chats_title, Icons.Outlined.Forum, Icons.Filled.Forum)
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Open this task's editor on entry ("edit the dentist one" from the assistant). */
        const val EXTRA_EDIT_TASK_ID = "edit_task_id"
        /** Open the Lumi Hub message (agent message, question or task proposal) with this chat message id. */
        const val EXTRA_OPEN_HUB_MESSAGE = "open_hub_message"
    }

    /** Task the assistant asked to edit (consumed when the editor opens). */
    private val pendingEditId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** Hub message to show (from a notification): its agent inbox opens in Orbit. */
    private val pendingHubMessage = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra(EXTRA_EDIT_TASK_ID, -1L).takeIf { it > 0 }?.let { pendingEditId.value = it }
        intent.getLongExtra(EXTRA_OPEN_HUB_MESSAGE, -1L).takeIf { it > 0 }?.let { pendingHubMessage.value = it }
    }

    private val orbitViewModel: io.github.salex27.lumi.presentation.orbit.OrbitViewModel by viewModels {
        io.github.salex27.lumi.presentation.orbit.OrbitViewModel.Factory(app.orbits, app.hubInbox, app.hubSettings)
    }

    private val app get() = application as TaskManagerApplication

    private val pcViewModel: io.github.salex27.lumi.presentation.pcview.PcViewViewModel by viewModels {
        io.github.salex27.lumi.presentation.pcview.PcViewViewModel.Factory(app.pcViewClient, app.hubSettings)
    }
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

    /** "Oye Lumi": microphone → (optional) display over other apps → download the model → start the service. */
    private val wakeMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) continueWakeWordSetup() else settingsViewModel.onWakeWordError(getString(R.string.wake_needs_mic))
    }

    /** Place to save as soon as the location permission is granted. */
    private var pendingPlace: Pair<String, String>? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val place = pendingPlace
        pendingPlace = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            place?.let { settingsViewModel.savePlaceHere(it.first, it.second) }
        } else {
            settingsViewModel.onPlaceError(getString(R.string.place_needs_precise))
        }
    }

    // Android 11+: "Allow all the time" can only be chosen on the system permission screen
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

    /** Manual alias: finds the contact in the address book (asks for the permission if needed) and saves it. */
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
            android.widget.Toast.makeText(this, getString(R.string.contact_not_found, contactName), android.widget.Toast.LENGTH_SHORT).show()
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
            is GoogleTasksAuth.Result.NeedsConsent -> settingsViewModel.onGoogleTasksError(getString(R.string.google_tasks_no_consent))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as io.github.salex27.lumi.TaskManagerApplication).updateAppLanguage(resources.configuration)
        viewModel.refreshIfLanguageChanged()
        if (savedInstanceState == null) intent?.getLongExtra(EXTRA_EDIT_TASK_ID, -1L)?.takeIf { it > 0 }?.let { pendingEditId.value = it }
        if (savedInstanceState == null) intent?.getLongExtra(EXTRA_OPEN_HUB_MESSAGE, -1L)?.takeIf { it > 0 }?.let { pendingHubMessage.value = it }

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            LumiAppTheme(themeMode = settings.themeMode) {
                val dark = Lumi.colors.isDark
                // Transparent system bars with dark icons in light mode and light ones in dark mode
                DisposableEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
                    else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }

                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var editing by remember { mutableStateOf<Task?>(null) }
                // The assistant asked to edit a task → its editor opens
                val editRequest by pendingEditId.collectAsStateWithLifecycle()
                LaunchedEffect(editRequest) {
                    val id = editRequest ?: return@LaunchedEffect
                    pendingEditId.value = null
                    app.repository.getTask(id)?.let { showSettings = false; tab = Tab.TASKS; editing = it }
                }
                val openThread by orbitViewModel.open.collectAsStateWithLifecycle()
                var showMyPc by rememberSaveable { mutableStateOf(false) }
                var showManage by rememberSaveable { mutableStateOf(false) }
                val hubRequest by pendingHubMessage.collectAsStateWithLifecycle()
                LaunchedEffect(hubRequest) {
                    val id = hubRequest ?: return@LaunchedEffect
                    pendingHubMessage.value = null
                    app.chatStore.flush()
                    app.chatStore.message(id)?.let { showSettings = false; showMyPc = false; tab = Tab.CHATS; orbitViewModel.openThread(it.sessionId) }
                }
                BackHandler(enabled = showSettings || tab != Tab.HOME) {
                    if (showSettings) showSettings = false else tab = Tab.HOME
                }

                Box(Modifier.fillMaxSize().background(Lumi.colors.background)) {
                    if (showMyPc) {
                        io.github.salex27.lumi.presentation.pcview.PcViewScreen(
                            pcViewModel, onBack = { showMyPc = false },
                            onOpenSettings = { showMyPc = false; showSettings = true }
                        )
                    } else if (tab == Tab.CHATS && !showSettings && (openThread != null || showManage)) {
                        // A thread or "Manage agents" takes the whole screen (own keyboard handling), without the tab bar
                        io.github.salex27.lumi.presentation.orbit.OrbitScreen(orbitViewModel, onBack = { showManage = false })
                    } else if (showSettings) {
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
                            assistantSection = { io.github.salex27.lumi.presentation.settings.AssistantSettings(app) },
                            onOpenMyPc = { showSettings = false; showMyPc = true }
                        )
                    } else {
                        val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                        // Keyboard up: hide the tab bar so the Home SmartBar (and any field) sits right above the keyboard
                        Scaffold(containerColor = Lumi.colors.background, bottomBar = { if (!imeOpen) BottomBar(tab) { tab = it } }) { padding ->
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
                                        onOpenMyPc = { showMyPc = true },
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
                                    Tab.CHATS -> io.github.salex27.lumi.presentation.orbit.ChatsScreen(
                                        orbitViewModel, onBack = { tab = Tab.HOME }, onManage = { showManage = true }, embedded = true,
                                        onOpenAssistantChat = { id ->
                                            if (id == null) app.chatStore.setActive(null) else app.chatStore.requestResume(id)
                                            startActivity(AssistantActivity.intent(this@MainActivity))
                                        }
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
        // New calendar meetings / location permission just granted
        app.appScope.launch { app.liveUpdates.refresh(); app.placeReminders.resyncAll() }
        app.appScope.launch { app.weather.forecast() } // uses the cached one if it is < 30 min old
        // Background microphone can only be started with the app visible (Android 14+)
        val s = app.settings.current
        // v3.5: the "Oye Lumi" detector ships inside the app → listening starts now; Vosk (voice print) downloads separately
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

    // ── "Oye Lumi" ──────────────────────────────────────────────────────────

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWakeWordSetup() {
        if (hasMic()) continueWakeWordSetup() else wakeMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Marked as enabled BEFORE downloading: the download runs in the app scope (it outlives this screen) and, if the
     * Activity is recreated, onResume starts listening as soon as the model is ready.
     * The "Display over other apps" permission is no longer opened at the same time (it closed the screen and
     * cancelled the download): it is a separate button in Settings.
     */
    private fun continueWakeWordSetup() {
        app.settings.update { it.copy(wakeWordEnabled = true) }
        settingsViewModel.onWakeWordError(null)
        // The detector needs no downloads; the Vosk model is only needed for "Train my voice"
        startWakeWordIfPossible()
        app.appScope.launch { app.wakeWordModel.download() }
    }

    private fun startWakeWordIfPossible() {
        if (!WakeWordService.running.value) {
            runCatching { WakeWordService.start(this) }
                .onFailure { settingsViewModel.onWakeWordError(getString(R.string.wake_start_failed, it.message ?: "")) }
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
        // Our own bar (not NavigationBarItem): Material's animates the indicator size, the label position and the icon
        // change at the same time, and on the S25 the icons seemed to shake. Here the geometry is fixed and only
        // colors animate (v3.1).
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
        Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium, color = textColor, maxLines = 1)
    }
}
