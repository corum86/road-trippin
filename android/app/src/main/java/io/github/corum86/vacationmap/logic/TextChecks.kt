package io.github.corum86.vacationmap.logic

// letters of scripts neither app language is written in: Cyrillic, Hebrew,
// Arabic, Indic, CJK (with its punctuation and full-width forms), Hangul
private val FOREIGN_SCRIPT = Regex("[\\u0400-\\u052f\\u0590-\\u08ff\\u0900-\\u0dff\\u3000-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af\\uff00-\\uffef]")
private val GREEK_LETTER = Regex("[\\u0370-\\u03ff\\u1f00-\\u1fff]")
private val LATIN_LETTER = Regex("[A-Za-z\\u00c0-\\u024f]")
private val WORD = Regex("[A-Za-z\\u00c0-\\u024f\\u0370-\\u03ff\\u1f00-\\u1fff]+")

/**
 * True when text the model wrote in `lang` came out corrupted, which the
 * small Gemini models do now and then: stray letters of another script, or,
 * in Greek, words with Latin letters in them ("Λitharitsia") up to whole
 * sentences of Latin look-alikes. One mixed word is let through; a Latin
 * name inside Greek text is fine. Same rule as the web app's (aiFindings.ts).
 */
fun looksGarbled(text: String, lang: String): Boolean {
    if (FOREIGN_SCRIPT.containsMatchIn(text)) return true
    if (lang != "el") return false
    val words = WORD.findAll(text).map { it.value }.toList()
    val mixed = words.count { GREEK_LETTER.containsMatchIn(it) && LATIN_LETTER.containsMatchIn(it) }
    if (mixed >= 2) return true
    val letters = words.joinToString("")
    val greek = letters.count { GREEK_LETTER.matches(it.toString()) }
    val latin = letters.length - greek
    // long enough to judge, and mostly not Greek
    return greek + latin >= 20 && latin > (greek + latin) * 0.4
}
