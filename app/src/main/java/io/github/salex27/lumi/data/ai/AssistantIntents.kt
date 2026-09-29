package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.weather.PartOfDay
import io.github.salex27.lumi.domain.weather.WeatherQuery
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Assistant" intents that aren't tasks, in Spanish (English: [EnglishCommands]): weather, the day summary, the smart
 * alarm, reading messages and general questions. Pure Kotlin and tested; used by [RuleBasedEngine].
 */
object AssistantIntents {

    private const val I = "(?iu)"

    // ── Weather ─────────────────────────────────────────────────────────────

    /** Weather words. "Tiempo" only counts with "qué/el tiempo" or "tiempo hace": "¿cuánto tiempo tengo?" isn't the weather. */
    private val WEATHER_TOPIC = Regex(
        "$I(?:\\b(?:qu[eé]|el|del|tal)\\s+tiempo\\b(?!\\s+(?:libre|tengo|me\\s+queda|queda|falta))|\\btiempo\\s+(?:hace|har[aá]|va\\s+a\\s+hacer)\\b|^tiempo\\b|" +
            "\\bclima\\b|\\bprevisi[oó]n\\b|\\bllov(?:er|er[aá]|iendo|i[oó])\\b|\\bllueve\\b|\\blluvia\\b|\\bparaguas\\b|\\bchubasquero\\b|" +
            "\\b(?:chaqueta|abrigo|chaquet[oó]n|jersey|sudadera)\\b|\\bfr[ií]o\\b|\\bcalor\\b|\\btemperatura\\b|\\bgrados\\b|" +
            "\\bnev(?:ar|ar[aá]|ando)\\b|\\bnieva\\b|\\btormenta\\b|\\bsoleado\\b|\\bnublado\\b|\\bhace\\s+sol\\b)"
    )
    /** Shaped like a question or a request for information (not "recuérdame coger el paraguas"). */
    private val WEATHER_ASK = Regex(
        "$I^\\s*¿|\\?\\s*$|^(?:y\\s+)?(?:qu[eé]|c[oó]mo|cu[aá]nt[oa]s?|va\\s+a|van\\s+a|voy\\s+a\\s+necesitar|necesito|necesitar[eé]|" +
            "hace|har[aá]|llueve|llover[aá]|nieva|nevar[aá]|me\\s+llevo|llevo|cojo|me\\s+pongo|dime|(?:el\\s+)?tiempo|(?:el\\s+)?clima|(?:la\\s+)?previsi[oó]n|" +
            "hay\\s+(?:previsi[oó]n|lluvia|tormenta))\\b"
    )
    private val TASKY = Regex("$I^(?:recu[eé]rda(?:me)?|ap[uú]nta(?:me)?|a[ñn]ade|crea|tengo\\s+que|hay\\s+que|debo|comprar|coger)\\b")

    fun isWeather(text: String): Boolean {
        val t = text.trim()
        return WEATHER_TOPIC.containsMatchIn(t) && WEATHER_ASK.containsMatchIn(t) && !TASKY.containsMatchIn(t)
    }

    /** "¿Va a llover mañana por la tarde en Madrid?" → tomorrow, afternoon, rain, Madrid. Null if it isn't about weather. */
    fun weather(text: String, now: LocalDateTime): WeatherQuery? {
        if (!isWeather(text)) return null
        var t = text.trim().trimEnd('?', '.', '!').replace(Regex("^¿"), "")
        val part = when {
            Regex("$I\\b(?:por\\s+la|esta|de\\s+la)\\s+ma[ñn]ana\\b").containsMatchIn(t) -> PartOfDay.MORNING
            Regex("$I\\b(?:por\\s+la|esta|de\\s+la|a\\s+la)\\s+tarde\\b|\\bpor\\s+la\\s+tarde\\b").containsMatchIn(t) -> PartOfDay.AFTERNOON
            Regex("$I\\b(?:por\\s+la|esta|de\\s+la|a\\s+la)\\s+noche\\b|\\besta\\s+noche\\b").containsMatchIn(t) -> PartOfDay.NIGHT
            else -> null
        }
        // Parts of the day are removed before looking for the date ("esta mañana" is not "mañana" = tomorrow)
        t = t.replace(Regex("$I\\b(?:por\\s+la|esta|de\\s+la|a\\s+la)\\s+(?:ma[ñn]ana|tarde|noche)\\b"), " ")
        val date = SpanishDateParser.parse(t, now)?.dateTime?.toLocalDate()?.takeIf { !it.isBefore(now.toLocalDate()) }
            ?: when {
                Regex("$I\\bfin\\s+de\\s+semana\\b").containsMatchIn(t) -> nextSaturday(now.toLocalDate())
                else -> now.toLocalDate()
            }
        val topic = when {
            Regex("$I\\bnev|\\bnieve|\\bnieva").containsMatchIn(t) -> WeatherQuery.Topic.SNOW
            Regex("$I\\bllov|\\bllueve|\\blluvia|\\bparaguas|\\bchubasquero|\\btormenta").containsMatchIn(t) -> WeatherQuery.Topic.RAIN
            Regex("$I\\bfr[ií]o\\b|\\bchaqueta|\\babrigo|\\bchaquet[oó]n|\\bjersey|\\bsudadera").containsMatchIn(t) -> WeatherQuery.Topic.COLD
            Regex("$I\\bcalor\\b").containsMatchIn(t) -> WeatherQuery.Topic.HEAT
            else -> WeatherQuery.Topic.GENERAL
        }
        val place = Regex("$I\\ben\\s+(?!el\\s+(?:fin|d[ií]a)\\b)([\\p{L}][\\p{L} .'-]{1,40}?)\\s*$").find(t.trim())?.groupValues?.get(1)?.trim()
            ?.replace(Regex("$I\\s+(?:hoy|mañana|pasado\\s+mañana|el\\s+\\p{L}+|este\\s+\\p{L}+)$"), "")
            ?.takeIf { it.isNotBlank() && !Regex("$I^(?:serio|general|la\\s+calle|la\\s+semana|la\\s+ma[ñn]ana|la\\s+tarde|la\\s+noche)$").matches(it) }
        return WeatherQuery(date, part, topic, place)
    }

