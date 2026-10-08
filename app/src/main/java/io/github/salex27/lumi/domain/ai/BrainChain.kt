package io.github.salex27.lumi.domain.ai

/**
 * Lumi's brain (#2): which engine leads. The others stay behind it as fallbacks and the rules are always last.
 * [speed] is shown in the picker because voice latency matters.
 */
enum class BrainChoice(val speed: Speed) {
    /** As before: on-device first (Gemini Nano → Gemma), then the clouds that are set up. */
    AUTO(Speed.VARIES),
    GEMMA(Speed.ON_DEVICE),
    GEMINI_CLOUD(Speed.FAST),
    ANTHROPIC(Speed.FAST),
    OPENAI(Speed.FAST),
    OPENAI_COMPATIBLE(Speed.DEPENDS_ON_SERVER),
    /** Claude on the user's PC through Lumi Hub (the Hub server profile); falls back to the others when the PC is off. */
    PC_CLAUDE(Speed.DEPENDS_ON_SERVER);

    enum class Speed { VARIES, ON_DEVICE, FAST, DEPENDS_ON_SERVER }
}

/** Ids of the engines in the chain (what the app registers, in the default order). */
enum class EngineId { NANO, GEMMA, GEMINI_CLOUD, ANTHROPIC, OPENAI, OPENAI_COMPATIBLE, PC }

/** Order of the engine chain for a choice (pure, tested). */
object BrainChain {
    /** On-device first (private, free), then the clouds, then Claude on the PC (only when the others fail or are off). */
    val DEFAULT_ORDER = listOf(EngineId.NANO, EngineId.GEMMA, EngineId.GEMINI_CLOUD, EngineId.ANTHROPIC, EngineId.OPENAI, EngineId.OPENAI_COMPATIBLE, EngineId.PC)

    fun order(choice: BrainChoice): List<EngineId> {
        val lead = when (choice) {
            BrainChoice.AUTO -> return DEFAULT_ORDER
            BrainChoice.GEMMA -> EngineId.GEMMA
            BrainChoice.GEMINI_CLOUD -> EngineId.GEMINI_CLOUD
            BrainChoice.ANTHROPIC -> EngineId.ANTHROPIC
            BrainChoice.OPENAI -> EngineId.OPENAI
            BrainChoice.OPENAI_COMPATIBLE -> EngineId.OPENAI_COMPATIBLE
            BrainChoice.PC_CLAUDE -> EngineId.PC
        }
        return listOf(lead) + DEFAULT_ORDER.filter { it != lead }
    }
}
