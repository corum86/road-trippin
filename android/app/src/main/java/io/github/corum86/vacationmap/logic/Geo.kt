package io.github.corum86.vacationmap.logic

import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.RouteSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6371000.0
private const val ASSUMED_FALLBACK_SPEED_KMH = 60.0

fun haversineDistanceMeters(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
    val c = 2 * asin(min(1.0, sqrt(h)))
    return EARTH_RADIUS_METERS * c
}

/** Now as an ISO-8601 instant, for `RouteInfo.fetchedAt`. */
fun nowIsoInstant(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** Distance as the crow flies and the time it takes at 60 km/h, when routing is unavailable. */
fun estimateFromStraightLine(from: LatLng, to: LatLng): RouteInfo {
    val distanceMeters = haversineDistanceMeters(from, to)
    val durationSeconds = (distanceMeters / 1000 / ASSUMED_FALLBACK_SPEED_KMH) * 3600
    return RouteInfo(
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        source = RouteSource.StraightLineEstimate,
        fetchedAt = nowIsoInstant(),
        geometry = listOf(listOf(from.lat, from.lng), listOf(to.lat, to.lng)),
    )
}

data class Point(val x: Double, val y: Double)

/**
 * Small deterministic string hash (djb2) for stable per-id variation. Matches
 * the web app's 32-bit arithmetic, so a destination's arrow bows the same way
 * in both apps.
 */
fun hashString(s: String): Long {
    var h = 5381
    for (ch in s) h = (h shl 5) + h + ch.code
    return abs(h.toLong())
}

/**
 * Control point for a quadratic bezier curve from `from` to `to`, offset
 * perpendicular to the midpoint so the curve bows outward instead of
 * drawing a straight line. A negative `bow` bends to the opposite side.
 */
fun bezierControlPoint(from: Point, to: Point, bow: Double = 0.22): Point {
    val mx = (from.x + to.x) / 2
    val my = (from.y + to.y) / 2
    val dx = to.x - from.x
    val dy = to.y - from.y
    // perpendicular vector
    val px = -dy
    val py = dx
    return Point(mx + px * bow, my + py * bow)
}

data class TrimmedCurve(val control: Point, val end: Point)

/**
 * Shorten a quadratic bezier so it ends roughly `gap` before its endpoint
 * (de Casteljau subdivision at t). Keeps at least half the curve so very
 * short arrows don't collapse. Returns the trimmed control and end points;
 * the start point is unchanged.
 */
fun trimQuadraticBezier(from: Point, control: Point, to: Point, gap: Double): TrimmedCurve {
    val dist = hypot(to.x - from.x, to.y - from.y)
    val t = max(0.5, 1 - gap / max(dist, 1.0))
    fun at(a: Double, b: Double, c: Double) = (1 - t) * (1 - t) * a + 2 * (1 - t) * t * b + t * t * c
    return TrimmedCurve(
        control = Point(from.x + (control.x - from.x) * t, from.y + (control.y - from.y) * t),
        end = Point(at(from.x, control.x, to.x), at(from.y, control.y, to.y)),
    )
}

/** Every pin on the map: the home base (when there is one), then the places. */
fun mapPoints(home: MainLocation?, destinations: List<Destination>): List<LatLng> =
    listOfNotNull(home?.location) + destinations.map { it.location }

/** "Home Base — Igoumenitsa" → "Igoumenitsa" */
fun shortPlaceName(name: String): String = Regex("^.*—\\s*").replaceFirst(name, "").ifEmpty { name }
