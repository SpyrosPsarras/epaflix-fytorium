package com.epaflix.fytorium.notifications

import com.google.firebase.messaging.FirebaseMessaging

object PushTokenSource {
    fun current(registered: (String) -> Unit) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { registered(it) }
    }
}
