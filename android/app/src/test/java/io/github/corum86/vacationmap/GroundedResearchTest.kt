package io.github.corum86.vacationmap

import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.net.GeminiService
import io.github.corum86.vacationmap.net.WikimediaService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Research asks the search-grounded model first and falls back to the plain one. */
class GroundedResearchTest {
    private val nafplio = Destination(id = "d1", name = "Nafplio", location = LatLng(37.5673, 22.8016))

    /** what was asked of Gemini: "model", plus "+search" and "+json" as the request had them */
    private val asked = ArrayList<String>()

    private fun geminiAnswer(text: String) = buildJsonObject {
        putJsonArray("candidates") {
            add(buildJsonObject { putJsonObject("content") { putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } } })
        }
    }.toString()

    /** Gemini as `gemini` answers it (status to body); Wikimedia finds nothing. */
    private fun service(gemini: (search: Boolean) -> Pair<Int, String>): GeminiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request: Request = chain.request()
                val (status, body) = if (request.url.host == "generativelanguage.googleapis.com") {
                    val sent = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    val search = "google_search" in sent
                    val model = request.url.pathSegments.last().substringBefore(':')
                    asked += model + (if (search) "+search" else "") + (if ("responseMimeType" in sent) "+json" else "")
                    gemini(search)
                } else {
                    200 to "{}"
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(if (status == 200) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return GeminiService(client, "test-key", WikimediaService(client))
    }

    private val plainAnswer = """{"things":[{"name":"Palamidi","text":"A fortress.","url":"https://example.org/palamidi","wiki":""}]}"""

    @Test
    fun aGroundedAnswerIsUsedAndItsRedirectLinksAreNot() {
        // not JSON only: a sentence before, code fences, a line after
        val grounded = "Here is what I found.\n```json\n" +
            """{"things":[
              {"name":"Palamidi Fortress","text":"Venetian fortress above the town.","url":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC","wiki":""},
              {"name":"Bourtzi Castle","text":"Island fort in the harbour.","url":"https://www.visitnafplio.com/bourtzi","wiki":""}
            ]}""" + "\n```\nSources: two pages."
        val gemini = service { search -> 200 to geminiAnswer(if (search) grounded else plainAnswer) }

        val result = runBlocking { gemini.fetchAiFindingsForDestination(nafplio, "en") }

        assertEquals(listOf("gemini-3.8-flash+search"), asked)
        assertTrue(result.grounded)
        assertEquals(listOf("Palamidi Fortress", "Bourtzi Castle"), result.findings.map { it.name })
        assertNull(result.findings[0].link)
        assertEquals("https://www.visitnafplio.com/bourtzi", result.findings[1].link?.url)
    }

    @Test
    fun aKeyWithoutSearchQuotaFallsBackAndStopsAsking() {
        val refused = """{"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED"}}"""
        val gemini = service { search -> if (search) 429 to refused else 200 to geminiAnswer(plainAnswer) }

        val first = runBlocking { gemini.fetchAiFindingsForDestination(nafplio, "en") }
        val second = runBlocking { gemini.fetchAiFindingsForDestination(nafplio.copy(id = "d2"), "en") }

        // refused once, then not tried again for the next place
        assertEquals(listOf("gemini-3.8-flash+search", "gemini-3.5-flash-lite+json", "gemini-3.5-flash-lite+json"), asked)
        assertFalse(first.grounded)
        assertNull(first.error)
        assertEquals(listOf("Palamidi"), first.findings.map { it.name })
        assertEquals(listOf("Palamidi"), second.findings.map { it.name })
    }

    @Test
    fun aGroundedAnswerWithoutSightsFallsBackButIsTriedAgain() {
        val gemini = service { search -> 200 to geminiAnswer(if (search) "I could not find anything certain." else plainAnswer) }

        val first = runBlocking { gemini.fetchAiFindingsForDestination(nafplio, "en") }
        runBlocking { gemini.fetchAiFindingsForDestination(nafplio.copy(id = "d2"), "en") }

        assertFalse(first.grounded)
        assertEquals(listOf("Palamidi"), first.findings.map { it.name })
        assertEquals(
            listOf("gemini-3.8-flash+search", "gemini-3.5-flash-lite+json", "gemini-3.8-flash+search", "gemini-3.5-flash-lite+json"),
            asked,
        )
    }
}
