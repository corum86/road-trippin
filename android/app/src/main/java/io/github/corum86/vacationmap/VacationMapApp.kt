package io.github.corum86.vacationmap

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.corum86.vacationmap.data.AppServices
import io.github.corum86.vacationmap.data.createAppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VacationMapApp : Application() {
    lateinit var services: AppServices
        private set

    private val mainScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val (services, http) = createAppServices(this, mainScope)
        this.services = services

        // photos load through the app's HTTP client: Wikimedia wants an identifying User-Agent
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }.build()
        }

        mainScope.launch {
            withContext(Dispatchers.IO) { services.store.loadInitialData() }
            startSaving()
            startSyncing()
        }
    }

    /** Every change goes to the device's own copy first. */
    @OptIn(FlowPreview::class)
    private fun startSaving() {
        val store = services.store
        mainScope.launch(Dispatchers.IO) {
            store.state.map { it.data }.filterNotNull().distinctUntilChanged().conflate().collect { data ->
                try {
                    store.persist(data)
                } catch (_: Exception) {
                    // out of space: the data stays in memory (and in the cloud, if syncing)
                }
            }
        }
    }

    private fun startSyncing() {
        val sync = services.cloudSync
        sync.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = sync.onResume()

            override fun onStop(owner: LifecycleOwner) = sync.onPause()
        })
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainScope.launch { sync.onResume() }
                }
            },
        )
    }
}
