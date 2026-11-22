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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    // W5b-P23: the import bar names what is left to choose, and Import explains instead of acting until it is chosen.
    @Test fun theImportBarExplainsWhyImportIsBlocked() {
        val receipts = mutableListOf<String>()
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row.copy(id = "confirmed", name = "Synthetic confirmed", status = "confirmed", canCommit = true), row)))
        compose.setContent { FytoriumTheme { PlantaScreen(state.value, onReceipt = { receipts += it }) } }
        compose.onNodeWithText("Still to choose:").assertIsDisplayed()
        compose.onNode(hasText("Synthetic imported fern") and hasAnyAncestor(hasTestTag("import-bar"))).assertIsDisplayed()
        compose.onAllNodesWithText("No Planta photo").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No reference photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import 1 plant").assert(hasStateDescription("Choose a species or skip the plants above first.")).performClick()
        compose.runOnIdle { assertEquals(listOf("Choose a species or skip the plants above first."), receipts) }
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(status = "confirmed", canCommit = true), row.copy(id = "skipped", name = "Synthetic skipped", status = "skipped"))) }
        compose.onNodeWithText("Still to choose:").assertDoesNotExist()
        compose.onNodeWithText("1 skipped plant stays out.").assertIsDisplayed()
        compose.onNodeWithText("Import 1 plant").assertIsEnabled().assert(!hasStateDescription("Choose a species or skip the plants above first."))
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(status = "skipped"))) }
        compose.onNodeWithText("Nothing to import").assertIsNotEnabled()
    }
    @Test fun theBarNamesScrollToTheirCards() {
        val rows = List(3) { row.copy(id = "review-$it", name = "Synthetic review $it") }
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = rows)) } }
        compose.onNodeWithText("and 1 more").assertIsDisplayed()
        for (target in listOf(rows[1], rows[0])) {
            compose.onNode(hasText(target.name) and hasAnyAncestor(hasTestTag("import-bar"))).assertHeightIsAtLeast(48.dp).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("import-heading:${target.id}").assertIsDisplayed()
        }
        compose.onNode(hasText(rows[2].name) and hasAnyAncestor(hasTestTag("import-bar"))).assertDoesNotExist()
    }
    @Test fun theIntroSaysARenameInFytoriumStaysUntilTheNameInPlantaChanges() {
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true)) } }
        compose.onNodeWithText("Fetching again updates plants you imported before: species, cultivar, site, pot and soil change to Planta's. " +
            "A name you gave a plant in Fytorium stays until its name in Planta changes.").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("nickname", substring = true, ignoreCase = true)).assertCountEquals(0)
    }
    // W5b-P21: the code step links the Planta App Portal, and Go on the keyboard fetches.
    @Test fun theCodeStepLinksThePortalAndGoFetches() {
        var opened: String? = null
        var fetched: String? = null
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) { opened = uri }
        }) { FytoriumTheme { PlantaScreen(PlantaState(loaded = true), onFetch = { fetched = it; true }) } } }
        compose.onNodeWithText("Open Planta App Portal").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("https://getplanta.com/apps/manage", opened) }
        compose.onNodeWithText("One-time app code").performTextInput("synthetic-go")
        compose.onNodeWithText("One-time app code").performImeAction()
        compose.runOnIdle { assertEquals("synthetic-go", fetched) }
    }
    // W5b-P22: details read as sentences, with no raw values; done rows fold into one line each.
    @Test fun detailsReadAsSentences() {
        val ready = row.copy(status = "confirmed", pot = "in_ground", size = "unknown", soil = "unknown", coverStatus = "imported",
            care = mapOf("water" to "2026-11-10", "fertilize" to "2026-10-20T09:00:00Z", "repot" to "2025-04-02", "clean_leaves" to "2026-11-01"))
        assertEquals("Terracotta pot · 18 cm · Cactus soil", potLine(row))
        assertEquals("In the ground", potLine(ready))
        assertNull(potLine(row.copy(pot = "unknown", size = "unknown", soil = "unknown")))
        val year = java.time.LocalDate.now().year
        assertEquals(listOf("Last watered " + (if (year == 2026) "10 Nov" else "10 Nov 2026"), "Last fertilized " + (if (year == 2026) "20 Oct" else "20 Oct 2026"),
            "Leaves last cleaned " + (if (year == 2026) "1 Nov" else "1 Nov 2026"), "Last repotted 2 Apr 2025"), careLines(ready))
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row.copy(status = "skipped"), ready.copy(id = "ready"), row))) } }
        compose.onNodeWithText("3 plants: 1 ready, 1 needs a choice, 1 skipped").assertIsDisplayed()
        compose.onNodeWithText("Choose the species").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("From Planta: Testoria neblosa · Synthetic nook").assertExists()
        compose.onNodeWithText("Photo imported").assertDoesNotExist()
        compose.onNodeWithTag("import-heading:ready", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Photo imported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In the ground").assertIsDisplayed()
        for (raw in listOf("in_ground", "unknown", "2026-11-10", "_")) compose.onAllNodes(hasText(raw, substring = true)).assertCountEquals(0)
    }
    @Test fun aTileSaysWhichOtherNameMatchedAndIsNamedByItsHeadline() {
        val matched = candidate.copy(matchedName = "Synthetic misty leaf", commonName = "Synthetic cloudleaf")
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row.copy(candidates = listOf(matched))))) } }
        compose.onNodeWithContentDescription("This is Synthetic cloudleaf").performScrollTo().assertIsEnabled()
            .assert(hasText("Matches \u201cSynthetic misty leaf\u201d"))
    }
    @Test fun reviewSkipIsAvailableWithoutExpandingControls() {
        var skipped: String? = null
        compose.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true, rows = listOf(row)), onSkip = { skipped = it }) } }
        compose.onNodeWithText("Search species").assertDoesNotExist()
        compose.onNodeWithText("Skip plant").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(row.id, skipped) }
    }
    @Test fun reviewInstructionsExplainReadyAndUnmatchedPlants() {
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row.copy(status = "confirmed", selectedName = candidate.canonicalName,
            selectedKey = candidate.key, selectedCommonName = "Synthetic cloudleaf"))))
        compose.setContent { FytoriumTheme { PlantaScreen(state.value) } }
        compose.onNodeWithText("Ready").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 plant: 1 ready").assertIsDisplayed()
        compose.onNodeWithTag("import-heading:${row.id}", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Synthetic cloudleaf").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change species or skip").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(candidates = emptyList()))) }
        compose.onNodeWithText("No match found. Search for the species or skip this plant.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Search species").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Search for a species").performScrollTo().performClick()
        compose.onNodeWithText("Search species").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row)) }
        compose.onNodeWithText("Choose the species").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Which picture looks like your plant?").assertDoesNotExist()
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
                compose.onAllNodesWithText("Synthetic cloudleaf", useUnmergedTree = true).assertCountEquals(0)
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
            PlantaScreen(state.value, online.value, onFetch = { fetched = it; true }, onConfirm = { _, key, _ -> confirmed = key })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/a11-$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("No plants to review yet. Fetch from Planta to start.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-time app code").performScrollTo().performTextInput("synthetic-secret")
        capture("code")
        compose.onNodeWithText("Fetch plants").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("synthetic-secret", fetched); state.value = state.value.copy(rows = listOf(row)) }
        // With a review open, the code step folds into one button.
        compose.onNodeWithText("Fetch again with a new code").assertIsDisplayed()
        compose.onNodeWithText("One-time app code").assertDoesNotExist()
        compose.onNodeWithText("Import plants").assert(hasStateDescription("Choose a species or skip the plants above first."))
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("review")
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(candidates = emptyList()))) }
        compose.onNodeWithText("Search for a species").assertExists()
        compose.onNodeWithText("Search species").performScrollTo().assertIsEnabled()
        capture("unmatched")
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row)) }
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(910002L, confirmed); state.value = state.value.copy(rows = listOf(row.copy(status = "confirmed", selectedName = "Testoria nebulosa", canCommit = true))) }
        compose.onNodeWithText("Import 1 plant").assertIsEnabled()
        capture("confirmed")
        compose.onNodeWithTag("import-heading:${row.id}", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Change species or skip").performScrollTo().performClick()
        compose.onNodeWithText("Search species").performTextInput("Testoria typo")
        compose.runOnIdle { state.value = state.value.copy(searchRow = row.id, search = com.epaflix.fytorium.plants.SpeciesSearch("search-one", listOf(candidate))) }
        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertIsEnabled()
        capture("search")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithContentDescription("This is Testoria nebulosa").assertIsNotEnabled()
        compose.onNode(hasText("Offline") and hasClickAction()).assertIsDisplayed()
        capture("offline")
        compose.runOnIdle { online.value = true; state.value = state.value.copy(blocked = true, error = "Fytorium isn't responding. Your staged plants stay. Try again when it answers.") }
        compose.onNode(hasText("The review can't be changed right now") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Fetch again with a new code").performScrollTo().performClick()
        compose.onNodeWithText("Fetch plants").performScrollTo().assertIsNotEnabled()
        capture("failure")
        compose.runOnIdle { state.value = state.value.copy(blocked = false, error = null, complete = true, incompleteSites = true,
            search = null, rows = listOf(row.copy(status = "committed", canReview = false, coverStatus = "imported", plantId = "plant-one"))) }
        compose.onNodeWithText("Import complete").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New sites from Planta start with default conditions.").assertIsDisplayed()
        compose.onNodeWithText("Check sites").assertIsDisplayed()
        compose.onNodeWithTag("import-bar").assertDoesNotExist()
        capture("complete")
        // A resumed review keeps the imported cover status without the transient completion banner.
        compose.runOnIdle { state.value = state.value.copy(complete = false) }
        // The row is still open from the species change above.
        compose.onNodeWithText("Photo imported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open imported plant").assertIsEnabled()
        capture("cover")
        compose.runOnIdle { state.value = state.value.copy(rows = listOf(row.copy(status = "committed", canReview = false, coverStatus = "failed", coverError = "the image could not be read."))) }
        compose.onNodeWithText("Photo not imported: the image could not be read. Fetch again to retry.").performScrollTo().assertIsDisplayed()
        capture("cover-failed")
    }
    @Test fun codeIsNotSavedAndIsClearedBeforeAnUnsuccessfulFetch() {
        val restore = StateRestorationTester(compose)
        var submitted: String? = null
        restore.setContent { FytoriumTheme { PlantaScreen(PlantaState(loaded = true), onFetch = { submitted = it; false }) } }
        compose.onNodeWithText("One-time app code").performTextInput("synthetic-never-save")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
        compose.onNodeWithText("One-time app code").performTextInput("synthetic-once")
        compose.onNodeWithText("Fetch plants").performScrollTo().performClick()
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
            val line = hasText("The review didn't load") and hasClickAction()
            compose.waitUntil(5_000) { compose.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("One-time app code").assertIsNotEnabled()
            // No Refresh button (W5b-P26): the line's Try again reloads.
            compose.onNode(line).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("One-time app code") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("One-time app code").performTextInput("synthetic-code")
            compose.onNodeWithText("Fetch plants").performScrollTo().assertIsEnabled()
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
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Fetch again with a new code")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fetch again with a new code").performClick()
            compose.onNodeWithText("One-time app code").performTextInput("synthetic-first-code")
            compose.onNodeWithText("Fetch plants").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic imported fern").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("One-time app code").performScrollTo().assertIsEnabled().assertTextEquals("One-time app code", "")
            compose.onNodeWithText("One-time app code").performTextInput("synthetic-new-code")
            compose.onNodeWithText("Fetch plants").performScrollTo().assertIsEnabled().performClick()
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
        compose.onNodeWithText("Skipped").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip plant").assertDoesNotExist()
        compose.onNodeWithContentDescription("This is Testoria nebulosa").assertDoesNotExist()
        compose.onNodeWithTag("import-heading:${row.id}", useUnmergedTree = true).performClick()
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
    // W5b-P24: an import that leaves a site on default conditions says so, and Check sites opens Plants, Sites.
    @Test fun checkSitesNavigatesFromImportToSites() {
        MockWebServer().use { server ->
            val fixture = org.json.JSONObject(importFixture).put("status", "confirmed").put("can_commit", true).put("candidates", org.json.JSONArray())
                .apply { getJSONObject("mapped").put("has_cover", false) }
            val committed = org.json.JSONObject(fixture.toString()).put("status", "committed").put("can_commit", false).put("can_review", false).put("plant_id", "plant-one")
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody(when (request.path) {
                    "/api/import/planta" -> "[$fixture]"
                    "/api/import/planta/commit" -> "[$committed]"
                    "/api/sites" -> """[{"id":"site-nook","name":"Synthetic nook","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":1,"imported_conditions_incomplete":true}]"""
                    else -> "[]"
                })
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("owner", Role.owner)),
                sitesApi = api, sitesConnected = { true }) } }
            compose.onNode(isTab("More")).performClick()
            compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Import 1 plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Import 1 plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Check sites").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Imported 1 plant").assertExists()
            compose.onNodeWithText("Check sites").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Indoor · 1 plant · Conditions not set").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isTab("Plants")).assertIsSelected()
            compose.onNodeWithText("Import from Planta").assertDoesNotExist()
        }
    }
    @Test fun writeControlsRejectDoubleTapsUntilSubmissionFinishes() {
        val state = mutableStateOf(PlantaState(loaded = true, rows = listOf(row)))
        var release = CompletableDeferred<Unit>()
        var writes = 0
        suspend fun write() { writes++; release.await() }
        compose.setContent { FytoriumTheme { PlantaScreen(state.value, onFetch = { write(); false },
            onConfirm = { _, _, _ -> write() }, onSkip = { write() }, onCommit = { write(); false }) } }
        for ((index, label) in listOf("Fetch plants", "This is Testoria nebulosa", "Skip plant", "Import 1 plant").withIndex()) {
            compose.runOnIdle {
                release = CompletableDeferred()
                state.value = state.value.copy(rows = listOf(if (index == 3) row.copy(status = "confirmed", canCommit = true) else row))
            }
            if (index == 0) {
                compose.onNodeWithText("Fetch again with a new code").performClick()
                compose.onNodeWithText("One-time app code").performTextInput("synthetic-once")
            }
            // The import bar stays at the bottom, outside the scrolling page.
            val control = when (index) { 1 -> compose.onNodeWithContentDescription(label).performScrollTo(); 3 -> compose.onNodeWithTag("import-button"); else -> compose.onNodeWithText(label).performScrollTo() }
            val click = control.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            // Invoke the same event twice before recomposition can disable the button.
            compose.runOnIdle { click(); click() }
            control.assertIsNotEnabled()
            compose.runOnIdle { assertEquals(index + 1, writes); release.complete(Unit) }
            // A fetch clears the code, so the field, not Fetch, comes back on.
            if (index == 0) compose.onNodeWithText("One-time app code").assertIsEnabled() else control.assertIsEnabled()
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
        compose.onNodeWithText("Fetch again with a new code").performScrollTo().performClick()
        compose.onNodeWithText("One-time app code").performScrollTo().assertIsEnabled()
    }
    @Test fun onlyOwnerSeesImportEntry() {
        val care = mutableStateOf(CareState(member = Member("member", Role.owner)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNode(isTab("More")).performClick()
        compose.onNodeWithText("Import from Planta").performScrollTo().assertIsDisplayed()
        for (role in listOf(Role.family, Role.sitter)) {
            compose.runOnIdle { care.value = care.value.copy(member = Member("member", role)) }
            compose.onNodeWithText("Import from Planta").assertDoesNotExist()
        }
    }
}
