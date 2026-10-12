package com.epaflix.fytorium

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess

class FytoriumApplication : Application() {
    val store: CredentialStore by lazy { KeystoreCredentialStore(this) }
    val session: SessionController by lazy { SessionController(store, api, Diagnostics(this)) }
    val api: ApiClient by lazy {
        ApiClient(BuildConfig.SERVER_URL, store) {
            Diagnostics(this).apply { clear(); record(ErrorEvent.SESSION_ENDED) }
        }
    }
    override fun onTerminate() { session.close(); super.onTerminate() }
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
