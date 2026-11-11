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

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class, com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
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
            compose.onNodeWithText("Mark 1 outdoor plant watered").assertIsEnabled()
            compose.runOnIdle { care.value = CareState(member = member, serverUnavailable = true) }
            compose.onNodeWithText("Mark 1 outdoor plant watered").assertIsEnabled()
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
            if (settings) FeedSettingsScreen(current.value) else HomeFeed(current.value)
        } }
        compose.onNodeWithTag("feed-rain").performScrollTo()
        compose.onNodeWithText("Mark 1 outdoor plant watered").assertIsEnabled()
        compose.onNodeWithContentDescription("Undo rain watering").assertIsEnabled()
        compose.onNodeWithText("Rain on Sat 1 Jun, 6 mm").assertExists()
        compose.runOnIdle { settings = true }
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
    @Test fun lateTasksAreLeftToTheListBelow() {
        val current = mutableStateOf(state())
        compose.setContent { Screen(false) { HomeFeed(current.value, today = "2030-06-01") } }
        compose.onNodeWithTag("feed-late").assertDoesNotExist()
        compose.onNode(hasText("late", substring = true)).assertDoesNotExist()
        compose.onNodeWithTag("feed-cold").assertExists()
        compose.runOnIdle { current.value = state(Role.sitter) }
        compose.onAllNodes(hasAnyAncestor(hasTestTag("feed-strip"))).assertCountEquals(0)
        compose.onNode(hasText("late", substring = true)).assertDoesNotExist()
    }
    @Test fun cardsUsePlainWordsAndKeepTheDetailsBehindWhy() {
        compose.setContent { Screen(false) { HomeFeed(state(), today = "2030-06-01") } }
        compose.onNodeWithText("Synthetic outdoor fern: too dark").assertExists()
        compose.onNodeWithText("Synthetic outdoor fern: frost today, bring inside").assertExists()
        compose.onNodeWithText("Today: low 0 °C, high 30 °C").assertExists()
        for (technical in listOf("warning.effective_light@1", "effectiveLight", "Hardiness source", "hardiness.pot_offset@1", "Open-Meteo", "Warning inputs", "2030-06-07"))
            compose.onNode(hasText(technical, substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Why?") and hasAnyAncestor(hasTestTag("feed-warnings-too_dark"))).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("The site is too dark for this plant.").assertIsDisplayed()
        compose.onNodeWithText("Technical details: warning.effective_light@1 · rule").assertIsDisplayed()
        compose.onNode(hasText("effectiveLight", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(hasText("warning.effective_light@1", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Why?") and hasAnyAncestor(hasTestTag("feed-cold"))).performScrollTo().performClick()
        compose.onNode(hasText("Technical details:", substring = true) and hasText("hardiness.pot_offset@1", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Sat 1 Jun to Fri 7 Jun", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("2030-06-0", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(hasText("Why?") and hasAnyAncestor(hasTestTag("feed-current"))).performScrollTo().performClick()
        val checked = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse("2030-06-01T10:00:00Z"))
        compose.onNodeWithText("Checked $checked").assertIsDisplayed()
        compose.onNodeWithText("Technical details: Open-Meteo").assertIsDisplayed()
        compose.onNode(hasText("2030-06-01T", substring = true)).assertDoesNotExist()
    }
    @Test fun aLongWarningStillNamesItsPlantAtLargeText() {
        val peony = parseFeedCard(JSONObject("""{"id":"peony","kind":"plant_warnings","href":"/plants/peony","plantName":"Chinese peony","warnings":[{"kind":"missing_info","reason":"Missing information.","inputs":[
            {"input":"site.light","value":"not set","source":"setting"},{"input":"pot.pot_type","value":"not set","source":"setting"},
            {"input":"careProfile","value":{},"source":"rule"},{"input":"household.location","value":false,"source":"setting"}]}]}"""))!!
        compose.setContent { CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 1.3f)) {
            Screen(false) { HomeFeed(state().copy(cards = listOf(peony)), today = "2030-06-01") }
        } }
        val layout = compose.onNode(hasAnyAncestor(hasTestTag("feed-peony-missing_info")) and hasText("missing", substring = true, ignoreCase = true), useUnmergedTree = true)
            .fetchSemanticsNode().textLayout()
        val text = layout.layoutInput.text.text
        assertTrue("Up to two lines: ${layout.lineCount}", layout.lineCount <= 2)
        val at = text.indexOf("Chinese peony")
        assertTrue("The plant name is not on the first line: $text", at >= 0 && at + "Chinese peony".length <= layout.getLineEnd(0))
    }
    @Test fun warningsOfOneKindShareACardWhoseWhyOpensEachPlant() {
        fun plant(id: String, name: String, vararg kinds: String) = parseFeedCard(JSONObject().put("id", id).put("kind", "plant_warnings").put("href", "/plants/$id").put("plantName", name)
            .put("warnings", org.json.JSONArray(kinds.map { kind -> if (kind == "too_dark") JSONObject().put("kind", kind).put("reason", "Too dark.").put("inputs", org.json.JSONArray())
                else JSONObject().put("kind", "missing_info").put("reason", "Missing information.").put("inputs", org.json.JSONArray()
                    .put(JSONObject().put("input", "pot.pot_size_cm").put("value", "not set").put("source", "setting"))) })))!!
        val cards = listOf(plant("peony", "Chinese peony", "missing_info"), plant("jade", "Jade Plant", "too_dark", "missing_info"), plant("thyme", "Thyme", "missing_info"))
        var opened = ""
        compose.setContent { Screen(false) { HomeFeed(state().copy(cards = cards), today = "2030-06-01", onOpen = { opened = it }) } }
        compose.onAllNodesWithText("Missing pot size for 3 plants").assertCountEquals(1)
        compose.onNodeWithText("Jade Plant: too dark").assertExists()
        compose.onNode(hasText("Chinese peony", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Missing pot size for 3 plants").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", opened) }
        for (name in listOf("Chinese peony", "Jade Plant", "Thyme")) compose.onNode(hasText(name) and hasClickAction()).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Thyme") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("/plants/thyme", opened) }
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNode(hasText("Why?") and hasAnyAncestor(hasText("Missing pot size for 3 plants"))).performClick()
        compose.onNode(hasText("Chinese peony") and hasClickAction()).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/screenshots/feed-grouped-why-light.png")
    }
    @Test fun offlineTheStripKeepsItsCardsAndSaysWeatherAndTipsNeedAConnection() {
        val current = mutableStateOf(state().copy(offline = true))
        compose.setContent { Screen(false) { HomeFeed(current.value, today = "2030-06-01", connectionNotice = false) } }
        compose.onNodeWithText("Weather and tips need a connection").assertIsDisplayed()
        compose.onNodeWithTag("feed-cold").assertExists()
        compose.runOnIdle { current.value = FeedState(member = Member("synthetic-member", Role.owner), offline = true) }
        compose.onNodeWithText("Weather and tips need a connection").assertIsDisplayed()
        compose.onAllNodes(hasAnyAncestor(hasTestTag("feed-strip"))).assertCountEquals(0)
    }
    @Test fun theRainCardComesFirstWithItsCountedReceipt() {
        val current = mutableStateOf(state())
        var waters = 0
        var undos = 0
        compose.setContent { Screen(false) { HomeFeed(current.value, today = "2030-06-01", onWater = { waters++ }, onUndo = { undos++ }) } }
        val order = listOf("feed-rain", "feed-cold", "feed-current", "feed-warnings-too_dark")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.x }
        assertEquals(order.sorted(), order)
        compose.onNodeWithTag("feed-rain").assert(hasText("Rain today, 6 mm")).assertIsDisplayed()
        compose.onNodeWithText("Mark 1 outdoor plant watered").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, waters)
        compose.runOnIdle { current.value = current.value.copy(weather = current.value.weather!!.copy(lastBatchId = "batch-one", lastBatchPlantCount = 1,
            rain = current.value.weather!!.rain!!.copy(batchId = "batch-one"))) }
        compose.onNodeWithText("Watered 1 outdoor plant").assertIsDisplayed()
        compose.onNodeWithText("Mark 1 outdoor plant watered").assertDoesNotExist()
        compose.onNodeWithContentDescription("Undo rain watering").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, undos)
    }
    @Test fun aNewRainOffersWateringWhileTheOlderBatchStaysUndoable() {
        val both = state().let { it.copy(weather = it.weather!!.copy(lastBatchId = "batch-old", lastBatchPlantCount = 2)) }
        var waters = 0
        var undos = 0
        compose.setContent { Screen(false) { HomeFeed(both, today = "2030-06-01", onWater = { waters++ }, onUndo = { undos++ }) } }
        val card = compose.onNodeWithTag("feed-rain").fetchSemanticsNode().boundsInRoot
        assertTrue("The rain card stays compact: ${card.height}", card.height <= 112f)
        assertEquals("The rain card keeps the strip's height", compose.onNodeWithTag("feed-cold").fetchSemanticsNode().size.height.toFloat(), card.height, 1f)
        val receipt = compose.onNode(hasText("Watered 2 outdoor plants") and hasAnyAncestor(hasTestTag("feed-rain")), useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The older batch's receipt lies inside the card", receipt.left >= card.left && receipt.right <= card.right && receipt.bottom <= card.bottom)
        for (action in listOf(hasText("Mark 1 outdoor plant watered"), hasContentDescription("Undo rain watering"))) {
            val button = compose.onNode(action and hasAnyAncestor(hasTestTag("feed-rain"))).assertExists().assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue("$action lies inside the card", bounds.left >= card.left && bounds.right <= card.right && bounds.bottom <= card.bottom)
            button.performClick()
        }
        compose.runOnIdle { assertEquals(1, waters); assertEquals(1, undos) }
    }
    @Test fun theFeedLeavesConnectionNoticesToTodaysBanner() {
        val current = mutableStateOf(state().copy(offline = true))
        val notice = mutableStateOf(false)
        compose.setContent { Screen(false) { HomeFeed(current.value, today = "2030-06-01", connectionNotice = notice.value) } }
        compose.onNode(hasText("offline", substring = true, ignoreCase = true)).assertDoesNotExist()
        compose.runOnIdle { current.value = state().copy(unavailable = true) }
        compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
        compose.runOnIdle { notice.value = true }
        compose.onNodeWithText("Fytorium isn't responding. Showing last loaded cards. Refresh to try again.").assertIsDisplayed()
        compose.runOnIdle { current.value = state().copy(offline = true) }
        compose.onNodeWithText("Weather and tips need a connection").assertIsDisplayed()
        compose.runOnIdle { notice.value = false; current.value = state().copy(message = "Synthetic feed failure") }
        compose.onNodeWithText("Synthetic feed failure").assertIsDisplayed()
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
            } else HomeFeed(current.value, today = "2030-06-01", onOpen = { opened = it }, onWater = { waters++ })
        } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("2 tasks are late").assertDoesNotExist()
        compose.onNodeWithTag("feed-cold").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        capture("feed-home")
        compose.onNodeWithTag("feed-cold").performScrollTo()
        compose.onNodeWithText("Synthetic outdoor fern: frost today, bring inside").assertIsDisplayed()
        compose.onNodeWithTag("feed-cold").performClick()
        assertEquals("/plants/fern", opened)
        capture("feed-cold")
        compose.onNodeWithTag("feed-rain").performScrollTo()
        compose.onNodeWithText("Mark 1 outdoor plant watered").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, waters)
        capture("feed-rain")
        compose.runOnIdle { current.value = current.value.copy(weather = current.value.weather!!.copy(lastBatchId = "batch-one", lastBatchPlantCount = 1, rain = current.value.weather!!.rain!!.copy(batchId = "batch-one"))) }
        compose.onNodeWithText("Watered 1 outdoor plant").assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo rain watering").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        capture("feed-rain-watered")
        compose.runOnIdle { current.value = current.value.copy(offline = true) }
        compose.onNodeWithContentDescription("Undo rain watering").assertIsNotEnabled()
        capture("feed-offline")
        compose.runOnIdle { current.value = state().let { it.copy(weather = it.weather!!.copy(lastBatchId = "batch-old", lastBatchPlantCount = 2)) } }
        compose.onNodeWithText("Watered 2 outdoor plants").assertIsDisplayed()
        compose.onNodeWithText("Mark 1 outdoor plant watered").assertIsDisplayed()
        capture("feed-rain-both")
        compose.runOnIdle { current.value = state() }
        compose.onNodeWithTag("feed-warnings-too_dark").performScrollTo()
        compose.onNode(hasText("Why?") and hasAnyAncestor(hasTestTag("feed-warnings-too_dark"))).performClick()
        compose.onNode(hasText("Technical details:", substring = true)).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/screenshots/feed-warnings-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { settings = true }
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
        compose.onNodeWithTag("feed-cold").assertDoesNotExist()
        compose.onNodeWithTag("feed-warnings-too_dark").assertDoesNotExist()
        capture("feed-sitter")
        compose.runOnIdle { current.value = FeedState(member = Member("synthetic-member", Role.owner), offline = true) }
        compose.onNodeWithText("Weather and tips need a connection").assertIsDisplayed()
        capture("feed-unavailable-offline")
    }
}
