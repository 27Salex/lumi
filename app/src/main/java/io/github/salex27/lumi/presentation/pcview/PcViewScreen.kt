package io.github.salex27.lumi.presentation.pcview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.salex27.lumi.R
import io.github.salex27.lumi.domain.pcview.PcPhase
import io.github.salex27.lumi.domain.pcview.ViewTransform
import io.github.salex27.lumi.domain.pcview.ZoomPan
import io.github.salex27.lumi.presentation.components.LumiCard
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.theme.Lumi

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * My PC: view-only live view of the PC screens. Status and unlock, monitor picker, pinch-zoom and pan, fit, pause,
 * full screen (landscape) and Lock now. There is no input of any kind towards the PC.
 */
@Composable
fun PcViewScreen(vm: PcViewViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Lumi.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val state by vm.state.collectAsState()
    val monitors by vm.monitors.collectAsState()
    val monitor by vm.monitor.collectAsState()
    val frame by vm.frame.collectAsState()
    val paused by vm.paused.collectAsState()
    val secondsLeft by vm.secondsLeft.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    // toolbar -> canvas (which owns the transform): bump to re-fit
    var fitTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var widthTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Screenshots and the recent-apps thumbnail must not capture the PC screen
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Leaving the app or the screen locks again; coming back re-checks the hub
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onLeave()
            if (event == Lifecycle.Event.ON_START) vm.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.onLeave() }
    }
    // Full screen = landscape + hidden system bars
    DisposableEffect(fullscreen, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = true) { if (fullscreen) fullscreen = false else onBack() }
    LaunchedEffect(Unit) { vm.refresh() }

    val viewing = state.phase == PcPhase.VIEWING || state.phase == PcPhase.RECONNECTING
    val unlockTitle = stringResource(R.string.pc_biometric_title)
    val unlockSub = stringResource(R.string.pc_biometric_sub)
    val cancelled = stringResource(R.string.pc_auth_cancelled)
    var authMessage by remember { mutableStateOf<String?>(null) }
    val unlock = {
        authMessage = null
        if (!PcAuth.isAvailable(context)) vm.noSecureLock()
        else PcAuth.prompt(context, unlockTitle, unlockSub, onSuccess = { vm.unlockAfterAuth() }, onFailure = { authMessage = cancelled })
        Unit
    }

    Column(
        Modifier.fillMaxSize().background(c.background)
            .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars))
    ) {
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pc_back), tint = c.textPrimary) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pc_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                    Text(stringResource(R.string.pc_sub), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                if (viewing) PillButton(stringResource(R.string.pc_lock_now), style = PillStyle.SECONDARY) { vm.lockNow() }
            }
        }

        if (!viewing) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(state.phase, state.retryAfterSeconds, authMessage,
                    onUnlock = unlock, onRetry = { vm.refresh() }, onSettings = onOpenSettings,
                    onSecuritySettings = { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
            }
        } else {
            val logical = monitors.firstOrNull { it.id == monitor }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(c.surface)) {
                val image = frame
                if (image == null || logical == null) {
                    Text(stringResource(R.string.pc_waiting_frame), color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
                } else {
                    ScreenCanvas(image, logical.width.toFloat(), logical.height.toFloat(), monitor, fitTick, widthTick)
                }
                if (state.phase == PcPhase.RECONNECTING) {
                    Text(stringResource(R.string.pc_reconnecting), style = MaterialTheme.typography.labelLarge, color = c.textPrimary,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(50)).background(c.elevated).padding(horizontal = 14.dp, vertical = 6.dp))
                } else if (paused) {
                    Text(stringResource(R.string.pc_paused), style = MaterialTheme.typography.labelLarge, color = c.textPrimary,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(50)).background(c.elevated).padding(horizontal = 14.dp, vertical = 6.dp))
                }
                if (fullscreen) {
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.pc_exit_fullscreen), style = PillStyle.SECONDARY) { fullscreen = false }
                        PillButton(stringResource(R.string.pc_lock_now), style = PillStyle.PRIMARY) { vm.lockNow() }
                    }
                }
            }
            if (!fullscreen) Controls(
                monitors = monitors.map { it.id }, selected = monitor, paused = paused, secondsLeft = secondsLeft,
                onMonitor = vm::selectMonitor, onPause = { vm.setPaused(!paused) }, onFullscreen = { fullscreen = true },
                onFit = { fitTick++ }, onFitWidth = { widthTick++ }
            )
        }
    }
}

