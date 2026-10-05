package io.github.salex27.lumi.presentation.orbit

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.salex27.lumi.R
import io.github.salex27.lumi.data.hub.HubEvent
import io.github.salex27.lumi.data.hub.HubPayload
import io.github.salex27.lumi.data.hub.HubSafety
import io.github.salex27.lumi.data.local.AgentEntity
import io.github.salex27.lumi.data.local.ChatMessageEntity
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.data.local.ChatSessionRow
import io.github.salex27.lumi.domain.orbit.AgentBackendKind
import io.github.salex27.lumi.domain.orbit.AgentFaceStyle
import io.github.salex27.lumi.domain.orbit.AgentPalette
import io.github.salex27.lumi.presentation.components.AgentFace
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.LumiMark
import io.github.salex27.lumi.presentation.components.LumiState
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.settings.Field
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.presentation.theme.color

/** Payload helpers for rendering (pure). */
internal object OrbitUi {
    fun payload(m: ChatMessageEntity) = HubPayload.decode(m.payload)
    fun openAsk(m: ChatMessageEntity) = payload(m)?.let { it.hub == HubEvent.ASK && it.state == HubPayload.STATE_OPEN } == true
    fun pending(m: ChatMessageEntity) = m.text.isBlank() && payload(m)?.let { it.hub == HubEvent.REPLY && it.state == HubPayload.STATE_OPEN } == true
    fun streaming(m: ChatMessageEntity) = payload(m)?.let { it.hub == HubEvent.REPLY && it.state == HubPayload.STATE_OPEN } == true
}

/** Orbit: list of threads and agents, and the open thread. Back goes thread → list → [onBack]. */
@Composable
fun OrbitScreen(vm: OrbitViewModel, onBack: () -> Unit) {
    val open by vm.open.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val closedText = stringResource(R.string.hub_question_closed)
    val unreachableText = stringResource(R.string.hub_unreachable)
    val addedText = stringResource(R.string.hub_task_added)
    LaunchedEffect(Unit) {
        vm.notices.collect {
            val text = when (it) {
                OrbitViewModel.Notice.QUESTION_CLOSED -> closedText
                OrbitViewModel.Notice.HUB_UNREACHABLE -> unreachableText
                OrbitViewModel.Notice.TASK_ADDED -> addedText
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler { if (open != null) vm.openThread(null) else onBack() }
    Box(Modifier.fillMaxSize().background(Lumi.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (open == null) OrbitList(vm, onBack) else OrbitThread(vm) { vm.openThread(null) }
    }
}

// ── List ─────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OrbitList(vm: OrbitViewModel, onBack: () -> Unit) {
    val c = Lumi.colors
    val orbits by vm.orbits.collectAsStateWithLifecycle()
    val inboxes by vm.inboxes.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val hub by vm.hubConfigured.collectAsStateWithLifecycle()
    var newOrbit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AgentEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.textPrimary) }
            Text(stringResource(R.string.orbit_title), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }
        Text(stringResource(R.string.orbit_sub), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))

        ListGroup(Modifier.padding(top = 8.dp)) {
            ListRow(
                stringResource(R.string.orbit_chat_with_claude),
                stringResource(if (hub) R.string.orbit_chat_with_claude_sub else R.string.orbit_needs_hub),
                onClick = if (hub) ({ vm.chatWithClaude() }) else null,
                leading = { AgentFace(AgentBackendKind.CLAUDE_PC.defaultFace, AgentBackendKind.CLAUDE_PC.defaultColor) }
            )
            ListDivider()
            ListRow(stringResource(R.string.orbit_new), stringResource(R.string.orbit_new_sub), onClick = { newOrbit = true },
                leading = { LumiMark(LumiState.IDLE, size = 32.dp) })
        }

        if (orbits.isNotEmpty()) {
            SectionHeader(stringResource(R.string.orbit_threads), Modifier.padding(start = 4.dp, top = 12.dp))
            ListGroup { orbits.forEachIndexed { i, row -> if (i > 0) ListDivider(); ThreadRow(row, agents) { vm.openThread(row.session.id) } } }
        }
        if (inboxes.isNotEmpty()) {
            SectionHeader(stringResource(R.string.orbit_inboxes), Modifier.padding(start = 4.dp, top = 12.dp))
            ListGroup { inboxes.forEachIndexed { i, row -> if (i > 0) ListDivider(); ThreadRow(row, emptyList()) { vm.openThread(row.session.id) } } }
        }

        SectionHeader(stringResource(R.string.orbit_agents), Modifier.padding(start = 4.dp, top = 12.dp))
        ListGroup {
            agents.forEach { a ->
                ListRow(a.name, backendLabel(AgentBackendKind.of(a.backend)), onClick = { editing = a },
                    leading = { AgentFace(AgentFaceStyle.of(a.face), AgentPalette.of(a.color)) })
                ListDivider()
            }
            ListRow(stringResource(R.string.orbit_add_agent), null, onClick = { adding = true },
                leading = { Icon(Icons.Outlined.Add, null, tint = c.accentText, modifier = Modifier.size(24.dp)) })
        }
        Text(stringResource(R.string.orbit_more_backends), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }

    if (newOrbit) NewOrbitDialog(agents, onDismiss = { newOrbit = false }) { title, ids -> newOrbit = false; vm.createOrbit(title, ids) }
    if (adding || editing != null) AgentDialog(editing, hub, onDismiss = { adding = false; editing = null },
        onDelete = { editing?.let { vm.deleteAgent(it.id) }; editing = null },
        onSave = { kind, name, color, face, purpose -> vm.saveAgent(editing, kind, name, color, face, purpose); adding = false; editing = null })
}

