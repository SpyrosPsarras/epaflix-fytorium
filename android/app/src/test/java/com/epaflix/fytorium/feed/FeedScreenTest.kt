package com.epaflix.fytorium.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class FeedScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun state(role: Role = Role.owner) = FeedState(member = Member("synthetic-member", role), loaded = true,
        cards = JSONObject(feedFixture).getJSONArray("cards").mapObjects(::parseFeedCard).filterNotNull(),
        weather = parseWeather(JSONObject(weatherFixture)), settings = feedLabels.keys.associateWith { true },
        location = WeatherLocation(0.5, 0.5, "Synthetic location"), locationLoaded = true)
    @Composable private fun Screen(dark: Boolean, content: @Composable () -> Unit) {
        // Reference images capture settled controls, not Android's wall-clock ripple animation.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
            } }
        }
    }
    @Test fun roleChangeHidesOldFeedBeforeAnotherReadFinishes() {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        try {
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true })
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(feedFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(weatherFixture))
            feed.refresh(Member("synthetic-member", Role.owner))
            compose.setContent { FytoriumTheme { AppShell(true, false,
                careState = CareState(member = Member("synthetic-member", Role.sitter)), feed = feed) } }
            compose.onNodeWithTag("feed-cold").assertDoesNotExist()
            compose.onNodeWithText("Customize my feed").assertDoesNotExist()
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Feed and weather settings").assertDoesNotExist()
        } finally { server.shutdown() }
    }
    @Test fun careFailuresDoNotDisableOnlineFeedControls() {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        try {
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true })
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(feedFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(weatherFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"location":{"latitude":0.5,"longitude":0.5,"label":"Synthetic location"}}"""))
            val member = Member("synthetic-member", Role.owner)
            feed.refresh(member)
            feed.loadLocation()
            val care = mutableStateOf(CareState(member = member, offline = true))
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value, feed = feed) } }
            compose.onNodeWithTag("feed-rain").performScrollTo()
            compose.onNodeWithText("Mark 1 outdoor plants watered").assertIsEnabled()
            compose.runOnIdle { care.value = CareState(member = member, serverUnavailable = true) }
            compose.onNodeWithText("Mark 1 outdoor plants watered").assertIsEnabled()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"location":{"latitude":0.5,"longitude":0.5}}"""))
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Feed and weather settings").performScrollTo().performClick()
            compose.onNodeWithText("Save my feed").assertIsEnabled()
            compose.onNodeWithText("Latitude").assertIsEnabled()
            compose.onNodeWithText("Save location").performScrollTo().assertIsEnabled()
            compose.runOnIdle { care.value = CareState(member = member, offline = true) }
            compose.onNodeWithText("Save location").assertIsEnabled()
            compose.onNodeWithText("Save my feed").assertIsEnabled()
        } finally { server.shutdown() }
    }
    @Test fun refreshingLeavesSettingsAndRainActionsEnabled() {
        val current = mutableStateOf(state().copy(refreshing = true,
            weather = state().weather!!.copy(lastBatchId = "batch-one")))
        var settings by mutableStateOf(false)
        compose.setContent { Screen(false) {
            if (settings) FeedSettingsScreen(current.value) else HomeFeed(current.value, onSettings = { settings = true })
        } }
        compose.onNodeWithTag("feed-rain").performScrollTo()
        compose.onNodeWithText("Mark 1 outdoor plants watered").assertIsEnabled()
        compose.onNodeWithText("Undo rain watering").assertIsEnabled()
        compose.onNodeWithText("It rained: 6 mm today or yesterday.").assertExists()
        compose.onNodeWithText("Customize my feed").performScrollTo().performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        repeat(4) { compose.onAllNodes(isToggleable())[it].assertIsEnabled() }
        compose.onNodeWithText("Latitude").assertIsEnabled()
        compose.onNodeWithText("Longitude").assertIsEnabled()
        compose.onNodeWithText("Place label, optional").assertIsEnabled()
        compose.onNodeWithText("Save my feed").assertIsEnabled()
        compose.onNodeWithText("Save location").assertIsEnabled()
        compose.runOnIdle { current.value = current.value.copy(saving = true) }
        compose.onNodeWithText("Latitude").assertIsNotEnabled()
        compose.onNodeWithText("Save my feed").assertIsNotEnabled()
    }
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val current = mutableStateOf(state())
        var settings by mutableStateOf(false)
        var opened = ""
        var waters = 0
        var saved: Map<String, Boolean>? = null
        compose.setContent { Screen(dark) {
            if (settings) {
                assertEquals(if (dark) DarkColors.background else LightColors.background, MaterialTheme.colorScheme.background)
                FeedSettingsScreen(current.value, onSaveFeed = { saved = it })
            } else HomeFeed(current.value, onSettings = { settings = true }, onOpen = { opened = it }, onWater = { waters++ })
        } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("2 tasks are late").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        capture("feed-home")
        compose.onNodeWithTag("feed-cold").performScrollTo()
        compose.onNode(hasText("Low temperature", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Synthetic outdoor fern") and hasAnyAncestor(hasTestTag("feed-cold"))).performClick()
        assertEquals("/plants/fern", opened)
        capture("feed-cold")
        compose.onNodeWithTag("feed-rain").performScrollTo()
        compose.onNodeWithText("Mark 1 outdoor plants watered").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, waters)
        capture("feed-rain")
        compose.runOnIdle { current.value = current.value.copy(weather = current.value.weather!!.copy(lastBatchId = "batch-one", rain = current.value.weather!!.rain!!.copy(batchId = "batch-one"))) }
        compose.onNodeWithText("Undo rain watering").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        capture("feed-rain-watered")
        compose.runOnIdle { current.value = current.value.copy(offline = true) }
        compose.onNodeWithText("Undo rain watering").assertIsNotEnabled()
        capture("feed-offline")
        compose.runOnIdle { current.value = state() }
        compose.onNodeWithTag("feed-warnings").performScrollTo()
        compose.onNodeWithText("Warning inputs").performClick()
        compose.onNode(hasText("warning.effective_light@1", substring = true)).assertIsDisplayed()
        capture("feed-warnings")
        compose.onNodeWithText("Customize my feed").performScrollTo().performClick()
        compose.onNodeWithText("Feed settings").performScrollTo().assertIsDisplayed()
        capture("feed-settings")
        compose.onNodeWithText("Plant warnings").performScrollTo().performClick()
        compose.onNodeWithText("Save my feed").performScrollTo().performClick()
        assertEquals(false, saved!!.getValue("plant_warnings"))
        compose.onNodeWithText("Latitude").performScrollTo()
        compose.onNodeWithText("Save location").performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        capture("feed-location")
        compose.runOnIdle { current.value = state(Role.family) }
        compose.onNodeWithText("Save location").assertDoesNotExist()
        compose.onNodeWithText("Only the owner can change it.").performScrollTo().assertIsDisplayed()
        capture("feed-family-location")
        compose.runOnIdle { current.value = state(Role.sitter) }
        compose.onNodeWithText("Weather location").assertDoesNotExist()
        compose.onNodeWithText("Save my feed").assertDoesNotExist()
        compose.runOnIdle { settings = false }
        compose.onNodeWithText("Customize my feed").assertDoesNotExist()
        compose.onNodeWithTag("feed-cold").assertDoesNotExist()
        compose.onNodeWithTag("feed-warnings").assertDoesNotExist()
        capture("feed-sitter")
        compose.runOnIdle { current.value = FeedState(member = Member("synthetic-member", Role.owner), offline = true) }
        compose.onNodeWithText("Feed unavailable offline").assertIsDisplayed()
        capture("feed-unavailable-offline")
    }
}
