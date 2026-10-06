package io.github.salex27.lumi.presentation.assistant

import io.github.salex27.lumi.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.presentation.components.EngineBadge
import io.github.salex27.lumi.presentation.components.LumiMark
import io.github.salex27.lumi.presentation.components.LumiState
import io.github.salex27.lumi.presentation.components.PillButton
import io.github.salex27.lumi.presentation.components.PillStyle
import io.github.salex27.lumi.presentation.components.TopEdgeGlow
import io.github.salex27.lumi.presentation.components.TaskMiniChip
import io.github.salex27.lumi.presentation.components.TypewriterText
import io.github.salex27.lumi.presentation.theme.Lumi

/**
 * Lumi's assistant.
 * - Compact (outside the app: side button, "Oye Lumi", widget, tile): a floating Siri/Gemini-style pill, voice first.
 *   Tapping the logo switches to typing. The reply appears in a card above it; tapping it opens the full conversation.
 * - Full (from the app's bar): a conversation panel.
 */
@Composable
fun AssistantScreen(
    state: AssistantUiState,
    activeEngine: String,
    startCompact: Boolean,
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    liveTranscript: String,
    micAvailable: Boolean,
    onSend: (String) -> Unit,
    onPlanDay: () -> Unit,
    onBriefing: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onRevealed: (Long) -> Unit,
    onConfirmPending: () -> Unit,
    onDiscardPending: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onPickOption: (Int) -> Unit = {},
    onPickNone: () -> Unit = {},
    sessionActions: SessionActions = SessionActions()
) {
    var compact by remember { mutableStateOf(startCompact) }
    val lastAssistantAnimating = state.messages.lastOrNull()?.let { it is ChatMessage.Assistant && it.id !in state.revealed } == true
    val completedSomething = (state.messages.lastOrNull() as? ChatMessage.Assistant)?.tasks?.any { it.status == TaskStatus.COMPLETED } == true
    val lumiState = when {
        isListening -> LumiState.LISTENING
        state.isThinking -> LumiState.THINKING
        isSpeaking || lastAssistantAnimating -> LumiState.SPEAKING
        completedSomething -> LumiState.SUCCESS
        else -> LumiState.IDLE
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (compact) 0.28f else 0.45f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    if (!state.isThinking && !isListening) onDismiss()
                }
        )
        if (compact) TopEdgeGlow(lumiState)

        AnimatedContent(targetState = compact, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "mode") { isCompact ->
            if (isCompact) {
                CompactAssistant(
                    state, lumiState, isListening, audioLevel, liveTranscript, micAvailable,
                    onSend, onStartVoice, onStopVoice, onRevealed, onConfirmPending, onDiscardPending, onDismiss, onPickOption, onPickNone, onExpand = { compact = false }
                )
            } else {
                FullAssistant(
                    state, lumiState, activeEngine, isListening, liveTranscript, micAvailable,
                    onSend, onPlanDay, onBriefing, onStartVoice, onStopVoice, onRevealed, onOpenApp, onDismiss, onPickOption, onPickNone,
                    sessionActions
                )
            }
        }
    }
}

// ── Compact mode (Siri/Gemini) ────────────────────────────────────────────

@Composable
private fun CompactAssistant(
    state: AssistantUiState,
    lumiState: LumiState,
    isListening: Boolean,
    audioLevel: Float,
    liveTranscript: String,
    micAvailable: Boolean,
    onSend: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onRevealed: (Long) -> Unit,
    onConfirmPending: () -> Unit,
    onDiscardPending: () -> Unit,
    onDismiss: () -> Unit,
    onPickOption: (Int) -> Unit,
    onPickNone: () -> Unit,
    onExpand: () -> Unit
) {
    val c = Lumi.colors
    var typing by remember { mutableStateOf(!micAvailable) }
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Only replies from this opening: not the greeting nor the history of a resumed chat
    val lastReply = state.messages.lastOrNull { it is ChatMessage.Assistant && it.id >= LIVE_ID_BASE } as? ChatMessage.Assistant

    LaunchedEffect(typing) { if (typing) { runCatching { focus.requestFocus() }; keyboard?.show() } }

    // Entrance: the pill drops from the top (like a notification / Dynamic Island)
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    // Swiping the pill up closes it
    var dragY by remember { mutableFloatStateOf(0f) }
    val dismissPx = with(LocalDensity.current) { 64.dp.toPx() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            appear,
            enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = 380f)) { -it * 2 } + fadeIn(tween(180))
        ) {
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer { translationY = dragY; alpha = 1f - (-dragY / (dismissPx * 2)).coerceIn(0f, 0.6f) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dragY < -dismissPx) onDismiss() else dragY = 0f },
                            onDragCancel = { dragY = 0f }
                        ) { _, delta -> dragY = (dragY + delta).coerceAtMost(0f) }
                    }
                    .shadow(28.dp, RoundedCornerShape(32.dp))
                    .clip(RoundedCornerShape(32.dp)).background(c.elevated).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactPillContent(
                    state, lumiState, isListening, audioLevel, liveTranscript, micAvailable,
                    typing, input, focus, onTypingChange = { typing = it }, onInputChange = { input = it },
                    onSend, onStartVoice, onStopVoice, hideKeyboard = { keyboard?.hide() }
                )
            }
        }

        // Confirmation: Lumi opened on its own and what it heard doesn't sound like a command
        AnimatedVisibility(state.pendingConfirmation != null, enter = fadeIn() + slideInVertically { -it / 3 }) {
            state.pendingConfirmation?.let { heard ->
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
                        .shadow(24.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(c.elevated).padding(18.dp)
                ) {
                    Text(stringResource(R.string.confirm_heard, heard), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.confirm_yes), onClick = onConfirmPending)
                        PillButton(stringResource(R.string.no), style = PillStyle.SECONDARY, onClick = onDiscardPending)
                    }
                }
            }
        }

        // The reply appears under the pill; tapping it opens the full conversation
        AnimatedVisibility(lastReply != null && state.pendingConfirmation == null, enter = fadeIn() + slideInVertically { -it / 3 }) {
            lastReply?.let { msg ->
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
                        .shadow(24.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(c.elevated)
                        .clickable(onClick = onExpand).padding(18.dp)
                ) {
                    TypewriterText(msg.text, style = MaterialTheme.typography.bodyLarge, color = if (msg.isError) c.danger else c.textPrimary,
                        animate = msg.id !in state.revealed, onFinished = { onRevealed(msg.id) })
                    msg.tasks.take(3).forEach { Spacer(Modifier.size(6.dp)); TaskMiniChip(it) }
                    if (state.optionLabels.isNotEmpty()) OptionList(state.optionLabels, onPickOption, onPickNone)
                }
            }
        }
    }
}

