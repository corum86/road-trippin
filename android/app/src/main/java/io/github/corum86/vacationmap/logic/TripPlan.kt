package io.github.corum86.vacationmap.logic

import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.Trip
import io.github.corum86.vacationmap.model.TripStatus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

// Port of the web app's src/services/tripPlan.ts. The planner must give the
// same plan in both apps for the same data; tests check it against fixtures
// produced by the TypeScript original.

const val MAX_TRIP_DAYS = 30
internal const val DEFAULT_TRIP_DAYS = 7

/** plan[dayIndex] = ordered destination ids for that day */
typealias Plan = List<List<String>>

/** Last allowed end date for a trip starting on `startDate`. */
fun clampTripEnd(startDate: String, endDate: String): String {
    val days = min(max(diffDays(startDate, endDate), 0), MAX_TRIP_DAYS - 1)
    return addDays(startDate, days)
}

/** Number of days in the trip's date range (1…MAX_TRIP_DAYS); 0 while the trip has no dates. */
fun tripDayCount(startDate: String?, endDate: String?): Int {
    if (startDate.isNullOrEmpty() || endDate.isNullOrEmpty()) return 0
    return min(max(diffDays(startDate, endDate) + 1, 1), MAX_TRIP_DAYS)
}

fun tripDayCount(trip: Trip): Int = tripDayCount(trip.startDate, trip.endDate)

/** The date of day `dayIndex`; empty while the trip has no dates (and so no days). */
fun tripDayDate(trip: Trip, dayIndex: Int): String =
    if (trip.startDate.isNullOrEmpty()) "" else addDays(trip.startDate, dayIndex)

/** The plan for exactly the days in the date range (padded with empty days). */
fun visiblePlan(trip: Trip): Plan = List(tripDayCount(trip)) { i -> trip.plan.getOrNull(i) ?: emptyList() }

/** destination id → the (visible) days it is on, in order; more than one is a revisit */
fun daysByDestination(trip: Trip): Map<String, List<Int>> {
    val days = LinkedHashMap<String, MutableList<Int>>()
    visiblePlan(trip).forEachIndexed { day, ids -> ids.forEach { id -> days.getOrPut(id) { mutableListOf() }.add(day) } }
    return days
}

/** Saved places on no day of the trip. */
fun unscheduledDestinations(trip: Trip, destinations: List<Destination>): List<Destination> {
    val scheduled = visiblePlan(trip).flatten().toSet()
    return destinations.filter { it.id !in scheduled }
}

/** One visit: a stop on a given day (`day`/`index`), or a place picked from the unscheduled tray (`day` null). */
data class StopRef(val id: String, val day: Int?, val index: Int = -1)

/**
 * Move one visit to `toDay` (before position `beforeIndex`, or last), or take
 * it off its day with `toDay` null. Only that visit moves; other days with
 * the same place are untouched. Moving onto a day that already has the place
 * changes nothing.
 */
fun movePlanStop(plan: Plan, from: StopRef, toDay: Int?, beforeIndex: Int? = null): Plan {
    if (toDay != null && toDay != from.day && plan.getOrNull(toDay)?.contains(from.id) == true) return plan
    val next = plan.mapTo(mutableListOf()) { it.toMutableList() }
    var insertAt = beforeIndex
    if (from.day != null) {
        next.getOrNull(from.day)?.let { ids -> if (from.index in ids.indices) ids.removeAt(from.index) }
        // within the same day, removing the item shifted everything after it up
        if (toDay == from.day && insertAt != null && insertAt > from.index) insertAt -= 1
    }
    if (toDay == null) return next
    while (next.size <= toDay) next.add(mutableListOf())
    val target = next[toDay]
    val at = max(0, min(insertAt ?: target.size, target.size))
    target.add(at, from.id)
    return next
}

/** Add a visit to `day`, or remove it when the place is already on that day. */
fun togglePlanDay(plan: Plan, id: String, day: Int): Plan {
    val next = plan.mapTo(mutableListOf()) { it.toMutableList() }
    while (next.size <= day) next.add(mutableListOf())
    if (!next[day].remove(id)) next[day].add(id)
    return next
}