@Composable
private fun ScreenCanvas(image: PcFrameImage, logicalW: Float, logicalH: Float, monitor: Int, fitCount: Int, widthCount: Int) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf<ViewTransform?>(null) }
    val vw = size.width.toFloat()
    val vh = size.height.toFloat()
    // a new monitor or a new view size starts fitted
    LaunchedEffect(size, monitor, logicalW, logicalH) {
        if (size.width > 0) transform = ZoomPan.fit(vw, vh, logicalW, logicalH)
    }
    LaunchedEffect(fitCount) { if (fitCount > 0 && size.width > 0) transform = ZoomPan.fit(vw, vh, logicalW, logicalH) }
    LaunchedEffect(widthCount) { if (widthCount > 0 && size.width > 0) transform = ZoomPan.fitWidth(vw, vh, logicalW, logicalH) }
    val t = transform ?: return Box(Modifier.fillMaxSize().onSizeChanged { size = it })
    Canvas(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(logicalW, logicalH) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val current = transform ?: return@detectTransformGestures
                    transform = ZoomPan.gesture(current, zoom, pan.x, pan.y, centroid.x, centroid.y, size.width.toFloat(), size.height.toFloat(), logicalW, logicalH)
                }
            }
            .pointerInput(logicalW, logicalH) {
                detectTapGestures(onDoubleTap = { at ->
                    val current = transform ?: return@detectTapGestures
                    transform = ZoomPan.doubleTap(current, at.x, at.y, size.width.toFloat(), size.height.toFloat(), logicalW, logicalH)
                })
            }
    ) {
        drawImage(
            image.image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(t.offX.toInt(), t.offY.toInt()),
            dstSize = IntSize((logicalW * t.scale).toInt().coerceAtLeast(1), (logicalH * t.scale).toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium
        )
    }
}

@Composable
private fun Controls(
    monitors: List<Int>, selected: Int, paused: Boolean, secondsLeft: Int,
    onMonitor: (Int) -> Unit, onPause: () -> Unit, onFullscreen: () -> Unit, onFit: () -> Unit, onFitWidth: () -> Unit
) {
    val c = Lumi.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (monitors.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                monitors.forEachIndexed { index, id ->
                    PillButton(stringResource(R.string.pc_monitor_n, index + 1), style = if (id == selected) PillStyle.PRIMARY else PillStyle.SECONDARY) { onMonitor(id) }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.pc_fit), style = PillStyle.SECONDARY, onClick = onFit)
            PillButton(stringResource(R.string.pc_fit_width), style = PillStyle.SECONDARY, onClick = onFitWidth)
            PillButton(stringResource(if (paused) R.string.pc_resume else R.string.pc_pause), style = PillStyle.SECONDARY, onClick = onPause)
            PillButton(stringResource(R.string.pc_fullscreen), style = PillStyle.SECONDARY, onClick = onFullscreen)
        }
        Text(stringResource(R.string.pc_time_left, "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
    }
}

@Composable
private fun StatusCard(
    phase: PcPhase, retryAfter: Int, authMessage: String?,
    onUnlock: () -> Unit, onRetry: () -> Unit, onSettings: () -> Unit, onSecuritySettings: () -> Unit
) {
    val c = Lumi.colors
    data class Info(val title: Int, val body: Int?, val action: Int?, val onAction: () -> Unit)
    val info = when (phase) {
        PcPhase.NOT_CONFIGURED -> Info(R.string.pc_not_configured_title, R.string.pc_not_configured_body, R.string.pc_open_settings, onSettings)
        PcPhase.CHECKING -> Info(R.string.pc_checking, null, null, {})
        PcPhase.HUB_OFFLINE -> Info(R.string.pc_offline_title, R.string.pc_offline_body, R.string.pc_retry, onRetry)
        PcPhase.HUB_OLD -> Info(R.string.pc_old_title, R.string.pc_old_body, R.string.pc_open_settings, onSettings)
        PcPhase.UNAUTHORIZED -> Info(R.string.pc_unauth_title, R.string.pc_unauth_body, R.string.pc_open_settings, onSettings)
        PcPhase.DISABLED -> Info(R.string.pc_disabled_title, R.string.pc_disabled_body, R.string.pc_retry, onRetry)
        PcPhase.LOCKED -> Info(R.string.pc_locked_title, R.string.pc_locked_body, R.string.pc_unlock, onUnlock)
        PcPhase.UNLOCKING -> Info(R.string.pc_unlocking, null, null, {})
        PcPhase.EXPIRED -> Info(R.string.pc_expired_title, R.string.pc_expired_body, R.string.pc_unlock, onUnlock)
        PcPhase.LOCKED_OUT -> Info(R.string.pc_locked_out_title, R.string.pc_locked_out_body, R.string.pc_retry, onRetry)
        PcPhase.NO_SECURE_LOCK -> Info(R.string.pc_no_lock_title, R.string.pc_no_lock_body, R.string.pc_security_settings, onSecuritySettings)
        PcPhase.VIEWING, PcPhase.RECONNECTING -> Info(R.string.pc_title, null, null, {})
    }
    LumiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(info.title), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            info.body?.let {
                val text = if (phase == PcPhase.LOCKED_OUT) stringResource(it, (retryAfter + 59) / 60) else stringResource(it)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            authMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
            Spacer(Modifier.height(4.dp))
            info.action?.let { PillButton(stringResource(it), style = PillStyle.PRIMARY, onClick = info.onAction) }
        }
    }
}
