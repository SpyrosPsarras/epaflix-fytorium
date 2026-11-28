package com.epaflix.fytorium.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage
import io.invertase.firebase.messaging.ReactNativeFirebaseMessagingService

object Push {
    const val OPEN_HOME = "com.epaflix.fytorium.OPEN_HOME"
    private const val COLOR = 0xFF3F6A2A.toInt()

    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel("daily_reminder", "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("weather_alert", "Weather alert", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("completion_notice", "Completion notice", NotificationManager.IMPORTANCE_DEFAULT)))
    }

    fun channelFor(tag: String) = when {
        tag == "fytorium-daily" || tag == "fytorium-test" -> "daily_reminder"
        tag.startsWith("fytorium-weather-") -> "weather_alert"
        tag.startsWith("fytorium-completion-") -> "completion_notice"
        else -> null
    }

    fun received(context: Context, data: Map<String, String>) {
        if (!Flags.get(context, Flags.PUSH_READY)) return
        RefreshWorker.enqueueOnce(context)
        FytoriumNativeModule.liveRefresh()
        channels(context)
        val tag = data["tag"] ?: return
        val channel = channelFor(tag) ?: return
        val title = data["title"] ?: return
        val body = data["body"] ?: return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.setAction(OPEN_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.fytorium_notification).setColor(COLOR)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).build()
        // The stable daily tag replaces yesterday's reminder.
        context.getSystemService(NotificationManager::class.java).notify(tag, 0, notification)
    }
}

class PushService : ReactNativeFirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) = Push.received(applicationContext, remoteMessage.data)
}
