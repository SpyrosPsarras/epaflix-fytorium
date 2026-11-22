package com.epaflix.fytorium.household

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import com.epaflix.fytorium.*
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
class CustomTasksScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun plant(id: String, name: String, site: String, status: String = "active") = Plant(id, name, "Testoria lucida", status, site, null, null, null, null, "{}")
    private val monty = plant("plant-monty", "Synthetic monty", "Synthetic guestroom")
    private val fern = plant("plant-fern", "Synthetic fernando", "Synthetic bathroom")
    private val aloe = plant("plant-aloe", "Synthetic old aloe", "Synthetic garden", "graveyard")
    private val care = CareState(member = Member("member-one", Role.owner), plants = listOf(monty, fern, aloe),
        today = parseTaskList(taskListFixture("today")))
    private val tasks = listOf(
        HouseholdTask("task-mist", "Mist leaves", 7, monty.id, null, "Synthetic monty", "Synthetic guestroom", "active", null),
        HouseholdTask("task-wipe", "Wipe windowsill", 14, null, "site-kitchen", "Synthetic kitchen", null, null, 3),
        HouseholdTask("task-rot", "Check for rot", 1, aloe.id, null, "Synthetic old aloe", "Synthetic garden", "graveyard", null))
    private val loaded = TasksState(tasks, listOf(TaskSite("site-kitchen", "Synthetic kitchen", 3)), loaded = true)
    private fun firstDue(days: Long) = shortDate(java.time.LocalDate.parse(care.today!!.today!!).plusDays(days).toString())

    // W5b-P15: one list, sites first, then active plants, then graveyard plants, each read as a sentence.
    @Test fun theListGroupsTasksBySiteAndPlant() {
        compose.setContent { FytoriumTheme { CustomTasksScreen(loaded, care) } }
        val kitchen = compose.onNodeWithText("Synthetic kitchen").fetchSemanticsNode().boundsInRoot.top
        val monty = compose.onNodeWithText("Synthetic monty").fetchSemanticsNode().boundsInRoot.top
        val aloe = compose.onNodeWithText("Synthetic old aloe").performScrollTo().fetchSemanticsNode().boundsInRoot.top
        assertTrue("Sites, then active plants, then the graveyard", kitchen < monty && monty < aloe)
        compose.onNodeWithText("Site · every plant here, now 3").assertExists()
        compose.onNodeWithText("Plant in Synthetic guestroom").assertExists()
        compose.onNodeWithText("In the graveyard. Its tasks pause until it is restored.").assertExists()
        compose.onNodeWithText("Every 14 days").assertExists()
        compose.onNodeWithText("Every day").assertExists()
        for (gone in listOf("Target", "Refresh custom tasks", "Interval in days")) compose.onNodeWithText(gone, substring = true).assertDoesNotExist()
    }

    // W5b-P16, P17: the picker offers sites and active plants only; the form checks before sending and shows the first due date.
    @Test fun addingPicksATargetChecksBeforeSendingAndShowsTheFirstDueDate() {
        val receipts = mutableListOf<String>()
        val sent = mutableListOf<Triple<TaskFor, String, Int>>()
        compose.setContent { FytoriumTheme { CustomTasksScreen(loaded, care, onReceipt = { receipts += it },
            onSave = { id, target, name, days -> assertNull(id); sent += Triple(target, name, days); true }) } }
        compose.onNodeWithText("Add task").performClick()
        compose.onNodeWithText("For which plant or site?").assertIsDisplayed()
        compose.onNodeWithText("Synthetic old aloe").assertDoesNotExist()
        compose.onNodeWithText("Search plants and sites").performTextInput("fern")
        compose.onNodeWithText("Synthetic monty").assertDoesNotExist()
        compose.onNodeWithText("Synthetic fernando").performClick()
        compose.onNodeWithText("For Synthetic fernando · Synthetic bathroom").assertIsDisplayed()
        compose.onNodeWithText("1 to 365 days").assertIsDisplayed()
        compose.onNodeWithText("Repeat every").performTextInput("0")
        compose.onNodeWithText("Save task").performScrollTo().performClick()
        compose.onNodeWithText("Enter whole days from 1 to 365.").assertIsDisplayed()
        compose.onNodeWithText("Enter a name from 1 to 60 characters.").assertIsDisplayed()
        compose.onNodeWithText("Repeat every").performTextReplacement("366")
        compose.onNodeWithText("Save task").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithText("Repeat every").performTextReplacement("7")
        compose.onNodeWithText("First due ${firstDue(7)}").assertIsDisplayed()
        compose.onNodeWithText("Task name").performTextInput("Test spray")
        compose.onNodeWithText("Save task").performScrollTo().performClick()
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        assertEquals(listOf(Triple(TaskFor("plant", fern.id, fern.displayName, fern.siteName), "Test spray", 7)), sent)
        assertEquals(listOf("Added Test spray for Synthetic fernando"), receipts)
        // A site task names every plant there, now and later.
        compose.onNodeWithText("Add task").performClick()
        compose.onNodeWithText("Synthetic kitchen").performClick()
        compose.onNodeWithText("For every plant in Synthetic kitchen, also plants added later").assertIsDisplayed()
        compose.onNodeWithText("Repeat every").performTextInput("14")
        compose.onNodeWithText("First due ${firstDue(14)} for the plants there now.").assertIsDisplayed()
    }

    // Sol W5b-S6: Back from Change returns to the task being typed, which keeps its text.
    @Test fun backFromChangeReturnsToTheForm() {
        compose.setContent { FytoriumTheme { CustomTasksScreen(loaded, care) } }
        compose.onNodeWithText("Add task").performClick()
        compose.onNodeWithText("Synthetic fernando").performClick()
        compose.onNodeWithText("Task name").performTextInput("Test spray")
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithText("For which plant or site?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("New task").assertIsDisplayed()
        compose.onNodeWithText("Task name").assertTextContains("Test spray")
    }

    // W5b-P17, P19: edits keep their target; drafts survive recreation and ask before leaving; delete names the task and its target.
    @Test fun editingKeepsItsTargetAndDraftsAskBeforeLeaving() {
        val restoration = StateRestorationTester(compose)
        val receipts = mutableListOf<String>()
        restoration.setContent { FytoriumTheme { CustomTasksScreen(loaded, care, onReceipt = { receipts += it }, onSave = { _, _, _, _ -> true }, onDelete = { true }) } }
        compose.onNodeWithText("Mist leaves").performClick()
        compose.onNodeWithText("Edit task").assertIsDisplayed()
        compose.onNodeWithText("Change").assertDoesNotExist()
        compose.onNodeWithText("Task name").performTextReplacement("Mist all leaves")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Task name").assertTextContains("Mist all leaves")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard("Your changes to Mist leaves are not saved yet.")
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Delete task").performScrollTo().performClick()
        compose.onNodeWithText("Delete task?").assertIsDisplayed()
        compose.onNodeWithText("Synthetic monty stops getting Mist leaves. Its past care stays in history.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { receipts.isNotEmpty() }
        assertEquals(listOf("Deleted Mist leaves"), receipts)
        compose.onNodeWithText("Add task").assertIsDisplayed()
    }

    // W5b-P18: from a plant page the form opens for that plant, with no Change, and closes back to the page.
    @Test fun aPlantPagesTaskIsForThatPlant() {
        var backs = 0
        var sent: TaskFor? = null
        compose.setContent { FytoriumTheme { CustomTasksScreen(TasksState(), care, fixed = TaskFor("plant", monty.id, monty.displayName, monty.siteName),
            onBack = { backs++ }, onSave = { _, target, _, _ -> sent = target; true }) } }
        compose.onNodeWithText("For Synthetic monty · Synthetic guestroom").assertIsDisplayed()
        compose.onNodeWithText("Change").assertDoesNotExist()
        compose.onNodeWithText("Task name").performTextInput("Test prune")
        compose.onNodeWithText("Repeat every").performTextInput("14")
        compose.onNodeWithText("Save task").performScrollTo().performClick()
        compose.waitUntil(5_000) { backs == 1 }
        assertEquals(monty.id, sent?.id)
    }

    // W5b-P27: the last list reads offline; writes explain.
    @Test fun offlineTasksReadTheLastCopyAndWritesExplain() {
        val cache = mutableMapOf<String, Pair<String, Long>>()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(when (request.path) {
                    "/api/custom-tasks" -> """[{"id":"task-mist","name":"Mist leaves","interval_days":7,"plant_id":"plant-monty","site_id":null,"target_name":"Synthetic monty","target_site_name":"Synthetic guestroom","target_status":"active","target_plant_count":null}]"""
                    else -> "[]"
                })
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val online = mutableStateOf(true)
            val shown = mutableStateOf(true)
            val receipts = mutableListOf<String>()
            compose.setContent { FytoriumTheme { if (shown.value) CustomTasksRoute(api, care, { online.value }, {}, onReceipt = { receipts += it },
                cached = { cache[it] }, keep = { key, json -> 1_000L.also { cache[key] = json to it } }) } }
            compose.waitUntil(5_000) { "custom-tasks" in cache }
            compose.runOnIdle { shown.value = false; online.value = false }
            compose.runOnIdle { shown.value = true }
            // The offline copy is read off the main thread, so wait for it.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Mist leaves").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Mist leaves").assertIsDisplayed()
            compose.onNode(hasText("Offline", substring = true) and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("Add task").assert(hasStateDescription(tasksOffline)).performClick()
            compose.runOnIdle { assertEquals(listOf(tasksOffline), receipts) }
        }
    }

    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val online = mutableStateOf(true)
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CustomTasksScreen(loaded, care, online.value)
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/a09-$name-${if (dark) "dark" else "light"}.png"
            if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.captureDialog(file) else compose.onRoot().captureRoboImage(file)
        }
        capture("tasks")
        compose.onNodeWithText("Mist leaves").performClick()
        capture("task-edit")
        compose.onNodeWithText("Delete task").performScrollTo().performClick()
        capture("task-delete")
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Save task").performScrollTo().assert(hasStateDescription(tasksOffline))
        capture("offline")
        compose.runOnIdle { online.value = true }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add task").performClick()
        capture("task-pick")
        compose.onNodeWithText("Synthetic kitchen").performClick()
        capture("task-create")
    }

    @Test fun tasksIgnoreCareBusyOfflineAndServerUnavailable() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody("[]")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val busy = mutableStateOf(CareState(member = Member("synthetic", Role.owner), busy = true, offline = true, serverUnavailable = true))
            compose.setContent { FytoriumTheme { CustomTasksRoute(api, busy.value, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No custom tasks yet. Add one for a plant or for every plant on a site.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add task").assertIsEnabled().assert(!hasStateDescription(tasksOffline))
            compose.runOnIdle { busy.value = busy.value.copy(updateRequired = true) }
            compose.onNodeWithText("Add task").assert(hasStateDescription(tasksOffline))
        }
    }

    @Test fun sittersHaveNoHouseholdScreensAndFamilyHasNoMembers() {
        val role = mutableStateOf(Role.owner)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("synthetic", role.value))) } }
        compose.onNode(isTab("More")).performClick()
        compose.onNodeWithText("Members").assertIsDisplayed()
        compose.runOnIdle { role.value = Role.family }
        compose.onNodeWithText("Members").assertDoesNotExist()
        compose.onNodeWithText("Weather and feed").assertIsDisplayed()
        compose.onNodeWithText("Custom tasks").assertIsDisplayed()
        compose.runOnIdle { role.value = Role.sitter }
        compose.onNodeWithText("Weather and feed").assertDoesNotExist()
        compose.onNodeWithText("Custom tasks").assertDoesNotExist()
    }
}
