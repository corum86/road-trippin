package io.github.corum86.vacationmap.map

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.corum86.vacationmap.logic.Point
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.bezierControlPoint
import io.github.corum86.vacationmap.logic.hashString
import io.github.corum86.vacationmap.logic.shortPlaceName
import io.github.corum86.vacationmap.logic.trimQuadraticBezier
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.RouteSource
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.net.MapPlace
import io.github.corum86.vacationmap.net.PlaceKind
import io.github.corum86.vacationmap.ui.theme.Poppins
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Everything the map shows on top of its tiles, drawn in one pass so it stays
// glued to the map while it moves. The same code draws the screen and the
// exported image. Sizes are the web app's CSS pixels, here dp.

/** What to draw on the map. */
internal class MapScene(
    /** null on a map with no home base yet: places only, nothing to connect them to */
    val home: MainLocation?,
    val destinations: List<Destination>,
    val selectedId: String?,
    val displayMode: RouteDisplayMode,
    val showLabels: Boolean,
    /** selectable town names (while picking a location) */
    val towns: List<MapPlace> = emptyList(),
)

// tile-less ground, close to the basemap's land colour
private val MAP_BACKGROUND = Color(0xFFEAEFE9)

// --- pins --------------------------------------------------------------------

internal const val PIN_MAIN = 34f
internal const val PIN_DESTINATION = 24f
internal const val PIN_SELECTED = 30f
private const val PIN_BORDER = 3f

// pins in the right 45% of the view get their label on the left so it never runs off the edge
private const val LABEL_FLIP_THRESHOLD = 0.55f

// --- arrows ------------------------------------------------------------------

// Arrow thickness scales with zoom: base widths apply at REF_ZOOM and grow or
// shrink by sqrt(2) per zoom step (half the map's own 2x-per-step rate — full
// geometric scaling overwhelms the fixed-size pins within a couple of steps),
// clamped so arrows stay visible far out and reasonable close in.
private const val REF_ZOOM = 7.0
private const val MIN_STROKE = 1.2f
private const val MAX_STROKE = 14f
private const val ARROW_WIDTH = 2.5f
private const val ARROW_WIDTH_SELECTED = 3.5f

// stop the arrow this far short of the destination point, so the arrowhead
// sits just before the pin instead of underneath it
private const val ARROW_TIP_GAP = 12.0

/** Android's blur radius for a Gaussian of standard deviation `sigma` (pixels). */
fun blurRadiusForSigma(sigma: Float): Float = max((sigma - 0.5f) / 0.57735f, 0.01f)

// --- tiles -------------------------------------------------------------------

internal fun DrawScope.drawTiles(state: MapState, tiles: TileProvider) {
    drawRect(MAP_BACKGROUND)
    val tz = tileZoomFor(state.zoom)
    val n = 1 shl tz
    val world = state.worldSize
    val tileSize = world / n
    val left = state.centerX * world - size.width / 2.0
    val top = state.centerY * world - size.height / 2.0
    val x0 = floor(left / tileSize).toInt()
    val x1 = floor((left + size.width) / tileSize).toInt()
    val y0 = max(0, floor(top / tileSize).toInt())
    val y1 = min(n - 1, floor((top + size.height) / tileSize).toInt())
    for (ty in y0..y1) {
        for (tx in x0..x1) {
            // whole-pixel edges, shared with the neighbours: no seams between tiles
            val dstLeft = (tx * tileSize - left).roundToInt()
            val dstTop = (ty * tileSize - top).roundToInt()
            val dstOffset = IntOffset(dstLeft, dstTop)
            val dstSize = IntSize(((tx + 1) * tileSize - left).roundToInt() - dstLeft, ((ty + 1) * tileSize - top).roundToInt() - dstTop)
            val x = Math.floorMod(tx, n)
            val tile = tiles.cached(TileKey(tz, x, ty))
            if (tile != null) {
                drawImage(tile, dstOffset = dstOffset, dstSize = dstSize)
                continue
            }
            // not loaded yet: the matching part of a coarser tile, if one is at hand
            for (up in 1..4) {
                if (tz - up < 0) break
                val parent = tiles.cached(TileKey(tz - up, x shr up, ty shr up)) ?: continue
                val part = TILE_PIXELS shr up
                val mask = (1 shl up) - 1
                drawImage(
                    parent,
                    srcOffset = IntOffset((x and mask) * part, (ty and mask) * part),
                    srcSize = IntSize(part, part),
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                )
                break
            }
        }
    }
}