    private fun nextSaturday(today: LocalDate): LocalDate {
        var d = today
        while (d.dayOfWeek != java.time.DayOfWeek.SATURDAY) d = d.plusDays(1)
        return d
    }

    // ── Day summary ("buenos días", "¿qué tengo mañana?") ────────────────────

    private val DAY_BRIEF = Regex(
        "$I^\\s*¿?\\s*(?:(?:y\\s+)?qu[eé]\\s+tengo\\s+(?:hoy|mañana|para\\s+hoy|para\\s+mañana|el\\s+\\p{L}+)|" +
            "c[oó]mo\\s+(?:es|ser[aá]|va\\s+a\\s+ser|pinta)\\s+(?:mi\\s+d[ií]a|el\\s+d[ií]a|hoy|mañana)|" +
            "(?:dame\\s+el\\s+)?resumen\\s+(?:de\\s+(?:hoy|mañana|la\\s+ma[ñn]ana|mi\\s+d[ií]a|el\\s+d[ií]a)|del\\s+d[ií]a|matinal)|" +
            "qu[eé]\\s+me\\s+espera\\s+(?:hoy|mañana))\\s*\\??\\s*$"
    )

    /** The day asked for in a day summary, or null if the sentence isn't one. */
    fun dayBrief(text: String, now: LocalDateTime): LocalDate? {
        val t = text.trim()
        if (!DAY_BRIEF.containsMatchIn(t)) return null
        if (Regex("$I\\bde\\s+la\\s+ma[ñn]ana\\b|\\bmatinal\\b").containsMatchIn(t)) return now.toLocalDate()
        return SpanishDateParser.parse(t, now)?.dateTime?.toLocalDate() ?: now.toLocalDate()
    }

    // ── Smart alarm ─────────────────────────────────────────────────────────

    private val SMART_ALARM = Regex(
        "$I^\\s*¿?\\s*(?:alarma\\s+inteligente|" +
            "(?:pon(?:me)?|programa|ajusta)\\s+(?:la|una|mi)\\s+alarma(?:\\s+para\\s+mañana|\\s+de\\s+mañana)?(?:\\s+seg[uú]n\\s+(?:mi\\s+)?(?:agenda|calendario|d[ií]a))?|" +
            "despi[eé]rta(?:me)?(?:\\s+mañana)?(?:\\s+con\\s+tiempo)?(?:\\s+seg[uú]n\\s+(?:mi\\s+)?(?:agenda|calendario))?|" +
            "a\\s+qu[eé]\\s+hora\\s+(?:me\\s+(?:pongo|pondr[ií]a)\\s+la\\s+alarma|me\\s+(?:levanto|despierto|tengo\\s+que\\s+levantar))(?:\\s+mañana)?)\\s*\\??\\s*$"
    )

    fun isSmartAlarm(text: String) = SMART_ALARM.containsMatchIn(text.trim())
    fun isQuestion(text: String) = Regex("$I^\\s*¿|\\?\\s*$|^a\\s+qu[eé]\\s+hora").containsMatchIn(text.trim())

    // ── Messages and notifications ──────────────────────────────────────────