/** Put `newId` in place of the stop at `day`/`index` (a no-op if that day already has it). */
fun swapPlanStop(plan: Plan, day: Int, index: Int, newId: String): Plan {
    val ids = plan.getOrNull(day) ?: return plan
    if (newId in ids) return plan
    return plan.mapIndexed { d, dayIds ->
        if (d == day) dayIds.mapIndexed { i, id -> if (i == index) newId else id } else dayIds
    }
}

/** Drop a deleted destination from every day. */
fun removeFromPlan(plan: Plan, id: String): Plan = plan.map { ids -> ids.filter { it != id } }

// ---------------------------------------------------------------------------
// Distance planner

/** What the planner needs to know about a place. */
data class PlanItem(
    val id: String,
    val location: LatLng,
    /** one-way drive from the home base */
    val driveMinutes: Double,
)

// neighbours in the same direction share a day, up to two per day
private const val CLUSTER_MAX_BEARING_DEG = 25.0
private const val CLUSTER_MAX_DISTANCE_M = 40_000.0
private const val CLUSTER_MAX_SIZE = 2

// a drive this long fills a day on its own
private const val LONG_DRIVE_MINUTES = 100.0

// extra time a day's second (third…) stop adds to the round trip
private const val MINUTES_PER_EXTRA_STOP = 20.0

/** Compass-style bearing from the home base, in degrees (−180…180). */
private fun bearingFrom(home: LatLng, point: LatLng): Double =
    Math.toDegrees(atan2(point.lng - home.lng, point.lat - home.lat))

/** Drive time from home: the cached OSRM route, else a straight-line estimate. */
fun destinationDriveMinutes(dest: Destination, home: MainLocation): Double {
    val route = dest.routeInfo ?: estimateFromStraightLine(home.location, dest.location)
    return route.durationSeconds / 60
}

fun toPlanItem(dest: Destination, home: MainLocation): PlanItem =
    PlanItem(dest.id, dest.location, destinationDriveMinutes(dest, home))

/**
 * Plan `items` onto days `fromDay…dayCount-1` of `base` (earlier days are
 * never touched). Places are walked in order of bearing from home and
 * grouped with close neighbours; long drives stay alone. When there are
 * enough empty days, groups are spread evenly so busy days alternate with
 * free ones; otherwise each group goes to the least-loaded day.
 */
fun planDays(items: List<PlanItem>, dayCount: Int, home: LatLng, base: Plan?, fromDay: Int = 0): Plan {
    if (dayCount <= 0) return emptyList()
    val plan = List(dayCount) { i -> (base?.getOrNull(i) ?: emptyList()).toMutableList() }
    val sorted = items.sortedBy { bearingFrom(home, it.location) }

    val clusters = mutableListOf<MutableList<PlanItem>>()
    for (item in sorted) {
        val current = clusters.lastOrNull()
        val last = current?.lastOrNull()
        val joins = current != null &&
            last != null &&
            current.size < CLUSTER_MAX_SIZE &&
            item.driveMinutes < LONG_DRIVE_MINUTES &&
            last.driveMinutes < LONG_DRIVE_MINUTES &&
            abs(bearingFrom(home, item.location) - bearingFrom(home, last.location)) < CLUSTER_MAX_BEARING_DEG &&
            haversineDistanceMeters(item.location, last.location) < CLUSTER_MAX_DISTANCE_M
        if (joins) current.add(item) else clusters.add(mutableListOf(item))
    }

    val start = min(max(fromDay, 0), dayCount - 1)
    var candidates = (start until dayCount).toList()
    // on a fresh plan, day 1 is for arriving, if there is room elsewhere
    if (start == 0 && dayCount > 2 && clusters.size < dayCount) candidates = candidates.drop(1)
    if (candidates.isEmpty()) candidates = listOf(dayCount - 1)

    fun place(cluster: List<PlanItem>, day: Int) {
        for (item in cluster) if (item.id !in plan[day]) plan[day].add(item.id)
    }

    val emptyDays = candidates.filter { plan[it].isEmpty() }
    if (clusters.isNotEmpty() && clusters.size <= emptyDays.size) {
        clusters.forEachIndexed { i, cluster -> place(cluster, emptyDays[(i * emptyDays.size) / clusters.size]) }
    } else {
        for (cluster in clusters) {
            // the least-loaded day, the earliest on ties
            val day = candidates.minBy { plan[it].size }
            place(cluster, day)
        }
    }
    return plan
}

