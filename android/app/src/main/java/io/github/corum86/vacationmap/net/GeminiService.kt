package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.logic.looksGarbled
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.LinkItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

// Same model as the web app (src/services/geminiService.ts).
private const val GEMINI_MODEL = "gemini-3.5-flash-lite"

// Research asks this model first, with Google Search grounding, so the sights
// and their links come from pages it has just read (see researchThings).
private const val GROUNDED_MODEL = "gemini-3.8-flash"

private fun geminiUrl(model: String) = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

// How long research goes without Google Search after a grounded call was
// refused for quota. A free-tier key has no search quota at all (every
// grounded call is a 429, whatever the model), so asking again for each
// place would only spend a request and the time it takes to fail.
private const val GROUNDING_PAUSE_MS = 10 * 60_000L

// Every prompt asks for JSON. A low temperature keeps the small model from
// drifting: at its default it wrote corrupted Greek (Latin, Cyrillic, even
// Chinese letters inside words) in about one answer in five.
private const val TEMPERATURE = 0.2

// Free-tier Gemini quota allows only a handful of requests per minute.
// Researching destinations one at a time with spacing, plus backing off on
// 429s, keeps us under that ceiling instead of bursting one request per
// destination and getting rate-limited.
const val DELAY_BETWEEN_DESTINATIONS_MS = 4000L
private const val MAX_RETRIES_ON_RATE_LIMIT = 3
private const val RETRY_BASE_DELAY_MS = 8000L

// 503 = model temporarily overloaded on Google's side. Retry once right
// away; if it happens again, wait this long and try one final time.
private const val MAX_RETRIES_ON_UNAVAILABLE = 2
private const val UNAVAILABLE_RETRY_DELAY_MS = 30_000L

class GeminiApiKeyMissingException : Exception("No Gemini API key configured.")

/** Gemini answered with an error status. */
class GeminiApiException(val status: Int, message: String) : Exception(message)

/** A failure with a message fit to show the user. */
class GeminiRequestException(message: String) : Exception(message)

private val LANGUAGE_NAMES = mapOf("en" to "English", "el" to "Greek")

data class DestinationTranslation(
    val name: String,
    val attractions: List<String>,
    val notes: String?,
    val links: List<LinkItem>,
)

/** A candidate place as the model describes it, before routing and validation. */
data class RawPlaceSuggestion(
    val name: String,
    val lat: Double,
    val lng: Double,
    val blurb: String,
    val tags: List<String>,
    val groups: List<String>,
    val budget: Double,
    val mustHaves: List<String>,
    val ferry: Boolean,
)

data class SuggestionRequest(
    val homeName: String,
    val home: LatLng,
    val startDate: String?,
    val endDate: String?,
    val lang: String,
)

internal fun stripCodeFences(raw: String): String =
    raw.replace(Regex("""^\s*```(?:json)?\s*""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s*```\s*$"""), "")

/** A sight as the model describes it, before its photo is looked up. */
internal data class RawThing(val name: String, val text: String, val url: String, val wiki: String)

private val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

// A grounded answer may cite Google's own redirect addresses, which stop
// working after a while: not something to save with a place.
private val SEARCH_REDIRECT_URL = Regex("""^https?://vertexaisearch\.cloud\.google\.com/""", RegexOption.IGNORE_CASE)

private fun isKeepableUrl(url: String) = HTTP_URL.containsMatchIn(url) && !SEARCH_REDIRECT_URL.containsMatchIn(url)

/** The JSON in an answer that may have a sentence or code fences around it. */
private fun parseJsonAnswer(raw: String): JsonElement {
    val text = stripCodeFences(raw)
    return try {
        AppJson.parseToJsonElement(text)
    } catch (e: Exception) {
        // a grounded answer can't be forced to be JSON only: take the object inside it
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw e
        AppJson.parseToJsonElement(text.substring(start, end + 1))
    }
}
private val WIKIPEDIA_URL = Regex("""^https?://[^/]*\bwikipedia\.org/""", RegexOption.IGNORE_CASE)
private val ENGLISH_WIKIPEDIA_PAGE = Regex("""^https?://en\.(?:m\.)?wikipedia\.org/wiki/([^?#]+)""", RegexOption.IGNORE_CASE)

