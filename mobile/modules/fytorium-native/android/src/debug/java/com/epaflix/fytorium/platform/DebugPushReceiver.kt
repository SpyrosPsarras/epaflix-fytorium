package com.epaflix.fytorium.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = listOf("title", "body", "tag", "url").mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
        Push.received(context.applicationContext, data)
    }
}
