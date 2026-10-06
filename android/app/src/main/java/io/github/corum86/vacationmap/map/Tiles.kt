package io.github.corum86.vacationmap.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.corum86.vacationmap.net.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A map tile in the usual z/x/y scheme. */
data class TileKey(val z: Int, val x: Int, val y: Int)

const val MAX_TILE_ZOOM = 19

/** Side of a tile image in pixels. */
const val TILE_PIXELS = 256

/** Where the map's background images come from. */
interface TileProvider {
    /** Changes whenever a requested tile has arrived; reading it while drawing redraws the map then. */
    val generation: Int

    /** The tile if it is in memory, without loading it. */
    fun cached(key: TileKey): ImageBitmap?

    /** Start loading the tiles that aren't in memory yet. */
    fun request(keys: Collection<TileKey>)

    /** The tile, loaded if need be; null when it can't be had. */
    suspend fun load(key: TileKey): ImageBitmap?
}

/**
 * The tile zoom to draw the camera zoom with. Tiles are fetched one level
 * deeper than the camera's and shown at half size, like the web app does:
 * twice the map detail per layout unit, which is what keeps the map (and
 * exported images) sharp on high-density screens.
 */
internal fun tileZoomFor(zoom: Double): Int = (Math.round(zoom).toInt() + 1).coerceIn(0, MAX_TILE_ZOOM)

/** The tiles covering a viewport of `size` pixels, nearest the centre first. */
internal fun visibleTiles(state: MapState, size: Size): List<TileKey> {
    if (size.width <= 0f || size.height <= 0f) return emptyList()
    val tz = tileZoomFor(state.zoom)
    val n = 1 shl tz
    val tileSize = state.worldSize / n
    val left = state.centerX * state.worldSize - size.width / 2.0
    val top = state.centerY * state.worldSize - size.height / 2.0
    val x0 = floor(left / tileSize).toInt()
    val x1 = floor((left + size.width) / tileSize).toInt()
    val y0 = max(0, floor(top / tileSize).toInt())
    val y1 = min(n - 1, floor((top + size.height) / tileSize).toInt())
    val cx = (x0 + x1) / 2.0
    val cy = (y0 + y1) / 2.0
    val keys = ArrayList<Pair<Double, TileKey>>()
    for (ty in y0..y1) {
        for (tx in x0..x1) {
            keys += ((tx - cx).pow(2) + (ty - cy).pow(2)) to TileKey(tz, Math.floorMod(tx, n), ty)
        }
    }
    return keys.sortedBy { it.first }.map { it.second }.distinct()
}

/**
 * OpenStreetMap's standard tiles. Tiles are kept in memory for drawing and on
 * disk by the HTTP cache (`client` must have one), which also serves them
 * when the device is offline.
 */
class OsmTileProvider(private val client: OkHttpClient, private val scope: CoroutineScope) : TileProvider {
    override var generation by mutableIntStateOf(0)
        private set

    // ~256 KB each
    private val memory = LruCache<TileKey, ImageBitmap>(192)
    private val inFlight = HashMap<TileKey, Deferred<ImageBitmap?>>()
    private val failedAt = HashMap<TileKey, Long>()
    private val connections = Semaphore(6)

    override fun cached(key: TileKey): ImageBitmap? = memory.get(key)

    override fun request(keys: Collection<TileKey>) {
        for (key in keys) {
            if (memory.get(key) != null) continue
            val failed = synchronized(failedAt) { failedAt[key] }
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) continue
            start(key)
        }
    }

    override suspend fun load(key: TileKey): ImageBitmap? = memory.get(key) ?: start(key).await()

    private fun start(key: TileKey): Deferred<ImageBitmap?> = synchronized(inFlight) {
        inFlight[key]?.let { return it }
        val result = CompletableDeferred<ImageBitmap?>()
        inFlight[key] = result
        scope.launch {
            val tile = try {
                connections.withPermit { fetch(key) }
            } catch (_: Exception) {
                null
            }
            if (tile != null) {
                memory.put(key, tile)
                synchronized(failedAt) { failedAt.remove(key) }
            } else {
                synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
            }
            synchronized(inFlight) { inFlight.remove(key) }
            result.complete(tile)
            if (tile != null) generation++
        }
        result
    }

    private suspend fun fetch(key: TileKey): ImageBitmap? {
        val request = Request.Builder().url("$TILE_URL/${key.z}/${key.x}/${key.y}.png").build()
        val bytes = try {
            client.newCall(request).await().use { response -> if (response.isSuccessful) response.body.bytes() else null }
        } catch (_: IOException) {
            // offline: an expired copy from the disk cache beats a blank map
            val cachedOnly = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
            client.newCall(cachedOnly).await().use { response -> if (response.isSuccessful) response.body.bytes() else null }
        } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    private companion object {
        const val TILE_URL = "https://tile.openstreetmap.org"
        const val RETRY_AFTER_MS = 5000L
    }
}
