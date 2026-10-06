package io.github.corum86.vacationmap.data

import io.github.corum86.vacationmap.logic.dataVersionOf
import io.github.corum86.vacationmap.logic.migrateVacationMapData
import io.github.corum86.vacationmap.model.CURRENT_DATA_VERSION
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.net.CloudDataApi
import io.github.corum86.vacationmap.net.CloudRequestException
import io.github.corum86.vacationmap.net.normalizeSyncCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

// long enough to gather a burst of edits (dragging stops, typing) into one save
private const val SAVE_DELAY_MS = 1000L
private const val RETRY_DELAY_MS = 30_000L

enum class SyncStatus(val id: String) {
    Connecting("connecting"),

    /** no backend to talk to: the data lives on this device only */
    Unavailable("unavailable"),
    Saving("saving"),
    Synced("synced"),

    /** the cloud can't be reached; changes wait on this device */
    Offline("offline"),

    /** the map is over the backend's size limit */
    TooLarge("tooLarge"),

    /** the cloud copy was written by a newer build than this one */
    Outdated("outdated"),
}

enum class ConnectResult { Ok, Invalid, NotFound, Outdated, Failed }

data class SyncMeta(
    /** names this device's cloud copy; devices that use the same code share one map */
    val syncCode: String,
    /** the cloud revision this device last saw; null while nothing is saved under the code */
    val revision: Long?,
    /** this device has changes the cloud copy lacks */
    val dirty: Boolean,
)

interface SyncMetaStorage {
    fun read(): SyncMeta?

    fun write(meta: SyncMeta)
}

/**
 * Keeps the map saved in MongoDB (through the web app's /api/data) in step
 * with the store: the Kotlin counterpart of src/store/cloudSync.ts.
 *
 * The file on this device stays the working copy, so the app opens at once
 * and works offline. This class uploads every change and brings in what
 * other devices with the same sync code saved. It syncs the map as a whole
 * and the last write wins: a device with changes of its own uploads them
 * over the cloud copy.
 *
 * Everything here runs on `scope`'s (single-threaded) dispatcher.
 */
