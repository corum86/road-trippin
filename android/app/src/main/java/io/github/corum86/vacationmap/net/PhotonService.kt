package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.logic.haversineDistanceMeters
import io.github.corum86.vacationmap.model.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.Locale
import kotlin.math.max

private const val PHOTON_REVERSE_URL = "https://photon.komoot.io/reverse"
private const val PHOTON_SEARCH_URL = "https://photon.komoot.io/api"

// the public server never returns more than this, nearest first
private const val PHOTON_LIMIT = 50
private const val SEARCH_LIMIT = 8

/** Shorter text matches half the world; wait for this much before searching. */
const val SEARCH_MIN_LENGTH = 2

// ask for more than the view so small pans stay inside what is loaded
private const val RADIUS_SLACK = 1.5

// a cut-off answer holds the places nearest its centre; asking again from
// this close (as a share of the distance it reached) would return the same
private const val SAME_ANSWER_SHARE = 0.3

enum class PlaceKind(val osmValue: String) { City("city"), Town("town"), Village("village") }

/** A settlement that can be picked from the map. */
data class MapPlace(val id: String, val name: String, val kind: PlaceKind, val location: LatLng)

/** Which settlements to offer at a zoom level: roughly what the basemap labels there. */
fun placeKindsForZoom(zoom: Double): List<PlaceKind> = when {
    zoom < 8 -> listOf(PlaceKind.City)
    zoom < 11 -> listOf(PlaceKind.City, PlaceKind.Town)
    else -> listOf(PlaceKind.City, PlaceKind.Town, PlaceKind.Village)
}

// One request per group. The rarer kinds get their own so the nearest-50
// cut-off of a crowded group (villages) can't push them out.
private fun requestGroups(zoom: Double): List<List<PlaceKind>> = when {
    zoom < 8 -> listOf(listOf(PlaceKind.City))
    zoom < 11 -> listOf(listOf(PlaceKind.City), listOf(PlaceKind.Town))
    else -> listOf(listOf(PlaceKind.City, PlaceKind.Town), listOf(PlaceKind.Village))
}

/** `Area`: a town, region or country · `Landmark`: a beach, peak, island… · `Address`: a building, business or street */
enum class PlaceMatchKind { Area, Landmark, Address }

/** A place found by name, anywhere in the world. */
data class PlaceMatch(
    val id: String,
    val name: String,
    /** where it is ("Epirus, Greece"), to tell namesakes apart; may be empty */
    val detail: String,
    val kind: PlaceMatchKind,
    val location: LatLng,
)

// Photon's own layers: house, street, locality, district, city, county, state, country, other
private val ADDRESS_LAYERS = setOf("house", "street")

private fun placeMatchKind(layer: String?): PlaceMatchKind = when {
    layer.isNullOrEmpty() || layer == "other" -> PlaceMatchKind.Landmark
    layer in ADDRESS_LAYERS -> PlaceMatchKind.Address
    else -> PlaceMatchKind.Area
}

/**
 * Photon's answers as matches to offer: named, without the repeats OSM holds
 * of one place (a town's point and its boundary), and with towns and
 * landmarks ahead of the businesses and streets named after them.
 */
fun placeMatchesFromPhoton(features: List<JsonElement>): List<PlaceMatch> {
    val seen = HashSet<String>()
    val matches = ArrayList<PlaceMatch>()
    for (feature in features) {
        val props = feature["properties"]
        val coords = feature["geometry"]["coordinates"]
        fun text(key: String): String? = props[key].string?.takeIf { it.isNotEmpty() }
        // an address without a name of its own goes by street and number
        val name = (text("name") ?: listOfNotNull(text("street"), text("housenumber")).joinToString(" ")).trim()
        if (name.isEmpty()) continue
        // GeoJSON is [lng, lat]
        val location = LatLng(coords[1].number ?: continue, coords[0].number ?: continue)
        val detail = listOfNotNull(text("city"), text("county"), text("state"), text("country"))
            .filter { it != name }
            .distinct()
            .joinToString(", ")
        if (!seen.add("$name|$detail")) continue
        matches += PlaceMatch(
            id = "${props["osm_type"].string}${props["osm_id"].number?.toLong()}",
            name = name,
            detail = detail,
            kind = placeMatchKind(props["type"].string),
            location = location,
        )
    }
    val (addresses, places) = matches.partition { it.kind == PlaceMatchKind.Address }
    return places + addresses
}

/**
 * City, town and village names around the map view, and places found by name,
 * from the free Photon geocoder (OpenStreetMap data). Answers are cached for
 * the session.
 */
class PhotonService(private val client: OkHttpClient) {

    private class Coverage(
        val lang: String,
        val kinds: String,
        val center: LatLng,
        /** every place of `kinds` within this distance of `center` is cached */
        val radiusKm: Double,
        val truncated: Boolean,
    )

    private val cache = HashMap<String, LinkedHashMap<String, MapPlace>>()
    private val coverage = ArrayList<Coverage>()

