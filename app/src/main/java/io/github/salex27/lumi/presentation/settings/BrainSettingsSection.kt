package io.github.salex27.lumi.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.data.ai.ApiFormats
import io.github.salex27.lumi.domain.ai.BrainChoice
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.components.StatusText
import io.github.salex27.lumi.presentation.theme.Lumi
import kotlinx.coroutines.launch

/** Settings → Lumi's brain (#2): which engine leads, the API providers' keys and models, and a connection test. */
@Composable
fun BrainSettingsSection() {
    val c = Lumi.colors
    val app = LocalContext.current.applicationContext as TaskManagerApplication
    val config by app.brainSettings.config.collectAsState()
    val active by app.assistant.activeEngine.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember(config) { mutableStateOf(config) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val okText = stringResource(R.string.brain_test_ok)

    SectionHeader(stringResource(R.string.brain_title), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.brain_sub), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
    ListGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrainChoice.entries.forEach { choice ->
                val on = choice == config.choice
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.accentContainer else c.muted)
                        .clickable { result = null; app.brainSettings.save(config.copy(choice = choice)) }.padding(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label(choice), style = MaterialTheme.typography.bodyLarge, color = if (on) c.accentText else c.textPrimary)
                        Text(speed(choice.speed), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
            StatusText(stringResource(R.string.brain_active, active), c.success)

            when (config.choice) {
                BrainChoice.ANTHROPIC -> {
                    Field(draft.anthropicKey, { draft = draft.copy(anthropicKey = it) }, stringResource(R.string.brain_anthropic_key), secret = true)
                    Field(draft.anthropicInterpretModel, { draft = draft.copy(anthropicInterpretModel = it) }, stringResource(R.string.brain_model_interpret))
                    Field(draft.anthropicReplyModel, { draft = draft.copy(anthropicReplyModel = it) }, stringResource(R.string.brain_model_reply))
                    Text(stringResource(R.string.brain_anthropic_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                BrainChoice.OPENAI -> {
                    Field(draft.openAiKey, { draft = draft.copy(openAiKey = it) }, stringResource(R.string.brain_openai_key), secret = true)
                    Field(draft.openAiModel, { draft = draft.copy(openAiModel = it) }, stringResource(R.string.brain_model))
                }
                BrainChoice.OPENAI_COMPATIBLE -> {
                    Field(draft.compatibleBaseUrl, { draft = draft.copy(compatibleBaseUrl = it) }, stringResource(R.string.brain_compatible_url))
                    if (draft.compatibleBaseUrl.isNotBlank() && ApiFormats.validBaseUrl(draft.compatibleBaseUrl) == null)
                        StatusText(stringResource(R.string.brain_compatible_url_invalid), c.danger)
                    Field(draft.compatibleKey, { draft = draft.copy(compatibleKey = it) }, stringResource(R.string.brain_compatible_key), secret = true)
                    Field(draft.compatibleModel, { draft = draft.copy(compatibleModel = it) }, stringResource(R.string.brain_compatible_model))
                }
                BrainChoice.PC_CLAUDE -> Text(stringResource(R.string.brain_pc_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                BrainChoice.GEMINI_CLOUD -> Text(stringResource(R.string.brain_gemini_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                else -> Unit
            }
            val apiChoice = config.choice in setOf(BrainChoice.ANTHROPIC, BrainChoice.OPENAI, BrainChoice.OPENAI_COMPATIBLE)
            if (apiChoice) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft != config) PillButton(stringResource(R.string.save)) { result = null; app.brainSettings.save(draft) }
                PillButton(stringResource(R.string.hub_test), style = PillStyle.SECONDARY, enabled = draft == config && !testing) {
                    testing = true
                    scope.launch {
                        val engine = when (config.choice) {
                            BrainChoice.ANTHROPIC -> app.anthropicEngine
                            BrainChoice.OPENAI -> app.openAiEngine
                            else -> app.compatibleEngine
                        }
                        val error = engine.testConnection()
                        result = (error ?: okText.format(engine.displayName)) to (error == null)
                        testing = false
                    }
                }
            }
            result?.let { (text, ok) -> StatusText(text, if (ok) c.success else c.danger) }
            Text(stringResource(R.string.brain_keys_note), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}

@Composable
private fun label(choice: BrainChoice) = stringResource(when (choice) {
    BrainChoice.AUTO -> R.string.brain_auto
    BrainChoice.GEMMA -> R.string.brain_gemma
    BrainChoice.GEMINI_CLOUD -> R.string.brain_gemini
    BrainChoice.ANTHROPIC -> R.string.brain_anthropic
    BrainChoice.OPENAI -> R.string.brain_openai
    BrainChoice.OPENAI_COMPATIBLE -> R.string.brain_compatible
    BrainChoice.PC_CLAUDE -> R.string.brain_pc
})

@Composable
private fun speed(s: BrainChoice.Speed) = stringResource(when (s) {
    BrainChoice.Speed.VARIES -> R.string.brain_speed_varies
    BrainChoice.Speed.ON_DEVICE -> R.string.brain_speed_device
    BrainChoice.Speed.FAST -> R.string.brain_speed_fast
    BrainChoice.Speed.DEPENDS_ON_SERVER -> R.string.brain_speed_server
})
