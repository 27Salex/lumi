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
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.StatusText
import io.github.salex27.lumi.presentation.theme.Lumi
import kotlinx.coroutines.launch

/**
 * Settings → Lumi Hub: the address of the hub on the PC (a Tailscale name) and the phone token it printed, with a
 * connection test. Self-contained (talks to the app's hub objects directly).
 */
@Composable
fun HubSettingsSection() {
    val c = Lumi.colors
    val app = LocalContext.current.applicationContext as TaskManagerApplication
    val config by app.hubSettings.config.collectAsState()
    val status by app.hubConnection.status.collectAsState()
    val scope = rememberCoroutineScope()
    var address by remember(config) { mutableStateOf(config.address) }
    var token by remember(config) { mutableStateOf(config.token) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val changed = address.trim() != config.address || token.trim() != config.token
    val addressError = address.isNotBlank() && HubSafety.normalizeAddress(address) == null
    val okText = stringResource(R.string.hub_test_ok)
    val failText = stringResource(R.string.hub_test_failed)
    val authText = stringResource(R.string.hub_test_unauthorized)

    SectionHeader(stringResource(R.string.hub_title), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.hub_sub), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    ListGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(address, { address = it; result = null }, stringResource(R.string.hub_address))
            if (addressError) StatusText(stringResource(R.string.hub_address_invalid), c.danger)
            Field(token, { token = it; result = null }, stringResource(R.string.hub_token), secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changed) PillButton(stringResource(R.string.save), style = PillStyle.PRIMARY, enabled = !addressError) {
                    app.hubSettings.save(address, token)
                }
                PillButton(stringResource(R.string.hub_test), style = PillStyle.SECONDARY, enabled = !testing && !changed && config.isConfigured) {
                    testing = true
                    scope.launch {
                        result = try {
                            val h = app.hubClient.health()
                            val agents = h.agents.joinToString { it.name }.ifBlank { "-" }
                            String.format(okText, h.host, agents) to true
                        } catch (e: HubClient.HubException) {
                            (if (e.code == 401 || e.code == 403) authText else String.format(failText, e.message ?: "")) to false
                        } catch (e: Exception) {
                            String.format(failText, e.message ?: e.javaClass.simpleName) to false
                        }
                        testing = false
                    }
                }
            }
            result?.let { (text, ok) -> StatusText(text, if (ok) c.success else c.danger) }
            if (config.isConfigured) Box {
                StatusText(
                    when (status) {
                        HubConnection.Status.CONNECTED -> stringResource(R.string.hub_status_connected)
                        HubConnection.Status.CONNECTING -> stringResource(R.string.hub_status_connecting)
                        HubConnection.Status.OFFLINE -> stringResource(R.string.hub_status_offline)
                        HubConnection.Status.OFF -> stringResource(R.string.hub_status_off)
                    },
                    if (status == HubConnection.Status.CONNECTED) c.success else c.warning
                )
            }
            Text(stringResource(R.string.hub_security_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}
