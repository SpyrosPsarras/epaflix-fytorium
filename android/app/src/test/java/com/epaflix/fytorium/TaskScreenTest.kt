package com.epaflix.fytorium

import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class TaskScreenTest {
    @get:Rule val compose = createComposeRule()
    @Composable private fun TaskTestTheme(dark: Boolean = false, content: @Composable () -> Unit) {
        FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
    }
    private fun state(view: String = "today", sitter: Boolean = false, offline: Boolean = false): CareState {
        val parsed = parseTaskList(taskListFixture(view, basis = if (sitter) "[]" else """[{"input":"wateringIntervalDays","value":7,"source":"rule","adjustment":"Default watering interval","ruleId":"water.default@1"}]"""))
        val list = if (sitter) parsed.copy(sections = parsed.sections.map { it.copy(tasks = it.tasks.map { row -> row.copy(siteName = "") }) }) else parsed
        return CareState(Member("member-one", if (sitter) Role.sitter else Role.owner), today = list.copy(view = "today"),
            upcoming = list.copy(view = "upcoming"), plants = listOf(parsePlant(org.json.JSONObject(plantFixture))), offline = offline, lastUpdated = java.time.Instant.parse("2030-06-02T10:42:00Z").toEpochMilli())
    }
    @Test fun scrolledCompletionTargetCannotBeInterceptedByAddFab() {
        val base = state()
        val rows = base.today!!.sections.single().tasks.single().let { row -> (0..7).map { row.copy(plantId = "plant-$it", plantName = "Fern $it") } }
        val list = base.today.copy(sections = listOf(TaskSection("water", "Water", rows)))
        var added = 0
        var completed: String? = null
        compose.setContent { TaskTestTheme { TaskScreen(base.copy(today = list), "today", onAddPlant = { added++ },
            onAction = { task, action -> if (action == "complete") completed = task.plantId }) } }
        compose.onNodeWithTag("task-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val fab = compose.onNodeWithContentDescription("Add plant").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("task-list").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("task:plant-7:water").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The list viewport must continue behind the FAB", viewport.bottom > fab.bottom)
        assertTrue("The last row must scroll fully above the FAB", last.bottom < fab.top)
        compose.onNodeWithContentDescription("Mark Water done for Fern 7, Synthetic site")
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals("plant-7", completed); assertEquals(0, added) }
    }
    @Test fun todayAndUpcomingLight() = screens(false)
    @Test fun todayAndUpcomingDark() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val current = mutableStateOf(state())
        val view = mutableStateOf("today")
        compose.setContent { TaskTestTheme(dark) { TaskScreen(current.value, view.value) } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("1d late").assertIsDisplayed()
        compose.onNodeWithContentDescription("Snooze 2 days").assertHeightIsAtLeast(48.dp).assertHasClickAction()
        capture("tasks-today")
        compose.runOnIdle { current.value = state("upcoming"); view.value = "upcoming" }
        compose.onNodeWithText("Next 14 calendar days, including today").assertIsDisplayed()
        capture("upcoming")
        compose.runOnIdle { current.value = state(offline = true); view.value = "today" }
        compose.onNodeWithText(current.value.offlineBanner()).assertIsDisplayed()
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsNotEnabled()
        capture("offline")
    }
    @Test fun sitterLightVariants() = sitterVariants(false)
    @Test fun sitterDarkVariants() = sitterVariants(true)
    private fun sitterVariants(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val current = mutableStateOf(state(sitter = true))
        val view = mutableStateOf("today")
        val own = parseEvent(org.json.JSONObject(eventFixture))
        compose.setContent { TaskTestTheme(dark) { TaskScreen(current.value, view.value) } }
        fun event(event: CareEvent?) {
            compose.runOnIdle {
                val fresh = state(view.value, sitter = true)
                fun rows(list: TaskList?) = list?.copy(sections = list.sections.map { it.copy(tasks = it.tasks.map { row -> row.copy(completedEvent = event, dueOn = if (event == null) row.dueOn else "2030-06-09", lateDays = if (event == null) row.lateDays else 0) }) })
                current.value = fresh.copy(today = rows(fresh.today), upcoming = rows(fresh.upcoming))
            }
        }
        for (screen in listOf("today", "upcoming")) {
            compose.runOnIdle { view.value = screen }
            event(own.copy(byMember = "member-two", canUndo = false))
            compose.onNodeWithContentDescription("Water done for Synthetic fern").assertHasNoClickAction()
            event(own.copy(taskType = "repot", previousPot = true, canUndo = false))
            compose.onNodeWithContentDescription("Water done for Synthetic fern").assertHasNoClickAction()
            event(own)
            compose.onNodeWithText("Undo").assertDoesNotExist()
            compose.onNodeWithContentDescription("Water done for Synthetic fern. Double tap to undo").assertHasClickAction().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("Task view settings").assertDoesNotExist()
            compose.onNode(hasText("Synthetic site", substring = true)).assertDoesNotExist()
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/sitter-$screen-${if (dark) "dark" else "light"}.png")
            compose.runOnIdle { current.value = current.value.copy(offline = true) }
            compose.onNodeWithContentDescription("Water done for Synthetic fern. Double tap to undo").assertIsNotEnabled()
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        }
    }
    @Test fun sitterNeverSeesSettingsOrPrivateBasisAndCannotUndoAnotherMembersCare() {
        compose.enableAccessibilityChecks()
        val list = state(sitter = true)
        compose.setContent { TaskTestTheme { TaskScreen(list, "today") } }
        compose.onNodeWithContentDescription("Task view settings").assertDoesNotExist()
        compose.onNodeWithText("Based on").assertDoesNotExist()
        compose.onNodeWithText("Repot with new pot and soil").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern").assertHasClickAction()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun selectedTabsAndLabelledSettingsSheet() {
        compose.enableAccessibilityChecks()
        val view = mutableStateOf("today")
        var saved: TaskSettings? = null
        compose.setContent { TaskTestTheme { TaskScreen(state(), view.value, onView = { view.value = it }, onSettings = { saved = it }) } }
        compose.onNodeWithText("Fytorium").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search plants").assertIsDisplayed()
        compose.onNodeWithContentDescription("My notifications").assertIsDisplayed()
        compose.onNodeWithTag("view-today").assertIsSelected()
        compose.onNodeWithTag("view-upcoming").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("view-today").assertIsNotSelected()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithContentDescription("Task view settings").performClick()
        compose.onNodeWithText("Task view settings").assertIsDisplayed()
        compose.onNodeWithText("Sort tasks: Task type").performClick()
        compose.onNodeWithText("Plant", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Sort tasks: Plant").assertIsDisplayed()
        compose.onNodeWithText("Filter: All").performClick()
        compose.onNodeWithText("Outdoor").performClick()
        compose.onNodeWithText("Filter: Outdoor").assertIsDisplayed()
        compose.onNodeWithText("Show others' completed tasks").assertIsOff().assertHasClickAction().performClick().assertIsOn()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onNodeWithText("Save task view").performClick()
        compose.runOnIdle { assertEquals(TaskSettings("plant", "outdoor", true), saved) }
        compose.onNodeWithText("Show others' completed tasks").assertDoesNotExist()
    }
    @Test fun rowsOpenWithoutInlineActionsAndUndoFollowsTheServer() {
        val current = mutableStateOf(state())
        val view = mutableStateOf("today")
        val actions = mutableListOf<String>()
        compose.setContent { TaskTestTheme { TaskScreen(current.value, view.value, onAction = { _, action -> actions.add(action) }) } }
        compose.onNodeWithContentDescription("Open Synthetic fern").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Based on").assertDoesNotExist()
        val original = state().today!!
        val event = parseEvent(org.json.JSONObject(eventFixture))
        for (role in Role.entries) for (screen in listOf("today", "upcoming")) for (done in listOf(false, true)) {
            compose.runOnIdle {
                val list = original.copy(sections = original.sections.map { it.copy(tasks = it.tasks.map { row ->
                    row.copy(completedEvent = if (done) event.copy(canUndo = role != Role.sitter) else null)
                }) })
                current.value = state().copy(member = Member("member-one", role), today = list, upcoming = list)
                view.value = screen
            }
            compose.onNodeWithContentDescription("Open Synthetic fern").assertHasClickAction()
            compose.onNodeWithText("Handle all tasks for Synthetic fern").assertDoesNotExist()
            compose.onNodeWithText("Undo").assertDoesNotExist()
            val label = "Water done for Synthetic fern, Synthetic site"
            if (done && role != Role.sitter) compose.onNodeWithContentDescription("$label. Double tap to undo").assertHasClickAction()
            else compose.onNodeWithContentDescription("$label. Double tap to undo").assertDoesNotExist()
        }
        // A completion is part of its own row; a pending row never shows a check.
        val done = original.copy(sections = original.sections.map { it.copy(tasks = it.tasks.map { row -> row.copy(completedEvent = event) }) })
        compose.runOnIdle { current.value = state().copy(today = done) ; view.value = "today" }
        val handledRow = hasAnyAncestor(hasTestTag("task:plant-one:water"))
        compose.onNode(handledRow and hasText("Undo")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo")).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("undo"), actions) }
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertDoesNotExist()
        compose.runOnIdle { view.value = "upcoming" }
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsDisplayed()
        compose.onNode(hasContentDescription(" done for", substring = true) and !hasContentDescription("Mark ", substring = true)).assertDoesNotExist()
    }
    @Test fun receiptLight() = receipts(false)
    @Test fun receiptDark() = receipts(true)
    private fun receipts(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val receipt = Receipt("Watered Synthetic fern", parseEvent(org.json.JSONObject(eventFixture)))
        val current = mutableStateOf(state().copy(receipt = receipt))
        val undone = mutableListOf<Receipt>()
        val ended = mutableListOf<Receipt>()
        compose.setContent { TaskTestTheme(dark) { TaskScreen(current.value, "today", onUndoReceipt = { undone.add(it) }, onReceiptDone = { ended.add(it) }) } }
        compose.onNodeWithText("Watered Synthetic fern").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onRoot().captureRoboImage("src/test/screenshots/receipt-snackbar-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Undo").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(listOf(receipt), undone); assertTrue(ended.isEmpty()) }
        compose.runOnIdle { current.value = current.value.copy(receipt = Receipt("Undone", short = true)) }
        compose.onNodeWithText("Undone").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        val failure = Receipt("Only due or late tasks can be snoozed.")
        compose.runOnIdle { current.value = current.value.copy(receipt = failure) }
        compose.onNodeWithText("Undone").assertDoesNotExist()
        compose.onNodeWithText(failure.text).assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(9_000)
        compose.onNodeWithText(failure.text).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertTrue(failure in ended) }
        compose.onNodeWithText(failure.text).assertDoesNotExist()
    }
    @Test fun leavingTheScreenEndsTheShownReceipt() {
        val receipt = Receipt("Watered Synthetic fern", parseEvent(org.json.JSONObject(eventFixture)))
        val shown = mutableStateOf(true)
        val ended = mutableListOf<Receipt>()
        compose.setContent { TaskTestTheme { if (shown.value) TaskScreen(state().copy(receipt = receipt), "today", onReceiptDone = { ended.add(it) }) } }
        compose.onNodeWithText("Watered Synthetic fern").assertIsDisplayed()
        compose.runOnIdle { assertTrue(ended.isEmpty()); shown.value = false }
        compose.runOnIdle { assertEquals(listOf(receipt), ended) }
    }
    @Test fun batchedCompletedCardLight() = batched(false)
    @Test fun batchedCompletedCardDark() = batched(true)
    private fun batched(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val base = state()
        val row = base.today!!.sections.single().tasks.single()
        val event = parseEvent(org.json.JSONObject(eventFixture).put("batch_id", "batch-one"))
        val rows = listOf(row.copy(completedEvent = event, lateDays = 0),
            row.copy(plantId = "plant-two", plantName = "Second fern", lateDays = 0, completedEvent = event.copy(id = "event-two", plantId = "plant-two")),
            row.copy(plantId = "plant-three", plantName = "Third fern"))
        val list = base.today.copy(sections = listOf(TaskSection("water", "Water", rows)))
        val actions = mutableListOf<String>()
        compose.setContent { TaskTestTheme(dark) { TaskScreen(base.copy(today = list, batches = mapOf("batch-one" to BatchReceipt("Watered 2 plants", "2 plants", 2, "Undid watering for 2 plants"))), "today",
            onAction = { task, action -> actions.add("${task.plantId}:$action") }) } }
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Water done for Second fern, Synthetic site. Part of a batch of 2 plants. Double tap to see the batch").assertHasClickAction()
        compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site. Part of a batch of 2 plants. Double tap to see the batch").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Mark Water done for Third fern, Synthetic site").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("plant-one:undo"), actions) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onRoot().captureRoboImage("src/test/screenshots/batch-completed-${if (dark) "dark" else "light"}.png")
    }
    @Test fun filledChecksNameTheirTaskAndBatch() {
        val base = state()
        val row = base.today!!.sections.single().tasks.single().copy(lateDays = 0)
        fun event(id: String, type: String, vararg batch: Pair<String, Any>) =
            parseEvent(org.json.JSONObject(eventFixture).put("id", id).put("task_type", type).apply { batch.forEach { (key, value) -> put(key, value) } })
        val water = event("event-one", "water")
        val handled = event("event-fertilize", "fertilize", "batch_id" to "batch-plant", "batch_action" to "complete-all", "batch_plant_count" to 1, "batch_event_count" to 3)
        val card = event("event-clean", "clean_leaves", "batch_id" to "batch-card", "batch_action" to "complete-type", "batch_task" to "clean_leaves",
            "batch_plant_count" to 4, "batch_event_count" to 4)
        val rows = listOf(water, handled, card).map { row.copy(taskType = it.taskType, completedEvent = it) }
        compose.setContent { TaskTestTheme { TaskScreen(base.copy(today = base.today.copy(sections = listOf(TaskSection("plant-one", "Synthetic fern", rows))),
            batches = listOf(handled, card).associate { it.batchId!! to batchReceipt(it, "Synthetic fern")!! }), "today") } }
        compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo").assertHasClickAction()
        compose.onNodeWithContentDescription("Fertilize done for Synthetic fern, Synthetic site. Part of a batch of 3 tasks. Double tap to see the batch").assertHasClickAction()
        compose.onNodeWithContentDescription("Clean leaves done for Synthetic fern, Synthetic site. Part of a batch of 4 plants. Double tap to see the batch").assertHasClickAction()
    }
    @Test fun aLiquidFertilizeCheckNamesTheLinkedWaterItAlsoUndoes() {
        val base = state()
        val row = base.today!!.sections.single().tasks.single().copy(lateDays = 0)
        val fertilize = org.json.JSONObject(eventFixture).put("id", "event-fert").put("task_type", "fertilize")
        val water = org.json.JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-fert")
        fun rows(vararg rows: CareTask) = base.copy(today = base.today.copy(sections = listOf(TaskSection("plant-one", "Synthetic fern", rows.toList()))))
        val fertilized = row.copy(taskType = "fertilize", completedEvent = parseEvent(fertilize))
        // The server lists the linked water as its own row.
        val current = mutableStateOf(rows(fertilized, row.copy(completedEvent = parseEvent(water))))
        compose.setContent { TaskTestTheme { TaskScreen(current.value, "today") } }
        val both = "Fertilize and water done for Synthetic fern, Synthetic site. Double tap to undo both"
        compose.onNodeWithContentDescription(both).assertHasClickAction()
        compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo").assertHasClickAction()
        // Before the next refresh lists the water, this device's write response names it.
        compose.runOnIdle { current.value = rows(row.copy(taskType = "fertilize",
            completedEvent = parseEvent(org.json.JSONObject(fertilize.toString()).put("linked_events", org.json.JSONArray().put(water))))) }
        compose.onNodeWithContentDescription(both).assertHasClickAction()
        // An opened plant page's history still lists water that another device has since undone.
        compose.runOnIdle { current.value = rows(fertilized).copy(detail = PlantDetail(parsePlant(org.json.JSONObject(plantFixture)),
            listOf(parseEvent(fertilize), parseEvent(water)), emptyMap())) }
        compose.onNodeWithContentDescription("Fertilize done for Synthetic fern, Synthetic site. Double tap to undo").assertHasClickAction()
    }
    @Test fun upcomingSnoozedLight() = snoozedUpcoming(false)
    @Test fun upcomingSnoozedDark() = snoozedUpcoming(true)
    private fun snoozedUpcoming(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04", event = ""","postponed":"snoozed""""))
        val row = upcoming.sections.single().tasks.single().copy(lateDays = 0)
        val skipped = row.copy(plantId = "plant-two", plantName = "Second fern", postponed = "skipped")
        val list = upcoming.copy(sections = listOf(upcoming.sections.single().copy(tasks = listOf(row, skipped))))
        compose.setContent { TaskTestTheme(dark) { TaskScreen(state("upcoming").copy(upcoming = list), "upcoming") } }
        compose.onNode(hasText("Snoozed") and hasAnyAncestor(hasTestTag("task:plant-one:water"))).assertIsDisplayed()
        compose.onNode(hasText("Skipped") and hasAnyAncestor(hasTestTag("task:plant-two:water"))).assertIsDisplayed()
        compose.onNode(hasContentDescription("Snoozed for", substring = true)).assertDoesNotExist()
        compose.onNode(hasContentDescription(" done for", substring = true) and !hasContentDescription("Mark ", substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsEnabled()
        compose.mainClock.advanceTimeBy(1000)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onRoot().captureRoboImage("src/test/screenshots/upcoming-snoozed-${if (dark) "dark" else "light"}.png")
    }
    @Test fun onlyTheActingRowAndCardButtonAreBusy() {
        val base = state()
        val row = base.today!!.sections.single().tasks.single()
        val list = base.today.copy(sections = listOf(TaskSection("water", "Water", listOf(row, row.copy(plantId = "plant-two", plantName = "Second fern")))))
        val current = mutableStateOf(base.copy(today = list, pending = setOf(BusyKey.Row(row.occurrence))))
        val actions = mutableListOf<String>()
        compose.setContent { TaskTestTheme { TaskScreen(current.value, "today", onAction = { task, action -> actions.add("${task.plantId}:$action") }) } }
        compose.onNodeWithContentDescription("Saving Water for Synthetic fern, Synthetic site").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertDoesNotExist()
        compose.onNode(hasContentDescription("Snooze 2 days") and hasAnyAncestor(hasTestTag("task:plant-one:water"))).assertIsNotEnabled()
        compose.onNode(hasContentDescription("Snooze 2 days") and hasAnyAncestor(hasTestTag("task:plant-two:water"))).assertIsEnabled()
        compose.onNodeWithContentDescription("Handle all Water tasks").assertIsEnabled()
        compose.onNodeWithContentDescription("Mark Water done for Second fern, Synthetic site").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("plant-two:complete"), actions) }
        compose.runOnIdle { current.value = current.value.copy(pending = setOf(BusyKey.Card("water"))) }
        compose.onNodeWithContentDescription("Handle all Water tasks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsEnabled()
    }
    @Test fun actingOnTheSkippedOccurrenceLeavesTheSameTasksCompletionEnabled() {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        val list = org.json.JSONObject(taskListFixture("upcoming", "2030-06-09", event = ""","postponed":"skipped""""))
        val done = org.json.JSONObject(taskListFixture("upcoming", "2030-06-09", event = ""","postponed":null,"completed_event":$eventFixture"""))
            .getJSONArray("sections").getJSONObject(0).put("key", "2030-06-02").put("label", "2030-06-02")
        val upcoming = list.put("sections", org.json.JSONArray().put(done).put(list.getJSONArray("sections").getJSONObject(0))).toString()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            HeldServer(server, held = { it.method == "POST" }) { request -> okhttp3.mockwebserver.MockResponse().setBody(
                when (request.requestUrl!!.encodedPath) {
                    "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    "/api/tasks" -> if (request.requestUrl!!.queryParameter("view") == "upcoming") upcoming else taskListFixture("today")
                    "/api/plants" -> "[]"
                    "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    else -> """{"event":${org.json.JSONObject(eventFixture).put("id", "event-two")},"task":{"status":"due","dueOn":"2030-06-09","intervalDays":7,"lateDays":0,"basedOn":[]}}"""
                }) }.use { held ->
                val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
                val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, CareCache(db), { true })
                repository.refresh()
                val skipped = repository.state.value.upcoming!!.sections.flatMap { it.tasks }.single { it.completedEvent == null }
                val acting = held.submit { repository.act(skipped, "complete") }
                held.await { repository.state.value.pending.isNotEmpty() }
                val busy = repository.state.value
                compose.setContent { TaskTestTheme { TaskScreen(busy, "upcoming") } }
                compose.onAllNodesWithContentDescription("Saving Water for Synthetic fern, Synthetic site").assertCountEquals(1)
                compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo").assertIsEnabled()
                held.release()
                assertTrue(acting.get(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        db.close()
    }
    @Test fun offlineAndServerBannersBeforeAndAfterRefreshDisableActions() {
        val current = mutableStateOf(state(offline = true).copy(lastUpdated = null))
        compose.setContent { TaskTestTheme { TaskScreen(current.value, "today") } }
        compose.onNodeWithText("Offline, not updated yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsNotEnabled()
        compose.runOnIdle { current.value = state(offline = true) }
        compose.onNodeWithText(current.value.offlineBanner()).assertIsDisplayed()
        for (time in listOf(null, state().lastUpdated)) {
            compose.runOnIdle { current.value = state().copy(serverUnavailable = true, lastUpdated = time) }
            compose.onNodeWithText(current.value.serverBanner()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsNotEnabled()
            compose.onNode(hasText("Offline", substring = true)).assertDoesNotExist()
        }
    }
    @Test fun plantsLight() = plants(false)
    @Test fun plantsDark() = plants(true)
    private fun plants(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val plant = parsePlant(org.json.JSONObject(plantFixture))
        val detail = PlantDetail(plant, listOf(parseEvent(org.json.JSONObject(eventFixture))), emptyMap())
        compose.setContent { TaskTestTheme(dark) { CachedPlants(state().copy(plants = listOf(plant), detail = detail, offline = true), {}, null) } }
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onRoot().captureRoboImage("src/test/screenshots/cached-plants-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText(plant.displayName).performClick()
        compose.onNodeWithText("Care history").assertIsDisplayed()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onRoot().captureRoboImage("src/test/screenshots/plant-detail-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Back to plants").performClick()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun futureTaskHasNoSnoozeOrInlineDetails() {
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04", basis = """[{"input":"wateringIntervalDays","value":7,"source":"perenual","adjustment":"Species interval","fetchedAt":"2030-06-01T10:00:00Z"}]"""))
        compose.setContent { TaskTestTheme { TaskScreen(state().copy(upcoming = upcoming), "upcoming") } }
        compose.onNodeWithContentDescription("Snooze 2 days").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Synthetic fern").performScrollTo().performClick()
        compose.onNodeWithText("Based on").assertDoesNotExist()
        compose.onNodeWithText("Skip").assertDoesNotExist()
    }
    @Test fun provenanceShowsTheSourceAndFetchTime() {
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04", basis = """[{"input":"wateringIntervalDays","value":7,"source":"perenual","adjustment":"Species interval","fetchedAt":"2030-06-01T10:00:00Z"}]"""))
        compose.setContent { TaskTestTheme { Provenance(upcoming.sections.single().tasks.single().basedOn) } }
        compose.onNodeWithText("Based on").performClick()
        compose.onNodeWithText("Source: perenual · Fetched 2030-06-01T10:00:00Z").assertIsDisplayed()
    }
}
