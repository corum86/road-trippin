package io.github.corum86.vacationmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.mapPoints
import io.github.corum86.vacationmap.map.FitToPoints
import io.github.corum86.vacationmap.map.MapPadding
import io.github.corum86.vacationmap.map.VacationMap
import io.github.corum86.vacationmap.map.rememberMapState
import io.github.corum86.vacationmap.ui.theme.VacationMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-420dpi")
class MapScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, mode: RouteDisplayMode, select: Boolean) {
        val data = loadedStore().data!!
        val tiles = TestTileProvider()
        compose.setContent {
            VacationMapTheme {
                val state = rememberMapState(data)
                Box(Modifier.fillMaxSize()) {
                    VacationMap(
                        data = data,
                        selectedDestinationId = if (select) data.destinations[1].id else null,
                        displayMode = mode,
                        state = state,
                        tiles = tiles,
                    )
                    FitToPoints(
                        state,
                        mapPoints(data.mainLocation, data.destinations),
                        MapPadding(top = 100f, right = 40f, bottom = 150f, left = 40f),
                        fitKey = "all",
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun arrows() = capture("map-arrows", RouteDisplayMode.Arrows, select = false)

    @Test fun arrowsSelected() = capture("map-arrows-selected", RouteDisplayMode.Arrows, select = true)

    @Test fun routes() = capture("map-routes", RouteDisplayMode.Routes, select = true)
}