class CloudSync(
    private val store: MapDataStore,
    private val api: CloudDataApi,
    private val metaStorage: SyncMetaStorage,
    private val scope: CoroutineScope,
) {
    private val savedMeta: SyncMeta? = metaStorage.read()?.let { saved ->
        normalizeSyncCode(saved.syncCode)?.let { saved.copy(syncCode = it) }
    }
    private var meta: SyncMeta = savedMeta ?: SyncMeta(syncCode = newId(), revision = null, dirty = false)

    private val _status = MutableStateFlow(SyncStatus.Connecting)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _syncCode = MutableStateFlow(meta.syncCode)
    val syncCode: StateFlow<String> = _syncCode.asStateFlow()

    /** the store's data known to match the cloud copy */
    private var syncedData: VacationMapData? = null
    private var saveJob: Job? = null
    private var retryJob: Job? = null
    private var running = false
    private var started = false

    private fun saveMeta(next: SyncMeta) {
        meta = next
        try {
            metaStorage.write(next)
        } catch (_: Exception) {
            // storage unavailable or full: syncing still works for this session
        }
    }

    /** Syncing can't work for the rest of this session. */
    private fun isStopped(): Boolean = _status.value == SyncStatus.Unavailable || _status.value == SyncStatus.Outdated

    /** Replace this device's data with the cloud copy. */
    private fun adopt(remote: JsonElement, revision: Long) {
        val data = migrateVacationMapData(remote)
        syncedData = data
        saveMeta(meta.copy(revision = revision, dirty = false))
        store.adoptRemote(data)
    }

    /** Switch this device to the map saved under `code`, replacing its data. */
    suspend fun connect(code: String): ConnectResult {
        val syncCode = normalizeSyncCode(code) ?: return ConnectResult.Invalid
        if (syncCode == meta.syncCode) return ConnectResult.Ok
        if (!api.isConfigured) return ConnectResult.Failed
        return try {
            val snapshot = api.fetch(syncCode, null)
            val remote = snapshot.data
            if (snapshot.revision == null || remote == null) return ConnectResult.NotFound
            if ((dataVersionOf(remote) ?: 0) > CURRENT_DATA_VERSION) return ConnectResult.Outdated
            saveJob?.cancel()
            retryJob?.cancel()
            // changes not yet saved under the old code are dropped along with its data
            saveMeta(meta.copy(syncCode = syncCode))
            adopt(remote, snapshot.revision)
            _syncCode.value = syncCode
            _status.value = SyncStatus.Synced
            ConnectResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ConnectResult.Failed
        }
    }

    /** Bring in what another device saved. */
    private suspend fun pull() {
        val syncCode = meta.syncCode
        val snapshot = api.fetch(syncCode, meta.revision)
        // switched to another map, or edited, while the request was out
        if (meta.syncCode != syncCode || meta.dirty) return
        if (snapshot.revision == null) {
            // the cloud copy this device once saved is gone: save it again
            if (meta.revision != null) saveMeta(meta.copy(revision = null, dirty = true))
            return
        }
        val remote = snapshot.data ?: return
        // a newer build's data would lose fields on its way through this one
        if ((dataVersionOf(remote) ?: 0) > CURRENT_DATA_VERSION) {
            _status.value = SyncStatus.Outdated
        } else {
            adopt(remote, snapshot.revision)
        }
    }

    /** Upload the current data; changes made meanwhile leave it dirty for another round. */
    private suspend fun push() {
        val syncCode = meta.syncCode
        val data = store.data
        if (data == null) {
            saveMeta(meta.copy(dirty = false))
            return
        }
        _status.value = SyncStatus.Saving
        val revision = api.save(syncCode, data)
        if (meta.syncCode != syncCode) return
        syncedData = data
        saveMeta(meta.copy(revision = revision, dirty = store.data != data))
    }

    /** One round with the cloud: upload this device's changes, or else fetch the others'. */
    private suspend fun runSync() {
        saveJob?.cancel()
        saveJob = null
        // a round already under way picks up changes made since it started
        if (running || isStopped()) return
        running = true
        retryJob?.cancel()
        retryJob = null
        try {
            if (!meta.dirty) pull()
            while (meta.dirty && !isStopped()) push()
            if (!isStopped()) _status.value = SyncStatus.Synced
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CloudRequestException && e.noBackend) {
                _status.value = SyncStatus.Unavailable
            } else if (e is CloudRequestException && e.tooLarge) {
                // sending it again unchanged would fail the same way; the next edit retries
                _status.value = SyncStatus.TooLarge
            } else {
                _status.value = SyncStatus.Offline
                retryJob = scope.launch {
                    delay(RETRY_DELAY_MS)
                    retryJob = null
                    sync()
                }
            }
        } finally {
            running = false
        }
    }

    /** Start a round with the cloud (a no-op while one is under way). */
    fun sync() {
        if (!started) return
        scope.launch { runSync() }
    }

    /** The app came to the foreground or the network came back: look for changes from elsewhere. */
    fun onResume() = sync()

    /** The app is leaving the foreground: don't sit on a pending save. */
    fun onPause() {
        if (meta.dirty) sync()
    }

    /** Start syncing the store with the cloud. Call once, after the store has loaded. */
    fun start() {
        if (started) return
        started = true
        // On the first run, data already on this device predates cloud sync and
        // is uploaded; a fresh install only holds the bundled seed, which isn't.
        saveMeta(if (savedMeta == null) meta.copy(dirty = store.loadedFromSavedCopy) else meta)

        if (!api.isConfigured) {
            _status.value = SyncStatus.Unavailable
            return
        }

        var previous = store.data
        scope.launch {
            store.state.map { it.data }.distinctUntilChanged().collect { data ->
                val before = previous
                previous = data
                if (data == before || data == syncedData) return@collect
                // the first data a fresh install gets is the bundled seed: nothing of the user's to save
                if (before == null) return@collect
                if (!meta.dirty) saveMeta(meta.copy(dirty = true))
                if (isStopped()) return@collect
                if (_status.value == SyncStatus.Synced) _status.value = SyncStatus.Saving
                saveJob?.cancel()
                saveJob = scope.launch {
                    delay(SAVE_DELAY_MS)
                    saveJob = null
                    sync()
                }
            }
        }

        sync()
    }
}
