package io.github.corum86.vacationmap

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontFamily
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.logic.AspectRatioId
import io.github.corum86.vacationmap.logic.ExportOptions
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.tripDayCount
import io.github.corum86.vacationmap.map.exportLayoutSize
import io.github.corum86.vacationmap.map.renderMapImage
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.ui.VacationMapRoot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

private const val SUGGESTIONS = """[
  {"name":"Parga","lat":39.285,"lng":20.4,"blurb":"Colourful seaside town under a Venetian castle.","tags":["relaxed","sightseeing"],"groups":["couple","family"],"budget":2,"mustHaves":["beach","food"],"ferry":false},
  {"name":"Sivota","lat":39.4005,"lng":20.2496,"blurb":"Turquoise coves and islets.","tags":["relaxed"],"groups":["family"],"budget":2,"mustHaves":["beach"],"ferry":false},
  {"name":"Paxos","lat":39.198,"lng":20.185,"blurb":"Small island of olive groves and sea caves.","tags":["relaxed","nature"],"groups":["couple"],"budget":3,"mustHaves":["beach"],"ferry":true},
  {"name":"Ioannina","lat":39.665,"lng":20.853,"blurb":"Lakeside city with a castle and silver workshops.","tags":["culture","food"],"groups":["family","friends"],"budget":2,"mustHaves":["food"],"ferry":false},
  {"name":"Dodoni","lat":39.546,"lng":20.787,"blurb":"Ancient oracle and theatre in a mountain valley.","tags":["culture","sightseeing"],"groups":["family"],"budget":1,"mustHaves":[],"ferry":false},
  {"name":"Atlantis","lat":10,"lng":10,"blurb":"Not a real place.","tags":[],"groups":[],"budget":1,"mustHaves":[],"ferry":false}
]"""

// four sights: one with a Wikipedia article, one with a site of its own and a
// photo by name, and two that only get a photo from around the place
private const val FINDINGS = """```json
{"things":[
 {"name":"Castle of Parga","text":"Walk up for the view over the bay.","url":"https://en.wikipedia.org/wiki/Castle_of_Parga","wiki":"Castle of Parga"},
 {"name":"Valtos Beach","text":"A long sandy beach behind the castle.","url":"https://www.visitgreece.gr/","wiki":""},
 {"name":"Sea caves","text":"Take a boat to the caves.","url":"ftp://nope","wiki":"No Such Article"},
 {"name":"Harbour tavernas","text":"Eat grilled octopus on the harbour.","url":"","wiki":""}
]}
```"""

