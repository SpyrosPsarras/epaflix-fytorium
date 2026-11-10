package com.epaflix.fytorium

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.epaflix.fytorium.feed.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class TodayLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val plant = parsePlant(JSONObject(plantFixture))
    private val template = parseTaskList(taskListFixture("today"))
    private val row = template.sections.single().tasks.single()
    private fun rows(count: Int) = (0 until count).map { row.copy(plantId = "plant-$it", plantName = "Fern $it") }
    private fun state(settings: TaskSettings = TaskSettings(), role: Role = Role.owner, tasks: List<CareTask> = listOf(row)): CareState {
        val list = template.copy(settings = settings, sections = listOf(TaskSection("water", "Water", tasks)))
        return CareState(member = Member("member-one", role), today = list, upcoming = list.copy(view = "upcoming"), plants = listOf(plant),
            lastUpdated = Instant.parse("2030-06-02T08:12:00Z").toEpochMilli())
    }
    @Composable private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) {
        FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
    }
    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
    private fun feed(server: MockWebServer, member: Member): FeedRepository {
        server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
        return FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
            tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, { true }).apply { refresh(member) }
    }
    private val feedCard = SemanticsMatcher("feed card") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> tag.startsWith("feed-") && tag != "feed-strip" } == true
    }

    @Test fun topBarHoldsSearchTitleTaskViewAndAddPlantWithNoBell() {
        val view = mutableStateOf("today")
        compose.setContent { Themed { TaskScreen(state(), view.value, onView = { view.value = it }) } }
        compose.onNodeWithContentDescription("My notifications").assertDoesNotExist()
        val toggle = bounds(hasTestTag("view-today"))
        for (label in listOf("Search plants", "Task view", "Add plant")) {
            val button = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must sit in the top bar", button.bottom <= toggle.top)
        }
        assertTrue("Add plant sits right of the task view icon", bounds(hasContentDescription("Add plant")).left >= bounds(hasContentDescription("Task view")).right)
        compose.onAllNodes(hasClickAction() and hasContentDescription("Add plant")).assertCountEquals(1)
        compose.onAllNodes(hasClickAction() and hasContentDescription("Task view settings")).assertCountEquals(0)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (selected in listOf("today", "upcoming")) {
            compose.runOnIdle { view.value = selected }
            val title = compose.onNode(hasText("Today") and !hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(root.center.x, title.center.x, 1f)
            assertTrue(title.bottom <= toggle.top)
        }
    }

    @Test fun selectedViewIsAFilledPillWithoutACheck() {
        val view = mutableStateOf("today")
        var host: android.view.View? = null
        compose.setContent { host = LocalView.current; Themed { TaskScreen(state(), view.value, onView = { view.value = it }) } }
        fun fill(tag: String): Color {
            val pill = compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            var color = Color.Unspecified
            compose.runOnUiThread {
                val bitmap = android.graphics.Bitmap.createBitmap(host!!.width, host!!.height, android.graphics.Bitmap.Config.ARGB_8888)
                host!!.draw(android.graphics.Canvas(bitmap))
                color = Color(bitmap.getPixel((pill.left + pill.width / 10).toInt(), pill.center.y.toInt()))
                bitmap.recycle()
            }
            return color
        }
        for ((selected, other) in listOf("today" to "upcoming", "upcoming" to "today")) {
            compose.onNodeWithTag("view-$selected").performClick().assertIsSelected()
            compose.onNodeWithTag("view-$other").assertIsNotSelected()
            assertEquals(LightColors.primary, fill("view-$selected"))
            assertNotEquals(LightColors.primary, fill("view-$other"))
        }
    }

    @Test fun anActiveViewIsStatedUnderTheToggleWithOneReset() {
        val current = mutableStateOf(state(TaskSettings("plant", "indoor", true)))
        val saved = mutableListOf<TaskSettings>()
        compose.setContent { Themed { TaskScreen(current.value, "today", onSettings = { saved.add(it); true }) } }
        val toggle = bounds(hasTestTag("view-today"))
        for (label in listOf("Indoor", "Sorted by plant", "Showing others' completed")) {
            assertTrue("$label sits under the toggle", compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top >= toggle.bottom)
        }
        compose.onAllNodesWithText("Reset").assertCountEquals(1)
        compose.onNodeWithText("Reset").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(TaskSettings()), saved) }
        compose.runOnIdle { current.value = state(TaskSettings("site", "outdoor", false)) }
        compose.onNodeWithText("Outdoor").assertIsDisplayed()
        compose.onNodeWithText("Sorted by site").assertIsDisplayed()
        compose.onNodeWithText("Showing others' completed").assertDoesNotExist()
        compose.runOnIdle { current.value = state() }
        compose.onNodeWithText("Reset").assertDoesNotExist()
        compose.onNodeWithText("Sorted by task", substring = true).assertDoesNotExist()
    }

    @Test fun feedStripIsCompactAndTheToggleSitsUnderItWithStatusBarInsetsLight() = strip(false)
    @Test fun feedStripIsCompactAndTheToggleSitsUnderItWithStatusBarInsetsDark() = strip(true)
    private fun strip(dark: Boolean) {
        compose.enableAccessibilityChecks()
        MockWebServer().use { server ->
            server.start()
            val member = Member("member-one", Role.owner)
            val feed = feed(server, member)
            var view: android.view.View? = null
            compose.setContent {
                view = LocalView.current
                FytoriumTheme(dark) { AppShell(true, false, careState = state(), feed = feed, deviceOnline = true) }
            }
            val inset = 40
            compose.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(view!!, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, inset, 0, 0)).build())
            }
            compose.waitForIdle()
            val search = bounds(hasContentDescription("Search plants"))
            assertTrue("The status bar inset must apply once above the top bar, search at ${search.top}", search.top >= inset && search.top - inset <= 16f)
            // Cards scrolled out of the strip are clipped, so measure their unclipped layout.
            val cards = compose.onAllNodes(feedCard).fetchSemanticsNodes().map { Rect(it.positionInRoot, it.size.toSize()) }
            assertTrue(cards.size >= 4)
            val tallest = cards.maxOf { it.height }
            for (card in cards) {
                assertEquals("Every card is as tall as the tallest, so none leaves a band under it: ${cards.map { it.height }}", tallest, card.height, 1f)
                assertTrue("Cards are compact, not ${card.height}", card.height <= 112f)
            }
            assertTrue("Strip sits directly under the top bar", cards.minOf { it.top } - search.bottom in 0f..16f)
            val toggle = bounds(hasTestTag("view-today"))
            assertTrue("No band between the strip and the toggle: ${toggle.top - cards.maxOf { it.bottom }}", toggle.top - cards.maxOf { it.bottom } in 0f..20.5f)
            val strip = bounds(hasTestTag("feed-strip"))
            assertEquals("The strip is one card tall", tallest, strip.height, 1f)
            compose.onNodeWithTag("feed-strip").assert(hasScrollAction())
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/today-feed-${if (dark) "dark" else "light"}.png")
        }
    }

    @Test fun theReceiptSitsAboveTheNavigationBarAndTheListGrowsByItsHeight() {
        val receipt = Receipt("Watered Fern 7", parseEvent(JSONObject(eventFixture)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(tasks = rows(8)).copy(receipt = receipt)) } }
        compose.onNodeWithText("Watered Fern 7").assertIsDisplayed()
        compose.onNodeWithTag("task-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val navigation = bounds(hasText("Today") and hasClickAction() and !hasTestTag("view-today"))
        val snackbar = bounds(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val last = bounds(hasTestTag("task:plant-7:water"))
        assertTrue("The receipt sits directly above the navigation bar: ${snackbar.bottom} vs ${navigation.top}",
            snackbar.bottom <= navigation.top + 1f && navigation.top - snackbar.bottom <= 8f)
        assertEquals("The receipt spans the width minus its margins", root.width, snackbar.width, 1f)
        assertTrue("The last row clears the receipt: ${last.bottom} > ${snackbar.top}", last.bottom <= snackbar.top)
        val handleAll = bounds(hasContentDescription("Handle all Water tasks"))
        assertTrue("The card's last control clears the receipt: ${handleAll.bottom} > ${snackbar.top}", handleAll.bottom <= snackbar.top)
        assertTrue("The list grows by the receipt only: ${snackbar.top - handleAll.bottom}", snackbar.top - handleAll.bottom <= 40f)
        compose.onNodeWithContentDescription("Mark Water done for Fern 7, Synthetic site").assertIsDisplayed()
    }

    @Test fun aReceiptAppearingMidListCoversNoRowControlInToday() = receiptMidList("today")
    @Test fun aReceiptAppearingMidListCoversNoRowControlInUpcoming() = receiptMidList("upcoming")
    private fun receiptMidList(view: String) {
        val care = mutableStateOf(state(tasks = rows(12)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithTag("view-$view").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("task-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        compose.runOnIdle { care.value = care.value.copy(receipt = Receipt("Watered Fern 3", parseEvent(JSONObject(eventFixture)))) }
        compose.onNodeWithText("Watered Fern 3").assertIsDisplayed()
        val range = compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The list is scrolled mid-way: ${range.value()} of ${range.maxValue()}", range.value() in 1f..(range.maxValue() - 200f))
        val snackbar = bounds(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        val controls = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("task-list"))).fetchSemanticsNodes()
            .map { it.boundsInRoot }.filter { it.height > 0f }
        assertTrue(controls.any { it.bottom > snackbar.top - 120f })
        assertEquals("Row controls under the receipt", emptyList<Rect>(), controls.filter { it.overlaps(snackbar) })
    }

    @Test fun addPlantExplainsItselfWhenTasksAreOfflineServerlessOrOutdated() {
        val current = mutableStateOf(state())
        var adds = 0
        compose.setContent { Themed { TaskScreen(current.value, "today", onAddPlant = { adds++ }, deviceOnline = true) } }
        for ((blocked, message) in listOf(state().copy(offline = true) to "Adding a plant needs a connection.",
            state().copy(serverUnavailable = true) to "Adding a plant needs Fytorium to respond. Pull down to try again.",
            state().copy(updateRequired = true) to "Update Fytorium to add plants.")) {
            compose.runOnIdle { current.value = blocked }
            compose.onNodeWithContentDescription("Add plant").assertIsNotEnabled().performClick()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(10_000)
            compose.onNodeWithText(message).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, adds); current.value = state() }
        compose.onNodeWithContentDescription("Add plant").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, adds) }
    }

    @Test fun anEmptyCollectionsAddAndImportExplainThemselvesWhenTasksAreOfflineOrServerless() {
        val empty = state().let { it.copy(plants = emptyList(), today = it.today!!.copy(sections = emptyList()), upcoming = it.upcoming!!.copy(sections = emptyList())) }
        val current = mutableStateOf(empty)
        var adds = 0
        var imports = 0
        compose.setContent { Themed { TaskScreen(current.value, "today", onAddPlant = { adds++ }, onImport = { imports++ }, deviceOnline = true) } }
        for ((blocked, message) in listOf(empty.copy(offline = true) to "Adding a plant needs a connection.",
            empty.copy(serverUnavailable = true) to "Adding a plant needs Fytorium to respond. Pull down to try again.")) {
            compose.runOnIdle { current.value = blocked }
            for (button in listOf("Add plant", "Import from Planta")) {
                compose.onNodeWithText(button).assertIsNotEnabled().assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithText(message).assertIsDisplayed()
                compose.mainClock.advanceTimeBy(10_000)
                compose.onNodeWithText(message).assertDoesNotExist()
            }
        }
        compose.runOnIdle { assertEquals(0, adds); assertEquals(0, imports); current.value = empty }
        compose.onNodeWithText("Add plant").assertIsEnabled().performClick()
        compose.onNodeWithText("Import from Planta").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(1, imports) }
    }

    @Test fun aBackgroundRefreshShowsNoSpinnerButAPullDoes() {
        val refreshing = mutableStateOf(false)
        compose.setContent { Themed { TaskScreen(state().copy(busy = true), "today", refreshing = refreshing.value) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle { refreshing.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
    }

    @Test fun todayAndUpcomingKeepTheirOwnScrollPositions() {
        val plants = (0 until 18).map { plant.copy(id = "plant-$it", displayName = "Fern $it") }
        val list = template.copy(sections = listOf(TaskSection("water", "Water", rows(18))))
        val care = CareState(member = Member("member-one", Role.owner), today = list, upcoming = list.copy(view = "upcoming"), plants = plants)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care) } }
        fun scroll() = compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("task-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 900f) }
        val today = scroll()
        assertTrue(today > 0f)
        compose.onNodeWithTag("view-upcoming").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("Upcoming starts at its own position", 0f, scroll(), 0f)
        compose.onNodeWithTag("task-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        val upcoming = scroll()
        assertNotEquals(today, upcoming, 1f)
        compose.onNodeWithTag("view-today").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(today, scroll(), 1f)
        compose.onNodeWithTag("view-upcoming").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(upcoming, scroll(), 1f)
    }

    @Test fun taskViewChoicesApplyAtOnceAndAFailureKeepsThePreviousChoiceLight() = taskView(false)
    @Test fun taskViewChoicesApplyAtOnceAndAFailureKeepsThePreviousChoiceDark() = taskView(true)
    private fun taskView(dark: Boolean) {
        val saved = mutableListOf<TaskSettings>()
        val results = ArrayDeque(listOf(true, false))
        compose.setContent { Themed(dark) { TaskScreen(state(), "today", onSettings = { saved.add(it); results.removeFirst() }) } }
        compose.onNodeWithContentDescription("Task view").performClick()
        compose.onNode(hasText("Task view") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Save task view").assertDoesNotExist()
        compose.onNodeWithText("Sort tasks: Task type").performClick()
        compose.onNodeWithText("Plant").performClick()
        compose.runOnIdle { assertEquals(listOf(TaskSettings(sort = "plant")), saved) }
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Sort tasks: Plant").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/screenshots/task-view-sheet-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Filter: All").performClick()
        compose.onNodeWithText("Outdoor").performClick()
        compose.runOnIdle { assertEquals(TaskSettings(sort = "plant", filter = "outdoor"), saved.last()) }
        compose.onNodeWithText("Could not save. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Filter: All").assertIsDisplayed()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithText("Show others' completed tasks").assertIsDisplayed()
    }

    @Test fun aFilteredEmptyDayNamesTheFilterAndOffersShowAll() {
        val saved = mutableListOf<TaskSettings>()
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04"))
        val empty = state(TaskSettings(filter = "indoor")).let { it.copy(today = it.today!!.copy(sections = emptyList()), upcoming = upcoming) }
        val current = mutableStateOf(empty)
        compose.setContent { Themed { TaskScreen(current.value, "today", onSettings = { saved.add(it); true }) } }
        compose.onNodeWithText("No indoor tasks today").assertIsDisplayed()
        compose.onNodeWithText("Nothing to do today").assertDoesNotExist()
        compose.onNode(hasText("Next: Water Synthetic fern", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Show all").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(TaskSettings(filter = "all")), saved) }
        compose.runOnIdle { current.value = empty.copy(today = empty.today!!.copy(settings = TaskSettings(filter = "outdoor"))) }
        compose.onNodeWithText("No outdoor tasks today").assertIsDisplayed()
        compose.runOnIdle { current.value = empty.copy(today = empty.today!!.copy(settings = TaskSettings(sort = "plant"))) }
        compose.onNodeWithText("Nothing to do today").assertIsDisplayed()
        compose.onNodeWithText("Show all").assertDoesNotExist()
    }

    @Test fun anEmptyUpcomingNamesItsFilter() {
        val current = mutableStateOf(state(TaskSettings(filter = "indoor")).let { it.copy(upcoming = it.upcoming!!.copy(sections = emptyList())) })
        compose.setContent { Themed { TaskScreen(current.value, "upcoming") } }
        compose.onNodeWithText("No upcoming indoor tasks").assertIsDisplayed()
        compose.onNodeWithText("Show all").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(upcoming = current.value.upcoming!!.copy(settings = TaskSettings())) }
        compose.onNodeWithText("No upcoming tasks").assertIsDisplayed()
        compose.onNodeWithText("Show all").assertDoesNotExist()
    }

    @Test fun aSitterWithNothingSharedIsToldWhoToAsk() {
        val sitter = state(role = Role.sitter).copy(plants = emptyList())
        compose.setContent { Themed { TaskScreen(sitter.copy(today = sitter.today!!.copy(sections = emptyList())), "today") } }
        compose.onNodeWithText("Nothing is shared with you yet").assertIsDisplayed()
        compose.onNodeWithText("Ask the household owner to share plants.").assertIsDisplayed()
        compose.onNodeWithText("No shared plants yet").assertDoesNotExist()
    }

    @Test fun offlineShowsOneBannerAndTheAddButtonExplainsItself() {
        MockWebServer().use { server ->
            server.start()
            val member = Member("member-one", Role.owner)
            val feed = feed(server, member).apply { connectivityChanged(false) }
            val offline = state().copy(offline = true, lastUpdated = java.time.LocalDate.now().atTime(8, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = offline, feed = feed, deviceOnline = false) } }
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(offline.lastUpdated!!))
            compose.onNodeWithText("Offline. Showing tasks from $time. Changes need a connection.").assertIsDisplayed()
            compose.onNode(hasText("Feed is offline", substring = true)).assertDoesNotExist()
            compose.onNode(hasText("Actions are unavailable offline", substring = true)).assertDoesNotExist()
            compose.onNodeWithContentDescription("Add plant").assertIsNotEnabled().performClick()
            compose.onNodeWithText("Adding a plant needs a connection.").assertIsDisplayed()
            compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).assertIsSelected()
        }
    }

    @Test fun upcomingHeadersUseDayNamesWithoutTheRangeCaption() {
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04"))
        val day = upcoming.sections.single()
        val list = upcoming.copy(sections = listOf("2030-06-02", "2030-06-03", "2030-06-06").map { key -> day.copy(key = key, label = key,
            tasks = day.tasks.map { it.copy(dueOn = key, plantId = "plant-$key") }) })
        compose.setContent { Themed { TaskScreen(state().copy(upcoming = list), "upcoming") } }
        val headings = compose.onAllNodes(isHeading()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Today", "Tomorrow", "Thursday 6 Jun"), headings)
        compose.onNode(hasText("Next 14 calendar days", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("2030-06", substring = true)).assertDoesNotExist()
    }
}
