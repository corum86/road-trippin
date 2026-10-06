package io.github.corum86.vacationmap.net

import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.VacationMapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// sync codes are v4 UUIDs: unguessable, so knowing one is what grants access to a map
private val SYNC_CODE_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** A code as typed or pasted, in the form the backend expects; null if it can't be one. */
fun normalizeSyncCode(code: String): String? = code.trim().lowercase().takeIf { SYNC_CODE_PATTERN.matches(it) }

/** The backend answered, but not with what was asked for. */
class CloudRequestException(val status: Int) : IOException("Cloud data request failed: HTTP $status") {
    /** nothing is listening (static hosting) or no database is configured: not worth retrying */
    val noBackend: Boolean get() = status == 404 || status == 405 || status == 501

    val tooLarge: Boolean get() = status == 413
}

class CloudSnapshot(
    /** counts the saves; null while nothing is saved under the code */
    val revision: Long?,
    /** absent when the revision asked about is still the current one */
    val data: JsonElement?,
)

/**
 * Client for the web app's /api/data endpoint (api/data.ts), which keeps one
 * MongoDB document per sync code. Both apps talk to the same backend, so a
 * phone and a browser sharing a code share one map.
 */
open class CloudDataApi(private val client: OkHttpClient, baseUrl: String) {
    private val endpoint = "${baseUrl.trimEnd('/')}/api/data"

    /** False when the build has no backend address: the data stays on this device. */
    val isConfigured: Boolean = baseUrl.isNotBlank()

    private suspend fun request(syncCode: String, query: String, configure: Request.Builder.() -> Unit = {}): JsonElement {
        val request = Request.Builder()
            .url("$endpoint?id=$syncCode$query")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply(configure)
            .build()
        // read and parse the body off the main thread, which may not touch the network
        return withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw CloudRequestException(response.code)
                // a static host may answer any path with its HTML page
                if (response.header("content-type")?.contains("application/json") != true) throw CloudRequestException(404)
                try {
                    AppJson.parseToJsonElement(response.body.string())
                } catch (e: IOException) {
                    throw e
                } catch (_: Exception) {
                    throw CloudRequestException(404)
                }
            }
        }
    }

    /** The map saved under `syncCode`; without its data if `knownRevision` is still current. */
    open suspend fun fetch(syncCode: String, knownRevision: Long?): CloudSnapshot {
        val json = request(syncCode, if (knownRevision == null) "" else "&rev=$knownRevision")
        return CloudSnapshot(revision = json["revision"].number?.toLong(), data = json["data"] as? JsonObject)
    }

    /** Save `data` under `syncCode`, replacing what is there; returns the new revision. */
    open suspend fun save(syncCode: String, data: VacationMapData): Long {
        // a map with photos inline can be megabytes: encode it off the main thread too
        val body = withContext(Dispatchers.Default) {
            buildJsonObject { put("data", AppJson.encodeToJsonElement<VacationMapData>(data)) }.toString()
        }
        val json = request(syncCode, "") { put(body.toRequestBody("application/json".toMediaType())) }
        return json["revision"].number?.toLong() ?: throw CloudRequestException(404)
    }
}
