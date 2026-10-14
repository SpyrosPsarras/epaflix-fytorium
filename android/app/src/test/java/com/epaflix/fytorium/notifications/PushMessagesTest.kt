package com.epaflix.fytorium.notifications

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.epaflix.fytorium.FytoriumApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushMessagesTest {
    @Test fun syntheticBuildDisablesFirebaseAutoInitAtRuntime() {
        val app = ApplicationProvider.getApplicationContext<FytoriumApplication>()
        val metadata = app.packageManager.getApplicationInfo(app.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
        assertFalse(metadata.getBoolean("firebase_messaging_auto_init_enabled"))
        com.google.firebase.FirebaseApp.initializeApp(app)
        assertFalse(com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled)
    }
    @Test fun channelReplacementHomeIntentAndRefreshUseTheSharedMessageHandler() {
        val app = ApplicationProvider.getApplicationContext<FytoriumApplication>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        var refreshes = 0
        // Unauthenticated messages are discarded, including after logout.
        PushMessages.handle(app, mapOf("title" to "Fytorium", "body" to "task", "tag" to "fytorium-daily", "url" to "/"), false) { refreshes++ }
        assertEquals(0, refreshes)
        for (tag in listOf("fytorium-daily", "fytorium-daily", "fytorium-weather-one", "fytorium-completion-one")) {
            PushMessages.handle(app, mapOf("title" to "Fytorium", "body" to "task", "tag" to tag, "url" to "/"), true) { refreshes++ }
        }
        assertEquals(4, refreshes)
        assertEquals(setOf("daily_reminder", "weather_alert", "completion_notice"), manager.notificationChannels.map { it.id }.toSet())
        assertEquals(3, manager.activeNotifications.size)
        for (notification in manager.activeNotifications) {
            assertTrue(notification.notification.actions.isNullOrEmpty())
            assertNotNull(notification.notification.contentIntent)
        }
        val daily = manager.activeNotifications.single { it.tag == "fytorium-daily" }
        assertEquals("daily_reminder", daily.notification.channelId)
    }
}
