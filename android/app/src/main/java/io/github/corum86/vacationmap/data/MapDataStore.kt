package io.github.corum86.vacationmap.data

import io.github.corum86.vacationmap.logic.Plan
import io.github.corum86.vacationmap.logic.StopRef
import io.github.corum86.vacationmap.logic.clampTripEnd
import io.github.corum86.vacationmap.logic.emptyTrip
import io.github.corum86.vacationmap.logic.migrateVacationMapData
import io.github.corum86.vacationmap.logic.movePlanStop
import io.github.corum86.vacationmap.logic.removeFromPlan
import io.github.corum86.vacationmap.logic.replanTrip
import io.github.corum86.vacationmap.logic.scheduleUnscheduled
import io.github.corum86.vacationmap.logic.swapPlanStop
import io.github.corum86.vacationmap.logic.todayIso
import io.github.corum86.vacationmap.logic.togglePlanDay
import io.github.corum86.vacationmap.logic.tripDayCount
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.CURRENT_DATA_VERSION
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.DestinationDraft
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.PlannedTrip
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.Trip
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.model.VisitLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import kotlin.math.max

/** Where the working copy of the data lives between launches. */
interface DataStorage {
    /** The saved JSON, or null when nothing has been saved yet. */
    fun read(): String?

    fun write(json: String)
}

data class MapDataState(
    val data: VacationMapData? = null,
    val selectedDestinationId: String? = null,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
)

fun newId(): String = UUID.randomUUID().toString()

fun encodeVacationData(data: VacationMapData): String = AppJson.encodeToString(VacationMapData.serializer(), data)

private fun normalizeName(name: String): String = name.trim().lowercase()

/** `existing` plus the items of `added` it doesn't have yet (compared by `key`). */
private fun <T> unionBy(existing: List<T>, added: List<T>, key: (T) -> String): List<T> {
    val seen = existing.mapTo(HashSet(), key)
    return existing + added.filter { seen.add(key(it)) }
}

/**
 * The app's data and every edit to it: the Kotlin counterpart of the web
 * app's zustand store (src/store/mapDataStore.ts). State is immutable and
 * replaced as a whole, so observers (the UI, persistence, cloud sync) see
 * consistent snapshots.
 */
