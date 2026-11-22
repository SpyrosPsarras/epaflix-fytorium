package com.epaflix.fytorium.feed

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.epaflix.fytorium.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WeatherFeedScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = Member("synthetic-owner", Role.owner)
    private val settings = mapOf("late_tasks" to true, "current_weather" to true, "weather_alerts" to true, "plant_warnings" to true)
    private val bergen = Place("Bergen, Vestland, Norway", "Bergen", "Vestland", "Norway", 60.39, 5.32)

    // W5a-P20, P30: a switch shows its new value at once and saves; a refused save returns only that switch and names it.
    @Test fun aSwitchSavesOnChangeAndARefusalReturnsOnlyIt() {
        val answer = CompletableDeferred<Boolean>()
        val sent = mutableListOf<Pair<String, Boolean>>()
        val receipts = mutableListOf<String>()
        compose.setContent { FytoriumTheme { WeatherFeedScreen(FeedState(member = owner, settings = settings), null, true, onReceipt = { receipts += it },
            onSetting = { key, value -> sent += key to value; answer.await() }, failure = { "Synthetic refusal." }) } }
        val alerts = compose.onNode(hasText("Weather alerts") and isToggleable())
        alerts.performScrollTo().assertIsOn().performClick()
        alerts.assertIsOff()
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf("weather_alerts" to false), sent)
        answer.complete(false)
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        alerts.assertIsOn()
        assertEquals(listOf("Weather alerts wasn't saved. Synthetic refusal."), receipts)
        compose.onNodeWithText("Late tasks").assertDoesNotExist()
    }

    // W5a-P30: taps save in order; an earlier write's end keeps a later tap of the same switch, and a refusal returns only its switch.
    @Test fun overlappingTapsKeepTheirOrderAndTheirOwnValues() {
        val answers = mutableListOf<CompletableDeferred<Boolean>>()
        val sent = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { FytoriumTheme { WeatherFeedScreen(FeedState(member = owner, settings = settings), null, true,
            onSetting = { key, value -> sent += key to value; CompletableDeferred<Boolean>().also { answers += it }.await() }, failure = { "Synthetic refusal." }) } }
        val alerts = compose.onNode(hasText("Weather alerts") and isToggleable())
        val warnings = compose.onNode(hasText("Plant warnings") and isToggleable())
        alerts.performScrollTo().performClick()
        alerts.performClick()
        warnings.performScrollTo().performClick()
        compose.waitUntil(5_000) { answers.size == 1 }
        assertEquals(listOf("weather_alerts" to false), sent)
        answers[0].complete(false)
        compose.waitUntil(5_000) { answers.size == 2 }
        alerts.assertIsOn()
        warnings.assertIsOff()
        assertEquals(listOf("weather_alerts" to false, "weather_alerts" to true), sent)
        answers[1].complete(true)
        compose.waitUntil(5_000) { answers.size == 3 }
        assertEquals("plant_warnings" to false, sent.last())
        warnings.assertIsOff()
        answers[2].complete(true)
    }

    // W5a-P30 (S1): taps already made still save after the screen goes, in their order.
    @Test fun tapsStillSaveAfterTheScreenGoes() {
        val first = CompletableDeferred<Boolean>()
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        val shown = mutableStateOf(true)
        compose.setContent { FytoriumTheme { if (shown.value) WeatherFeedScreen(FeedState(member = owner, settings = settings), null, true,
            onSetting = { key, value -> sent += key to value; if (sent.size == 1) first.await() else true }) } }
        compose.onNode(hasText("Weather alerts") and isToggleable()).performScrollTo().performClick()
        compose.onNode(hasText("Plant warnings") and isToggleable()).performScrollTo().performClick()
        compose.waitUntil(5_000) { sent.size == 1 }
        compose.runOnIdle { shown.value = false }
        first.complete(true)
        compose.waitUntil(5_000) { sent.size == 2 }
        assertEquals(listOf("weather_alerts" to false, "plant_warnings" to false), sent.toList())
    }

    // W5a-P16: Try again repeats the query that was sent, and an older reply never replaces a newer one.
    @Test fun tryAgainRepeatsTheSentQueryAndOlderRepliesAreIgnored() {
        val replies = mutableMapOf<String, CompletableDeferred<FeedRepository.PlaceAnswer>>()
        compose.setContent { FytoriumTheme { WeatherFeedScreen(FeedState(member = owner, settings = settings, locationLoaded = true), null, true,
            onSearch = { q -> CompletableDeferred<FeedRepository.PlaceAnswer>().also { replies[q] = it }.await() }) } }
        val field = compose.onNodeWithText("Find your town")
        field.performTextInput("Oslo"); field.performImeAction()
        field.performTextReplacement("Bergen"); field.performImeAction()
        compose.waitUntil(5_000) { replies.size == 2 }
        replies.getValue("Bergen").complete(FeedRepository.PlaceAnswer(listOf(bergen)))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Vestland, Norway").fetchSemanticsNodes().isNotEmpty() }
        // An older search's outage shows no banner over the newer results.
        replies.getValue("Oslo").complete(FeedRepository.PlaceAnswer(null, unavailable = true))
        compose.waitForIdle()
        compose.onNodeWithText("Place search isn't responding").assertDoesNotExist()
        compose.onNodeWithText("Vestland, Norway").assertIsDisplayed()
    }

    // W5a-P16: searching lists places by name and region; picking one saves it and names it in the receipt.
    @Test fun aPlaceSearchListsPlacesAndPickingOneSavesIt() {
        val receipts = mutableListOf<String>()
        var saved: WeatherLocation? = null
        val state = mutableStateOf(FeedState(member = owner, settings = settings, locationLoaded = true))
        compose.setContent { FytoriumTheme { WeatherFeedScreen(state.value, null, true, onReceipt = { receipts += it },
            onSearch = { FeedRepository.PlaceAnswer(if (it == "Bergen") listOf(bergen) else emptyList()) }, onSaveLocation = { saved = it; true }) } }
        compose.onNodeWithText("No location set. Weather cards and frost alerts need one.").assertIsDisplayed()
        compose.onNodeWithText("Find your town").performTextInput("B")
        compose.onNodeWithText("Find your town").performImeAction()
        compose.onNodeWithText("Type at least 2 letters.").assertIsDisplayed()
        compose.onNodeWithText("Find your town").performTextReplacement("B".repeat(101))
        compose.onNodeWithText("Find your town").performImeAction()
        compose.onNodeWithText("Type at most 100 letters.").assertIsDisplayed()
        compose.onNodeWithText("Find your town").performTextReplacement("Zzqx")
        compose.onNodeWithText("Find your town").performImeAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No place called \u201cZzqx\u201d").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Find your town").performTextReplacement("Bergen")
        compose.onNodeWithText("Find your town").performImeAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Vestland, Norway").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Vestland, Norway").performScrollTo().performClick()
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        assertEquals(WeatherLocation(60.39, 5.32, "Bergen, Vestland, Norway"), saved)
        assertEquals(listOf("Weather location set to Bergen, Vestland, Norway"), receipts)
        compose.onNodeWithText("Vestland, Norway").assertDoesNotExist()
    }

    // W5a-P17, P18: coordinates are checked before sending, and removing asks first.
    @Test fun coordinatesAreCheckedAndRemovingAsksFirst() {
        val saves = mutableListOf<WeatherLocation?>()
        compose.setContent { FytoriumTheme { WeatherFeedScreen(FeedState(member = owner, settings = settings, locationLoaded = true,
            location = WeatherLocation(60.39, 5.32, null)), null, true, onSaveLocation = { saves += it; true }) } }
        compose.onNodeWithText("60.39, 5.32").assertIsDisplayed()
        compose.onNodeWithText("Enter coordinates instead").performClick()
        compose.onNodeWithText("Latitude").performTextReplacement("95")
        compose.onNodeWithText("Save location").performScrollTo().performClick()
        compose.onNodeWithText("Enter a number from -90 to 90.").assertIsDisplayed()
        compose.onNodeWithText("Latitude").performTextReplacement("-33.9")
        compose.onNodeWithText("Save location").performScrollTo().performClick()
        compose.waitUntil(5_000) { saves.isNotEmpty() }
        assertEquals(listOf<WeatherLocation?>(WeatherLocation(-33.9, 5.32, null)), saves)
        // A refusal shows under Save, and the form stays with the draft.
        compose.onNodeWithText("Remove location").performScrollTo().performClick()
        compose.onNodeWithText("Remove the household location?").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { saves.size == 2 }
        assertNull(saves.last())
    }

    // W5a-P19, P30: family reads the place and who can change it; offline a switch explains instead of changing.
    @Test fun familyReadsThePlaceAndOfflineSwitchesExplain() {
        val receipts = mutableListOf<String>()
        compose.setContent { FytoriumTheme { WeatherFeedScreen(FeedState(member = Member("synthetic-family", Role.family), settings = settings, locationLoaded = true,
            location = WeatherLocation(60.39, 5.32, "Bergen, Vestland, Norway")), CareState(names = MemberNames("Synthetic Ada", "Synthetic Owen")), false,
            onReceipt = { receipts += it }) } }
        compose.onNodeWithText("Bergen, Vestland, Norway").assertIsDisplayed()
        compose.onNodeWithText("Only Synthetic Owen can change it.").assertIsDisplayed()
        compose.onNodeWithText("Find your town").assertDoesNotExist()
        compose.onNode(hasText("Today's weather") and isToggleable()).performScrollTo().assert(hasStateDescription("Changing settings needs a connection.")).performClick().assertIsOn()
        assertEquals(listOf("Changing settings needs a connection."), receipts)
    }
}
