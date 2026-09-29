package io.github.salex27.lumi.service.wakeword

import kotlin.math.sqrt

/**
 * Detección de «Oye Lumi» en dos filtros (puro, testeado). Historia en MEMORY.md:
 *
 * - v3.0.2 usaba una gramática CERRADA («oye lu mi»): Vosk fuerza cualquier audio a una de las frases, así que
 *   «Oye Luis, mira esto» salía como «oye lu mi» con confianza 1.0 → 18/20 falsos positivos en conversación.
 * - v3.0.3: transcripción LIBRE + estos filtros:
 *   1. Frase: la frase reconocida debe ser SOLO «oye/hola + nombre» (el usuario hace una pausa, como con Siri) y el
 *      nombre debe sonar a «lumi» (distancia de edición ≤ 1 a las formas en que el modelo transcribe «Lumi»).
 *      Con audio de prueba: 0/20 falsos positivos.
 *   2. Voz (opcional, «Entrenar mi voz»): similitud coseno entre la huella de voz (x-vector de Vosk) y la media
 *      de 3 muestras del usuario. Misma voz 0,69–0,97; otra persona 0,03.
 */
// Umbrales del detector «Oye Lumi» (elegidos con train.py: ver MEMORY.md, v3.5)
// Con 5 voces nunca vistas y 5,3 h de audio real: Estricta 66 % acierto / 0 falsas por hora; Normal 75 % / 0,4;
// Relajada 81 % / 0,75. Con una sola ventana basta (exigir varias seguidas perdía más aciertos que falsas quitaba).
const val DETECTOR_STRICT = 0.6f
const val DETECTOR_NORMAL = 0.4f
const val DETECTOR_RELAXED = 0.3f

object WakePhrases {

    /** Formas en que el modelo español transcribe «Lumi» (medido con voz es-ES; se amplía con las del usuario). */
    // «luni» NO: a distancia 1 de «luna» («hola luna llena» activaba)
    val DEFAULT_NAMES = listOf("lumi", "lomi", "alumni", "lumí", "lumie")
    private val WAKE_WORDS = setOf("oye", "hola", "ey", "hey")
    /** Cómo transcribe a menudo el modelo pequeño un «oye» rápido (v3.3: el usuario no conseguía activarlo). */
    private val WAKE_WORDS_LOOSE = WAKE_WORDS + setOf("hoy", "oi", "oiga", "olle", "o", "ei", "ay", "joe", "holle", "oyes")
    private val CANONICAL = listOf("oyelumi", "holalumi", "heylumi", "eylumi")

    /**
     * Sensibilidad: afecta a la voz (umbral coseno) Y a la frase (v3.3: antes «Relajada» solo bajaba el umbral de voz
     * y la frase seguía descartando «hoy lumi»). [phraseRatio] = distancia de edición permitida / longitud.
     */
    enum class Sensitivity(
        val threshold: Float, val label: String, val phraseRatio: Double, val maxWords: Int, val looseWakeWords: Boolean,
        /** v3.5 (openWakeWord): puntuación mínima del detector y trozos de 80 ms seguidos por encima. */
        val detectorThreshold: Float, val patience: Int
    ) {
        STRICT(0.55f, "Estricta", 0.12, 3, false, DETECTOR_STRICT, 1),
        NORMAL(0.42f, "Normal", 0.2, 3, true, DETECTOR_NORMAL, 1),
        RELAXED(0.30f, "Relajada", 0.27, 4, true, DETECTOR_RELAXED, 1)
    }

    /** Sin acentos ni signos: «¡Oye, Lumí!» → «oye lumi». */
    fun normalize(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-zñ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * ¿Es «Oye Lumi»? Dos vías:
     *  A) palabra de llamada («oye», «hoy», «hola»…) + nombre a distancia ≤ 1 de «lumi» o de cómo lo dices tú;
     *  B) la frase entera (sin espacios) parecida a «oyelumi» o a TUS frases de entrenamiento (tolerancia según [sensitivity]).
     * [personal] = nombres y frases guardados al entrenar la voz.
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
            // Estricta: 0 errores en la frase entera; Normal ≈ 1; Relajada 1-2 según la longitud
            levenshtein(joined, p) <= (p.length * sensitivity.phraseRatio).toInt()
        }
    }

    /**
     * ¿La frase (final, no parcial) es «Oye Lumi»? Solo 2-3 palabras: una conversación larga que empiece por
     * «oye, Luis…» no cuenta. [personalNames] = cómo transcribió el modelo el «Lumi» del propio usuario al entrenar.
     */
    fun isWakePhrase(text: String, personalNames: Collection<String> = emptyList()): Boolean =
        matches(text, personalNames, Sensitivity.NORMAL)

    /** Nombre tal como lo oyó el modelo en una muestra de entrenamiento («oye lo mi» → «lomi»), o null si no vale. */
    fun nameFromSample(text: String): String? {
        val words = normalize(text).split(" ").filter { it.isNotBlank() }
        if (words.size !in 2..3 || words[0] !in WAKE_WORDS_LOOSE) return null
        return words.drop(1).joinToString("").takeIf { it.length in 2..8 }
    }

    /**
     * Frase de entrenamiento tal como la transcribe el modelo con TU voz («hoy lumi» → «hoylumi»). Se acepta cualquier
     * frase corta (1-4 palabras, 4-16 letras): así se aprende tu forma de decirlo aunque el modelo no oiga «oye».
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
 * Red de seguridad cuando Lumi se abre por voz: si lo que oye no suena a una orden para ella
 * (conversación de fondo), no se ejecuta directamente: se pide confirmación.
 */
object CommandLikeness {
    private val COMMAND = Regex(
        "(?iu)^\\s*(?:y\\s+)?(?:" +
            "recu[eé]rda(?:me|lo)?|recordar|ap[uú]nta(?:me)?|anota|a[ñn]ade|agrega|crea|nueva\\s+tarea|pon(?:me)?|programa|av[ií]same|" +
            "tengo\\s+que|hay\\s+que|debo|necesito|" +
            "(?:¿\\s*)?(?:qu[eé]|cu[aá]l|c[oó]mo|cu[aá]ndo|d[oó]nde)|dime|dame|resume|resumen|planifica|organiza|" +
            "mueve|cambia|pasa|posp[oó]n|aplaza|retrasa|adelanta|reprograma|" +
            "ya\\s+(?:he\\s+)?(?:termin|acab|hice|complet)|he\\s+terminado|marca|cancela|borra|elimina|prioriza|" +
            "cuando\\s+(?:llegue|salga|est[eé])|al\\s+(?:llegar|salir)|" +
            "ll[aá]ma|abre|ed[ií]ta|renombra|recuerda|olvida|env[ií]a|manda|escr[ií]be|busca|enciende|apaga|temporizador|alarma|" +
            "ma[ñn]ana|hoy|el\\s+(?:lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)" +
            ")"
    )

    fun looksLikeCommand(text: String): Boolean = COMMAND.containsMatchIn(text)
}
