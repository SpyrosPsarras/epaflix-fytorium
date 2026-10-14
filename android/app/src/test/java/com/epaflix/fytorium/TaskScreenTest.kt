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
            onAction = { task, action, _ -> if (action == "complete") completed = task.plantId }) } }
        compose.onNodeWithTag("task-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val fab = compose.onNodeWithContentDescription("Add plant").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("task-list").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("task:plant-7:water").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The list viewport must continue behind the FAB", viewport.bottom > fab.bottom)
        assertTrue("The last row must scroll fully above the FAB", last.bottom < fab.top)
        compose.onNodeWithContentDescription("Mark Water for Fern 7 done")
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals("plant-7", completed); assertEquals(0, added) }
    }
    @Test fun todayAndUpcomingLight() = screens(false)
    @Test fun todayAndUpcomingDark() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val current = mutableStateOf(state())
        val view = mutableStateOf("today")
        compose.setContent { TaskTestTheme(dark) { TaskScreen(current.value, view.value, now = java.time.Instant.parse("2030-06-02T18:00:00Z")) } }
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
        compose.onNodeWithContentDescription("Mark Water for Synthetic fern done").assertIsNotEnabled()
        capture("offline")
    }
    @Test fun sitterLightVariants() = sitterVariants(false)
    @Test fun sitterDarkVariants() = sitterVariants(true)
    private fun sitterVariants(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val current = mutableStateOf(state(sitter = true))
        val view = mutableStateOf("today")
        val own = parseEvent(org.json.JSONObject(eventFixture))
        compose.setContent { TaskTestTheme(dark) { TaskScreen(current.value, view.value, now = java.time.Instant.parse("2030-06-02T18:00:00Z")) } }
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
            compose.onNodeWithText("Undo").assertDoesNotExist()
            event(own.copy(taskType = "repot", previousPot = true, canUndo = false))
            compose.onNodeWithText("Undo").assertDoesNotExist()
            event(own)
            compose.onNodeWithText("Undo").assertHasClickAction().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("Task view settings").assertDoesNotExist()
            compose.onNode(hasText("Synthetic site", substring = true)).assertDoesNotExist()
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/sitter-$screen-${if (dark) "dark" else "light"}.png")
            compose.runOnIdle { current.value = current.value.copy(offline = true) }
            compose.onNodeWithText("Undo").assertIsNotEnabled()
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        }
    }
    @Test fun todayRepotOwnsLoadingAndErrorsAndRetainsEditedFields() {
        val result = kotlinx.coroutines.CompletableDeferred<Plant>()
        val current = mutableStateOf(state())
        val task = state().today!!.sections.first().tasks.first().copy(taskType = "repot")
        compose.setContent { TaskTestTheme { RepotForm(task, current.value, { result.await() }, {}, {}) } }
        compose.onNodeWithText("Loading current pot…").assertExists()
        compose.onNodeWithText("Pot size in cm").assertIsNotEnabled()
        compose.onNodeWithText("Pot type: plastic").assertIsNotEnabled()
        compose.onNodeWithText("Save repot").assertIsNotEnabled()
        compose.runOnIdle { result.complete(parsePlant(org.json.JSONObject(plantFixture))) }
        compose.waitUntil { runCatching { compose.onNodeWithText("Pot size in cm").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Pot size in cm").performTextReplacement("23")
        compose.onNodeWithText("Pot type: plastic").performClick()
        compose.onNodeWithText("terracotta").performClick()
        compose.runOnIdle { current.value = current.value.copy(detail = PlantDetail(parsePlant(org.json.JSONObject(plantFixture)), emptyList(), emptyMap())) }
        compose.onNodeWithText("23").assertExists()
        compose.onNodeWithText("Pot type: terracotta").assertExists()
        compose.onNodeWithText("Save repot").assertIsEnabled()
    }
    @Test fun todayRepotFailedReadShowsErrorWithoutBlockingEditsOrPlants() {
        val result = kotlinx.coroutines.CompletableDeferred<Plant>()
        val task = state().today!!.sections.first().tasks.first().copy(taskType = "repot")
        compose.setContent { TaskTestTheme { RepotForm(task, state(), { result.await() }, {}, {}) } }
        compose.runOnIdle { result.completeExceptionally(java.io.IOException("Could not load the current pot")) }
        compose.waitUntil { runCatching { compose.onNodeWithText("Could not load the current pot").assertExists(); true }.getOrDefault(false) }
        compose.onNodeWithText("Pot size in cm").assertIsEnabled().performTextReplacement("20")
        compose.onNodeWithText("Save repot").assertIsEnabled()
    }
    @Test fun repotFormUsesExistingDetailsAndRejectsInvalidPotSize() {
        val list = parseTaskList(taskListFixture("today"))
        val repot = list.copy(sections = list.sections.map { it.copy(tasks = it.tasks.map { row -> row.copy(taskType = "repot") }) })
        val detail = PlantDetail(parsePlant(org.json.JSONObject(plantFixture)), emptyList(), emptyMap())
        compose.enableAccessibilityChecks()
        val care = state().copy(today = repot, detail = detail)
        compose.setContent { TaskTestTheme { TaskScreen(care, "today") } }
        compose.onNodeWithContentDescription("Task details for Synthetic fern").performScrollTo().performClick()
        compose.onNodeWithText("Repot with new pot and soil").performScrollTo().performClick()
        compose.onNodeWithText("Pot type: plastic").assertIsDisplayed()
        compose.onNodeWithText("Soil type: all purpose").assertIsDisplayed()
        compose.onNodeWithText("Pot size in cm").performTextReplacement("0")
        compose.onNodeWithText("Save repot").assertIsNotEnabled()
        compose.onNodeWithText("Pot size in cm").performTextReplacement("20")
        compose.onNodeWithText("Save repot").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun sitterNeverSeesSettingsOrPrivateBasisAndCannotUndoAnotherMembersCare() {
        compose.enableAccessibilityChecks()
        val list = state(sitter = true)
        compose.setContent { TaskTestTheme { TaskScreen(list, "today") } }
        compose.onNodeWithContentDescription("Task view settings").assertDoesNotExist()
        compose.onNodeWithText("Based on").assertDoesNotExist()
        compose.onNodeWithText("Repot with new pot and soil").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Water for Synthetic fern done").assertHasClickAction()
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
        compose.onNodeWithText("Show completed").assertIsOff().assertHasClickAction().performClick().assertIsOn()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        compose.onNodeWithText("Save task view").performClick()
        compose.runOnIdle { assertEquals(TaskSettings("plant", "outdoor", true), saved) }
        compose.onNodeWithText("Show completed").assertDoesNotExist()
    }
    @Test fun handleAllVisibilityMatrixAndUndoFollowTheServer() {
        val current = mutableStateOf(state())
        val view = mutableStateOf("today")
        compose.setContent { TaskTestTheme { TaskScreen(current.value, view.value, now = java.time.Instant.parse("2030-06-02T18:00:00Z")) } }
        compose.onNodeWithContentDescription("Task details for Synthetic fern").performClick()
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
            val handle = compose.onNodeWithText("Handle all tasks for Synthetic fern")
            if (role != Role.sitter && screen == "today" && !done) handle.performScrollTo().assertIsDisplayed() else handle.assertDoesNotExist()
            if (done && role != Role.sitter) compose.onNodeWithText("Undo").assertExists() else compose.onNodeWithText("Undo").assertDoesNotExist()
        }
        compose.runOnIdle { current.value = state().copy(handled = mapOf("plant-one:water" to event)) ; view.value = "today" }
        compose.onNodeWithText("Handle all tasks for Synthetic fern").assertDoesNotExist()
    }
    @Test fun offlineAndServerBannersBeforeAndAfterRefreshDisableActions() {
        val current = mutableStateOf(state(offline = true).copy(lastUpdated = null))
        compose.setContent { TaskTestTheme { TaskScreen(current.value, "today") } }
        compose.onNodeWithText("Offline, not updated yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Water for Synthetic fern done").assertIsNotEnabled()
        compose.runOnIdle { current.value = state(offline = true) }
        compose.onNodeWithText(current.value.offlineBanner()).assertIsDisplayed()
        for (time in listOf(null, state().lastUpdated)) {
            compose.runOnIdle { current.value = state().copy(serverUnavailable = true, lastUpdated = time) }
            compose.onNodeWithText(current.value.serverBanner()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Mark Water for Synthetic fern done").assertIsNotEnabled()
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
    @Test fun futureTaskHasNoSnoozeAndProvenanceIsReadable() {
        val upcoming = parseTaskList(taskListFixture("upcoming", "2030-06-04", basis = """[{"input":"wateringIntervalDays","value":7,"source":"perenual","adjustment":"Species interval","fetchedAt":"2030-06-01T10:00:00Z"}]"""))
        compose.setContent { TaskTestTheme { TaskScreen(state().copy(upcoming = upcoming), "upcoming") } }
        compose.onNodeWithContentDescription("Snooze 2 days").assertDoesNotExist()
        compose.onNodeWithContentDescription("Task details for Synthetic fern").performScrollTo().performClick()
        compose.onNodeWithText("Based on").performScrollTo().performClick()
        compose.onNodeWithText("Source: perenual · Fetched 2030-06-01T10:00:00Z").performScrollTo().assertIsDisplayed()
    }
}
