package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.LinkItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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

// Same model as the web app (src/services/geminiService.ts).
private const val GEMINI_MODEL = "gemini-3.5-flash-lite"
private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

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

internal data class ParsedFindings(val facts: List<String>, val links: List<AiLinkFinding>)

internal fun parseFindings(rawText: String?): ParsedFindings {
    if (rawText.isNullOrEmpty()) return ParsedFindings(emptyList(), emptyList())
    fun facts(items: List<JsonElement>) = items.mapNotNull { it.string?.takeIf { fact -> fact.isNotBlank() } }
    try {
        val parsed = AppJson.parseToJsonElement(stripCodeFences(rawText))
        if (parsed is JsonObject) {
            val links = parsed["links"].items.mapNotNull { link ->
                val url = link["url"].string?.takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    ?: return@mapNotNull null
                AiLinkFinding(id = newId(), label = link["title"].string?.trim()?.takeIf { it.isNotEmpty() } ?: url, url = url)
            }
            return ParsedFindings(facts(parsed["facts"].items), links)
        }
        // Older prompt shape: a bare JSON array of fact strings.
        if (parsed is JsonArray) return ParsedFindings(facts(parsed), emptyList())
    } catch (_: Exception) {
        // fall through to the line-splitting fallback below
    }
    return ParsedFindings(
        facts = rawText.split('\n').map { it.replace(Regex("""^[-*\d.)\s]+"""), "").trim() }.filter { it.isNotEmpty() },
        links = emptyList(),
    )
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
 * Facts, links, translations and day-trip ideas from Google's Gemini API,
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

    private suspend fun generate(contents: String): String? {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", contents) }) } })
            }
        }
        val request = Request.Builder()
            .url(GEMINI_URL)
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

    // Google Search grounding has its own free-tier quota that 429s even when
    // plain generateContent works fine. So the model provides facts and
    // well-known links from its own knowledge, and images come from the
    // Wikimedia APIs instead (see WikimediaService).
    private fun researchPrompt(dest: Destination, lang: String): String =
        """
        You are researching the travel destination "${dest.name}" (near latitude ${dest.location.lat}, longitude ${dest.location.lng}).
        Provide 5 short, independent, interesting facts or things to do there, plus up to 5 relevant, well-known, stable URLs (official tourism sites, Wikipedia, notable attractions).
        Write the facts and link titles in ${LANGUAGE_NAMES[lang] ?: "English"}.
        Only include URLs you are confident actually exist. Return ONLY a JSON object of the shape:
        {"facts": ["fact 1", "..."], "links": [{"title": "page title", "url": "https://..."}]}
        No markdown formatting, no code fences, no extra commentary.
        """.trimIndent()

    /**
     * Facts and links from Gemini plus photos from Wikimedia for one place.
     * A Gemini failure comes back as a result with `error` set; only a
     * missing key throws.
     */
    suspend fun fetchAiFindingsForDestination(dest: Destination, lang: String = "en"): DestinationAiResult = coroutineScope {
        // the Wikimedia image lookup runs in parallel with the Gemini call
        val images = async { wikimedia.fetchImagesForDestination(dest) }
        try {
            val findings = parseFindings(generateWithRetries(researchPrompt(dest, lang)))
            DestinationAiResult(
                destinationId = dest.id,
                destinationName = dest.name,
                images = images.await(),
                texts = findings.facts.map { AiTextFinding(newId(), it) },
                links = findings.links,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiKeyMissingException) {
            images.cancel()
            throw e
        } catch (e: Exception) {
            images.cancel()
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
        return parsePlaceSuggestions(rawText)
    }
}
