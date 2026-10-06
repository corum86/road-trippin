package io.github.corum86.vacationmap

import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.logic.DateRangeSelection
import io.github.corum86.vacationmap.logic.MatchReason
import io.github.corum86.vacationmap.logic.Plan
import io.github.corum86.vacationmap.logic.PlanItem
import io.github.corum86.vacationmap.logic.PlanResult
import io.github.corum86.vacationmap.logic.Point
import io.github.corum86.vacationmap.logic.RankingPreferences
import io.github.corum86.vacationmap.logic.StopRef
import io.github.corum86.vacationmap.logic.addDays
import io.github.corum86.vacationmap.logic.addMonths
import io.github.corum86.vacationmap.logic.bezierControlPoint
import io.github.corum86.vacationmap.logic.clampTripEnd
import io.github.corum86.vacationmap.logic.dayDriveMinutes
import io.github.corum86.vacationmap.logic.daysByDestination
import io.github.corum86.vacationmap.logic.daysInMonth
import io.github.corum86.vacationmap.logic.diffDays
import io.github.corum86.vacationmap.logic.firstOpenDay
import io.github.corum86.vacationmap.logic.formatDateRange
import io.github.corum86.vacationmap.logic.formatDayLabel
import io.github.corum86.vacationmap.logic.formatDistance
import io.github.corum86.vacationmap.logic.formatDuration
import io.github.corum86.vacationmap.logic.formatMonthTitle
import io.github.corum86.vacationmap.logic.formatMonthYear
import io.github.corum86.vacationmap.logic.formatShortDate
import io.github.corum86.vacationmap.logic.hashString
import io.github.corum86.vacationmap.logic.haversineDistanceMeters
import io.github.corum86.vacationmap.logic.isIsoDate
import io.github.corum86.vacationmap.logic.isReachable
import io.github.corum86.vacationmap.logic.migrateVacationMapData
import io.github.corum86.vacationmap.logic.movePlanStop
import io.github.corum86.vacationmap.logic.pickRangeDate
import io.github.corum86.vacationmap.logic.planDays
import io.github.corum86.vacationmap.logic.rangeEndOf
import io.github.corum86.vacationmap.logic.rankSuggestions
import io.github.corum86.vacationmap.logic.recommendedPlaceCount
import io.github.corum86.vacationmap.logic.removeFromPlan
import io.github.corum86.vacationmap.logic.replanTrip
import io.github.corum86.vacationmap.logic.scheduleUnscheduled
import io.github.corum86.vacationmap.logic.shortPlaceName
import io.github.corum86.vacationmap.logic.swapPlanStop
import io.github.corum86.vacationmap.logic.togglePlanDay
import io.github.corum86.vacationmap.logic.trimQuadraticBezier
import io.github.corum86.vacationmap.logic.tripDayCount
import io.github.corum86.vacationmap.logic.unscheduledDestinations
import io.github.corum86.vacationmap.logic.weekdayIndex
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle
import io.github.corum86.vacationmap.model.Trip
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.net.placeMatchesFromPhoton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Replays the cases recorded from the web app's TypeScript logic
 * (android/scripts/gen-parity-fixtures.mjs) through the Kotlin port. Both
 * apps edit the same synced data, so they must agree exactly on dates, plan
 * edits, the day planner, suggestion ranking and migration.
 */
class ParityTest {
    private class Case(val fn: String, val args: List<JsonElement>, val out: JsonElement)

