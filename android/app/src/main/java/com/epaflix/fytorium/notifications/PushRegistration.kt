package com.epaflix.fytorium.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.CareRefreshWorker
import com.epaflix.fytorium.CredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PushRegistration(private val api: ApiClient, private val store: CredentialStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch { store.tokensState.map { it != null }.distinctUntilChanged().collect { if (it) refresh() } }
    }
    fun refresh() { if (store.tokens != null) PushTokenSource.current(::register) }
    fun register(token: String) {
        scope.launch {
            if (store.tokens == null) return@launch
            // Registration errors cannot put a token, response or exception into diagnostics.
            runCatching { registerPushToken(api, token) }
        }
    }
}
object PushRefresh {
    const val NAME = "notification-cache-refresh"
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CareRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
