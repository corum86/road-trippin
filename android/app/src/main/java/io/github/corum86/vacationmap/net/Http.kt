package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.model.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The free services this app talks to (OSM tiles, OSRM, Photon, Wikimedia)
// ask clients to identify themselves.
const val USER_AGENT = "VacationMap-Android/1.0 (+https://github.com/corum86/road-trippin)"

fun baseHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

/** Run the call without blocking a thread; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, unused, _ -> unused.close() }
        }
    })
}

class HttpStatusException(val status: Int, val body: String) : IOException("HTTP $status")

/** GET `url` and parse the JSON body; throws [HttpStatusException] on a non-2xx answer. */
suspend fun OkHttpClient.getJson(url: HttpUrl): JsonElement = requestJson(Request.Builder().url(url).build())

// The body is read (from the socket) and parsed off the caller's thread:
// Android refuses network reads on the main thread.
suspend fun OkHttpClient.requestJson(request: Request): JsonElement = withContext(Dispatchers.IO) {
    newCall(request).await().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) throw HttpStatusException(response.code, body)
        try {
            AppJson.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IOException("Unexpected response format", e)
        }
    }
}

/** `base` with the query parameters appended (a name may repeat). */
fun urlWithQuery(base: String, vararg params: Pair<String, String>): HttpUrl {
    val builder = base.toHttpUrl().newBuilder()
    for ((name, value) in params) builder.addQueryParameter(name, value)
    return builder.build()
}

// Lenient readers for JSON that comes from services (or an AI model) and may
// not have the expected shape: a wrong type reads as absent.

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonElement?.number: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

internal val JsonElement?.boolean: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal val JsonElement?.items: List<JsonElement> get() = (this as? JsonArray).orEmpty()
