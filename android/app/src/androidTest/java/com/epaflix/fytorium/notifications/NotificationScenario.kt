package com.epaflix.fytorium.notifications

import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.epaflix.fytorium.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

fun runNotificationScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    val token = "synthetic-a12-" + UUID.randomUUID()
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val manager = app.getSystemService(NotificationManager::class.java)
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            assertTrue("A12 API $path returned ${it.code}", it.isSuccessful)
            it.body?.string().orEmpty()
        }
    fun testRequest(path: String, body: JSONObject, method: String = "POST") {
        val connection = URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer " + app.store.tokens!!.access)
            connection.setRequestProperty("Content-Type", "application/json"); connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
        } finally { connection.disconnect() }
    }
    fun clock(at: String) = testRequest("/__test/clock", JSONObject().put("action", "set").put("at", at))
    fun cleanFixtures() {
        val plants = JSONArray(json("/api/plants"))
        (0 until plants.length()).map { plants.getJSONObject(it) }.filter { it.optString("nickname") == "Synthetic A12 fern" }.forEach {
            json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", "Synthetic A12 fern"))
        }
        val sites = JSONArray(json("/api/sites"))
        (0 until sites.length()).map { sites.getJSONObject(it) }.filter { it.getString("name") == "Synthetic A12 site" }.forEach {
            json("/api/sites/${it.getString("id")}", "DELETE")
        }
    }
    val previous = JSONObject(json("/api/notifications/settings"))
    try {
        cleanFixtures()
        clock("2030-06-01T10:00:00Z")
        val site = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"Synthetic A12 site","location":"indoor","light":"part_shade","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
        val plant = JSONObject("""{"nickname":"Synthetic A12 fern","pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("site_id", site).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
        json("/api/plants", "POST", plant)
        PushDebugBridge.token(app, token)
        compose.waitForState("A12 bearer-bound FCM registration", 30_000) {
            val rows = JSONArray(json("/api/notifications/subscriptions"))
            (0 until rows.length()).any { rows.getJSONObject(it).getString("transport") == "fcm" }
        }
        compose.onNodeWithText("More").performClick()
        compose.waitForNode(hasText("My notifications")).performScrollTo().performClick()
        compose.waitForNode(hasText("Enable notifications")).performScrollTo().performClick()
        val allow = By.res("com.android.permissioncontroller", "permission_allow_button")
        assertTrue("A12 permission must follow the explained user action", device.wait(Until.hasObject(allow), 10_000))
        device.findObject(allow).click()
        json("/api/notifications/settings", "PUT", JSONObject().put("reminder_time", "00:00").put("reminders_enabled", true)
            .put("weather_alerts_enabled", false).put("completion_notices_enabled", false))
        // Clear only this member's current-day lease through the test-only fixture endpoint.
        // Delivery still uses the real worker, task summary and shared lease/retry policy.
        testRequest("/__test/push-worker", JSONObject(), "DELETE")
        compose.waitForNode(hasText("Send test notification")).performScrollTo().assertIsEnabled().performClick()
        compose.waitForNode(hasText("Test notification sent.")).performScrollTo().assertIsDisplayed()
        testRequest("/__test/push-worker", JSONObject())
        val connection = URL("http://fake-push:8080/fcm/received").openConnection() as HttpURLConnection
        val recorded = try { connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            assertEquals(200, connection.responseCode)
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        val messages = (0 until recorded.length()).map { recorded.getJSONObject(it).getJSONObject("message") }.filter { it.getString("token") == token }
        val testMessage = messages.last { it.getJSONObject("data").getString("tag") == "fytorium-test" }
        val testData = testMessage.getJSONObject("data").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        PushDebugBridge.deliver(app, testData)
        compose.waitForState("A12 test notification posted in app", 10_000) {
            manager.activeNotifications.any { it.tag == "fytorium-test" && it.notification.channelId == "daily_reminder" }
        }
        // Keep the shade tap focused on the reminder, not an Android auto-group summary.
        manager.cancel("fytorium-test", 0)
        val message = messages.last { it.getJSONObject("data").getString("tag") == "fytorium-daily" }
        assertFalse(message.has("notification"))
        val data = message.getJSONObject("data").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        assertTrue(data.getValue("body").contains("Synthetic A12 fern"))
        PushDebugBridge.deliver(app, data)
        PushDebugBridge.deliver(app, data)
        compose.waitForState("A12 real notification posted in daily channel", 10_000) {
            manager.activeNotifications.count { it.tag == "fytorium-daily" && it.notification.channelId == "daily_reminder" } == 1
        }
        assertTrue(manager.activeNotifications.single { it.tag == "fytorium-daily" }.notification.actions.isNullOrEmpty())
        device.openNotification()
        val summary = By.textContains("Synthetic A12 fern")
        assertTrue("A12 notification visible in system shade", device.wait(Until.hasObject(summary), 10_000))
        device.findObject(summary).click()
        compose.waitForNode(hasTestTag("view-today")).assertIsDisplayed()
        compose.onNodeWithText("My notifications").assertDoesNotExist()
        assertTrue("A12 notification scheduled cache refresh", androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(PushRefresh.NAME).get().isNotEmpty())
        println("A12 E2E PASS: server data message recorded by fake FCM, replayed through real handler, daily channel and replacement, notification tap opens Home")
    } finally {
        manager.cancelAll()
        try {
            json("/api/notifications/settings", "PUT", previous)
            cleanFixtures()
        } finally { testRequest("/__test/clock", JSONObject().put("action", "reset")) }
    }
}
