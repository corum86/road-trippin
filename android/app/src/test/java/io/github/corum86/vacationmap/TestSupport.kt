package io.github.corum86.vacationmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import io.github.corum86.vacationmap.data.DataStorage
import io.github.corum86.vacationmap.data.MapDataStore
import io.github.corum86.vacationmap.map.TileKey
import io.github.corum86.vacationmap.map.TileProvider
import io.github.corum86.vacationmap.net.USER_AGENT
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** The web app's seed data, as bundled into the APK. */
fun seedJson(): String =
    ApplicationProvider.getApplicationContext<android.content.Context>().assets.open("vacation-data.json")
        .bufferedReader().use { it.readText() }

class MemoryStorage(var saved: String? = null) : DataStorage {
    override fun read(): String? = saved

    override fun write(json: String) {
        saved = json
    }
}

fun loadedStore(json: String = seedJson()): MapDataStore =
    MapDataStore(MemoryStorage(), readSeed = { json }).apply { loadInitialData() }

/**
 * Tiles for screenshots, available at once. By default a plain stand-in for
 * the map (so the tests need no network); with -PrealTiles, real
 * OpenStreetMap tiles, downloaded once into build/test-tiles.
 */
class TestTileProvider : TileProvider {
    override var generation by mutableIntStateOf(0)
        private set

    private val real = System.getProperty("vacationmap.realTiles") == "true"
    private val cache = HashMap<TileKey, ImageBitmap>()

    override fun cached(key: TileKey): ImageBitmap? = cache.getOrPut(key) { (if (real) download(key) else null) ?: placeholder(key) }

    override fun request(keys: Collection<TileKey>) = Unit

    override suspend fun load(key: TileKey): ImageBitmap? = cached(key)

    private fun download(key: TileKey): ImageBitmap? {
        val file = File("build/test-tiles/${key.z}/${key.x}/${key.y}.png")
        if (!file.isFile) {
            try {
                val connection = URI("https://tile.openstreetmap.org/${key.z}/${key.x}/${key.y}.png").toURL().openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val bytes = connection.inputStream.use { it.readBytes() }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            } catch (_: Exception) {
                return null
            }
        }
        return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }

    private fun placeholder(key: TileKey): ImageBitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if ((key.x + key.y) % 2 == 0) Color.rgb(234, 239, 233) else Color.rgb(226, 233, 226))
        val paint = Paint().apply {
            color = Color.rgb(170, 185, 172)
            textSize = 18f
            isAntiAlias = true
        }
        canvas.drawText("${key.z}/${key.x}/${key.y}", 10f, 26f, paint)
        return bitmap.asImageBitmap()
    }
}

class MemorySyncMeta(var meta: io.github.corum86.vacationmap.data.SyncMeta? = null) : io.github.corum86.vacationmap.data.SyncMetaStorage {
    override fun read() = meta

    override fun write(meta: io.github.corum86.vacationmap.data.SyncMeta) {
        this.meta = meta
    }
}

/**
 * An HTTP client that never touches the network: `respond` answers a request
 * with a JSON body, or null to fail it like a device that is offline.
 */
fun fakeHttpClient(respond: (okhttp3.Request) -> String? = { null }): okhttp3.OkHttpClient =
    okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val body = respond(request) ?: throw java.io.IOException("offline (test)")
            okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("content-type", "application/json")
                .body(body.toResponseBody())
                .build()
        }
        .build()

/**
 * Keeps photos from loading over the real network in tests (they show their
 * striped placeholder instead). With -PrealTiles the network is wanted, so
 * photos load too.
 */
@OptIn(coil3.annotation.DelicateCoilApi::class)
fun useOfflineImages() {
    if (System.getProperty("vacationmap.realTiles") == "true") return
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    coil3.SingletonImageLoader.setUnsafe(
        coil3.ImageLoader.Builder(context)
            .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { fakeHttpClient() })) }
            .build(),
    )
}

/**
 * Robolectric's native graphics look up the java.nio buffer classes the first
 * time a buffer crosses into native code. When that first time is on a
 * background thread (an image loader's, say) the lookup fails and aborts the
 * JVM ("JniConstants: Class not found: java/nio/…", exit 134). Doing it once
 * on the test thread first avoids that.
 */
fun warmUpNativeGraphics() {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.allocate(bitmap.byteCount))
}

/** The app's services with memory storage, stand-in tiles and a fake network. */
fun testServices(
    store: MapDataStore = loadedStore(),
    lang: io.github.corum86.vacationmap.i18n.Lang = io.github.corum86.vacationmap.i18n.Lang.En,
    geminiKey: String = "",
    http: okhttp3.OkHttpClient = fakeHttpClient(),
): io.github.corum86.vacationmap.data.AppServices {
    warmUpNativeGraphics()
    useOfflineImages()
    val wikimedia = io.github.corum86.vacationmap.net.WikimediaService(http)
    return io.github.corum86.vacationmap.data.AppServices(
        store = store,
        tiles = TestTileProvider(),
        osrm = io.github.corum86.vacationmap.net.OsrmService(http),
        photon = io.github.corum86.vacationmap.net.PhotonService(http),
        gemini = io.github.corum86.vacationmap.net.GeminiService(http, geminiKey, wikimedia),
        wikimedia = wikimedia,
        cloudSync = io.github.corum86.vacationmap.data.CloudSync(
            store,
            io.github.corum86.vacationmap.net.CloudDataApi(http, "https://example.invalid"),
            MemorySyncMeta(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        ),
        language = io.github.corum86.vacationmap.data.LanguageSetting(null).apply { set(lang) },
    )
}
