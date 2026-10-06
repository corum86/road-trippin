package io.github.corum86.vacationmap.logic

import io.github.corum86.vacationmap.model.CURRENT_DATA_VERSION
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.LinkItem
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.RouteSource
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle
import io.github.corum86.vacationmap.model.Trip
import io.github.corum86.vacationmap.model.TripPreferences
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.model.VisitLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Data arrives as JSON from four places: the bundled seed, this device's saved
// copy, an imported file and the cloud. All of it goes through
// migrateVacationMapData, which reads it leniently (as the web app does) and
// brings older versions up to the current shape.

class InvalidVacationDataException : Exception("Data does not match the vacation data format")

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.num(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** The shape check run on anything that claims to be vacation data. */
fun isValidVacationMapData(value: JsonElement?): Boolean {
    val v = value.obj() ?: return false
    // the home base is an object, or null on a map that has none yet
    val home = v["mainLocation"]
    return v["version"].num() != null && (home is JsonObject || home is JsonNull) && v["destinations"] is JsonArray
}

/** The data's version number, or null if it has none. */
fun dataVersionOf(value: JsonElement?): Int? = value.obj()?.get("version").num()?.toInt()

private fun parseLatLng(value: JsonElement?): LatLng? {
    val o = value.obj() ?: return null
    return LatLng(o["lat"].num() ?: return null, o["lng"].num() ?: return null)
}

private fun parsePhotos(value: JsonElement?): List<Photo> =
    value.arr().orEmpty().mapNotNull { item ->
        val o = item.obj() ?: return@mapNotNull null
        Photo(id = o["id"].str() ?: return@mapNotNull null, url = o["url"].str() ?: return@mapNotNull null, caption = o["caption"].str())
    }

private fun parseLinks(value: JsonElement?): List<LinkItem> =
    value.arr().orEmpty().mapNotNull { item ->
        val o = item.obj() ?: return@mapNotNull null
        val url = o["url"].str() ?: return@mapNotNull null
        LinkItem(id = o["id"].str() ?: return@mapNotNull null, label = o["label"].str() ?: url, url = url)
    }

private fun parseRouteInfo(value: JsonElement?): RouteInfo? {
    val o = value.obj() ?: return null
    val geometry = o["geometry"].arr()
        ?.mapNotNull { point ->
            val pair = point.arr() ?: return@mapNotNull null
            listOf(pair.getOrNull(0).num() ?: return@mapNotNull null, pair.getOrNull(1).num() ?: return@mapNotNull null)
        }
        ?.takeIf { it.size >= 2 }
    return RouteInfo(
        distanceMeters = o["distanceMeters"].num() ?: return null,
        durationSeconds = o["durationSeconds"].num() ?: return null,
        source = if (o["source"].str() == "straight-line-estimate") RouteSource.StraightLineEstimate else RouteSource.Osrm,
        fetchedAt = o["fetchedAt"].str() ?: "",
        geometry = geometry,
    )
}

private fun parseDestination(value: JsonElement): Destination? {
    val o = value.obj() ?: return null
    return Destination(
        id = o["id"].str() ?: return null,
        name = o["name"].str() ?: "",
        location = parseLatLng(o["location"]) ?: return null,
        attractions = o["attractions"].arr().orEmpty().mapNotNull { it.str() },
        photos = parsePhotos(o["photos"]),
        links = parseLinks(o["links"]),
        notes = o["notes"].str(),
        routeInfo = parseRouteInfo(o["routeInfo"]),
        status = if (o["status"].str() == "visited") TripStatus.Visited else TripStatus.Planned,
        favorite = o["favorite"].bool() == true,
        visit = o["visit"].obj()?.let { v ->
            VisitLog(
                visitedOn = v["visitedOn"].str(),
                rating = v["rating"].num()?.toInt()?.takeIf { it in 1..5 },
                note = v["note"].str(),
                photos = parsePhotos(v["photos"]),
            )
        },
    )
}

/** A stored trip before validation: any field may be missing or wrong. */
internal data class LooseTrip(
    val name: String? = null,
    /** the dates were stored as null: the traveller cleared them */
    val undated: Boolean = false,
    val startDate: String? = null,
    val endDate: String? = null,
    val plan: List<List<String>>? = null,
    val preferences: JsonElement? = null,
    val suggestions: JsonElement? = null,
)

private fun looseTripOf(value: JsonElement?): LooseTrip? {
    val o = value.obj() ?: return null
    return LooseTrip(
        name = o["name"].str(),
        undated = o["startDate"] is JsonNull,
        startDate = o["startDate"].str(),
        endDate = o["endDate"].str(),
        plan = o["plan"].arr()?.map { ids -> ids.arr().orEmpty().mapNotNull { it.str() } },
        preferences = o["preferences"],
        suggestions = o["suggestions"],
    )
}

/** v2 stored the trip as dated itinerary days plus a separate trip name. */
private fun tripFromLegacy(root: JsonObject): LooseTrip? {
    val tripName = root["tripName"].str()
    val days = root["itinerary"].arr().orEmpty().mapNotNull { day ->
        val o = day.obj() ?: return@mapNotNull null
        val date = o["date"].str()?.takeIf(::isIsoDate) ?: return@mapNotNull null
        date to o["stopIds"].arr().orEmpty().mapNotNull { it.str() }
    }
    if (days.isEmpty()) return tripName?.takeIf { it.isNotEmpty() }?.let { LooseTrip(name = it) }
    val dates = days.map { it.first }.sorted()
    val startDate = dates.first()
    val plan = mutableListOf<MutableList<String>>()
    for ((date, stopIds) in days) {
        val index = diffDays(startDate, date)
        // fill the gaps a sparse assignment leaves
        while (plan.size <= index) plan.add(mutableListOf())
        plan[index].addAll(stopIds)
    }
    return LooseTrip(name = tripName ?: "", startDate = startDate, endDate = dates.last(), plan = plan)
}

private inline fun <reified T : Enum<T>> enumOf(value: JsonElement?, id: (T) -> String): T? {
    val text = value.str() ?: return null
    return enumValues<T>().firstOrNull { id(it) == text }
}

private inline fun <reified T : Enum<T>> enumListOf(value: JsonElement?, id: (T) -> String): List<T> =
    value.arr().orEmpty().mapNotNull { enumOf(it, id) }.distinct()

private fun budgetOf(value: JsonElement?): Int? = value.num()?.takeIf { it == 1.0 || it == 2.0 || it == 3.0 }?.toInt()

/** Keep only well-formed planner answers. */
fun sanitizePreferences(value: JsonElement?): TripPreferences? {
    val p = value.obj() ?: return null
    val group = enumOf<TravelGroup>(p["group"]) { it.id } ?: return null
    return TripPreferences(
        maxDriveMinutes = p["maxDriveMinutes"].num()?.takeIf { it > 0 },
        ferry = p["ferry"].bool() != false,
        group = group,
        styles = enumListOf<TravelStyle>(p["styles"]) { it.id },
        budget = budgetOf(p["budget"]),
        mustHaves = enumListOf<MustHave>(p["mustHaves"]) { it.id },
    )
}

/** Keep only well-formed suggestions (they come from an AI model). */
fun sanitizeSuggestions(value: JsonElement?): List<PlaceSuggestion> =
    value.arr().orEmpty().mapNotNull { item ->
        val s = item.obj() ?: return@mapNotNull null
        val id = s["id"].str() ?: return@mapNotNull null
        val name = s["name"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val location = parseLatLng(s["location"]) ?: return@mapNotNull null
        PlaceSuggestion(
            id = id,
            name = name,
            location = location,
            blurb = s["blurb"].str() ?: "",
            styles = enumListOf<TravelStyle>(s["styles"]) { it.id },
            groups = enumListOf<TravelGroup>(s["groups"]) { it.id },
            budget = budgetOf(s["budget"]) ?: 2,
            mustHaves = enumListOf<MustHave>(s["mustHaves"]) { it.id },
            ferry = s["ferry"].bool() == true,
            driveMinutes = s["driveMinutes"].num()?.takeIf { it >= 0 } ?: 0.0,
            distanceKm = s["distanceKm"].num()?.takeIf { it >= 0 } ?: 0.0,
            estimated = s["estimated"].bool() == true,
        )
    }

/**
 * Make a stored trip safe to use: valid ordered dates within the length
 * limit, and a plan that only references existing destinations, at most
 * once per day. Dates stored as null stay null (the traveller cleared them);
 * missing or broken ones, as in data that has no trip yet, become a week
 * starting today.
 */
internal fun sanitizeTrip(trip: LooseTrip?, destinationIds: Set<String>): Trip {
    val undated = trip?.undated == true
    val startDate = trip?.startDate?.takeIf(::isIsoDate) ?: todayIso()
    val rawEnd = trip?.endDate?.takeIf(::isIsoDate) ?: addDays(startDate, DEFAULT_TRIP_DAYS - 1)
    return Trip(
        name = trip?.name ?: "",
        startDate = if (undated) null else startDate,
        endDate = if (undated) null else clampTripEnd(startDate, rawEnd),
        plan = trip?.plan.orEmpty().map { ids -> ids.filter { it in destinationIds }.distinct() },
        preferences = sanitizePreferences(trip?.preferences),
        suggestions = sanitizeSuggestions(trip?.suggestions).takeIf { it.isNotEmpty() },
    )
}

/**
 * Bring data of any earlier version up to the current shape. Idempotent, so
 * it is safe to run on every entry point: the seed, the saved copy, a file
 * import and the cloud.
 */
fun migrateVacationMapData(raw: JsonElement?): VacationMapData {
    if (!isValidVacationMapData(raw)) throw InvalidVacationDataException()
    val root = raw as JsonObject
    val mainLocation = root["mainLocation"].obj()?.let { main ->
        parseLatLng(main["location"])?.let { MainLocation(name = main["name"].str() ?: "", location = it) }
    }
    val destinations = root["destinations"].arr().orEmpty().mapNotNull(::parseDestination).distinctBy { it.id }
    // older data lacks the trip entirely, or holds it in the v2 shape
    val trip = if (root["trip"] != null) looseTripOf(root["trip"]) else tripFromLegacy(root)
    return VacationMapData(
        version = CURRENT_DATA_VERSION,
        mainLocation = mainLocation,
        destinations = destinations,
        trip = sanitizeTrip(trip, destinations.mapTo(HashSet()) { it.id }),
    )
}
