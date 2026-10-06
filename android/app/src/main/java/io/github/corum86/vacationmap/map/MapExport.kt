package io.github.corum86.vacationmap.map

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import io.github.corum86.vacationmap.logic.ExportOptions
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.exportRatio
import io.github.corum86.vacationmap.logic.mapPoints
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.VacationMapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Layout width of the exported map for fixed ratios; × quality gives the
// image width (2x → 1920 pixels wide)
private const val EXPORT_BASE_WIDTH = 960f
private val EXPORT_PADDING = MapPadding(top = 72f, right = 40f, bottom = 40f, left = 40f)

// never hang the export on a stuck tile; draw what we have
private const val TILES_TIMEOUT_MS = 10_000L

// keep even "4x portrait" within what a bitmap can reasonably hold
private const val MAX_IMAGE_SIDE = 8192f

/** The layout size (dp) of an export: fixed ratios are 960 wide, "free" is what the Map tab shows. */
fun exportLayoutSize(options: ExportOptions, freeSize: Size): Size {
    val ratio = exportRatio(options.aspect, options.orientation) ?: return freeSize
    return Size(EXPORT_BASE_WIDTH, (EXPORT_BASE_WIDTH / ratio).roundToInt().toFloat())
}

/**
 * Draw the map — tiles, connections, pins, labels and the OSM attribution —
 * into an image of `layoutSize` dp at `quality` pixels per dp, fitted to the
 * home base and all destinations. Phones have no map on screen while Settings
 * is open, so this renders one from scratch, waiting for its tiles first.
 */
suspend fun renderMapImage(
    data: VacationMapData,
    displayMode: RouteDisplayMode,
    layoutSize: Size,
    quality: Int,
    tiles: TileProvider,
    fontFamilyResolver: FontFamily.Resolver,
    /** where the drawing runs: off the main thread, so a large image doesn't freeze the UI */
    drawContext: CoroutineContext = Dispatchers.Default,
): Bitmap {
    val scale = min(quality.toFloat(), MAX_IMAGE_SIDE / max(layoutSize.width, layoutSize.height))
    val width = max((layoutSize.width * scale).roundToInt(), 1)
    val height = max((layoutSize.height * scale).roundToInt(), 1)
    val density = Density(scale)

    val state = MapState(LatLng(30.0, 10.0), 2.0)
    state.density = scale
    state.size = IntSize(width, height)
    state.fitToPoints(mapPoints(data.mainLocation, data.destinations), EXPORT_PADDING)

    val pixelSize = Size(width.toFloat(), height.toFloat())
    withTimeoutOrNull(TILES_TIMEOUT_MS) {
        coroutineScope { visibleTiles(state, pixelSize).map { async { tiles.load(it) } }.awaitAll() }
    }

    return withContext(drawContext) {
        val bitmap = createBitmap(width, height)
        val measurer = TextMeasurer(fontFamilyResolver, density, LayoutDirection.Ltr, cacheSize = 0)
        val scene = MapScene(
            home = data.mainLocation,
            destinations = data.destinations,
            selectedId = null,
            displayMode = displayMode,
            showLabels = true,
        )
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), pixelSize) {
            drawTiles(state, tiles)
            drawMapOverlay(state, scene, measurer, RouteGeometryCache())
            drawAttribution(measurer)
        }
        bitmap
    }
}
