package com.epaflix.fytorium.notifications

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class NotificationScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val state = mutableStateOf(NotificationState(loaded = true, timezone = "Europe/Athens", devices = listOf(PushDevice("device", "Test phone", "fcm", true, current = true), PushDevice("browser", "Test browser", "web", false))))
        val online = mutableStateOf(true)
        val asked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var settingsLinks = 0
        var savedTime: String? = null
        var enables = 0
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize()) {
            NotificationScreen(state.value, online.value, permissionAsked = asked.value, notificationsEnabled = enabled.value, onSystemSettings = { settingsLinks++ }, onSave = { savedTime = it.time }, onEnable = { enables++; asked.value = true })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/a12-$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("Enable notifications").assertHeightIsAtLeast(48.dp)
        capture("settings")
        compose.onNodeWithText("Enable notifications").performClick()
        compose.onNodeWithText("Android notification settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, enables) }
        compose.onNodeWithText("Time zone: Europe/Athens").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reminder time: 09:00").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Set time").assertIsDisplayed()
        capture("time-picker")
        compose.onNodeWithContentDescription("10 hours").performClick()
        compose.onNodeWithText("Set time").performClick()
        compose.onNodeWithText("Reminder time: 10:00").assertIsDisplayed()
        compose.onNodeWithText("Save notification settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("10:00", savedTime) }
        compose.onNodeWithText("Send test notification").performScrollTo().assertIsEnabled()
        capture("permission")
        compose.onNodeWithText("Remove Test browser").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("This device").performScrollTo().assertIsDisplayed()
        capture("devices")
        compose.runOnIdle { enabled.value = false; state.value = state.value.copy(message = "Test notification sent.") }
        compose.onNodeWithText("Notifications are off on this phone.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Android notification settings").performScrollTo().performClick()
        compose.onNodeWithText("Test notification sent, but notifications are off on this phone.").assertIsDisplayed()
        compose.onNodeWithText("Test notification sent.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settingsLinks) }
        capture("notifications-off")
        compose.runOnIdle { enabled.value = true; state.value = state.value.copy(message = "Device removed.") }
        compose.onNodeWithText("Device removed.").performScrollTo().assertIsDisplayed()
        capture("removed")
        compose.runOnIdle { state.value = state.value.copy(message = null); online.value = false }
        compose.onNodeWithText("Remove Test phone").performScrollTo().assertIsNotEnabled()
        capture("offline")
        compose.runOnIdle { online.value = true; state.value = state.value.copy(blocked = true, error = "Notifications unavailable. Reconnect and refresh to retry.") }
        compose.onNodeWithText("Refresh notifications").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Save notification settings").performScrollTo().assertIsNotEnabled()
        capture("failure")
        compose.runOnIdle { state.value = state.value.copy(blocked = false, error = null, loading = true) }
        compose.onNodeWithText("Save notification settings").assertIsEnabled()
        capture("background-read")
    }
    @Test fun notificationControlsDoNotDependOnCareFailuresOrBackgroundReads() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"reminder_time":"09:00","reminders_enabled":false,"weather_alerts_enabled":false,"completion_notices_enabled":false}"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Athens"}"""))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            compose.setContent { FytoriumTheme { NotificationRoute(api,
                CareState(member = Member("owner", Role.owner), busy = true, offline = true, serverUnavailable = true), { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save notification settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save notification settings").performScrollTo().assertIsEnabled()
        }
    }
}
