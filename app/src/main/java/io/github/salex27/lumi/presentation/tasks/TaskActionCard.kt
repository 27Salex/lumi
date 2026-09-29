package io.github.salex27.lumi.presentation.tasks
import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource
import io.github.salex27.lumi.presentation.components.uiLabel

import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.places.PlacesStore
import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.presentation.agent.ActionPreview
import io.github.salex27.lumi.presentation.assistant.AssistantActivity
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.theme.Lumi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "When I remind you: WhatsApp to Roberto Pérez · «I just got home»" with "Try now". Shows which contact the task is
 * linked to and warns if something would stop it from working (contact not found, place not saved).
 */
@Composable
fun TaskActionCard(title: String, placeTrigger: PlaceTrigger?, places: List<PlacesStore.SavedPlace>) {
    val c = Lumi.colors
    val context = LocalContext.current
    val app = context.applicationContext as TaskManagerApplication
    var preview by remember { mutableStateOf<ActionPreview.Preview?>(null) }
    LaunchedEffect(title, placeTrigger) {
        kotlinx.coroutines.delay(300) // mientras escribes el título
        preview = withContext(Dispatchers.IO) { ActionPreview.resolve(context, app.contactAliases, Task(title = title, placeTrigger = placeTrigger)) }
    }
    val p = preview ?: return
    val placeMissing = placeTrigger?.takeIf { !it.isAdHoc && places.none { s -> s.key == it.place } }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.accentContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text((placeTrigger?.uiLabel ?: stringResource(R.string.action_card_when_reminded)).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.accentText)
        val cmd = p.command
        Text(
            when (cmd) {
                is DeviceCommand.Call -> stringResource(R.string.action_card_call, p.contactName ?: cmd.contact)
                is DeviceCommand.Message -> stringResource(if (cmd.whatsapp) R.string.action_card_whatsapp else R.string.action_card_sms, p.contactName ?: cmd.contact)
                else -> p.action.label
            },
            style = MaterialTheme.typography.titleMedium, color = c.textPrimary
        )
        p.number?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        (cmd as? DeviceCommand.Message)?.text?.takeIf { it.isNotBlank() }?.let {
            Text("«$it»", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        }
        p.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
        // If the place isn't saved, the place editor right above already says so; here only the consequence is recalled
        placeMissing?.let {
            Text(stringResource(R.string.action_card_place_unsaved, it.place), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Text(stringResource(R.string.action_card_note),
            style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Row(Modifier.padding(top = 4.dp)) {
            PillButton(stringResource(R.string.action_try_now), style = PillStyle.GHOST) {
                context.startActivity(
                    AssistantActivity.intent(context, compact = true).putExtra(AssistantActivity.EXTRA_DEVICE, cmd.serialize())
                )
            }
        }
    }
}