@Composable
private fun backendLabel(kind: AgentBackendKind?) = when (kind) {
    AgentBackendKind.LUMI -> stringResource(R.string.backend_lumi)
    AgentBackendKind.CLAUDE_PC -> stringResource(R.string.backend_claude_pc)
    null -> "-"
}

@Composable
private fun ThreadRow(row: ChatSessionRow, agents: List<AgentEntity>, onClick: () -> Unit) {
    ListRow(row.session.title.ifBlank { "Orbit" }, row.preview?.replace('\n', ' ')?.take(80), onClick = onClick,
        leading = {
            if (row.session.kind == ChatSessionEntity.KIND_HUB) AgentFace(AgentFaceStyle.RING, AgentPalette.TEAL)
            else LumiMark(LumiState.IDLE, size = 32.dp)
        })
}

@Composable
private fun NewOrbitDialog(agents: List<AgentEntity>, onDismiss: () -> Unit, onCreate: (String, List<Long>) -> Unit) {
    val c = Lumi.colors
    var title by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.elevated).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.orbit_new), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Field(title, { title = it }, stringResource(R.string.orbit_name_hint))
            Text(stringResource(R.string.orbit_pick_agents), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            if (agents.isEmpty()) Text(stringResource(R.string.orbit_no_agents), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            agents.forEach { a ->
                val on = a.id in picked
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.accentContainer else c.muted)
                    .clickable { if (on) picked.remove(a.id) else picked.add(a.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentFace(AgentFaceStyle.of(a.face), AgentPalette.of(a.color), size = 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, color = if (on) c.accentText else c.textPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.orbit_create), enabled = title.isNotBlank()) { onCreate(title, picked.toList()) }
                PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AgentDialog(
    agent: AgentEntity?, hubConfigured: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit,
    onSave: (AgentBackendKind, String, AgentPalette, AgentFaceStyle, String) -> Unit
) {
    val c = Lumi.colors
    val startKind = agent?.let { AgentBackendKind.of(it.backend) } ?: if (hubConfigured) AgentBackendKind.CLAUDE_PC else AgentBackendKind.LUMI
    var kind by remember { mutableStateOf(startKind) }
    var name by remember { mutableStateOf(agent?.name ?: startKind.defaultName) }
    var color by remember { mutableStateOf(agent?.let { AgentPalette.of(it.color) } ?: startKind.defaultColor) }
    var face by remember { mutableStateOf(agent?.let { AgentFaceStyle.of(it.face) } ?: startKind.defaultFace) }
    var purpose by remember { mutableStateOf(agent?.purpose.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.elevated).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentFace(face, color, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(name.ifBlank { kind.defaultName }, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            }
            if (agent == null) {
                Text(stringResource(R.string.orbit_agent_backend), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                AgentBackendKind.entries.forEach { k ->
                    val on = k == kind
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.accentContainer else c.muted)
                        .clickable {
                            // Name, face and colour follow the backend until the user changes them
                            if (name == kind.defaultName) name = k.defaultName
                            if (color == kind.defaultColor) color = k.defaultColor
                            if (face == kind.defaultFace) face = k.defaultFace
                            kind = k
                        }.padding(12.dp)) {
                        Column {
                            Text(backendLabel(k), style = MaterialTheme.typography.bodyLarge, color = if (on) c.accentText else c.textPrimary)
                            if (k == AgentBackendKind.CLAUDE_PC && !hubConfigured) Text(stringResource(R.string.orbit_needs_hub), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                }
            }
            Field(name, { name = it }, stringResource(R.string.orbit_agent_name))
            Text(stringResource(R.string.orbit_agent_color), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentPalette.entries.forEach { p ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (p == color) c.textPrimary else c.background).padding(3.dp)
                        .clip(CircleShape).background(p.color(c.isDark)).clickable { color = p })
                }
            }
            Text(stringResource(R.string.orbit_agent_face), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentFaceStyle.entries.forEach { f ->
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (f == face) c.accentContainer else c.muted).clickable { face = f }.padding(8.dp)) {
                        AgentFace(f, color, size = 30.dp)
                    }
                }
            }
            Field(purpose, { purpose = it }, stringResource(R.string.orbit_agent_purpose))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(stringResource(R.string.save), enabled = name.isNotBlank()) { onSave(kind, name, color, face, purpose) }
                PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST, onClick = onDismiss)
                if (agent != null) {
                    Spacer(Modifier.weight(1f))
                    if (confirmDelete) Text(stringResource(R.string.chat_delete_confirm), color = c.danger, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDelete).padding(8.dp))
                    else IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), tint = c.textTertiary) }
                }
            }
        }
    }
}

