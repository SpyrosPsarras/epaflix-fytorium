package com.epaflix.fytorium.platform

import android.content.Context

object Flags {
    const val PUSH_READY = "push_ready"
    const val PUSH_RESET_PENDING = "push_reset_pending"
    const val NOTIFICATIONS_ASKED = "notifications_asked"
    const val FIRST_RUN_DONE = "first_run_done"
    val names = setOf(PUSH_READY, PUSH_RESET_PENDING, NOTIFICATIONS_ASKED, FIRST_RUN_DONE)

    private fun prefs(context: Context) = context.getSharedPreferences("fytorium-platform", Context.MODE_PRIVATE)
    fun get(context: Context, name: String) = prefs(context).getBoolean(name, false)
    fun set(context: Context, name: String, value: Boolean) {
        require(name in names) { "Unknown flag" }
        prefs(context).edit().putBoolean(name, value).commit()
    }
}
