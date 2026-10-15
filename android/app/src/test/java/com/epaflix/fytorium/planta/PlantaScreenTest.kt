package com.epaflix.fytorium.planta

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
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
    @Test fun reviewSummaryAndPhotoPlaceholdersExplainWhyImportIsBlocked() {
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row.copy(id = "confirmed", name = "Synthetic confirmed", status = "confirmed"), row)))
        compose.setContent { FytoriumTheme { PlantaScreen(state.value) } }
        compose.onAllNodesWithText("1 plant still needs review:").assertCountEquals(2)
        compose.onAllNodesWithText("No Planta photo").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No reference photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import confirmed plants").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(status = "confirmed", canCommit = true))) }
        compose.onAllNodesWithText("1 plant still needs review:").assertCountEquals(0)
        compose.onNodeWithText("Import confirmed plants").performScrollTo().assertIsEnabled()
    }
    @Test fun bothReviewSummariesScrollToTheNamedCard() {
        val rows = List(3) { row.copy(id = "review-$it", name = "Synthetic review $it") }
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = rows)) } }
        for (summary in listOf("top", "bottom")) {
            for (target in listOf(rows.last(), rows.first())) {
                compose.onNode(hasText(target.name) and hasAnyAncestor(hasTestTag("review-summary:$summary")))
                    .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("import-heading:${target.id}").assertIsDisplayed()
            }
        }
    }
    @Test fun reviewSkipIsAvailableWithoutExpandingControls() {
        var skipped: String? = null
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row)), onSkip = { skipped = it }) } }
        compose.onNodeWithText("Search species").assertDoesNotExist()
        compose.onNodeWithText("Skip plant").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(row.id, skipped) }
    }
    @Test fun reviewInstructionsExplainReadyAndUnmatchedPlants() {
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row.copy(status = "confirmed", selectedName = candidate.canonicalName))))
        compose.setContent { FytoriumTheme { PlantaScreen(state.value) } }
        compose.onNodeWithText("Plants marked Needs review need one tap: choose the matching species or skip. Everything else is ready.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ready to import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change species or skip").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(candidates = emptyList()))) }
        compose.onNodeWithText("No match found. Search for the species or skip this plant.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Search species").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Search for a species").performScrollTo().performClick()
        compose.onNodeWithText("Search species").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row)) }
        compose.onNodeWithText("Which picture looks like your plant?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertIsEnabled()
    }
    @Test fun photoTilesShowSuggestionsAndGenusWithoutNameMatchPercentages() {
        var picked: Long? = null
        val genus = candidate.copy(key = 910000, canonicalName = "Testoria", scientificName = "Testoria Inventor", rank = "GENUS")
        val suggestion = ImportSuggestion(candidate.key, emptyList(), genus)
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row), suggestions = mapOf(row.id to suggestion)))
        compose.setContent { FytoriumTheme { PlantaScreen(state.value, onConfirm = { _, key, _ -> picked = key }) } }
        compose.onNodeWithText("This is my plant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Looks most like your photo").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("confidence", substring = true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(candidate.key, picked) }
        compose.onNodeWithText("Not sure: import as Testoria (any kind)").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(910000L, picked); state.value = state.value.copy(rows = listOf(row.copy(status = "confirmed", selectedName = candidate.canonicalName))) }
        compose.onNodeWithTag("import-ready:${row.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip plant").assertDoesNotExist()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").assertDoesNotExist()
    }
    @Test fun importChoiceBoundsStayFixedThroughDelayedPhotoCredit() {
        val creditReady = java.util.concurrent.CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    if (request.path.orEmpty().endsWith("/credit")) {
                        if (!creditReady.await(10, java.util.concurrent.TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                        return MockResponse().setBody("""{"common_name":"Synthetic cloudleaf","photo":{"creator":"Synthetic photographer","license":"CC BY 4.0","source_url":"https://www.gbif.org/occurrence/1"}}""")
                    }
                    val image = java.io.ByteArrayOutputStream().apply {
                        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, this)
                    }
                    return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(okio.Buffer().write(image.toByteArray()))
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row)), imageApi = api) } }
            try {
                val pill = compose.onNodeWithText("This is my plant", useUnmergedTree = true)
                pill.performScrollTo().assertIsDisplayed()
                val before = pill.fetchSemanticsNode().boundsInRoot
                creditReady.countDown()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("CC BY 4.0 · Photo: Synthetic photographer", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Reference photo of Testoria nebulosa", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                val after = pill.fetchSemanticsNode().boundsInRoot
                println("A11 photo credit choice bounds: before=$before after=$after")
                assertEquals(before, after)
            } finally { creditReady.countDown() }
        }
    }
    @Test fun disabledPhotoPillsChangeColourAndRejectTaps() {
        val online = mutableStateOf(true)
        val blocked = mutableStateOf(false)
        val dark = mutableStateOf(false)
        var confirmations = 0
        lateinit var view: android.view.View
        compose.setContent { FytoriumTheme(dark.value) {
            view = LocalView.current
            PlantaScreen(PlantaState(loaded = true, blocked = blocked.value, rows = listOf(row)), online.value,
                onConfirm = { _, _, _ -> confirmations++ })
        } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night; online.value = true; blocked.value = false }
            val pill = compose.onNodeWithText("This is my plant", useUnmergedTree = true)
            pill.performScrollTo()
            fun colour(): Int {
                pill.performScrollTo()
                val bounds = pill.fetchSemanticsNode().boundsInRoot
                return compose.runOnIdle { view.drawToBitmap().getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 1) }
            }
            val enabledColour = colour()
            for (offline in listOf(true, false)) {
                compose.runOnIdle { online.value = !offline; blocked.value = !offline }
                val tile = compose.onNodeWithContentDescription("This is Testoria nebulosa")
                tile.assertIsNotEnabled()
                assertNotEquals(enabledColour, colour())
                tile.performClick()
                compose.runOnIdle { assertEquals(0, confirmations) }
            }
        }
    }
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
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("review")
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(candidates = emptyList()))) }
        compose.onNodeWithText("Search for a species").assertExists()
        compose.onNodeWithText("Search species").performScrollTo().assertIsEnabled()
        capture("unmatched")
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row)) }
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(910002L, confirmed); state.value = state.value.copy(rows = listOf(row.copy(status = "confirmed", selectedName = "Testoria nebulosa", canCommit = true))) }
        compose.onNodeWithText("Import confirmed plants").performScrollTo().assertIsEnabled()
        capture("confirmed")
        compose.onNodeWithText("Change species or skip").performScrollTo().performClick()
        compose.onNodeWithText("Search species").performTextInput("Testoria typo")
        compose.runOnIdle { state.value = state.value.copy(searchRow = row.id, search = com.epaflix.fytorium.plants.SpeciesSearch("search-one", listOf(candidate))) }
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertIsEnabled()
        capture("search")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithContentDescription("This is Testoria nebulosa").assertIsNotEnabled()
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertIsNotEnabled()
        capture("offline")
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").performScrollTo()
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
            val fixture = org.json.JSONObject(importFixture).put("status", "confirmed").put("candidates", org.json.JSONArray()).apply { getJSONObject("mapped").put("has_cover", false) }
            server.enqueue(MockResponse().setBody("[$fixture]"))
            server.enqueue(MockResponse().setResponseCode(503).setBody(org.json.JSONObject().put("message", message).toString()))
            server.enqueue(MockResponse().setBody("[$fixture]"))
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
    @Test fun skippedPlantsCanBeRestoredByChoosingASpecies() {
        var confirmed: Long? = null
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row.copy(status = "skipped"))),
            onConfirm = { _, key, _ -> confirmed = key }) } }
        compose.onNodeWithText("Skipped. This plant will not be imported.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip plant").assertDoesNotExist()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").assertDoesNotExist()
        compose.onNodeWithText("Undo skip").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Search species").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(910002L, confirmed) }
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
        for ((index, label) in listOf("Fetch Planta plants", "This is Testoria nebulosa", "Skip plant", "Import confirmed plants").withIndex()) {
            compose.runOnIdle {
                release = CompletableDeferred()
                state.value = state.value.copy(rows = listOf(if (index == 3) row.copy(status = "confirmed", canCommit = true) else row))
            }
            if (index == 0) compose.onNodeWithText("One-time app code").performTextInput("synthetic-once")
            val control = (if (index == 1) compose.onNodeWithContentDescription(label) else compose.onNodeWithText(label)).performScrollTo()
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
        compose.onNodeWithText("Search for another species").performScrollTo().performClick()
        compose.onAllNodesWithText("Skip plant").assertCountEquals(1)
        compose.onNodeWithText("Search species").performTextInput("Testoria")
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("Find species").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertIsEnabled()
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
