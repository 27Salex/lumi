package io.github.salex27.lumi.service.wakeword

/** Where the phone's media output goes right now (decides how much of it the microphone hears). */
enum class OutputRoute {
    /** The phone's own speaker: the microphone hears everything (series, music). */
    SPEAKER,
    /** Wired/USB headphones: the microphone barely hears the playback. */
    HEADPHONES,
    /** Bluetooth A2DP/LE audio or SCO: usually the car (loud cabin speakers, phone AEC can't subtract them well). */
    BLUETOOTH
}

/**
 * What the phone is doing with audio while a wake window is scored.
 * [mediaPlaying] = another app plays audio (or did a moment ago, see [WakeGate.mediaActive]); [inCall] = phone or VoIP call.
 */
data class AudioContext(val mediaPlaying: Boolean, val route: OutputRoute, val inCall: Boolean) {
    companion object {
        val QUIET = AudioContext(mediaPlaying = false, route = OutputRoute.SPEAKER, inCall = false)
    }
}

/**
 * How the detector behaves in a given [AudioContext]. [listen] = false pauses detection (calls); [threshold]/[patience]
 * feed [WakeTrigger]; [alwaysConfirm] = the assistant asks "Should I note it down?" even for a clear command;
 * [raised] = the threshold is above the user's sensitivity (diagnostics: a wake ignored because of other audio).
 */
data class WakePolicy(
    val listen: Boolean,
    val threshold: Float,
    val patience: Int,
    val alwaysConfirm: Boolean,
    val raised: Boolean,
    val reason: String
)

/**
 * Issue #6: far fewer false wakes from audio the phone itself plays (series/music on the speaker) or from the car's
 * Bluetooth audio. Pure, tested. The model can't tell "Hey Lumi" said by the user from "Hey Lucy" said by an actor, so
 * while other audio plays the bar goes up sharply, more windows in a row are needed and the assistant always confirms.
 * In silence nothing changes (the true-wake rate measured for the sensitivities stays the same).
 */
object WakeGate {

    /** Minimum score while media plays on the speaker (true "Hey Lumi" scores 0.95-0.99 in the self-test). */
    const val MEDIA_SPEAKER_THRESHOLD = 0.80f
    /** Minimum score while media plays over Bluetooth (car speakers: louder, no echo cancellation possible). */
    const val MEDIA_BLUETOOTH_THRESHOLD = 0.85f
    /** Extra score needed with a Bluetooth route but nothing playing from the phone (car radio, idle headset). */
    const val BLUETOOTH_IDLE_BONUS = 0.10f
    /** Above the threshold but by less than this → borderline: the assistant asks for confirmation. */
    const val BORDERLINE_MARGIN = 0.15f
    /**
     * Media counts as playing until this long after it stops: the detector looks at the last ~1.3 s of audio, and
     * players report "stopped" a little before the speaker goes quiet.
     */
    const val MEDIA_HOLD_MS = 2_000L

    fun policy(sensitivity: WakePhrases.Sensitivity, ctx: AudioContext): WakePolicy =
        policy(sensitivity.detectorThreshold, sensitivity.patience, ctx)

    fun policy(baseThreshold: Float, basePatience: Int, ctx: AudioContext): WakePolicy = when {
        ctx.inCall -> WakePolicy(false, baseThreshold, basePatience, alwaysConfirm = true, raised = true, reason = "call")
        ctx.mediaPlaying && ctx.route == OutputRoute.BLUETOOTH ->
            raise(baseThreshold, MEDIA_BLUETOOTH_THRESHOLD, maxOf(basePatience, 3), "media-bluetooth")
        ctx.mediaPlaying && ctx.route == OutputRoute.SPEAKER ->
            raise(baseThreshold, MEDIA_SPEAKER_THRESHOLD, maxOf(basePatience, 2), "media-speaker")
        // Headphones: the microphone barely hears the playback; same bar, but a clear command still gets confirmed
        ctx.mediaPlaying -> WakePolicy(true, baseThreshold, basePatience, alwaysConfirm = true, raised = false, reason = "media-headphones")
        ctx.route == OutputRoute.BLUETOOTH ->
            WakePolicy(true, minOf(baseThreshold + BLUETOOTH_IDLE_BONUS, MEDIA_BLUETOOTH_THRESHOLD), basePatience,
                alwaysConfirm = false, raised = true, reason = "bluetooth")
        else -> WakePolicy(true, baseThreshold, basePatience, alwaysConfirm = false, raised = false, reason = "quiet")
    }

    private fun raise(base: Float, floor: Float, patience: Int, reason: String) =
        WakePolicy(true, maxOf(base, floor), patience, alwaysConfirm = true, raised = true, reason = reason)

    /**
     * After a wake: must the assistant confirm before running what it hears? Yes when the policy says so or the score
     * was borderline, unless Voice Match already checked that it is the user's voice ([voiceMatched]).
     */
    fun needsConfirmation(score: Float, policy: WakePolicy, voiceMatched: Boolean): Boolean =
        !voiceMatched && (policy.alwaysConfirm || score < policy.threshold + BORDERLINE_MARGIN)

    /** A score the user's own sensitivity would have accepted but the raised [policy] rejected (diagnostics). */
    fun suppressedByAudio(score: Float?, baseThreshold: Float, policy: WakePolicy): Boolean =
        score != null && policy.raised && score >= baseThreshold && score < policy.threshold

    /** Is media playing, counting [MEDIA_HOLD_MS] after it was last seen ([lastActiveAt], elapsed ms; 0 = never)? */
    fun mediaActive(activeNow: Boolean, lastActiveAt: Long, now: Long): Boolean =
        activeNow || (lastActiveAt > 0L && now - lastActiveAt < MEDIA_HOLD_MS)
}
