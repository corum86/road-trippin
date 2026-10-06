package io.github.corum86.vacationmap

import io.github.corum86.vacationmap.data.CloudSync
import io.github.corum86.vacationmap.data.ConnectResult
import io.github.corum86.vacationmap.data.MapDataStore
import io.github.corum86.vacationmap.data.SyncMeta
import io.github.corum86.vacationmap.data.SyncStatus
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.net.CloudDataApi
import io.github.corum86.vacationmap.net.CloudRequestException
import io.github.corum86.vacationmap.net.CloudSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

private const val OTHER_CODE = "0f8fad5b-d9cb-469f-a165-70867728950e"

/** The backend, in memory: one revision counter and document per sync code. */
private class FakeCloud : CloudDataApi(fakeHttpClient(), "https://example.invalid") {
    class Doc(val revision: Long, val data: JsonElement)

    val docs = HashMap<String, Doc>()
    var failWith: Exception? = null
    var saves = 0
    var fetches = 0

    override suspend fun fetch(syncCode: String, knownRevision: Long?): CloudSnapshot {
        fetches++
        failWith?.let { throw it }
        val doc = docs[syncCode] ?: return CloudSnapshot(null, null)
        return CloudSnapshot(doc.revision, doc.data.takeIf { doc.revision != knownRevision })
    }

    override suspend fun save(syncCode: String, data: VacationMapData): Long {
        saves++
        failWith?.let { throw it }
        val revision = (docs[syncCode]?.revision ?: 0) + 1
        docs[syncCode] = Doc(revision, AppJson.encodeToJsonElement<VacationMapData>(data))
        return revision
    }
}