    // Photon speaks en/de/fr; 'default' is the local name (Greek within Greece)
    private fun photonLang(lang: Lang): String = if (lang == Lang.En) "en" else "default"

    private fun kindsKey(kinds: List<PlaceKind>): String = kinds.joinToString(",") { it.osmValue }

    private fun distanceKm(a: LatLng, b: LatLng): Double = haversineDistanceMeters(a, b) / 1000

    @Synchronized
    private fun isCovered(lang: String, kinds: String, center: LatLng, radiusKm: Double): Boolean =
        coverage.any { c ->
            if (c.lang != lang || c.kinds != kinds) return@any false
            val offset = distanceKm(center, c.center)
            offset + radiusKm <= c.radiusKm || (c.truncated && offset <= c.radiusKm * SAME_ANSWER_SHARE)
        }

    private suspend fun fetchGroup(lang: String, kinds: List<PlaceKind>, center: LatLng, radiusKm: Double): Boolean {
        val params = buildList {
            add("lat" to String.format(Locale.ROOT, "%.5f", center.lat))
            add("lon" to String.format(Locale.ROOT, "%.5f", center.lng))
            add("radius" to String.format(Locale.ROOT, "%.1f", radiusKm))
            add("limit" to PHOTON_LIMIT.toString())
            add("lang" to lang)
            for (kind in kinds) add("osm_tag" to "place:${kind.osmValue}")
        }
        val features = client.getJson(urlWithQuery(PHOTON_REVERSE_URL, *params.toTypedArray()))["features"].items

        synchronized(this) {
            val places = cache.getOrPut(lang) { LinkedHashMap() }
            var added = false
            var reachedKm = 0.0
            for (feature in features) {
                val props = feature["properties"]
                val coords = feature["geometry"]["coordinates"]
                val name = props["name"].string?.takeIf { it.isNotEmpty() } ?: continue
                val kind = kinds.firstOrNull { it.osmValue == props["osm_value"].string } ?: continue
                // GeoJSON is [lng, lat]
                val location = LatLng(coords[1].number ?: continue, coords[0].number ?: continue)
                reachedKm = max(reachedKm, distanceKm(center, location))
                val id = "${props["osm_type"].string}${props["osm_id"].number?.toLong()}"
                if (id !in places) {
                    places[id] = MapPlace(id, name, kind, location)
                    added = true
                }
            }
            val truncated = features.size >= PHOTON_LIMIT
            coverage += Coverage(lang, kindsKey(kinds), center, if (truncated) reachedKm else radiusKm, truncated)
            return added
        }
    }

    /** Places loaded so far, in the given language. */
    @Synchronized
    fun cachedPlaces(lang: Lang): List<MapPlace> = cache[photonLang(lang)]?.values?.toList().orEmpty()

    /** True when [loadPlaces] for this view would have to ask the server. */
    fun needsPlaces(center: LatLng, radiusKm: Double, zoom: Double, lang: Lang): Boolean =
        requestGroups(zoom).any { kinds -> !isCovered(photonLang(lang), kindsKey(kinds), center, radiusKm) }

    /**
     * Load the cities and towns (and, zoomed in, villages) around `center`
     * into the cache. Skips what earlier calls already cover. Returns true
     * when the cache gained places; throws when a request fails.
     */
    suspend fun loadPlaces(center: LatLng, radiusKm: Double, zoom: Double, lang: Lang): Boolean = coroutineScope {
        val pLang = photonLang(lang)
        requestGroups(zoom)
            .filter { kinds -> !isCovered(pLang, kindsKey(kinds), center, radiusKm) }
            .map { kinds -> async { fetchGroup(pLang, kinds, center, radiusKm * RADIUS_SLACK) } }
            .awaitAll()
            .any { it }
    }

    private val searchCache = HashMap<HttpUrl, List<PlaceMatch>>()

    /**
     * Places matching what has been typed so far, best match first; those
     * near `near` count as better matches. Answers are kept, so deleting a
     * letter does not ask again. Throws when the request fails.
     */
    suspend fun searchPlaces(query: String, lang: Lang, near: LatLng?): List<PlaceMatch> {
        val q = query.trim()
        if (q.length < SEARCH_MIN_LENGTH) return emptyList()
        val params = buildList {
            add("q" to q)
            add("limit" to SEARCH_LIMIT.toString())
            add("lang" to photonLang(lang))
            if (near != null) {
                // the bias only needs to know the region
                add("lat" to String.format(Locale.ROOT, "%.2f", near.lat))
                add("lon" to String.format(Locale.ROOT, "%.2f", near.lng))
            }
        }
        val url = urlWithQuery(PHOTON_SEARCH_URL, *params.toTypedArray())
        synchronized(this) { searchCache[url] }?.let { return it }
        val matches = placeMatchesFromPhoton(client.getJson(url)["features"].items)
        synchronized(this) { searchCache[url] = matches }
        return matches
    }
}