internal fun parseThings(rawText: String?): List<RawThing> {
    if (rawText.isNullOrEmpty()) return emptyList()
    fun sentence(text: String) = RawThing(name = "", text = text, url = "", wiki = "")
    try {
        val parsed = parseJsonAnswer(rawText)
        // older prompt shapes: {"facts": [...]} or a bare array of sentences
        val list = parsed as? JsonArray ?: (parsed["things"] ?: parsed["facts"]) as? JsonArray
        if (list != null) {
            return list
                .map { item ->
                    item.string?.let { return@map sentence(it.trim()) }
                    val url = item["url"].string?.trim() ?: ""
                    RawThing(
                        name = item["name"].string?.trim() ?: "",
                        text = item["text"].string?.trim() ?: "",
                        url = if (isKeepableUrl(url)) url else "",
                        wiki = item["wiki"].string?.trim() ?: "",
                    )
                }
                .filter { it.name.isNotEmpty() || it.text.isNotEmpty() }
        }
    } catch (_: Exception) {
        // fall through to the line-splitting fallback below
    }
    return rawText.split('\n').map { it.replace(Regex("""^[-*\d.)\s]+"""), "").trim() }.filter { it.isNotEmpty() }.map(::sentence)
}

/** "Bourtzi Castle" from https://en.wikipedia.org/wiki/Bourtzi_Castle */
internal fun englishWikipediaTitle(url: String): String? {
    val path = ENGLISH_WIKIPEDIA_PAGE.find(url)?.groupValues?.get(1) ?: return null
    return try {
        // URLDecoder would also turn "+" into a space, which a path keeps
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8").replace('_', ' ')
    } catch (_: Exception) {
        null
    }
}

internal fun parsePlaceSuggestions(rawText: String?): List<RawPlaceSuggestion> {
    val parsed = try {
        AppJson.parseToJsonElement(stripCodeFences(rawText ?: ""))
    } catch (_: Exception) {
        throw GeminiRequestException("Gemini returned an unexpected format.")
    }
    if (parsed !is JsonArray) throw GeminiRequestException("Gemini returned an unexpected format.")
    fun strings(value: JsonElement?) = value.items.mapNotNull { it.string }
    // the model sometimes writes numbers as strings
    fun numeric(value: JsonElement?) = value.number ?: value.string?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    return parsed.mapNotNull { item ->
        if (item !is JsonObject) return@mapNotNull null
        val name = item["name"].string?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        RawPlaceSuggestion(
            name = name,
            lat = numeric(item["lat"]) ?: return@mapNotNull null,
            lng = numeric(item["lng"]) ?: return@mapNotNull null,
            blurb = item["blurb"].string?.trim() ?: "",
            tags = strings(item["tags"]),
            groups = strings(item["groups"]),
            budget = numeric(item["budget"])?.takeIf { it != 0.0 } ?: 2.0,
            mustHaves = strings(item["mustHaves"]),
            ferry = item["ferry"].boolean == true,
        )
    }
}

/**
 * Things to do, translations and day-trip ideas from Google's Gemini API,
 * called over plain REST. The key is baked into the build (see
 * app/build.gradle.kts); without one, every call throws
 * [GeminiApiKeyMissingException] and the UI explains.
 */
