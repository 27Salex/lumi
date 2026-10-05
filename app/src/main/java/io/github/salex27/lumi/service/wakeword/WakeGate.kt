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
 * [voiceRequired] = Voice Match is the gate: a wake only counts if the user's voice print matches (bar lowered by
 * [voiceRelax]: the user's voice is mixed with the TV/car audio); when no print can be computed, [noPrint] decides
 * instead (a higher score, and confirmation).
 */
data class WakePolicy(
    val listen: Boolean,
    val threshold: Float,
    val patience: Int,
    val alwaysConfirm: Boolean,
    val raised: Boolean,
    val reason: String,
    val voiceRequired: Boolean = false,
    val voiceRelax: Float = 0f,
    val noPrint: WakePolicy? = null
)

/** What to do with a wake the detector fired on. */
sealed interface WakeVerdict {
    /** Open the assistant; [confirm] = ask before running the first request. */
    data class Accept(val confirm: Boolean, val voiceMatched: Boolean) : WakeVerdict
    /** Voice Match ran and it isn't the user's voice. */
    data class RejectVoice(val similarity: Float, val needed: Float) : WakeVerdict
    /** Voice Match is required but no print came out, and the score isn't high enough to go on without it. */
    data object RejectNoPrint : WakeVerdict
}

/**
 * Issue #6: far fewer false wakes from audio the phone itself plays (series/music on the speaker) or from the car's
 * Bluetooth audio. Pure, tested. The detector can't tell "Hey Lumi" said by the user from "Hey Lucy" said by an actor,
 * but the voice print can (another person ~0.03 against the user's 0.69-0.97).
 *
 * - With a trained voice (Voice Match): while media plays or a Bluetooth route is active the detector keeps the
 *   user's own sensitivity and a single window, and Voice Match decides; a match skips the confirmation. A raised
 *   detector bar (0.80 x 2 windows) let through only 26 % of real "Oye Lumi" / 50 % of "Hey Lumi" (Bluetooth 0.85 x 3:
 *   7 % / 24 %), so calling Lumi over music mostly failed.
 * - Without a trained voice: a modest raise ([NO_PROFILE_MEDIA_THRESHOLD], 1 window) and the assistant always confirms.
 * In silence nothing changes (the true-wake rate measured for the sensitivities stays the same).
 */
object WakeGate {

    /** Minimum detector score while media plays (speaker or Bluetooth) and no voice is trained (= Strict). */
    const val NO_PROFILE_MEDIA_THRESHOLD = 0.50f
    /**
     * With a trained voice, while media plays, a wake whose print couldn't be computed (too little speech for Vosk)
     * needs this score in one window and is confirmed: the old media bar, since the voice can't vouch for it.
     */
    const val MEDIA_NO_PRINT_THRESHOLD = 0.80f
    /** Extra score needed with a Bluetooth route but nothing playing from the phone (car radio, idle headset). */
    const val BLUETOOTH_IDLE_BONUS = 0.10f
    /** Above the threshold but by less than this → borderline: the assistant asks for confirmation. */
    const val BORDERLINE_MARGIN = 0.15f
    /**
     * The voice similarity bar is this much lower while media plays on the speaker/Bluetooth: the user's voice is
     * mixed with the TV or car audio and scores lower. Kept small: TV voices alone stay far below it (see MEMORY.md).
     */
    const val MEDIA_VOICE_RELAX = 0.05f
    /** The voice similarity bar never goes below this, whatever the sensitivity or the spread. */
    const val MIN_VOICE_THRESHOLD = 0.25f
    /**
     * …and never below this while media plays: TV audio alone scored up to 0.37 against a voice print (1 of 195
     * windows reached 0.35, 2 % reached 0.32), so on Relaxed the media bar is stricter than in quiet.
     */
    const val MEDIA_MIN_VOICE_THRESHOLD = 0.35f
    /**
     * Media counts as playing until this long after it stops: the detector looks at the last ~1.3 s of audio, and
     * players report "stopped" a little before the speaker goes quiet.
     */
    const val MEDIA_HOLD_MS = 2_000L

