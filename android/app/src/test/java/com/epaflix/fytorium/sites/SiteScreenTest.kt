package com.epaflix.fytorium.sites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SiteScreenTest {
    @get:Rule val compose = createComposeRule()
    private val site = Site("site-one", mapOf("name" to "Synthetic room", "location" to "indoor", "light" to "part_sun", "humidity" to "normal",
        "warm_min_c" to "18", "warm_max_c" to "25", "cold_min_c" to "", "cold_max_c" to "20"), growLight = true, plantCount = 2, importedIncomplete = true)
    private val empty = Site("site-two", mapOf("name" to "Synthetic shelf", "location" to "outdoor", "light" to "full_sun", "humidity" to "dry",
        "warm_min_c" to "", "warm_max_c" to "", "cold_min_c" to "", "cold_max_c" to ""), draft = true)
    private fun plantOn(id: String, name: String, siteId: String) = Plant(id, name, "Testoria lucida", "active", "Synthetic room", null, null, null, null,
        org.json.JSONObject().put("site_id", siteId).toString())
    private fun openSite(name: String = "Synthetic room") = compose.onNodeWithText(name).performClick()
    @Test fun aSiteChoiceStaysOpenAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var choice = ""
        restoration.setContent { FytoriumTheme {
            com.epaflix.fytorium.plants.Choice("Synthetic condition", "indoor", mapOf("indoor" to "Indoor", "outdoor" to "Outdoor"), true) { choice = it }
        } }
        compose.onNodeWithText("Synthetic condition").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("Outdoor") and hasAnyAncestor(isPopup())).performClick()
        assertEquals("outdoor", choice)
    }

    // W5b-P3, P4: rows read as sentences and open a site page that lists the site's own plants from the cached list.
    @Test fun aSitePageReadsAsSentencesAndListsItsOwnPlants() {
        val opened = mutableListOf<String>()
        val care = CareState(member = Member("synthetic-member", Role.owner), plants = listOf(plantOn("plant-a", "Synthetic fern", "site-one"),
            plantOn("plant-b", "Synthetic aloe", "site-one"), plantOn("plant-c", "Synthetic other", "site-three")))
        compose.setContent { FytoriumTheme { SitesScreen(SiteState(listOf(site, empty), loaded = true), care = care, onOpenPlant = { opened += it }) } }
        compose.onNodeWithText("Indoor · 2 plants · Conditions not set").assertIsDisplayed()
        compose.onNodeWithText("Outdoor · No plants").assertIsDisplayed()
        compose.onNodeWithText("Refresh sites").assertDoesNotExist()
        openSite()
        for (line in listOf("Indoor · Part sun · Normal humidity", "Grow light", "Warm half of the year: 18 to 25 °C", "Cold half of the year: at most 20 °C",
            "Imported from Planta with default conditions. Edit the site to set them.", "Plants (2)",
            "To delete Synthetic room, move its 2 plants to another site or to the graveyard first.")) compose.onNodeWithText(line).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic other").assertDoesNotExist()
        // A site with plants offers no delete the server would refuse (W5b-P7).
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit Synthetic room").assertIsDisplayed()
        compose.onNodeWithText("Synthetic aloe").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("plant-b"), opened) }
        compose.onNodeWithContentDescription("Back").performClick()
        openSite("Synthetic shelf")
        compose.onNodeWithText("Draft from a door, window or vent").assertIsDisplayed()
        compose.onNodeWithText("No plants here yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete site").assertIsDisplayed()
    }

    // W5b-P6, P7: saves and deletes name the site in a receipt; a site created from the list opens its own page.
    @Test fun receiptsNameTheSiteAndANewSiteOpensItsPage() {
        val state = mutableStateOf(SiteState(listOf(site, empty), loaded = true))
        val receipts = mutableListOf<String>()
        compose.setContent { FytoriumTheme { SitesScreen(state.value, onReceipt = { receipts += it }, createdId = { state.value.created }, onSave = { saved ->
            if (saved.id == null) state.value = state.value.copy(sites = state.value.sites + saved.copy(id = "site-new"), created = "site-new"); true
        }, onDelete = { id -> state.value = state.value.copy(sites = state.value.sites.filter { it.id != id }); true }) } }
        compose.onNodeWithContentDescription("Create site").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic hallway")
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        compose.onNodeWithContentDescription("Edit Synthetic hallway").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        openSite()
        compose.onNodeWithContentDescription("Edit Synthetic room").performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.waitUntil(5_000) { receipts.size == 2 }
        compose.onNodeWithContentDescription("Back").performClick()
        openSite("Synthetic shelf")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete site").performClick()
        compose.onNodeWithText("Synthetic shelf and its conditions are removed for good. Plants in the graveyard that were here keep their history; restoring one asks for a site.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { receipts.size == 3 }
        assertEquals(listOf("Created Synthetic hallway", "Saved Synthetic room", "Deleted Synthetic shelf"), receipts)
        compose.onNodeWithText("Synthetic shelf").assertDoesNotExist()
        compose.onNodeWithText("Synthetic room").assertIsDisplayed()
    }

    // W5b-P27: the last list is kept, so the pages read offline with its age; a write explains instead of failing.
    @Test fun offlineSitesReadTheLastCopyAndWritesExplain() {
        val cache = mutableMapOf<String, Pair<String, Long>>()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(
                    """[{"id":"site-one","name":"Synthetic room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":0}]""")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val online = mutableStateOf(true)
            val shown = mutableStateOf(true)
            val receipts = mutableListOf<String>()
            val care = CareState(member = Member("synthetic-member", Role.owner))
            compose.setContent { FytoriumTheme { if (shown.value) SitesRoute(api, care, { online.value }, {}, onReceipt = { receipts += it },
                cached = { cache[it] }, keep = { key, json -> 1_000L.also { cache[key] = json to it } }) } }
            compose.waitUntil(5_000) { "site-list" in cache }
            compose.runOnIdle { shown.value = false; online.value = false }
            compose.runOnIdle { shown.value = true }
            // The offline copy is read off the main thread, so wait for it.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic room").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic room").assertIsDisplayed()
            compose.onNode(hasText("Offline", substring = true) and hasClickAction()).assertIsDisplayed()
            // Sol W5b-sol-2: it looks off as well as explaining.
            compose.onNodeWithContentDescription("Create site").assert(hasStateDescription(sitesOffline)).assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf(sitesOffline), receipts) }
            compose.onNodeWithText("Save site").assertDoesNotExist()
        }
    }

    // Sol W5b-S8: a change Fytorium left unanswered stops pausing writes once Fytorium answers again.
    @Test fun aBlockedSaveRecoversWhenFytoriumAnswersAgain() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val down = java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.method == "POST" && down.get()) okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}")
                    else okhttp3.mockwebserver.MockResponse().setBody("[]")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val answers = mutableIntStateOf(0)
            compose.setContent { FytoriumTheme { SitesRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { true }, {}, answers = answers.intValue) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No sites yet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Create site").performClick()
            compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
            compose.onNodeWithText("Save site").performScrollTo().performClick()
            val paused = hasText("Sites can't be changed right now") and hasClickAction()
            compose.waitUntil(5_000) { compose.onAllNodes(paused).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { down.set(false); answers.intValue++ }
            compose.waitUntil(5_000) { compose.onAllNodes(paused).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Name").assertTextContains("Synthetic porch")
        }
    }

    // Sol W5b-S8 (round 2): an answer that arrives while a save is still running reloads once that save fails.
    @Test fun anAnswerDuringAFailingSaveStillReloads() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val release = java.util.concurrent.CountDownLatch(1)
            val posted = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (request.method == "POST") {
                    posted.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}")
                } else okhttp3.mockwebserver.MockResponse().setBody("[]")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val answers = mutableIntStateOf(0)
            compose.setContent { FytoriumTheme { SitesRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { true }, {}, answers = answers.intValue) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No sites yet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Create site").performClick()
            compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
            compose.onNodeWithText("Save site").performScrollTo().performClick()
            assertTrue(posted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.runOnIdle { answers.intValue++ }
            // The answer is handled while the save still runs.
            compose.waitForIdle()
            release.countDown()
            val paused = hasText("Sites can't be changed right now") and hasClickAction()
            compose.waitUntil(5_000) { server.requestCount >= 3 && compose.onAllNodes(paused).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
        }
    }

    @Test fun aNewSiteDraftSurvivesRecreation() = siteDraftSurvivesRecreation(edit = false)
    @Test fun anExistingSiteDraftSurvivesRecreation() = siteDraftSurvivesRecreation(edit = true)

    private fun siteDraftSurvivesRecreation(edit: Boolean) {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var saved: Site? = null
        restoration.setContent { FytoriumTheme { SitesScreen(SiteState(listOf(site), loaded = true), onSave = { saved = it; true }) } }
        if (edit) { openSite(); compose.onNodeWithContentDescription("Edit Synthetic room").performClick() }
        else compose.onNodeWithContentDescription("Create site").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
        compose.onNodeWithText("Grow light").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(if (edit) "Edit Synthetic room" else "New site").assertIsDisplayed()
        compose.onNodeWithText("Name").performScrollTo().assertTextContains("Synthetic porch")
        compose.onNodeWithText("Grow light").performScrollTo().let { if (edit) it.assertIsOff() else it.assertIsOn() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard(if (edit) "Your changes to Synthetic room are not saved yet." else "Your changes to this new site are not saved yet.")
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(if (edit) "site-one" else null, saved!!.id)
            assertEquals("Synthetic porch", saved!!.fields["name"])
        }
    }

    @Test fun aSiteDiscardQuestionSurvivesRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SitesScreen(SiteState(listOf(site), loaded = true)) } }
        openSite()
        compose.onNodeWithContentDescription("Edit Synthetic room").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
        compose.onNodeWithContentDescription("Back").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Keep editing").assertIsDisplayed()
    }

    @Test fun aSiteDeletionQuestionSurvivesAnOfflineReload() {
        val state = mutableStateOf(SiteState(listOf(empty), loaded = true))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SitesScreen(state.value, online = state.value.loaded) } }
        openSite("Synthetic shelf")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete site").performClick()
        compose.runOnIdle { state.value = SiteState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Delete site?").assertIsDisplayed()
        compose.onNodeWithText("Synthetic shelf and its conditions are removed for good. Plants in the graveyard that were here keep their history; restoring one asks for a site.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete").assert(hasStateDescription(sitesOffline))
    }

    @Test fun anOfflineRecreatedSiteEditorKeepsItsDraftAndDiscardsItBeforeCreate() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(
                    """[{"id":"site-one","name":"Synthetic room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false}]""")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val online = mutableStateOf(true)
            val answers = mutableIntStateOf(0)
            var dirty: () -> String? = { null }
            var exits = 0
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme { SitesRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { online.value },
                { exits++ }, answers = answers.intValue, onFormDirty = { dirty = it }) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic room").fetchSemanticsNodes().isNotEmpty() }
            openSite()
            compose.onNodeWithContentDescription("Edit Synthetic room").performClick()
            compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
            compose.runOnIdle { online.value = false }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Edit Synthetic room").assertIsDisplayed()
            compose.onNodeWithText("Name").assertTextContains("Synthetic porch")
            compose.runOnIdle { assertEquals("Your changes to Synthetic room are not saved yet.", dirty()) }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic room are not saved yet.")
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.runOnIdle { assertEquals(0, exits); online.value = true; answers.intValue++ }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Create site") and isEnabled() and !hasStateDescription(sitesOffline)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Create site").performClick()
            compose.onNode(hasText("Name") and hasSetTextAction()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        }
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun siteDeletionStaysUsableInLandscapeWithLargeText() = landscapeDeletion("Synthetic room", "site-delete-landscape-large")

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun longNameSiteDeletionStaysUsableInLandscapeWithLargeText() = landscapeDeletion("Synthetic room ".repeat(15).take(200), "site-delete-long-name-landscape-large")

    private fun landscapeDeletion(name: String, screenshot: String) {
        val current = empty.copy(fields = empty.fields + ("name" to name))
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme { SitesScreen(SiteState(listOf(current), loaded = true)) } }
        compose.onNodeWithTag("site:site-two").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete site").performClick()
        val title = compose.onNodeWithText("Delete site?", useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertTrue("The short title fits in two lines", title.lineCount <= 2 && !title.isLineEllipsized(title.lineCount - 1))
        val body = "$name and its conditions are removed for good. Plants in the graveyard that were here keep their history; restoring one asks for a site."
        compose.onNodeWithText(body).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(name) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Delete").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.checkDialogTextScale("Delete", 1.3f)
        compose.captureDialog("src/test/screenshots/$screenshot.png")
    }

    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val state = mutableStateOf(SiteState(listOf(site, empty), loaded = true))
        val online = mutableStateOf(true)
        var saved: Site? = null
        val care = CareState(member = Member("synthetic-member", Role.owner), plants = listOf(plantOn("plant-a", "Synthetic fern", "site-one"), plantOn("plant-b", "Synthetic aloe", "site-one")))
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SitesScreen(state.value, online.value, care = care, onSave = { saved = it; false }, onDelete = {
                state.value = state.value.copy(errors = mapOf("_form" to "Move or remove the 1 plant on this site first.")); false
            })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/$name-${if (dark) "dark" else "light"}.png"
            if (name == "site-delete-refused") compose.captureDialog(file) else compose.onRoot().captureRoboImage(file)
        }
        compose.onNodeWithText("Indoor · 2 plants · Conditions not set").assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        val touch = compose.onNodeWithContentDescription("Create site").fetchSemanticsNode().touchBoundsInRoot
        assertTrue("Create site is a 48 dp target", touch.height >= with(compose.density) { 48.dp.toPx() } - 0.5f)
        capture("sites")
        // A plant can arrive meanwhile: the server's refusal shows inside the dialog, which stays (W5b-P7).
        openSite("Synthetic shelf")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete site").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Move or remove the 1 plant on this site first.").assertIsDisplayed()
        compose.onNodeWithText("Delete site?").assertIsDisplayed()
        capture("site-delete-refused")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { state.value = state.value.copy(errors = emptyMap()) }
        compose.onNodeWithContentDescription("Back").performClick()
        openSite()
        capture("site-page")
        compose.onNodeWithContentDescription("Edit Synthetic room").performClick()
        compose.onNodeWithText("Name").assertTextContains("Synthetic room")
        capture("site-edit")
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOn()
        compose.onNodeWithText("Cold minimum °C").performScrollTo().assertTextContains("")
        compose.onNodeWithText("Draft from a door, window or vent").performScrollTo().assertIsOff()
        capture("site-conditions")
        compose.onNodeWithText("Warm minimum °C").performScrollTo().performTextReplacement("26")
        compose.onNodeWithText("Save site").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("site-actions")
        compose.onNodeWithText("Save site").performClick()
        compose.runOnIdle {
            assertEquals("26", saved!!.fields["warm_min_c"])
            state.value = state.value.copy(errors = mapOf("warm_min_c" to "Minimum must not exceed maximum."))
        }
        compose.onNodeWithText("Warm minimum °C").performScrollTo()
        compose.onNodeWithText("Minimum must not exceed maximum.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.assertDiscard("Your changes to Synthetic room are not saved yet.")
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { state.value = state.value.copy(errors = emptyMap()) }
        compose.onNodeWithContentDescription("Create site").performClick()
        compose.onNode(hasText("Location") and hasText("Indoor")).assertIsDisplayed()
        compose.onNode(hasText("Light") and hasText("Part sun")).assertIsDisplayed()
        // The temperatures wait folded until asked for (W5b-P5).
        compose.onNodeWithText("Warm minimum °C").assertDoesNotExist()
        compose.onNodeWithText("Temperatures (optional)").performScrollTo().performClick()
        compose.onNodeWithText("Warm minimum °C").performScrollTo().assertIsDisplayed()
        capture("site-create")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Save site").performScrollTo().assert(hasStateDescription(sitesOffline))
        val offline = hasText("Offline", substring = true) and hasClickAction()
        compose.onNode(offline).assertIsDisplayed()
        capture("site-offline")
        compose.onNode(offline).performClick()
        compose.onNodeWithText("This phone can't reach Fytorium. You can still see the saved sites. Changing sites needs a connection.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }
    // C1b: a reload that fails after a save shows in the top bar's line on the list.
    @Test fun aFailedReloadAfterSavingStaysVisibleOnTheList() {
        val state = mutableStateOf(SiteState(loaded = true))
        compose.setContent { FytoriumTheme { SitesScreen(state.value,
            onClear = { state.value = state.value.copy(errors = emptyMap()) }, onSave = {
                state.value = state.value.copy(blocked = true, readError = "Could not reach the server. Try again."); true
            }) } }
        compose.onNodeWithContentDescription("Create site").performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.explains("Sites didn't load", "Could not reach the server. Try again.")
        compose.onNodeWithContentDescription("Create site").assertIsNotEnabled()
        compose.onNode(hasText("Sites didn't load") and hasClickAction()).performClick()
        compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).assertIsEnabled()
    }
    // W5b-P26: after a refused change the line offers Try again, which reloads, and the form keeps what was typed.
    @Test fun tryAgainAfterAFailedWriteKeepsTheUnsavedForm() {
        val state = mutableStateOf(SiteState(loaded = true))
        compose.setContent { FytoriumTheme { SitesScreen(state.value, onSave = {
            state.value = state.value.copy(blocked = true, errors = mapOf("_form" to "Could not reach the server. Try again.")); false
        }, onRefresh = { state.value = state.value.copy(blocked = false, errors = emptyMap()) }) } }
        compose.onNodeWithContentDescription("Create site").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic unsaved room")
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.onNodeWithText("Save site").assertIsNotEnabled()
        compose.onNode(hasText("Sites can't be changed right now") and hasClickAction()).performClick()
        compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Name").assertTextContains("Synthetic unsaved room")
        compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
    }
    @Test fun careRefreshDoesNotDisableAnOnlineSiteForm() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[]"))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner)))
            compose.setContent { FytoriumTheme { SitesRoute(api, care.value, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Create site").performClick()
            compose.runOnIdle { care.value = care.value.copy(busy = true) }
            compose.onNodeWithText("Name").assertIsEnabled().performTextReplacement("Synthetic online room")
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
            compose.onNode(hasText("Offline", substring = true)).assertDoesNotExist()
        }
    }
    @Test fun successfulSitesRefreshEnablesTheFormDespiteCareServerUnavailable() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[]"))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = CareState(member = Member("synthetic-member", Role.owner), serverUnavailable = true, offline = true)
            compose.setContent { FytoriumTheme { SitesRoute(api, care, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Sites didn't load") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Create site").assertIsNotEnabled()
            compose.onNode(hasText("Sites didn't load") and hasClickAction()).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sites didn't load").assertDoesNotExist()
            compose.onNodeWithContentDescription("Create site").performClick()
            compose.onNodeWithText("Name").assertIsEnabled().performTextReplacement("Synthetic recovered room")
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
            assertEquals(2, server.requestCount)
        }
    }
    // C19: in Plants, leaving a site form with changes through the top bar's Back, Cancel or the navigation bar asks first.
    @Test fun leavingASiteFormWithChangesAsksFirst() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(if (request.path == "/api/sites")
                    """[{"id":"site-one","name":"Synthetic room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":2}]""" else "{}")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("synthetic-member", Role.owner)), sitesApi = api, sitesConnected = { true }) } }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Sites").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic room").fetchSemanticsNodes().isNotEmpty() }
            openSite()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit Synthetic room") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit Synthetic room").performClick()
            compose.onNodeWithText("Name").performTextReplacement("Synthetic den")
            val question = "Your changes to Synthetic room are not saved yet."
            for (leave in listOf<() -> Unit>({ compose.onNodeWithContentDescription("Back").performClick() }, { compose.onNodeWithText("Cancel").performScrollTo().performClick() },
                { compose.onNode(hasText("Today") and hasClickAction()).performClick() })) {
                leave()
                compose.onNodeWithText("Discard changes?").assertIsDisplayed()
                compose.onNodeWithText(question).assertIsDisplayed()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithText("Name").assertTextContains("Synthetic den")
            }
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Save site").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        }
    }
    // W5b-P1, P2: Sites sit in the Plants overflow for the owner and family; a sitter has no overflow.
    @Test fun onlyOwnerAndFamilySeeSitesUnderPlants() {
        val api = ApiClient("https://example.invalid", MemoryCredentials()) {}
        val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value, sitesApi = api) } }
        compose.onNode(isTab("More")).performClick()
        compose.onNodeWithText("Sites").assertDoesNotExist()
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        for (role in listOf(Role.owner, Role.family)) {
            compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", role)) }
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Sites").assertIsDisplayed()
            compose.onNodeWithText("Graveyard").assertIsDisplayed()
            assertTrue("Sites comes before Graveyard", compose.onNodeWithText("Sites").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("Graveyard").fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithText("Sites").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Search plants").assertIsDisplayed()
        }
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
    }
}
