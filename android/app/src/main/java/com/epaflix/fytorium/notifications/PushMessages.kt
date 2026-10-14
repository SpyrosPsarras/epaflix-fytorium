package com.epaflix.fytorium.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.epaflix.fytorium.FytoriumApplication
import com.epaflix.fytorium.MainActivity
import com.epaflix.fytorium.LightColors
import androidx.compose.ui.graphics.toArgb
import com.epaflix.fytorium.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

object PushMessages {
    const val OPEN_HOME = "com.epaflix.fytorium.OPEN_HOME"
    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel("daily_reminder", "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("weather_alert", "Weather alert", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("completion_notice", "Completion notice", NotificationManager.IMPORTANCE_DEFAULT)))
    }
    fun handle(context: Context, data: Map<String, String>, loggedIn: Boolean, refresh: () -> Unit) {
        if (!loggedIn) return
        refresh()
        channels(context)
        val tag = data["tag"] ?: return
        val channel = when {
            tag == "fytorium-daily" || tag == "fytorium-test" -> "daily_reminder"
            tag.startsWith("fytorium-weather-") -> "weather_alert"
            tag.startsWith("fytorium-completion-") -> "completion_notice"
            else -> return
        }
        val title = data["title"] ?: return
        val body = data["body"] ?: return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setColor(LightColors.primary.toArgb())
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).build()
        // The stable daily tag replaces yesterday's reminder, without action buttons.
        context.getSystemService(NotificationManager::class.java).notify(tag, 0, notification)
    }
    fun received(app: FytoriumApplication, data: Map<String, String>) = handle(app, data, app.store.tokens != null) {
        app.care.refresh()
        PushRefresh.enqueue(app)
    }
}
class FytoriumMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) { PushMessages.received(application as FytoriumApplication, message.data) }
    override fun onNewToken(token: String) { (application as FytoriumApplication).push.register(token) }
}
