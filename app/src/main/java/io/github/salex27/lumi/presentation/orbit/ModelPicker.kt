package io.github.salex27.lumi.presentation.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.hub.AgentOptions
import io.github.salex27.lumi.data.hub.ModelChoice
import io.github.salex27.lumi.presentation.theme.Lumi

/** Localized name of a reasoning level the hub reports; unknown ids are shown as written. */
@Composable
internal fun effortLabel(id: String): String = when (id) {
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Model and reasoning-level rows. A null choice means "the default" (the thread uses the Settings default, which uses the hub's). */
@Composable
internal fun ModelEffortPanel(options: AgentOptions, choice: ModelChoice, defaultLabel: String, onChange: (ModelChoice) -> Unit) {
    val c = Lumi.colors
    @Composable
    fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) c.accentContainer else c.muted).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (on) c.accentText else c.textPrimary, modifier = Modifier.weight(1f))
            if (on) Icon(Icons.Outlined.Check, null, tint = c.accentText, modifier = Modifier.size(18.dp))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (options.models.isNotEmpty()) {
            Text(stringResource(R.string.model_model), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Choice(defaultLabel, choice.model == null) { onChange(choice.copy(model = null)) }
            options.models.forEach { m -> Choice(m.label, choice.model == m.id) { onChange(choice.copy(model = m.id)) } }
        }
        if (options.efforts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.model_effort), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Choice(defaultLabel, choice.effort == null) { onChange(choice.copy(effort = null)) }
            options.efforts.forEach { e -> Choice(effortLabel(e), choice.effort == e) { onChange(choice.copy(effort = e)) } }
        }
    }
}

/**
 * The model + reasoning chip of a Claude chat (header). Hidden when the hub is older and has no options; the choice is
 * kept per thread and sent with each turn. Tap opens the picker.
 */
@Composable
internal fun ModelChip(sessionId: Long) {
    val c = Lumi.colors
    val app = LocalContext.current.applicationContext as TaskManagerApplication
    val choices = app.agentChoices
    val options by choices.options.collectAsState()
    val default by choices.default.collectAsState()
    val version by choices.version.collectAsState()
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) { choices.refresh() }
    val opts = options ?: return
    if (opts.models.isEmpty() && opts.efforts.isEmpty()) return
    val thread = remember(sessionId, version) { choices.thread(sessionId) }.validated(opts)
    val shownModel = thread.model ?: default.validated(opts).model ?: opts.defaultModel
    val shownEffort = thread.effort ?: default.validated(opts).effort ?: opts.defaultEffort
    val label = listOfNotNull(shownModel?.let { id -> opts.models.firstOrNull { it.id == id }?.label ?: id }, shownEffort?.let { effortLabel(it) })
        .joinToString(" · ").ifBlank { stringResource(R.string.model_title) }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(50)).background(c.muted).clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Psychology, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.textSecondary, maxLines = 1)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        containerColor = c.surface,
        title = { Text(stringResource(R.string.model_title), color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModelEffortPanel(opts, thread, stringResource(R.string.model_default)) { choices.setThread(sessionId, it) }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.model_done), color = c.accentText) } }
    )
}