// --- routes and arrows -------------------------------------------------------

/** Road geometry in Mercator coordinates, converted once per route rather than per frame. */
internal class RouteGeometryCache {
    private class Entry(val route: RouteInfo, val xy: DoubleArray)

    private val entries = HashMap<String, Entry>()

    fun mercator(id: String, route: RouteInfo): DoubleArray? {
        val geometry = route.geometry ?: return null
        entries[id]?.let { if (it.route === route) return it.xy }
        val xy = DoubleArray(geometry.size * 2)
        geometry.forEachIndexed { i, point ->
            xy[i * 2] = Mercator.x(point[1])
            xy[i * 2 + 1] = Mercator.y(point[0])
        }
        entries[id] = Entry(route, xy)
        return xy
    }
}

private fun Offset.toPoint() = Point(x.toDouble(), y.toDouble())

private fun DrawScope.drawRoutes(state: MapState, scene: MapScene, home: MainLocation, cache: RouteGeometryCache) {
    val u = density
    val homeAt = state.toScreen(home.location)
    // selected last so it draws on top
    val ordered = scene.destinations.sortedBy { it.id == scene.selectedId }
    for (dest in ordered) {
        val selected = dest.id == scene.selectedId
        val route = dest.routeInfo
        val xy = route?.let { cache.mercator(dest.id, it) }
        // still loading, or only a straight-line estimate: a dashed straight line
        val dashed = xy == null || route.source == RouteSource.StraightLineEstimate
        val path = Path()
        if (xy == null) {
            val end = state.toScreen(dest.location)
            path.moveTo(homeAt.x, homeAt.y)
            path.lineTo(end.x, end.y)
        } else {
            var last = Offset.Unspecified
            for (i in 0 until xy.size / 2) {
                val p = state.toScreen(xy[i * 2], xy[i * 2 + 1])
                if (i == 0) {
                    path.moveTo(p.x, p.y)
                } else if (i == xy.size / 2 - 1 || abs(p.x - last.x) + abs(p.y - last.y) >= 0.75f) {
                    path.lineTo(p.x, p.y)
                } else {
                    continue
                }
                last = p
            }
        }
        val color = if (selected) VmColors.Accent2 else VmColors.Accent
        val casing = if (selected) VmColors.Accent2Dark else VmColors.AccentCasing
        if (!dashed) {
            drawPath(
                path,
                casing,
                alpha = 0.9f,
                style = Stroke((if (selected) 7f else 6f) * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawPath(
            path,
            color,
            alpha = 0.9f,
            style = Stroke(
                width = (if (selected) 4f else 3.5f) * u,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f * u, 8f * u)) else null,
            ),
        )
    }
}

private class Arrow(val path: Path, val head: Path, val strokeWidth: Float, val selected: Boolean, val bounds: Rect)

private fun DrawScope.drawArrows(state: MapState, scene: MapScene, home: MainLocation) {
    val u = density
    val zoomScale = 2.0.pow((state.zoom - REF_ZOOM) / 2).toFloat()
    val origin = state.toScreen(home.location).toPoint()

    val arrows = scene.destinations.map { dest ->
        val selected = dest.id == scene.selectedId
        val end = state.toScreen(dest.location).toPoint()
        // per-destination bow side and strength, hashed from the id so the
        // curve shape is stable across launches and unrelated edits
        val h = hashString(dest.id)
        val side = if (h % 2 == 0L) 1 else -1
        val bow = side * (0.16 + (h % 7) * 0.015)
        val control = bezierControlPoint(origin, end, bow)
        // shape the bow from the full curve, then cut it short of the pin
        val trimmed = trimQuadraticBezier(origin, control, end, ARROW_TIP_GAP * u)
        val path = Path().apply {
            moveTo(origin.x.toFloat(), origin.y.toFloat())
            quadraticTo(
                trimmed.control.x.toFloat(),
                trimmed.control.y.toFloat(),
                trimmed.end.x.toFloat(),
                trimmed.end.y.toFloat(),
            )
        }
        val strokeWidth = ((if (selected) ARROW_WIDTH_SELECTED else ARROW_WIDTH) * zoomScale).coerceIn(MIN_STROKE, MAX_STROKE) * u

        // the arrowhead: a triangle 3.5 (selected 4) stroke widths across,
        // pointing along the curve's final direction
        val unit = (if (selected) 4f else 3.5f) / 10f * strokeWidth
        val angle = atan2(trimmed.end.y - trimmed.control.y, trimmed.end.x - trimmed.control.x)
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        fun corner(x: Float, y: Float) = Offset(
            trimmed.end.x.toFloat() + (x * cosA - y * sinA) * unit,
            trimmed.end.y.toFloat() + (x * sinA + y * cosA) * unit,
        )
        val a = corner(-8f, -5f)
        val b = corner(2f, 0f)
        val c = corner(-8f, 5f)
        val head = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        }
        Arrow(path, head, strokeWidth, selected, path.getBounds().inflate(strokeWidth * 3 + 8 * u))
    }
    // selected arrow last so its border and shadow draw over the others
    val ordered = arrows.sortedBy { it.selected }

    // with a selection, the other arrows recede so the highlighted one reads first
    fun alphaOf(arrow: Arrow) = if (arrow.selected) 1f else if (scene.selectedId != null) 0.55f else 0.85f

    // one soft shadow under all of them
    val shadowDy = min(1.5f * zoomScale, 4f) * u
    val shadowBlur = BlurMaskFilter(blurRadiusForSigma(min(1.5f * zoomScale, 5f) * u), BlurMaskFilter.Blur.NORMAL)
    drawIntoCanvas { canvas ->
        for (arrow in ordered) {
            val paint = AndroidPaint().apply {
                isAntiAlias = true
                style = AndroidPaint.Style.STROKE
                strokeCap = AndroidPaint.Cap.ROUND
                strokeWidth = arrow.strokeWidth + 2 * u
                color = Color.Black.copy(alpha = 0.35f * alphaOf(arrow)).toArgb()
                maskFilter = shadowBlur
            }
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(0f, shadowDy)
            canvas.nativeCanvas.drawPath(arrow.path.asAndroidPath(), paint)
            canvas.nativeCanvas.restore()
        }
    }

    for (arrow in ordered) {
        val color = if (arrow.selected) VmColors.Accent2 else VmColors.Accent
        val casing = if (arrow.selected) VmColors.Accent2Dark else VmColors.AccentCasing
        val alpha = alphaOf(arrow)
        drawIntoCanvas { canvas ->
            // a layer, so casing, stroke and head fade as one shape
            if (alpha < 1f) canvas.saveLayer(arrow.bounds, Paint().apply { this.alpha = alpha })
            // casing: 1dp border on each side of the coloured stroke
            drawPath(arrow.path, casing, style = Stroke(arrow.strokeWidth + 2 * u, cap = StrokeCap.Round))
            drawPath(arrow.path, color, style = Stroke(arrow.strokeWidth, cap = StrokeCap.Round))
            drawPath(arrow.head, color)
            drawPath(arrow.head, casing, style = Stroke(0.6f * arrow.strokeWidth * (if (arrow.selected) 0.4f else 0.35f)))
            if (alpha < 1f) canvas.restore()
        }
    }
}

