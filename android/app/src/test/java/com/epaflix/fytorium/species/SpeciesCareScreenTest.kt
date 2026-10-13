package com.epaflix.fytorium.species

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import androidx.core.view.drawToBitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SpeciesCareScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun card(): CareCard = JSONObject(careFixture).let {
        CareCard(910002, "Testoria nebulosa Inventor", it.getJSONObject("profile"), it.getJSONArray("fetches").mapObjects { row -> row }, JSONObject(outdoorFixture))
    }
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val state = mutableStateOf(SpeciesCareState(card = card(), loaded = true))
        val online = mutableStateOf(true)
        var value: Any? = null
        var reset: String? = null
        var retried: Long? = null
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SpeciesCareScreen(state.value, online.value, onOverride = { _, input -> value = input; true },
                onReset = { reset = it; true }, onRetry = { retried = it })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/$name-${if (dark) "dark" else "light"}.png"
            if (name in listOf("species-override", "species-reset")) {
                // Roborazzi window capture misses Compose dialogs, as in PlantsScreenTest.
                val layout = compose.onNode(isDialog()).fetchSemanticsNode().layoutInfo
                val owner = layout.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layout) as android.view.View
                compose.runOnIdle { owner.drawToBitmap().captureRoboImage(file) }
            } else compose.onRoot().captureRoboImage(file)
        }
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithText("Refresh care data").assertHeightIsAtLeast(48.dp)
        capture("species-care")
        compose.onNodeWithText("Source: rules · water.base@1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Override watering interval in days").performScrollTo().performClick()
        compose.onNodeWithText("Override value for watering interval in days").assertIsDisplayed()
        compose.onNodeWithText("Value").performTextReplacement("9")
        capture("species-override")
        compose.onNodeWithText("Save override").performClick()
        compose.runOnIdle {
            assertEquals(9, value)
            state.value.card!!.profile.put("wateringIntervalDays", JSONObject("""{"value":9,"source":"override","fetchedAt":"2030-06-02T12:00:00Z"}"""))
            state.value = state.value.copy(message = "Override saved.")
        }
        compose.onNodeWithText("Source: override").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove override for watering interval in days").performScrollTo().performClick()
        capture("species-reset")
        compose.onNodeWithText("Confirm reset").performClick()
        compose.runOnIdle { assertEquals("wateringIntervalDays", reset); online.value = false }
        compose.onNodeWithText("Override watering interval in days").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").performScrollTo().assertIsDisplayed()
        capture("species-offline")
        compose.runOnIdle { online.value = true; state.value = SpeciesCareState(unmatched = listOf(UnmatchedSpecies(910002, "Testoria nebulosa", "ambiguous")), loaded = true) }
        compose.onNodeWithText("Testoria nebulosa: ambiguous").assertIsDisplayed()
        capture("species-unmatched")
        compose.onNodeWithText("Retry match").performClick()
        compose.runOnIdle { assertEquals(910002L, retried) }
    }
    @Test fun backgroundReadsKeepDraftsEnabledAndFailedWritesKeepTheEditor() {
        val state = mutableStateOf(SpeciesCareState(card = card(), loaded = true))
        compose.setContent { FytoriumTheme { SpeciesCareScreen(state.value, onOverride = { _, _ ->
            state.value = state.value.copy(loading = false, blocked = true, error = "Invalid value for wateringIntervalDays"); false
        }, onRefresh = { state.value = state.value.copy(blocked = false, error = null) }) } }
        compose.onNodeWithText("Override watering interval in days").performScrollTo().performClick()
        compose.onNodeWithText("Value").performTextReplacement("9")
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("Save override").assertIsEnabled().performClick()
        compose.onNodeWithText("Invalid value for wateringIntervalDays").assertIsDisplayed()
        compose.onNodeWithText("Value").assertTextContains("9")
        compose.onNodeWithText("Retry loading species").performClick()
        compose.onNodeWithText("Value").assertTextContains("9")
        compose.onNodeWithText("Save override").assertIsEnabled()
    }
    @Test fun onlyOwnerAndFamilySeeUnmatchedSpeciesUnderMore() {
        val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Unmatched species").assertIsDisplayed()
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.family)) }
        compose.onNodeWithText("Unmatched species").assertIsDisplayed()
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
        compose.onNodeWithText("Unmatched species").assertDoesNotExist()
    }
    @Test fun pendingCarePollsOnlyWhileStartedAndStopsWhenDone() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(careFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(careFixture.replace("pending", "done")))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
                tokens = Tokens("synthetic-access", "synthetic-refresh")
            }) {}
            val owner = object : androidx.lifecycle.LifecycleOwner {
                val registry = androidx.lifecycle.LifecycleRegistry(this)
                override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
            }
            compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}) }
            } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Refresh care data")).fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(2_200)
            assertEquals(2, server.requestCount)
            compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
            compose.waitUntil(5_000) { server.requestCount == 4 }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Open Plantbook fetch: done")).fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(2_200)
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun careFailuresNeverGateSpeciesAndSessionChangesHideControls() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(careFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner), busy = true, offline = true, serverUnavailable = true))
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, care.value, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Refresh care data") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Override watering interval in days").performScrollTo().assertIsEnabled()
            compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
            compose.onNodeWithText("Refresh care data").assertDoesNotExist()
            compose.onNodeWithText("Retry match").assertDoesNotExist()
            compose.onNodeWithText("Override watering interval in days").assertDoesNotExist()
        }
    }
}
