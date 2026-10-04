package io.github.salex27.lumi.service.wakeword

import kotlin.math.sqrt

/** The two wake phrases (one detector model for both). Voice Match keeps a separate print for each. */
enum class WakePhrase {
    HEY, OYE;

    companion object {
        // How the small Spanish Vosk model transcribes each phrase in a 2 s wake window (TTS voices): "Oye Lumi" →
        // "hoy lunes", "hoy lumen", "uy…"; "Hey Lumi" → "el humi", "eh bildu", "en…", "ay…", "ayúdame", "ilumina".
        // Never confused one for the other in 80 windows; about half can't be told (→ best of both prints).
        private val OYE_WORDS = setOf("oye", "oyes", "hoy", "oy", "oi", "oiga", "olle", "holle", "joe", "hoyo", "uy")
        private val HEY_WORDS = setOf("hey", "ey", "ei", "eh", "e", "he", "el", "en", "ahi", "hay", "ay", "ahy")

        /** Which phrase a transcript of the wake sounds like, or null if it can't be told. */
        fun fromTranscript(text: String): WakePhrase? {
            val first = WakePhrases.normalize(text).split(" ").firstOrNull { it.isNotBlank() } ?: return null
            return when {
                first in OYE_WORDS || first.startsWith("oyen") -> OYE
                first in HEY_WORDS || first.startsWith("ilumin") || first.startsWith("ayud") -> HEY
                else -> null
            }
        }
    }
}

/**
 * One phrase's voice prints (Vosk x-vectors, 128 numbers each; never audio). [enrolled] = from "Train my voice";
 * [learned] = from wakes the user confirmed (bounded, rolling); [legacy] = the single averaged print of the old
 * 3-sample training (no spread can be computed from it).
 */
class PhrasePrints(val enrolled: List<FloatArray>, val learned: List<FloatArray> = emptyList(), val legacy: Boolean = false)

/**
 * The user's voice profile: prints per phrase, plus [negatives] (prints of wakes the user dismissed right away, only
 * used to calibrate the similarity bar) and [names] (how the speech model transcribed the user's "Lumi").
 */
class VoiceData(
    val phrases: Map<WakePhrase, PhrasePrints>,
    val negatives: List<FloatArray> = emptyList(),
    val names: Set<String> = emptySet()
) {
    val trained: Boolean get() = phrases.values.any { it.enrolled.isNotEmpty() }
    fun prints(phrase: WakePhrase): PhrasePrints? = phrases[phrase]?.takeIf { it.enrolled.isNotEmpty() }

    /** With [phrase] trained anew (its learned prints start over); a legacy print of the other phrase stays. */
    fun withPhrase(phrase: WakePhrase, prints: List<FloatArray>, newNames: Set<String>): VoiceData =
        VoiceData(phrases + (phrase to PhrasePrints(prints)), negatives, names + newNames)

    /** Without [phrase]; null if nothing is left. */
    fun without(phrase: WakePhrase): VoiceData? =
        VoiceData(phrases - phrase, negatives, names).takeIf { it.trained }
}

/** Result of matching a wake's print: the [similarity] to the user's voice and the phrase whose print was used. */
data class VoiceScore(val similarity: Float, val phrase: WakePhrase)

/**
 * Voice Match logic (pure, tested): similarity per phrase, the similarity bar computed from the enrolment spread,
 * adaptive learning from confirmed wakes and calibration from dismissed ones, and the profile's storage format.
 *
 * Measured with Vosk on TTS voices and TV audio (MEMORY.md, 2026-10-05): the user's 2 s wake window scores 0.70 on
 * average in quiet (5th percentile 0.49) against their phrase print; TV audio alone reached 0.37 at most; another voice
 * 0.29 on average (16 % above 0.42). Enrolment leave-one-out similarity 0.59-0.63 ± 0.14-0.17 (close, far, soft, loud
 * and music samples): mean − 1.5 sd ≈ 0.38-0.42, i.e. the fixed Normal bar for a typical spread.
 */
object VoiceMatch {