// --- pins and labels ---------------------------------------------------------

internal enum class PinKind(val size: Float) {
    Main(PIN_MAIN),
    Destination(PIN_DESTINATION),
    Selected(PIN_SELECTED),
}

internal class Pin(val id: String?, val kind: PinKind, val tip: Offset, val name: String, val visited: Boolean)

/** Every pin with its screen position, in drawing order (the ones lower on screen on top). */
internal fun pinsOf(state: MapState, scene: MapScene): List<Pin> {
    val pins = ArrayList<Pin>(scene.destinations.size + 1)
    scene.home?.let { pins += Pin(null, PinKind.Main, state.toScreen(it.location), shortPlaceName(it.name), false) }
    for (dest in scene.destinations) {
        val kind = if (dest.id == scene.selectedId) PinKind.Selected else PinKind.Destination
        pins += Pin(dest.id, kind, state.toScreen(dest.location), dest.name, dest.status == TripStatus.Visited)
    }
    return pins.sortedBy { it.tip.y }
}

/** Centre of the pin's round head: the pin is a teardrop whose tip sits on the coordinate. */
internal fun pinHeadCenter(pin: Pin, density: Float): Offset = Offset(pin.tip.x, pin.tip.y - 0.7f * pin.kind.size * density)

private fun starPath(center: Offset, outer: Float): Path {
    val inner = outer * 0.4f
    return Path().apply {
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / 5
            val x = center.x + (radius * cos(angle)).toFloat()
            val y = center.y + (radius * sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

private fun DrawScope.drawPin(pin: Pin) {
    val u = density
    val z = pin.kind.size * u
    val center = pinHeadCenter(pin, u)
    val box = Rect(center.x - z / 2, center.y - z / 2, center.x + z / 2, center.y + z / 2)
    val colors = when (pin.kind) {
        PinKind.Main -> listOf(VmColors.HomeGradientStart, VmColors.AccentSoft)
        PinKind.Destination -> listOf(Color(0xFFFF8F66), VmColors.Accent)
        PinKind.Selected -> listOf(VmColors.Accent2Bright, VmColors.Accent2)
    }

    // a square with three round corners, turned so the sharp one points down
    fun teardrop(rect: Rect): Path {
        val r = CornerRadius(rect.width / 2)
        return Path().apply {
            addRoundRect(RoundRect(rect, topLeft = r, topRight = r, bottomRight = r, bottomLeft = CornerRadius.Zero))
        }
    }

    rotate(-45f, center) {
        val outer = teardrop(box)
        drawIntoCanvas { canvas ->
            val main = pin.kind == PinKind.Main
            val paint = AndroidPaint().apply {
                isAntiAlias = true
                color = Color.White.toArgb()
                setShadowLayer(
                    blurRadiusForSigma((if (main) 4f else 3f) * u),
                    0f,
                    (if (main) 3f else 2f) * u,
                    Color.Black.copy(alpha = 0.35f).toArgb(),
                )
            }
            canvas.nativeCanvas.drawPath(outer.asAndroidPath(), paint)
        }
        val inner = box.deflate(PIN_BORDER * u)
        drawPath(teardrop(inner), Brush.linearGradient(colors, start = inner.topLeft, end = inner.bottomRight))
    }

    if (pin.kind == PinKind.Main) {
        drawPath(starPath(center.copy(y = center.y + u), 6.4f * u), Color.Black.copy(alpha = 0.18f))
        drawPath(starPath(center, 6.4f * u), Color.White)
    } else if (pin.visited) {
        // a white check, upright inside the turned pin
        val s = 0.6f * (z - 2 * PIN_BORDER * u) / 24f
        val origin = Offset(center.x - 12f * s, center.y - 12f * s)
        val check = Path().apply {
            moveTo(origin.x + 5f * s, origin.y + 12.5f * s)
            lineTo(origin.x + 9.5f * s, origin.y + 17f * s)
            lineTo(origin.x + 19f * s, origin.y + 7.5f * s)
        }
        drawPath(check, Color.White, style = Stroke(3.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun Density.labelStyle(weight: FontWeight, color: Color): TextStyle = TextStyle(
    fontFamily = Poppins,
    fontWeight = weight,
    fontSize = 11.dp.toSp(),
    lineHeight = (11 * 1.45f).dp.toSp(),
    color = color,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private fun Density.measureLabel(measurer: TextMeasurer, text: String, style: TextStyle): TextLayoutResult =
    measurer.measure(
        AnnotatedString(text),
        style,
        maxLines = 1,
        softWrap = false,
        density = this,
        layoutDirection = LayoutDirection.Ltr,
    )

private fun DrawScope.drawPill(rect: Rect, radius: Float, fill: Color, border: Color? = null) {
    val u = density
    drawIntoCanvas { canvas ->
        val paint = AndroidPaint().apply {
            isAntiAlias = true
            color = Color.Black.copy(alpha = 0.15f).toArgb()
            maskFilter = BlurMaskFilter(blurRadiusForSigma(1.5f * u), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawRoundRect(rect.left, rect.top + u, rect.right, rect.bottom + u, radius, radius, paint)
    }
    drawRoundRect(fill, rect.topLeft, rect.size, CornerRadius(radius))
    if (border != null) {
        val half = u / 2
        drawRoundRect(
            border,
            Offset(rect.left + half, rect.top + half),
            Size(rect.width - u, rect.height - u),
            CornerRadius(radius - half),
            style = Stroke(u),
        )
    }
}

/** Permanent name pill beside a pin. */
private fun DrawScope.drawPinLabel(pin: Pin, measurer: TextMeasurer) {
    val u = density
    val layout = measureLabel(measurer, pin.name, labelStyle(FontWeight.SemiBold, VmColors.Text))
    val width = layout.size.width + 16 * u
    val height = layout.size.height + 6 * u
    // distance from the pin's centre line to the label's near edge
    val gap = (pin.kind.size / 2 + 4) * u
    val onLeft = pin.tip.x > size.width * LABEL_FLIP_THRESHOLD
    val left = if (onLeft) pin.tip.x - gap - width else pin.tip.x + gap
    val top = pinHeadCenter(pin, u).y - height / 2
    val rect = Rect(left, top, left + width, top + height)
    drawPill(rect, 10 * u, Color.White.copy(alpha = 0.95f))
    drawText(layout, topLeft = Offset(rect.left + 8 * u, rect.top + 3 * u))
}

// --- town names ----------------------------------------------------------------

// when names would overlap, the bigger settlement keeps its spot
private fun kindRank(kind: PlaceKind): Int = kind.ordinal

private fun townWeight(kind: PlaceKind): FontWeight = when (kind) {
    PlaceKind.City -> FontWeight.Bold
    PlaceKind.Town -> FontWeight.SemiBold
    PlaceKind.Village -> FontWeight.Medium
}

/** A town name's pill on screen (centred on the town). */
internal fun Density.townPillRect(state: MapState, place: MapPlace, measurer: TextMeasurer): Pair<Rect, TextLayoutResult> {
    val u = density
    val layout = measureLabel(measurer, place.name, labelStyle(townWeight(place.kind), VmColors.Accent2Dark))
    val width = layout.size.width + 20 * u
    val height = layout.size.height + 8 * u
    val at = state.toScreen(place.location)
    return Rect(at.x - width / 2, at.y - height / 2, at.x + width / 2, at.y + height / 2) to layout
}

/**
 * The towns to offer for the current view: the ones of a kind that fits the
 * zoom, inside (or just outside) the viewport, minus those whose name would
 * overlap a bigger or earlier one.
 */
internal fun Density.visibleTowns(
    state: MapState,
    size: Size,
    places: List<MapPlace>,
    kinds: List<PlaceKind>,
    measurer: TextMeasurer,
): List<MapPlace> {
    val u = density
    val marginX = size.width * 0.25f
    val marginY = size.height * 0.25f
    val view = Rect(-marginX, -marginY, size.width + marginX, size.height + marginY)
    val taken = ArrayList<Rect>()
    return places
        .filter { it.kind in kinds && view.contains(state.toScreen(it.location)) }
        .sortedWith(compareBy<MapPlace> { kindRank(it.kind) }.thenBy { it.id })
        .filter { place ->
            // the pill plus the gap kept between neighbours
            val box = townPillRect(state, place, measurer).first.inflate(u)
            if (taken.any { it.overlaps(box) }) return@filter false
            taken += box
            true
        }
}

// --- everything ----------------------------------------------------------------

internal fun DrawScope.drawMapOverlay(state: MapState, scene: MapScene, measurer: TextMeasurer, routes: RouteGeometryCache) {
    val home = scene.home
    if (home != null && scene.displayMode == RouteDisplayMode.Routes) drawRoutes(state, scene, home, routes)
    val pins = pinsOf(state, scene)
    pins.forEach { drawPin(it) }
    // arrows go over the pins: they start on the home pin and stop short of the others
    if (home != null && scene.displayMode == RouteDisplayMode.Arrows) drawArrows(state, scene, home)
    for (place in scene.towns) {
        val (rect, layout) = townPillRect(state, place, measurer)
        drawPill(rect, 12 * density, Color.White.copy(alpha = 0.96f), border = VmColors.Accent2)
        drawText(layout, topLeft = Offset(rect.left + 10 * density, rect.top + 4 * density))
    }
    if (scene.showLabels) pins.forEach { drawPinLabel(it, measurer) }
}

/** "© OpenStreetMap contributors" in the bottom-right corner (for exported images). */
internal fun DrawScope.drawAttribution(measurer: TextMeasurer) {
    val u = density
    val style = TextStyle(
        fontFamily = Poppins,
        fontSize = 11.dp.toSp(),
        color = Color(0xFF333333),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val layout = measureLabel(measurer, OSM_ATTRIBUTION, style)
    val width = layout.size.width + 12 * u
    val height = layout.size.height + 6 * u
    val topLeft = Offset(size.width - width, size.height - height)
    drawRect(Color.White.copy(alpha = 0.8f), topLeft, Size(width, height))
    translate(topLeft.x + 6 * u, topLeft.y + 3 * u) { drawText(layout) }
}

const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
