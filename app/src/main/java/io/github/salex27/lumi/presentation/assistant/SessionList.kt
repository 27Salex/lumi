package io.github.salex27.lumi.presentation.assistant

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.presentation.components.uiLocale
import io.github.salex27.lumi.presentation.theme.Lumi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the session list can do (defaults do nothing: previews, screens without sessions). */
data class SessionActions(
    val onNew: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onRename: (Long, String) -> Unit = { _, _ -> },
    val onDelete: (Long) -> Unit = {}
)

/** Saved chats: tap to resume, pencil to rename, bin (tapped twice) to delete. */
@Composable
fun SessionList(
    sessions: List<SessionItem>,
    currentId: Long?,
    modifier: Modifier = Modifier,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val c = Lumi.colors
    if (sessions.isEmpty()) {
        Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, modifier = modifier.padding(vertical = 24.dp))
        return
    }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(sessions, key = { it.id }) { s ->
            val current = s.id == currentId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current) c.accentContainer else c.surface)
                    .clickable(enabled = renaming != s.id) { onOpen(s.id) }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    if (renaming == s.id) {
                        var draft by remember(s.id) { mutableStateOf(s.title) }
                        BasicTextField(
                            value = draft, onValueChange = { draft = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accentText),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onRename(s.id, draft); renaming = null }),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.muted).padding(10.dp)
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge, color = c.accentText,
                                modifier = Modifier.clickable { onRename(s.id, draft); renaming = null })
                            Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = c.textSecondary,
                                modifier = Modifier.clickable { renaming = null })
                        }
                    } else {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${shortDate(s.updatedAt)} · ${s.preview.replace('\n', ' ')}",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (renaming != s.id) {
                    IconButton(onClick = { renaming = s.id; confirmDelete = null }) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.chat_rename), tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    }
                    if (confirmDelete == s.id) {
                        Text(stringResource(R.string.chat_delete_confirm), style = MaterialTheme.typography.labelLarge, color = c.danger,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onDelete(s.id); confirmDelete = null }.padding(8.dp))
                    } else IconButton(onClick = { confirmDelete = s.id }) {
                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Today → "14:05"; otherwise "3 Oct". */
private fun shortDate(millis: Long): String {
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (dt.toLocalDate() == LocalDate.now()) dt.format(HM) else dt.format(DateTimeFormatter.ofPattern("d MMM", uiLocale))
}