class GeminiService(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val wikimedia: WikimediaService,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** When research may ask with Google Search again (see [GROUNDING_PAUSE_MS]). */
    private var groundingPausedUntil = 0L

    /** One generateContent call. `search` asks `model` to look things up with Google Search first. */
    private suspend fun generate(contents: String, model: String = GEMINI_MODEL, search: Boolean = false): String? {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", contents) }) } })
            }
            if (search) putJsonArray("tools") { add(buildJsonObject { putJsonObject("google_search") {} }) }
            putJsonObject("generationConfig") {
                put("temperature", TEMPERATURE)
                // JSON mode is left off with search: the API has refused it together with
                // tools, and parseJsonAnswer copes with an answer that has more than the JSON in it
                if (!search) put("responseMimeType", "application/json")
            }
        }
        val request = Request.Builder()
            .url(geminiUrl(model))
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val json = try {
            client.requestJson(request)
        } catch (e: HttpStatusException) {
            val message = try {
                AppJson.parseToJsonElement(e.body)["error"]["message"].string
            } catch (_: Exception) {
                null
            }
            throw GeminiApiException(e.status, message ?: "HTTP ${e.status}")
        }
        val parts = json["candidates"][0]["content"]["parts"].items
        return parts.mapNotNull { it["text"].string }.joinToString("").takeIf { it.isNotEmpty() }
    }

    // Runs a plain generateContent call with the shared retry policy:
    // 429s back off exponentially, 503s (model overloaded) retry once
    // immediately and once more after a 30s wait. Throws the final error
    // once both budgets are exhausted.
    private suspend fun generateWithRetries(contents: String): String? {
        if (!isConfigured) throw GeminiApiKeyMissingException()
        var rateLimitRetries = 0
        var unavailableRetries = 0
        while (true) {
            try {
                return generate(contents)
            } catch (e: GeminiApiException) {
                if (e.status == 429 && rateLimitRetries < MAX_RETRIES_ON_RATE_LIMIT) {
                    delay(RETRY_BASE_DELAY_MS shl rateLimitRetries)
                    rateLimitRetries++
                    continue
                }
                if (e.status == 503 && unavailableRetries < MAX_RETRIES_ON_UNAVAILABLE) {
                    // First 503: retry immediately. Second 503: wait 30s, then one last try.
                    if (unavailableRetries == 1) delay(UNAVAILABLE_RETRY_DELAY_MS)
                    unavailableRetries++
                    continue
                }
                throw e
            }
        }
    }

    private fun formatError(err: Exception, fallback: String): String {
        if (err is GeminiApiException) {
            return when (err.status) {
                429 -> "Gemini rate limit (429) exceeded — your free-tier quota has been used up for now. " +
                    "Try again later, or wait a minute between searches. (${err.message})"
                503 -> "Gemini is temporarily overloaded (503) — retried ${MAX_RETRIES_ON_UNAVAILABLE + 1} times " +
                    "without success. Try again in a few minutes. (${err.message})"
                else -> "Gemini HTTP ${err.status}: ${err.message}"
            }
        }
        return err.message ?: fallback
    }

    // The model names the sights and a link for each; their photos come from
    // the Wikimedia APIs (see WikimediaService). Grounded, it searches the web
    // first and takes the links from what it found; otherwise both come from
    // its own knowledge.
    private fun researchPrompt(dest: Destination, lang: String, grounded: Boolean): String {
        val language = LANGUAGE_NAMES[lang] ?: "English"
        val search = if (grounded) "\nUse Google Search to check what is worth seeing and doing there now." else ""
        val url = if (grounded) {
            "the address of one page about it that your search found (official site, tourism board or Wikipedia), or \"\" if it found none"
        } else {
            "one relevant, well-known, stable web page about it (official site, tourism board or Wikipedia), or \"\" if you are not confident one exists"
        }
        return """
            You are researching the travel destination "${dest.name}" (near latitude ${dest.location.lat}, longitude ${dest.location.lng}).$search
            Suggest 6 specific sights or things to do there. For each give:
            - "name": its short proper name (the sight, beach, museum, walk, market…), in $language
            - "text": one or two sentences on what it is and why it is worth the visit, in $language
            - "url": $url
            - "wiki": the title of its English Wikipedia article, or "" if it has none
            Return ONLY a JSON object of the shape:
            {"things": [{"name": "", "text": "", "url": "", "wiki": ""}]}
            No markdown formatting, no code fences, no extra commentary.
        """.trimIndent()
    }

    /**
     * The sights for a place, and whether they are grounded: from the grounded
     * model when it answers, else from the plain one. Only the plain call is
     * retried and may throw; a grounded call that fails for any reason just
     * falls through to it.
     */
    private suspend fun researchThings(dest: Destination, lang: String): Pair<List<RawThing>, Boolean> {
        if (!isConfigured) throw GeminiApiKeyMissingException()
        // a sight whose text came out corrupted is left out rather than shown
        fun readable(rawText: String?) = parseThings(rawText).filterNot { looksGarbled("${it.name} ${it.text}", lang) }

        if (System.currentTimeMillis() >= groundingPausedUntil) {
            try {
                val things = readable(generate(researchPrompt(dest, lang, grounded = true), GROUNDED_MODEL, search = true))
                    .filter { it.name.isNotEmpty() }
                // prose instead of the JSON asked for reads as no named sights
                if (things.isNotEmpty()) return things to true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GeminiApiException && e.status == 429) groundingPausedUntil = System.currentTimeMillis() + GROUNDING_PAUSE_MS
            }
        }
        return readable(generateWithRetries(researchPrompt(dest, lang, grounded = false))) to false
    }

    /**
     * Give each sight its photo and link: its own Wikipedia article or Commons
     * photo when one matches, else one of the photos taken around the
     * destination, so a card is only left without a picture when there are none.
     */
    private suspend fun toFindings(things: List<RawThing>, dest: Destination, areaPhotos: List<AiPhoto>): List<AiFinding> {
        val matches = coroutineScope {
            things.map { thing ->
                async {
                    if (thing.name.isEmpty()) {
                        SightMatch()
                    } else {
                        wikimedia.fetchSight(thing.name, thing.wiki.ifEmpty { englishWikipediaTitle(thing.url) }, dest.location)
                    }
                }
            }.awaitAll()
        }
        val used = mutableSetOf<String>()
        val spare = ArrayDeque(areaPhotos)
        return things.mapIndexed { i, thing ->
            val match = matches[i]
            // two sights can resolve to the same article: only the first keeps its picture
            var photo = match.photo?.takeIf { it.imageUrl !in used }
            while (photo == null && spare.isNotEmpty()) photo = spare.removeFirst().takeIf { it.imageUrl !in used }
            if (photo != null) used += photo.imageUrl
            // a Wikipedia link comes from the lookup, which knows the article exists
            val ownUrl = if (WIKIPEDIA_URL.containsMatchIn(thing.url)) "" else thing.url
            val link = if (ownUrl.isNotEmpty()) AiLink(label = thing.name.ifEmpty { ownUrl }, url = ownUrl) else match.article
            AiFinding(id = newId(), name = thing.name, text = thing.text, photo = photo, link = link)
        }
    }

    /**
     * Things to do from Gemini, each with a photo from Wikimedia and a link,
     * for one place. A Gemini failure comes back as a result with `error`
     * set; only a missing key throws.
     */
    suspend fun fetchAiFindingsForDestination(dest: Destination, lang: String = "en"): DestinationAiResult = coroutineScope {
        // the photos around the destination load in parallel with the Gemini call
        val areaPhotos = async { wikimedia.fetchImagesForDestination(dest) }
        try {
            val (things, grounded) = researchThings(dest, lang)
            DestinationAiResult(
                destinationId = dest.id,
                destinationName = dest.name,
                grounded = grounded,
                findings = toFindings(things, dest, areaPhotos.await()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiKeyMissingException) {
            areaPhotos.cancel()
            throw e
        } catch (e: Exception) {
            areaPhotos.cancel()
            DestinationAiResult(dest.id, dest.name, error = formatError(e, "Gemini search failed."))
        }
    }

    /**
     * Translate a destination's saved text (name, attractions, notes, link
     * labels) into `targetLang`. URLs and ids are never touched. Throws
     * [GeminiRequestException] with a presentable message on failure.
     */
    suspend fun translateDestinationContent(dest: Destination, targetLang: String): DestinationTranslation {
        val language = LANGUAGE_NAMES[targetLang] ?: targetLang
        val payload = buildJsonObject {
            put("name", dest.name)
            put("attractions", buildJsonArray { dest.attractions.forEach { add(it) } })
            put("notes", dest.notes ?: "")
            put("linkLabels", buildJsonArray { dest.links.forEach { add(it.label) } })
        }
        val prompt = """
            Translate the string values in the JSON object below into $language.
            Rules:
            - Keep the exact same JSON shape and array lengths and order.
            - Translate naturally; keep proper nouns in their conventional $language form (or unchanged if none exists).
            - If a string is already in $language, return it unchanged.
            - Return ONLY the JSON object, no markdown, no code fences, no commentary.

        """.trimIndent() + "\n" + payload.toString()

        val rawText = try {
            generateWithRetries(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiKeyMissingException) {
            throw e
        } catch (e: Exception) {
            throw GeminiRequestException(formatError(e, "Translation failed."))
        }
        val obj = try {
            AppJson.parseToJsonElement(stripCodeFences(rawText ?: "")) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw GeminiRequestException("Translation failed: Gemini returned an unexpected format.")

        // Only accept fields that came back with the right shape; anything
        // malformed falls back to the original value rather than corrupting data.
        fun stringsOfLength(value: JsonElement?, size: Int): List<String>? {
            val items = (value as? JsonArray) ?: return null
            if (items.size != size) return null
            return items.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        }
        val linkLabels = stringsOfLength(obj["linkLabels"], dest.links.size) ?: dest.links.map { it.label }
        return DestinationTranslation(
            name = obj["name"].string?.trim()?.takeIf { it.isNotEmpty() } ?: dest.name,
            attractions = stringsOfLength(obj["attractions"], dest.attractions.size) ?: dest.attractions,
            notes = obj["notes"].string?.takeIf { it.isNotBlank() } ?: dest.notes,
            links = dest.links.mapIndexed { i, link -> link.copy(label = linkLabels[i].ifEmpty { link.label }) },
        )
    }

    // Asked once per planner run, before the traveller's answers are in: the
    // list is broad, and the app ranks it by their answers on the device.
    private fun suggestionsPrompt(req: SuggestionRequest): String {
        val whenText = if (req.startDate != null) " The trip runs ${req.startDate} to ${req.endDate ?: req.startDate}." else ""
        return """
            You are a local travel expert. A traveller stays at "${req.homeName}" (latitude ${req.home.lat}, longitude ${req.home.lng}) and makes day trips from there by car.$whenText
            Suggest 18 varied day-trip destinations within about 2.5 hours one-way drive, nearest first, including islands reachable by car ferry if there are any.
            For each give: a short name, accurate latitude and longitude, a one-sentence blurb, and these attributes:
            - "tags": any of ["relaxed","active","sightseeing","culture","food","nature"]
            - "groups": who it suits, any of ["couple","family","friends"]
            - "budget": 1 (cheap) to 3 (expensive)
            - "mustHaves": any of ["beach","food","kids","nightlife"]
            - "ferry": true only if reaching it requires a ferry crossing
            Write names and blurbs in ${LANGUAGE_NAMES[req.lang] ?: "English"}. Only include real places you are confident exist at those coordinates.
            Return ONLY a JSON array of objects: [{"name":"","lat":0,"lng":0,"blurb":"","tags":[],"groups":[],"budget":1,"mustHaves":[],"ferry":false}]
            No markdown formatting, no code fences, no extra commentary.
        """.trimIndent()
    }

    /** Ask Gemini for day-trip ideas around the home base (throws [GeminiRequestException] with a presentable message). */
    suspend fun fetchPlaceSuggestions(req: SuggestionRequest): List<RawPlaceSuggestion> {
        val rawText = try {
            generateWithRetries(suggestionsPrompt(req))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiKeyMissingException) {
            throw e
        } catch (e: Exception) {
            throw GeminiRequestException(formatError(e, "Suggestions failed."))
        }
        return parsePlaceSuggestions(rawText).filterNot { looksGarbled("${it.name} ${it.blurb}", req.lang) }
    }
}
