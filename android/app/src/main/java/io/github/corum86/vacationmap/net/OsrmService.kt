package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.logic.estimateFromStraightLine
import io.github.corum86.vacationmap.logic.nowIsoInstant
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.RouteSource
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

private const val OSRM_ROUTE_URL = "https://router.project-osrm.org/route/v1/driving"
private const val OSRM_TABLE_URL = "https://router.project-osrm.org/table/v1/driving"

data class DriveFromHome(
    val minutes: Double,
    val km: Double,
    /** straight-line estimate because routing failed for this point */
    val estimated: Boolean,
)

/** Driving routes from the free OSRM demo server, with a straight-line fallback. */
class OsrmService(private val client: OkHttpClient) {

    /** The road route between two points; never fails — a straight-line estimate stands in. */
    suspend fun fetchRoute(from: LatLng, to: LatLng): RouteInfo {
        val url = "$OSRM_ROUTE_URL/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson"
        return try {
            val json = client.getJson(url.toHttpUrl())
            val route = json["routes"][0]
            if (json["code"].string != "Ok" || route == null) error("OSRM: no route found")
            RouteInfo(
                distanceMeters = route["distance"].number ?: error("OSRM: no distance"),
                durationSeconds = route["duration"].number ?: error("OSRM: no duration"),
                source = RouteSource.Osrm,
                fetchedAt = nowIsoInstant(),
                // GeoJSON is [lng, lat]; the app stores [lat, lng]
                geometry = route["geometry"]["coordinates"].items
                    .mapNotNull { pair -> listOf(pair[1].number ?: return@mapNotNull null, pair[0].number ?: return@mapNotNull null) }
                    .takeIf { it.size >= 2 },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            estimateFromStraightLine(from, to)
        }
    }

    /**
     * Drive time and distance from `from` to every point, in one OSRM table
     * request (the public server asks for few, batched calls). Points it can't
     * route fall back to a straight-line estimate.
     */
    suspend fun fetchDrivesFrom(from: LatLng, points: List<LatLng>): List<DriveFromHome> {
        fun estimate(to: LatLng): DriveFromHome {
            val info = estimateFromStraightLine(from, to)
            return DriveFromHome(info.durationSeconds / 60, info.distanceMeters / 1000, estimated = true)
        }
        if (points.isEmpty()) return emptyList()
        val coords = (listOf(from) + points).joinToString(";") { "${it.lng},${it.lat}" }
        return try {
            val json = client.getJson("$OSRM_TABLE_URL/$coords?sources=0&annotations=duration,distance".toHttpUrl())
            val durations = json["durations"][0]
            val distances = json["distances"][0]
            if (json["code"].string != "Ok" || durations == null) error("OSRM: no table")
            points.mapIndexed { i, point ->
                // index 0 is the origin itself
                val seconds = durations[i + 1].number ?: return@mapIndexed estimate(point)
                val meters = distances[i + 1].number
                DriveFromHome(seconds / 60, if (meters != null) meters / 1000 else estimate(point).km, estimated = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            points.map(::estimate)
        }
    }
}
