package io.github.salex27.lumi.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.time.DueDateFormatter
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.StatusText
import io.github.salex27.lumi.presentation.theme.Lumi
import kotlinx.coroutines.launch

/**
 * Settings > Claude: connects Lumi to its relay (tools/lumi-relay) and shows the address to add in the Claude app as a
 * custom connector. The address is a secret (anyone with it can edit the tasks), so it is only shown here.
 */
@Composable
fun ClaudeConnectorSection() {
    val c = Lumi.colors
    val context = LocalContext.current
    val app = context.applicationContext as TaskManagerApplication
    val state by app.claudeRelay.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember(state.url) { mutableStateOf(state.url) }
    var connecting by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.claude_title), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.claude_intro), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))

    ListGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.error != null -> StatusText(state.error ?: "", c.danger)
                state.connected -> StatusText(
                    if (state.lastSync > 0) stringResource(R.string.claude_status_synced, DueDateFormatter.format(state.lastSync, true, lang = ReplyLanguage.app))
                    else stringResource(R.string.claude_status_connected),
                    c.success
                )
                else -> StatusText(stringResource(R.string.claude_status_off), c.warning)
            }

            if (!state.connected) {
                Field(url, { url = it }, stringResource(R.string.claude_relay_address))
                PillButton(
                    stringResource(if (connecting) R.string.claude_connecting else R.string.claude_connect),
                    enabled = !connecting && url.startsWith("https://")
                ) {
                    connecting = true
                    scope.launch { app.claudeRelay.connect(url); connecting = false }
                }
            }
        }

        state.connectorUrl?.let { link ->
            ListDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.claude_link_title), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted)
                        .clickable { copy(context, link) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        link, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.textSecondary,
                        maxLines = 2, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.claude_copy), tint = c.accentText, modifier = Modifier.size(18.dp))
                }
                Text(stringResource(R.string.claude_steps), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Text(stringResource(R.string.claude_link_private), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            ListDivider()
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(if (state.busy) R.string.claude_syncing else R.string.claude_sync_now), enabled = !state.busy) {
                    scope.launch { app.claudeRelay.syncNow() }
                }
                PillButton(stringResource(R.string.claude_new_link), style = PillStyle.GHOST) {
                    scope.launch { app.claudeRelay.connect(state.url) }
                }
                PillButton(stringResource(R.string.claude_disconnect), style = PillStyle.GHOST) { app.claudeRelay.disconnect() }
            }
            if (state.applied > 0) {
                Text(
                    stringResource(R.string.claude_applied, state.applied), style = MaterialTheme.typography.bodySmall, color = c.textTertiary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                )
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Lumi connector", text))
    Toast.makeText(context, R.string.claude_copied, Toast.LENGTH_SHORT).show()
}
