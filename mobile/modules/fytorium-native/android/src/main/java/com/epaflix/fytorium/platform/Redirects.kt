package com.epaflix.fytorium.platform

import android.content.Intent

// Login redirects are captured by MainActivity before React is ready: after process death Android recreates the
// activity with its launcher intent and hands the redirect to onNewIntent, which React Native would drop.
object Redirects {
    private val pending = mutableListOf<String>()
    @Volatile var listener: (() -> Unit)? = null

    @JvmStatic @Synchronized fun capture(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (!data.startsWith("com.epaflix.fytorium:")) return
        pending += data
        listener?.invoke()
    }

    @Synchronized fun take(): List<String> = pending.toList().also { pending.clear() }
}
