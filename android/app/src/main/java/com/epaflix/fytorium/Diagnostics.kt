package com.epaflix.fytorium

import android.content.Context
import android.util.Log

// Only predefined events may enter diagnostics. Never accept exception text, URLs or response bodies.
enum class DebugEvent { REDIRECT_IGNORED }
enum class ErrorEvent { NETWORK_UNAVAILABLE, LOGIN_REJECTED, SESSION_ENDED, APP_CRASH }
class Diagnostics(context: Context) {
    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    @Synchronized fun record(event: ErrorEvent) {
        val lines = prefs.getString("events", "").orEmpty().lines().filter { it.isNotEmpty() }
        prefs.edit().putString("events", (lines + "${System.currentTimeMillis()} ${event.name}").takeLast(64).joinToString("\n")).commit()
    }
    @Synchronized fun copyText(): String = "Fytorium ${BuildConfig.VERSION_NAME}\n" + prefs.getString("events", "").orEmpty().take(8192)
    fun debug(event: DebugEvent) { Log.d("Fytorium", event.name) }
    fun clear() { prefs.edit().clear().commit() }
}
