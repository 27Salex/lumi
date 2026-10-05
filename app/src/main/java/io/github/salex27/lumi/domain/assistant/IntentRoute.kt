package io.github.salex27.lumi.domain.assistant

/** Top-level kind of request, decided before the detailed parsing (see data/ai/IntentRouter). */
enum class IntentRoute { TASK, AGENT, QUESTION, OPINION, DEVICE, UNSURE }
