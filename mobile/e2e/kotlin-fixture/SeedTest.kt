package com.epaflix.fytorium

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.epaflix.fytorium.notifications.PushDebugBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Leaves a logged-in, populated Kotlin install for the React Native upgrade tests. Never logs out.
@RunWith(AndroidJUnit4::class)
class SeedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun seedLoggedInInstall() {
        val args = InstrumentationRegistry.getArguments()
        val pushId = requireNotNull(args.getString("push" + "Token")) { "pushToken argument is required" }
        val run = pushId.takeLast(8)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitForState("Log in shown", 30_000) { runCatching { compose.onNodeWithText("Log in").assertExists() }.isSuccess }
        compose.onNodeWithText("Log in").performClick()
        compose.waitForState("Local OIDC page loaded in Chrome") { device.hasObject(By.pkg("com.android.chrome").text("Mock OAuth2 Server Sign-in")) }
        val subjectSelector = By.pkg("com.android.chrome").clazz("android.widget.EditText")
        compose.waitForState("OIDC subject input visible") { device.hasObject(subjectSelector) }
        device.findObject(subjectSelector).text = "synthetic-a03-owner"
        compose.waitForState("OIDC subject entered", 10_000) { device.hasObject(By.text("synthetic-a03-owner")) }
        val keyboard = By.pkg("com.google.android.inputmethod.latin")
        if (compose.waitForState("Login keyboard appeared", 2_000, required = false) { device.hasObject(keyboard) }) {
            blurFocusedChromeField()
            compose.waitForState("Keyboard closed", 5_000) { !device.hasObject(keyboard) }
        }
        for (attempt in 1..8) {
            if (device.hasObject(By.text("Sign-in"))) break
            if (device.hasObject(keyboard)) {
                blurFocusedChromeField()
                compose.waitForState("Keyboard closed before swipe $attempt", 5_000) { !device.hasObject(keyboard) }
            }
            device.swipe(device.displayWidth * 95 / 100, device.displayHeight * 3 / 4, device.displayWidth * 95 / 100, device.displayHeight / 2, 80)
            compose.waitForState("OIDC Sign-in after swipe $attempt", 2_000, required = false) { device.hasObject(By.text("Sign-in")) }
        }
        compose.waitForState("OIDC Sign-in button visible") { device.hasObject(By.text("Sign-in")) }
        compose.waitForState("OIDC Sign-in enabled") { device.findObject(By.text("Sign-in"))?.let { it.isEnabled && !it.visibleBounds.isEmpty } == true }
        device.findObject(By.text("Sign-in")).click()
        compose.waitForState("OIDC redirect returned to Fytorium") {
            if (device.hasObject(By.pkg("com.epaflix.fytorium"))) true else {
                device.findObject(By.textContains("Open"))?.let { if (it.isEnabled && !it.visibleBounds.isEmpty) it.click() }
                false
            }
        }
        val app = compose.activity.application as FytoriumApplication
        compose.waitForState("Initial care refresh after login", 30_000) { app.repository.state.value.member != null && !app.repository.state.value.busy }
        fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
            app.api.request(path, method = method, body = body, query = query).use {
                val text = it.body?.string().orEmpty()
                assertTrue("Seed API $path returned ${it.code}: $text", it.isSuccessful)
                text
            }
        val site = JSONObject(json("/api/sites", "POST", JSONObject("""{"location":"indoor","light":"part_shade","humidity":"normal","grow_light":false,"draft":false}""")
            .put("name", "Synthetic W1 seed site $run"))).getString("id")
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
        json("/api/plants", "POST", JSONObject("""{"pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("nickname", "Synthetic W1 seed fern $run").put("site_id", site).put("search_id", search.getString("search_id")).put("gbif_key", 910001))
        app.care.refresh()
        compose.waitForState("Plant cached", 30_000) { app.repository.state.value.plants.any { it.displayName == "Synthetic W1 seed fern $run" } }
        PushDebugBridge.token(app, pushId)
        compose.waitForState("Server lists this device's FCM registration", 30_000) {
            val rows = JSONArray(json("/api/notifications/subscriptions"))
            (0 until rows.length()).map { rows.getJSONObject(it) }.any { it.getString("transport") == "fcm" && it.optBoolean("current_device") }
        }
        // Leftovers the upgrade must remove: a camera capture, a kept Identify draft and a diagnostics entry.
        File(app.cacheDir, "captures").apply { mkdirs() }.resolve("seed.jpg").writeBytes(byteArrayOf(1))
        File(app.noBackupFilesDir, "photo-drafts").apply { mkdirs() }.resolve("seed.jpg").writeBytes(byteArrayOf(1))
        Diagnostics(app).record(ErrorEvent.SESSION_ENDED)
        if (args.getString("denyNotifications") == "true") {
            compose.selectTab("More")
            compose.waitForNode(hasText("Notifications") and hasClickAction()).performScrollTo().performClick()
            compose.waitForNode(hasText("Turn on") and hasClickAction()).performScrollTo().performClick()
            val deny = By.res("com.android.permissioncontroller", "permission_deny_button")
            assertTrue("Permission dialog shown", device.wait(Until.hasObject(deny), 10_000))
            compose.waitForState("Permission dialog takes input", 10_000) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.windows.any { it.isFocused && it.root?.packageName?.endsWith("permissioncontroller") == true }
            }
            device.findObject(deny).click()
            assertTrue("Permission dialog closed", device.wait(Until.gone(deny), 10_000))
            assertTrue(app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            assertTrue(app.getSharedPreferences("notification-permission", android.content.Context.MODE_PRIVATE).getBoolean("asked", false))
        }
        assertTrue(app.getDatabasePath("care-cache.db").isFile)
        assertTrue(app.store.tokens != null)
    }
}
