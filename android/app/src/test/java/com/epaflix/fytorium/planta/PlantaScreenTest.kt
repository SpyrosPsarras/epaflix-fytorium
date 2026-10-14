package com.epaflix.fytorium.planta

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.parseSpeciesSearch
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
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
class PlantaScreenTest {
    @get:Rule val compose = createComposeRule()
    private val candidate = parseSpeciesSearch("""{"search_id":"search-one","candidates":[$importCandidate]}""").candidates.single()
    private val row = ImportRow("staging-one", "Synthetic imported fern", "Testoria neblosa", "Synthetic nook", "Invented stripe",
        "terracotta", "18", "cactus", mapOf("water" to "2026-06-01"), "review", "pending", null, null, listOf(candidate), null, false, true)
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val state = mutableStateOf(PlantaState(loaded = true))
        val online = mutableStateOf(true)
        var fetched: String? = null
        var confirmed: Long? = null
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PlantaScreen(state.value, online.value, onFetch = { fetched = it }, onConfirm = { _, key, _ -> confirmed = key })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/a11-$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("No staged plants. Fetch from Planta to start a review.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-time app code").performScrollTo().performTextInput("synthetic-secret")
        capture("code")
        compose.onNodeWithText("Fetch Planta plants").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("synthetic-secret", fetched); state.value = state.value.copy(rows = listOf(row)) }
        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
        compose.onNodeWithText("Import confirmed plants").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("review")
        compose.onNodeWithText("Confirm Testoria nebulosa").performClick()
        compose.runOnIdle { assertEquals(910002L, confirmed); state.value = state.value.copy(rows = listOf(row.copy(status = "confirmed", selectedName = "Testoria nebulosa", canCommit = true))) }
        compose.onNodeWithText("Import confirmed plants").performScrollTo().assertIsEnabled()
        capture("confirmed")
        compose.onNodeWithText("Search again").performScrollTo().performClick()
        compose.onNodeWithText("Search species").performTextInput("Testoria typo")
        compose.runOnIdle { state.value = state.value.copy(searchRow = row.id, search = com.epaflix.fytorium.plants.SpeciesSearch("search-one", listOf(candidate))) }
        compose.onNodeWithText("Confirm searched Testoria nebulosa").performScrollTo().assertIsEnabled()
        capture("search")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Confirm searched Testoria nebulosa").assertIsNotEnabled()
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").performScrollTo().assertIsDisplayed()
        capture("offline")
        compose.runOnIdle { online.value = true; state.value = state.value.copy(blocked = true, error = "Fytorium isn't responding. Your staged plants remain available. Refresh to retry.") }
        compose.onNodeWithText("Refresh review").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Fetch Planta plants").assertIsNotEnabled()
        capture("failure")
        compose.runOnIdle { state.value = state.value.copy(blocked = false, error = null, complete = true,
            search = null, rows = listOf(row.copy(status = "committed", canReview = false, coverStatus = "imported", plantId = "plant-one"))) }
        compose.onNodeWithText("Import complete.").performScrollTo().assertIsDisplayed()
        capture("complete")
        // A resumed review keeps the imported cover status without the transient completion banner.
        compose.runOnIdle { state.value = state.value.copy(complete = false) }
        compose.onNodeWithText("Cover imported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open imported plant").assertIsEnabled()
        capture("cover")
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(status = "committed", canReview = false, coverStatus = "failed", coverError = "the image could not be read."))) }
        compose.onNodeWithText("Cover not imported: the image could not be read. Fetch again to retry the cover.").performScrollTo().assertIsDisplayed()
        capture("cover-failed")
    }
    @Test fun codeIsNotSavedAndIsClearedBeforeAnUnsuccessfulFetch() {
        val restore = StateRestorationTester(compose)
        var submitted: String? = null
        restore.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true), onFetch = { submitted = it }) } }
        compose.onNodeWithText("One-time app code").performTextInput("synthetic-never-save")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
        compose.onNodeWithText("One-time app code").performTextInput("synthetic-once")
        compose.onNodeWithText("Fetch Planta plants").performScrollTo().performClick()
        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
        compose.runOnIdle { assertEquals("synthetic-once", submitted) }
    }
    @Test fun ownRefreshRecoversWritesDespiteCareBeingBusyOfflineAndUnavailable() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setBody("[]"))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            val care = CareState(member = Member("owner", Role.owner), busy = true, offline = true, serverUnavailable = true)
            compose.setContent { FytoriumTheme { PlantaRoute(api, care, { true }, {}, {}, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Fytorium isn't responding. Your staged plants remain available. Refresh to retry.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("One-time app code").assertIsNotEnabled()
            compose.onNodeWithText("Refresh review").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("One-time app code") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("One-time app code").performTextInput("synthetic-code")
            compose.onNodeWithText("Fetch Planta plants").performScrollTo().assertIsEnabled()
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun aPlantaFailureAllowsANewCodeWithoutRefreshingTheReview() {
        MockWebServer().use { server ->
            server.start()
            val message = "Planta is rate-limiting this import. Try again later with a new app code."
            server.enqueue(MockResponse().setBody("[$importFixture]"))
            server.enqueue(MockResponse().setResponseCode(503).setBody(org.json.JSONObject().put("message", message).toString()))
            server.enqueue(MockResponse().setBody("[$importFixture]"))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            compose.setContent { FytoriumTheme { PlantaRoute(api, CareState(member = Member("owner", Role.owner)), { true }, {}, {}, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("One-time app code") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("One-time app code").performTextInput("synthetic-first-code")
            compose.onNodeWithText("Fetch Planta plants").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic imported fern").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("One-time app code").performScrollTo().assertIsEnabled().assertTextEquals("One-time app code", "")
            compose.onNodeWithText("One-time app code").performTextInput("synthetic-new-code")
            compose.onNodeWithText("Fetch Planta plants").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { server.requestCount == 3 && compose.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }
            assertEquals("GET", server.takeRequest().method)
            assertEquals("POST", server.takeRequest().method)
            val retry = server.takeRequest()
            assertEquals("POST", retry.method)
            assertEquals("synthetic-new-code", org.json.JSONObject(retry.body.readUtf8()).getString("code"))
        }
    }
    @Test fun skippedPlantsCanBeSearchedButCannotBeSkippedAgain() {
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row.copy(status = "skipped")))) } }
        compose.onNodeWithText("Skip plant").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Search again").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Search species").assertIsEnabled()
    }
    @Test fun attributionOpensGbifExternally() {
        var opened: String? = null
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) { opened = uri }
        }) { FytoriumTheme { PlantaScreen(PlantaState(loaded = true)) } } }
        compose.onNodeWithText("Species names from GBIF, CC BY 4.0").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("https://www.gbif.org", opened) }
    }
    @Test fun sitesLinkNavigatesFromImportToSites() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[]"))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("owner", Role.owner)),
                sitesApi = api, sitesConnected = { true }) } }
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
            compose.onNodeWithText("Complete them in Sites").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No sites yet. Create a room, garden or terrace for your plants.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create site").assertIsEnabled()
            compose.onNodeWithText("Import from Planta").assertDoesNotExist()
        }
    }
    @Test fun writeControlsRejectDoubleTapsUntilSubmissionFinishes() {
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row)))
        var release = CompletableDeferred<Unit>()
        var writes = 0
        suspend fun write() { writes++; release.await() }
        compose.setContent { FytoriumTheme { PlantaScreen(state.value, onFetch = { write() },
            onConfirm = { _, _, _ -> write() }, onSkip = { write() }, onCommit = { write() }) } }
        for ((index, label) in listOf("Fetch Planta plants", "Confirm Testoria nebulosa", "Skip plant", "Import confirmed plants").withIndex()) {
            compose.runOnIdle {
                release = CompletableDeferred()
                state.value = state.value.copy(rows = listOf(if (index == 3) row.copy(status = "confirmed", canCommit = true) else row))
            }
            if (index == 0) compose.onNodeWithText("One-time app code").performTextInput("synthetic-once")
            val control = compose.onNodeWithText(label).performScrollTo()
            val click = control.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            // Invoke the same event twice before recomposition can disable the button.
            compose.runOnIdle { click(); click() }
            control.assertIsNotEnabled()
            compose.onNodeWithText("One-time app code").assertIsNotEnabled()
            compose.onNodeWithText("Refresh review").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(index + 1, writes); release.complete(Unit) }
            compose.onNodeWithText("One-time app code").assertIsEnabled()
        }
    }
    @Test fun aBackgroundReadNeverDisablesReviewControls() {
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row)))
        compose.setContent { FytoriumTheme { PlantaScreen(state.value) } }
        compose.onNodeWithText("Search again").performScrollTo().performClick()
        compose.onNodeWithText("Search species").performTextInput("Testoria")
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("Find species").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("One-time app code").performScrollTo().assertIsEnabled()
    }
    @Test fun onlyOwnerSeesImportEntry() {
        val care = mutableStateOf(CareState(member = Member("member", Role.owner)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Import from Planta").performScrollTo().assertIsDisplayed()
        for (role in listOf(Role.family, Role.sitter)) {
            compose.runOnIdle { care.value = care.value.copy(member = Member("member", role)) }
            compose.onNodeWithText("Import from Planta").assertDoesNotExist()
        }
    }
}