    private val NOTIFICATIONS = Regex(
        "$I^\\s*¿?\\s*(?:(?:y\\s+)?qu[eé]\\s+me\\s+(?:han|ha)\\s+(?:escrito|dicho|mandado|enviado|puesto)|" +
            "l[eé]e(?:me)?\\s+(?:mis\\s+|los\\s+|las\\s+)?(?:[uú]ltimos\\s+)?(?:mensajes|notificaciones|whatsapps?|correos)|" +
            "(?:tengo|hay)\\s+(?:alg[uú]n\\s+|alguna\\s+|nuevos?\\s+|nuevas?\\s+)?(?:mensajes?|notificaci[oó]n(?:es)?|whatsapps?|correos?)|" +
            "(?:alg[uú]n|alguna)\\s+(?:mensaje|notificaci[oó]n|whatsapp|correo)|" +
            "qu[eé]\\s+(?:mensajes|notificaciones|whatsapps?)\\s+tengo|" +
            "res[uú]me(?:me)?\\s+(?:mis\\s+|los\\s+|las\\s+)?(?:mensajes|notificaciones|whatsapps?)|" +
            "qui[eé]n\\s+me\\s+(?:ha\\s+escrito|ha\\s+llamado|escribi[oó]))"
    )

    /** Null if it isn't about messages; "" = everyone; text = from whom ("¿qué me ha dicho Víctor?"). */
    fun notifications(text: String): String? {
        val t = text.trim().trimEnd('?', '.', '!')
        if (!NOTIFICATIONS.containsMatchIn(t)) return null
        val who = Regex("$I\\bme\\s+ha\\s+(?:escrito|dicho|mandado|enviado|puesto)\\s+(?!nadie)(.+)$").find(t)?.groupValues?.get(1)
            ?: Regex("$I\\b(?:mensajes?|whatsapps?|correos?|notificaciones)\\s+de\\s+(.+)$").find(t)?.groupValues?.get(1)
        return who?.trim()?.replace(Regex("$I^(?:el|la|mi)\\s+"), "").orEmpty()
    }

    // ── General questions and requests (not tasks) ───────────────────────────

    private val GENERAL_ASK = Regex(
        "$I^\\s*(?:dame\\s+(?:ideas?|consejos?|un\\s+consejo|una\\s+receta|un\\s+dato|recomendaciones)|d[ií]me\\s+(?:un|una|c[oó]mo|qu[eé]\\s+es|por\\s+qu[eé]|cu[aá]nt)|" +
            "cu[eé]ntame|expl[ií]ca(?:me)?|traduce|trad[uú]ceme|calcula|recomi[eé]ndame|ay[uú]dame\\s+(?:a|con)|redacta|" +
            "escr[ií]beme\\s+(?:un|una)|haz(?:me)?\\s+(?:un\\s+c[aá]lculo|una\\s+cuenta|un\\s+resumen|un\\s+poema|un\\s+chiste)|" +
            "cu[aá]nto\\s+es|cu[aá]nt[oa]s\\s+\\p{L}+\\s+(?:hay|tiene|faltan|quedan)|por\\s+qu[eé]|" +
            "c[oó]mo\\s+(?:se\\s+(?!llama)|puedo\\s+|funciona|hago\\s+(?:un|una|para))|qu[eé]\\s+significa|qu[eé]\\s+(?:es|son)\\s+(?:un|una|el|la|los|las)\\b)"
    )

    fun isGeneralAsk(text: String) = GENERAL_ASK.containsMatchIn(text.trim())

