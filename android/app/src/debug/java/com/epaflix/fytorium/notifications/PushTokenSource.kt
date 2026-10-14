package com.epaflix.fytorium.notifications

import com.epaflix.fytorium.FytoriumApplication
import com.google.firebase.messaging.RemoteMessage

// Debug builds never request a real Firebase installation or registration token.
object PushTokenSource {
    fun current(registered: (String) -> Unit) = Unit
}
// Instrumentation-only calls. No exported receiver, intent or HTTP entry point exists.
object PushDebugBridge {
    fun token(app: FytoriumApplication, token: String) { app.push.register(token) }
    fun deliver(app: FytoriumApplication, data: Map<String, String>) {
        val message = RemoteMessage.Builder("synthetic-fcm").setData(data).build()
        PushMessages.received(app, message.data)
    }
}
