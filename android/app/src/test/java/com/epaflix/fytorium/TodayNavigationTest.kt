package com.epaflix.fytorium

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class TodayNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun todayBackKeepsScrollAndClearsThePlantRoute() = navigate("today", Role.owner)
    @Test fun upcomingBackKeepsViewAndScroll() = navigate("upcoming", Role.family)
    @Test fun sitterOpensOnlyTheTappedTaskAndReturnsToToday() = navigate("today", Role.sitter)

    @Test fun selectingPlantsWhileViewingATodayTaskOpensTheList() {
        val list = parseTaskList(taskListFixture("today"))
        val plant = parsePlant(JSONObject(plantFixture))
        val care = CareState(member = Member("synthetic-member", Role.owner), plants = listOf(plant), today = list,
            detail = PlantDetail(plant, emptyList(), emptyMap()))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care) } }
        compose.onNodeWithContentDescription("Open Synthetic fern").performClick()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        compose.onNodeWithText("Search plants").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Edit plant").assertDoesNotExist()
    }

    @Test fun openingEachOccurrenceOfOneTaskSelectsThatOccurrence() {
        val plant = parsePlant(JSONObject(plantFixture))
        val list = parseTaskList(taskListFixture("upcoming", "2030-06-09", event = ""","postponed":"skipped""""))
        val skipped = list.sections.single().tasks.single()
        val done = skipped.copy(postponed = null, completedEvent = parseEvent(JSONObject(eventFixture)))
        val upcoming = list.copy(sections = listOf(TaskSection("2030-06-02", "2030-06-02", listOf(done)), TaskSection("2030-06-09", "2030-06-09", listOf(skipped))))
        val care = CareState(member = Member("member-one", Role.owner), plants = listOf(plant), today = upcoming.copy(view = "today", sections = emptyList()),
            upcoming = upcoming, detail = PlantDetail(plant, emptyList(), emptyMap()))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care) } }
        compose.onNodeWithTag("view-upcoming").performClick()
        val card = hasAnyAncestor(hasTestTag("today-task:${plant.id}:water"))
        for ((index, completed) in listOf(1 to false, 0 to true)) {
            compose.onAllNodesWithContentDescription("Open ${plant.displayName}")[index].performScrollTo().performClick()
            compose.onNode(card and hasText(if (completed) "Undo" else "Skip")).assertExists()
            compose.onNode(card and hasText(if (completed) "Skip" else "Undo")).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
        }
    }

    @Test fun theOpenedOccurrenceStaysSelectedAfterItsCompletionAndARestart() = openedOccurrence(completedHere = true)
    @Test fun anOccurrenceCompletedElsewhereLeavesThePlantPageWithoutItsTask() = openedOccurrence(completedHere = false)

    private fun openedOccurrence(completedHere: Boolean) {
        val plant = parsePlant(JSONObject(plantFixture))
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val care = mutableStateOf(CareState(member = Member("member-one", Role.owner), plants = listOf(plant), today = list,
            upcoming = list.copy(view = "upcoming"), detail = PlantDetail(plant, emptyList(), emptyMap())))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithContentDescription("Open ${plant.displayName}").performClick()
        val block = hasAnyAncestor(hasTestTag("today-task:${plant.id}:water"))
        compose.onNode(block and hasText("Skip")).assertIsDisplayed()
        val event = parseEvent(JSONObject(eventFixture)).let { if (completedHere) it else it.copy(id = "event-other", byMember = "member-two") }
        val done = list.copy(sections = listOf(TaskSection("water", "Water", listOf(task.copy(completedEvent = event)))))
        // The server's rows arrive in the same state as the repository's moved selection, with no patched rows drawn in between.
        compose.runOnIdle { care.value = care.value.copy(today = done, upcoming = done.copy(view = "upcoming"),
            openedOccurrence = if (completedHere) event.id else task.occurrence) }
        if (completedHere) {
            compose.onNode(block and hasText("Undo")).assertIsDisplayed()
            // A restarted process keeps the server's rows and the saved selection, but not the repository's.
            compose.runOnIdle { care.value = care.value.copy(openedOccurrence = null) }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNode(block and hasText("Undo")).assertIsDisplayed()
        } else {
            compose.onNodeWithTag("today-task:${plant.id}:water").assertDoesNotExist()
            compose.onNodeWithText(plant.displayName).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).assertIsSelected()
        compose.onNode(hasContentDescription("Done for ${plant.displayName}", substring = true)).assertIsDisplayed()
    }

    @Test fun aRestartedProcessHandsTheOpenedOccurrenceToItsNewRepository() {
        val plant = parsePlant(JSONObject(plantFixture))
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val care = CareState(member = Member("member-one", Role.owner), plants = listOf(plant), today = list,
            upcoming = list.copy(view = "upcoming"), detail = PlantDetail(plant, emptyList(), emptyMap()))
        val database = androidx.room.Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), CareDatabase::class.java)
            .allowMainThreadQueries().build()
        fun controller(): CareController {
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val client = ApiClient("http://127.0.0.1:9", credentials) {}
            return CareController(org.robolectric.RuntimeEnvironment.getApplication(), credentials, Repository(client, CareCache(database), { false }), client)
        }
        val before = controller()
        val after = controller()
        val current = mutableStateOf(before)
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme { AppShell(true, false, careState = care, controller = current.value) } }
            compose.onNodeWithContentDescription("Open ${plant.displayName}").performClick()
            compose.runOnIdle { assertEquals(task.occurrence, before.repository.state.value.openedOccurrence) }
            compose.runOnIdle { current.value = after }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { assertEquals(task.occurrence, after.repository.state.value.openedOccurrence) }
        } finally { before.close(); after.close(); database.close() }
    }

    @Test fun ownerTaskSurvivesRestorationAndBackReturnsToToday() = navigate("today", Role.owner, restore = true)
    @Test fun sitterTaskSurvivesRestorationAndBackReturnsToToday() = navigate("today", Role.sitter, restore = true)
    @Test fun systemBackFromTodayTaskKeepsTodayScroll() = navigate("today", Role.owner, systemBack = true)
    @Test fun customUpcomingTaskKeepsItsIdentityAcrossRestoration() = navigate("upcoming", Role.family, restore = true, custom = true)
    @Test fun differentMemberStartsTodayAtTheTop() = navigate("today", Role.owner, changeMember = true)
    @Test fun removedTodayTaskStillReturnsToTodayWithScroll() = navigate("today", Role.owner, removeTask = true)
    @Test fun removedUpcomingTaskStillReturnsWithViewAndScroll() = navigate("upcoming", Role.sitter, systemBack = true, removeTask = true)

    private fun navigate(view: String, role: Role, restore: Boolean = false, systemBack: Boolean = false,
                         custom: Boolean = false, changeMember: Boolean = false, removeTask: Boolean = false) {
        val template = parseTaskList(taskListFixture("today"))
        val task = template.sections.single().tasks.single().let {
            if (custom) it.copy(taskType = null, customTaskId = "synthetic-custom", customTaskName = "Rotate") else it
        }
        val plants = (1..18).map { index -> parsePlant(JSONObject(plantFixture)).copy(id = "synthetic-$index", displayName = "Synthetic plant $index") }
        val tasks = plants.map { task.copy(plantId = it.id, plantName = it.displayName) }
        val list = template.copy(sections = listOf(TaskSection("water", "Water", tasks)))
        val plant = if (removeTask) plants[14] else plants.last()
        val care = mutableStateOf(CareState(member = Member("synthetic-member", role), plants = plants, today = list,
            upcoming = list.copy(view = "upcoming"), detail = PlantDetail(plant, emptyList(), emptyMap())))
        val restoration = StateRestorationTester(compose)
        var backDispatcher: OnBackPressedDispatcher? = null
        restoration.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            FytoriumTheme { AppShell(true, false, careState = care.value) }
        }
        if (view == "upcoming") compose.onNodeWithTag("view-upcoming").performClick()
        compose.onNodeWithContentDescription("Open ${plant.displayName}").performScrollTo()
        val scroll = compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(scroll > 0f)
        compose.onNodeWithContentDescription("Open ${plant.displayName}").performClick()
        compose.onNodeWithText("Skip").assertIsDisplayed().assertIsEnabled()
        if (restore) restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Skip").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("today-task:${plant.id}:${if (custom) "synthetic-custom" else "water"}").assertIsDisplayed()
        if (custom) compose.onNodeWithText("Rotate").assertIsDisplayed()
        compose.onNodeWithTag("task-list").assertDoesNotExist()
        if (role == Role.sitter) {
            compose.onNodeWithText("Edit plant").assertDoesNotExist()
            compose.onNodeWithText("Care history").assertDoesNotExist()
        }
        if (removeTask) {
            compose.runOnIdle {
                val remaining = list.copy(sections = listOf(TaskSection("water", "Water", tasks.filter { it.plantId != plant.id })))
                care.value = care.value.copy(today = remaining, upcoming = remaining.copy(view = "upcoming"))
            }
            compose.onNodeWithTag("today-task:${plant.id}:${task.key}").assertDoesNotExist()
            compose.onNodeWithText("Skip").assertDoesNotExist()
            compose.onNodeWithText(plant.displayName).assertIsDisplayed()
        }
        if (systemBack) compose.runOnIdle { checkNotNull(backDispatcher).onBackPressed() }
        else compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).assertIsSelected()
        compose.onNodeWithTag("view-$view").assertIsSelected()
        assertEquals(scroll, compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        if (removeTask) compose.onNodeWithContentDescription("Open ${plant.displayName}").assertDoesNotExist()
        else compose.onNodeWithContentDescription("Open ${plant.displayName}").assertIsDisplayed()
        if (changeMember) {
            compose.runOnIdle { care.value = care.value.copy(member = Member("different-member", role)) }
            assertEquals(0f, compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
        }
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        compose.onNodeWithText("Search plants").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Edit plant").assertDoesNotExist()
    }
}
