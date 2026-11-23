package com.epaflix.fytorium.notifications

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        val state = mutableStateOf(NotificationState(loaded = true, timezone = "Europe/Athens", devices = listOf(
            PushDevice("device", "Fytorium Android", "fcm", true, current = true, lastSuccessAt = "2026-11-14T08:00:00Z"),
            PushDevice("browser", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0", "web", false))))
        val online = mutableStateOf(true)
        val asked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var settingsLinks = 0
        var savedTime: String? = null
        var enables = 0
        val receipts = mutableListOf<String>()
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize()) {
            NotificationScreen(state.value, online.value, permissionAsked = asked.value, notificationsEnabled = enabled.value, onSystemSettings = { settingsLinks++ },
                onUpdate = { change -> savedTime = change(state.value.settings).time; true }, onEnable = { enables++; asked.value = true },
                onReceipt = { receipts += it }, onTest = { 1 })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/a12-$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("Notifications are on for this phone.").assertIsDisplayed()
        compose.onNodeWithText("Android settings").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, settingsLinks) }
        capture("settings")
        compose.onNodeWithText("Times use Europe/Athens time.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reminder time").performScrollTo().performClick()
        compose.onNodeWithText("Set").assertIsDisplayed()
        capture("time-picker")
        compose.onNodeWithText("Set").performClick()
        compose.waitUntil(5_000) { savedTime != null }
        assertEquals("09:00", savedTime)
        compose.onNodeWithText("Send a test notification").performScrollTo().performClick()
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        assertEquals("Test sent to 1 device", receipts.last())
        capture("permission")
        compose.onNodeWithText("Android phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This phone \u00b7 Last notified 14 Nov", substring = true).assertExists()
        compose.onNodeWithText("Firefox on Linux").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Firefox on Linux").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Remove Firefox on Linux?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Send notifications to Firefox on Linux").assertExists()
        capture("devices")
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Notifications are off for Fytorium on this phone.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()
        compose.runOnIdle { assertEquals(1, enables) }
        capture("notifications-off")
        compose.runOnIdle { enabled.value = true; online.value = false }
        compose.onNode(hasText("Daily reminder") and isToggleable()).performScrollTo().assert(hasStateDescription("Changing settings needs a connection.")).performClick()
        assertEquals("Changing settings needs a connection.", receipts.last())
        capture("offline")
        compose.runOnIdle { online.value = true; state.value = state.value.copy(blocked = true, error = "Notifications unavailable. Reconnect and refresh to retry.") }
        compose.onNodeWithText("Fytorium isn't responding").assertIsDisplayed()
        capture("failure")
        compose.runOnIdle { state.value = state.value.copy(blocked = false, error = null, loading = true) }
        compose.onNode(hasText("Daily reminder") and isToggleable()).performScrollTo().assert(!hasStateDescription("Changing settings needs a connection."))
        capture("background-read")
    }
    // W5a-P24, P28: names a person recognises, and the time in the phone's 12 or 24 hour style.
    @Test fun devicesAndTimesReadAsPeopleSayThem() {
        assertEquals("Edge on Windows", browserName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 Edg/120.0"))
        assertEquals("Chrome on Android", browserName("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"))
        assertEquals("Safari on iOS", browserName("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
        assertEquals("Synthetic agent with a very long name th\u2026", browserName("Synthetic agent with a very long name that goes on"))
        assertEquals("Android phone", deviceName(PushDevice("id", "Fytorium Android", "fcm", true)))
        assertEquals("Pixel 8", deviceName(PushDevice("id", "Pixel 8", "fcm", true)))
        assertEquals(" \u00b7 Not notified yet", lastNotified(null))
        assertEquals("9:05 PM", clock("21:05", false))
        assertEquals("21:05", clock("21:05", true))
    }

    // W5a-P25: in landscape the dial sits beside the time, so no keyboard covers Set and Cancel.
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun theReminderTimeFitsLandscapeWithoutAKeyboard() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        var saved: String? = null
        compose.setContent { FytoriumTheme { Surface(Modifier.fillMaxSize()) { NotificationScreen(NotificationState(loaded = true),
            onUpdate = { change -> saved = change(NotificationSettings()).time; true }) } } }
        compose.onNodeWithText("Reminder time").performScrollTo().performClick()
        compose.onNodeWithText("Set").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        // Every hour can be chosen from a menu that fits the short window.
        compose.onNodeWithText("Hour", substring = true).performClick()
        compose.onNode((hasText("07") or hasText("7 AM")) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        compose.onNodeWithText("Set").performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("07:00", saved)
    }

    // W5a-P25 (Opus W5a-opus-2): the time being picked survives recreation, and minutes come in fives.
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun theTimeBeingPickedSurvivesRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var saved: String? = null
        restoration.setContent { FytoriumTheme { Surface(Modifier.fillMaxSize()) { NotificationScreen(NotificationState(loaded = true),
            onUpdate = { change -> saved = change(NotificationSettings()).time; true }) } } }
        compose.onNodeWithText("Reminder time").performScrollTo().performClick()
        compose.onNodeWithText("Minute", substring = true).performClick()
        compose.onNode(hasText("30") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Minute: 30").assertIsDisplayed()
        compose.onNodeWithText("Set").performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("09:30", saved)
    }

    // Opus W5a-opus-12: the hour menu opens at the chosen hour and marks it.
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun theHourMenuOpensAtTheChosenHour() {
        compose.setContent { FytoriumTheme { Surface(Modifier.fillMaxSize()) { NotificationScreen(NotificationState(loaded = true,
            settings = NotificationSettings(time = "21:00"))) } } }
        compose.onNodeWithText("Reminder time").performScrollTo().performClick()
        compose.onNodeWithText("Hour", substring = true).performClick()
        compose.onNode((hasText("21") or hasText("9 PM")) and hasAnyAncestor(isPopup())).assertIsDisplayed().assertIsSelected()
    }

    // W5a-P30 (Opus W5a-opus-3): a refused device change says so and reloads the list.
    @Test fun aRefusedDeviceChangeSaysSoAndReloads() {
        val receipts = mutableListOf<String>()
        var reloads = 0
        compose.setContent { FytoriumTheme { Surface(Modifier.fillMaxSize()) { NotificationScreen(NotificationState(loaded = true,
            devices = listOf(PushDevice("browser", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0", "web", true))),
            onSwitch = { _, _ -> false }, onReceipt = { receipts += it }, onRefresh = { reloads++ }, failure = { "Device not found" }) } } }
        compose.onNodeWithContentDescription("Send notifications to Firefox on Linux").performScrollTo().performClick()
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        assertEquals("Firefox on Linux wasn't changed. Device not found.", receipts.single())
        assertEquals(1, reloads)
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
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Daily reminder") and isToggleable() and !hasStateDescription("Changing settings needs a connection.")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Daily reminder") and isToggleable()).performScrollTo().assertIsEnabled().assert(!hasStateDescription("Changing settings needs a connection."))
        }
    }
}
