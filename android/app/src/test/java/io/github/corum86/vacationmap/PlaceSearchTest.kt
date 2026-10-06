package io.github.corum86.vacationmap

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.corum86.vacationmap.data.AppServices
import io.github.corum86.vacationmap.ui.VacationMapRoot
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

// what Photon answers for "par" around Igoumenitsa, cut down to what the app reads
private const val PAR_ANSWER = """{"type":"FeatureCollection","features":[
{"type":"Feature","properties":{"osm_type":"N","osm_id":10014245517,"type":"house","name":"Parga Apartments","street":"Asagia","city":"Parga","county":"Preveza Regional Unit","state":"Epirus and Western Macedonia","country":"Greece"},"geometry":{"type":"Point","coordinates":[20.4012,39.2861]}},
{"type":"Feature","properties":{"osm_type":"N","osm_id":283363565,"type":"city","name":"Parga","county":"Preveza Regional Unit","state":"Epirus and Western Macedonia","country":"Greece"},"geometry":{"type":"Point","coordinates":[20.3998186,39.2852643]}},
{"type":"Feature","properties":{"osm_type":"R","osm_id":2225507,"type":"city","name":"Parga","county":"Preveza Regional Unit","state":"Epirus and Western Macedonia","country":"Greece"},"geometry":{"type":"Point","coordinates":[20.41,39.29]}},
{"type":"Feature","properties":{"osm_type":"N","osm_id":136892352,"type":"city","name":"Paramythia","county":"Thesprotia Regional Unit","state":"Epirus and Western Macedonia","country":"Greece"},"geometry":{"type":"Point","coordinates":[20.5067,39.4707]}},
{"type":"Feature","properties":{"osm_type":"W","osm_id":190570770,"type":"other","name":"Parakladi Beach","city":"Municipal Unit of Lefkimmi","county":"Corfu Regional Unit","country":"Greece"},"geometry":{"type":"Point","coordinates":[20.07,39.42]}}
]}"""

/** The form's name field looking up places as you type (see PlaceSearchField). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-420dpi")
class PlaceSearchTest {
    @get:Rule
    val compose = createComposeRule()

    private val searches = CopyOnWriteArrayList<HttpUrl>()

    /** The app on its home-base form, with `answer` standing in for Photon's search. */
    private fun openForm(answer: (query: String) -> String?): AppServices {
        val http = fakeHttpClient { request ->
            val url = request.url
            if (url.host != "photon.komoot.io" || url.encodedPath != "/api") return@fakeHttpClient null
            searches += url
            answer(url.queryParameter("q").orEmpty())
        }
        val services = testServices(tripStore(), http = http)
        compose.setContent { VacationMapRoot(services) }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Home Base — Igoumenitsa").performClick()
        compose.waitForIdle()
        return services
    }

    /** The form lies over Settings; its fields are the last three on screen: name, latitude, longitude. */
    private fun formField(index: Int): SemanticsNodeInteraction {
        val fields = compose.onAllNodes(hasSetTextAction())
        return fields[fields.fetchSemanticsNodes().size - 3 + index]
    }

    private fun nameField() = formField(0)

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun typingOffersPlacesAndPickingOneFillsTheForm() {
        val services = openForm { query -> PAR_ANSWER.takeIf { query == "par" } }
        nameField().assertTextEquals("Home Base — Igoumenitsa")
        // the name that is already there is not looked up
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(emptyList<HttpUrl>(), searches.toList())

        // one letter is not worth asking about
        nameField().performTextReplacement("p")
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(emptyList<HttpUrl>(), searches.toList())

        nameField().performTextReplacement("par")
        waitForText("Paramythia")
        val url = searches.single()
        assertEquals("par", url.queryParameter("q"))
        assertEquals("en", url.queryParameter("lang"))
        // biased towards the home base (Igoumenitsa)
        assertEquals("39.51", url.queryParameter("lat"))
        assertEquals("20.26", url.queryParameter("lon"))
        // the town's boundary repeats it; the business named after it comes last
        assertEquals(1, compose.onAllNodesWithText("Parga").fetchSemanticsNodes().size)
        compose.onNodeWithText("Parga Apartments").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/07-form-search.png")

        compose.onNodeWithText("Parga").performClick()
        compose.waitForIdle()
        nameField().assertTextEquals("Parga")
        formField(1).assertTextEquals("39.28526")
        formField(2).assertTextEquals("20.39982")
        // the matches go away once one is chosen, and choosing does not search again
        compose.onNodeWithText("Paramythia").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, searches.size)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/07-form-search-picked.png")

        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        val saved = services.store.data!!.mainLocation!!
        assertEquals("Parga", saved.name)
        assertEquals(39.28526, saved.location.lat, 1e-9)
        assertEquals(20.39982, saved.location.lng, 1e-9)
    }

    @Test
    fun aNameNobodyKnowsStaysWhatWasTyped() {
        openForm { """{"type":"FeatureCollection","features":[]}""" }
        nameField().performTextReplacement("Grandma’s house")
        waitForText("No places found")
        nameField().assertTextEquals("Grandma’s house")
    }

    @Test
    fun aFailedSearchSaysSo() {
        openForm { null }
        nameField().performTextReplacement("lefka")
        waitForText("Couldn’t search for places")
    }
}