/** Inside of the compact pill: logo (voice ↔ typing) + status text or text field. */
@Composable
private fun RowScope.CompactPillContent(
    state: AssistantUiState,
    lumiState: LumiState,
    isListening: Boolean,
    audioLevel: Float,
    liveTranscript: String,
    micAvailable: Boolean,
    typing: Boolean,
    input: String,
    focus: FocusRequester,
    onTypingChange: (Boolean) -> Unit,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    hideKeyboard: () -> Unit
) {
    val c = Lumi.colors
    // The logo: in voice mode, tapping it switches to typing; while typing, tapping it goes back to voice
    Box(
        Modifier.size(52.dp).clip(CircleShape).clickable {
            if (typing && micAvailable) { onTypingChange(false); hideKeyboard(); onStartVoice() }
            else { onStopVoice(); onTypingChange(true) }
        },
        contentAlignment = Alignment.Center
    ) { LumiMark(lumiState, size = if (typing) 34.dp else 46.dp, level = audioLevel) }
    Spacer(Modifier.width(8.dp))
    if (typing) {
        Box(Modifier.weight(1f)) {
            if (input.isEmpty()) Text(stringResource(R.string.compact_type_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary)
            BasicTextField(
                value = input, onValueChange = onInputChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { onSend(input.trim()); onInputChange("") } }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
        if (input.isNotBlank()) SendButton { onSend(input.trim()); onInputChange("") }
    } else {
        Text(
            when {
                isListening && liveTranscript.isNotBlank() -> liveTranscript
                isListening -> stringResource(R.string.voice_listening)
                state.isThinking -> stringResource(if (state.searching) R.string.searching_web else R.string.thinking)
                state.voiceError != null -> stringResource(R.string.voice_error_retry, state.voiceError)
                else -> stringResource(R.string.compact_idle_hint)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                isListening && liveTranscript.isNotBlank() -> c.textPrimary
                state.voiceError != null && !isListening -> c.danger
                else -> c.textSecondary
            },
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(enabled = !isListening && !state.isThinking) { onStartVoice() }
        )
    }
}

/** Options for "Did you mean…?" / "Which one?": tapped or answered with "the second one", "yes", "no". */
@Composable
private fun OptionList(labels: List<String>, onPick: (Int) -> Unit, onNone: () -> Unit) {
    val c = Lumi.colors
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentContainer).clickable { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.accentText)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(stringResource(R.string.none_of_them), style = MaterialTheme.typography.labelLarge, color = c.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onNone).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

// ── Full mode (conversation) ──────────────────────────────────────────────

@Composable
private fun FullAssistant(
    state: AssistantUiState,
    lumiState: LumiState,
    activeEngine: String,
    isListening: Boolean,
    liveTranscript: String,
    micAvailable: Boolean,
    onSend: (String) -> Unit,
    onPlanDay: () -> Unit,
    onBriefing: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onRevealed: (Long) -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onPickOption: (Int) -> Unit,
    onPickNone: () -> Unit,
    sessionActions: SessionActions
) {
    val c = Lumi.colors
    var input by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(liveTranscript) { if (liveTranscript.isNotBlank()) input = liveTranscript }
    LaunchedEffect(state.messages.size, state.isThinking) {
        val count = state.messages.size + if (state.isThinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    fun submit() { if (input.isNotBlank()) { onSend(input.trim()); input = ""; keyboard?.hide() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.Bottom) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.background)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LumiMark(lumiState, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val title = state.sessions.firstOrNull { it.id == state.sessionId }?.title ?: state.sessionTitle
                    Text(title.ifBlank { "Lumi" }, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(activeEngine, style = MaterialTheme.typography.labelMedium, color = c.textTertiary, maxLines = 1)
                }
                IconButton(onClick = { showSessions = !showSessions }) {
                    Icon(Icons.Outlined.History, stringResource(R.string.chat_history), tint = if (showSessions) c.accentText else c.textSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { showSessions = false; sessionActions.onNew() }, enabled = !state.isThinking) {
                    Icon(Icons.Outlined.AddComment, stringResource(R.string.chat_new), tint = c.textSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenApp) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.open_app), tint = c.textSecondary, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = c.textSecondary) }
            }

            if (showSessions) {
                SessionList(state.sessions, state.sessionId, Modifier.weight(1f, fill = false).padding(top = 12.dp),
                    onOpen = { showSessions = false; sessionActions.onOpen(it) },
                    onRename = sessionActions.onRename, onDelete = sessionActions.onDelete)
                return@Column
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.messages, key = { it.id }) { msg ->
                    when (msg) {
                        is ChatMessage.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                msg.text, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary,
                                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                                    .background(c.accentContainer).padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                        is ChatMessage.Assistant -> {
                            var finished by remember(msg.id) { mutableStateOf(msg.id in state.revealed) }
                            Column {
                                TypewriterText(msg.text, style = MaterialTheme.typography.bodyLarge, color = if (msg.isError) c.danger else c.textPrimary,
                                    animate = msg.id !in state.revealed, onFinished = { finished = true; onRevealed(msg.id) })
                                AnimatedVisibility(finished && (msg.tasks.isNotEmpty() || msg.engine.isNotBlank() || msg.sources.isNotEmpty() || msg.places.isNotEmpty())) {
                                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        msg.tasks.take(6).forEach { TaskMiniChip(it) }
                                        if (msg.sources.isNotEmpty()) SourceLinks(msg.sources)
                                        if (msg.places.isNotEmpty()) NearbyList(msg.places)
                                        EngineBadge(msg.engine)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.optionLabels.isNotEmpty() && !state.isThinking) item(key = "options") {
                    OptionList(state.optionLabels, onPickOption, onPickNone)
                }
                if (state.isThinking) item(key = "thinking") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LumiMark(LumiState.THINKING, size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.searching) R.string.searching_web else R.string.thinking), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
            }

            AnimatedVisibility(!state.isThinking && !isListening) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Suggestion(stringResource(R.string.prompt_what_now), onPlanDay)
                    Suggestion(stringResource(R.string.suggest_how_am_i), onBriefing)
                    val remindPrefix = stringResource(R.string.suggest_remind_me_prefix)
                    Suggestion(stringResource(R.string.suggest_remind_me)) { input = remindPrefix }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(26.dp)).background(c.surface)
                    .border(1.dp, if (isListening) c.accent else c.outline, RoundedCornerShape(26.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(enabled = micAvailable) { if (isListening) onStopVoice() else onStartVoice() },
                    contentAlignment = Alignment.Center
                ) { LumiMark(if (isListening) LumiState.LISTENING else LumiState.IDLE, size = 30.dp) }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text(
                        when {
                            isListening -> stringResource(R.string.voice_listening)
                            state.voiceError != null -> state.voiceError
                            else -> stringResource(R.string.full_input_hint)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.voiceError != null && !isListening) c.danger else c.textTertiary, maxLines = 2
                    )
                    BasicTextField(
                        value = input, onValueChange = { input = it }, maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accentText),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (input.isNotBlank()) SendButton(::submit)
            }
        }
    }
}

@Composable
private fun SendButton(onClick: () -> Unit) {
    val c = Lumi.colors
    Box(Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).background(c.accent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.cd_send), tint = c.onAccent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    val c = Lumi.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = c.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.muted).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

/** Web sources of an answer (#7): numbered links that open in the browser (http(s) only, checked on the way in). */
@Composable
private fun SourceLinks(sources: List<io.github.salex27.lumi.domain.search.WebHit>) {
    val c = Lumi.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.web_sources), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        sources.forEachIndexed { i, hit ->
            Text(
                "[${i + 1}] ${hit.title} · ${io.github.salex27.lumi.domain.search.WebAnswers.host(hit.url)}",
                style = MaterialTheme.typography.bodySmall, color = c.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(hit.url))
                                .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }.padding(vertical = 2.dp)
            )
        }
    }
}

/** Nearby places (OpenStreetMap, untrusted text): name, distance, open 24 h; a tap opens the place in the maps app. */
@Composable
private fun NearbyList(places: List<io.github.salex27.lumi.domain.places.NearbyPlace>) {
    val c = Lumi.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val open24 = stringResource(R.string.nearby_open_24h)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        places.forEach { p ->
            val detail = listOfNotNull(p.distanceLabel, p.kind?.replace('_', ' '), open24.takeIf { p.open == true }).joinToString(" · ")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.muted).clickable {
                    // Only numbers go into the URI; the name is a label (URL-encoded), never executed
                    val label = android.net.Uri.encode(p.name)
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}($label)"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