    private val cases: List<Case> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/parity/fixtures.json")) {
            "fixtures missing: run node android/scripts/gen-parity-fixtures.mjs"
        }.bufferedReader().use { it.readText() }
        AppJson.parseToJsonElement(text).jsonArray.map {
            val o = it.jsonObject
            Case(o.getValue("fn").jsonPrimitive.content, o.getValue("args").jsonArray, o.getValue("out"))
        }
    }

    // --- reading arguments ---------------------------------------------------

    private fun JsonElement.s(): String = jsonPrimitive.content
    private fun JsonElement.i(): Int = jsonPrimitive.int
    private fun JsonElement.d(): Double = jsonPrimitive.double
    private fun JsonElement.sOrNull(): String? = if (this is JsonNull) null else s()
    private fun JsonElement.iOrNull(): Int? = if (this is JsonNull) null else i()
    private fun JsonElement.lang(): Lang = Lang.fromCode(s())
    private fun JsonElement.plan(): Plan = jsonArray.map { ids -> ids.jsonArray.map { it.s() } }
    private fun JsonElement.point(): Point = jsonObject.let { Point(it.getValue("x").d(), it.getValue("y").d()) }
    private fun JsonElement.latLng(): LatLng = AppJson.decodeFromJsonElement(this)
    private fun JsonElement.trip(): Trip = AppJson.decodeFromJsonElement(this)
    private fun JsonElement.home(): MainLocation = AppJson.decodeFromJsonElement(this)
    private fun JsonElement.destinations(): List<Destination> = AppJson.decodeFromJsonElement(this)
    private fun JsonElement.suggestions(): List<PlaceSuggestion> = AppJson.decodeFromJsonElement(this)
    private fun JsonElement.range(): DateRangeSelection =
        jsonObject.let { DateRangeSelection(it.getValue("start").sOrNull(), it.getValue("end").sOrNull()) }

    // --- writing results -----------------------------------------------------

    private fun json(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
    private fun json(value: Number): JsonElement = JsonPrimitive(value)
    private fun json(value: Boolean): JsonElement = JsonPrimitive(value)
    private fun json(point: Point): JsonElement = buildJsonObject { put("x", point.x); put("y", point.y) }
    private fun planJson(plan: Plan): JsonElement = JsonArray(plan.map { ids -> JsonArray(ids.map(::JsonPrimitive)) })
    private fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))
    private fun json(result: PlanResult): JsonElement =
        buildJsonObject { put("plan", planJson(result.plan)); put("count", result.count) }

    // --- comparing -----------------------------------------------------------

    /** Null when equal, else where and how the two differ. Absent and null are the same thing. */
    private fun difference(expected: JsonElement?, actual: JsonElement?, path: String = "$"): String? {
        val e = expected ?: JsonNull
        val a = actual ?: JsonNull
        return when {
            e is JsonObject && a is JsonObject ->
                (e.keys + a.keys).firstNotNullOfOrNull { key -> difference(e[key], a[key], "$path.$key") }
            e is JsonArray && a is JsonArray ->
                if (e.size != a.size) {
                    "$path: expected ${e.size} items, got ${a.size}\n  expected $e\n  actual   $a"
                } else {
                    e.indices.firstNotNullOfOrNull { i -> difference(e[i], a[i], "$path[$i]") }
                }
            e is JsonPrimitive && a is JsonPrimitive -> {
                val en = if (e.isString) null else e.doubleOrNull
                val an = if (a.isString) null else a.doubleOrNull
                val same = if (en != null && an != null) {
                    abs(en - an) <= 1e-9 * max(1.0, max(abs(en), abs(an)))
                } else {
                    e.isString == a.isString && e.content == a.content
                }
                if (same) null else "$path: expected $e, got $a"
            }
            else -> "$path: expected $e, got $a"
        }
    }

    private fun check(fn: String, run: (List<JsonElement>) -> JsonElement) {
        val relevant = cases.filter { it.fn == fn }
        assertTrue("no recorded cases for $fn", relevant.isNotEmpty())
        val failures = relevant.mapNotNull { case ->
            val actual = try {
                run(case.args)
            } catch (e: Exception) {
                return@mapNotNull "$fn(${case.args.toString().take(300)}) threw $e"
            }
            difference(case.out, actual)?.let { "$fn(${case.args.toString().take(300)})\n  $it" }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${relevant.size} $fn cases differ:\n" + failures.take(5).joinToString("\n"))
        }
    }

    // --- dates -----------------------------------------------------------------

    @Test fun addDays() = check("addDays") { a -> json(addDays(a[0].s(), a[1].i())) }

    @Test fun diffDays() = check("diffDays") { a -> json(diffDays(a[0].s(), a[1].s())) }

    @Test fun addMonths() = check("addMonths") { a -> json(addMonths(a[0].s(), a[1].i())) }

    @Test fun daysInMonth() = check("daysInMonth") { a -> json(daysInMonth(a[0].s())) }

    @Test fun weekdayIndex() = check("weekdayIndex") { a -> json(weekdayIndex(a[0].s())) }

    @Test fun isIsoDate() = check("isIsoDate") { a -> json(isIsoDate(a[0].s())) }

    @Test fun clampTripEnd() = check("clampTripEnd") { a -> json(clampTripEnd(a[0].s(), a[1].s())) }

    @Test fun tripDayCount() = check("tripDayCount") { a -> json(tripDayCount(a[0].sOrNull(), a[1].sOrNull())) }

    @Test fun formatDayLabel() = check("formatDayLabel") { a -> json(formatDayLabel(a[0].s(), a[1].lang())) }

    @Test fun formatShortDate() = check("formatShortDate") { a -> json(formatShortDate(a[0].s(), a[1].lang())) }

    @Test fun formatMonthTitle() = check("formatMonthTitle") { a -> json(formatMonthTitle(a[0].s(), a[1].lang())) }

    @Test fun formatMonthYear() = check("formatMonthYear") { a -> json(formatMonthYear(a[0].s(), a[1].lang())) }

    @Test fun formatDateRange() = check("formatDateRange") { a -> json(formatDateRange(a[0].s(), a[1].s(), a[2].lang())) }

    @Test fun pickRangeDate() = check("pickRangeDate") { a ->
        val next = pickRangeDate(a[0].range(), a[1].s())
        buildJsonObject { put("start", json(next.start)); put("end", json(next.end)) }
    }

    @Test fun rangeEndOf() = check("rangeEndOf") { a -> json(rangeEndOf(a[0].range())) }

    // --- geometry and formatting -------------------------------------------------

    @Test fun haversine() = check("haversine") { a -> json(haversineDistanceMeters(a[0].latLng(), a[1].latLng())) }

    @Test fun hashString() = check("hashString") { a -> json(hashString(a[0].s())) }

    @Test fun bezierControlPoint() = check("bezierControlPoint") { a ->
        json(bezierControlPoint(a[0].point(), a[1].point(), a[2].d()))
    }

    @Test fun trimQuadraticBezier() = check("trimQuadraticBezier") { a ->
        val trimmed = trimQuadraticBezier(a[0].point(), a[1].point(), a[2].point(), a[3].d())
        buildJsonObject { put("control", json(trimmed.control)); put("end", json(trimmed.end)) }
    }

    @Test fun shortPlaceName() = check("shortPlaceName") { a -> json(shortPlaceName(a[0].s())) }

    @Test fun placeMatchesFromPhoton() = check("placeMatchesFromPhoton") { a ->
        JsonArray(
            placeMatchesFromPhoton(a[0].jsonArray).map { match ->
                buildJsonObject {
                    put("id", match.id)
                    put("name", match.name)
                    put("detail", match.detail)
                    put("kind", match.kind.name.lowercase())
                    put("location", AppJson.encodeToJsonElement(match.location))
                }
            },
        )
    }

    @Test fun formatDistance() = check("formatDistance") { a -> json(formatDistance(a[0].d(), Translator(a[1].lang()))) }

    @Test fun formatDuration() = check("formatDuration") { a -> json(formatDuration(a[0].d(), Translator(a[1].lang()))) }

    // --- plan editing --------------------------------------------------------------

    @Test fun movePlanStop() = check("movePlanStop") { a ->
        val from = a[1].jsonObject.let {
            StopRef(it.getValue("id").s(), it["day"]?.iOrNull(), it["index"]?.iOrNull() ?: -1)
        }
        planJson(movePlanStop(a[0].plan(), from, a[2].iOrNull(), a[3].iOrNull()))
    }

    @Test fun togglePlanDay() = check("togglePlanDay") { a -> planJson(togglePlanDay(a[0].plan(), a[1].s(), a[2].i())) }

    @Test fun swapPlanStop() = check("swapPlanStop") { a ->
        planJson(swapPlanStop(a[0].plan(), a[1].i(), a[2].i(), a[3].s()))
    }

    @Test fun removeFromPlan() = check("removeFromPlan") { a -> planJson(removeFromPlan(a[0].plan(), a[1].s())) }

    // --- the distance planner ---------------------------------------------------------

    @Test fun planDays() = check("planDays") { a ->
        val items = a[0].jsonArray.map {
            val o = it.jsonObject
            PlanItem(o.getValue("id").s(), o.getValue("location").latLng(), o.getValue("driveMinutes").d())
        }
        val base = if (a[3] is JsonNull) null else a[3].plan()
        planJson(planDays(items, a[1].i(), a[2].latLng(), base, a[4].i()))
    }

    @Test fun dayDriveMinutes() = check("dayDriveMinutes") { a -> json(dayDriveMinutes(a[0].jsonArray.map { it.d() })) }

    @Test fun scheduleUnscheduled() = check("scheduleUnscheduled") { a ->
        json(scheduleUnscheduled(a[0].trip(), a[1].home(), a[2].destinations()))
    }

    @Test fun firstOpenDay() = check("firstOpenDay") { a -> json(firstOpenDay(a[0].trip(), a[1].destinations())) }

    @Test fun replanTrip() = check("replanTrip") { a ->
        json(replanTrip(a[0].trip(), a[1].home(), a[2].destinations(), a[3].i(), a[4].jsonPrimitive.content.toBoolean()))
    }

    @Test fun unscheduled() = check("unscheduled") { a ->
        strings(unscheduledDestinations(a[0].trip(), a[1].destinations()).map { it.id })
    }

    @Test fun daysByDestination() = check("daysByDestination") { a ->
        buildJsonObject {
            for ((id, days) in daysByDestination(a[0].trip())) put(id, buildJsonArray { days.forEach { add(JsonPrimitive(it)) } })
        }
    }

    // --- suggestions -------------------------------------------------------------------

    @Test fun rankSuggestions() = check("rankSuggestions") { a ->
        val p = a[1].jsonObject
        val prefs = RankingPreferences(
            styles = AppJson.decodeFromJsonElement<List<TravelStyle>>(p.getValue("styles")),
            group = p.getValue("group").let { if (it is JsonNull) null else AppJson.decodeFromJsonElement<TravelGroup>(it) },
            mustHaves = AppJson.decodeFromJsonElement<List<MustHave>>(p.getValue("mustHaves")),
            budget = p.getValue("budget").iOrNull(),
        )
        JsonArray(
            rankSuggestions(a[0].suggestions(), prefs).map { ranked ->
                buildJsonObject {
                    put("id", ranked.suggestion.id)
                    put("score", ranked.score)
                    put(
                        "reasons",
                        strings(
                            ranked.reasons.map { reason ->
                                when (reason) {
                                    is MatchReason.Style -> "style:${reason.value.id}"
                                    is MatchReason.Group -> "group:${reason.value.id}"
                                    is MatchReason.Must -> "must:${reason.value.id}"
                                }
                            },
                        ),
                    )
                }
            },
        )
    }

    @Test fun reachable() = check("reachable") { a ->
        val limit = if (a[1] is JsonNull) null else a[1].d()
        val ferry = a[2].jsonPrimitive.content.toBoolean()
        strings(a[0].suggestions().filter { isReachable(it, limit, ferry) }.map { it.id })
    }

    @Test fun recommendedPlaceCount() = check("recommendedPlaceCount") { a -> json(recommendedPlaceCount(a[0].i())) }

    // --- migration ------------------------------------------------------------------------

    @Test fun migrate() = check("migrate") { a ->
        AppJson.encodeToJsonElement<VacationMapData>(migrateVacationMapData(a[0]))
    }

    @Test fun migrateIsIdempotent() {
        for (case in cases.filter { it.fn == "migrate" }) {
            val once = migrateVacationMapData(case.args[0])
            val twice = migrateVacationMapData(AppJson.encodeToJsonElement<VacationMapData>(once))
            assertTrue("migrating twice changed the data", once == twice)
        }
    }
}
