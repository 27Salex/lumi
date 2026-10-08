package io.github.salex27.lumi.presentation.assistant

/**
 * The same assistant as [AssistantActivity] but NOT exported: only Lumi's own notifications, widgets, tiles and
 * screens start it, so it is the only one that honours the internal extras (phone action to run, prompt, speak,
 * wake-word data). The exported activity ignores them: any app could send them.
 */
class InternalAssistantActivity : AssistantActivity() {
    override val trusted: Boolean = true
}