    /** Samples per phrase in "Train my voice". */
    const val SAMPLES_PER_PHRASE = 12
    /** At least this many enrolled prints to compute a spread (fewer, or the legacy print → the fixed bar). */
    const val MIN_SPREAD_SAMPLES = 6
    /** Spread bar = mean − this × standard deviation of the leave-one-out similarities. */
    const val SPREAD_K = 1.5f
    /** The spread can move the bar at most this much from the sensitivity's fixed bar. */
    const val SPREAD_RANGE = 0.08f
    /** An enrolment sample this unlike the others (leave-one-out) is dropped: noise, or someone else spoke. */
    const val OUTLIER_SIMILARITY = 0.12f
    const val MAX_OUTLIERS = 2
    /** The legacy averaged print stood for 3 samples. */
    private const val LEGACY_WEIGHT = 3f

    /** Learned prints kept per phrase (oldest dropped first): at most as many as a training gives. */
    const val LEARNED_MAX = 10
    /** Only learn from a wake whose detector score was at least this… */
    const val LEARN_MIN_SCORE = 0.50f
    /** …and whose voice similarity was at least this much above the bar (more while media plays: it is mixed in). */
    const val LEARN_MARGIN = 0.08f
    const val LEARN_MARGIN_MEDIA = 0.15f
    /** Dismissed wakes kept for calibration. */
    const val NEGATIVES_MAX = 20
    /** Dismissed wakes raise the bar to just above them, at most this much and never into the user's own range. */
    const val NEGATIVE_MARGIN = 0.03f
    const val MAX_NEGATIVE_RAISE = 0.10f
    /** The user's own range starts at this quantile of their genuine similarities (enrolment + learned). */
    const val GENUINE_LOW_QUANTILE = 0.20f

    fun cosine(a: FloatArray, b: FloatArray): Float = WakePhrases.cosine(a, b)

    /** The phrase's centroid: mean of enrolled (the legacy print counts 3 times) and learned prints. */
    fun centroid(p: PhrasePrints): FloatArray {
        val all = p.enrolled + p.learned
        val dim = all.first().size
        val out = FloatArray(dim)
        var total = 0f
        all.forEachIndexed { i, v ->
            val w = if (p.legacy && i < p.enrolled.size) LEGACY_WEIGHT else 1f
            for (d in 0 until dim) out[d] += v[d] * w
            total += w
        }
        for (d in 0 until dim) out[d] /= total
        return out
    }

    /**
     * Similarity of a wake's print [x] to the user's voice: against the detected [phrase]'s print when there is one,
     * otherwise the best of both. Null if nothing is trained.
     */
    fun score(x: FloatArray, data: VoiceData, phrase: WakePhrase?): VoiceScore? {
        phrase?.let { ph -> data.prints(ph)?.let { return VoiceScore(cosine(x, centroid(it)), ph) } }
        return WakePhrase.entries.mapNotNull { ph -> data.prints(ph)?.let { VoiceScore(cosine(x, centroid(it)), ph) } }
            .maxByOrNull { it.similarity }
    }

    /** Leave-one-out similarity of each enrolled print to the centroid of the others. */
    fun leaveOneOut(prints: List<FloatArray>): List<Float> = prints.indices.map { i ->
        cosine(prints[i], centroid(PhrasePrints(prints.filterIndexed { j, _ -> j != i })))
    }

    /** Drops up to [MAX_OUTLIERS] samples clearly unlike the rest (only with enough samples to tell). */
    fun dropOutliers(prints: List<FloatArray>): List<FloatArray> {
        if (prints.size < MIN_SPREAD_SAMPLES) return prints
        val loo = leaveOneOut(prints)
        val bad = loo.withIndex().filter { it.value < OUTLIER_SIMILARITY }.sortedBy { it.value }.take(MAX_OUTLIERS).map { it.index }.toSet()
        return prints.filterIndexed { i, _ -> i !in bad }
    }

    /** Mean and standard deviation of [values]. */
    fun meanSd(values: List<Float>): Pair<Float, Float> {
        val m = values.average()
        val sd = sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
        return m.toFloat() to sd.toFloat()
    }

