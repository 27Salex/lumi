package io.github.salex27.lumi.presentation.update

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.update.UpdateError
import io.github.salex27.lumi.data.update.UpdateManager
import io.github.salex27.lumi.data.update.UpdateState
import io.github.salex27.lumi.domain.update.ReleaseInfo
import io.github.salex27.lumi.domain.update.UpdateLogic
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.uiLocale
import io.github.salex27.lumi.presentation.theme.Lumi
import java.text.DateFormat
import java.util.Date

@Composable
private fun rememberManager(): UpdateManager = (LocalContext.current.applicationContext as TaskManagerApplication).updates

@Composable
private fun errorText(e: UpdateError) = stringResource(
    when (e) {
        UpdateError.OFFLINE -> R.string.update_err_offline
        UpdateError.RATE_LIMIT -> R.string.update_err_rate_limit
        UpdateError.SERVER -> R.string.update_err_server
        UpdateError.INVALID -> R.string.update_err_invalid
        UpdateError.NO_APK -> R.string.update_err_no_apk
        UpdateError.DOWNLOAD -> R.string.update_err_download
        UpdateError.SIZE, UpdateError.CHECKSUM -> R.string.update_err_checksum
        UpdateError.SIGNATURE -> R.string.update_err_signature
    }
)

/** Non-intrusive card on Home: only while a newer release is waiting and the user hasn't skipped it. */
@Composable
fun UpdateHomeCard() {
    val manager = rememberManager()
    val state by manager.state.collectAsStateWithLifecycle()
    val skipped by manager.skippedTag.collectAsStateWithLifecycle()
    var later by remember { mutableStateOf(false) }
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    } ?: return
    if (state is UpdateState.Available && (later || release.tag == skipped)) return
    val c = Lumi.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.accentContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UpdateBody(manager, state, release, onLater = { later = true }, showSkip = true)
    }
}

/** Settings → Updates: current version, last check, "Check now" and the pending release, if any. */
@Composable
fun UpdatesSettingsSection() {
    val manager = rememberManager()
    val state by manager.state.collectAsStateWithLifecycle()
    val last by manager.lastCheckAt.collectAsStateWithLifecycle()
    val c = Lumi.colors
    SectionHeader(stringResource(R.string.update_section), Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        ListRow(stringResource(R.string.update_current), manager.currentVersion)
        ListDivider()
        ListRow(
            stringResource(R.string.update_last_check),
            if (last <= 0) stringResource(R.string.update_never) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, uiLocale).format(Date(last))
        )
        ListDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val release = when (val s = state) {
                is UpdateState.Available -> s.release
                is UpdateState.Downloading -> s.release
                is UpdateState.Ready -> s.release
                is UpdateState.Failed -> s.release
                else -> null
            }
            when {
                release != null -> UpdateBody(manager, state, release, onLater = null, showSkip = false)
                state is UpdateState.Checking -> Text(stringResource(R.string.update_checking), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                state is UpdateState.Failed -> {
                    Text(errorText((state as UpdateState.Failed).error), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                }
                state is UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                else -> Text(stringResource(R.string.update_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            PillButton(stringResource(R.string.update_check_now), style = PillStyle.SECONDARY, enabled = state !is UpdateState.Checking) { manager.check(manual = true) }
        }
    }
}

/** Shared by the Home card and the Settings row: version, notes and the buttons for the current step. */
@Composable
private fun UpdateBody(manager: UpdateManager, state: UpdateState, release: ReleaseInfo, onLater: (() -> Unit)?, showSkip: Boolean) {
    val c = Lumi.colors
    val context = LocalContext.current
    val permissionHint = stringResource(R.string.update_allow_installs)
    var blocked by remember { mutableStateOf<UpdateError?>(null) }
    Text(stringResource(R.string.update_available).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.accentText)
    Text(stringResource(R.string.update_version, release.tag.removePrefix("v").removePrefix("V")), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
    val notes = remember(release) { UpdateLogic.trimNotes(release.notes) }
    if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 8)

    val error = (state as? UpdateState.Failed)?.error ?: blocked ?: if (!release.hasApk) UpdateError.NO_APK else null
    error?.let { Text(errorText(it), style = MaterialTheme.typography.bodyMedium, color = c.danger) }

    when (state) {
        is UpdateState.Downloading -> {
            val frac = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else null
            if (frac != null) LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = c.accentText, trackColor = c.muted)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accentText, trackColor = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.update_downloading, ((frac ?: 0f) * 100).toInt(), state.bytes / 1_048_576),
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f)
                )
            }
            PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST) { manager.cancelDownload() }
        }
        is UpdateState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.update_install)) {
                when (val i = manager.prepareInstall(release, state.file)) {
                    is UpdateManager.Install.Blocked -> blocked = i.error
                    is UpdateManager.Install.NeedsPermission -> {
                        Toast.makeText(context, permissionHint, Toast.LENGTH_LONG).show()
                        runCatching { context.startActivity(i.settings) }
                    }
                    is UpdateManager.Install.Go -> runCatching { context.startActivity(i.installer) }.onFailure { blocked = UpdateError.DOWNLOAD }
                }
            }
            onLater?.let { PillButton(stringResource(R.string.update_later), style = PillStyle.GHOST, onClick = it) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val failed = state is UpdateState.Failed
            PillButton(stringResource(if (failed) R.string.update_retry else R.string.update_action), enabled = release.hasApk) {
                blocked = null
                if (failed) manager.dismissError()
                manager.download(release)
            }
            onLater?.let { PillButton(stringResource(R.string.update_later), style = PillStyle.GHOST, onClick = it) }
            if (showSkip) PillButton(stringResource(R.string.update_skip), style = PillStyle.GHOST) { manager.skip(release) }
        }
    }
}
