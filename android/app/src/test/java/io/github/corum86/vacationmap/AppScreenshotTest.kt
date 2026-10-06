package io.github.corum86.vacationmap

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.corum86.vacationmap.data.AppServices
import io.github.corum86.vacationmap.data.MapDataStore
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.ui.VacationMapRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The seed data with a trip under way: dates, a plan, two visited places, a favourite. */
fun tripStore(): MapDataStore = loadedStore().apply {
    val byName = data!!.destinations.associateBy { it.name.substringBefore(' ') }
    val syvota = byName.getValue("Syvota").id
    val korfu = byName.getValue("Korfu").id
    val nikopolis = byName.getValue("Nikopolis").id
    val preveza = byName.getValue("Preveza").id
    setTripName("Epirus summer")
    setTripDates("2026-07-13", "2026-07-18")
    setStatus(syvota, TripStatus.Visited)
    setRating(syvota, 4)
    updateVisit(syvota) { it.copy(visitedOn = "2026-07-14", note = "Turquoise water, boat to the islets. Go early.") }
    toggleFavorite(syvota)
    setStatus(korfu, TripStatus.Visited)
    toggleTripDay(syvota, 1)
    toggleTripDay(nikopolis, 2)
    toggleTripDay(preveza, 2)
    toggleTripDay(syvota, 4)
    updateDestination(nikopolis) { it.copy(attractions = listOf("Roman odeon and walls", "Archaeological museum", "Monument of Augustus")) }
}

/**
 * Renders the app's screens to build/outputs/roborazzi for a look at the
 * layout: the closest thing to running it that needs no device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-420dpi")
class AppScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun launch(services: AppServices = testServices(tripStore())): AppServices {
        compose.setContent { VacationMapRoot(services) }
        compose.waitForIdle()
        return services
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasContentDescription("").not()).performClick()

    @Test
    fun mapTab() {
        val services = launch()
        capture("01-map")
        services.store.setSelectedDestination(services.store.data!!.destinations.first { it.name.startsWith("Syvota") }.id)
        capture("02-map-selected")
    }

    @Test
    fun placesTab() {
        launch()
        compose.onNodeWithText("Places").performClick()
        capture("03-places")
    }

    @Test
    fun tripTab() {
        launch()
        compose.onNodeWithText("Trip").performClick()
        capture("04-trip")
        compose.onNodeWithText("13–18 Jul 2026").performClick()
        capture("09-trip-dates")
    }

    @Test
    fun tripSheets() {
        launch()
        compose.onNodeWithText("Trip").performClick()
        compose.onNodeWithContentDescription("Add to day 2").performClick()
        capture("10-add-to-day")
        compose.onNodeWithText("Done").performClick()
        compose.onAllNodesWithContentDescription("Swap").onFirst().performClick()
        capture("21-trip-swap")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Re-plan").performClick()
        capture("22-trip-replan")
    }

    @Test
    fun settingsTab() {
        launch()
        compose.onNodeWithText("Settings").performClick()
        capture("05-settings")
    }

    @Test
    fun settingsInGreek() {
        launch(testServices(tripStore(), lang = Lang.El))
        compose.onNodeWithText("Ρυθμίσεις").performClick()
        capture("05-settings-el")
    }

    @Test
    fun detailAndForm() {
        launch()
        compose.onNodeWithText("Places").performClick()
        compose.onNodeWithText("Syvota (Zavia)").performClick()
        capture("06-detail")
        compose.onNodeWithText("Edit").performScrollTo()
        capture("06-detail-bottom")
        compose.onNodeWithText("Edit").performClick()
        capture("07-form")
        compose.onNodeWithText("Pick on map").performClick()
        capture("08-pick-on-map")
    }

    @Test
    fun emptyApp() {
        val services = launch(testServices(loadedStore().apply { clearAllData() }))
        capture("30-empty-map")
        compose.onNodeWithText("Trip").performClick()
        capture("23-trip-empty")
        compose.onNodeWithText("Settings").performClick()
        capture("31-empty-settings")
        check(services.store.data!!.mainLocation == null)
    }

    @Test
    fun wizardFirstSteps() {
        launch()
        compose.onNodeWithText("Trip").performClick()
        compose.onNodeWithText("New trip").performClick()
        capture("11-wizard-dates")
        compose.onNodeWithText("1 week").performClick()
        capture("11-wizard-dates-picked")
        compose.onNodeWithText("Continue").performClick()
        capture("12-wizard-drive")
        compose.onNodeWithText("Up to 1 hour").performClick()
        compose.onNodeWithText("Continue").performClick()
        capture("13-wizard-group")
        compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Relaxed").performClick()
        capture("14-wizard-style")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Beach").performClick()
        compose.onNodeWithText("Comfortable").performClick()
        capture("15-wizard-extras")
        compose.onNodeWithText("Continue").performClick()
        capture("16-wizard-suggestions-unavailable")
        check(compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty())
    }
}
