package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

// What the trip planner and the AI research find for a place, before the
// traveller decides what to keep.

data class AiPhoto(
    val imageUrl: String,
    /** the page the photo comes from */
    val sourceUrl: String,
    val sourceTitle: String,
)

data class AiLink(val label: String, val url: String)

/** One researched sight or thing to do: shown as a card with its photo, text and link. */
data class AiFinding(
    val id: String,
    /** the sight or activity; empty when the model only returned a sentence */
    val name: String,
    val text: String,
    val photo: AiPhoto? = null,
    val link: AiLink? = null,
)

data class DestinationAiResult(
    val destinationId: String,
    val destinationName: String,
    /** null on success */
    val error: String? = null,
    val findings: List<AiFinding> = emptyList(),
)

/** What a lookup of one sight found. */
data class SightMatch(
    val photo: AiPhoto? = null,
    /** the Wikipedia article about the sight, when it has one */
    val article: AiLink? = null,
)

private const val MAX_IMAGES = 8
private const val THUMB_WIDTH = 640
private const val FETCH_TIMEOUT_MS = 8000L

// a sight's photo must have been taken this close to its destination, which
// keeps the Syntagma Square of one town from showing another's
private const val SIGHT_RADIUS_KM = 15

// Skip non-photo files (maps, icons, audio) that geosearch can return.
private val PHOTO_EXTENSIONS = Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)

/**
 * Image search backed by the Wikimedia APIs (free, no key):
 *   1. Commons geosearch — photos actually taken near the destination's
 *      coordinates (namespace 6 = File pages).
 *   2. Fallback: Wikipedia article search by destination name, taking each
 *      article's lead image.
 *
 * A single sight is looked up by name instead (see [fetchSight]): its
 * Wikipedia article, else a Commons photo of that name taken nearby.
 */
class WikimediaService(private val client: OkHttpClient) {

    private suspend fun fetchJson(url: HttpUrl): JsonElement? =
        try {
            withTimeout(FETCH_TIMEOUT_MS) { client.getJson(url) }
        } catch (e: CancellationException) {
            // a timeout is an ordinary miss; a cancelled caller is not
            if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
        } catch (_: Exception) {
            null
        }

    private fun pagesOf(data: JsonElement?): List<JsonElement> = (data["query"]["pages"] as? JsonObject)?.values?.toList().orEmpty()

    /** Search results in rank order (the API returns pages unordered). */
    private fun byRank(pages: List<JsonElement>): List<JsonElement> = pages.sortedBy { it["index"].number ?: 0.0 }

    private fun cleanFileTitle(title: String?): String = (title ?: "").removePrefix("File:").replace(PHOTO_EXTENSIONS, "")

    private fun commonsPhotos(pages: List<JsonElement>): List<AiPhoto> = pages
        .filter { PHOTO_EXTENSIONS.containsMatchIn(it["title"].string ?: "") }
        .mapNotNull { page ->
            val info = page["imageinfo"][0]
            val imageUrl = info["thumburl"].string?.takeIf { it.isNotEmpty() } ?: info["url"].string ?: return@mapNotNull null
            AiPhoto(
                imageUrl = imageUrl,
                sourceUrl = info["descriptionurl"].string?.takeIf { it.isNotEmpty() } ?: imageUrl,
                sourceTitle = cleanFileTitle(page["title"].string),
            )
        }

    private suspend fun searchCommonsNearby(dest: Destination): List<AiPhoto> {
        val url = urlWithQuery(
            "https://commons.wikimedia.org/w/api.php",
            "action" to "query",
            "format" to "json",
            "generator" to "geosearch",
            "ggscoord" to "${dest.location.lat}|${dest.location.lng}",
            "ggsradius" to "10000", // meters (API max)
            "ggslimit" to (MAX_IMAGES * 2).toString(),
            "ggsnamespace" to "6", // File pages, i.e. the photos themselves
            "prop" to "imageinfo",
            "iiprop" to "url",
            "iiurlwidth" to THUMB_WIDTH.toString(),
        )
        return commonsPhotos(pagesOf(fetchJson(url))).take(MAX_IMAGES)
    }

