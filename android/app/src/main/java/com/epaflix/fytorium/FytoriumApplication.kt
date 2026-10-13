package com.epaflix.fytorium

import android.app.Application
import android.os.Process
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import java.io.File
import kotlin.system.exitProcess

class FytoriumApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
    val store: CredentialStore by lazy { KeystoreCredentialStore(this) }
    val session: SessionController by lazy { SessionController(store, api, Diagnostics(this)) }
    private fun connected() = getSystemService(ConnectivityManager::class.java).let {
        it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    val thumbnails: Thumbnails by lazy { Thumbnails(api, ThumbnailCache(File(cacheDir, "thumbnails")), ::connected) }
    val repository: Repository by lazy {
        val database = Room.databaseBuilder(this, CareDatabase::class.java, "care-cache.db").fallbackToDestructiveMigration(dropAllTables = true).build()
        Repository(api, CareCache(database), ::connected, wipeThumbnails = thumbnails::wipe)
    }
    private val careInstance = lazy { CareController(this, store, repository, api) }
    val care: CareController get() = careInstance.value
    val api: ApiClient by lazy {
        ApiClient(BuildConfig.SERVER_URL, store) {
            repository.wipe()
            care.cancelRefresh()
            Diagnostics(this).apply { clear(); record(ErrorEvent.SESSION_ENDED) }
        }
    }
    override fun onTerminate() { if (careInstance.isInitialized()) care.close(); super.onTerminate() }
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            runCatching { Diagnostics(this).record(ErrorEvent.APP_CRASH) }
            // Throwable text can contain credentials. Do not send it to logcat or a remote collector.
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