    /**
     * A question even without "¿qué/cómo…" or question marks (voice often drops them):
     * "he dejado las natillas fuera toda la noche, me las puedo comer", "es malo dormir con el móvil", "qué pasa si…".
     * Requests to Lumi ("¿puedes apuntar…?", "¿me recuerdas…?") DON'T count: they are commands.
     */
    fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (Regex("$I\\b(?:puedes|podr[ií]as|me\\s+(?:recuerdas|apuntas|pones|haces|creas|a[ñn]ades)|recu[eé]rdame|ap[uú]ntame|av[ií]same)\\b").containsMatchIn(t)) return false
        if (t.contains('?')) return true
        return Regex(
            "$I\\b(?:(?:me|te|se|nos)\\s+(?:lo|la|los|las)\\s+puedo|(?:lo|la|los|las)\\s+puedo|puedo\\s+(?:comer|beber|tomar|usar|dejar|mezclar|lavar|congelar|dar)|" +
                "se\\s+puede|es\\s+(?:malo|bueno|seguro|peligroso|normal|sano|recomendable|verdad)|est[aá]\\s+(?:bien|mal|buen[oa]|mal[oa]|caducad[oa])|" +
                "estar[aá]\\s+(?:bien|mal|buen[oa]|mal[oa])|qu[eé]\\s+pasa\\s+si|qu[eé]\\s+(?:hago|har[ií]as)\\s+si|crees\\s+que|sabes\\s+si|" +
                "hace\\s+falta|tengo\\s+que\\s+preocuparme|deber[ií]a\\s+(?:ir|preocuparme|tirar)|cu[aá]nto\\s+(?:tiempo\\s+)?(?:dura|aguanta|tarda))\\b"
        ).containsMatchIn(t)
    }

    /** The user explicitly asks Lumi to remember something (only then is it saved to memory). */
    fun asksToRemember(text: String) = Regex(
        "$I\\b(?:recuerda|acu[eé]rdate|acordarme\\s+de\\s+que|ten\\s+en\\s+cuenta|memoriza|gu[aá]rda(?:te|lo)?\\s+(?:en\\s+(?:tu\\s+)?memoria|que)|apunta\\s+que|que\\s+sepas|no\\s+(?:te\\s+)?olvides\\s+(?:de\\s+)?que|" +
            "remember\\s+that|keep\\s+in\\s+mind|note\\s+that|don'?t\\s+(?:let\\s+me\\s+)?forget\\s+that|for\\s+the\\s+record)\\b"
    ).containsMatchIn(text) || Regex("$I^(?:mi|mis)\\s+\\p{L}+").containsMatchIn(text.trim())

    /** A question that needs current data (better with web search when Gemini online is on). Works for both languages. */
    fun needsFreshData(text: String) = Regex(
        "$I\\b(?:hoy|ahora|actual(?:mente)?|[uú]ltim[oa]s?|noticias?|resultado|marcador|partido|gan[oó]|jug[oó]|precio|cotiza|cuesta|" +
            "abre|cierra|horario|esta\\s+semana|este\\s+a[ñn]o|20\\d\\d|elecciones|presidente|estreno)\\b"
    ).containsMatchIn(text)
}

/**
 * Simple maths without AI (small models get numbers wrong): "¿cuánto es el 15 % de 80?" / "what's 15% of 80",
 * "234 por 12" / "234 times 12", "100 entre 3" / "100 divided by 3". Null if it isn't a calculation.
 * Replies in the language of the sentence (Spanish uses a decimal comma).
 */
object QuickMath {
    private val NUM = "(-?\\d+(?:[.,]\\d+)?)"

    fun answer(text: String): String? {
        val t = text.lowercase().trim().trimEnd('?', '.', '!').replace(Regex("^¿"), "")
            .replace(Regex("^(?:cu[aá]nto\\s+(?:es|son|da)|calcula(?:me)?|dime|what'?s|what\\s+is|how\\s+much\\s+is|calculate)\\s+"), "").trim()
        val en = !Regex("[áéíóúñ]|\\b(?:de|por|entre|m[aá]s|menos|dividido)\\b").containsMatchIn(t) &&
            Regex("\\b(?:of|times|plus|minus|divided|percent)\\b|%").containsMatchIn(t)
        Regex("^(?:el\\s+)?$NUM\\s*(?:%|por\\s*ciento|percent)\\s+(?:de|of)\\s+$NUM$").find(t)?.let { m ->
            val p = n(m.groupValues[1]); val x = n(m.groupValues[2])
            return if (en) "${fmt(p, true)}% of ${fmt(x, true)} is ${fmt(p * x / 100, true)}."
            else "El ${fmt(p)} % de ${fmt(x)} es ${fmt(p * x / 100)}."
        }
        Regex("^$NUM\\s*(\\+|más|mas|plus|-|menos|minus|x|\\*|por|times|multiplied\\s+by|/|entre|dividido\\s+(?:por|entre)|divided\\s+by|over)\\s*$NUM$").find(t)?.let { m ->
            val a = n(m.groupValues[1]); val b = n(m.groupValues[3])
            val op = m.groupValues[2]
            val (r, sym) = when {
                op in setOf("+", "más", "mas", "plus") -> a + b to "+"
                op in setOf("-", "menos", "minus") -> a - b to "−"
                op in setOf("x", "*", "por", "times") || op.startsWith("multiplied") -> a * b to "×"
                else -> { if (b == 0.0) return if (en) "You can't divide by zero." else "No se puede dividir entre cero."; a / b to "÷" }
            }
            val english = en || op in setOf("plus", "minus", "times", "over") || op.startsWith("multiplied") || op.startsWith("divided")
            return "${fmt(a, english)} $sym ${fmt(b, english)} = ${fmt(r, english)}"
        }
        return null
    }

    private fun n(s: String) = s.replace(',', '.').toDouble()

    private fun fmt(x: Double, english: Boolean = false): String =
        if (x == Math.rint(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString()
        else "%.2f".format(java.util.Locale.forLanguageTag(if (english) "en-US" else "es-ES"), x).trimEnd('0').trimEnd(',', '.')
}