    private suspend fun searchWikipediaByName(dest: Destination): List<AiPhoto> {
        val url = urlWithQuery(
            "https://en.wikipedia.org/w/api.php",
            "action" to "query",
            "format" to "json",
            "generator" to "search",
            "gsrsearch" to dest.name,
            "gsrlimit" to MAX_IMAGES.toString(),
            "prop" to "pageimages|info",
            "piprop" to "thumbnail",
            "pithumbsize" to THUMB_WIDTH.toString(),
            "inprop" to "url",
        )
        return byRank(pagesOf(fetchJson(url)))
            .mapNotNull { page ->
                val imageUrl = page["thumbnail"]["source"].string ?: return@mapNotNull null
                AiPhoto(
                    imageUrl = imageUrl,
                    sourceUrl = page["fullurl"].string?.takeIf { it.isNotEmpty() } ?: imageUrl,
                    sourceTitle = page["title"].string ?: dest.name,
                )
            }
            .take(MAX_IMAGES)
    }

    /** The English Wikipedia article with exactly this title (redirects followed), if there is one. */
    private suspend fun fetchWikipediaArticle(title: String): SightMatch {
        val url = urlWithQuery(
            "https://en.wikipedia.org/w/api.php",
            "action" to "query",
            "format" to "json",
            "titles" to title,
            "redirects" to "1",
            "prop" to "pageimages|info",
            "piprop" to "thumbnail",
            "pithumbsize" to THUMB_WIDTH.toString(),
            "inprop" to "url",
        )
        val page = pagesOf(fetchJson(url)).firstOrNull() as? JsonObject ?: return SightMatch()
        val pageTitle = page["title"].string?.takeIf { it.isNotEmpty() }
        val pageUrl = page["fullurl"].string?.takeIf { it.isNotEmpty() }
        // "missing" is present (as "") when no article has that title
        if (pageTitle == null || pageUrl == null || "missing" in page) return SightMatch()
        return SightMatch(
            photo = page["thumbnail"]["source"].string?.let { AiPhoto(imageUrl = it, sourceUrl = pageUrl, sourceTitle = pageTitle) },
            article = AiLink(label = pageTitle, url = pageUrl),
        )
    }

    /** Commons photos matching the name among those taken around the point, best match first. */
    private suspend fun searchCommonsByName(name: String, near: LatLng): List<AiPhoto> {
        val url = urlWithQuery(
            "https://commons.wikimedia.org/w/api.php",
            "action" to "query",
            "format" to "json",
            "generator" to "search",
            "gsrsearch" to "$name nearcoord:${SIGHT_RADIUS_KM}km,${near.lat},${near.lng}",
            "gsrnamespace" to "6",
            "gsrlimit" to "3",
            "prop" to "imageinfo",
            "iiprop" to "url",
            "iiurlwidth" to THUMB_WIDTH.toString(),
        )
        return commonsPhotos(byRank(pagesOf(fetchJson(url))))
    }

    /** Best-effort: any failure just means fewer/no images, never a thrown error. */
    suspend fun fetchImagesForDestination(dest: Destination): List<AiPhoto> =
        searchCommonsNearby(dest).ifEmpty { searchWikipediaByName(dest) }

    /**
     * Best-effort photo and Wikipedia article for one sight (empty when
     * nothing matches). `wikiTitle` is the title of its English Wikipedia
     * article as far as the model knows; `near` is the destination it belongs to.
     */
    suspend fun fetchSight(name: String, wikiTitle: String?, near: LatLng): SightMatch {
        val match = if (wikiTitle != null) fetchWikipediaArticle(wikiTitle) else SightMatch()
        if (match.photo != null) return match
        return match.copy(photo = searchCommonsByName(name, near).firstOrNull())
    }
}
