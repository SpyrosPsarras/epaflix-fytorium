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
