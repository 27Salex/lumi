package io.github.salex27.lumi.presentation.orbit

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.salex27.lumi.R
import io.github.salex27.lumi.data.local.ChatSessionEntity
import io.github.salex27.lumi.domain.chat.ChatEntry
import io.github.salex27.lumi.domain.chat.ChatHistory
import io.github.salex27.lumi.domain.chat.ChatHistory.DayLabel
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
import io.github.salex27.lumi.presentation.components.uiLocale
import io.github.salex27.lumi.presentation.settings.Field
import io.github.salex27.lumi.presentation.theme.Lumi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Chats: the one history of every conversation (Lumi chats, Orbit threads, agent inboxes), newest first and grouped
 * by day. Tap resumes: a Lumi chat opens the assistant on that session ([onOpenAssistantChat]), the rest open the
 * thread inside the app. Long-press renames or deletes. Agents live in [onManage].
 */
@Composable
fun ChatsScreen(
    vm: OrbitViewModel,
    onBack: () -> Unit,
    onManage: () -> Unit,
    /** null = a new, empty Lumi chat. */
    onOpenAssistantChat: (Long?) -> Unit
) {
    val c = Lumi.colors
    val rows by vm.chats.collectAsStateWithLifecycle()
    val hub by vm.hubConfigured.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var newChat by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatEntry?>(null) }
    var deleting by remember { mutableStateOf<ChatEntry?>(null) }
    val entries = remember(rows) {
        rows.map {
            ChatEntry(
                it.session.id, it.session.kind, it.session.title.ifBlank { "" }, ChatHistory.snippet(it.preview), it.session.updatedAt
            )
        }
    }
    val groups = remember(entries, query) { ChatHistory.group(ChatHistory.search(entries, query)) }
    val today = remember { LocalDate.now() }

    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.textPrimary) }
                Text(stringResource(R.string.chats_title), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onManage) { Icon(Icons.Outlined.Tune, stringResource(R.string.orbit_manage_title), tint = c.textPrimary) }
            }
            if (entries.isNotEmpty()) SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            when {
                entries.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    LumiMark(LumiState.IDLE, size = 56.dp)
                    Spacer(Modifier.size(16.dp))
                    Text(stringResource(R.string.chats_empty_title), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(stringResource(R.string.chats_empty_sub), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                groups.isEmpty() -> Text(stringResource(R.string.chats_no_match), style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, modifier = Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    groups.forEach { g ->
                        item(key = "h${g.day}") {
                            Text(dayTitle(g.day, today).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp))
                        }
                        item(key = "g${g.day}") {
                            ListGroup {
                                g.entries.forEachIndexed { i, e ->
                                    if (i > 0) ListDivider()
                                    ChatRow(
                                        e,
                                        onClick = { if (e.kind == ChatSessionEntity.KIND_ASSISTANT) onOpenAssistantChat(e.id) else vm.openThread(e.id) },
                                        onRename = { renaming = e },
                                        onDelete = { deleting = e }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        PillButton(
            stringResource(R.string.chat_new),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        ) { newChat = true }
    }

    if (newChat) NewChatDialog(hub, vm.agents.collectAsStateWithLifecycle().value, onDismiss = { newChat = false },
        onLumi = { newChat = false; onOpenAssistantChat(null) },
        onAgent = { newChat = false; vm.newChat(it) })
    renaming?.let { e ->
        RenameDialog(e.title, onDismiss = { renaming = null }) { vm.renameThread(e.id, it); renaming = null }
    }
    deleting?.let { e ->
        DeleteDialog(displayTitle(e), onDismiss = { deleting = null }) { vm.deleteThread(e.id); deleting = null }
    }
}

@Composable
private fun displayTitle(e: ChatEntry): String = e.title.ifBlank {
    stringResource(if (e.kind == ChatSessionEntity.KIND_ASSISTANT) R.string.chats_untitled else R.string.chats_untitled_orbit)
}

@Composable
private fun dayTitle(day: LocalDate, today: LocalDate): String = when (ChatHistory.dayLabel(day, today)) {
    DayLabel.TODAY -> stringResource(R.string.chats_today)
    DayLabel.YESTERDAY -> stringResource(R.string.chats_yesterday)
    DayLabel.WEEKDAY_DATE -> day.format(DateTimeFormatter.ofPattern("EEEE d MMM", uiLocale))
    DayLabel.FULL_DATE -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy", uiLocale))
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(e: ChatEntry, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = Lumi.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (e.kind) {
                ChatSessionEntity.KIND_HUB -> AgentFace(AgentFaceStyle.RING, AgentPalette.TEAL, size = 32.dp)
                ChatSessionEntity.KIND_ORBIT -> AgentFace(AgentBackendKind.CLAUDE_PC.defaultFace, AgentBackendKind.CLAUDE_PC.defaultColor, size = 32.dp)
                else -> LumiMark(LumiState.IDLE, size = 32.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayTitle(e), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    e.snippet.ifBlank { stringResource(R.string.chats_no_messages) },
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Instant.ofEpochMilli(e.updatedAt).atZone(ZoneId.systemDefault()).format(HM), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(
                        when (e.kind) {
                            ChatSessionEntity.KIND_HUB -> R.string.chats_badge_hub
                            ChatSessionEntity.KIND_ORBIT -> R.string.chats_badge_orbit
                            else -> R.string.chats_badge_lumi
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall, color = c.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.muted).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_rename)) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = c.danger) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Lumi.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.chats_search), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
            BasicTextField(
                value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accentText)
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Outlined.Close, stringResource(R.string.cancel), tint = c.textTertiary, modifier = Modifier.size(20.dp).clickable { onChange("") })
    }
}

@Composable
private fun NewChatDialog(hub: Boolean, agents: List<io.github.salex27.lumi.data.local.AgentEntity>, onDismiss: () -> Unit, onLumi: () -> Unit, onAgent: (Long?) -> Unit) {
    val c = Lumi.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.elevated).padding(vertical = 16.dp)) {
            Text(stringResource(R.string.chat_new), style = MaterialTheme.typography.titleLarge, color = c.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            ListRow(
                stringResource(R.string.chats_new_lumi), stringResource(R.string.chats_new_lumi_sub), onClick = onLumi,
                leading = { LumiMark(LumiState.IDLE, size = 32.dp) }
            )
            if (agents.none { it.backend == AgentBackendKind.CLAUDE_PC.name }) ListRow(
                stringResource(R.string.orbit_chat_with_claude),
                stringResource(if (hub) R.string.orbit_chat_with_claude_sub else R.string.orbit_needs_hub),
                onClick = if (hub) ({ onAgent(null) }) else null,
                leading = { AgentFace(AgentBackendKind.CLAUDE_PC.defaultFace, AgentBackendKind.CLAUDE_PC.defaultColor) }
            )
            agents.forEach { a ->
                val kind = AgentBackendKind.of(a.backend) ?: AgentBackendKind.CLAUDE_PC
                ListRow(
                    a.name,
                    stringResource(if (hub) R.string.orbit_chat_with_claude_sub else R.string.orbit_needs_hub),
                    onClick = if (hub) ({ onAgent(a.id) }) else null,
                    leading = { AgentFace(kind.defaultFace, kind.defaultColor) }
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Lumi.colors
    var draft by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.elevated).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_rename), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Field(draft, { draft = it }, stringResource(R.string.orbit_agent_name))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.save), enabled = draft.isNotBlank()) { onSave(draft) }
                PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun DeleteDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Lumi.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(c.elevated).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chats_delete_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Text(stringResource(R.string.chats_delete_body, title), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.delete), onClick = onConfirm)
                PillButton(stringResource(R.string.cancel), style = PillStyle.GHOST, onClick = onDismiss)
            }
        }
    }
}
