package io.github.salex27.lumi.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.hub.HubClient
import io.github.salex27.lumi.data.hub.HubConnection
import io.github.salex27.lumi.data.hub.HubSafety
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.StatusText
import io.github.salex27.lumi.presentation.theme.Lumi
import kotlinx.coroutines.launch

import androidx.compose.runtime.LaunchedEffect
import io.github.salex27.lumi.presentation.orbit.ModelEffortPanel

/**
 * Settings > Servers & PC > Lumi Hub: connection state of the event stream, the default Claude model and reasoning
 * level (only when the hub offers them) and the My PC entry. The address and token live in the server list above.
 */
@Composable
fun HubSettingsSection(onOpenMyPc: () -> Unit = {}) {
    val c = Lumi.colors
    val app = LocalContext.current.applicationContext as TaskManagerApplication
    val config by app.hubSettings.config.collectAsState()
    val status by app.hubConnection.status.collectAsState()
    val options by app.agentChoices.options.collectAsState()
    val default by app.agentChoices.default.collectAsState()
    LaunchedEffect(config) { if (config.isConfigured) app.agentChoices.refresh() }

    SectionHeader(stringResource(R.string.hub_title), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.hub_sub), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    ListGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!config.isConfigured) StatusText(stringResource(R.string.hub_not_assigned), c.warning)
            else StatusText(
                when (status) {
                    HubConnection.Status.CONNECTED -> stringResource(R.string.hub_status_connected)
                    HubConnection.Status.CONNECTING -> stringResource(R.string.hub_status_connecting)
                    HubConnection.Status.OFFLINE -> stringResource(R.string.hub_status_offline)
                    HubConnection.Status.RECONNECTING -> stringResource(R.string.hub_status_reconnecting)
                    HubConnection.Status.OFF -> stringResource(R.string.hub_status_off)
                },
                if (status == HubConnection.Status.CONNECTED) c.success else c.warning
            )
            Text(stringResource(R.string.hub_security_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        options?.takeIf { it.models.isNotEmpty() || it.efforts.isNotEmpty() }?.let { o ->
            ListDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.model_title), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(stringResource(R.string.model_default_sub), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                ModelEffortPanel(o, default.validated(o), stringResource(R.string.model_default)) { app.agentChoices.setDefault(it) }
            }
        }
        ListDivider()
        ListRow(stringResource(R.string.pc_settings_row), stringResource(R.string.pc_settings_row_sub), onClick = onOpenMyPc)
    }
}
