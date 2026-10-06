@file:OptIn(ExperimentalSerializationApi::class)

package io.github.corum86.vacationmap.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The data model is the web app's (src/types/models.ts), field for field: the
// same JSON is exported, imported and synced between the two apps.
//
// Optional fields are left out of the JSON when unset (@EncodeDefault NEVER),
// as JavaScript leaves out `undefined`. Fields the web app stores as `null`
// (no home base, no trip dates, "no limit") are written as null: it tells
// the two apart.

@Serializable
data class LatLng(val lat: Double, val lng: Double)

/** A point chosen on the map; `name` comes with it when a town's name was picked. */
data class PickedLocation(val lat: Double, val lng: Double, val name: String? = null)

@Serializable
data class LinkItem(val id: String, val label: String, val url: String)

@Serializable
data class Photo(val id: String, val url: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val caption: String? = null)

@Serializable
enum class RouteSource {
    @SerialName("osrm")
    Osrm,

    @SerialName("straight-line-estimate")
    StraightLineEstimate,
}

@Serializable
data class RouteInfo(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val source: RouteSource,
    val fetchedAt: String,
    /**
     * Road path as [lat, lng] pairs; straight line for estimates. Absent on
     * data cached before route display was added — refetched when needed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val geometry: List<List<Double>>? = null,
)

@Serializable
enum class TripStatus(val id: String) {
    @SerialName("planned")
    Planned("planned"),

    @SerialName("visited")
    Visited("visited"),
}

@Serializable
data class VisitLog(
    /** ISO date (YYYY-MM-DD) */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val visitedOn: String? = null,
    /** 1…5 */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rating: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String? = null,
    /** the traveller's own photos, as opposed to the destination's reference photos */
    val photos: List<Photo> = emptyList(),
)

@Serializable
data class Destination(
    val id: String,
    val name: String,
    val location: LatLng,
    val attractions: List<String> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val links: List<LinkItem> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val notes: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val routeInfo: RouteInfo? = null,
    val status: TripStatus = TripStatus.Planned,
    val favorite: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val visit: VisitLog? = null,
)

/** What the edit forms produce: everything except identity and trip-tracking state. */
data class DestinationDraft(
    val name: String,
    val location: LatLng,
    val attractions: List<String> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val links: List<LinkItem> = emptyList(),
    val notes: String? = null,
    val routeInfo: RouteInfo? = null,
)

@Serializable
data class MainLocation(val name: String, val location: LatLng)

@Serializable
enum class TravelGroup(val id: String) {
    @SerialName("couple")
    Couple("couple"),

    @SerialName("family")
    Family("family"),

    @SerialName("friends")
    Friends("friends"),
}

@Serializable
enum class TravelStyle(val id: String) {
    @SerialName("relaxed")
    Relaxed("relaxed"),

    @SerialName("active")
    Active("active"),

    @SerialName("sightseeing")
    Sightseeing("sightseeing"),

    @SerialName("culture")
    Culture("culture"),

    @SerialName("food")
    Food("food"),

    @SerialName("nature")
    Nature("nature"),
}

@Serializable
enum class MustHave(val id: String) {
    @SerialName("beach")
    Beach("beach"),

    @SerialName("food")
    Food("food"),

    @SerialName("kids")
    Kids("kids"),

    @SerialName("nightlife")
    Nightlife("nightlife"),
}

@Serializable
data class TripPreferences(
    /** one-way drive limit from the home base, in minutes; null = no limit */
    val maxDriveMinutes: Double? = null,
    val ferry: Boolean,
    val group: TravelGroup,
    val styles: List<TravelStyle> = emptyList(),
    /** 1…3 */
    val budget: Int? = null,
    val mustHaves: List<MustHave> = emptyList(),
)

/** A candidate place from the trip planner (not necessarily saved to Places). */
@Serializable
data class PlaceSuggestion(
    /** a saved destination's id when the suggestion matches one, else a fresh id */
    val id: String,
    val name: String,
    val location: LatLng,
    val blurb: String = "",
    val styles: List<TravelStyle> = emptyList(),
    val groups: List<TravelGroup> = emptyList(),
    /** 1…3 */
    val budget: Int = 2,
    val mustHaves: List<MustHave> = emptyList(),
    /** reached by ferry: the crossing counts as drive time */
    val ferry: Boolean = false,
    /** one-way from the home base */
    val driveMinutes: Double = 0.0,
    val distanceKm: Double = 0.0,
    /** drive time is a straight-line estimate (routing unavailable) */
    val estimated: Boolean = false,
)

@Serializable
data class Trip(
    /** empty until the traveller names it */
    val name: String,
    /** ISO date (YYYY-MM-DD); null, together with endDate, until the traveller picks the dates */
    val startDate: String?,
    /** ISO date (YYYY-MM-DD), inclusive */
    val endDate: String?,
    /**
     * plan[dayIndex] = ordered destination ids for that day. A place may be on
     * several days (a revisit) but at most once per day. Indexed by day rather
     * than date, so moving the trip keeps the plan. It may be longer than the
     * date range: days cut off by shortening the trip are kept (their stops
     * show as unscheduled) and come back if the trip is extended again.
     */
    val plan: List<List<String>>,
    /** answers from the last trip-planner run; seed the next run */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val preferences: TripPreferences? = null,
    /** places the planner suggested last time; swap offers the unsaved ones */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val suggestions: List<PlaceSuggestion>? = null,
)

/** A place as the trip planner hands it over: what to save to Places. */
data class PlannedPlace(
    val id: String,
    val name: String,
    val location: LatLng,
    val photos: List<Photo>,
    val attractions: List<String>,
    val links: List<LinkItem>,
    val notes: String? = null,
    /** drive from home as the planner measured it (OSRM table, no road geometry) */
    val routeInfo: RouteInfo? = null,
)

/** The trip planner's result. Plan ids refer to `places` (or saved destinations). */
data class PlannedTrip(
    val name: String,
    val startDate: String,
    val endDate: String,
    val plan: List<List<String>>,
    val places: List<PlannedPlace>,
    val preferences: TripPreferences,
    val suggestions: List<PlaceSuggestion>,
)

const val CURRENT_DATA_VERSION = 4

@Serializable
data class VacationMapData(
    val version: Int,
    /** null until the traveller sets one (a cleared map) */
    val mainLocation: MainLocation?,
    val destinations: List<Destination>,
    val trip: Trip,
)

/** The JSON dialect shared with the web app: defaults written out (it expects its arrays present). */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