/** Stands in for Gemini, OSRM, Wikimedia and Photon with fixed answers. */
private fun cannedBackend(request: Request): String? {
    val url = request.url
    fun geminiAnswer(text: String) = buildJsonObject {
        putJsonArray("candidates") {
            add(buildJsonObject { putJsonObject("content") { putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } } })
        }
    }.toString()
    return when (url.host) {
        "generativelanguage.googleapis.com" -> {
            val prompt = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            geminiAnswer(if ("day-trip destinations" in prompt) SUGGESTIONS else FINDINGS)
        }
        "router.project-osrm.org" -> {
            if ("/table/" !in url.encodedPath) return null
            // one row from the home base: 25 minutes more, and 20 km further, per place
            val count = url.pathSegments.last().split(';').size
            buildJsonObject {
                put("code", "Ok")
                put("durations", buildJsonArray { add(buildJsonArray { repeat(count) { add(JsonPrimitive(it * 1500)) } }) })
                put("distances", buildJsonArray { add(buildJsonArray { repeat(count) { add(JsonPrimitive(it * 20000)) } }) })
            }.toString()
        }
        "commons.wikimedia.org" -> buildJsonObject {
            // around a place: four harbour views; by name: only the beach has a photo of its own
            val titles = when {
                url.queryParameter("generator") == "geosearch" -> (1..4).map { "Harbour view $it" }
                url.queryParameter("gsrsearch")?.startsWith("Valtos Beach nearcoord:15km,") == true -> listOf("Valtos Beach")
                else -> emptyList()
            }
            putJsonObject("query") {
                putJsonObject("pages") {
                    titles.forEachIndexed { i, title ->
                        putJsonObject((i + 1).toString()) {
                            put("title", "File:$title.jpg")
                            put("index", i + 1)
                            putJsonArray("imageinfo") {
                                add(
                                    buildJsonObject {
                                        put("thumburl", "https://example.invalid/${title.replace(' ', '_')}.jpg")
                                        put("descriptionurl", "https://commons.wikimedia.org/wiki/File:${title.replace(' ', '_')}.jpg")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }.toString()
        "en.wikipedia.org" -> buildJsonObject {
            val title = url.queryParameter("titles") ?: return null
            putJsonObject("query") {
                putJsonObject("pages") {
                    if (title == "Castle of Parga") {
                        putJsonObject("7") {
                            put("title", title)
                            put("fullurl", "https://en.wikipedia.org/wiki/Castle_of_Parga")
                            putJsonObject("thumbnail") { put("source", "https://example.invalid/castle.jpg") }
                        }
                    } else {
                        putJsonObject("-1") {
                            put("title", title)
                            put("missing", "")
                            put("fullurl", "https://en.wikipedia.org/wiki/${title.replace(' ', '_')}")
                        }
                    }
                }
            }
        }.toString()
        "photon.komoot.io" -> buildJsonObject {
            putJsonArray("features") {
                val towns = listOf(
                    Triple("Parga", "town", 20.4 to 39.285),
                    Triple("Ioannina", "city", 20.853 to 39.665),
                    Triple("Paramythia", "town", 20.507 to 39.47),
                    Triple("Kerkyra", "city", 19.92 to 39.62),
                    Triple("Plataria", "village", 20.273 to 39.45),
                )
                val wanted = url.queryParameterValues("osm_tag").map { it?.substringAfter(':') }
                towns.filter { it.second in wanted }.forEach { (name, kind, at) ->
                    add(
                        buildJsonObject {
                            putJsonObject("properties") {
                                put("osm_type", "N")
                                put("osm_id", 1000 + towns.indexOfFirst { it.first == name })
                                put("osm_value", kind)
                                put("name", name)
                            }
                            putJsonObject("geometry") { putJsonArray("coordinates") { add(JsonPrimitive(at.first)); add(JsonPrimitive(at.second)) } }
                        },
                    )
                }
            }
        }.toString()
        else -> null
    }
}

/** Walks through the app's longer flows against a canned backend. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-420dpi")
class FlowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun tripPlannerFromDatesToSavedTrip() {
        val services = testServices(tripStore(), geminiKey = "test-key", http = fakeHttpClient(::cannedBackend))
        val store = services.store
        val savedSyvota = store.data!!.destinations.first { it.name.startsWith("Syvota") }
        compose.setContent { VacationMapRoot(services) }

        compose.onNodeWithText("Trip").performClick()
        compose.onNodeWithText("New trip").performClick()
        compose.onNodeWithText("5 days").performClick()
        compose.onNodeWithText("Continue").performClick()

        // the drive step counts the suggestions within each limit once they have loaded
        waitForText("5 places")
        capture("12-wizard-drive-counts")
        compose.onNodeWithText("Up to 1½ hours").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Culture & history").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Good food").performClick()
        compose.onNodeWithText("Continue").performClick()

        // within 90 minutes: Parga (25), Sivota (50), Paxos (75, by ferry); Ioannina and Dodoni are further
        compose.onNodeWithText("Parga").assertExists()
        compose.onNodeWithText("Paxos").assertExists()
        compose.onNodeWithText("Ioannina").assertDoesNotExist()
        compose.onNodeWithText("In Places").assertExists()
        capture("16-wizard-suggestions")

        compose.onNodeWithText("Parga").performClick()
        compose.onNodeWithText("Sivota").performClick()
        compose.onNodeWithText("Find things to do (2)").performClick()
        // two places, researched one after the other with a pause in between
        waitForText("Found things to do", timeoutMs = 60_000)
        capture("17-wizard-research")
        compose.onNodeWithText("Review findings").performClick()
        // one card per sight, all ticked; its link is on the card, and there are no tabs
        compose.onNodeWithText("4 of 4 ticked to save").assertExists()
        compose.onNodeWithText("Castle of Parga").assertExists()
        compose.onNodeWithText("Walk up for the view over the bay.").assertExists()
        compose.onNodeWithText("en.wikipedia.org").assertExists()
        compose.onNodeWithText("visitgreece.gr").assertExists()
        compose.onNodeWithText("Photos", substring = true).assertDoesNotExist()
        capture("18-wizard-review")
        compose.onNodeWithText("Sea caves").performScrollTo().performClick()
        compose.onNodeWithText("3 of 4 ticked to save").assertExists()
        capture("18-wizard-review-unticked")
        compose.onNodeWithText("Next place").performClick()
        compose.onNodeWithText("Save 2 places & plan").performClick()
        capture("19-wizard-plan")
        compose.onNodeWithText("Open my trip").performClick()
        compose.waitForIdle()

        val data = store.data!!
        assertEquals("Family trip · Oct 2026", data.trip.name)
        assertEquals(5, tripDayCount(data.trip))
        assertEquals(90.0, data.trip.preferences?.maxDriveMinutes)
        assertEquals(5, data.trip.suggestions?.size)
        // "Sivota" is the saved Syvota: merged into it rather than added twice
        assertEquals(6, data.destinations.size)
        val syvota = data.destinations.first { it.id == savedSyvota.id }
        assertEquals("Syvota (Zavia)", syvota.name)
        assertTrue(syvota.favorite)
        // every card of the second place was left ticked: text, photo and link of each are saved
        assertEquals(savedSyvota.attractions.size + 4, syvota.attractions.size)
        assertEquals(savedSyvota.photos.size + 4, syvota.photos.size)
        val parga = data.destinations.first { it.name == "Parga" }
        // the unticked card took its text and photo with it
        assertEquals(
            listOf(
                "Castle of Parga: Walk up for the view over the bay.",
                "Valtos Beach: A long sandy beach behind the castle.",
                "Harbour tavernas: Eat grilled octopus on the harbour.",
            ),
            parga.attractions,
        )
        // the castle's photo is its article's, the beach's was found by name, the taverna's is from around Parga
        assertEquals(listOf("Castle of Parga", "Valtos Beach", "Harbour view 2"), parga.photos.map { it.caption })
        // a Wikipedia link is the article that was found; other links are the model's
        assertEquals(
            listOf("Castle of Parga" to "https://en.wikipedia.org/wiki/Castle_of_Parga", "Valtos Beach" to "https://www.visitgreece.gr/"),
            parga.links.map { it.label to it.url },
        )
        assertEquals(1500.0, parga.routeInfo?.durationSeconds)
        // both places are on a day, and not on the arrival day
        val planned = data.trip.plan.flatten()
        assertEquals(setOf(parga.id, syvota.id), planned.toSet())
        assertTrue(data.trip.plan.first().isEmpty())
        capture("20-trip-after-planner")
    }

    @Test
    fun researchEveryPlaceFromPlaces() {
        val store = tripStore()
        // two places are enough: they are researched a few seconds apart
        store.data!!.destinations.drop(2).forEach { store.removeDestination(it.id) }
        val (preveza, korfu) = store.data!!.destinations
        val services = testServices(store, geminiKey = "test-key", http = fakeHttpClient(::cannedBackend))
        compose.setContent { VacationMapRoot(services) }

        compose.onNodeWithText("Places").performClick()
        // the research button sits beside "Add destination"
        compose.onNodeWithText("Add destination").assertExists()
        capture("21-places-with-research")
        compose.onNodeWithContentDescription("Search with AI").performClick()
        // the counter is set in capitals
        waitForText("DESTINATION 1 OF 2", timeoutMs = 60_000)
        // its name heads the review (and is still in the Places list underneath)
        assertEquals(2, compose.onAllNodesWithText(preveza.name).fetchSemanticsNodes().size)
        // the canned Gemini answers the search-grounded request
        compose.onNodeWithText("Checked with Google Search").assertExists()
        compose.onNodeWithText("Castle of Parga").assertExists()
        compose.onNodeWithText("en.wikipedia.org").assertExists()
        capture("22-research-review")

        // a card's button saves its text, photo and link to the place, once
        compose.onNodeWithContentDescription("Add “Castle of Parga” to the destination").performScrollTo().performClick()
        compose.onNodeWithText("Added ✓").assertExists()
        val saved = store.data!!.destinations.first { it.id == preveza.id }
        assertEquals(preveza.attractions + "Castle of Parga: Walk up for the view over the bay.", saved.attractions)
        assertEquals(preveza.photos.size + 1, saved.photos.size)
        assertEquals("https://en.wikipedia.org/wiki/Castle_of_Parga", saved.links.last().url)
        capture("22-research-review-added")

        compose.onNodeWithText("Next →").performClick()
        compose.onNodeWithText("DESTINATION 2 OF 2").assertExists()
        assertEquals(2, compose.onAllNodesWithText(korfu.name).fetchSemanticsNodes().size)
        // closing the review returns to Places; the other place was left as it was
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("DESTINATION 2 OF 2").assertDoesNotExist()
        compose.onNodeWithText("Add destination").assertExists()
        assertEquals(korfu, store.data!!.destinations.first { it.id == korfu.id })
    }

    @Test
    fun townNamesLoadIntoTheCache() {
        val photon = io.github.corum86.vacationmap.net.PhotonService(fakeHttpClient(::cannedBackend))
        val center = io.github.corum86.vacationmap.model.LatLng(39.5, 20.3)
        val gained = runBlocking { photon.loadPlaces(center, 60.0, 9.0, Lang.En) }
        assertTrue(gained)
        assertEquals(setOf("Parga", "Ioannina", "Paramythia", "Kerkyra"), photon.cachedPlaces(Lang.En).map { it.name }.toSet())
        // the same view again is covered by what is cached: nothing to ask
        assertTrue(!photon.needsPlaces(center, 60.0, 9.0, Lang.En))
    }

    @Test
    fun pickOnMapOffersTownNames() {
        val services = testServices(tripStore(), http = fakeHttpClient(::cannedBackend))
        compose.setContent { VacationMapRoot(services) }
        compose.onNodeWithText("Places").performClick()
        compose.onNodeWithText("Add destination").performClick()
        compose.onNodeWithText("Pick on map").performClick()
        try {
            compose.waitUntil(8_000) { services.photon.cachedPlaces(Lang.En).isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            capture("debug-pick-timeout")
            throw e
        }
        capture("08-pick-on-map-towns")
        assertTrue(services.photon.cachedPlaces(Lang.En).any { it.name == "Parga" })
    }

    @Test
    fun exportRendersTheMapOffScreen() {
        val services = testServices(tripStore())
        var resolver: FontFamily.Resolver? = null
        compose.setContent { resolver = LocalFontFamilyResolver.current }
        compose.waitForIdle()
        val options = ExportOptions(aspect = AspectRatioId.Wide, quality = 2)
        val image = runBlocking {
            renderMapImage(
                services.store.data!!,
                RouteDisplayMode.Arrows,
                exportLayoutSize(options, Size(412f, 812f)),
                options.quality,
                services.tiles,
                assertNotNull(resolver).let { resolver!! },
                // Robolectric's native graphics must first be used on the test thread
                drawContext = kotlin.coroutines.EmptyCoroutineContext,
            )
        }
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        File("build/outputs/roborazzi").mkdirs()
        FileOutputStream("build/outputs/roborazzi/export-16x9-2x.png").use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun exportedDataReadsBackTheSame() {
        val store = tripStore()
        val json = io.github.corum86.vacationmap.data.encodeVacationData(store.data!!)
        val copy = loadedStore().apply { replaceAllData(AppJson.parseToJsonElement(json)) }
        assertEquals(store.data, copy.data)
    }
}
