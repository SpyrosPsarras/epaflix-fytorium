package com.epaflix.fytorium

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class CareController(context: Context, private val store: CredentialStore, val repository: Repository, private val api: ApiClient) {
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val work = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { api.networkLost(); repository.connectivityChanged(false) }
        override fun onAvailable(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val online = connected()
            repository.connectivityChanged(online)
            if (online) refresh()
        }
    }
    init {
        network.registerDefaultNetworkCallback(callback)
        scope.launch {
            store.tokensState.map { it != null }.distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) {
                    repository.restore()
                    work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
                    refresh()
                } else { work.cancelUniqueWork(WORK_NAME); repository.wipe() }
            }
        }
    }
    fun connected(): Boolean = network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    @Synchronized fun refresh() {
        if (store.tokens == null || refreshJob?.isActive == true) return
        refreshJob = scope.launch { repository.refresh() }
    }
    fun act(task: CareTask, action: String, pot: PotDetails?) { scope.launch { repository.act(task, action, pot) } }
    fun handleAll(plant: String) { scope.launch { repository.handleAll(plant) } }
    fun settings(settings: TaskSettings) { scope.launch { repository.saveSettings(settings) } }
    fun cancelRefresh() { work.cancelUniqueWork(WORK_NAME) }
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
        // Periodic reads wait for the next six-hour interval. No care write is ever queued.
        Result.success()
    }
}
