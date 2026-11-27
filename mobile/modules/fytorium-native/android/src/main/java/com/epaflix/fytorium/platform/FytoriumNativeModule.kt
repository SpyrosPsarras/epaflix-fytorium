package com.epaflix.fytorium.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

object RestoreState {
    @JvmStatic var restored = false

    // A notification tap that started the task stays in its base intent; once handled it must not run again when
    // Android recreates the activity from saved state (Kotlin's saved homeHandled).
    @JvmStatic fun created(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
        restored = savedInstanceState != null
        if (restored && activity.intent?.action == Push.OPEN_HOME) activity.intent.action = null
    }
}

// W6-P11 at targetSdk 35: with predictive back on, Android calls no onBackPressed, and React Native registers its
// own callback only from targetSdk 36. This callback hands Back to React Native while the app has a use for it;
// at Today's root with nothing to ask, it is off, so the system shows its back-to-home preview.
object BackState {
    private var wanted = false
    private var callback: androidx.activity.OnBackPressedCallback? = null

    @JvmStatic fun attach(activity: androidx.activity.ComponentActivity) {
        callback = object : androidx.activity.OnBackPressedCallback(wanted) {
            @Suppress("DEPRECATION")
            override fun handleOnBackPressed() = activity.onBackPressed()
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
    }

    fun handled(value: Boolean) {
        wanted = value
        callback?.isEnabled = value
    }

    // React Native's default Back goes back through the dispatcher, which must not call this callback again.
    @JvmStatic fun whileDefault(run: Runnable) {
        callback?.isEnabled = false
        run.run()
        callback?.isEnabled = wanted
    }
}

class RequestOptions : Record {
    @Field val method: String = "GET"
    @Field val url: String = ""
    @Field val headers: Map<String, String> = emptyMap()
    @Field val body: String? = null
    @Field val tokenEndpoint: Boolean = false
    @Field val binary: Boolean = false
}

class FytoriumNativeModule : Module() {
    private val context: Context get() = requireNotNull(appContext.reactContext).applicationContext
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = http.networkLost()
    }

    override fun definition() = ModuleDefinition {
        Name("FytoriumNative")
        Events("liveRefresh", "openHome", "redirect")

        OnCreate {
            instance = this@FytoriumNativeModule
            Redirects.listener = { sendEvent("redirect") }
            Push.channels(context)
            context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }
        OnDestroy {
            runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            if (instance === this@FytoriumNativeModule) instance = null
        }
        OnActivityEntersForeground { foreground = true }
        OnActivityEntersBackground { foreground = false }
        OnNewIntent { intent -> if (intent.action == Push.OPEN_HOME) sendEvent("openHome") }

        Constant("isDebugBuild") { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

        Function("firstRun") { FirstRun.run(context) }
        // N3: true once per activity creation from saved state; a later mount in the same process reads false.
        Function("consumeRestoredFromSavedState") { RestoreState.restored.also { RestoreState.restored = false } }
        // W6-P9: Back at Today's root with a draft waiting keeps the task instead of finishing the activity.
        Function("moveTaskToBack") { appContext.currentActivity?.moveTaskToBack(true) == true }
        Function("setBackHandled") { value: Boolean -> appContext.currentActivity?.runOnUiThread { BackState.handled(value) }; Unit }
        Function("excludeFromBackup") { _: String -> true }
        Function("getFlag") { name: String -> Flags.get(context, name) }
        Function("setFlag") { name: String, value: Boolean -> Flags.set(context, name, value) }
        Function("setFlags") { values: Map<String, Boolean> -> Flags.setAll(context, values) }
        Function("obtainiumPackage") {
            listOf("dev.imranr.obtainium", "dev.imranr.obtainium.fdroid").firstOrNull { name ->
                runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess
            }
        }
        Function("openObtainium") {
            val launch = listOf("dev.imranr.obtainium", "dev.imranr.obtainium.fdroid").firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
            launch?.let { (appContext.currentActivity ?: context).startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } != null
        }
        Function("takeRedirects") { Redirects.take() }
        // A plain Custom Tab, as the Kotlin app used; its redirect returns through MainActivity and Redirects.
        Function("openLogin") { url: String ->
            val activity = appContext.currentActivity ?: return@Function false
            runCatching { androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(activity, android.net.Uri.parse(url)) }.isSuccess
        }
        Function("consumeOpenHome") {
            val intent = appContext.currentActivity?.intent
            (intent?.action == Push.OPEN_HOME).also { if (it) intent?.action = null }
        }
        Function("debugLaunchArgument") { name: String ->
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
            else appContext.currentActivity?.intent?.getStringExtra(name)
        }
        // Debug builds only: e2e reads these lines from logcat.
        Function("debugLog") { message: String ->
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("FytoriumDebug", message)
            Unit
        }
        Function("schedulePeriodicRefresh") { RefreshWorker.schedulePeriodic(context); Unit }
        Function("cancelRefresh") { RefreshWorker.cancel(context); Unit }

        AsyncFunction("request") { options: RequestOptions, promise: Promise ->
            val call = runCatching { http.request(options.method, options.url, options.headers, options.body, options.tokenEndpoint) }
                .getOrElse { promise.reject(CodedException("ERR_REQUEST", "Invalid request", null)); return@AsyncFunction }
            http.enqueue(call, options.binary) { result ->
                result.fold(
                    { promise.resolve(mapOf("status" to it.status, "headers" to it.headers, "body" to it.body)) },
                    { promise.reject(CodedException("ERR_NETWORK", "Network request failed", null)) })
            }
        }
    }

    companion object {
        val http = Http()
        @Volatile private var instance: FytoriumNativeModule? = null
        @Volatile private var foreground = false
        fun liveRefresh() {
            val module = instance ?: return
            if (foreground) module.sendEvent("liveRefresh")
        }
    }
}
