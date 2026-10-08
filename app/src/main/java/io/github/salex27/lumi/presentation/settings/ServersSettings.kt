package io.github.salex27.lumi.presentation.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.hub.HubSafety
import io.github.salex27.lumi.data.server.ServerTestResult
import io.github.salex27.lumi.data.server.ServerTester
import io.github.salex27.lumi.domain.server.ServerLogic
import io.github.salex27.lumi.domain.server.ServerProfile
import io.github.salex27.lumi.domain.server.ServerService
import io.github.salex27.lumi.domain.server.ServerTestError
import io.github.salex27.lumi.domain.server.ServiceEndpoint
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.StatusText
import io.github.salex27.lumi.presentation.theme.Lumi
import kotlinx.coroutines.launch

@Composable
private fun serviceName(s: ServerService) = stringResource(when (s) {
    ServerService.HUB -> R.string.svc_hub
    ServerService.SEARCH -> R.string.svc_search
    ServerService.PCVIEW -> R.string.svc_pcview
    ServerService.REMOTE -> R.string.svc_remote
})

/** The localized message of a failed connection test (what to check, not a stack trace). */
@Composable
internal fun serverErrorText(e: ServerTestError, service: ServerService): String = stringResource(when (e) {
    ServerTestError.NOT_CONFIGURED -> R.string.server_err_not_configured
    ServerTestError.BAD_URL -> R.string.server_err_bad_url
    ServerTestError.UNREACHABLE -> if (service == ServerService.SEARCH) R.string.server_err_unreachable_search else R.string.server_err_unreachable
    ServerTestError.TIMEOUT -> R.string.server_err_timeout
    ServerTestError.TLS -> R.string.server_err_tls
    ServerTestError.UNAUTHORIZED -> R.string.server_err_unauthorized
    ServerTestError.FORBIDDEN -> if (service == ServerService.SEARCH) R.string.server_err_forbidden_search else R.string.server_err_forbidden
    ServerTestError.NOT_FOUND -> if (service == ServerService.SEARCH) R.string.server_err_not_found_search else R.string.server_err_not_found
    ServerTestError.SERVER_ERROR -> R.string.server_err_server
    ServerTestError.BAD_ANSWER -> R.string.server_err_bad_answer
    ServerTestError.PC_ROUTES_MISSING -> R.string.server_err_pc_routes
    ServerTestError.PC_DISABLED -> R.string.server_err_pc_disabled
    ServerTestError.RDP_CLOSED -> R.string.server_err_rdp_closed
    ServerTestError.OTHER -> R.string.server_err_other
})

@Composable
private fun TestLine(result: ServerTestResult, service: ServerService) {
    val c = Lumi.colors
    when (result) {
        is ServerTestResult.Ok -> StatusText(
            when (service) {
                ServerService.SEARCH -> stringResource(R.string.server_test_ok_search, result.detail)
                ServerService.REMOTE -> stringResource(R.string.server_test_ok_rdp, result.detail)
                else -> if (result.detail.isBlank()) stringResource(R.string.server_test_ok) else stringResource(R.string.server_test_ok_detail, result.detail)
            }, c.success
        )
        is ServerTestResult.Failed -> StatusText(serverErrorText(result.error, service), c.danger)
    }
}

/**
 * Settings > Servers & PC: define the PCs / services once (name, address, optional token), say which one each service
 * uses, and test each connection. Old settings were migrated into the first server, so nothing needs re-entering.
 */