    /** The bar from the enrolment spread (mean − [SPREAD_K]·sd), or null without enough samples. */
    fun spreadBar(p: PhrasePrints): Float? {
        if (p.legacy || p.enrolled.size < MIN_SPREAD_SAMPLES) return null
        val (m, sd) = meanSd(leaveOneOut(p.enrolled))
        return m - SPREAD_K * sd
    }

    /**
     * The similarity a wake needs to count as the user's voice for [phrase] (before the media relax, see
     * [WakeGate.voiceBar]): the sensitivity's fixed bar, moved by the enrolment spread by at most ±[SPREAD_RANGE]. A
     * steady voice (similar samples) gets a stricter bar, a variable one a looser bar; a legacy profile keeps the
     * fixed bar.
     */
    fun bar(data: VoiceData, sensitivity: WakePhrases.Sensitivity, phrase: WakePhrase): Float {
        val fixed = sensitivity.threshold
        val p = data.prints(phrase) ?: return fixed
        // The spread bar matches Normal; the other sensitivities keep their distance from it
        val offset = fixed - WakePhrases.Sensitivity.NORMAL.threshold
        val base = spreadBar(p)?.let { (it + offset).coerceIn(fixed - SPREAD_RANGE, fixed + SPREAD_RANGE) } ?: fixed
        return calibrate(maxOf(WakeGate.MIN_VOICE_THRESHOLD, base), data, p)
    }

    /** Genuine similarities: enrolment leave-one-out plus each learned print against the enrolled centroid. */
    private fun genuine(p: PhrasePrints): List<Float> {
        val loo = if (!p.legacy && p.enrolled.size >= MIN_SPREAD_SAMPLES) leaveOneOut(p.enrolled) else emptyList()
        val c = centroid(PhrasePrints(p.enrolled, legacy = p.legacy))
        return loo + p.learned.map { cosine(it, c) }
    }

    /**
     * Wakes the user dismissed right away (TV, someone else) raise the bar to just above them: the second highest
     * of those below the user's own range + [NEGATIVE_MARGIN], by at most [MAX_NEGATIVE_RAISE] and never above the
     * user's range. A dismissed wake that sounded like the user (they changed their mind) teaches nothing.
     */
    private fun calibrate(bar: Float, data: VoiceData, p: PhrasePrints): Float {
        if (data.negatives.size < 2) return bar
        val c = centroid(p)
        val g = genuine(p).sorted()
        val genuineLow = if (g.size >= 5) g[((g.size - 1) * GENUINE_LOW_QUANTILE).toInt()] else bar + SPREAD_RANGE
        val near = data.negatives.map { cosine(it, c) }.filter { it < genuineLow }.sortedDescending()
        if (near.size < 2) return bar
        val raised = near[1] + NEGATIVE_MARGIN
        return maxOf(bar, minOf(raised, genuineLow, bar + MAX_NEGATIVE_RAISE))
    }

    /** A wake that opened the assistant, waiting to learn whether the user really called Lumi. */
    class WakeSample(
        val print: FloatArray, val phrase: WakePhrase, val score: Float, val similarity: Float, val bar: Float, val media: Boolean
    )

    /**
     * Confirmed wake (the user went on with a command or said yes): its print is learned for its phrase if both the
     * detector score and the voice were clearly good, so one doubtful wake can't drag the print towards someone else.
     */
    fun learn(data: VoiceData, w: WakeSample): VoiceData {
        val p = data.prints(w.phrase) ?: return data
        val margin = if (w.media) LEARN_MARGIN_MEDIA else LEARN_MARGIN
        if (w.score < LEARN_MIN_SCORE || w.similarity < w.bar + margin) return data
        val learned = (p.learned + w.print).takeLast(LEARNED_MAX)
        return VoiceData(data.phrases + (w.phrase to PhrasePrints(p.enrolled, learned, p.legacy)), data.negatives, data.names)
    }