/** Rough driving for a day: there and back to the furthest stop, plus time per extra stop. */
fun dayDriveMinutes(stopDriveMinutes: List<Double>): Double {
    if (stopDriveMinutes.isEmpty()) return 0.0
    return 2 * stopDriveMinutes.max() + MINUTES_PER_EXTRA_STOP * (stopDriveMinutes.size - 1)
}

data class PlanResult(val plan: Plan, val count: Int)

/** Schedule only the places that are on no day ("Auto-plan"). */
fun scheduleUnscheduled(trip: Trip, home: MainLocation, destinations: List<Destination>): PlanResult {
    val todo = unscheduledDestinations(trip, destinations)
    if (todo.isEmpty()) return PlanResult(trip.plan, 0)
    val visible = visiblePlan(trip)
    val next = planDays(todo.map { toPlanItem(it, home) }, visible.size, home.location, visible, 0)
    return PlanResult(next + trip.plan.drop(visible.size), todo.size)
}

/** First day that still has a place to visit (re-plan defaults to it). */
fun firstOpenDay(trip: Trip, destinations: List<Destination>): Int {
    val byId = destinations.associateBy { it.id }
    val day = visiblePlan(trip).indexOfFirst { ids -> ids.any { byId[it]?.status != TripStatus.Visited } }
    return max(day, 0)
}

/**
 * The places a re-plan from `fromDay` redistributes: unvisited stops on those
 * days (one entry per place, so revisits collapse into one visit) plus,
 * optionally, unvisited places that are on no day.
 */
fun replanPool(
    trip: Trip,
    destinations: List<Destination>,
    fromDay: Int,
    includeUnscheduled: Boolean,
): List<Destination> {
    val byId = destinations.associateBy { it.id }
    val pool = LinkedHashMap<String, Destination>()
    for (ids in visiblePlan(trip).drop(max(fromDay, 0))) {
        for (id in ids) {
            val dest = byId[id]
            if (dest != null && dest.status != TripStatus.Visited) pool[id] = dest
        }
    }
    if (includeUnscheduled) {
        for (dest in unscheduledDestinations(trip, destinations)) {
            if (dest.status != TripStatus.Visited) pool[dest.id] = dest
        }
    }
    return pool.values.toList()
}

/**
 * Re-plan days `fromDay…end`: earlier days stay as they are, visited stops
 * stay put, and the rest of the pool is planned again by drive time.
 */
fun replanTrip(
    trip: Trip,
    home: MainLocation,
    destinations: List<Destination>,
    fromDay: Int,
    includeUnscheduled: Boolean,
): PlanResult {
    val byId = destinations.associateBy { it.id }
    val visible = visiblePlan(trip)
    val from = min(max(fromDay, 0), visible.size - 1)
    val pool = replanPool(trip, destinations, from, includeUnscheduled)
    val keep = visible.mapIndexed { day, ids ->
        if (day < from) ids else ids.filter { byId[it]?.status == TripStatus.Visited }
    }
    val next = planDays(pool.map { toPlanItem(it, home) }, visible.size, home.location, keep, from)
    return PlanResult(next + trip.plan.drop(visible.size), pool.size)
}

/** A trip with nothing decided yet: no name, no dates, no plan. */
fun emptyTrip(): Trip = Trip(name = "", startDate = null, endDate = null, plan = emptyList())
