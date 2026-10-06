package io.github.corum86.vacationmap.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import io.github.corum86.vacationmap.BuildConfig
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.map.OsmTileProvider
import io.github.corum86.vacationmap.map.TileProvider
import io.github.corum86.vacationmap.net.CloudDataApi
import io.github.corum86.vacationmap.net.GeminiService
import io.github.corum86.vacationmap.net.OsrmService
import io.github.corum86.vacationmap.net.PhotonService
import io.github.corum86.vacationmap.net.WikimediaService
import io.github.corum86.vacationmap.net.baseHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

/** The working copy of the data: one JSON file, replaced atomically on every save. */
class FileDataStorage(file: File) : DataStorage {
    private val file = AtomicFile(file)

    override fun read(): String? = try {
        file.readFully().toString(Charsets.UTF_8)
    } catch (_: java.io.IOException) {
        null
    }

    override fun write(json: String) {
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: java.io.IOException) {
            file.failWrite(stream)
            throw e
        }
    }
}

class PrefsSyncMetaStorage(private val prefs: SharedPreferences) : SyncMetaStorage {
    override fun read(): SyncMeta? {
        val syncCode = prefs.getString(KEY_CODE, null) ?: return null
        return SyncMeta(
            syncCode = syncCode,
            revision = if (prefs.contains(KEY_REVISION)) prefs.getLong(KEY_REVISION, 0) else null,
            dirty = prefs.getBoolean(KEY_DIRTY, true),
        )
    }

    override fun write(meta: SyncMeta) = prefs.edit {
        putString(KEY_CODE, meta.syncCode)
        if (meta.revision != null) putLong(KEY_REVISION, meta.revision) else remove(KEY_REVISION)
        putBoolean(KEY_DIRTY, meta.dirty)
    }

    private companion object {
        const val KEY_CODE = "sync_code"
        const val KEY_REVISION = "sync_revision"
        const val KEY_DIRTY = "sync_dirty"
    }
}

/** The app's language: chosen in the app, remembered on the device. */
class LanguageSetting(private val prefs: SharedPreferences?) {
    private val _lang = MutableStateFlow(
        when (prefs?.getString(KEY_LANG, null)) {
            "el" -> Lang.El
            "en" -> Lang.En
            // first launch: follow the device if it speaks Greek
            else -> if (Locale.getDefault().language == "el" && prefs != null) Lang.El else Lang.En
        },
    )
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    fun set(lang: Lang) {
        _lang.value = lang
        prefs?.edit { putString(KEY_LANG, lang.code) }
    }

    private companion object {
        const val KEY_LANG = "lang"
    }
}

/** Everything the UI works with besides its own state. */
class AppServices(
    val store: MapDataStore,
    val tiles: TileProvider,
    val osrm: OsrmService,
    val photon: PhotonService,
    val gemini: GeminiService,
    val wikimedia: WikimediaService,
    val cloudSync: CloudSync,
    val language: LanguageSetting,
)

val LocalServices = staticCompositionLocalOf<AppServices> { error("AppServices not provided") }

/** The real thing, wired to the device and the network. */
fun createAppServices(context: Context, mainScope: CoroutineScope = MainScope()): Pair<AppServices, OkHttpClient> {
    val prefs = context.getSharedPreferences("vacation-map", Context.MODE_PRIVATE)
    val http = baseHttpClient()
    // tiles are cached on disk, so the map opens instantly and survives being offline
    val tileHttp = http.newBuilder().cache(Cache(File(context.cacheDir, "tiles"), 100L * 1024 * 1024)).build()
    val store = MapDataStore(
        storage = FileDataStorage(File(context.filesDir, "vacation-map-data.json")),
        readSeed = { context.assets.open("vacation-data.json").bufferedReader().use { it.readText() } },
    )
    val wikimedia = WikimediaService(http)
    val services = AppServices(
        store = store,
        tiles = OsmTileProvider(tileHttp, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
        osrm = OsrmService(http),
        photon = PhotonService(http),
        gemini = GeminiService(http, BuildConfig.GEMINI_API_KEY, wikimedia),
        wikimedia = wikimedia,
        cloudSync = CloudSync(store, CloudDataApi(http, BuildConfig.CLOUD_API_BASE_URL), PrefsSyncMetaStorage(prefs), mainScope),
        language = LanguageSetting(prefs),
    )
    return services to http
}
