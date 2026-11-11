package com.epaflix.fytorium

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class CareController(context: Context, private val store: CredentialStore, val repository: Repository, private val api: ApiClient,
                     private val feed: com.epaflix.fytorium.feed.FeedRepository? = null) {
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val work = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private val _online = MutableStateFlow(connected())
    val online: StateFlow<Boolean> = _online
    private val _pulling = MutableStateFlow(false)
    // Only a pull shows the spinner, until the refresh that serves it and its feed read end, so the pull shows the new strip too.
    val pulling: StateFlow<Boolean> = _pulling
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { _online.value = false; api.networkLost(); repository.connectivityChanged(false); feed?.connectivityChanged(false) }
        override fun onAvailable(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val online = connected()
            _online.value = online
            repository.connectivityChanged(online)
            feed?.connectivityChanged(online)
            if (online) refresh()
        }
    }
    init {
        network.registerDefaultNetworkCallback(callback)
        // The cached list and strip are in place before the first frame, so a cold start never shows a loading state.
        var restored = store.tokens != null && runBlocking(Dispatchers.IO) { feed?.restore(); repository.restore(); true }
        if (feed != null) scope.launch { repository.careChanged.collect { feed.refresh(repository.state.value.member) } }
        scope.launch {
            store.tokensState.map { it != null }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) {
                    // A login restores here, the strip before the rows; a cold start has already restored above.
                    if (!restored) { feed?.restore(); repository.restore() }
                    work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
                    refresh()
                } else { work.cancelUniqueWork(WORK_NAME); repository.wipe(); feed?.wipe() }
                restored = false
            }
        }
    }
    fun connected(): Boolean = network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    @Synchronized fun refresh(pulled: Boolean = false) {
        if (store.tokens == null) return
        if (pulled) _pulling.value = true
        if (refreshJob?.isActive == true) return
        val job = scope.launch { repository.refresh() }
        refreshJob = job
        // Feed reads must not extend the task refresh's single-flight window.
        job.invokeOnCompletion { error ->
            scope.launch {
                if (error == null && store.tokens != null) feed?.refresh(repository.state.value.member)
                synchronized(this@CareController) { if (refreshJob === job) _pulling.value = false }
            }
        }
    }
    fun act(task: CareTask, action: String) { scope.launch { repository.act(task, action) } }
    fun rainWatered(batchId: String?) { scope.launch { repository.rainWatered(batchId); feed?.refresh(repository.state.value.member) } }
    fun open(occurrence: String?) = repository.open(occurrence)
    fun clearReceipt(receipt: Receipt) = repository.clearReceipt(receipt)
    fun undoReceipt(receipt: Receipt) { scope.launch { repository.undoReceipt(receipt) } }
    fun handleType(taskKey: String) { scope.launch { repository.handleType(taskKey) } }
    fun handleAll(plantId: String) { scope.launch { repository.handleAll(plantId) } }
    suspend fun settings(settings: TaskSettings): Boolean = withContext(Dispatchers.IO) { repository.saveSettings(settings) }
    fun cancelRefresh() { work.cancelUniqueWork(WORK_NAME); work.cancelUniqueWork(com.epaflix.fytorium.notifications.PushRefresh.NAME) }
    fun close() { network.unregisterNetworkCallback(callback); scope.cancel() }
    companion object {
        const val WORK_NAME = "care-cache-refresh"
        fun periodicRequest() = PeriodicWorkRequestBuilder<CareRefreshWorker>(6, TimeUnit.HOURS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
    }
}
class CareRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as FytoriumApplication
        if (app.store.tokens == null) return@withContext Result.success()
        app.repository.refresh()
        app.feed.refresh(app.repository.state.value.member)
        // Periodic reads wait for the next six-hour interval. No care write is ever queued.
        Result.success()
    }
}
