package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.logic.haversineDistanceMeters
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle

// the model sometimes invents coordinates; drop anything implausibly far away
private const val MAX_STRAIGHT_LINE_KM = 400.0

// a suggestion this close to a saved place is that place
private const val SAME_PLACE_KM = 1.5

private fun normalizeName(name: String): String = name.trim().lowercase()

/**
 * Day-trip suggestions around the home base: ideas from Gemini, matched to
 * already-saved places (so they keep the saved id), with real drive times
 * from one OSRM table request.
 */
suspend fun loadSuggestions(
    gemini: GeminiService,
    osrm: OsrmService,
    home: MainLocation,
    destinations: List<Destination>,
    lang: String,
    startDate: String? = null,
    endDate: String? = null,
): List<PlaceSuggestion> {
    val raw = gemini.fetchPlaceSuggestions(SuggestionRequest(home.name, home.location, startDate, endDate, lang))

    val seenNames = HashSet<String>()
    val candidates = raw.filter { s ->
        val km = haversineDistanceMeters(home.location, LatLng(s.lat, s.lng)) / 1000
        km <= MAX_STRAIGHT_LINE_KM && seenNames.add(normalizeName(s.name))
    }

    val drives = osrm.fetchDrivesFrom(home.location, candidates.map { LatLng(it.lat, it.lng) })

    return candidates.mapIndexed { i, s ->
        val location = LatLng(s.lat, s.lng)
        val saved = destinations.firstOrNull { d ->
            normalizeName(d.name) == normalizeName(s.name) || haversineDistanceMeters(d.location, location) / 1000 < SAME_PLACE_KM
        }
        PlaceSuggestion(
            id = saved?.id ?: newId(),
            name = s.name,
            location = location,
            blurb = s.blurb,
            styles = s.tags.mapNotNull { tag -> TravelStyle.entries.firstOrNull { it.id == tag } }.distinct(),
            groups = s.groups.mapNotNull { group -> TravelGroup.entries.firstOrNull { it.id == group } }.distinct(),
            budget = Math.round(s.budget).toInt().coerceIn(1, 3),
            mustHaves = s.mustHaves.mapNotNull { must -> MustHave.entries.firstOrNull { it.id == must } }.distinct(),
            ferry = s.ferry,
            driveMinutes = drives[i].minutes.coerceAtLeast(0.0),
            distanceKm = drives[i].km.coerceAtLeast(0.0),
            estimated = drives[i].estimated,
        )
    }
}