// ── Thread ───────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OrbitThread(vm: OrbitViewModel, onBack: () -> Unit) {
    val c = Lumi.colors
    val session by vm.session.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val byId = remember(agents) { agents.associateBy { it.id } }
    val isInbox = session?.kind == ChatSessionEntity.KIND_HUB
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.textPrimary) }
            Column(Modifier.weight(1f)) {
                Text(session?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!isInbox) Text(
                    when (members.size) {
                        0 -> "Lumi"
                        1 -> backendLabel(AgentBackendKind.of(members[0].backend)) // a direct chat: Lumi doesn't answer here
                        else -> (listOf("Lumi") + members.map { it.name }).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.labelMedium, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (!isInbox) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.GroupAdd, stringResource(R.string.orbit_members), tint = c.textSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    agents.forEach { a ->
                        val inside = members.any { it.id == a.id }
                        DropdownMenuItem(
                            text = { Text(stringResource(if (inside) R.string.orbit_remove_member else R.string.orbit_add_member, a.name)) },
                            leadingIcon = { AgentFace(AgentFaceStyle.of(a.face), AgentPalette.of(a.color), size = 22.dp) },
                            onClick = { menu = false; if (inside) vm.removeMember(a.id) else vm.addMember(a.id) }
                        )
                    }
                }
            }
            session?.let { s ->
                if (confirmDelete) Text(stringResource(R.string.chat_delete_confirm), color = c.danger, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.deleteThread(s.id) }.padding(8.dp))
                else IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), tint = c.textTertiary) }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (messages.isEmpty()) item {
                Text(
                    when {
                        isInbox -> stringResource(R.string.orbit_inbox_empty)
                        members.size == 1 -> stringResource(R.string.orbit_empty_single, members[0].name)
                        else -> stringResource(R.string.orbit_empty_thread)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            items(messages, key = { it.id }) { m -> MessageItem(m, byId[m.agentId], members, vm) }
        }

        val openAsk = isInbox && messages.any { OrbitUi.openAsk(it) }
        if (!isInbox && members.size > 1) LazyRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(members, key = { it.id }) { a ->
                Row(Modifier.clip(RoundedCornerShape(50)).background(c.muted).clickable { input = "@${a.name} " + input.removePrefix("@${a.name} ") }
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentFace(AgentFaceStyle.of(a.face), AgentPalette.of(a.color), size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("@${a.name}", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                }
            }
        }
        val enabled = !isInbox || openAsk
        Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(c.surface).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (input.isEmpty()) Text(
                    when {
                        isInbox -> stringResource(if (openAsk) R.string.orbit_input_hint_inbox else R.string.orbit_inbox_no_question)
                        members.size == 1 -> stringResource(R.string.orbit_input_hint_single, members[0].name)
                        else -> stringResource(R.string.orbit_input_hint)
                    },
                    style = MaterialTheme.typography.bodyLarge, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                BasicTextField(input, { input = it }, enabled = enabled, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accentText), maxLines = 5, modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = { vm.send(input); input = "" }, enabled = enabled && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.send), tint = if (input.isNotBlank()) c.accentText else c.textTertiary)
            }
        }
    }
}

