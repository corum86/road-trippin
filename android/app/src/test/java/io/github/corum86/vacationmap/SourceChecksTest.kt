package io.github.corum86.vacationmap

import io.github.corum86.vacationmap.data.SyncStatus
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strings and icons are looked up by name at run time, where a typo shows up
 * as a raw key or icon name on screen. These tests read the sources and check
 * every name they use against the tables.
 */
class SourceChecksTest {
    private val sources: List<File> = File("src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name != "Translations.kt" }
        .toList()

    // names and keys are plain one-line literals: no escapes, no templates
    private val literal = Regex(""""([^"\\$\n]*)"""")

    private fun literals(files: List<File>): Set<String> =
        files.flatMapTo(HashSet()) { file -> literal.findAll(file.readText()).map { it.groupValues[1] } }

    private val translationKey = Regex("[a-z]+\\.[A-Za-z0-9.]+")

    /** every key the generated tables define */
    private val keys: Set<String> =
        literals(listOf(File("src/main/java/io/github/corum86/vacationmap/i18n/Translations.kt")))
            .filterTo(HashSet()) { it.matches(translationKey) }

    @Test
    fun everyTranslationKeyUsedExists() {
        assertTrue("translation table looks empty", keys.size > 300)
        val namespaces = keys.mapTo(HashSet()) { it.substringBefore('.') }
        val used = literals(sources).filter { it.matches(translationKey) && it.substringBefore('.') in namespaces }
        val missing = used.filter { it !in keys }.sorted()
        assertEquals("keys used in the sources but absent from the translations", emptyList<String>(), missing)
    }

    @Test
    fun keysBuiltAtRunTimeExist() {
        val built = buildList {
            for (group in TravelGroup.entries) {
                add("wizard.group.${group.id}")
                add("wizard.group.${group.id}Desc")
            }
            for (style in TravelStyle.entries) {
                add("wizard.style.${style.id}")
                add("wizard.style.${style.id}Desc")
            }
            for (must in MustHave.entries) add("wizard.must.${must.id}")
            for (level in 1..3) add("wizard.budget.$level")
            for (status in SyncStatus.entries) if (status != SyncStatus.Unavailable) add("sync.status.${status.id}")
        }
        assertEquals(emptyList<String>(), built.filter { it !in keys })
    }

    @Test
    fun bothLanguagesTranslateEveryKey() {
        val en = Translator(Lang.En)
        val el = Translator(Lang.El)
        for (key in keys) {
            assertTrue("$key missing in English", en(key) != key || key == "units.km")
            assertTrue("$key missing in Greek", el(key) != key)
        }
    }

    // lowercase words in the UI sources that are not icon names
    private val notIcons = setOf(
        // animation and layout labels
        "angle", "attribution", "nav", "spinner",
        // translation parameters
        "a", "b", "g", "home", "i", "k", "km", "l", "m", "n", "name", "q", "r", "t", "v", "caption",
        // keys of remembered state
        "all", "pick",
        // words quoted in comments
        "emphasized", "free",
    )

    @Test
    fun everyIconUsedIsInTheFont() {
        // tests run in the app module's directory
        val font = File("../scripts/icons.txt").readLines().filter { it.isNotBlank() }.toSet()
        val ui = sources.filter { "/ui/" in it.path || "/map/" in it.path }
        val words = literals(ui).filter { it.matches(Regex("[a-z][a-z0-9_]*")) }.toSet()
        val unknown = (words - font - notIcons).sorted()
        assertEquals("icon names used in the UI but not in scripts/icons.txt (or add the word to notIcons)", emptyList<String>(), unknown)
        val unused = (font - words).sorted()
        assertEquals("icons in scripts/icons.txt that nothing uses", emptyList<String>(), unused)
    }
}