class MapDataStore(
    private val storage: DataStorage,
    /** the bundled defaults, as JSON */
    private val readSeed: () -> String,
) {
    private val _state = MutableStateFlow(MapDataState())
    val state: StateFlow<MapDataState> = _state.asStateFlow()

    /** True when the data came from this device's saved copy rather than the bundled seed. */
    var loadedFromSavedCopy: Boolean = false
        private set

    val data: VacationMapData? get() = _state.value.data

    private fun seedData(): VacationMapData = migrateVacationMapData(AppJson.parseToJsonElement(readSeed()))

    /** Load the saved copy, or the bundled seed on first launch. Blocking: call off the main thread. */
    fun loadInitialData() {
        if (_state.value.data != null) {
            _state.update { it.copy(isLoaded = true) }
            return
        }
        val saved = try {
            storage.read()?.let { migrateVacationMapData(AppJson.parseToJsonElement(it)) }
        } catch (_: Exception) {
            // an unreadable saved copy must not lock the user out of the app
            null
        }
        if (saved != null) {
            loadedFromSavedCopy = true
            _state.update { if (it.data != null) it.copy(isLoaded = true) else it.copy(data = saved, isLoaded = true, loadError = null) }
            return
        }
        try {
            val seed = seedData()
            // cloud sync may have delivered the saved map while the seed was being read
            _state.update { if (it.data != null) it.copy(isLoaded = true) else it.copy(data = seed, isLoaded = true, loadError = null) }
        } catch (e: Exception) {
            _state.update { it.copy(isLoaded = true, loadError = e.message ?: "Failed to load vacation data") }
        }
    }

    /** Save the current data to this device. Blocking: call off the main thread. */
    fun persist(data: VacationMapData) = storage.write(encodeVacationData(data))

    private inline fun updateData(crossinline fn: (VacationMapData) -> VacationMapData) {
        _state.update { state -> state.data?.let { state.copy(data = fn(it)) } ?: state }
    }

    private inline fun updateTrip(crossinline fn: (Trip) -> Trip) = updateData { it.copy(trip = fn(it.trip)) }

    private inline fun mapDestination(id: String, crossinline fn: (Destination) -> Destination) =
        updateData { data -> data.copy(destinations = data.destinations.map { if (it.id == id) fn(it) else it }) }

    fun setMainLocation(location: MainLocation) = updateData { current ->
        // every cached route starts at the home base, so moving it voids them all
        val moved = current.mainLocation?.location != location.location
        current.copy(
            mainLocation = location,
            destinations = if (moved) current.destinations.map { it.copy(routeInfo = null) } else current.destinations,
        )
    }

    /** Save a new place; returns its id. */
    fun addDestination(draft: DestinationDraft, id: String = newId()): String {
        updateData { current ->
            current.copy(
                destinations = current.destinations + Destination(
                    id = id,
                    name = draft.name,
                    location = draft.location,
                    attractions = draft.attractions,
                    photos = draft.photos,
                    links = draft.links,
                    notes = draft.notes,
                    routeInfo = draft.routeInfo,
                ),
            )
        }
        return id
    }

    fun updateDestination(id: String, change: (Destination) -> Destination) = mapDestination(id) { d ->
        val next = change(d).copy(id = d.id)
        // a moved destination needs a fresh route from OSRM
        if (d.location == next.location) next else next.copy(routeInfo = null)
    }

    /** Apply an edit form's result, keeping identity and trip-tracking state. */
    fun updateDestination(id: String, draft: DestinationDraft) = updateDestination(id) {
        it.copy(
            name = draft.name,
            location = draft.location,
            attractions = draft.attractions,
            photos = draft.photos,
            links = draft.links,
            notes = draft.notes,
        )
    }

    fun removeDestination(id: String) = _state.update { state ->
        val current = state.data ?: return@update state
        state.copy(
            data = current.copy(
                destinations = current.destinations.filter { it.id != id },
                trip = current.trip.copy(plan = removeFromPlan(current.trip.plan, id)),
            ),
            selectedDestinationId = state.selectedDestinationId.takeIf { it != id },
        )
    }

    fun setSelectedDestination(id: String?) = _state.update { it.copy(selectedDestinationId = id) }

    fun setRouteInfo(destinationId: String, info: RouteInfo) = mapDestination(destinationId) { it.copy(routeInfo = info) }

    fun setStatus(id: String, status: TripStatus) = mapDestination(id) { d ->
        if (status == TripStatus.Planned) return@mapDestination d.copy(status = status)
        // switching back to planned keeps the log, so only stamp a date when there is none
        val visit = d.visit ?: VisitLog()
        d.copy(status = status, visit = visit.copy(visitedOn = visit.visitedOn ?: todayIso()))
    }

    fun toggleFavorite(id: String) = mapDestination(id) { it.copy(favorite = !it.favorite) }

    fun setRating(id: String, rating: Int) = updateVisit(id) { it.copy(rating = rating) }

    fun addVisitPhoto(id: String, photo: Photo) = updateVisit(id) { it.copy(photos = it.photos + photo) }

    fun removeVisitPhoto(id: String, photoId: String) =
        updateVisit(id) { visit -> visit.copy(photos = visit.photos.filter { it.id != photoId }) }

    fun updateVisit(id: String, change: (VisitLog) -> VisitLog) =
        mapDestination(id) { it.copy(visit = change(it.visit ?: VisitLog())) }

    fun setTripName(name: String) = updateTrip { it.copy(name = name) }

    /** Delete the trip (name, dates, plan, planner answers) and start a blank one; the places stay. */
    fun clearTrip() = updateTrip { emptyTrip() }

    /** Set the trip's date range (end is clamped to the maximum trip length). */
    fun setTripDates(startDate: String, endDate: String) = updateTrip { trip ->
        val next = trip.copy(startDate = startDate, endDate = clampTripEnd(startDate, endDate))
        // days are derived from the range; the plan only ever grows, so
        // shortening and re-extending the trip brings its stops back
        next.copy(plan = List(max(trip.plan.size, tripDayCount(next))) { i -> trip.plan.getOrNull(i) ?: emptyList() })
    }

    /** Move one visit to a day (before position `beforeIndex`, or last), or off its day with null. */
    fun moveStop(from: StopRef, dayIndex: Int?, beforeIndex: Int? = null) =
        updateTrip { it.copy(plan = movePlanStop(it.plan, from, dayIndex, beforeIndex)) }

    /** Add a visit to a day, or remove it if the place is already on that day. */
    fun toggleTripDay(id: String, dayIndex: Int) = updateTrip { it.copy(plan = togglePlanDay(it.plan, id, dayIndex)) }

    /** Replace the stop at `dayIndex`/`index` with another place. */
    fun swapStop(dayIndex: Int, index: Int, newId: String) =
        updateTrip { it.copy(plan = swapPlanStop(it.plan, dayIndex, index, newId)) }

    /** Plan the places that are on no day; returns how many were placed. */
    fun autoPlan(): Int {
        val current = data ?: return 0
        // planning goes by drive time from the home base
        val home = current.mainLocation ?: return 0
        if (tripDayCount(current.trip) == 0) return 0
        val result = scheduleUnscheduled(current.trip, home, current.destinations)
        if (result.count > 0) setPlan(result.plan)
        return result.count
    }

    /** Re-plan days from `fromDay` on by drive time; returns how many places were redistributed. */
    fun replan(fromDay: Int, includeUnscheduled: Boolean): Int {
        val current = data ?: return 0
        val home = current.mainLocation ?: return 0
        if (tripDayCount(current.trip) == 0) return 0
        val result = replanTrip(current.trip, home, current.destinations, fromDay, includeUnscheduled)
        if (result.count > 0) setPlan(result.plan)
        return result.count
    }

    /** Replace the whole plan, e.g. to undo an auto-plan. */
    fun setPlan(plan: Plan) = updateTrip { it.copy(plan = plan) }

    /** Save the trip-planner result: merge its places into Places and replace the trip. */
    fun applyPlannedTrip(planned: PlannedTrip) = _state.update { state ->
        val current = state.data ?: return@update state
        val destinations = current.destinations.toMutableList()
        // a planner place may already be saved: under its id, or by name
        val idMap = HashMap<String, String>()
        for (place in planned.places) {
            val at = destinations.indexOfFirst { it.id == place.id || normalizeName(it.name) == normalizeName(place.name) }
            if (at >= 0) {
                val existing = destinations[at]
                idMap[place.id] = existing.id
                // keep the visit log, favourite and status; add what's new
                destinations[at] = existing.copy(
                    photos = unionBy(existing.photos, place.photos) { it.url },
                    links = unionBy(existing.links, place.links) { it.url },
                    attractions = unionBy(existing.attractions, place.attractions) { it.trim().lowercase() },
                )
            } else {
                idMap[place.id] = place.id
                destinations += Destination(
                    id = place.id,
                    name = place.name,
                    location = place.location,
                    attractions = place.attractions,
                    photos = place.photos,
                    links = place.links,
                    notes = place.notes,
                    routeInfo = place.routeInfo,
                )
            }
        }
        state.copy(
            data = current.copy(
                destinations = destinations,
                trip = Trip(
                    name = planned.name,
                    startDate = planned.startDate,
                    endDate = clampTripEnd(planned.startDate, planned.endDate),
                    plan = planned.plan.map { ids -> ids.map { idMap[it] ?: it }.distinct() },
                    preferences = planned.preferences,
                    suggestions = planned.suggestions.takeIf { it.isNotEmpty() },
                ),
            ),
            selectedDestinationId = null,
        )
    }

    /** Replace everything with imported data (throws if it isn't vacation data). */
    fun replaceAllData(json: JsonElement) {
        val data = migrateVacationMapData(json)
        _state.update { it.copy(data = data, selectedDestinationId = null, loadError = null) }
    }

    /** Replace this device's data with the copy another device saved to the cloud. */
    fun adoptRemote(data: VacationMapData) = _state.update { state ->
        state.copy(
            data = data,
            loadError = null,
            // an open place stays open if the cloud copy still has it
            selectedDestinationId = state.selectedDestinationId?.takeIf { id -> data.destinations.any { it.id == id } },
        )
    }

    /** Delete everything: the places, the trip and the home base. */
    fun clearAllData() = _state.update { state ->
        if (state.data == null) return@update state
        state.copy(
            data = VacationMapData(
                version = CURRENT_DATA_VERSION,
                mainLocation = null,
                destinations = emptyList(),
                trip = emptyTrip(),
            ),
            selectedDestinationId = null,
            loadError = null,
        )
    }
}