@Composable
private fun MessageItem(m: ChatMessageEntity, agent: AgentEntity?, members: List<AgentEntity>, vm: OrbitViewModel) {
    val c = Lumi.colors
    val context = LocalContext.current
    if (m.role == ChatMessageEntity.ROLE_USER) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = c.onAccent,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(c.accent).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        return
    }
    val p = OrbitUi.payload(m)
    var menu by remember { mutableStateOf(false) }
    val others = members.filter { it.id != m.agentId }
    Row(Modifier.fillMaxWidth()) {
        if (m.role == ChatMessageEntity.ROLE_ASSISTANT) LumiMark(if (OrbitUi.streaming(m)) LumiState.THINKING else LumiState.IDLE, size = 28.dp)
        else AgentFace(AgentFaceStyle.of(agent?.face ?: AgentFaceStyle.RING.name), AgentPalette.of(agent?.color ?: AgentPalette.TEAL.name), size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val name = when {
                m.role == ChatMessageEntity.ROLE_ASSISTANT -> "Lumi"
                agent != null -> agent.name
                else -> m.engine.ifBlank { "Agent" }
            }
            Text(name, style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            Spacer(Modifier.height(2.dp))
            Box {
                Text(
                    if (OrbitUi.pending(m)) stringResource(R.string.orbit_thinking) else m.text,
                    style = MaterialTheme.typography.bodyLarge, color = if (m.isError) c.danger else if (OrbitUi.pending(m)) c.textTertiary else c.textPrimary,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { if (others.isNotEmpty() && !OrbitUi.streaming(m)) menu = true })
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    others.forEach { a ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.orbit_second_opinion, a.name)) },
                            leadingIcon = { AgentFace(AgentFaceStyle.of(a.face), AgentPalette.of(a.color), size = 22.dp) },
                            onClick = { menu = false; vm.secondOpinion(m, a.id) })
                    }
                }
            }
            p?.link?.takeIf { HubSafety.isSafeLink(it) }?.let { link ->
                Text(link, style = MaterialTheme.typography.bodyMedium, color = c.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
            }
            when {
                p?.hub == HubEvent.ASK && p.state == HubPayload.STATE_OPEN -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.options.forEach { o -> PillButton(o, style = PillStyle.SECONDARY) { vm.answer(m.id, o) } }
                }
                p?.hub == HubEvent.ASK -> StateLine(if (p.state == HubPayload.STATE_ANSWERED && p.answer != null) stringResource(R.string.orbit_answered, p.answer) else stringResource(R.string.orbit_closed))
                p?.hub == HubEvent.TASK && p.state == HubPayload.STATE_OPEN -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.hub_task_add)) { vm.acceptTask(m.id) }
                    PillButton(stringResource(R.string.hub_task_dismiss), style = PillStyle.GHOST) { vm.dismissTask(m.id) }
                }
                p?.hub == HubEvent.TASK -> StateLine(stringResource(if (p.state == HubPayload.STATE_ADDED) R.string.orbit_task_added_state else R.string.orbit_task_dismissed))
            }
            if (m.engine.isNotBlank() && m.role == ChatMessageEntity.ROLE_ASSISTANT) Text(m.engine, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

@Composable
private fun StateLine(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = Lumi.colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
