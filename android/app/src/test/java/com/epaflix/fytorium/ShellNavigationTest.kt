package com.epaflix.fytorium

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.epaflix.fytorium.feed.FeedRepository
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// W6: one back stack per tab; links open over the current tab; a tab tap keeps every page and draft.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ShellNavigationTest {
    @get:Rule val compose = createComposeRule()

    private class Shell(val restoration: StateRestorationTester, val homeRequest: MutableIntState, val back: () -> OnBackPressedDispatcher,
                        val release: () -> Unit, val requests: List<String>)

    private fun shell(role: Role = Role.owner, noPlants: Boolean = false, responses: Map<String, String> = emptyMap(),
                      hold: (RecordedRequest) -> Boolean = { false }, registry: androidx.compose.runtime.saveable.SaveableStateRegistry? = null, test: Shell.() -> Unit) {
        val held = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    if (hold(request)) held.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return answer(request)
                }
                fun answer(request: RecordedRequest) = MockResponse().setBody(responses["${request.method} ${request.path}"] ?: responses[request.path] ?: when (request.path) {
                    "/api/journal" -> "[" + JSONObject(com.epaflix.fytorium.photos.photoFixture).put("plant_name", "Synthetic fern") + "]"
                    "/api/import/planta" -> "[]"
                    "/api/choices" -> """{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":["good"]},"nameLanguages":{}}"""
                    "/api/sites" -> "[]"
                    "/api/meta" -> """{"today":"2030-06-01","timezone":"Europe/Oslo"}"""
                    "/api/feed" -> """{"settings":{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true},"cards":[{"id":"plant-one","kind":"plant_warnings","href":"/plants/plant-one","plantName":"Synthetic fern","warnings":[{"kind":"no_drainage","reason":"Synthetic reason.","inputs":[]}]}]}"""
                    "/api/weather" -> """{"locationSet":false,"forecastFetchedAt":null,"rain":null,"lastRainBatch":null}"""
                    else -> "[]"
                })
            }
            server.start()
            val client = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val fern = parsePlant(JSONObject(plantFixture))
            val template = parseTaskList(taskListFixture("today"))
            val task = template.sections.single().tasks.single()
            val plants = if (noPlants) emptyList() else listOf(fern) + (1..17).map { fern.copy(id = "synthetic-$it", displayName = "Synthetic plant $it") }
            val list = template.copy(sections = if (noPlants) emptyList() else listOf(TaskSection("water", "Water", plants.map { task.copy(plantId = it.id, plantName = it.displayName) })))
            val care = CareState(member = Member("owner", role), plants = plants, today = list, upcoming = list.copy(view = "upcoming"),
                detail = PlantDetail(fern, emptyList(), emptyMap()))
            val feed = FeedRepository(client, { true }).apply { refresh(care.member) }
            val homeRequest = mutableIntStateOf(0)
            var dispatcher: OnBackPressedDispatcher? = null
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val shell = @Composable { FytoriumTheme { AppShell(true, false, careState = care, feed = feed, sitesApi = client, sitesConnected = { true }, homeRequest = homeRequest.intValue) } }
                if (registry == null) shell() else CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides registry) { shell() }
            }
            try { Shell(restoration, homeRequest, { checkNotNull(dispatcher) }, held::countDown, requests).test() } finally { held.countDown() }
        }
    }

    private fun tab(title: String) = compose.onNode(isTab(title))
    private fun searchField() = compose.onNode(hasText("Search plants") and hasSetTextAction())
    private fun todayScroll() = compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun openSiteDraft() {
        tab("Plants").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sites").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No sites yet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Create site").performClick()
        compose.onNodeWithText("Name").performTextInput("Synthetic draft room")
    }

    // W6-T1: a tab trip keeps the plant page, and Back finds the grid's query.
    @Test fun aTabTripKeepsThePlantPageAndTheGridQuery() = shell {
        tab("Plants").performClick()
        searchField().performTextInput("fern")
        compose.onNodeWithTag("plant:plant-one").performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        tab("Today").performClick()
        tab("Plants").performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        searchField().assertTextContains("fern")
    }

    // W6-T2: a tab tap never asks, and the draft waits in its tab across a restore.
    @Test fun aPlantsDraftSurvivesATabTripAndARestore() = shell {
        openSiteDraft()
        tab("More").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        tab("Plants").performClick()
        compose.onNodeWithText("Synthetic draft room").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
    }

    // W6-T4: a feed card's plant opens over Today, and Back returns to Today.
    @Test fun aFeedPlantOpensOverTodayAndBackReturnsThere() = shell {
        compose.onNodeWithTag("feed-plant-one-no_drainage").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        tab("Today").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.onNodeWithTag("feed-plant-one-no_drainage").assertExists()
    }

    // W6-T5: a Journal plant link opens over the Journal, and Back returns there.
    @Test fun aJournalPlantOpensOverTheJournalAndBackReturnsThere() = shell {
        tab("Journal").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic fern") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic fern").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        tab("Journal").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Journal") and isHeading()).assertIsDisplayed()
    }

    // W6-T6: Today's search opens Find a plant over Today; its query survives a plant; Back walks to Today.
    @Test fun todaySearchOpensFindAPlantOverToday() = shell {
        compose.onNodeWithContentDescription("Search plants").performClick()
        compose.onNode(hasText("Find a plant") and isHeading()).assertIsDisplayed()
        tab("Today").assertIsSelected()
        searchField().assertIsFocused()
        searchField().performTextInput("fern")
        compose.onNodeWithTag("plant:plant-one").performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        tab("Today").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        searchField().assertTextContains("fern")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search plants").performClick()
        searchField().assertTextEquals("Search plants", "")
    }

    // W6-T7: Today's + opens Add plant over Today; Back returns to Today.
    @Test fun todayAddOpensOverToday() = shell {
        compose.onNodeWithContentDescription("Add plant").performClick()
        compose.onNode(hasText("Add plant") and isHeading()).assertIsDisplayed()
        tab("Today").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        tab("Today").assertIsSelected()
    }

    // W6-T8: the empty Today's Import from Planta opens over Today; Back returns to Today.
    @Test fun emptyTodayImportOpensOverToday() = shell(noPlants = true) {
        compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
        compose.onNodeWithText("One-time app code").assertExists()
        tab("Today").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Import from Planta").assertExists()
        tab("Today").assertIsSelected()
        compose.onNodeWithText("Log out").assertDoesNotExist()
    }

    // W6-T10 (a, b): reselecting Plants goes back to the grid with its query, then scrolls it to the top.
    @Test fun reselectingPlantsReturnsToTheGridWithItsQuery() = shell {
        tab("Plants").performClick()
        searchField().performTextInput("Synthetic")
        compose.onNodeWithTag("plant:plant-one").performClick()
        tab("Plants").performClick()
        searchField().assertTextContains("Synthetic")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("plant:synthetic-17"))
        compose.onNodeWithTag("plant:plant-one").assertDoesNotExist()
        tab("Plants").performClick()
        compose.onNodeWithTag("plant:plant-one").assertIsDisplayed()
    }

    // W6-T10 (c): reselecting Today at its root scrolls to the top and keeps the view.
    @Test fun reselectingTodayScrollsToTheTop() = shell {
        compose.onNodeWithTag("view-upcoming").performClick()
        compose.onNodeWithContentDescription("Open Synthetic plant 17").performScrollTo()
        assertTrue(todayScroll() > 0f)
        tab("Today").performClick()
        compose.waitUntil(5_000) { todayScroll() == 0f }
        compose.onNodeWithTag("view-upcoming").assertIsSelected()
    }

    // W6-T10 (d): reselecting Plants over a draft asks first; Keep editing keeps it, Discard shows the grid.
    @Test fun reselectingPlantsOverADraftAsksFirst() = shell {
        openSiteDraft()
        tab("Plants").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Synthetic draft room").assertExists()
        tab("Plants").performClick()
        compose.onNodeWithText("Discard").performClick()
        searchField().assertIsDisplayed()
        compose.onNodeWithText("Synthetic draft room").assertDoesNotExist()
    }

    // W6-T10 (e): reselecting More from one of its pages shows the More list.
    @Test fun reselectingMoreClosesItsPage() = shell {
        tab("More").performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.onNodeWithText("Copy diagnostics").assertIsDisplayed()
        tab("More").performClick()
        compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy diagnostics").assertDoesNotExist()
    }

    // W6-T11: Back at the More, Journal and Plants roots goes to Today as left; at Today's root it leaves the app.
    @Test fun backAtATabRootGoesToToday() = shell {
        for (title in listOf("More", "Journal", "Plants")) {
            tab(title).performClick()
            compose.runOnIdle { back().onBackPressed() }
            tab("Today").assertIsSelected()
            compose.onNodeWithTag("task-list").assertIsDisplayed()
        }
        compose.runOnIdle { assertFalse(back().hasEnabledCallbacks()) }
    }

    // W6-T12 (a): a notification shows Today and leaves another tab's draft alone.
    @Test fun aNotificationLeavesAnotherTabsDraftAlone() = shell {
        openSiteDraft()
        compose.runOnIdle { homeRequest.intValue++ }
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        tab("Plants").performClick()
        compose.onNodeWithText("Synthetic draft room").assertExists()
    }

    // W6-T13 (part): every tab's page survives a restore.
    @Test fun everyTabsPageSurvivesARestore() = shell {
        compose.onNodeWithTag("feed-plant-one-no_drainage").performScrollTo().performClick()
        tab("Plants").performClick()
        searchField().performTextInput("fern")
        tab("Journal").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Add a photo update").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Add a photo update").performClick()
        compose.onNodeWithTag("plant:plant-one").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Note") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Note").performTextInput("Synthetic journal note")
        tab("More").performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Copy diagnostics").assertIsDisplayed()
        tab("Journal").performClick()
        compose.onNodeWithText("Synthetic journal note").assertExists()
        tab("Plants").performClick()
        searchField().assertTextContains("fern")
        tab("Today").performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
    }

    // W6-T13 (size): with a page open in every tab, the saved state stays far below Android's 1 MB transaction limit.
    @Test fun savedStateWithEveryTabOpenStaysSmall() {
        val registry = androidx.compose.runtime.saveable.SaveableStateRegistry(null) { true }
        shell(registry = registry) {
            compose.onNodeWithTag("feed-plant-one-no_drainage").performScrollTo().performClick()
            tab("Plants").performClick()
            searchField().performTextInput("fern")
            compose.onNodeWithTag("plant:plant-one").performClick()
            tab("Journal").performClick()
            tab("More").performClick()
            compose.onNodeWithText("About").performScrollTo().performClick()
            val size = compose.runOnIdle { android.os.Parcel.obtain().run { writeMap(registry.performSave()); dataSize().also { recycle() } } }
            println("W6-T13 saved state: $size bytes")
            assertTrue("saved state is $size bytes", size < 200_000)
        }
    }

    // W6-T15: hiding a tab keeps an add flow's Identify photos; only closing the page clears them.
    @Test fun hidingATabKeepsTheAddFlowsPhotos() = shell {
        val captures = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().cacheDir, "captures/identify").apply { mkdirs() }
        val photo = java.io.File(captures, "synthetic.jpg").apply { writeText("synthetic") }
        compose.onNodeWithContentDescription("Add plant").performClick()
        compose.onNode(hasText("Add plant") and isHeading()).assertIsDisplayed()
        tab("Plants").performClick()
        tab("Today").performClick()
        compose.waitForIdle()
        assertTrue(photo.exists())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertFalse(photo.exists())
    }

    // W6-T9: Planta's plant and Sites links open over the review; View imported plants shows the Plants grid from the top with no search.
    @Test fun plantaLinksOpenOverTheReviewAndViewImportedPlantsShowsTheGrid() = shell(responses = mapOf(
        "/api/import/planta" to "[" + JSONObject(com.epaflix.fytorium.planta.importFixture).put("status", "committed").put("can_review", false)
            .put("plant_id", "plant-one").put("candidates", org.json.JSONArray()) + "]",
        "/api/sites" to "[${com.epaflix.fytorium.sites.siteFixture}]")) {
        tab("Plants").performClick()
        searchField().performTextInput("fern")
        tab("More").performClick()
        compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Check sites").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("import-heading:staging-one", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Open imported plant").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed()
        tab("More").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Check sites").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check sites").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic room").fetchSemanticsNodes().isNotEmpty() }
        tab("More").assertIsSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("View imported plants").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View imported plants").performScrollTo().performClick()
        tab("Plants").assertIsSelected()
        searchField().assertTextEquals("Search plants", "")
        tab("More").performClick()
        compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
    }

    // W6-T16: a tab tap during a save waits for it to end, so the page never comes back with its pre-save draft.
    @Test fun aTabTapWaitsForASaveInFlight() = shell(responses = mapOf("POST /api/sites" to com.epaflix.fytorium.sites.siteFixture.replace("Synthetic room", "Synthetic draft room")),
        hold = { it.method == "POST" && it.path == "/api/sites" }) {
        openSiteDraft()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.contains("POST /api/sites") }
        tab("Journal").performClick()
        tab("Plants").assertIsSelected()
        compose.onNodeWithText("Synthetic draft room").assertExists()
        release()
        compose.waitUntil(5_000) { compose.onAllNodes(isTab("Journal") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        tab("Plants").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        assertEquals(1, requests.count { it == "POST /api/sites" })
    }

    // Sol W6-S5: View imported plants asks about a form left in Plants from the review; Keep editing stays on the review.
    @Test fun viewImportedPlantsAsksAboutAPlantsDraftFromTheReview() = shell(responses = mapOf(
        "/api/import/planta" to "[" + JSONObject(com.epaflix.fytorium.planta.importFixture).put("status", "committed").put("can_review", false)
            .put("plant_id", "plant-one").put("candidates", org.json.JSONArray()) + "]")) {
        openSiteDraft()
        tab("More").performClick()
        compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("View imported plants").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View imported plants").performScrollTo().performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        tab("More").assertIsSelected()
        compose.onNodeWithText("View imported plants").assertExists()
        compose.onNodeWithText("View imported plants").performScrollTo().performClick()
        compose.onNodeWithText("Discard").performClick()
        tab("Plants").assertIsSelected()
        searchField().assertIsDisplayed()
        compose.onNodeWithText("Synthetic draft room").assertDoesNotExist()
        tab("More").performClick()
        compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
    }

    // Sol W6-S2: reselecting the tab during a save waits too, and never asks about the form being saved.
    @Test fun aReselectDuringASaveWaitsAndDoesNotAsk() = shell(responses = mapOf("POST /api/sites" to com.epaflix.fytorium.sites.siteFixture.replace("Synthetic room", "Synthetic draft room")),
        hold = { it.method == "POST" && it.path == "/api/sites" }) {
        openSiteDraft()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.contains("POST /api/sites") }
        tab("Journal").performClick()
        tab("Plants").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        release()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search plants") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        tab("Plants").assertIsSelected()
        assertEquals(1, requests.count { it == "POST /api/sites" })
    }

    // Sol W6-S11: a gallery write such as Use as cover reports itself, so a tab tap waits for it too.
    @Test fun aGalleryWriteReportsItsSave() {
        val pages = mutableStateMapOf<Any, Boolean>()
        compose.setContent { CompositionLocalProvider(LocalPageBusy provides pages) { FytoriumTheme {
            com.epaflix.fytorium.photos.PhotoScreen(com.epaflix.fytorium.photos.PhotoState(loaded = true, saving = true), true, "Synthetic fern", 0) } } }
        compose.runOnIdle { assertTrue(pages.isNotEmpty()) }
    }

    // W6-T18: the app opts in to predictive back.
    @Test fun theAppOptsInToPredictiveBack() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))
    }
}
