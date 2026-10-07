package io.github.corum86.vacationmap

import io.github.corum86.vacationmap.net.RawThing
import io.github.corum86.vacationmap.net.englishWikipediaTitle
import io.github.corum86.vacationmap.net.parseThings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the model's research answer, in the shapes it has come in. */
class ResearchParsingTest {
    @Test
    fun readsThingsAndDropsWhatIsNotALink() {
        val things = parseThings(
            """```json
            {"things":[
              {"name":" Palamidi ","text":"A fortress above the town.","url":"https://example.org/palamidi","wiki":"Palamidi"},
              {"name":"Old Town","text":"","url":"ftp://nope"},
              {"name":"","text":""},
              42
            ]}
            ```""",
        )
        assertEquals(
            listOf(
                RawThing("Palamidi", "A fortress above the town.", "https://example.org/palamidi", "Palamidi"),
                RawThing("Old Town", "", "", ""),
            ),
            things,
        )
    }

    @Test
    fun olderAnswerShapesBecomeSentences() {
        val sentences = listOf(RawThing("", "Walk the walls.", "", ""), RawThing("", "Swim at the beach.", "", ""))
        assertEquals(sentences, parseThings("""{"facts":["Walk the walls.","Swim at the beach."]}"""))
        assertEquals(sentences, parseThings("""["Walk the walls.","Swim at the beach."]"""))
        assertEquals(sentences, parseThings("1. Walk the walls.\n- Swim at the beach.\n"))
        assertEquals(emptyList<RawThing>(), parseThings(null))
    }

    @Test
    fun articleTitleFromAWikipediaLink() {
        assertEquals("Bourtzi (Nafplio)", englishWikipediaTitle("https://en.wikipedia.org/wiki/Bourtzi_(Nafplio)"))
        assertEquals("Syntagma Square, Nafplio", englishWikipediaTitle("https://en.m.wikipedia.org/wiki/Syntagma_Square%2C_Nafplio#History"))
        assertNull(englishWikipediaTitle("https://el.wikipedia.org/wiki/Ναύπλιο"))
        assertNull(englishWikipediaTitle("https://www.visitgreece.gr/"))
    }
}
