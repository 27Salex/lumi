package io.github.salex27.lumi.service.wakeword

import kotlin.math.sqrt

/**
 * Text-based "Oye Lumi" detection in two filters (pure, tested), used by the voice training and as a fallback.
 * History:
 * - A CLOSED grammar ("oye lu mi") made Vosk force any audio into one of the phrases, so "Oye Luis, mira esto" came
 *   out as "oye lu mi" with confidence 1.0 → 18/20 false positives in conversation.
 * - Then FREE transcription + these filters:
 *   1. Phrase: the recognized phrase must be ONLY "oye/hola + name" (the user pauses, like with Siri) and the name must
 *      sound like "lumi" (edit distance ≤ 1 to the ways the model transcribes "Lumi"). With test audio: 0/20 false positives.
 *   2. Voice (optional, "Train my voice"): cosine similarity between the voice print (Vosk x-vector) and the average of
 *      3 samples from the user. Same voice 0.69–0.97; another person 0.03.
 */
// Wake phrase detector thresholds (model v2, one model for "Hey Lumi" + "Oye Lumi", picked with train_v2.py).
// Unseen voices, phrase inside 3 s of audio with noise and phone-mic effects, and 5.3 h of real audio for false wakes:
//   Strict 0.50  → "Oye Lumi" 69 %, "Hey Lumi" 80 %, 0 false wakes/h
//   Normal 0.35  → ~76 %, ~85 %, ~0.1/h   (the previous model: 75 %, 56 %, 0.37/h)
//   Relaxed 0.25 → ~81 %, ~88 %, ~0.5/h
// English sound-alike names ("Hey Lucy", "Hey Louie") are the weak spot (~25–35 % on Normal); Voice Match and the
// "Should I note it down?" confirmation filter them. A single window is enough (requiring several in a row lost more
// detections than false ones it removed).
const val DETECTOR_STRICT = 0.50f
const val DETECTOR_NORMAL = 0.35f
const val DETECTOR_RELAXED = 0.25f

object WakePhrases {

    /** Ways the Spanish model transcribes "Lumi" (measured with an es-ES voice; extended with the user's own). */
    // Not "luni": distance 1 from "luna" ("hola luna llena" triggered it)
    val DEFAULT_NAMES = listOf("lumi", "lomi", "alumni", "lumí", "lumie")
    private val WAKE_WORDS = setOf("oye", "hola", "ey", "hey")
    /** How the small model often transcribes a quick "oye" (users couldn't trigger it otherwise). */
    private val WAKE_WORDS_LOOSE = WAKE_WORDS + setOf("hoy", "oi", "oiga", "olle", "o", "ei", "ay", "joe", "holle", "oyes")
    private val CANONICAL = listOf("oyelumi", "holalumi", "heylumi", "eylumi")

    /**
     * Sensitivity: affects the voice (cosine threshold) AND the phrase (before, "Relaxed" only lowered the voice
     * threshold and the phrase still rejected "hoy lumi"). [phraseRatio] = allowed edit distance / length.
     */
    enum class Sensitivity(
        val threshold: Float, val label: String, val phraseRatio: Double, val maxWords: Int, val looseWakeWords: Boolean,
        /** openWakeWord: minimum detector score and consecutive 80 ms chunks above it. */
        val detectorThreshold: Float, val patience: Int
    ) {
        STRICT(0.55f, "Strict", 0.12, 3, false, DETECTOR_STRICT, 1),
        NORMAL(0.42f, "Normal", 0.2, 3, true, DETECTOR_NORMAL, 1),
        RELAXED(0.30f, "Relaxed", 0.27, 4, true, DETECTOR_RELAXED, 1)
    }

