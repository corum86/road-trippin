package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

// What the trip planner and the AI research find for a place, before the
// traveller decides what to keep.

data class AiImageFinding(val id: String, val imageUrl: String, val sourceUrl: String, val sourceTitle: String)

data class AiTextFinding(val id: String, val fact: String)

data class AiLinkFinding(val id: String, val label: String, val url: String)

data class DestinationAiResult(
    val destinationId: String,
    val destinationName: String,
    /** null on success */
    val error: String? = null,
    val images: List<AiImageFinding> = emptyList(),
    val texts: List<AiTextFinding> = emptyList(),
    val links: List<AiLinkFinding> = emptyList(),
)

private const val MAX_IMAGES = 8
private const val THUMB_WIDTH = 640
private const val FETCH_TIMEOUT_MS = 8000L

// Skip non-photo files (maps, icons, audio) that geosearch can return.
private val PHOTO_EXTENSIONS = Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)

/**
 * Image search backed by the Wikimedia APIs (free, no key):
 *   1. Commons geosearch — photos actually taken near the destination's
 *      coordinates (namespace 6 = File pages).
 *   2. Fallback: Wikipedia article search by destination name, taking each
 *      article's lead image.
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

    private fun cleanFileTitle(title: String?): String = (title ?: "").removePrefix("File:").replace(PHOTO_EXTENSIONS, "")

    private suspend fun searchCommonsNearby(dest: Destination): List<AiImageFinding> {
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
        return pagesOf(fetchJson(url))
            .filter { PHOTO_EXTENSIONS.containsMatchIn(it["title"].string ?: "") }
            .mapNotNull { page ->
                val info = page["imageinfo"][0]
                val imageUrl = info["thumburl"].string?.takeIf { it.isNotEmpty() } ?: info["url"].string ?: return@mapNotNull null
                AiImageFinding(
                    id = newId(),
                    imageUrl = imageUrl,
                    sourceUrl = info["descriptionurl"].string?.takeIf { it.isNotEmpty() } ?: imageUrl,
                    sourceTitle = cleanFileTitle(page["title"].string),
                )
            }
            .take(MAX_IMAGES)
    }

    private suspend fun searchWikipediaByName(dest: Destination): List<AiImageFinding> {
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
        return pagesOf(fetchJson(url))
            .mapNotNull { page ->
                val imageUrl = page["thumbnail"]["source"].string ?: return@mapNotNull null
                AiImageFinding(
                    id = newId(),
                    imageUrl = imageUrl,
                    sourceUrl = page["fullurl"].string?.takeIf { it.isNotEmpty() } ?: imageUrl,
                    sourceTitle = page["title"].string ?: dest.name,
                )
            }
            .take(MAX_IMAGES)
    }

    /** Best-effort: any failure just means fewer/no images, never a thrown error. */
    suspend fun fetchImagesForDestination(dest: Destination): List<AiImageFinding> =
        searchCommonsNearby(dest).ifEmpty { searchWikipediaByName(dest) }
}