/**
 * Cloud sync decides when this device's data goes up and when another
 * device's comes down; getting it wrong loses someone's edits. The store's
 * seed needs Android assets, hence Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CloudSyncTest {
    private val cloud = FakeCloud()
    private val meta = MemorySyncMeta()

    private fun TestScope.startSync(store: MapDataStore = loadedStore()): Pair<MapDataStore, CloudSync> {
        val sync = CloudSync(store, cloud, meta, backgroundScope)
        sync.start()
        runCurrent()
        return store to sync
    }

    private fun MapDataStore.firstId() = data!!.destinations.first().id

    @Test
    fun aFreshInstallDoesNotUploadTheSeed() = runTest {
        val (_, sync) = startSync()
        assertEquals(SyncStatus.Synced, sync.status.value)
        assertEquals(1, cloud.fetches)
        assertEquals(0, cloud.saves)
        assertFalse(meta.meta!!.dirty)
    }

    @Test
    fun anEditIsUploadedOnceTheBurstIsOver() = runTest {
        val (store, sync) = startSync()
        store.toggleFavorite(store.firstId())
        runCurrent()
        assertEquals(SyncStatus.Saving, sync.status.value)
        assertTrue(meta.meta!!.dirty)

        // more edits within the second keep pushing the save back
        advanceTimeBy(600)
        store.setTripName("Epirus")
        runCurrent()
        advanceTimeBy(600)
        assertEquals(0, cloud.saves)

        advanceTimeBy(500)
        assertEquals(1, cloud.saves)
        assertEquals(SyncStatus.Synced, sync.status.value)
        assertEquals(SyncMeta(sync.syncCode.value, revision = 1, dirty = false), meta.meta)
        val saved = cloud.docs.getValue(sync.syncCode.value).data as JsonObject
        assertEquals(JsonPrimitive("Epirus"), (saved.getValue("trip") as JsonObject).getValue("name"))
    }

    @Test
    fun whatAnotherDeviceSavedIsAdoptedOnResume() = runTest {
        val (store, sync) = startSync()
        store.setTripName("Mine")
        runCurrent()
        advanceTimeBy(1100)
        assertEquals(1, cloud.saves)

        // another device with the same code saves revision 2
        val theirs = loadedStore().apply { setTripName("Theirs") }.data!!
        cloud.save(sync.syncCode.value, theirs)
        sync.onResume()
        runCurrent()

        assertEquals("Theirs", store.data!!.trip.name)
        assertEquals(2L, meta.meta!!.revision)
        // adopting it is not an edit of this device's: nothing goes back up
        advanceTimeBy(5000)
        assertEquals(2, cloud.saves)
    }

    @Test
    fun localEditsWinOverTheCloudCopy() = runTest {
        val (store, sync) = startSync()
        cloud.save(sync.syncCode.value, loadedStore().apply { setTripName("Theirs") }.data!!)
        store.setTripName("Mine")
        runCurrent()
        advanceTimeBy(1100)
        assertEquals("Mine", store.data!!.trip.name)
        val saved = cloud.docs.getValue(sync.syncCode.value).data as JsonObject
        assertEquals(JsonPrimitive("Mine"), (saved.getValue("trip") as JsonObject).getValue("name"))
    }

    @Test
    fun offlineChangesWaitAndGoUpWhenTheCloudIsBack() = runTest {
        val (store, sync) = startSync()
        cloud.failWith = IOException("no route to host")
        store.toggleFavorite(store.firstId())
        runCurrent()
        advanceTimeBy(1100)
        assertEquals(SyncStatus.Offline, sync.status.value)
        assertTrue(meta.meta!!.dirty)

        cloud.failWith = null
        advanceTimeBy(30_000)
        assertEquals(SyncStatus.Synced, sync.status.value)
        assertFalse(meta.meta!!.dirty)
        assertTrue(cloud.docs.containsKey(sync.syncCode.value))
    }

    @Test
    fun withoutABackendSyncingStops() = runTest {
        cloud.failWith = CloudRequestException(404)
        val (store, sync) = startSync()
        assertEquals(SyncStatus.Unavailable, sync.status.value)
        val fetches = cloud.fetches
        store.toggleFavorite(store.firstId())
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(fetches, cloud.fetches)
        assertEquals(0, cloud.saves)
        // the change is still remembered as unsaved, for a later launch with a backend
        assertTrue(meta.meta!!.dirty)
    }

    @Test
    fun dataFromANewerAppVersionIsLeftAlone() = runTest {
        val (store, sync) = startSync()
        val newer = JsonObject(
            (AppJson.encodeToJsonElement<VacationMapData>(store.data!!) as JsonObject) + ("version" to JsonPrimitive(99)),
        )
        cloud.docs[sync.syncCode.value] = FakeCloud.Doc(7, newer)
        val before = store.data
        sync.onResume()
        runCurrent()
        assertEquals(SyncStatus.Outdated, sync.status.value)
        assertEquals(before, store.data)
    }

    @Test
    fun connectingSwitchesToTheOtherDevicesMap() = runTest {
        val (store, sync) = startSync()
        assertEquals(ConnectResult.Invalid, sync.connect("not a code"))
        assertEquals(ConnectResult.NotFound, sync.connect(OTHER_CODE))

        cloud.save(OTHER_CODE, loadedStore().apply { clearAllData() }.data!!)
        assertEquals(ConnectResult.Ok, sync.connect("  ${OTHER_CODE.uppercase()} "))
        assertEquals(OTHER_CODE, sync.syncCode.value)
        assertNull(store.data!!.mainLocation)
        assertTrue(store.data!!.destinations.isEmpty())
        assertEquals(SyncMeta(OTHER_CODE, revision = 1, dirty = false), meta.meta)

        // later edits go to the shared map
        store.setTripName("Together")
        runCurrent()
        advanceTimeBy(1100)
        assertEquals(2L, cloud.docs.getValue(OTHER_CODE).revision)
    }

    @Test
    fun aClearedMapSyncsAsNullHomeAndDates() = runTest {
        val (store, sync) = startSync()
        store.clearAllData()
        runCurrent()
        advanceTimeBy(1100)
        val saved = cloud.docs.getValue(sync.syncCode.value).data as JsonObject
        // explicit nulls: the web app tells "cleared" from "never set" by them
        assertEquals("null", saved.getValue("mainLocation").toString())
        assertEquals("null", (saved.getValue("trip") as JsonObject).getValue("startDate").toString())
    }
}