    /** Dismissed wake (closed or cancelled right away): kept only to calibrate the bar, never added to the print. */
    fun reject(data: VoiceData, w: WakeSample): VoiceData =
        VoiceData(data.phrases, (data.negatives + w.print).takeLast(NEGATIVES_MAX), data.names)

    /** "Reset learned voice data": the trained prints stay; learned prints and dismissed wakes go. */
    fun forget(data: VoiceData): VoiceData =
        VoiceData(data.phrases.mapValues { (_, p) -> PhrasePrints(p.enrolled, emptyList(), p.legacy) }, emptyList(), data.names)

    // ── Storage: plain text, one "key=value" per line; prints as comma-separated numbers, ";" between prints ──

    fun encode(data: VoiceData): String = buildString {
        appendLine("version=2")
        appendLine("names=" + data.names.joinToString(","))
        for ((ph, p) in data.phrases) {
            appendLine("${ph.name}.legacy=${p.legacy}")
            appendLine("${ph.name}.enrolled=" + p.enrolled.joinToString(";") { it.joinToString(",") })
            appendLine("${ph.name}.learned=" + p.learned.joinToString(";") { it.joinToString(",") })
        }
        appendLine("negatives=" + data.negatives.joinToString(";") { it.joinToString(",") })
    }

    fun decode(text: String): VoiceData? = runCatching {
        val kv = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        if (kv["version"] != "2") return null
        fun prints(s: String?): List<FloatArray> = s.orEmpty().split(";").filter { it.isNotBlank() }
            .map { v -> v.split(",").map { it.toFloat() }.toFloatArray() }
        val phrases = WakePhrase.entries.mapNotNull { ph ->
            val enrolled = prints(kv["${ph.name}.enrolled"])
            if (enrolled.isEmpty()) null
            else ph to PhrasePrints(enrolled, prints(kv["${ph.name}.learned"]), kv["${ph.name}.legacy"] == "true")
        }.toMap()
        VoiceData(phrases, prints(kv["negatives"]), kv["names"].orEmpty().split(",").filter { it.isNotBlank() }.toSet())
            .takeIf { it.trained }
    }.getOrNull()

    /** The old profile (one averaged print of 3 "Oye Lumi" samples) keeps working for both phrases until retrained. */
    fun migrate(legacyPrint: FloatArray, names: Set<String>): VoiceData = VoiceData(
        WakePhrase.entries.associateWith { PhrasePrints(listOf(legacyPrint), legacy = true) }, names = names
    )
}

/**
 * The last wake that opened the assistant with a voice print, waiting for what the user did (pure: the time is passed
 * in). Only the first outcome counts; a confirmation counts within [CONFIRM_WINDOW_MS] of the wake and a dismissal
 * only within [DISMISS_WINDOW_MS] ("closed it right away").
 */
class WakeFeedback {
    private var pendingId = 0L
    private var pending: VoiceMatch.WakeSample? = null
    private var pendingAt = 0L
    private var nextId = 1L

    /** Remembers [sample] (replacing any older one) and returns its id for the assistant. */
    @Synchronized
    fun begin(sample: VoiceMatch.WakeSample, now: Long): Long {
        pendingId = nextId++
        pending = sample; pendingAt = now
        return pendingId
    }

    /** The sample to learn from ([confirmed]) or to keep as a negative, or null if unknown, late or already used. */
    @Synchronized
    fun outcome(id: Long, confirmed: Boolean, now: Long): VoiceMatch.WakeSample? {
        val s = pending ?: return null
        if (id != pendingId) return null
        pending = null
        val age = now - pendingAt
        return s.takeIf { age in 0..(if (confirmed) CONFIRM_WINDOW_MS else DISMISS_WINDOW_MS) }
    }

    @Synchronized
    fun clear() { pending = null }

    companion object {
        const val CONFIRM_WINDOW_MS = 60_000L
        /** Listening (up to ~8 s) plus the unanswered "Should I note it down?" (8 s) fit well inside this. */
        const val DISMISS_WINDOW_MS = 30_000L
    }
}