@Composable
fun ServersSettingsSection() {
    val c = Lumi.colors
    val app = LocalContext.current.applicationContext as TaskManagerApplication
    val state by app.servers.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val results = remember { mutableStateMapOf<ServerService, ServerTestResult>() }
    val testing = remember { mutableStateMapOf<ServerService, Boolean>() }
    val svcNames = ServerService.entries.associateWith { serviceName(it) }

    SectionHeader(stringResource(R.string.servers_title), Modifier.padding(start = 4.dp))
    Text(stringResource(R.string.servers_sub), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    ListGroup {
        if (state.servers.isEmpty() && editing == null) {
            Text(stringResource(R.string.servers_none), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(16.dp))
        }
        state.servers.forEachIndexed { i, server ->
            if (i > 0) ListDivider()
            val usedBy = ServerService.entries.filter { state.idFor(it) == server.id }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(server.name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(server.host.ifBlank { "-" }, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                if (usedBy.isNotEmpty()) {
                    Text(stringResource(R.string.server_used_by, usedBy.joinToString { svcNames.getValue(it) }), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.server_edit), style = PillStyle.SECONDARY) { editing = server; editingIsNew = false }
                    PillButton(stringResource(R.string.server_use_all), style = PillStyle.SECONDARY, enabled = usedBy.size < ServerService.entries.size) {
                        app.servers.useForAll(server.id); results.clear()
                    }
                    PillButton(stringResource(R.string.delete), style = PillStyle.GHOST) {
                        app.servers.remove(server.id); results.clear(); if (editing?.id == server.id) editing = null
                    }
                }
            }
        }
        editing?.let { e ->
            if (state.servers.isNotEmpty() || !editingIsNew) ListDivider()
            var name by remember(e.id) { mutableStateOf(e.name) }
            var url by remember(e.id) { mutableStateOf(e.host) }
            var token by remember(e.id) { mutableStateOf(e.token) }
            val parsedUrl = ServerLogic.splitUrl(url)
            val urlOk = url.isBlank() || parsedUrl != null
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it }, stringResource(R.string.server_name))
                Field(url, { url = it }, stringResource(R.string.server_url))
                if (!urlOk) StatusText(stringResource(R.string.server_err_bad_url), c.danger)
                Field(token, { token = it }, stringResource(R.string.server_token), secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.save), enabled = urlOk && parsedUrl != null) {
                        val p = parsedUrl!!
                        app.servers.upsert(e.copy(name = name.trim().ifBlank { e.name }, host = p.host, scheme = if (url.contains("://")) p.scheme else e.scheme, token = token.trim()))
                        // a pasted port is the user's explicit choice: it becomes the endpoint of every service that uses this server
                        if (p.port != null) ServerService.entries.filter { it != ServerService.REMOTE && app.servers.current.idFor(it) == e.id }.forEach { sv ->
                            app.servers.update { ServerLogic.setEndpoint(it, sv, ServiceEndpoint(p.port, if (sv == ServerService.SEARCH) p.path else "")) }
                        }
                        results.clear(); editing = null
                    }
                    PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST) { editing = null }
                }
            }
        }
        ListDivider()
        Box(Modifier.padding(16.dp)) {
            PillButton(stringResource(R.string.server_add), style = PillStyle.SECONDARY) {
                val id = ServerLogic.newId(state)
                editing = ServerProfile(id, stringResource0(app, R.string.server_new_name, state.servers.size + 1)); editingIsNew = true
            }
        }
    }

    SectionHeader(stringResource(R.string.server_services), Modifier.padding(start = 4.dp, top = 12.dp))
    ListGroup {
        ServerService.entries.forEachIndexed { i, service ->
            if (i > 0) ListDivider()
            val assigned = state.serverFor(service)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(serviceName(service), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(stringResource(when (service) {
                    ServerService.HUB -> R.string.svc_hub_sub
                    ServerService.SEARCH -> R.string.svc_search_sub
                    ServerService.PCVIEW -> R.string.svc_pcview_sub
                    ServerService.REMOTE -> R.string.svc_remote_sub
                }), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segment(stringResource(if (service == ServerService.SEARCH) R.string.svc_search_phone else R.string.svc_none), assigned == null) {
                        app.servers.assign(service, null); results.remove(service)
                    }
                    state.servers.forEach { s ->
                        Segment(s.name, assigned?.id == s.id) { app.servers.assign(service, s.id); results.remove(service) }
                    }
                }
                if (assigned != null) {
                    val ep = state.endpointFor(service)
                    var portText by remember(service, assigned.id, ep.port) { mutableStateOf(ep.port.toString()) }
                    var pathText by remember(service, assigned.id, ep.path) { mutableStateOf(ep.path) }
                    val portVal = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
                    Field(portText, { portText = it.filter(Char::isDigit).take(5) }, stringResource(R.string.svc_port))
                    if (service == ServerService.SEARCH) Field(pathText, { pathText = it }, stringResource(R.string.svc_path))
                    if (portVal == null) StatusText(stringResource(R.string.server_err_bad_port), c.danger)
                    else if (portVal != ep.port || (service == ServerService.SEARCH && pathText.trim() != ep.path)) {
                        PillButton(stringResource(R.string.save), style = PillStyle.SECONDARY) {
                            app.servers.update { ServerLogic.setEndpoint(it, service, ServiceEndpoint(portVal, if (service == ServerService.SEARCH) pathText.trim() else "")) }
                            results.remove(service)
                        }
                    }
                    Text(
                        stringResource(if (service == ServerService.REMOTE) R.string.svc_full_hostport else R.string.svc_full_url, ServerLogic.baseUrl(state, service) ?: "-"),
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        stringResource(if (testing[service] == true) R.string.server_testing else R.string.server_test), style = PillStyle.SECONDARY,
                        enabled = assigned != null && testing[service] != true
                    ) {
                        testing[service] = true
                        scope.launch {
                            results[service] = ServerTester.test(service, state)
                            testing[service] = false
                        }
                    }
                }
                results[service]?.let { TestLine(it, service) }
            }
        }
    }
}

private fun stringResource0(app: TaskManagerApplication, id: Int, n: Int) = app.getString(id, n)
