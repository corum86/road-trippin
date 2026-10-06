package io.github.corum86.vacationmap.i18n

import androidx.compose.runtime.staticCompositionLocalOf

enum class Lang(val code: String) {
    En("en"),
    El("el");

    companion object {
        fun fromCode(code: String?): Lang = entries.firstOrNull { it.code == code } ?: En
    }
}

/**
 * Looks strings up in the tables generated from the web app's translations
 * (see android/scripts/gen-i18n.mjs). `{name}` placeholders are filled from
 * `params`. The language is switched in the app, independent of the system
 * locale, so this does not go through Android string resources.
 */
class Translator(val lang: Lang) {
    private val table: Map<String, String> = when (lang) {
        Lang.En -> Translations.en
        Lang.El -> Translations.el
    }

    operator fun invoke(key: String, vararg params: Pair<String, Any>): String {
        var text = table[key] ?: Translations.en[key] ?: key
        for ((name, value) in params) text = text.replace("{$name}", value.toString())
        return text
    }
}

val LocalTranslator = staticCompositionLocalOf { Translator(Lang.En) }
