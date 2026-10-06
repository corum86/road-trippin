package io.github.corum86.vacationmap.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.github.corum86.vacationmap.logic.haversineDistanceMeters
import io.github.corum86.vacationmap.model.LatLng
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Web Mercator, with the whole world mapped onto the unit square (x right, y down). */
object Mercator {
    private const val MAX_LATITUDE = 85.0511287798

    fun x(lng: Double): Double = (lng + 180.0) / 360.0

    fun y(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun lng(x: Double): Double = x * 360.0 - 180.0

    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
}

/** Room (in dp) kept clear of overlaid UI when fitting the view. */
data class MapPadding(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f)

const val MIN_ZOOM = 2.0
const val MAX_ZOOM = 18.0

// tiles are 256px squares; the world is one tile at zoom 0
private const val TILE_UNITS = 256.0

/**
 * The map's camera: where it looks and how far in. Zoom has Leaflet's
 * meaning in layout units — at zoom z the world is 256·2^z dp wide — so zoom
 * levels (fit limits, label thresholds, arrow scaling) match the web app's.
 *
 * Screen positions are in pixels; `density` converts from dp.
 */
@Stable
class MapState(center: LatLng, zoom: Double = 7.0) {
    /** camera centre in unit-square Mercator coordinates */
    var centerX by mutableDoubleStateOf(Mercator.x(center.lng))
        private set
    var centerY by mutableDoubleStateOf(Mercator.y(center.lat))
        private set
    var zoom by mutableDoubleStateOf(zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        private set

    /** viewport in pixels; zero until laid out */
    var size by mutableStateOf(IntSize.Zero)

    /** pixels per dp */
    var density by mutableFloatStateOf(1f)

    private val animation = Animatable(0f)

    /** Width of the whole world in pixels at the current zoom. */
    val worldSize: Double get() = TILE_UNITS * density * 2.0.pow(zoom)

    val center: LatLng get() = LatLng(Mercator.lat(centerY), Mercator.lng(centerX))

    private fun setCamera(x: Double, y: Double, z: Double) {
        val nextZoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoom = nextZoom
        // the world repeats sideways but ends at the poles: keep the view on the map
        val halfHeight = if (size.height > 0) (size.height / 2.0) / (TILE_UNITS * density * 2.0.pow(nextZoom)) else 0.0
        centerY = if (halfHeight >= 0.5) 0.5 else y.coerceIn(halfHeight, 1 - halfHeight)
        centerX = x - floor(x)
    }

    fun jumpTo(center: LatLng, zoom: Double = this.zoom) = setCamera(Mercator.x(center.lng), Mercator.y(center.lat), zoom)

    /** Where a coordinate falls on screen, on the copy of the world nearest the centre. */
    fun toScreen(location: LatLng): Offset = toScreen(Mercator.x(location.lng), Mercator.y(location.lat))

    fun toScreen(mercatorX: Double, mercatorY: Double): Offset {
        var dx = mercatorX - centerX
        dx -= Math.rint(dx)
        val world = worldSize
        return Offset((size.width / 2.0 + dx * world).toFloat(), (size.height / 2.0 + (mercatorY - centerY) * world).toFloat())
    }

    fun fromScreen(point: Offset): LatLng {
        val world = worldSize
        val x = centerX + (point.x - size.width / 2.0) / world
        val y = centerY + (point.y - size.height / 2.0) / world
        return LatLng(Mercator.lat(y.coerceIn(0.0, 1.0)), Mercator.lng(x - floor(x)))
    }

    /** Move the map by a drag of `delta` pixels. */
    fun panBy(delta: Offset) {
        val world = worldSize
        setCamera(centerX - delta.x / world, centerY - delta.y / world, zoom)
    }

    /** Scale the map by `factor`, keeping the point under `focus` in place. */
    fun zoomBy(factor: Float, focus: Offset) {
        if (factor <= 0f || factor == 1f) return
        val next = (zoom + log2(factor.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val before = worldSize
        val after = TILE_UNITS * density * 2.0.pow(next)
        val fx = focus.x - size.width / 2.0
        val fy = focus.y - size.height / 2.0
        // the map point under the focus must land on the focus again
        setCamera(centerX + fx / before - fx / after, centerY + fy / before - fy / after, next)
    }

    /** Stop a fit or pan animation in flight (the user touched the map). */
    suspend fun stopAnimation() = animation.stop()

    private suspend fun moveTo(x: Double, y: Double, z: Double, animate: Boolean) {
        if (!animate || size == IntSize.Zero) {
            animation.stop()
            setCamera(x, y, z)
            return
        }
        val fromX = centerX
        val fromY = centerY
        val fromZoom = zoom
        // the short way round the world
        var dx = x - fromX
        dx -= Math.rint(dx)
        animation.snapTo(0f)
        animation.animateTo(1f, tween(durationMillis = 300, easing = EMPHASIZED)) {
            val t = value.toDouble()
            setCamera(fromX + dx * t, fromY + (y - fromY) * t, fromZoom + (z - fromZoom) * t)
        }
    }

    /** Zoom in (or out) by `delta` levels around the point under `focus`. */
    suspend fun animateZoomBy(delta: Double, focus: Offset) {
        val from = zoom
        val to = (from + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (to == from) return
        animation.snapTo(0f)
        animation.animateTo(1f, tween(durationMillis = 250, easing = EMPHASIZED)) {
            val target = from + (to - from) * value
            zoomBy(2.0.pow(target - zoom).toFloat(), focus)
        }
    }

    /** The camera that shows all `points` inside the padded viewport (like Leaflet's fitBounds). */
    internal fun cameraFor(points: List<LatLng>, padding: MapPadding, maxZoom: Double): Triple<Double, Double, Double>? {
        if (points.isEmpty() || size == IntSize.Zero) return null
        val xs = points.map { Mercator.x(it.lng) }
        val ys = points.map { Mercator.y(it.lat) }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val availableWidth = max(size.width / density - padding.left - padding.right, 1f)
        val availableHeight = max(size.height / density - padding.top - padding.bottom, 1f)
        val spanX = max(maxX - minX, 1e-12)
        val spanY = max(maxY - minY, 1e-12)
        val exact = log2(min(availableWidth / (TILE_UNITS * spanX), availableHeight / (TILE_UNITS * spanY)))
        // whole zoom levels, like the web map: tiles show at their natural size
        val fitZoom = floor(exact + 1e-9).coerceIn(MIN_ZOOM, min(maxZoom, MAX_ZOOM))
        val world = TILE_UNITS * 2.0.pow(fitZoom)
        // shift the centre so the bounds sit in the middle of the unpadded area
        val x = (minX + maxX) / 2 + (padding.right - padding.left) / 2 / world
        val y = (minY + maxY) / 2 + (padding.bottom - padding.top) / 2 / world
        return Triple(x, y, fitZoom)
    }

    /** Show all `points`, leaving `padding` clear for overlaid UI. */
    suspend fun fitToPoints(points: List<LatLng>, padding: MapPadding, maxZoom: Double = 12.0, animate: Boolean = false) {
        val (x, y, z) = cameraFor(points, padding, maxZoom) ?: return
        moveTo(x, y, z, animate)
    }

    /** Pan just enough to bring `point` inside the padded viewport. */
    suspend fun panInside(point: LatLng, padding: MapPadding, animate: Boolean = true) {
        if (size == IntSize.Zero) return
        val at = toScreen(point)
        val left = padding.left * density
        val right = size.width - padding.right * density
        val top = padding.top * density
        val bottom = size.height - padding.bottom * density
        val dx = if (at.x < left) at.x - left else if (at.x > right) at.x - right else 0f
        val dy = if (at.y < top) at.y - top else if (at.y > bottom) at.y - bottom else 0f
        if (dx == 0f && dy == 0f) return
        val world = worldSize
        moveTo(centerX + dx / world, centerY + dy / world, zoom, animate)
    }

    /** Distance in km from the centre to the viewport's corner, for "what's in view" queries. */
    fun viewRadiusKm(): Double {
        val corner = fromScreen(Offset(size.width.toFloat(), 0f))
        return haversineDistanceMeters(center, corner) / 1000
    }

    private companion object {
        // Material 3 "emphasized" easing, as the web app uses for its transitions
        val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}
