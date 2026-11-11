package com.epaflix.fytorium

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.epaflix.fytorium.feed.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
    private val event = parseEvent(JSONObject(eventFixture))
    private fun done(task: CareTask) = task.copy(completedEvent = event.copy(id = "event-${task.plantId}-${task.key}", plantId = task.plantId, dueOn = task.dueOn))
    private val taskRow = SemanticsMatcher("task row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("task:") == true }
    private fun rowTops() = compose.onAllNodes(taskRow).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to it.positionInRoot.y }
    private fun scrollList(by: Float) = compose.onNodeWithTag("task-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }

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
        for ((selected, label) in listOf("today" to "Today", "upcoming" to "Upcoming")) {
            compose.runOnIdle { view.value = selected }
            val title = compose.onNode(hasText(label) and !hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
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

    @Test fun theReceiptCoversTheNavigationBarUntilSwipedAndTheListKeepsItsSize() {
        val care = mutableStateOf(state(tasks = rows(8)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithTag("task-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val list = bounds(hasTestTag("task-list"))
        val rows = rowTops()
        compose.runOnIdle { care.value = care.value.copy(receipt = Receipt("Watered Fern 7", event)) }
        compose.onNodeWithText("Watered Fern 7").assertIsDisplayed()
        val navigation = bounds(hasText("Today") and hasClickAction() and !hasTestTag("view-today"))
        val snackbar = bounds(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The receipt sits over the navigation bar: $snackbar vs $navigation", snackbar.overlaps(navigation) && snackbar.bottom <= root.bottom)
        assertTrue("The receipt covers no part of the list: ${snackbar.top} < ${list.bottom}", snackbar.top >= list.bottom - 0.5f)
        assertEquals("The receipt spans the width minus its margins", root.width, snackbar.width, 1f)
        assertEquals("The list keeps its size while a receipt shows", list, bounds(hasTestTag("task-list")))
        assertEquals("A row moved when the receipt appeared", rows, rowTops())
        compose.onNodeWithContentDescription("Handle all Water tasks").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Water done for Fern 7, Synthetic site").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/today-receipt-light.png")
        val plants = bounds(hasText("Plants") and hasClickAction())
        compose.onRoot().performTouchInput { click(Offset(plants.center.x, snackbar.top + 2f)) }
        compose.onNodeWithText("Watered Fern 7").assertIsDisplayed()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.onNodeWithText("Watered Fern 7").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Watered Fern 7").assertDoesNotExist()
        assertEquals("A row moved when the receipt went", rows, rowTops())
    }

    @Test fun theLongestReceiptsAndExplanationsFitTheNavigationBar() = receiptsFitTheBar(1f)
    @Test fun theLongestReceiptsAndExplanationsFitTheNavigationBarAtLargeText() = receiptsFitTheBar(1.3f)
    private fun receiptsFitTheBar(fontScale: Float) {
        val name = "Synthetic variegated Swiss cheese plant from the far corner of the greenhouse ".repeat(3).take(200).trim()
        val custom = "Synthetic custom task named as long as the server allows it".take(60)
        val care = mutableStateOf(state(tasks = rows(2)))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            FytoriumTheme { AppShell(true, false, careState = care.value) } } }
        val navigation = bounds(hasText("Today") and hasClickAction() and !hasTestTag("view-today"))
        for (receipt in listOf(Receipt(doneText("remove_dead", null, name), event), Receipt(doneText(null, custom, name), event),
            Receipt(undoneText(event.copy(taskType = "remove_dead"), name, watered = true), short = true),
            Receipt("Adding a plant needs Fytorium to respond. Pull down to try again.", short = true))) {
            compose.runOnIdle { care.value = care.value.copy(receipt = receipt) }
            compose.onNodeWithContentDescription(receipt.text).assertExists()
            val snackbar = bounds(hasTestTag("receipt"))
            assertTrue("'${receipt.text.take(30)}…' at ${fontScale}x leaves the bar: $snackbar vs $navigation",
                snackbar.top >= navigation.top - 0.5f && snackbar.bottom <= navigation.bottom + 0.5f)
        }
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

    @Test fun aTapOnTheBarJustAfterAReceiptTimesOutKeepsTheTab() = lateTap(swiped = false)
    @Test fun aTapOnTheBarJustAfterAReceiptIsSwipedKeepsTheTab() = lateTap(swiped = true)
    private fun lateTap(swiped: Boolean) {
        val care = mutableStateOf(state(tasks = rows(2)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        val more = bounds(hasText("More") and hasClickAction()).center
        compose.runOnIdle { care.value = care.value.copy(receipt = Receipt("Watered Fern 1", event)) }
        compose.onNodeWithText("Watered Fern 1").assertIsDisplayed()
        if (swiped) compose.onNodeWithText("Watered Fern 1").performTouchInput { swipeRight() } else compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Watered Fern 1").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(more) }
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().performTouchInput { click(more) }
        compose.onNodeWithTag("task-list").assertDoesNotExist()
        compose.onNode(hasText("More") and !hasClickAction()).assertIsDisplayed()
    }

    @Test fun aTapOnTheBarWhileANewExplanationFadesInWaitsHalfASecondAfterTheLastOneLeft() {
        val message = "Changes need a connection."
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(tasks = rows(2)).copy(offline = true)) } }
        val more = bounds(hasText("More") and hasClickAction()).center
        val control = compose.onNodeWithContentDescription("Mark Water done for Fern 0, Synthetic site")
        control.performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(message).performTouchInput { swipeRight() }
        var frames = 0
        while (compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("The explanation stays after $frames frames", ++frames < 30)
        }
        // The explanation left about 80 ms before it vanished; the next one arrives near the end of the pause.
        compose.mainClock.advanceTimeBy(300)
        control.performClick()
        frames = 0
        while (compose.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty()) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("No explanation after $frames frames", ++frames < 30)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onRoot().performTouchInput { click(more) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(32)
        compose.onRoot().performTouchInput { click(more) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("task-list").assertDoesNotExist()
        compose.onNodeWithText(message).assertExists()
    }

    @Test fun aTapOnTheBarJustAfterAnExplanationIsSwipedKeepsTheTabWhileTheQueuedOneFadesIn() = queuedLateTap(swiped = true)
    @Test fun aTapOnTheBarJustAfterAnExplanationTimesOutKeepsTheTabWhileTheQueuedOneFadesIn() = queuedLateTap(swiped = false)
    private fun queuedLateTap(swiped: Boolean) {
        val first = "Changes need a connection."
        val queued = "Adding a plant needs a connection."
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(tasks = rows(2)).copy(offline = true)) } }
        val more = bounds(hasText("More") and hasClickAction()).center
        compose.onNodeWithContentDescription("Mark Water done for Fern 0, Synthetic site").performClick()
        compose.onNodeWithContentDescription("Add plant").performClick()
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        if (swiped) compose.onNodeWithText(first).performTouchInput { swipeRight() }
        else { compose.mainClock.advanceTimeBy(3_000); compose.onNodeWithText(queued).assertDoesNotExist() }
        var frames = 0
        while (compose.onAllNodesWithText(queued).fetchSemanticsNodes().isEmpty()) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("No queued explanation after $frames frames", ++frames < 100)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onRoot().performTouchInput { click(more) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
        compose.onNodeWithText(queued).assertExists()
    }

    @Test fun aTapOnATabWhileAReceiptFadesInSwitchesTheTab() = tapWhileFadingIn(replacing = false)
    @Test fun aTapOnATabWhileAReceiptReplacesAnotherSwitchesTheTab() = tapWhileFadingIn(replacing = true)
    private fun tapWhileFadingIn(replacing: Boolean) {
        val care = mutableStateOf(state(tasks = rows(2)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        val more = bounds(hasText("More") and hasClickAction()).center
        if (replacing) {
            compose.runOnIdle { care.value = care.value.copy(receipt = Receipt("Watered Fern 0", event)) }
            compose.onNodeWithText("Watered Fern 0").assertIsDisplayed()
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { care.value = care.value.copy(receipt = Receipt("Watered Fern 1", event)) }
        var frames = 0
        while (compose.onAllNodesWithText("Watered Fern 1").fetchSemanticsNodes().isEmpty()) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("No receipt after $frames frames", ++frames < 30)
        }
        compose.mainClock.advanceTimeBy(if (replacing) 180 else 32)
        compose.onRoot().performTouchInput { click(more) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("task-list").assertDoesNotExist()
    }

    @Test fun aTapOnTheBarOnceTheReceiptIsFullyShownHitsTheReceipt() {
        val care = mutableStateOf(state(tasks = rows(2)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        val tabs = listOf("Today", "Plants", "Journal", "More").map { bounds(hasText(it) and hasClickAction() and !hasTestTag("view-today")) }
        compose.runOnIdle { care.value = care.value.copy(receipt = Receipt("Watered Fern 1", event)) }
        compose.onNodeWithText("Watered Fern 1").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(100)
        val undo = bounds(hasText("Undo") and hasClickAction())
        val overTab = tabs.map { it.intersect(undo) }.firstOrNull { !it.isEmpty }
        assertNotNull("Undo $undo lies over no tab: $tabs", overTab)
        compose.onRoot().performTouchInput { click(overTab!!.center) }
        compose.onNodeWithText("Watered Fern 1").assertDoesNotExist()
        compose.onNodeWithTag("task-list").assertIsDisplayed()
    }

    @Test fun theBarShowsItsItemsOrTheReceiptInEveryFrame() {
        val care = mutableStateOf(state(tasks = rows(2)))
        var host: android.view.View? = null
        compose.setContent { host = LocalView.current; FytoriumTheme { AppShell(true, false, careState = care.value) } }
        val today = bounds(hasText("Today") and hasClickAction() and !hasTestTag("view-today"))
        val bar = Rect(today.left, today.top, bounds(hasText("More") and hasClickAction()).right, today.bottom)
        fun blank(): Boolean {
            val colors = mutableSetOf<Int>()
            compose.runOnUiThread {
                val bitmap = android.graphics.Bitmap.createBitmap(host!!.width, host!!.height, android.graphics.Bitmap.Config.ARGB_8888)
                host!!.draw(android.graphics.Canvas(bitmap))
                for (x in bar.left.toInt() until bar.right.toInt() step 3) for (y in bar.top.toInt() until bar.bottom.toInt() step 3) colors.add(bitmap.getPixel(x, y))
                bitmap.recycle()
            }
            return colors.size == 1
        }
        fun frames(after: String, count: Int) = repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            assertFalse("The bar shows neither its items nor the receipt ${it + 1} frames after $after", blank())
        }
        assertFalse(blank())
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { care.value = care.value.copy(receipt = Receipt("Watered Fern 1", event)) }
        frames("the receipt arrived", 20)
        compose.onNodeWithText("Watered Fern 1").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(9_500)
        frames("the receipt neared its timeout", 30)
        compose.onNodeWithText("Watered Fern 1").assertDoesNotExist()
        compose.runOnUiThread { care.value = care.value.copy(receipt = Receipt("Watered Fern 0", event)) }
        frames("the next receipt arrived", 20)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        frames("the receipt was dismissed", 20)
        compose.onNodeWithText("Watered Fern 0").assertDoesNotExist()
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
        compose.onNodeWithText("Plant").performClick()
        compose.runOnIdle { assertEquals(listOf(TaskSettings(sort = "plant")), saved) }
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Plant").assertIsSelected()
        compose.mainClock.advanceTimeBy(1_000)
        com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/screenshots/task-view-sheet-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Outdoor").performClick()
        compose.runOnIdle { assertEquals(TaskSettings(sort = "plant", filter = "outdoor"), saved.last()) }
        compose.onNodeWithText("Could not save. Try again.").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsSelected().assertIsEnabled()
        compose.onNodeWithText("Outdoor").assertIsNotSelected()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithText("Show others' completed tasks").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h640dp-mdpi") fun theTaskViewSheetOpensWholeSoItsStatusLineIsInView() {
        compose.setContent { Themed { TaskScreen(state(), "today", onSettings = { true }) } }
        compose.onNodeWithContentDescription("Task view").performClick()
        compose.onNodeWithText("Plant").performClick()
        val saved = compose.onNodeWithText("Saved").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val window = compose.onNode(isRoot() and hasAnyDescendant(hasText("Saved"))).fetchSemanticsNode().boundsInRoot
        assertTrue("Saved sits below the window: ${saved.bottom} > ${window.bottom}", saved.bottom <= window.bottom)
    }

    @Test fun aBlockedTaskViewDimsEveryChoiceAndItsLabelAlikeAndKeepsTheSelection() {
        compose.setContent { Themed(dark = true) { TaskScreen(state(TaskSettings(sort = "plant")).copy(offline = true), "today") } }
        compose.onNodeWithContentDescription("Task view").performClick()
        compose.onNodeWithText("Task view needs a connection.").assertIsDisplayed()
        compose.onNode(hasText("Plant") and hasClickAction()).assertIsSelected().assertIsNotEnabled()
        val colors = listOf("Task type", "Plant", "Site", "All", "Indoor", "Outdoor", "Show others' completed tasks").associateWith { label ->
            compose.onAllNodes(hasText(label) and hasAnyAncestor(hasClickAction()), useUnmergedTree = true).fetchSemanticsNodes().single().textLayout().layoutInput.style.color }
        assertEquals("Disabled choices differ: $colors", 1, colors.values.distinct().size)
        assertTrue("Disabled choices look enabled: $colors", colors.values.first().alpha < 0.5f)
        compose.mainClock.advanceTimeBy(1_000)
        com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/screenshots/task-view-sheet-offline-dark.png")
    }

    @Test fun theTaskViewsBlockedMessageAsksFytoriumAgainAndClearsOnceWritesWork() {
        val current = mutableStateOf(state().copy(serverUnavailable = true))
        var retries = 0
        compose.setContent { Themed { TaskScreen(current.value, "today", onRetry = { retries++ }) } }
        compose.onNodeWithContentDescription("Task view").performClick()
        compose.onNodeWithText("Task view needs Fytorium to respond.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6_000)
        compose.runOnIdle { assertTrue("The sheet never asked Fytorium again", retries > 0) }
        compose.runOnIdle { current.value = state() }
        compose.onNodeWithText("Task view needs Fytorium to respond.").assertDoesNotExist()
        compose.onNode(hasText("Plant") and hasClickAction()).assertIsEnabled()
        val settled = retries
        compose.mainClock.advanceTimeBy(20_000)
        compose.runOnIdle { assertEquals("Retries go on once writes work", settled, retries) }
    }

    @Test fun aRowWithAnEmptySubtitleCentresItsNameOnThePhoto() = nameCentred(1f)
    @Test fun aRowWithAnEmptySubtitleCentresItsNameOnThePhotoAtLargeText() = nameCentred(1.3f)
    private fun nameCentred(fontScale: Float) {
        val names = listOf("Spider Plant", "Variegated Monstera Fern 1")
        val tasks = names.mapIndexed { index, name -> row.copy(plantId = "plant-$index", plantName = name, siteName = "") }
        val care = mutableStateOf(state(role = Role.sitter, tasks = tasks))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed { TaskScreen(care.value, "today") } } }
        val photo = SemanticsMatcher("photo") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("thumbnail:") == true }
        fun offsets() = names.mapIndexed { index, name ->
            val text = compose.onNode(hasText(name) and inRow("plant-$index"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val avatar = compose.onNode(photo and inRow("plant-$index"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            name to text.center.y - avatar.center.y
        }
        assertEquals(2, compose.onNode(hasText(names[1]) and inRow("plant-1"), useUnmergedTree = true).fetchSemanticsNode().textLayout().lineCount)
        val off = offsets().filter { kotlin.math.abs(it.second) > 1f }
        assertEquals("Names off the photo's centre at ${fontScale}x text", emptyList<Pair<String, Float>>(), off)
        val before = rowBounds()
        compose.runOnIdle { care.value = care.value.written(state(role = Role.sitter, tasks = listOf(tasks[0], tasks[1].postponed("snoozed", "2030-06-04")))) }
        compose.onNode(hasContentDescription("Snoozed until Tuesday 4 June") and inRow("plant-1"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("A row changed when its label appeared at ${fontScale}x text", before, rowBounds())
    }

    @Test fun aDisabledRowControlExplainsWhyOnTap() {
        val (pending, completed, snoozed) = rows(3)
        val tasks = listOf(pending, done(completed), snoozed.postponed("snoozed", "2030-06-04"))
        val current = mutableStateOf(state(tasks = tasks))
        val acted = mutableListOf<String>()
        compose.setContent { Themed { TaskScreen(current.value, "today", onAction = { task, action -> acted += "${task.plantName}:$action" }) } }
        for ((blocked, message) in listOf(state(tasks = tasks).copy(offline = true) to "Changes need a connection.",
            state(tasks = tasks).copy(serverUnavailable = true) to "Changes need Fytorium to respond.",
            state(tasks = tasks).copy(updateRequired = true) to "Update Fytorium to make changes.")) {
            compose.runOnIdle { current.value = blocked }
            for (control in listOf("Mark Water done for Fern 0, Synthetic site", "Snooze 2 days", "Water done for Fern 1, Synthetic site",
                "Undo watering snooze for Fern 2, Synthetic site")) {
                compose.onNodeWithContentDescription(control).assertIsNotEnabled()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, message)).performClick()
                compose.onNodeWithText(message).assertIsDisplayed()
                compose.mainClock.advanceTimeBy(10_000)
                compose.onNodeWithText(message).assertDoesNotExist()
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), acted) }
    }

    @Test fun aHeldRowsDisabledControlSaysItChangedElsewhere() {
        val (kept, removed) = rows(2)
        val current = mutableStateOf(state(tasks = listOf(kept, removed)))
        val acted = mutableListOf<String>()
        compose.setContent { Themed { TaskScreen(current.value, "today", onAction = { task, action -> acted += "${task.plantName}:$action" }) } }
        compose.runOnIdle { current.value = state(tasks = listOf(kept)) }
        compose.onNodeWithContentDescription("Mark Water done for Fern 1, Synthetic site").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "This task changed elsewhere. Pull to refresh.")).performClick()
        compose.onNodeWithText("This task changed elsewhere. Pull to refresh.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), acted) }
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
            compose.onNodeWithText("Weather and tips need a connection").assertIsDisplayed()
            assertTrue("The cached cards stay", compose.onAllNodes(feedCard).fetchSemanticsNodes().isNotEmpty())
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

    @Test fun completingTheLastTaskOfTheLastCardMovesNoRow() = lastTaskMovesNoRow(endOfList = true)
    @Test fun completingTheLastTaskOfACardMidListMovesNoRow() = lastTaskMovesNoRow(endOfList = false)
    private fun lastTaskMovesNoRow(endOfList: Boolean) {
        val water = TaskSection("water", "Water", rows(5).mapIndexed { index, task -> if (index == 4) task else done(task) })
        val fertilize = TaskSection("fertilize", "Fertilize", rows(5).map { it.copy(taskType = "fertilize") })
        val list = state().today!!.copy(sections = if (endOfList) listOf(fertilize, water) else listOf(water, fertilize))
        val care = mutableStateOf(state().copy(today = list))
        compose.setContent { Themed { TaskScreen(care.value, "today") } }
        scrollList(if (endOfList) 100_000f else 300f)
        val before = rowTops()
        val slot = compose.onNodeWithContentDescription("Handle all Water tasks").assertIsEnabled().fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { care.value = care.value.written(care.value.copy(today = list.copy(sections = list.sections.map { section ->
            if (section.key == "water") section.copy(tasks = section.tasks.map { if (it.completedEvent == null) done(it) else it }) else section }))) }
        compose.onNodeWithContentDescription("Handle all Water tasks").assertDoesNotExist()
        val allDone = compose.onNodeWithContentDescription("All Water tasks done").assertIsNotEnabled().assert(hasText("All done"))
        assertEquals("All done keeps the button's place", slot, allDone.fetchSemanticsNode().positionInRoot.y, 0.5f)
        assertEquals("No row moved", before, rowTops())
        if (endOfList) compose.onRoot().captureRoboImage("src/test/screenshots/today-all-done-light.png")
    }

    @Test fun snoozingAndSkippingRowsMidCardMovesNoRow() {
        val water = TaskSection("water", "Water", rows(6))
        val fertilize = TaskSection("fertilize", "Fertilize", rows(3).map { it.copy(taskType = "fertilize") })
        val care = mutableStateOf(state().let { it.copy(today = it.today!!.copy(sections = listOf(water, fertilize))) })
        compose.setContent { Themed { TaskScreen(care.value, "today") } }
        scrollList(300f)
        val before = rowTops()
        val slot = compose.onNodeWithContentDescription("Handle all Water tasks").fetchSemanticsNode().positionInRoot.y
        for ((plant, kind, label) in listOf(Triple("plant-3", "snoozed", "until Tue 4 Jun"), Triple("plant-5", "skipped", "until Sun 9 Jun"))) {
            val until = if (kind == "snoozed") "2030-06-04" else "2030-06-09"
            compose.runOnIdle { care.value = care.value.written(care.value.copy(today = care.value.today!!.copy(sections = care.value.today!!.sections.map { section ->
                section.copy(tasks = section.tasks.map { if (it.plantId != plant || it.key != "water") it else it.postponed(kind, until) }) }))) }
            compose.onNode(hasText(label) and inRow(plant), useUnmergedTree = true).assertIsDisplayed()
            assertEquals("A row moved after the $kind", before, rowTops())
        }
        assertEquals("Handle all keeps its place", slot, compose.onNodeWithContentDescription("Handle all Water tasks").fetchSemanticsNode().positionInRoot.y, 0.5f)
    }

    @Test fun snoozingAndSkippingKeepsTwoLineRowsTheirHeight() = postponingKeepsRowHeight(1f)
    @Test fun snoozingAndSkippingKeepsTwoLineRowsTheirHeightAtLargeText() = postponingKeepsRowHeight(1.3f)
    private fun postponingKeepsRowHeight(fontScale: Float) {
        val water = TaskSection("water", "Water", rows(5).map { it.copy(plantName = "Variegated Monstera ${it.plantName}") })
        val care = mutableStateOf(state().let { it.copy(today = it.today!!.copy(sections = listOf(water))) })
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed { TaskScreen(care.value, "today") } } }
        fun rowBounds() = compose.onAllNodes(taskRow).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to Rect(it.positionInRoot, it.size.toSize()) }
        fun text(plant: String, text: String) = compose.onNode(hasText(text) and inRow(plant), useUnmergedTree = true).assertExists().fetchSemanticsNode()
        fun name(plant: String) = "Variegated Monstera Fern ${plant.removePrefix("plant-")}"
        fun nameLines(plant: String) = text(plant, name(plant)).textLayout().lineCount
        val failures = mutableListOf<String>()
        fun expect(what: String, check: () -> Unit) { runCatching(check).onFailure { failures += "$what at ${fontScale}x text: ${it.message}" } }
        val before = rowBounds()
        val siteLines = listOf("plant-1", "plant-3").associateWith { text(it, "Synthetic site").boundsInRoot.center.y }
        assertEquals(listOf(2, 2), listOf(nameLines("plant-1"), nameLines("plant-3")))
        for ((plant, kind, spoken) in listOf(Triple("plant-1", "snoozed", "Snoozed until Tuesday 4 June"), Triple("plant-3", "skipped", "Skipped until Sunday 9 June"))) {
            val until = if (kind == "snoozed") "2030-06-04" else "2030-06-09"
            compose.runOnIdle { care.value = care.value.written(care.value.copy(today = care.value.today!!.copy(sections = care.value.today!!.sections.map { section ->
                section.copy(tasks = section.tasks.map { if (it.plantId != plant) it else it.postponed(kind, until) }) }))) }
            val label = compose.onNode(hasContentDescription(spoken) and inRow(plant), useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            val day = java.time.LocalDate.parse(until)
            val forms = listOf("until " + day.format(DateTimeFormatter.ofPattern("EEE d MMM")), "until " + day.format(DateTimeFormatter.ofPattern("d MMM")))
            expect("The $kind label reads ${label.config[SemanticsProperties.Text].joinToString()}") {
                assertTrue(label.config[SemanticsProperties.Text].joinToString() in forms.take(if (fontScale > 1f) 2 else 1)) }
            assertEquals("A row changed after the $kind at ${fontScale}x text", before, rowBounds())
            assertEquals(2, nameLines(plant))
            assertEquals("The $kind label sits on the site line", siteLines.getValue(plant), label.boundsInRoot.center.y, 1f)
            expect("The $kind label is cut") { label.textLayout().let { assertFalse("${it.multiParagraph.intrinsics.maxIntrinsicWidth} px in ${it.size.width} px", it.isLineEllipsized(0)) } }
            expect("The $kind row shows a cut site or a lone separator") {
                val site = compose.onAllNodes(hasText("Synthetic site") and inRow(plant), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                val separators = compose.onAllNodes(hasTestTag("site-separator") and inRow(plant), useUnmergedTree = true).fetchSemanticsNodes().size
                if (site != null) assertFalse("cut site", site.textLayout().isLineEllipsized(0))
                assertEquals("separators", if (site == null) 0 else 1, separators)
            }
            expect("The $kind Undo is not a 48 dp icon button") {
                compose.onNodeWithContentDescription("Undo watering ${if (kind == "snoozed") "snooze" else "skip"} for ${name(plant)}, Synthetic site")
                    .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                compose.onNode(hasText("Undo") and inRow(plant)).assertDoesNotExist()
            }
        }
        assertEquals(emptyList<String>(), failures)
        if (fontScale > 1f) compose.onRoot().captureRoboImage("src/test/screenshots/today-postponed-large-light.png")
    }

    @Test fun theSnoozedOrSkippedLabelIsNotCutForAnyDateOfAYear() = postponedLabelFitsAllYear(1f)
    @Test fun theSnoozedOrSkippedLabelIsNotCutForAnyDateOfAYearAtLargeText() = postponedLabelFitsAllYear(1.3f)
    private fun postponedLabelFitsAllYear(fontScale: Float) {
        val tasks = rows(2).map { it.copy(plantName = "Variegated Monstera ${it.plantName}") }
        val care = mutableStateOf(state(tasks = tasks))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed { TaskScreen(care.value, "today") } } }
        val cut = mutableListOf<String>()
        var day = java.time.LocalDate.parse("2030-01-01")
        fun format(pattern: String) = day.format(DateTimeFormatter.ofPattern(pattern))
        while (day.year == 2030) {
            for (kind in listOf("snoozed", "skipped")) {
                compose.runOnIdle { care.value = care.value.written(state(tasks = listOf(tasks[0].postponed(kind, day.toString()), tasks[1]))) }
                val spoken = (if (kind == "snoozed") "Snoozed" else "Skipped") + " until " + format("EEEE d MMMM")
                val label = compose.onNode(hasContentDescription(spoken) and inRow("plant-0"), useUnmergedTree = true).fetchSemanticsNode()
                val shown = label.config[SemanticsProperties.Text].joinToString()
                if (label.textLayout().isLineEllipsized(0)) cut += "$kind label $day"
                val site = compose.onAllNodes(hasText("Synthetic site") and inRow("plant-0"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                site?.let { if (it.textLayout().isLineEllipsized(0)) cut += "$kind site $day" }
                // Only at large text, and only once the site is gone, may the weekday give way.
                val short = fontScale > 1f && site == null && shown == "until " + format("d MMM")
                if (shown != "until " + format("EEE d MMM") && !short) cut += "$kind reads '$shown' on $day"
            }
            day = day.plusDays(1)
        }
        assertEquals("Cut at ${fontScale}x text", emptyList<String>(), cut)
    }

    @Test fun aSnoozedOrSkippedRowShowsItsGlyphAndReadsTheFullDate() {
        val (first, second) = rows(2)
        compose.setContent { Themed { TaskScreen(state(tasks = listOf(first.postponed("snoozed", "2030-06-04"), second.postponed("skipped", "2030-06-09"))), "today") } }
        for ((plant, kind, label, spoken) in listOf(listOf("plant-0", "snoozed", "until Tue 4 Jun", "Snoozed until Tuesday 4 June"),
            listOf("plant-1", "skipped", "until Sun 9 Jun", "Skipped until Sunday 9 June"))) {
            compose.onNode(hasTestTag("$kind-glyph") and inRow(plant), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasText(label) and hasContentDescription(spoken) and inRow(plant), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasText(kind, substring = true, ignoreCase = true) and inRow(plant), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun aSnoozedOrSkippedRowsUndoLinesUpWithTheDoneCircles() {
        val (first, second, third) = rows(3)
        compose.setContent { Themed { TaskScreen(state(tasks = listOf(done(first), second.postponed("snoozed", "2030-06-04"), third.postponed("skipped", "2030-06-09"))), "today") } }
        val circle = bounds(hasContentDescription("done for Fern 0", substring = true))
        for ((name, noun) in listOf("Fern 1" to "snooze", "Fern 2" to "skip")) {
            val undo = bounds(hasContentDescription("Undo watering $noun for $name, Synthetic site"))
            assertEquals("The $noun Undo centre", circle.center.x, undo.center.x, 0.5f)
            assertEquals("The $noun Undo size", circle.width, undo.width, 0.5f)
        }
    }

    @Test fun aCardLeftWithOnlySnoozedOrSkippedTasksSaysNothingDueToday() {
        val (first, second, third) = rows(3)
        val snoozed = first.postponed("snoozed", "2030-06-04")
        val skipped = second.postponed("skipped", "2030-06-04")
        val care = mutableStateOf(state(tasks = listOf(snoozed, skipped, third)))
        compose.setContent { Themed { TaskScreen(care.value, "today") } }
        val slot = compose.onNodeWithContentDescription("Handle all Water tasks").assertIsEnabled().fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { care.value = care.value.written(state(tasks = listOf(snoozed, skipped, third.postponed("snoozed", "2030-06-04")))) }
        val nothing = compose.onNodeWithContentDescription("No Water tasks due today").assertIsNotEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Nothing due today"))
        assertEquals("The button keeps its place", slot, nothing.fetchSemanticsNode().positionInRoot.y, 0.5f)
        compose.onNodeWithText("All done").assertDoesNotExist()
        compose.onNodeWithContentDescription("All Water tasks done").assertDoesNotExist()
        compose.runOnIdle { care.value = care.value.written(state(tasks = listOf(snoozed, skipped, third))) }
        compose.runOnIdle { care.value = care.value.written(state(tasks = listOf(snoozed, skipped, done(third)))) }
        compose.onNodeWithContentDescription("All Water tasks done").assertIsNotEnabled().assert(hasText("All done"))
        compose.onNodeWithText("Nothing due today").assertDoesNotExist()
    }

    @Test fun aReceiptShowingAndHidingAtTheEndOfTheListMovesNoRow() = receiptMovesNoRow(endOfList = true)
    @Test fun aReceiptShowingAndHidingMidListMovesNoRow() = receiptMovesNoRow(endOfList = false)
    private fun receiptMovesNoRow(endOfList: Boolean) {
        val care = mutableStateOf(state(tasks = rows(12)))
        compose.setContent { Themed { TaskScreen(care.value, "today", onReceiptDone = { done ->
            if (care.value.receipt?.id == done.id) care.value = care.value.copy(receipt = null) }) } }
        scrollList(if (endOfList) 100_000f else 300f)
        val before = rowTops()
        compose.runOnIdle { care.value = care.value.copy(receipt = Receipt("Watered Fern 3", event)) }
        compose.onNodeWithText("Watered Fern 3").assertIsDisplayed()
        assertEquals("Rows moved when the receipt appeared", before, rowTops())
        if (endOfList) scrollList(100_000f)
        val shown = rowTops()
        assertEquals("The receipt adds nothing to the end of the list", before, shown)
        compose.mainClock.advanceTimeBy(11_000)
        compose.onNodeWithText("Watered Fern 3").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals("Rows moved when the receipt went", shown, rowTops())
    }

    @Test fun aTapBurstAtTheEndOfTheListActsOnlyOnTheTappedRows() = burst(endOfList = true)
    @Test fun aTapBurstAtTheEndOfACardActsOnlyOnTheTappedRows() = burst(endOfList = false)
    private fun burst(endOfList: Boolean) {
        val pending = if (endOfList) 6..7 else 3..4
        val water = TaskSection("water", "Water", rows(if (endOfList) 9 else 6).mapIndexed { index, task -> if (index in pending) task else done(task) })
        val fertilize = TaskSection("fertilize", "Fertilize", rows(3).map { it.copy(taskType = "fertilize") })
        val care = mutableStateOf(state().let { it.copy(today = it.today!!.copy(sections = if (endOfList) listOf(water) else listOf(water, fertilize))) })
        val acted = mutableListOf<String>()
        fun act(task: CareTask, action: String) {
            acted += "${task.plantName}:${task.label}:$action"
            val today = care.value.today!!
            care.value = care.value.written(care.value.copy(receipt = Receipt("${task.label} $action for ${task.plantName}", event.takeIf { action == "complete" }, short = action != "complete"),
                today = today.copy(sections = today.sections.map { section -> section.copy(tasks = section.tasks.map {
                    if (it.plantId != task.plantId || it.key != task.key) it else if (action == "complete") done(it) else it.copy(completedEvent = null) }) })))
        }
        compose.setContent { Themed { TaskScreen(care.value, "today", onAction = ::act, onReceiptDone = { done ->
            if (care.value.receipt?.id == done.id) care.value = care.value.copy(receipt = null) }) } }
        scrollList(if (endOfList) 100_000f else 300f)
        fun centre(description: String) = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        val (first, last) = pending.map { "Fern $it" }
        val firstAt = centre("Mark Water done for $first, Synthetic site")
        val lastAt = centre("Mark Water done for $last, Synthetic site")
        val below = if (endOfList) null else centre("Mark Fertilize done for Fern 0, Synthetic site")
        fun tap(at: Offset) = compose.onRoot().performTouchInput { click(at) }
        tap(firstAt)
        if (endOfList) {
            compose.onNodeWithText("Water complete for $first").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(11_000)
            compose.onNodeWithText("Water complete for $first").assertDoesNotExist()
            tap(lastAt); tap(firstAt); tap(lastAt)
            assertEquals(listOf("$first:Water:complete", "$last:Water:complete", "$first:Water:undo", "$last:Water:undo"), acted)
        } else {
            tap(lastAt); tap(below!!)
            assertEquals(listOf("Fern 3:Water:complete", "Fern 4:Water:complete", "Fern 0:Fertilize:complete"), acted)
        }
    }

    @Test fun aFirstEverStartShowsNoPlaceholderAndTheFirstFeedAtOnceOnAnUntouchedScreen() {
        MockWebServer().use { server ->
            server.start()
            val member = Member("member-one", Role.owner)
            val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
                tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, { true })
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(), feed = feed, deviceOnline = true) } }
            compose.onNodeWithTag("feed-loading").assertDoesNotExist()
            compose.onNodeWithTag("feed-strip").assertDoesNotExist()
            compose.onRoot().captureRoboImage("src/test/screenshots/today-first-start-light.png")
            server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
            feed.refresh(member)
            compose.waitForIdle()
            compose.onNodeWithTag("feed-cold").assertIsDisplayed()
        }
    }

    @Test fun anOfflineColdStartSaysWeatherAndTipsNeedAConnection() {
        val feed = FeedRepository(ApiClient("https://example.invalid", MemoryCredentials()) {}, { false })
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state().copy(offline = true), feed = feed, deviceOnline = false) } }
        compose.onNodeWithText("Weather and tips need a connection").assertIsDisplayed()
        compose.onNode(hasText("Offline.", substring = true)).assertIsDisplayed()
        compose.onAllNodes(feedCard).assertCountEquals(0)
    }

    @Test fun sortingByPlantOrSiteShowsOneCardPerPlantOrSite() {
        val spider = row.copy(plantId = "spider", plantName = "Spider Plant", siteName = "Bathroom")
        val soil = spider.copy(taskType = null, customTaskId = "91000000-0000-4000-8000-000000000001", customTaskName = "Inspect soil")
        val cheese = row.copy(plantId = "cheese", plantName = "Swiss Cheese Plant", siteName = "Office")
        val byPlant = template.copy(settings = TaskSettings(sort = "plant"), groups = mapOf("water" to listOf(spider, cheese), soil.key to listOf(soil)),
            sections = listOf(TaskSection("spider", "Spider Plant", listOf(spider, soil)), TaskSection("cheese", "Swiss Cheese Plant", listOf(cheese))))
        val current = mutableStateOf(state().copy(today = byPlant))
        val plants = mutableListOf<String>()
        val types = mutableListOf<String>()
        compose.setContent { Themed { TaskScreen(current.value, "today", onHandleType = { types += it }, onHandlePlant = { plants += it }) } }
        fun headings() = compose.onAllNodes(isHeading()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Spider Plant", "Swiss Cheese Plant"), headings())
        compose.onRoot().captureRoboImage("src/test/screenshots/today-plant-sort-light.png")
        for (name in listOf("Spider Plant", "Swiss Cheese Plant")) compose.onAllNodes(hasText(name), useUnmergedTree = true).assertCountEquals(1)
        for ((task, key) in listOf("Water" to "water", "Inspect soil" to soil.key)) {
            compose.onNode(hasText(task) and hasAnyAncestor(hasTestTag("task:spider:$key")), useUnmergedTree = true).assertExists()
            compose.onNode(hasText("Bathroom") and hasAnyAncestor(hasTestTag("task:spider:$key")), useUnmergedTree = true).assertExists()
        }
        val heading = bounds(hasText("Swiss Cheese Plant") and isHeading())
        val only = bounds(hasTestTag("task:cheese:water"))
        val button = bounds(hasContentDescription("Handle all Swiss Cheese Plant tasks"))
        assertTrue("A one-task card is one row plus its heading and button: ${button.bottom - heading.top}",
            button.bottom - heading.top <= heading.height + 16f + only.height + 12f + button.height + 1f)
        compose.onNodeWithContentDescription("Handle all Spider Plant tasks").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("spider"), plants); assertTrue(types.isEmpty()) }
        compose.runOnIdle { current.value = current.value.copy(pending = setOf(BusyKey.HandleAll("spider"))) }
        compose.onNodeWithContentDescription("Handle all Spider Plant tasks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Handle all Swiss Cheese Plant tasks").assertIsEnabled()
        val bySite = byPlant.copy(settings = TaskSettings(sort = "site"),
            sections = listOf(TaskSection("Bathroom", "Bathroom", listOf(spider, soil)), TaskSection("Office", "Office", listOf(cheese))))
        compose.runOnIdle { current.value = current.value.copy(today = bySite, pending = emptySet()) }
        assertEquals(listOf("Bathroom", "Office"), headings())
        compose.onNode(hasContentDescription("Handle all", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Spider Plant") and hasAnyAncestor(hasTestTag("task:spider:${soil.key}")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Inspect soil") and hasAnyAncestor(hasTestTag("task:spider:${soil.key}")), useUnmergedTree = true).assertExists()
        for (site in listOf("Bathroom", "Office")) compose.onAllNodes(hasText(site, substring = true), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun aRowKeepsItsNameColumnInEveryState() = nameColumnStays(1f)
    @Test fun aRowKeepsItsNameColumnInEveryStateAtLargeText() = nameColumnStays(1.3f)
    private fun nameColumnStays(fontScale: Float) {
        val names = listOf("Snake plant", "Monstera Fern", "Monstera Fern 1", "Monstera Fern 12", "Variegated Fern 1", "Variegated Fern 12", "Swiss Cheese Plant 1")
        val tasks = names.mapIndexed { index, name -> row.copy(plantId = "plant-$index", plantName = name) }
        val care = mutableStateOf(state(tasks = tasks))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed { TaskScreen(care.value, "today") } } }
        fun name(task: CareTask) = compose.onNode(hasText(task.plantName) and inRow(task.plantId), useUnmergedTree = true).fetchSemanticsNode().textLayout()
        fun columns() = tasks.associate { task -> name(task).let { task.plantName to (it.layoutInput.constraints.maxWidth to it.lineCount) } }
        fun rows() = compose.onAllNodes(taskRow).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to it.size }
        val wide = compose.onRoot().fetchSemanticsNode().layoutInfo.density.run { 48.dp.toPx() }
        val wrapsOnlyWhenNarrow = tasks.filter { task -> name(task).let { it.multiParagraph.intrinsics.maxIntrinsicWidth.let { width ->
            width > it.layoutInput.constraints.maxWidth && width <= it.layoutInput.constraints.maxWidth + wide } } }
        assertTrue("No name wraps only at the narrow width at ${fontScale}x: ${columns()}", wrapsOnlyWhenNarrow.isNotEmpty())
        val before = columns() to rows()
        val theirs = event.copy(byMember = "member-two", canUndo = false)
        val states = listOf<Pair<String, (CareTask) -> CareState>>(
            "done" to { t -> state(tasks = tasks.map { if (it == t) done(it) else it }) },
            "snoozed" to { t -> state(tasks = tasks.map { if (it == t) it.postponed("snoozed", "2030-06-04") else it }) },
            "skipped" to { t -> state(tasks = tasks.map { if (it == t) it.postponed("skipped", "2030-06-09") else it }) },
            "done by another member" to { t -> state(tasks = tasks.map { if (it == t) it.copy(completedEvent = theirs.copy(id = "event-${it.plantId}", plantId = it.plantId)) else it }) },
            "snoozed by another member" to { t -> state(tasks = tasks.map { if (it == t) it.copy(postponedUntil = "2030-06-04",
                completedEvent = theirs.copy(id = "event-${it.plantId}", plantId = it.plantId, kind = "snoozed")) else it }) },
            "busy" to { t -> state(tasks = tasks).copy(pending = setOf(BusyKey.Row(t.occurrence))) },
            "undone" to { _ -> state(tasks = tasks) },
            "offline" to { _ -> state(tasks = tasks).copy(offline = true) })
        val moved = mutableListOf<String>()
        for (task in wrapsOnlyWhenNarrow) for ((label, change) in states) {
            compose.runOnIdle { care.value = change(task) }
            if (columns() to rows() != before) moved += "${task.plantName} $label: ${columns()[task.plantName]} vs ${before.first[task.plantName]}"
        }
        assertEquals("Rows changed at ${fontScale}x text", emptyList<String>(), moved)
    }

    @Test fun upcomingKeepsEachDaysHeadingAndOneCardPerPlantOrTaskTypeWithinIt() {
        val spider = row.copy(plantId = "spider", plantName = "Spider Plant", dueOn = "2030-06-02", lateDays = 0)
        val soil = spider.copy(taskType = null, customTaskId = "91000000-0000-4000-8000-000000000001", customTaskName = "Inspect soil")
        val cheese = row.copy(plantId = "cheese", plantName = "Swiss Cheese Plant", dueOn = "2030-06-02", lateDays = 0)
        val later = spider.copy(dueOn = "2030-06-03", postponed = "snoozed")
        val byPlant = template.copy(view = "upcoming", settings = TaskSettings(sort = "plant"), sections = listOf(
            TaskSection("2030-06-02:spider", "Spider Plant", listOf(spider, soil), day = "2030-06-02"),
            TaskSection("2030-06-02:cheese", "Swiss Cheese Plant", listOf(cheese), day = "2030-06-02"),
            TaskSection("2030-06-03:spider", "Spider Plant", listOf(later), day = "2030-06-03")))
        val current = mutableStateOf(state().copy(upcoming = byPlant))
        compose.setContent { Themed { TaskScreen(current.value, "upcoming") } }
        fun headings() = compose.onAllNodes(isHeading()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Today", "Spider Plant", "Swiss Cheese Plant", "Tomorrow", "Spider Plant"), headings())
        compose.onAllNodes(hasText("Swiss Cheese Plant"), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasText("Water") and hasAnyAncestor(hasTestTag("task:cheese:water")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Synthetic site") and hasAnyAncestor(hasTestTag("task:cheese:water")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Snoozed") and hasAnyAncestor(hasTestTag("task:spider:water")), useUnmergedTree = true).assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/upcoming-plant-sort-light.png")
        val byType = byPlant.copy(settings = TaskSettings(), sections = listOf(
            TaskSection("2030-06-02:water", "Water", listOf(spider, cheese), day = "2030-06-02"),
            TaskSection("2030-06-02:${soil.key}", "Inspect soil", listOf(soil), day = "2030-06-02"),
            TaskSection("2030-06-03:water", "Water", listOf(later), day = "2030-06-03")))
        compose.runOnIdle { current.value = current.value.copy(upcoming = byType) }
        assertEquals(listOf("Today", "Water", "Inspect soil", "Tomorrow", "Water"), headings())
        compose.onNode(hasText("Water · Synthetic site"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Synthetic site") and hasAnyAncestor(hasTestTag("task:cheese:water")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Snoozed") and hasAnyAncestor(hasTestTag("task:spider:water")), useUnmergedTree = true).assertExists()
    }

    private class Resumable : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.CREATED; registry.currentState = Lifecycle.State.RESUMED }
    }
    private enum class Moment { Switch, Pull, Resume }
    private class Screen(val care: MutableState<CareState>, val feed: MutableState<FeedState?>, val view: MutableState<String>,
                         val refreshing: MutableState<Boolean> = mutableStateOf(false), val app: Resumable = Resumable())
    private fun screen(care: CareState, feed: FeedState? = null, view: String = "today", fontScale: Float = 1f) = Screen(mutableStateOf(care), mutableStateOf(feed), mutableStateOf(view)).also { shown ->
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides shown.app, LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed {
            TaskScreen(shown.care.value, shown.view.value, onView = { shown.view.value = it }, refreshing = shown.refreshing.value, feed = shown.feed.value,
                feedContent = { HomeFeed(it, "2030-06-02") })
        } } }
    }
    private fun Screen.reach(moment: Moment) {
        when (moment) {
            Moment.Switch -> view.value.let { shown ->
                compose.onNodeWithTag(if (shown == "today") "view-upcoming" else "view-today").performClick()
                compose.onNodeWithTag("view-$shown").performClick()
            }
            Moment.Pull -> { compose.runOnIdle { refreshing.value = true }; compose.runOnIdle { refreshing.value = false } }
            Moment.Resume -> compose.runOnIdle { app.resume() }
        }
        compose.waitForIdle()
    }
    private val owner = Member("member-one", Role.owner)
    private val loadedFeed = FeedState(member = owner, loaded = true, weather = parseWeather(JSONObject(weatherFixture)),
        cards = JSONObject(feedFixture).getJSONArray("cards").mapObjects { parseFeedCard(it) }.filterNotNull())

    @Test fun aSnoozeInUpcomingMovesNoRowUntilAViewSwitch() = postponedOccurrenceWaits(Moment.Switch)
    @Test fun aSnoozeInUpcomingMovesNoRowUntilAPull() = postponedOccurrenceWaits(Moment.Pull)
    private fun postponedOccurrenceWaits(moment: Moment) {
        val due = row.copy(plantId = "plant-0", plantName = "Fern 0", dueOn = "2030-06-02", lateDays = 0)
        val later = rows(3).drop(1).map { it.copy(dueOn = "2030-06-09", lateDays = 0) }
        val fourth = TaskSection("2030-06-04:water", "Water", listOf(due.copy(dueOn = "2030-06-04", postponed = "snoozed")), day = "2030-06-04")
        fun upcoming(today: CareTask, vararg between: TaskSection) = state().upcoming!!.copy(sections = listOf(TaskSection("2030-06-02:water", "Water", listOf(today), day = "2030-06-02")) +
            between + TaskSection("2030-06-09:water", "Water", later, day = "2030-06-09"))
        val screen = screen(state().copy(upcoming = upcoming(due)), view = "upcoming")
        val before = rowTops()
        val snoozed = due.postponed("snoozed", "2030-06-04")
        compose.runOnIdle { screen.care.value = screen.care.value.written(screen.care.value.copy(upcoming = upcoming(snoozed))) }
        compose.onNode(hasText("until Tue 4 Jun") and inRow("plant-0"), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { screen.care.value = screen.care.value.copy(upcoming = upcoming(snoozed, fourth)) }
        assertEquals("A row moved when the postponed occurrence arrived", before, rowTops())
        compose.onNodeWithText("Tuesday 4 Jun").assertDoesNotExist()
        compose.runOnIdle { screen.care.value = screen.care.value.written(screen.care.value.copy(upcoming = upcoming(due))) }
        compose.onNodeWithContentDescription("Mark Water done for Fern 0, Synthetic site").assertIsDisplayed()
        assertEquals("A row moved after Undo", before, rowTops())
        compose.runOnIdle { screen.care.value = screen.care.value.written(screen.care.value.copy(upcoming = upcoming(snoozed, fourth))) }
        assertEquals("A row moved when the postponed occurrence came back", before, rowTops())
        screen.reach(moment)
        compose.onNodeWithText("Tuesday 4 Jun").assertIsDisplayed()
    }

    @Test fun aStripArrivingAboveAShortListMovesNoRowUntilResume() = stripWaits(loadedFeed.copy(cards = emptyList()), loadedFeed, tasks = 2, Moment.Resume)
    @Test fun aStripGoingAtTheTopOfALongListMovesNoRowUntilAPull() = stripWaits(loadedFeed, loadedFeed.copy(cards = emptyList()), tasks = 12, Moment.Pull)
    @Test fun aFirstEverFeedWaitsForAPullWhenTheListWasScrolledFirst() =
        stripWaits(FeedState(member = owner), loadedFeed, tasks = 12, Moment.Pull) { scrollList(300f) }
    @Test fun aFirstEverFeedWaitsForResumeWhenARowWasActedOnFirst() = stripWaits(FeedState(member = owner), loadedFeed, tasks = 12, Moment.Resume) {
        compose.onNodeWithContentDescription("Mark Water done for Fern 1, Synthetic site").performClick()
    }
    private fun stripWaits(first: FeedState, next: FeedState, tasks: Int, moment: Moment, touch: () -> Unit = {}) {
        val screen = screen(state(tasks = rows(tasks)), first)
        compose.onNodeWithTag("feed-loading").assertDoesNotExist()
        val shown = first.cards.isNotEmpty()
        compose.onNodeWithTag("feed-strip").apply { if (shown) assertExists() else assertDoesNotExist() }
        touch()
        compose.waitForIdle()
        val before = rowTops()
        compose.runOnIdle { screen.feed.value = next }
        compose.waitForIdle()
        assertEquals("A row moved when the strip ${if (shown) "went" else "arrived"}", before, rowTops())
        compose.onNodeWithTag("feed-strip").apply { if (shown) assertExists() else assertDoesNotExist() }
        screen.reach(moment)
        compose.onNodeWithTag("feed-strip").apply { if (shown) assertDoesNotExist() else assertExists() }
    }

    @Test fun aRainCardArrivingAndGoingKeepsTheStripHeightAndMovesNoRow() = rainCardMovesNoRow(1f)
    @Test fun aRainCardArrivingAndGoingKeepsTheStripHeightAndMovesNoRowAtLargeText() = rainCardMovesNoRow(1.3f)
    private fun rainCardMovesNoRow(fontScale: Float) {
        val regular = loadedFeed.copy(cards = loadedFeed.cards.filter { it.id == "cold" })
        val rainBoth = loadedFeed.copy(cards = loadedFeed.cards.filter { it.id in listOf("rain", "cold") },
            weather = loadedFeed.weather!!.copy(lastBatchId = "batch-old", lastBatchPlantCount = 2))
        val screen = screen(state(tasks = rows(3)), regular, fontScale = fontScale)
        val failures = mutableListOf<String>()
        fun strip() = bounds(hasTestTag("feed-strip")).height
        val before = rowTops() to strip()
        for ((label, next) in listOf("rain-both" to rainBoth, "regular" to regular, "rain-both again" to rainBoth)) {
            compose.runOnIdle { screen.feed.value = next }
            if (rowTops() to strip() != before) failures += "$label: strip ${strip()} vs ${before.second}, rows ${rowTops()} vs ${before.first}"
        }
        compose.onNodeWithText("Watered 2 outdoor plants").assertIsDisplayed()
        val card = compose.onNodeWithTag("feed-rain").fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
        fun holds(inner: Rect) = inner.left >= card.left - 0.5f && inner.top >= card.top - 0.5f && inner.right <= card.right + 0.5f && inner.bottom <= card.bottom + 0.5f
        val inside = hasAnyAncestor(hasTestTag("feed-rain"))
        compose.onAllNodes(inside and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val text = node.config[SemanticsProperties.Text].joinToString()
            val layout = node.textLayout()
            if (layout.didOverflowHeight || (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                failures += "'$text' is cut: ${layout.size} of ${layout.multiParagraph.width}x${layout.multiParagraph.height}, ${layout.lineCount} lines"
            if (!holds(Rect(node.positionInRoot, node.size.toSize()))) failures += "'$text' leaves the card"
        }
        for (action in listOf(hasText("Mark 1 outdoor plant watered"), hasContentDescription("Undo rain watering"))) {
            val button = compose.onNode(action and inside).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            if (!holds(Rect(button.positionInRoot, button.size.toSize()))) failures += "$action leaves the card"
        }
        assertEquals("At ${fontScale}x text", emptyList<String>(), failures)
    }

    @Test fun undoingASnoozeBesideItsCompletedPostponedOccurrenceKeepsEachRowItsOwnOccurrence() {
        val snoozed = row.postponed("snoozed", "2030-06-04")
        val completed = row.copy(dueOn = "2030-06-11", lateDays = 0, completedEvent = event.copy(id = "event-postponed"))
        val acted = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val care = mutableStateOf(state(tasks = listOf(snoozed)))
        val view = mutableStateOf("today")
        compose.setContent { Themed { TaskScreen(care.value, view.value, onView = { view.value = it },
            onAction = { task, action -> acted += "${task.occurrence}:$action" }, onOpenTask = { opened += it.occurrence }) } }
        compose.runOnIdle { care.value = state(tasks = listOf(snoozed, completed)) }
        compose.onNodeWithTag("view-upcoming").performClick()
        compose.onNodeWithTag("view-today").performClick()
        val done = "Water done for Synthetic fern, Synthetic site. Double tap to undo"
        val undoSnooze = "Undo watering snooze for Synthetic fern, Synthetic site"
        compose.onNodeWithContentDescription(undoSnooze).assertIsEnabled()
        compose.onAllNodesWithContentDescription(done).assertCountEquals(1)
        compose.runOnIdle { care.value = state(tasks = listOf(completed)) }
        compose.onAllNodesWithContentDescription(done).assertCountEquals(1)
        compose.onNodeWithContentDescription(undoSnooze).assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(hasText("until Tue 4 Jun") and inRow("plant-one"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(done).performClick()
        compose.onAllNodesWithContentDescription("Open Synthetic fern").apply { assertCountEquals(2); get(0).performClick(); get(1).performClick() }
        compose.runOnIdle {
            assertEquals(listOf("event-postponed:undo"), acted)
            assertEquals(listOf("event-plant-one", "event-postponed"), opened)
            care.value = care.value.copy(pending = setOf(BusyKey.Row("event-postponed")))
        }
        compose.onAllNodesWithContentDescription("Saving Water for Synthetic fern, Synthetic site").assertCountEquals(1)
    }

    @Test fun aFeedFailureWithAnEmptyCachedFeedShowsItsNoticeUntilARefreshSucceeds() = withCache { cache, api, server ->
        feedFailureShows(cachedFeed(api, server, cache, """{"settings":$settingsFixture,"cards":[]}"""), server)
    }
    @Test fun aFeedFailureWithNoCachedFeedShowsItsNoticeUntilARefreshSucceeds() = withCache { cache, api, server ->
        feedFailureShows(FeedRepository(api, { true }, cache = cache), server)
    }
    private fun feedFailureShows(feed: FeedRepository, server: MockWebServer) {
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(tasks = rows(3)), feed = feed, deviceOnline = true) } }
        compose.onNodeWithTag("feed-strip").assertDoesNotExist()
        server.enqueue(MockResponse().setResponseCode(500))
        feed.refresh(owner)
        compose.onNodeWithText("Fytorium isn't responding. Refresh to try again.").assertIsDisplayed()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Synthetic feed failure"}"""))
        feed.refresh(owner)
        compose.onNodeWithText("Synthetic feed failure").assertIsDisplayed()
        server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
        feed.refresh(owner)
        compose.onNodeWithText("Synthetic feed failure").assertDoesNotExist()
        compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
    }

    @Test fun anotherMembersChangeFromABackgroundRefreshWaitsForResume() {
        val tasks = rows(6)
        val screen = screen(state(tasks = tasks))
        val before = rowTops()
        val added = row.copy(plantId = "plant-new", plantName = "Fern new")
        compose.runOnIdle { screen.care.value = state(tasks = listOf(added) + tasks.filter { it.plantId != "plant-1" }) }
        assertEquals("A row moved when another member's change arrived", before, rowTops())
        compose.onNodeWithTag("task:plant-new:water").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Water done for Fern 1, Synthetic site").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mark Water done for Fern 2, Synthetic site").assertIsEnabled()
        screen.reach(Moment.Resume)
        compose.onNodeWithTag("task:plant-new:water").assertIsDisplayed()
        compose.onNodeWithTag("task:plant-1:water").assertDoesNotExist()
    }

    @Test fun theActedRowAndItsReceiptApplyAtOnceWhileARenameAndALayoutChangeWait() {
        val tasks = rows(4)
        val screen = screen(state(tasks = tasks))
        val before = rowTops()
        val added = row.copy(plantId = "plant-new", plantName = "Fern new")
        compose.runOnIdle { screen.care.value = screen.care.value.written(state(tasks = listOf(added) + tasks.map { when (it.plantId) { "plant-0" -> done(it); "plant-2" -> it.copy(plantName = "Renamed fern"); else -> it } })
            .copy(receipt = Receipt("Watered Fern 0", event))) }
        compose.onNodeWithText("Watered Fern 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Water done for Fern 0, Synthetic site. Double tap to undo").assertIsDisplayed()
        compose.onNodeWithText("Renamed fern").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Water done for Fern 2, Synthetic site").assertIsEnabled()
        compose.onNodeWithTag("task:plant-new:water").assertDoesNotExist()
        assertEquals("A row moved", before, rowTops())
        screen.reach(Moment.Resume)
        compose.onNodeWithText("Renamed fern").assertIsDisplayed()
        compose.onNodeWithTag("task:plant-new:water").assertIsDisplayed()
    }

    private fun cachedFeed(api: ApiClient, server: MockWebServer, cache: CareCache, body: String): FeedRepository {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(weatherFixture))
        FeedRepository(api, { true }, cache = cache).refresh(owner)
        return FeedRepository(api, { true }, cache = cache).apply { restore() }
    }

    @Test fun aColdStartWithACachedEmptyFeedShowsNoStripUntilAViewSwitch() = withCache { cache, api, server ->
        val feed = cachedFeed(api, server, cache, """{"settings":$settingsFixture,"cards":[]}""")
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(tasks = rows(8)), feed = feed, deviceOnline = true) } }
        compose.onNodeWithTag("feed-loading").assertDoesNotExist()
        compose.onNodeWithTag("feed-strip").assertDoesNotExist()
        val rows = rowTops()
        server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
        feed.refresh(owner)
        compose.waitForIdle()
        compose.onNodeWithTag("feed-strip").assertDoesNotExist()
        assertEquals("Rows moved when the strip arrived", rows, rowTops())
        compose.onNodeWithTag("view-upcoming").performClick()
        compose.onNodeWithTag("view-today").performClick()
        compose.onNodeWithTag("feed-cold").assertIsDisplayed()
    }

    @Test fun aColdStartWithACachedFeedShowsItsStripAtOnce() = withCache { cache, api, server ->
        val feed = cachedFeed(api, server, cache, feedFixture)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = state(), feed = feed, deviceOnline = true) } }
        compose.onNodeWithTag("feed-cold").assertIsDisplayed()
        compose.onNodeWithTag("feed-loading").assertDoesNotExist()
    }

    // Lists and write responses as the server sends them, for sequences that run this device's writes through the repository.
    private fun taskJson(due: String, late: Int = 0, postponed: String? = null, plant: String = "plant-one", name: String = "Synthetic fern", site: String = "Synthetic site",
                         type: String = "water") =
        JSONObject().put("status", "due").put("dueOn", due).put("intervalDays", 7).put("lateDays", late).put("basedOn", JSONArray())
            .put("task_type", type).put("custom_task_id", JSONObject.NULL).put("plant_id", plant).put("plant_name", name).put("site_name", site)
            .put("postponed", postponed ?: JSONObject.NULL)
    private fun eventJson(id: String, kind: String = "completed", plant: String = "plant-one", batch: String? = null, action: String? = null) =
        JSONObject(eventFixture).put("id", id).put("kind", kind).put("plant_id", plant).apply {
            if (batch != null) put("batch_id", batch).put("batch_action", action).put("batch_task", "water").put("batch_plant_count", 2).put("batch_event_count", 2) }
    private fun JSONObject.acted(event: JSONObject, until: String? = null) =
        JSONObject(toString()).put("postponed", JSONObject.NULL).put("completed_event", event).put("postponed_until", until ?: JSONObject.NULL)
    private fun listJson(view: String, vararg sections: Pair<String, List<JSONObject>>) = JSONObject().put("today", "2030-06-02").put("view", view)
        .put("settings", JSONObject().put("sort", "task").put("filter", "all").put("show_completed", false)).put("groups", JSONObject())
        .put("sections", JSONArray(sections.map { (key, rows) -> JSONObject().put("key", key).put("label", "Water").put("tasks", JSONArray(rows))
            .apply { if (view == "upcoming") put("day", key.substringBefore(":")) } })).toString()
    private fun nextJson(due: String, postponed: String? = null) = JSONObject().put("status", "due").put("dueOn", due).put("intervalDays", 7)
        .put("lateDays", 0).put("basedOn", JSONArray()).put("postponed", postponed ?: JSONObject.NULL)
    private fun actedBody(event: JSONObject, next: JSONObject) = JSONObject().put("event", event).put("linked_events", JSONArray()).put("task", next).toString()
    private fun undoBody(event: JSONObject, next: JSONObject, today: List<JSONObject>, vararg upcoming: Pair<String, List<JSONObject>>) = JSONObject()
        .put("event", event).put("task", next).put("today", JSONObject(listJson("today", *listOfNotNull(today.takeIf { it.isNotEmpty() }?.let { "water" to it }).toTypedArray())))
        .put("upcoming", JSONObject(listJson("upcoming", *upcoming))).toString()
    // The occurrence the server says an Undo brought back for one reversed event, or none.
    private fun restoredJson(event: String, due: String?, type: String = "water", plant: String = "plant-one") = JSONObject().put("event_id", event)
        .put("occurrence", due?.let { JSONObject().put("plant_id", plant).put("task_type", type).put("custom_task_id", JSONObject.NULL).put("due_on", it) } ?: JSONObject.NULL)
    private fun String.restoring(vararg restored: JSONObject) = JSONObject(this).put("restored", JSONArray(restored.toList())).toString()
    private fun rowBounds() = compose.onAllNodes(taskRow).fetchSemanticsNodes().map { Rect(it.positionInRoot, it.size.toSize()) }

    @Test fun undoingASnoozeAndThenItsCompletedPostponedOccurrenceGivesThePendingRowOnlyToTheCompletionsSlot() = withCache { cache, api, server ->
        val pending = taskJson("2030-06-01", late = 1)
        val snooze = eventJson("event-snooze", "snoozed")
        val completion = eventJson("event-postponed").put("due_on", "2030-06-04")
        val snoozed = pending.acted(snooze, "2030-06-04")
        val postponed = taskJson("2030-06-04", postponed = "snoozed")
        val next = taskJson("2030-06-11")
        val completed = next.acted(completion)
        ServedLists(server, listJson("today", "water" to listOf(pending)), listJson("upcoming", "2030-06-02:water" to listOf(pending)), "[$plantFixture]").use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            val view = mutableStateOf("today")
            compose.setContent { Themed { val care by repository.state.collectAsState(); TaskScreen(care, view.value, onView = { view.value = it }) } }
            served.responses["/api/plants/plant-one/tasks/water/snooze"] = actedBody(snooze, nextJson("2030-06-04", "snoozed"))
            served.today = listJson("today", "water" to listOf(snoozed))
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(postponed))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "snooze"))
            served.await { repository.state.value.upcoming!!.sections.size == 2 }
            served.responses["/api/plants/plant-one/tasks/water/complete"] = actedBody(completion, nextJson("2030-06-11"))
            served.today = listJson("today", "water" to listOf(snoozed, completed))
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(completed), "2030-06-11:water" to listOf(next))
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.postponed == "snoozed" }, "complete"))
            repository.refresh()
            compose.onNodeWithTag("view-upcoming").performClick()
            compose.onNodeWithTag("view-today").performClick()
            val undoSnooze = "Undo watering snooze for Synthetic fern, Synthetic site"
            val check = hasContentDescription("Water done for Synthetic fern, Synthetic site", substring = true) and !hasContentDescription("Mark ", substring = true)
            compose.onNodeWithContentDescription(undoSnooze).assertIsEnabled()
            compose.onNode(check).assertIsEnabled()
            val tops = rowBounds()
            // The snooze's Undo leaves no occurrence due today, so its row stays in place without controls.
            served.responses["/api/care-events/event-snooze/undo"] = undoBody(snooze, nextJson("2030-06-11"), listOf(completed),
                "2030-06-04:water" to listOf(completed), "2030-06-11:water" to listOf(next)).restoring(restoredJson("event-snooze", null))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.first { it.completedEvent?.id == "event-snooze" }, "undo"))
            compose.onNodeWithContentDescription(undoSnooze).assertIsNotEnabled()
            compose.onNode(check).assertIsEnabled()
            // The completion's Undo brings back the occurrence due today, which is what the completion's row became.
            served.responses["/api/care-events/event-postponed/undo"] = undoBody(completion, nextJson("2030-06-01"), listOf(pending), "2030-06-02:water" to listOf(pending))
                .restoring(restoredJson("event-postponed", "2030-06-01"))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "undo"))
            compose.waitForIdle()
            compose.onNode(check).assertDoesNotExist()
            val held = compose.onNodeWithContentDescription(undoSnooze).assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
            val restored = compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue("The pending row takes the completion's slot, below the snooze's", restored.top > held.bottom)
            assertEquals("A row moved", tops, rowBounds())
        }
    }

    @Test fun aSittersRowWithoutASiteKeepsItsHeightThroughSnoozeAndUndo() = sitterRowKeepsHeight(1f)
    @Test fun aSittersRowWithoutASiteKeepsItsHeightThroughSnoozeAndUndoAtLargeText() = sitterRowKeepsHeight(1.3f)
    private fun sitterRowKeepsHeight(fontScale: Float) = withCache { cache, api, server ->
        val name = "Variegated Monstera Fern 1"
        val pending = taskJson("2030-06-01", late = 1, name = name, site = "")
        val other = taskJson("2030-06-01", late = 1, plant = "plant-two", name = "Second fern", site = "")
        val snooze = eventJson("event-snooze", "snoozed")
        val snoozed = pending.acted(snooze, "2030-06-04")
        ServedLists(server, listJson("today", "water" to listOf(pending, other)), listJson("upcoming", "2030-06-02:water" to listOf(pending, other)), role = "sitter").use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed {
                val care by repository.state.collectAsState(); TaskScreen(care, "today") } } }
            assertEquals(2, compose.onNode(hasText(name) and inRow("plant-one"), useUnmergedTree = true).fetchSemanticsNode().textLayout().lineCount)
            val before = rowBounds()
            served.responses["/api/plants/plant-one/tasks/water/snooze"] = actedBody(snooze, nextJson("2030-06-04", "snoozed"))
            served.today = listJson("today", "water" to listOf(snoozed, other))
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(snoozed, other),
                "2030-06-04:water" to listOf(taskJson("2030-06-04", postponed = "snoozed", name = name, site = "")))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.first(), "snooze"))
            served.await { served.paths.count { it == "/api/tasks?view=upcoming" } == 2 }
            compose.onNode(hasContentDescription("Snoozed until Tuesday 4 June") and inRow("plant-one"), useUnmergedTree = true).assertIsDisplayed()
            assertEquals("A row changed when the snooze label appeared at ${fontScale}x text", before, rowBounds())
            served.responses["/api/care-events/event-snooze/undo"] = undoBody(snooze, nextJson("2030-06-01"), listOf(pending, other), "2030-06-02:water" to listOf(pending, other))
                .restoring(restoredJson("event-snooze", "2030-06-01"))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.first(), "undo"))
            compose.onNodeWithContentDescription("Mark Water done for $name").assertIsEnabled()
            assertEquals("A row changed when the snooze label went at ${fontScale}x text", before, rowBounds())
        }
    }

    @Test fun aBackgroundRenameWaitsForAMomentWhileRowStateAppliesAtOnce() {
        val long = "Variegated Monstera Deliciosa Thai Constellation"
        val (fern, palm) = rows(2)
        val byPlant = template.copy(settings = TaskSettings(sort = "plant"),
            sections = listOf(TaskSection("plant-0", "Fern 0", listOf(fern)), TaskSection("plant-1", "Fern 1", listOf(palm))))
        val screen = screen(state().copy(today = byPlant))
        fun headings() = compose.onAllNodes(isHeading()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        val before = rowBounds()
        val renamed = fern.copy(plantName = long)
        compose.runOnIdle { screen.care.value = state().copy(today = byPlant.copy(sections = listOf(TaskSection("plant-0", long, listOf(renamed)), byPlant.sections.last())),
            pending = setOf(BusyKey.Row(renamed.occurrence))) }
        compose.onNodeWithContentDescription("Saving Water for Fern 0, Synthetic site").assertIsDisplayed()
        assertEquals(listOf("Fern 0", "Fern 1"), headings())
        compose.onNode(hasText(long), useUnmergedTree = true).assertDoesNotExist()
        assertEquals("A row changed with the rename", before, rowBounds())
        compose.runOnIdle { screen.care.value = screen.care.value.copy(pending = emptySet()) }
        compose.onNodeWithContentDescription("Mark Water done for Fern 0, Synthetic site").assertIsEnabled()
        screen.reach(Moment.Resume)
        assertEquals(listOf(long, "Fern 1"), headings())
        compose.onNodeWithContentDescription("Mark Water done for $long, Synthetic site").assertIsEnabled()
        // Under site sort a renamed site waits too, and its rows keep their controls.
        val bySite = byPlant.copy(settings = TaskSettings(sort = "site"), sections = listOf(TaskSection("Synthetic site", "Synthetic site", listOf(fern, palm))))
        compose.runOnIdle { screen.care.value = state().copy(today = bySite) }
        val sited = rowBounds()
        compose.runOnIdle { screen.care.value = state().copy(today = bySite.copy(sections = listOf(
            TaskSection("Greenhouse", "Greenhouse", listOf(fern, palm).map { it.copy(siteName = "Greenhouse") })))) }
        assertEquals(listOf("Synthetic site"), headings())
        compose.onNode(hasText("Greenhouse", substring = true), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Water done for Fern 1, Synthetic site").assertIsEnabled()
        assertEquals("A row changed with the site rename", sited, rowBounds())
        screen.reach(Moment.Pull)
        assertEquals(listOf("Greenhouse"), headings())
        compose.onNodeWithContentDescription("Mark Water done for Fern 1, Greenhouse").assertIsEnabled()
    }

    @Test fun aBackgroundRenameOfACustomTaskWaitsForAMomentUnderPlantSort() = customTaskRenameWaits(1f)
    @Test fun aBackgroundRenameOfACustomTaskWaitsForAMomentUnderPlantSortAtLargeText() = customTaskRenameWaits(1.3f)
    private fun customTaskRenameWaits(fontScale: Float) {
        val long = "Check every leaf and stem for signs of pests and disease"
        val (fern, palm) = rows(2)
        val check = fern.copy(taskType = null, customTaskId = "check", customTaskName = "Check")
        fun byPlant(name: String) = state().copy(today = template.copy(settings = TaskSettings(sort = "plant"), sections = listOf(
            TaskSection("plant-0", "Fern 0", listOf(check.copy(customTaskName = name), fern)), TaskSection("plant-1", "Fern 1", listOf(palm)))))
        val screen = screen(byPlant("Check"), fontScale = fontScale)
        val before = rowBounds()
        compose.runOnIdle { screen.care.value = byPlant(long) }
        assertEquals("A row moved or changed height with the rename at ${fontScale}x text", before, rowBounds())
        compose.onNode(hasText("Check") and inRow("plant-0", "check"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(long), useUnmergedTree = true).assertDoesNotExist()
        screen.reach(Moment.Pull)
        assertEquals(2, compose.onNode(hasText(long) and inRow("plant-0", "check"), useUnmergedTree = true).fetchSemanticsNode().textLayout().lineCount)
        assertNotEquals("The rename never applied at ${fontScale}x text", before, rowBounds())
    }

    @Test fun aBackgroundChangeOfAHeldRowsOccurrenceLeavesItInPlaceWithoutControlsUntilAMoment() {
        val (moved, undone, unsnoozed, taken) = rows(4)
        val theirs = event.copy(id = "event-theirs", plantId = taken.plantId, byMember = "member-two", canUndo = false)
        val settings = TaskSettings(showCompleted = true)
        val screen = screen(state(settings, tasks = listOf(moved, done(undone), unsnoozed.postponed("snoozed", "2030-06-04"), taken)))
        val before = rowTops()
        // An interval change elsewhere, a completion and a snooze undone elsewhere, and another member's completion.
        compose.runOnIdle { screen.care.value = state(settings, tasks = listOf(moved.copy(dueOn = "2030-05-31", lateDays = 2), undone, unsnoozed, taken.copy(completedEvent = theirs))) }
        compose.onNode(hasText("1d late") and inRow("plant-0"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Water done for Fern 0, Synthetic site").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Water done for Fern 1, Synthetic site").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Undo watering snooze for Fern 2, Synthetic site").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mark Water done for Fern 3, Synthetic site").assertIsNotEnabled()
        compose.onAllNodes(hasContentDescription("Mark Water done", substring = true) and isEnabled()).assertCountEquals(0)
        assertEquals("A row moved", before, rowTops())
        screen.reach(Moment.Pull)
        compose.onNode(hasText("2d late") and inRow("plant-0"), useUnmergedTree = true).assertIsDisplayed()
        for (fern in listOf("Fern 0", "Fern 1", "Fern 2")) compose.onNodeWithContentDescription("Mark Water done for $fern, Synthetic site").assertIsEnabled()
        compose.onNodeWithContentDescription("Water done for Fern 3, Synthetic site").assertIsDisplayed()
    }

    @Test fun thisDevicesCardAndRainBatchesAndTheirUndosKeepEachRowInPlaceWithItsControls() = withCache { cache, api, server ->
        val pending = listOf(taskJson("2030-06-01", late = 1), taskJson("2030-06-01", late = 1, plant = "plant-two", name = "Second fern"))
        val names = listOf("Synthetic fern", "Second fern")
        fun lists(rows: List<JSONObject>) = listJson("today", "water" to rows) to listJson("upcoming", "2030-06-02:water" to rows)
        ServedLists(server, lists(pending).first, lists(pending).second, "[$plantFixture]").use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            compose.setContent { Themed { val care by repository.state.collectAsState(); TaskScreen(care, "today") } }
            val before = rowBounds()
            fun serve(rows: List<JSONObject>) = lists(rows).let { (today, upcoming) -> served.today = today; served.upcoming = upcoming }
            fun batched(batch: String, action: String, scope: String) {
                val events = listOf("plant-one", "plant-two").map { eventJson("$batch-$it", plant = it, batch = batch, action = action) }
                serve(pending.zip(events) { row, event -> row.acted(event) })
                if (action == "rain") repository.rainWatered(batch) else {
                    served.responses["/api/tasks/water/complete-all"] = JSONObject().put("events", JSONArray(events)).toString()
                    val reads = served.paths.size
                    assertTrue(repository.handleType("water"))
                    served.await { served.paths.drop(reads).count { it == "/api/tasks?view=upcoming" } == 1 }
                }
                compose.waitForIdle()
                for (name in names) compose.onNodeWithContentDescription("Water done for $name, Synthetic site. Part of a batch of $scope. Double tap to see the batch").assertIsEnabled()
                assertEquals("A row moved after the $action batch", before, rowBounds())
                served.responses["/api/task-batches/$batch/undo"] = JSONObject().put("batchId", batch).put("undoneAt", "2030-06-02T10:00:00Z")
                    .put("today", JSONObject(lists(pending).first)).put("upcoming", JSONObject(lists(pending).second)).toString()
                    .restoring(*events.map { restoredJson(it.getString("id"), "2030-06-01", plant = it.getString("plant_id")) }.toTypedArray())
                assertTrue(repository.undoBatch(batch))
                for (name in names) compose.onNodeWithContentDescription("Mark Water done for $name, Synthetic site").assertIsEnabled()
                assertEquals("A row moved after the $action batch's Undo", before, rowBounds())
            }
            batched("card", "complete-type", "2 plants")
            batched("rain", "rain", "2 outdoor plants")
        }
    }

    @Test fun aLiquidFertilizeAndItsUndoKeepTheLinkedWaterRowInPlaceWithItsControls() = withCache { cache, api, server ->
        val water = taskJson("2030-06-01", late = 1)
        val fertilize = taskJson("2030-06-01", late = 1, type = "fertilize")
        val fertilized = eventJson("event-fertilize").put("task_type", "fertilize")
        val watered = eventJson("event-water").put("linked_event_id", "event-fertilize")
        fun lists(water: JSONObject, fertilize: JSONObject) = listJson("today", "water" to listOf(water), "fertilize" to listOf(fertilize)) to
            listJson("upcoming", "2030-06-02:water" to listOf(water), "2030-06-02:fertilize" to listOf(fertilize))
        ServedLists(server, lists(water, fertilize).first, lists(water, fertilize).second, "[$plantFixture]").use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            compose.setContent { Themed { val care by repository.state.collectAsState(); TaskScreen(care, "today") } }
            val before = rowBounds()
            served.responses["/api/plants/plant-one/tasks/fertilize/complete"] = JSONObject().put("event", fertilized).put("linked_events", JSONArray().put(watered))
                .put("task", nextJson("2030-06-15")).toString()
            lists(water.acted(watered), fertilize.acted(fertilized)).let { (today, upcoming) -> served.today = today; served.upcoming = upcoming }
            assertTrue(repository.act(repository.state.value.today!!.sections.first { it.key == "fertilize" }.tasks.single(), "complete"))
            served.await { repository.state.value.today!!.sections.first { it.key == "water" }.tasks.single().completedEvent != null }
            compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo").assertIsEnabled()
            compose.onNodeWithContentDescription("Fertilize and water done for Synthetic fern, Synthetic site. Double tap to undo both").assertIsEnabled()
            assertEquals("A row moved after the fertilize", before, rowBounds())
            served.responses["/api/care-events/event-fertilize/undo"] = JSONObject().put("event", fertilized).put("task", nextJson("2030-06-01"))
                .put("today", JSONObject(lists(water, fertilize).first)).put("upcoming", JSONObject(lists(water, fertilize).second)).toString()
                .restoring(restoredJson("event-fertilize", "2030-06-01", type = "fertilize"), restoredJson("event-water", "2030-06-01"))
            assertTrue(repository.act(repository.state.value.today!!.sections.first { it.key == "fertilize" }.tasks.single(), "undo"))
            for (task in listOf("Water", "Fertilize")) compose.onNodeWithContentDescription("Mark $task done for Synthetic fern, Synthetic site").assertIsEnabled()
            assertEquals("A row moved after the Undo", before, rowBounds())
        }
    }

    @Test fun anUndoGivesItsRowNoPendingOccurrenceThatAnotherMembersSkipAlreadyMade() = withCache { cache, api, server ->
        val pending = taskJson("2030-06-02")
        val done = eventJson("event-done").put("due_on", "2030-06-02")
        val completed = pending.acted(done)
        val skipped = taskJson("2030-06-09", postponed = "skipped")
        ServedLists(server, listJson("today", "water" to listOf(pending)), listJson("upcoming", "2030-06-02:water" to listOf(pending)), "[$plantFixture]").use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            val view = mutableStateOf("today")
            compose.setContent { Themed { val care by repository.state.collectAsState(); TaskScreen(care, view.value, onView = { view.value = it }) } }
            served.responses["/api/plants/plant-one/tasks/water/complete"] = actedBody(done, nextJson("2030-06-09"))
            served.today = listJson("today", "water" to listOf(completed))
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(completed))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "complete"))
            compose.onNodeWithTag("view-upcoming").performClick()
            val before = rowBounds()
            // Another member skips the next occurrence, and a background refresh brings it while the screen holds its rows.
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(completed), "2030-06-09:water" to listOf(skipped))
            repository.refresh()
            compose.onNodeWithText("Sunday 9 Jun").assertDoesNotExist()
            served.responses["/api/care-events/event-done/undo"] = undoBody(done, nextJson("2030-06-09", "skipped"), emptyList(), "2030-06-09:water" to listOf(skipped))
                .restoring(restoredJson("event-done", null))
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.completedEvent?.id == "event-done" }, "undo"))
            compose.waitForIdle()
            assertEquals(emptyList<Pair<String, String>>(), repository.state.value.transitions.filter { it.first == "event-done" })
            compose.onAllNodes(hasContentDescription("Mark Water done", substring = true) and isEnabled()).assertCountEquals(0)
            compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site").assertIsNotEnabled()
            assertEquals("A row moved", before, rowBounds())
            compose.onNodeWithTag("view-today").performClick()
            compose.onNodeWithTag("view-upcoming").performClick()
            compose.onNodeWithText("Sunday 9 Jun").assertIsDisplayed()
            compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsEnabled()
        }
    }

    // Another member skipped the fern's water A, so the server's write acted on the skipped occurrence B, due 8 June; this device still shows A.
    private fun skippedElsewhere(write: String, role: Role = Role.owner) = withCache { cache, api, server ->
        val site = if (role == Role.sitter) "" else "Synthetic site"
        val name = "Synthetic fern" + if (site.isEmpty()) "" else ", $site"
        val fern = taskJson("2030-06-01", late = 1, site = site)
        val second = taskJson("2030-06-01", late = 1, plant = "plant-two", name = "Second fern", site = site)
        val fertilize = taskJson("2030-06-01", late = 1, type = "fertilize", site = site)
        val batch = write.takeIf { it in listOf("plant", "card", "rain") }
        val action = mapOf("plant" to "complete-all", "card" to "complete-type", "rain" to "rain")[write]
        fun event(plant: String, due: String) = eventJson("$write-$plant", plant = plant, batch = batch, action = action).put("due_on", due)
        val acted = event("plant-one", "2030-06-08").apply { if (write == "fertilize") put("linked_event_id", "event-fertilize") }
        val fertilized = eventJson("event-fertilize").put("task_type", "fertilize").put("due_on", "2030-06-01")
        val both = write in listOf("card", "rain")
        val secondDone = event("plant-two", "2030-06-01")
        val after = listOf(taskJson("2030-06-15", site = site).acted(acted), if (both) second.acted(secondDone) else second)
        fun lists(water: List<JSONObject>, fertilize: JSONObject, upcoming: List<Pair<String, List<JSONObject>>>) = listJson("today", "water" to water, "fertilize" to listOf(fertilize)) to
            listJson("upcoming", "2030-06-02:fertilize" to listOf(fertilize), *upcoming.toTypedArray())
        val (today, upcoming) = lists(listOf(fern, second), fertilize, listOf("2030-06-02:water" to listOf(fern, second)))
        ServedLists(server, today, upcoming, "[$plantFixture]", role = role.name).use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            val view = mutableStateOf("today")
            compose.setContent { Themed { val care by repository.state.collectAsState(); TaskScreen(care, view.value, onView = { view.value = it }) } }
            lists(after, if (write == "fertilize") fertilize.acted(fertilized) else fertilize,
                listOf("2030-06-02:water" to after.drop(1), "2030-06-08:water" to after.take(1))).let { (today, upcoming) -> served.today = today; served.upcoming = upcoming }
            val reads = served.paths.size
            when (write) {
                "single" -> {
                    served.responses["/api/plants/plant-one/tasks/water/complete"] = actedBody(acted, nextJson("2030-06-15"))
                    assertTrue(repository.act(repository.state.value.today!!.sections.first { it.key == "water" }.tasks.first(), "complete"))
                }
                "fertilize" -> {
                    served.responses["/api/plants/plant-one/tasks/fertilize/complete"] = JSONObject().put("event", fertilized)
                        .put("linked_events", JSONArray().put(acted)).put("task", nextJson("2030-06-15")).toString()
                    assertTrue(repository.act(repository.state.value.today!!.sections.first { it.key == "fertilize" }.tasks.single(), "complete"))
                    served.await { served.paths.drop(reads).count { it == "/api/tasks?view=upcoming" } == 1 }
                }
                "plant" -> {
                    served.responses["/api/plants/plant-one/tasks/complete-all"] = JSONObject().put("events", JSONArray().put(acted))
                        .put("tasks", JSONObject().put("water", nextJson("2030-06-15")).put("customTasks", JSONObject())).toString()
                    assertTrue(repository.handleAll("plant-one"))
                }
                "card" -> {
                    served.responses["/api/tasks/water/complete-all"] = JSONObject().put("events", JSONArray().put(acted).put(secondDone)).toString()
                    assertTrue(repository.handleType("water"))
                    served.await { served.paths.drop(reads).count { it == "/api/tasks?view=upcoming" } == 1 }
                }
                "rain" -> repository.rainWatered("rain")
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Mark Water done for $name").assertIsNotEnabled()
            assertEquals(emptyList<Pair<String, String>>(), repository.state.value.transitions.filter { it.second == "$write-plant-one" })
            val secondRow = compose.onNode(hasContentDescription("Water done for Second fern", substring = true))
            secondRow.assertIsEnabled()
            if (both) secondRow.assert(hasContentDescription("Part of a batch of 2", substring = true))
            // At the next moment the list is what the server sent, with the occurrence the write really completed and its own Undo.
            repository.refresh()
            compose.onNodeWithTag("view-upcoming").performClick()
            compose.onNodeWithTag("view-today").performClick()
            compose.onNode(hasContentDescription("Water done for $name", substring = true) and isEnabled()).assertExists()
            compose.onNodeWithContentDescription("Mark Water done for $name").assertDoesNotExist()
        }
    }
    @Test fun aCompletionOfAnotherMembersSkippedOccurrenceLeavesTheTappedRowWithoutControls() = skippedElsewhere("single")
    @Test fun aLiquidFertilizesWaterOfAnotherMembersSkippedOccurrenceLeavesTheShownWaterRowWithoutControls() = skippedElsewhere("fertilize")
    @Test fun aSittersLiquidFertilizesWaterOfAnotherMembersSkippedOccurrenceLeavesTheShownWaterRowWithoutControls() = skippedElsewhere("fertilize", Role.sitter)
    @Test fun aPlantBatchThatCompletedAnotherMembersSkippedOccurrenceLeavesItsRowWithoutControls() = skippedElsewhere("plant")
    @Test fun aCardBatchThatCompletedAnotherMembersSkippedOccurrenceLeavesItsRowWithoutControls() = skippedElsewhere("card")
    @Test fun rainThatWateredAnotherMembersSkippedOccurrenceLeavesItsRowWithoutControls() = skippedElsewhere("rain")

    @Test fun anOverdueOccurrenceRestoredIntoAFutureRowKeepsTheRowsWidthForTheOwner() = restoredIntoFutureRow(Role.owner, 1f)
    @Test fun anOverdueOccurrenceRestoredIntoAFutureRowKeepsTheRowsWidthForTheOwnerAtLargeText() = restoredIntoFutureRow(Role.owner, 1.3f)
    @Test fun anOverdueOccurrenceRestoredIntoAFutureRowKeepsTheRowsWidthForASitter() = restoredIntoFutureRow(Role.sitter, 1f)
    @Test fun anOverdueOccurrenceRestoredIntoAFutureRowKeepsTheRowsWidthForASitterAtLargeText() = restoredIntoFutureRow(Role.sitter, 1.3f)
    private fun restoredIntoFutureRow(role: Role, fontScale: Float) = withCache { cache, api, server ->
        val name = "Variegated Monstera Fern 12"
        val site = if (role == Role.sitter) "" else "Synthetic site"
        fun task(due: String, late: Int = 0, postponed: String? = null) = taskJson(due, late, postponed, name = name, site = site)
        val pending = task("2030-06-01", late = 1)
        val snooze = eventJson("event-snooze", "snoozed")
        val completion = eventJson("event-postponed").put("due_on", "2030-06-04")
        val snoozed = pending.acted(snooze, "2030-06-04")
        val next = task("2030-06-11")
        val completed = next.acted(completion)
        ServedLists(server, listJson("today", "water" to listOf(pending)), listJson("upcoming", "2030-06-02:water" to listOf(pending)), "[$plantFixture]", role = role.name).use { served ->
            val repository = Repository(api, cache, { true })
            repository.refresh()
            val view = mutableStateOf("today")
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { Themed {
                val care by repository.state.collectAsState(); TaskScreen(care, view.value, onView = { view.value = it }) } } }
            served.responses["/api/plants/plant-one/tasks/water/snooze"] = actedBody(snooze, nextJson("2030-06-04", "snoozed"))
            served.today = listJson("today", "water" to listOf(snoozed))
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(task("2030-06-04", postponed = "snoozed")))
            assertTrue(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "snooze"))
            served.await { repository.state.value.upcoming!!.sections.size == 2 }
            compose.onNodeWithTag("view-upcoming").performClick()
            fun names() = compose.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.textLayout().let { layout -> layout.layoutInput.constraints.maxWidth to layout.lineCount } }
            fun layout() = names() to rowBounds()
            val before = layout()
            assertEquals(2, before.first.size)
            served.responses["/api/plants/plant-one/tasks/water/complete"] = actedBody(completion, nextJson("2030-06-11"))
            served.today = listJson("today", "water" to listOf(snoozed, completed))
            served.upcoming = listJson("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(completed), "2030-06-11:water" to listOf(next))
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.postponed == "snoozed" }, "complete"))
            compose.waitForIdle()
            assertEquals("The future row changed when completed at ${fontScale}x text", before, layout())
            served.responses["/api/care-events/event-snooze/undo"] = undoBody(snooze, nextJson("2030-06-11"), listOf(completed),
                "2030-06-04:water" to listOf(completed), "2030-06-11:water" to listOf(next)).restoring(restoredJson("event-snooze", null))
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.completedEvent?.id == "event-snooze" }, "undo"))
            compose.waitForIdle()
            assertEquals("A row changed after the snooze's Undo at ${fontScale}x text", before, layout())
            served.responses["/api/care-events/event-postponed/undo"] = undoBody(completion, nextJson("2030-06-01"), listOf(pending), "2030-06-02:water" to listOf(pending))
                .restoring(restoredJson("event-postponed", "2030-06-01"))
            assertTrue(repository.act(repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.completedEvent?.id == "event-postponed" }, "undo"))
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Snooze 2 days").assertIsEnabled()
            assertEquals("The future row changed when the overdue occurrence came back into it at ${fontScale}x text", before, layout())
        }
    }

    @Test fun aSitterLosingTheLastShareKeepsTheToggleAndRowsUntilAMoment() = emptyCollectionWaits(Role.sitter, "Nothing is shared with you yet")
    @Test fun anOwnerLosingTheLastPlantKeepsTheToggleAndRowsUntilAMoment() = emptyCollectionWaits(Role.owner, "Add your first plant")
    private fun emptyCollectionWaits(role: Role, empty: String) {
        val screen = screen(state(role = role, tasks = rows(3)))
        val before = rowTops()
        compose.runOnIdle { screen.care.value = state(role = role, tasks = emptyList()).copy(plants = emptyList()) }
        compose.onNodeWithTag("view-today").assertIsDisplayed()
        compose.onNodeWithText(empty).assertDoesNotExist()
        assertEquals("A row moved when the collection emptied", before, rowTops())
        screen.reach(Moment.Resume)
        compose.onNodeWithText(empty).assertIsDisplayed()
        compose.onNodeWithTag("view-today").assertDoesNotExist()
    }
}