    fun policy(sensitivity: WakePhrases.Sensitivity, ctx: AudioContext, voiceMatch: Boolean): WakePolicy =
        policy(sensitivity.detectorThreshold, sensitivity.patience, ctx, voiceMatch)

    /** [voiceMatch] = the user trained their voice and the print can be computed (Vosk models installed). */
    fun policy(baseThreshold: Float, basePatience: Int, ctx: AudioContext, voiceMatch: Boolean): WakePolicy {
        val loudMedia = ctx.mediaPlaying && (ctx.route == OutputRoute.SPEAKER || ctx.route == OutputRoute.BLUETOOTH)
        val route = ctx.route.name.lowercase()
        return when {
            ctx.inCall -> WakePolicy(false, baseThreshold, basePatience, alwaysConfirm = true, raised = true, reason = "call")
            // Voice Match is the gate: the user's own bar, one window, the voice must match
            voiceMatch && loudMedia -> WakePolicy(
                true, baseThreshold, 1, alwaysConfirm = false, raised = false, reason = "media-voice-$route",
                voiceRequired = true, voiceRelax = MEDIA_VOICE_RELAX,
                noPrint = maxOf(baseThreshold, MEDIA_NO_PRINT_THRESHOLD).let {
                    WakePolicy(true, it, 1, alwaysConfirm = true, raised = it > baseThreshold, reason = "media-no-print-$route")
                }
            )
            voiceMatch && ctx.route == OutputRoute.BLUETOOTH && !ctx.mediaPlaying -> WakePolicy(
                true, baseThreshold, 1, alwaysConfirm = false, raised = false, reason = "bluetooth-voice",
                voiceRequired = true, noPrint = bluetoothIdle(baseThreshold, basePatience)
            )
            loudMedia -> maxOf(baseThreshold, NO_PROFILE_MEDIA_THRESHOLD).let {
                WakePolicy(true, it, 1, alwaysConfirm = true, raised = it > baseThreshold, reason = "media-$route")
            }
            // Headphones: the microphone barely hears the playback; same bar, but a clear command still gets confirmed
            ctx.mediaPlaying -> WakePolicy(true, baseThreshold, basePatience, alwaysConfirm = true, raised = false, reason = "media-headphones")
            ctx.route == OutputRoute.BLUETOOTH -> bluetoothIdle(baseThreshold, basePatience)
            else -> WakePolicy(true, baseThreshold, basePatience, alwaysConfirm = false, raised = false, reason = "quiet")
        }
    }

    private fun bluetoothIdle(baseThreshold: Float, basePatience: Int): WakePolicy {
        val t = maxOf(baseThreshold, minOf(baseThreshold + BLUETOOTH_IDLE_BONUS, NO_PROFILE_MEDIA_THRESHOLD))
        return WakePolicy(true, t, basePatience, alwaysConfirm = false, raised = t > baseThreshold, reason = "bluetooth")
    }

    /** The voice similarity needed under [policy], given the user's own bar [voiceThreshold]. */
    fun voiceBar(voiceThreshold: Float, policy: WakePolicy): Float =
        if (policy.voiceRelax > 0f) maxOf(MEDIA_MIN_VOICE_THRESHOLD, voiceThreshold - policy.voiceRelax)
        else maxOf(MIN_VOICE_THRESHOLD, voiceThreshold)

    /**
     * Decides a wake the detector fired on. [similarity] = Voice Match result (null = no print could be computed);
     * [hasProfile] = the user trained their voice; [voiceThreshold] = their similarity bar (sensitivity, spread…).
     */
    fun verdict(score: Float, policy: WakePolicy, hasProfile: Boolean, similarity: Float?, voiceThreshold: Float): WakeVerdict {
        if (hasProfile && similarity != null) {
            val needed = voiceBar(voiceThreshold, policy)
            return if (similarity < needed) WakeVerdict.RejectVoice(similarity, needed)
            else WakeVerdict.Accept(confirm = false, voiceMatched = true)
        }
        val p = if (policy.voiceRequired) policy.noPrint ?: policy else policy
        if (score < p.threshold) return WakeVerdict.RejectNoPrint
        return WakeVerdict.Accept(needsConfirmation(score, p, voiceMatched = false), voiceMatched = false)
    }

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
