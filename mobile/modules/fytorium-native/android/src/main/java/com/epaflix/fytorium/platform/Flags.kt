package com.epaflix.fytorium.platform

import android.content.Context

object Flags {
    const val PUSH_READY = "push_ready"
    const val PUSH_RESET_PENDING = "push_reset_pending"
    const val NOTIFICATIONS_ASKED = "notifications_asked"
    const val FIRST_RUN_DONE = "first_run_done"
    const val SESSION_WIPE_PENDING = "session_wipe_pending"
    val names = setOf(PUSH_READY, PUSH_RESET_PENDING, NOTIFICATIONS_ASKED, FIRST_RUN_DONE, SESSION_WIPE_PENDING)

    private fun prefs(context: Context) = context.getSharedPreferences("fytorium-platform", Context.MODE_PRIVATE)
    fun get(context: Context, name: String) = prefs(context).getBoolean(name, false)
    fun set(context: Context, name: String, value: Boolean) = setAll(context, mapOf(name to value))
    // One commit for all values; false when the write did not reach disk.
    fun setAll(context: Context, values: Map<String, Boolean>): Boolean {
        require(values.keys.all { it in names }) { "Unknown flag" }
        return prefs(context).edit().apply { values.forEach { (name, value) -> putBoolean(name, value) } }.commit()
    }
}
