package io.github.salex27.lumi.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.domain.search.WebSearchBackend
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.theme.Lumi

/** Settings → Web search (#7): opt-in, and where to search (key-less by default). Self-contained. */
@Composable
fun WebSearchSettingsSection(onOpenServers: () -> Unit = {}) {
    val c = Lumi.colors
    val app = LocalContext.current.applicationContext as TaskManagerApplication
    val config by app.webSearch.config.collectAsState()
    val hub by app.hubSettings.config.collectAsState()
    val servers by app.servers.state.collectAsState()
    var searx by remember(config.searxUrl) { mutableStateOf(config.searxUrl) }
    var brave by remember(config.braveKey) { mutableStateOf(config.braveKey) }

    SectionHeader(stringResource(R.string.web_title), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.web_sub), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    ListGroup {
        ListRow(stringResource(R.string.web_enable), stringResource(R.string.web_enable_sub), trailing = {
            Toggle(config.enabled) { app.webSearch.save(config.copy(enabled = it)) }
        })
        if (config.enabled) {
            ListDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WebSearchBackend.entries.forEach { b ->
                    val on = b == config.backend
                    val enabled = b != WebSearchBackend.HUB || hub.isConfigured
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.accentContainer else c.muted)
                            .clickable(enabled = enabled) { app.webSearch.save(config.copy(backend = b)) }.padding(12.dp)
                    ) {
                        Text(
                            stringResource(when (b) {
                                WebSearchBackend.DEVICE -> R.string.web_device
                                WebSearchBackend.WIKIPEDIA -> R.string.web_wikipedia
                                WebSearchBackend.SEARXNG -> R.string.web_searxng
                                WebSearchBackend.BRAVE -> R.string.web_brave
                                WebSearchBackend.HUB -> R.string.web_hub
                            }),
                            style = MaterialTheme.typography.bodyLarge, color = if (on) c.accentText else if (enabled) c.textPrimary else c.textTertiary
                        )
                        Text(
                            stringResource(when (b) {
                                WebSearchBackend.DEVICE -> R.string.web_device_sub
                                WebSearchBackend.WIKIPEDIA -> R.string.web_wikipedia_sub
                                WebSearchBackend.SEARXNG -> R.string.web_searxng_sub
                                WebSearchBackend.BRAVE -> R.string.web_brave_sub
                                WebSearchBackend.HUB -> if (enabled) R.string.web_hub_sub else R.string.orbit_needs_hub
                            }),
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                        )
                    }
                }
                when (config.backend) {
                    WebSearchBackend.DEVICE -> {
                        Field(searx, { searx = it }, stringResource(R.string.web_device_instance))
                        if (searx.trim() != config.searxUrl) PillButton(stringResource(R.string.save)) { app.webSearch.save(config.copy(searxUrl = searx)) }
                    }
                    WebSearchBackend.SEARXNG -> {
                        val server = servers.serverFor(io.github.salex27.lumi.domain.server.ServerService.SEARCH)
                        if (server != null) {
                            Text(stringResource(R.string.web_server_assigned, server.name, io.github.salex27.lumi.domain.server.ServerLogic.baseUrl(servers, io.github.salex27.lumi.domain.server.ServerService.SEARCH).orEmpty()), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        } else {
                            Text(stringResource(R.string.web_server_none), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Field(searx, { searx = it }, stringResource(R.string.web_searxng_url))
                            if (searx.trim() != config.searxUrl) PillButton(stringResource(R.string.save)) { app.webSearch.save(config.copy(searxUrl = searx)) }
                        }
                        PillButton(stringResource(R.string.web_manage_servers), style = io.github.salex27.lumi.presentation.components.PillStyle.SECONDARY, onClick = onOpenServers)
                    }
                    WebSearchBackend.BRAVE -> {
                        Field(brave, { brave = it }, stringResource(R.string.web_brave_key), secret = true)
                        if (brave.trim() != config.braveKey) PillButton(stringResource(R.string.save)) { app.webSearch.save(config.copy(braveKey = brave)) }
                    }
                    else -> Unit
                }
                Text(stringResource(R.string.web_privacy), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
    }
}
