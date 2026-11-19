package io.github.salex27.lumi.domain.schedule

import java.text.Normalizer

/** Does a sentence ask for a reminder ("recuérdame…", "avísame…", "remind me…") rather than a to-do? Pure, tested. */
object ReminderPhrase {
    private val PREFIXES = listOf("recuerdame", "recordarme", "avisame", "remind me", "ping me", "nudge me")

    fun isReminder(sentence: String): Boolean {
        val s = Normalizer.normalize(sentence.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z ]+"), " ").replace(Regex(" +"), " ").trim()
            .removePrefix("lumi ").removePrefix("oye ").removePrefix("hey ").removePrefix("por favor ").removePrefix("please ")
        return PREFIXES.any { s.startsWith(it) }
    }
}