    /** Without accents or punctuation: "¡Oye, Lumí!" → "oye lumi". */
    fun normalize(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-zñ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Is it "Oye Lumi"? Two paths:
     *  A) a calling word ("oye", "hoy", "hola"…) + a name within distance 1 of "lumi" or of how YOU say it;
     *  B) the whole phrase (no spaces) close to "oyelumi" or to YOUR training phrases (tolerance per [sensitivity]).
     * [personal] = names and phrases saved while training the voice.
     */
    fun matches(text: String, personal: Collection<String> = emptyList(), sensitivity: Sensitivity = Sensitivity.NORMAL): Boolean {
        val words = normalize(text).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > sensitivity.maxWords) return false
        val wakeSet = if (sensitivity.looseWakeWords) WAKE_WORDS_LOOSE else WAKE_WORDS
        val names = DEFAULT_NAMES.map(::normalize) + personal
        if (words.size >= 2 && words[0] in wakeSet) {
            for (k in 1..minOf(2, words.size - 1)) {
                val name = words.subList(1, 1 + k).joinToString("")
                if (names.any { levenshtein(name, it) <= 1 }) return true
            }
        }
        if (words.size > 3) return false
        val joined = words.joinToString("")
        return (CANONICAL + personal.filter { it.length >= 5 }).any { p ->
            // Strict: 0 errors in the whole phrase; Normal ≈ 1; Relaxed 1-2 depending on length
            levenshtein(joined, p) <= (p.length * sensitivity.phraseRatio).toInt()
        }
    }

    /**
     * Is the (final, not partial) phrase "Oye Lumi"? Only 2-3 words: a long conversation starting with "oye, Luis…"
     * doesn't count. [personalNames] = how the model transcribed the user's own "Lumi" during training.
     */
    fun isWakePhrase(text: String, personalNames: Collection<String> = emptyList()): Boolean =
        matches(text, personalNames, Sensitivity.NORMAL)

    /** The name as the model heard it in a training sample ("oye lo mi" → "lomi"), or null if it isn't valid. */
    fun nameFromSample(text: String): String? {
        val words = normalize(text).split(" ").filter { it.isNotBlank() }
        if (words.size !in 2..3 || words[0] !in WAKE_WORDS_LOOSE) return null
        return words.drop(1).joinToString("").takeIf { it.length in 2..8 }
    }

    /**
     * The training phrase as the model transcribes it with YOUR voice ("hoy lumi" → "hoylumi"). Any short phrase
     * (1-4 words, 4-16 letters) is accepted: that way it learns how you say it even if the model doesn't hear "oye".
     */
    fun phraseFromSample(text: String): String? {
        val words = normalize(text).split(" ").filter { it.isNotBlank() }
        if (words.size !in 1..4) return null
        return words.joinToString("").takeIf { it.length in 4..16 }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    fun mean(vectors: List<FloatArray>): FloatArray =
        FloatArray(vectors.first().size) { i -> vectors.sumOf { it[i].toDouble() }.toFloat() / vectors.size }

    fun levenshtein(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }
}

/**
 * Safety net when Lumi opens by voice: if what it hears doesn't sound like a command for it (background
 * conversation), it isn't run directly: Lumi asks for confirmation. Spanish and English.
 */
object CommandLikeness {
    private val COMMAND = Regex(
        "(?iu)^\\s*(?:y\\s+|and\\s+)?(?:" +
            "recu[eé]rda(?:me|lo)?|recordar|ap[uú]nta(?:me)?|anota|a[ñn]ade|agrega|crea|nueva\\s+tarea|pon(?:me)?|programa|av[ií]same|" +
            "tengo\\s+que|hay\\s+que|debo|necesito|" +
            "(?:¿\\s*)?(?:qu[eé]|cu[aá]l|c[oó]mo|cu[aá]ndo|d[oó]nde)|dime|dame|resume|resumen|planifica|organiza|" +
            "mueve|cambia|pasa|posp[oó]n|aplaza|retrasa|adelanta|reprograma|" +
            "ya\\s+(?:he\\s+)?(?:termin|acab|hice|complet)|he\\s+terminado|marca|cancela|borra|elimina|prioriza|" +
            "cuando\\s+(?:llegue|salga|est[eé])|al\\s+(?:llegar|salir)|" +
            "ll[aá]ma|abre|ed[ií]ta|renombra|recuerda|olvida|env[ií]a|manda|escr[ií]be|busca|enciende|apaga|temporizador|alarma|" +
            "ma[ñn]ana|hoy|el\\s+(?:lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)|" +
            // English
            "remind\\s+me|add|create|set|call|text|tell|send|open|play|move|postpone|mark|cancel|delete|search|take\\s+me|" +
            "turn\\s+(?:on|off)|read|wake\\s+me|what|when|where|how|who|which|do\\s+i|i\\s+(?:need|have)\\s+to|i\\s+finished|" +
            "tomorrow|today|tonight|next\\s+\\w+|good\\s+(?:morning|night)" +
            ")"
    )
    fun looksLikeCommand(text: String): Boolean = COMMAND.containsMatchIn(text)
}
