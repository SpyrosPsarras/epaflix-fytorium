package com.epaflix.fytorium

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.epaflix.fytorium.plants.*
import com.epaflix.fytorium.species.*
import kotlinx.coroutines.ThreadContextElement
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class PlantPageTest {
    // Counts screen coroutines running off the main thread, because the test body itself runs on main.
    private object Running : ThreadContextElement<Boolean>, CoroutineContext.Key<Running> {
        val count = AtomicInteger()
        override val key get() = this
        override fun updateThreadContext(context: CoroutineContext) = (Looper.myLooper() != Looper.getMainLooper()).also { if (it) count.incrementAndGet() }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) { if (oldState) count.decrementAndGet() }
    }
    @get:Rule val compose = createComposeRule(Running)
    private val plant = parsePlant(JSONObject(plantFixture))
    private val owner = CareState(member = Member("member-one", Role.owner), plants = listOf(plant))
    private val today = parseTaskList(taskListFixture("today"))
    private fun schedule() = parseSchedule(JSONObject(scheduleFixture))
    private fun detail(history: List<CareEvent> = emptyList(), changes: String = "[]") =
        PlantDetail(plant, history, schedule(), """{"customIntervals":{"fertilize":9}}""", changes)
    private fun event(id: String, vararg fields: Pair<String, Any>) =
        parseEvent(JSONObject(eventFixture).put("id", id).apply { fields.forEach { (key, value) -> put(key, value) } })
    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (runCatching { compose.onNode(matcher).assertIsDisplayed() }.isFailure)
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
        return compose.onNode(matcher)
    }
    private fun top(matcher: SemanticsMatcher) = reveal(matcher).fetchSemanticsNode().boundsInRoot.top
    private fun keyboardClass(label: String): Int {
        reveal(hasText(label) and hasSetTextAction()).performClick()
        val layout = compose.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().layoutInfo
        val view = layout.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layout) as android.view.View
        val info = EditorInfo()
        compose.runOnIdle { view.onCreateInputConnection(info) }
        return info.inputType and InputType.TYPE_MASK_CLASS
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun plantsListIsAPhotoGridGroupedBySite() {
        val plants = listOf(plant.copy(id = "plant-west", displayName = "Synthetic aloe", siteName = "Synthetic west"),
            plant.copy(siteName = "Synthetic east"), plant.copy(id = "plant-ivy", displayName = "Synthetic ivy", siteName = "Synthetic east"))
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(plants = plants, today = today), null) } }
        val east = top(hasText("Synthetic east") and isHeading())
        val west = top(hasText("Synthetic west") and isHeading())
        assertTrue("sites are ordered by name", east < west)
        val fern = reveal(hasTestTag("plant:plant-one")).fetchSemanticsNode().boundsInRoot
        val ivy = reveal(hasTestTag("plant:plant-ivy")).fetchSemanticsNode().boundsInRoot
        assertEquals("two columns", fern.top, ivy.top, 1f)
        assertTrue(ivy.left > fern.right - 1f)
        val cover = compose.onNode(hasAnyAncestor(hasTestTag("plant:plant-one")) and hasContentDescription("No cover photo for Synthetic fern"), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("full-width square cover", fern.width, cover.width, 1f)
        assertEquals("square cover", cover.width, cover.height, 1f)
        compose.onNodeWithTag("plant:plant-one").assert(hasText("1d late"))
        compose.onNodeWithTag("plant:plant-ivy").assert(!hasText("1d late"))
        compose.onAllNodes(hasText("Testoria lucida", substring = true)).assertCountEquals(0)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }

    @Config(qualifiers = "w320dp-h2400dp-mdpi")
    @Test fun theNarrowestPhoneStillShowsTwoColumns() {
        val plants = listOf(plant, plant.copy(id = "plant-ivy", displayName = "Synthetic ivy"))
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(plants = plants), null) } }
        val fern = compose.onNodeWithTag("plant:plant-one").fetchSemanticsNode().boundsInRoot
        val ivy = compose.onNodeWithTag("plant:plant-ivy").fetchSemanticsNode().boundsInRoot
        assertEquals("two columns", fern.top, ivy.top, 1f)
        assertTrue(ivy.left > fern.right - 1f)
    }

    @Test fun searchQuerySurvivesOpeningAPlantAndComingBack() {
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = today, detail = detail()), null) } }
        compose.onNode(hasText("Search plants") and hasSetTextAction()).performTextInput("fern")
        compose.onNodeWithTag("plant:plant-one").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Search plants") and hasSetTextAction()).assertTextContains("fern")
    }

    @Test fun searchFromTodayFocusesTheSearchField() {
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = owner.copy(today = today)) } }
        compose.onNodeWithContentDescription("Search plants").performClick()
        compose.onNode(hasText("Search plants") and hasSetTextAction()).assertIsFocused()
    }

    @Test fun plantsTopBarHasAddAndGraveyardWithoutDuplicatesOrRefresh() {
        compose.setContent { FytoriumTheme { PlantsScreen(owner, null) } }
        compose.onAllNodesWithText("Plants").assertCountEquals(1)
        compose.onNodeWithContentDescription("Add plant").assertIsDisplayed()
        compose.onNodeWithText("Refresh plants").assertDoesNotExist()
        compose.onNodeWithText("Graveyard").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Graveyard").assertIsDisplayed()
    }

    @Test fun emptyStatesSayWhatToDoNext() {
        val state = mutableStateOf(owner.copy(plants = emptyList()))
        compose.setContent { FytoriumTheme { PlantsScreen(state.value, null) } }
        compose.onNodeWithText("Add your first plant").assertIsDisplayed()
        compose.runOnIdle { state.value = owner }
        compose.onNode(hasText("Search plants") and hasSetTextAction()).performTextInput("zz")
        compose.onNodeWithText("No plant called \u201czz\u201d").assertIsDisplayed()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun plantPageLeadsWithPhotoAndHasCareRowsOnEveryRoute() {
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = today, detail = detail()), null, initialPlant = plant.id) } }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithContentDescription("No cover photo for Synthetic fern").fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, cover.width, 1f)
        assertEquals(cover.width * 3 / 4, cover.height, 1f)
        reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).assertIsEnabled()
        reveal(hasContentDescription("Snooze Water for Synthetic fern, Synthetic site by 2 days")).assertIsEnabled()
        reveal(hasText("1 day late")).assertIsDisplayed()
        reveal(hasText("Due in 5 days")).assertIsDisplayed()
        reveal(hasText("Not needed now: Seasonal cut-back")).assertIsDisplayed()
        reveal(hasText("Testoria lucida")).assertIsDisplayed()
        compose.onAllNodes(hasText("0d late", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Testoria lucida Inventor", substring = true)).assertCountEquals(0)
        assertTrue(cover.top < top(hasText("Care") and isHeading()))
        assertTrue(top(hasText("Care") and isHeading()) < top(hasText("History") and isHeading()))
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun careRowsActLikeTodayRowsAndNeverMove() {
        val acted = mutableListOf<Pair<String, String>>()
        val state = mutableStateOf(owner.copy(today = today, detail = detail()))
        compose.setContent { FytoriumTheme { PlantsScreen(state.value, null, initialPlant = plant.id, onTaskAction = { task, action -> acted += task.key to action }) } }
        val water = reveal(hasTestTag("task:plant-one:water")).fetchSemanticsNode().boundsInRoot
        val fertilize = reveal(hasTestTag("task:plant-one:fertilize")).fetchSemanticsNode().boundsInRoot
        reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).performClick()
        compose.onNodeWithContentDescription("More actions for Fertilize on Synthetic fern").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.runOnIdle { assertEquals(listOf("water" to "complete", "fertilize" to "skip"), acted) }
        val task = today.sections.single().tasks.single()
        val done = today.copy(sections = listOf(TaskSection("water", "Water", listOf(task.copy(completedEvent = event("event-one"))))))
        // This device's own writes say which occurrence each acted row became.
        compose.runOnIdle { state.value = state.value.copy(today = done, detail = detail(listOf(event("event-one"))), transitions = listOf(task.occurrence to "event-one")) }
        reveal(hasContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo")).assertIsEnabled()
        reveal(hasText("Done today")).assertIsDisplayed()
        assertEquals(water, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
        assertEquals(fertilize, compose.onNodeWithTag("task:plant-one:fertilize").fetchSemanticsNode().boundsInRoot)
        // After a skip the row offers its Undo, never a second Skip.
        val skipped = event("event-skip", "task_type" to "fertilize", "kind" to "skipped", "postponed_until" to "2030-06-16")
        compose.runOnIdle { state.value = state.value.copy(detail = detail(listOf(skipped, event("event-one"))),
            transitions = state.value.transitions + ("plant-one:fertilize:2030-06-07:null" to "event-skip")) }
        reveal(hasContentDescription("Undo fertilizing skip for Synthetic fern, Synthetic site")).assertIsEnabled()
        compose.onNodeWithContentDescription("More actions for Fertilize on Synthetic fern").assertDoesNotExist()
        assertEquals(fertilize, compose.onNodeWithTag("task:plant-one:fertilize").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(today = today, detail = detail(listOf(skipped)), transitions = state.value.transitions + ("event-one" to task.occurrence)) }
        reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).assertIsEnabled()
        assertEquals(water, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun thePageIsHeadedByTheFullNameUnderTheCover() {
        val long = "Synthetic fern from the north window with a nickname too long for one line"
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail().copy(plant = plant.copy(displayName = long)), {}, { _, _ -> }, {}, {}) } }
        val cover = compose.onNodeWithContentDescription("No cover photo for $long").fetchSemanticsNode().boundsInRoot
        val heading = compose.onAllNodes(hasText(long) and isHeading()).fetchSemanticsNodes().single { it.boundsInRoot.top >= cover.bottom }
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        heading.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        assertTrue("wraps", layout.single().lineCount > 1)
        assertFalse("never cut", layout.single().hasVisualOverflow)
        assertTrue(heading.boundsInRoot.bottom <= top(hasText("Testoria lucida")))
        assertTrue(top(hasText("Testoria lucida")) < top(hasText("Synthetic site")))
    }

    @Test fun withoutTheServersDateRowsShowNoActionsOrStatus() {
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, detail(), {}, { _, _ -> }, {}, {}) } }
        reveal(hasTestTag("task:plant-one:water")).assertExists()
        for (control in listOf("Mark", "Snooze", "More actions")) compose.onAllNodes(hasContentDescription(control, substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("late", substring = true) or hasText("Due", substring = true)).assertCountEquals(0)
    }

    @Test fun blockedWritesNameARealReasonOrNone() {
        fun changes(state: CareState, online: Boolean = true) = state.needs("Changes", "make changes", verb = "need", online = online)
        assertNull(changes(CareState()))
        assertNull(changes(owner))
        assertEquals("Changes need a connection.", changes(owner, online = false))
        assertEquals("Changes need a connection.", changes(owner.copy(offline = true)))
        assertEquals("Changes need Fytorium to respond.", changes(owner.copy(serverUnavailable = true)))
        assertEquals("Update Fytorium to make changes.", changes(owner.copy(updateRequired = true)))
    }

    @Test fun careControlsNameThePlantAndTheTask() {
        val schedule = JSONObject(scheduleFixture).put("repot", JSONObject("""{"status":"due","dueOn":"2030-06-01","intervalDays":365,"lateDays":1,"postponed":null,"basedOn":[]}"""))
        val history = listOf(event("event-one", "occurred_on" to "2030-05-20"))
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), PlantDetail(plant, history, parseSchedule(schedule)), {}, { _, _ -> }, {}, {}) } }
        reveal(hasContentDescription("Handle all Synthetic fern tasks: Water, Repot")).assertIsEnabled()
        reveal(hasTestTag("undo:event-one")).assert(hasContentDescription("Undo watering for Synthetic fern"))
        reveal(hasContentDescription("More actions for Repot on Synthetic fern")).performClick()
        compose.onNodeWithContentDescription("Skip Repot for Synthetic fern").assertExists()
        compose.onNodeWithContentDescription("Repot Synthetic fern with new pot and soil").assertExists()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anotherMembersCompletionWaitsInPlaceUntilThePageIsShownAgain() {
        val showing = mutableStateOf(true)
        val state = mutableStateOf(owner.copy(today = today, detail = detail()))
        val explained = mutableListOf<String>()
        compose.setContent { FytoriumTheme { if (showing.value) PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, {}, onBlocked = { explained += it }) } }
        val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
        val water = reveal(hasTestTag("task:plant-one:water")).fetchSemanticsNode().boundsInRoot
        val theirs = event("event-theirs", "by_member" to "member-two", "by_name" to "Synthetic partner", "can_undo" to false)
        val pending = today.sections.single().tasks.single()
        val shown = today.copy(settings = today.settings.copy(showCompleted = true),
            sections = listOf(TaskSection("water", "Water", listOf(pending.copy(completedEvent = theirs)))))
        compose.runOnIdle { state.value = state.value.copy(today = shown) }
        reveal(mark).assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("This task changed elsewhere. Pull to refresh."), explained) }
        compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site").assertDoesNotExist()
        assertEquals(water, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { showing.value = false }
        compose.runOnIdle { showing.value = true }
        reveal(hasContentDescription("Water done for Synthetic fern, Synthetic site")).assertExists()
        compose.onNode(mark).assertDoesNotExist()
    }

    @Test fun theEndOfAPullShowsWhatChangedElsewhere() {
        val pulling = mutableStateOf(false)
        val state = mutableStateOf(owner.copy(today = today, detail = detail()))
        compose.setContent { FytoriumTheme { PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, {}, refreshing = pulling.value) } }
        val theirs = event("event-theirs", "by_member" to "member-two", "by_name" to "Synthetic partner", "can_undo" to false)
        val done = today.copy(sections = listOf(TaskSection("water", "Water", listOf(today.sections.single().tasks.single().copy(completedEvent = theirs)))))
        compose.runOnIdle { pulling.value = true; state.value = state.value.copy(today = done) }
        reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).assertIsNotEnabled()
        compose.runOnIdle { pulling.value = false }
        reveal(hasContentDescription("Water done for Synthetic fern, Synthetic site")).assertExists()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aLongerNameOrSiteFromALoadWaitsForAPull() = namesWaitForAPull(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aLongerNameOrSiteFromALoadWaitsForAPullAtLargeText() = namesWaitForAPull(1.3f)
    private fun namesWaitForAPull(fontScale: Float) {
        val pulling = mutableStateOf(false)
        val state = mutableStateOf(owner.copy(today = today, detail = detail()))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
            PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, {}, refreshing = pulling.value) } } }
        reveal(hasText("Details")).performClick()
        val water = reveal(hasTestTag("task:plant-one:water")).fetchSemanticsNode().boundsInRoot
        val name = "Synthetic fern from the north window with a nickname too long for one line"
        val site = "Synthetic sunroom on the second floor behind the long curtain"
        val species = "Testoria nova Inventor with a longer author citation"
        compose.runOnIdle { state.value = state.value.copy(detail = detail().copy(plant = plant.copy(displayName = name, siteName = site, scientificName = species))) }
        assertEquals(water, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
        compose.onAllNodes(hasText(name) or hasText(site) or hasText(species)).assertCountEquals(0)
        compose.onNodeWithText("Testoria lucida Inventor").assertExists()
        compose.runOnIdle { pulling.value = true }
        compose.runOnIdle { pulling.value = false }
        compose.onAllNodes(hasText(name) and isHeading()).assertCountEquals(2)
        compose.onNodeWithText(site).assertIsDisplayed()
        compose.onNodeWithText(species).assertExists()
        assertTrue("the longer header would have pushed the rows down", compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot.top > water.top)
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aPlantMovedToTheGraveyardElsewhereKeepsItsRowsInPlaceAndDisabledUntilAPull() {
        val pulling = mutableStateOf(false)
        val explained = mutableListOf<String>()
        val state = mutableStateOf(owner.copy(today = today, detail = detail()))
        compose.setContent { FytoriumTheme { PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, {}, onBlocked = { explained += it }, refreshing = pulling.value) } }
        val water = reveal(hasTestTag("task:plant-one:water")).fetchSemanticsNode().boundsInRoot
        val buried = plant.copy(status = "graveyard", attributesJson = JSONObject(plant.attributesJson).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString())
        compose.runOnIdle { state.value = state.value.copy(detail = detail().copy(plant = buried)) }
        assertEquals(water, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
        compose.onAllNodes(hasText("graveyard", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Handle all tasks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("This task changed elsewhere. Pull to refresh."), explained) }
        compose.runOnIdle { pulling.value = true }
        compose.runOnIdle { pulling.value = false }
        compose.onNodeWithText("Care pauses in the graveyard. Restore Synthetic fern to bring its tasks back.").assertIsDisplayed()
        compose.onNodeWithText("In the graveyard since 2 Jun 2030").assertIsDisplayed()
        compose.onNodeWithTag("task:plant-one:water").assertDoesNotExist()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun renamedAndRemovedTasksWaitInPlaceUntilThePageIsShownAgain() {
        fun custom(id: String, name: String, due: String) =
            """"$id":{"status":"due","dueOn":"$due","intervalDays":10,"lateDays":0,"postponed":null,"basedOn":[],"custom_task_id":"$id","name":"$name"}"""
        fun withCustom(vararg tasks: String) = PlantDetail(plant, emptyList(), parseSchedule(JSONObject(scheduleFixture).put("customTasks", JSONObject("{${tasks.joinToString(",")}}"))))
        val showing = mutableStateOf(true)
        val state = mutableStateOf(owner.copy(today = today, detail = withCustom(custom("custom-mist", "Synthetic misting", "2030-06-04"),
            custom("custom-polish", "Synthetic polishing", "2030-06-05"))))
        compose.setContent { FytoriumTheme { if (showing.value) PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, {}) } }
        val mist = reveal(hasTestTag("task:plant-one:custom-mist")).fetchSemanticsNode().boundsInRoot
        val polish = reveal(hasTestTag("task:plant-one:custom-polish")).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(detail = withCustom(custom("custom-mist", "Synthetic spraying", "2030-06-04"))) }
        reveal(hasText("Synthetic misting")).assertIsDisplayed()
        compose.onNodeWithText("Synthetic spraying").assertDoesNotExist()
        assertEquals(mist, compose.onNodeWithTag("task:plant-one:custom-mist").fetchSemanticsNode().boundsInRoot)
        assertEquals(polish, compose.onNodeWithTag("task:plant-one:custom-polish").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Mark Synthetic misting done for Synthetic fern, Synthetic site").assertIsEnabled()
        compose.onNodeWithContentDescription("Mark Synthetic polishing done for Synthetic fern, Synthetic site").assertIsNotEnabled()
        compose.runOnIdle { showing.value = false }
        compose.runOnIdle { showing.value = true }
        reveal(hasText("Synthetic spraying")).assertIsDisplayed()
        compose.onNodeWithTag("task:plant-one:custom-polish").assertDoesNotExist()
    }

    @Test fun aCompletionTheListsDoNotShowStillReadsDoneOnThePlantPage() {
        // A location filter can hide the plant from Today; history still knows this member watered it today.
        val hidden = today.copy(sections = emptyList())
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = hidden, detail = detail(listOf(event("event-one")))), null, initialPlant = plant.id) } }
        reveal(hasContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo")).assertIsEnabled()
        reveal(hasText("Done today")).assertIsDisplayed()
    }

    @Test fun sitterPlantPageHasCareRowsButNoOwnerSections() {
        val sitter = owner.copy(member = Member("sitter-one", Role.sitter), today = today)
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantDetailScreen(sitter, PlantDetail(plant, emptyList(), schedule()), {}, { _, _ -> }, {}, {}) } }
        reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).assertIsEnabled()
        for (owned in listOf("History", "Photos", "About this plant")) compose.onNode(hasText(owned) and isHeading()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit plant").assertDoesNotExist()
        compose.onNodeWithContentDescription("Plant settings").assertDoesNotExist()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun historyIsOneTimelineGroupedByDay() {
        val history = listOf(
            event("watered", "occurred_on" to "2030-06-02", "occurred_at" to "2030-06-02T08:00:00.000Z"),
            event("snoozed", "task_type" to "fertilize", "kind" to "snoozed", "occurred_on" to "2030-06-01", "occurred_at" to "2030-06-01T09:00:00.000Z", "can_undo" to false),
            event("undone", "task_type" to "clean_leaves", "occurred_on" to "2030-05-27", "occurred_at" to "2030-05-27T10:00:00.000Z", "undone_at" to "2030-05-28T10:00:00.000Z", "can_undo" to false))
        val changes = """[{"changed_on":"2030-06-01","changed_at":"2030-06-01T12:00:00.000Z","previous_name":"Testoria prior","next_name":"Testoria lucida","by_name":"Synthetic member"}]"""
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(history, changes), {}, { _, _ -> }, {}, {}) } }
        val order = listOf(hasText("Today") and isHeading(), hasText("Watered \u00b7 Synthetic member"),
            hasText("Yesterday") and isHeading(), hasText("Species changed from Testoria prior to Testoria lucida \u00b7 Synthetic member"),
            hasText("Snoozed fertilizing \u00b7 Synthetic member"), hasText("Mon 27 May") and isHeading())
        val tops = order.map(::top)
        assertEquals(tops.sorted(), tops)
        reveal(hasText("Undone")).assertIsDisplayed()
        reveal(hasText("Undo watering")).assertIsEnabled()
        compose.onAllNodes(hasText("2030-", substring = true)).assertCountEquals(0)
    }

    @Test fun photoStripShowsTheLatestUpdatesWithAddAndSeeAll() {
        val commands = mutableListOf<String>()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail().copy(photoIds = listOf("photo-one", "photo-two")), {},
            { action, _ -> commands += action }, {}, {}) } }
        reveal(hasText("Photos") and isHeading())
        compose.onAllNodesWithContentDescription("Photo update", substring = true).assertCountEquals(2)
        reveal(hasText("Add photo")).performClick()
        reveal(hasText("See all")).performClick()
        compose.runOnIdle { assertEquals(listOf("photos", "photos"), commands) }
    }

    @Test fun aboutListsOnlySetFieldsAsSentences() {
        var edits = 0
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), { edits++ }, { _, _ -> }, {}, {}, options = syntheticOptions()) } }
        reveal(hasText("About this plant") and isHeading())
        for (sentence in listOf("14 cm plastic pot with drainage holes", "All purpose soil", "Small, young plant", "Liquid fertilizer")) reveal(hasText(sentence)).assertIsDisplayed()
        for (gone in listOf("Not set", "Nickname", "Cultivar", "Pot type:")) compose.onAllNodes(hasText(gone, substring = true)).assertCountEquals(0)
        reveal(hasText("Add details")).performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun technicalDetailsStayFoldedUntilAsked() {
        var speciesCare = 0
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {}, onSpeciesCare = { speciesCare++ }) } }
        compose.onAllNodes(hasText("GBIF key", substring = true)).assertCountEquals(0)
        compose.onNodeWithTag("basis:water").assertDoesNotExist()
        reveal(hasText("Details")).performClick()
        reveal(hasText("GBIF key 910001", substring = true)).assertIsDisplayed()
        reveal(hasText("Testoria lucida Inventor")).assertIsDisplayed()
        reveal(hasTestTag("basis:water")).assertExists()
        reveal(hasText("Care defaults for this species")).performClick()
        compose.runOnIdle { assertEquals(1, speciesCare) }
    }

    @Test fun movingToTheGraveyardAsksFirstAndTheTopBarEdits() {
        val commands = mutableListOf<String>()
        var edits = 0
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), { edits++ }, { action, _ -> commands += action }, {}, {}) } }
        compose.onNodeWithContentDescription("Edit plant").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        for (item in listOf("Change species", "This plant's schedule", "Repot", "Move to graveyard", "Delete plant")) compose.onNodeWithText(item).assertIsDisplayed()
        compose.onNodeWithText("Species care").assertDoesNotExist()
        compose.onNodeWithText("Move to graveyard").performClick()
        compose.onNodeWithText("Move Synthetic fern to the graveyard?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), commands) }
        compose.onNode(hasText("Move to graveyard") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf("graveyard"), commands) }
    }

    @Test fun loadingOnlineNeverClaimsNothingIsCached() {
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = today), null, initialPlant = "plant-two") } }
        compose.onNode(hasText("No cached details", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Loading plant\u2026").assertIsDisplayed()
    }

    @Test fun editFormLeadsWithNameAndSiteAndFoldsTheRest() {
        compose.setContent { FytoriumTheme { PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {}) } }
        val name = top(hasText("Name") and hasSetTextAction())
        assertTrue(name < top(hasText("Site")))
        compose.onNode(hasText("Cultivar") and hasSetTextAction()).assertDoesNotExist()
        reveal(hasText("More details")).performClick()
        reveal(hasText("Cultivar") and hasSetTextAction()).assertExists()
        assertEquals(InputType.TYPE_CLASS_NUMBER, keyboardClass("Pot size in cm"))
        assertEquals(InputType.TYPE_CLASS_NUMBER, keyboardClass("Window distance in cm"))
    }

    @Test fun birthdayPickerBlocksFutureDates() {
        val now = LocalDate.now(java.time.ZoneOffset.UTC)
        val tomorrow = now.plusDays(1)
        assumeTrue("the picker shows this month", tomorrow.month == now.month)
        compose.setContent { FytoriumTheme { PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {}) } }
        reveal(hasText("More details")).performClick()
        reveal(hasText("Birthday", substring = true) and hasClickAction()).performClick()
        // Material's day cells carry the spoken date as their text, for example "Thursday, November 12, 2026".
        fun day(date: LocalDate) = compose.onNode(hasText(date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US)), substring = true)
            and hasClickAction() and hasAnyAncestor(isDialog()))
        day(tomorrow).assertIsNotEnabled()
        day(now).assertIsEnabled()
    }

    @Test fun leavingTheEditFormWithChangesAsksFirst() = withPlantServer { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
        compose.waitUntil(5_000) { repo.state.value.detail != null }
        compose.waitUntil(5_000) { compose.onAllNodes((hasText("Edit plant") or hasContentDescription("Edit plant")) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Edit plant") or hasContentDescription("Edit plant")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("Synthetic renamed fern")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes to Synthetic fern?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Synthetic renamed fern").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNode(hasText("Name") and hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit plant").assertExists()
    }

    @Test fun plantScheduleShowsCurrentIntervalsWithOneSaveAtTheTop() {
        var saved: JSONObject? = null
        var speciesCare = 0
        compose.setContent { FytoriumTheme { Surface(Modifier.fillMaxSize()) { PlantCareSettings("""{"customIntervals":{"fertilize":9}}""", true, { saved = it },
            tasks = schedule(), plantName = "Synthetic fern", onSpeciesCare = { speciesCare++ }) } } }
        compose.onNodeWithText("This plant's schedule").assertIsDisplayed()
        reveal(hasText("Changes here apply only to Synthetic fern.", substring = true)).assertIsDisplayed()
        reveal(hasText("Every 7 days, from species care")).assertIsDisplayed()
        reveal(hasText("Every 9 days, your setting for this plant")).assertIsDisplayed()
        reveal(hasText("Not scheduled for this plant now")).assertIsDisplayed()
        val save = compose.onNode(hasText("Save") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top
        assertTrue(save < compose.onAllNodes(hasSetTextAction()).onFirst().fetchSemanticsNode().boundsInRoot.top)
        compose.onAllNodes(hasText("Save", substring = true) and hasClickAction()).assertCountEquals(1)
        assertEquals(InputType.TYPE_CLASS_NUMBER, keyboardClass("Water interval in days"))
        compose.onNode(hasText("Water interval in days") and hasSetTextAction()).performTextInput("5")
        compose.onNodeWithContentDescription("Clear Water interval for Synthetic fern").assertExists()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        compose.runOnIdle {
            val intervals = saved!!.getJSONObject("customIntervals")
            assertEquals(2, intervals.length())
            assertEquals(5, intervals.getInt("water"))
            assertEquals(9, intervals.getInt("fertilize"))
        }
        reveal(hasText("Care defaults for this species")).performClick()
        compose.runOnIdle { assertEquals(1, speciesCare) }
    }

    @Test fun speciesOverridesNeedNoJson() {
        val values = mutableListOf<Pair<String, Any>>()
        val card = JSONObject(careFixture).let { CareCard(910002, "Testoria nebulosa Inventor", it.getJSONObject("profile"), it.getJSONArray("fetches").mapObjects { row -> row }, JSONObject(outdoorFixture)) }
        compose.setContent { FytoriumTheme { SpeciesCareScreen(SpeciesCareState(card = card, loaded = true), onOverride = { field, value -> values += field to value; true }) } }
        compose.onNodeWithText("Care defaults for this species").assertIsDisplayed()
        reveal(hasText("every plant of Testoria nebulosa", substring = true)).assertIsDisplayed()
        reveal(hasText("Override temperature range in °C")).performClick()
        compose.onAllNodes(hasText("JSON", substring = true)).assertCountEquals(0)
        compose.onNode(hasText("Minimum") and hasSetTextAction()).performTextReplacement("15")
        compose.onNode(hasText("Maximum") and hasSetTextAction()).performTextReplacement("28")
        compose.onNodeWithText("Save override").performClick()
        reveal(hasText("Override pruning months")).performClick()
        compose.onNodeWithText("Mar").performClick()
        compose.onNodeWithText("Apr").performClick()
        compose.onNodeWithText("Save override").performClick()
        reveal(hasText("Override source care text")).performClick()
        compose.onNode(hasText("Care text") and hasSetTextAction()).performTextReplacement("Water when the top dries")
        compose.onNodeWithText("Save override").performClick()
        compose.runOnIdle {
            assertEquals("temperatureRangeC", values[0].first)
            assertEquals("""{"min":15,"max":28}""", values[0].second.toString())
            assertEquals("pruningMonths" to "[3,4]", values[1].first to values[1].second.toString())
            assertEquals("careText" to "Water when the top dries", values[2])
        }
    }

    @Test fun confirmationsNameTheirTarget() {
        val screen = mutableStateOf("restore")
        compose.setContent { FytoriumTheme { when (screen.value) {
            "restore" -> RestoreForm(plant.copy(status = "graveyard"), listOf(PlantSite("site-one", "Synthetic site")), true, {})
            "repot" -> PlantRepotForm(plant, syntheticOptions(), true, onSave = {})
            else -> PlantDetailScreen(owner, detail(), {}, { _, _ -> }, {}, {})
        } } }
        reveal(hasText("Restore Synthetic fern to Synthetic site")).assertIsEnabled()
        reveal(hasText("Create a site")).assertHasClickAction()
        compose.runOnIdle { screen.value = "repot" }
        reveal(hasText("Repot Synthetic fern")).assertIsEnabled()
        compose.runOnIdle { screen.value = "detail" }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Delete plant").performClick()
        compose.onNodeWithText("Permanently delete Synthetic fern?").assertIsDisplayed()
    }

    @Test fun movingThePlantToTheGraveyardHereAndUndoingItShowAtOnce() {
        val buried = AtomicBoolean(false)
        val graveyard = JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/graveyard" -> { buried.set(true); graveyard }
            "/api/plants/plant-one/restore" -> { buried.set(false); plantFixture }
            "/api/plants/plant-one" -> graveyard.takeIf { buried.get() }
            // The graveyard turns this member's older Undo off, and restoring the plant turns it on again.
            "/api/plants/plant-one/history" -> "[${JSONObject(eventFixture).put("occurred_on", "2030-06-01").put("can_undo", !buried.get())}]"
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasTestTag("task:plant-one:water")).assertExists()
            reveal(hasTestTag("undo:event-one")).assertIsEnabled()
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Move to graveyard").performClick()
            compose.onNode(hasText("Move to graveyard") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Care pauses", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task:plant-one:water").assertDoesNotExist()
            // This device's own move took the Undo away, so the entry drops it instead of saying it changed elsewhere.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("undo:event-one").fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasText("Undo") and hasClickAction()).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("task:plant-one:water").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Care pauses", substring = true).assertDoesNotExist()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-one") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun aBatchCheckOnAPlantTheListsHideReopensItsCountedReceipt() {
        fun batched(id: String, type: String, due: String) = JSONObject(eventFixture).put("id", id).put("task_type", type).put("due_on", due)
            .put("batch_id", "batch-all").put("batch_action", "complete-all").put("batch_plant_count", 1).put("batch_event_count", 2)
        val events = JSONArray(listOf(batched("event-water", "water", "2030-06-01"), batched("event-fertilize", "fertilize", "2030-06-02")))
        val after = JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-09").put("lateDays", 0); getJSONObject("fertilize").put("dueOn", "2030-06-11") }
        val hidden = JSONObject(taskListFixture("today")).put("settings", JSONObject("""{"sort":"task","filter":"outdoor","show_completed":false}""")).put("sections", JSONArray()).toString()
        val due = JSONObject(scheduleFixture).apply { getJSONObject("fertilize").put("dueOn", "2030-06-02") }.toString()
        val handled = AtomicBoolean(false)
        withPlantServer({ path -> when (path) {
            "/api/tasks" -> hidden
            "/api/plants/plant-one/tasks/complete-all" -> { handled.set(true); JSONObject().put("batch_id", "batch-all").put("events", events).put("tasks", after).toString() }
            "/api/plants/plant-one/history" -> events.toString().takeIf { handled.get() }
            "/api/plants/plant-one/tasks" -> if (handled.get()) after.toString() else due
            else -> null
        } }, lists = hidden) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Handle all tasks") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Handle all tasks")).performClick()
            val check = hasContentDescription("Water done for Synthetic fern, Synthetic site. Part of a batch", substring = true)
            compose.waitUntil(5_000) { compose.onAllNodes(check).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(check).assert(hasContentDescription("Water done for Synthetic fern, Synthetic site. Part of a batch of 2 tasks. Double tap to see the batch"))
            val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
            compose.waitUntil(5_000) { compose.onAllNodes(dismiss).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(dismiss).performSemanticsAction(SemanticsActions.Dismiss)
            compose.waitUntil(5_000) { repo.state.value.receipt == null }
            compose.onNode(check).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Handled 2 tasks for Synthetic fern").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Undo") and hasClickAction()).assertExists()
        }
    }

    @Test fun pullingThePlantPageReloadsTheListsAndThenThePlant() {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer(paths = paths) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("task:plant-one:water").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
            fun reloaded() = synchronized(paths) { paths.lastIndexOf("/api/meta").let { it > paths.indexOf("/api/tasks") && "/api/plants/plant-one/tasks" in paths.drop(it) } }
            compose.waitUntil(5_000) { reloaded() }
        }
    }

    @Test fun aListedRowANewerScheduleNoLongerHasWaitsForAPullAndNeverActsOnAnother() {
        val skipped = JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-03").put("lateDays", 0).put("postponed", "skipped") }.toString()
        val theirs = JSONObject(eventFixture).put("id", "event-theirs").put("kind", "skipped").put("by_member", "member-two").put("by_name", "Synthetic partner")
            .put("can_undo", false).put("postponed_until", "2030-06-03")
        val gone = JSONObject(taskListFixture("today")).put("sections", JSONArray()).toString()
        val acted = mutableListOf<String>()
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/tasks" -> skipped
            "/api/plants/plant-one/history" -> "[$theirs]"
            "/api/tasks" -> gone
            else -> null
        } }) { repo, showing ->
            val opened = repo.state.value.today!!.sections.single().tasks.single()
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, initialTask = care.currentTask(opened.occurrence), onTaskAction = { task, action -> acted += "${task.dueOn}:$action" }) } }
            compose.waitUntil(5_000) { repo.state.value.detail != null }
            val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
            reveal(mark).assertIsNotEnabled().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Handle all tasks")).assertIsNotEnabled()
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Skipped until Mon 3 Jun").fetchSemanticsNodes().isNotEmpty() }
            reveal(mark).assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf("2030-06-03:complete"), acted) }
        }
    }

    @Test fun aListedCompletionANewerHistoryShowsUndoneWaitsInPlaceAndSendsNoUndo() =
        aListedEventANewerHistoryShowsUndone(eventFixture, "Water done for Synthetic fern, Synthetic site")
    @Test fun aListedSnoozeANewerHistoryShowsUndoneWaitsInPlaceAndSendsNoUndo() = aListedEventANewerHistoryShowsUndone(
        JSONObject(eventFixture).put("kind", "snoozed").put("postponed_until", "2030-06-04").toString(), "Undo watering snooze for Synthetic fern, Synthetic site")

    private fun aListedEventANewerHistoryShowsUndone(listed: String, control: String) {
        val undone = JSONObject(listed).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        // Fertilize is due too, so Handle all would name only it while the server also handles the Water the Undo made due again.
        val due = JSONObject(scheduleFixture).apply { getJSONObject("fertilize").put("dueOn", "2030-06-02") }.toString()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> "[$undone]"
            "/api/plants/plant-one/tasks" -> due
            else -> null
        } }, lists = taskListFixture("today", event = ""","completed_event":$listed"""), paths = paths) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            // Both opening plant loads land first, so the row below reflects the newest history.
            compose.waitUntil(5_000) { synchronized(paths) { "/api/sites" in paths && paths.count { it == "/api/plants/plant-one/photos" } == 2 } }
            compose.waitUntil(5_000) { repo.state.value.detail?.history?.singleOrNull()?.undoneAt != null }
            val check = hasContentDescription(control, substring = true)
            compose.waitUntil(5_000) { compose.onAllNodes(check).fetchSemanticsNodes().isNotEmpty() }
            val changed = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, ChangedElsewhere)
            reveal(check).assertIsNotEnabled().assert(hasContentDescription(control)).assert(changed).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText(ChangedElsewhere)))
                .performSemanticsAction(SemanticsActions.Dismiss)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isEmpty() }
            reveal(hasText("Handle all tasks")).assertIsNotEnabled().assert(hasContentDescription("Handle all Synthetic fern tasks: Fertilize")).assert(changed).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals(emptyList<String>(), paths.filter { it.endsWith("/undo") || it.endsWith("/complete-all") }) }
        }
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun handleAllWaitsWhenTheDueTasksChangedElsewhereAndSaysWhy() {
        fun custom(id: String, name: String) =
            """"$id":{"status":"due","dueOn":"2030-06-02","intervalDays":10,"lateDays":0,"postponed":null,"basedOn":[],"custom_task_id":"$id","name":"$name"}"""
        fun page(vararg tasks: String) = PlantDetail(plant, emptyList(), parseSchedule(JSONObject(scheduleFixture).put("customTasks", JSONObject("{${tasks.joinToString(",")}}"))))
        val mist = custom("custom-mist", "Synthetic misting")
        val polish = custom("custom-polish", "Synthetic polishing")
        val buried = plant.copy(status = "graveyard", attributesJson = JSONObject(plant.attributesJson).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString())
        val showing = mutableStateOf(true)
        val state = mutableStateOf(owner.copy(today = today, detail = page(mist)))
        val explained = mutableListOf<String>()
        val commands = mutableListOf<String>()
        compose.setContent { FytoriumTheme { if (showing.value) PlantDetailScreen(state.value, state.value.detail!!, {}, { action, _ -> commands += action }, {}, {},
            onBlocked = { explained += it }) } }
        val handle = hasText("Handle all tasks")
        for ((change, next) in listOf("replaced" to page(polish), "added" to page(mist, polish), "graveyard" to page(mist).copy(plant = buried),
            "offline" to page(mist))) {
            compose.runOnIdle { showing.value = false; state.value = owner.copy(today = today, detail = page(mist)) }
            compose.runOnIdle { showing.value = true }
            val place = reveal(handle).assertIsEnabled().fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { state.value = state.value.copy(detail = next, offline = change == "offline") }
            reveal(handle).assertIsNotEnabled().performClick()
            assertEquals(change, place, compose.onNode(handle).fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { assertEquals(change, if (change == "offline") "Changes need a connection." else ChangedElsewhere, explained.removeLastOrNull()) }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), commands) }
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun historyChangedElsewhereWaitsForAPull() = historyWaitsForAPull(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun historyChangedElsewhereWaitsForAPullAtLargeText() = historyWaitsForAPull(1.3f)
    private fun historyWaitsForAPull(fontScale: Float) {
        val pulling = mutableStateOf(false)
        val explained = mutableListOf<String>()
        val undone = mutableListOf<String>()
        val top = event("event-top", "occurred_at" to "2030-06-02T10:00:00.000Z")
        val middle = event("event-middle", "task_type" to "fertilize", "occurred_at" to "2030-06-02T09:00:00.000Z")
        val state = mutableStateOf(owner.copy(today = today, detail = detail(listOf(top, middle))))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
            PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, { undone += it.id }, onBlocked = { explained += it }, refreshing = pulling.value) } } }
        val below = reveal(hasTestTag("undo:event-middle")).fetchSemanticsNode().boundsInRoot
        val theirs = event("event-theirs", "by_member" to "member-two", "by_name" to "Synthetic partner", "can_undo" to false, "occurred_at" to "2030-06-02T11:00:00.000Z")
        val undoneTop = event("event-top", "occurred_at" to "2030-06-02T10:00:00.000Z", "undone_at" to "2030-06-02T10:30:00.000Z", "can_undo" to false)
        compose.runOnIdle { state.value = state.value.copy(detail = detail(listOf(theirs, undoneTop, middle))) }
        assertEquals(below, compose.onNodeWithTag("undo:event-middle").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Watered \u00b7 Synthetic partner").assertDoesNotExist()
        compose.onNodeWithTag("undo:event-top").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("undo:event-middle").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf(ChangedElsewhere), explained); assertEquals(emptyList<String>(), undone) }
        compose.runOnIdle { pulling.value = true }
        compose.runOnIdle { pulling.value = false }
        reveal(hasText("Watered \u00b7 Synthetic partner")).assertIsDisplayed()
        compose.onNodeWithTag("undo:event-top").assertDoesNotExist()
        reveal(hasText("Undone")).assertIsDisplayed()
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun ownCareAndUndoShowInHistoryAtOnceWithoutMovingTheEntriesBelow() = ownHistory(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun ownCareAndUndoShowInHistoryAtOnceWithoutMovingTheEntriesBelowAtLargeText() = ownHistory(1.3f)
    private fun ownHistory(fontScale: Float) {
        val older = JSONObject(eventFixture).put("id", "event-older").put("task_type", "fertilize").put("occurred_on", "2030-06-01").put("occurred_at", "2030-06-01T09:00:00.000Z")
        val watered = JSONObject(eventFixture).put("occurred_at", "2030-06-02T10:00:00.000Z")
        val history = java.util.concurrent.atomic.AtomicReference("[$older]")
        val next = JSONObject(scheduleFixture).getJSONObject("water").put("dueOn", "2030-06-09").put("lateDays", 0)
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> history.get()
            "/api/plants/plant-one/tasks/water/complete" -> { history.set("[$watered,$older]"); JSONObject().put("event", watered).put("linked_events", JSONArray()).put("task", next).toString() }
            "/api/care-events/event-one/undo" -> {
                history.set("[${JSONObject(watered.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)},$older]")
                """{"event":$watered,"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}"""
            }
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
                val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-older") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-one") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val below = compose.onNodeWithTag("undo:event-older").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("undo:event-one").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Undone").fetchSemanticsNodes().isNotEmpty() }
            // The Undo's plant list read ends before the screen goes, so no write is still in flight then.
            compose.waitUntil(5_000) { repo.state.value.pending.isEmpty() }
            assertEquals(below, compose.onNodeWithTag("undo:event-older").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("undo:event-one").assertDoesNotExist()
        }
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun undoingARepotFromItsReceiptShowsTheEarlierPotAtOnce() = repotUndone("receipt")
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun undoingARepotFromHistoryShowsTheEarlierPotAtOnce() = repotUndone("history")
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun undoingARepotFromItsCareRowShowsTheEarlierPotAtOnce() = repotUndone("row")
    private fun repotUndone(from: String) {
        val repot = JSONObject(eventFixture).put("id", "event-repot").put("task_type", "repot").put("occurred_at", "2030-06-02T10:00:00.000Z")
            .put("previous_pot", JSONObject().put("pot_type", "plastic").put("pot_size_cm", 14).put("soil_type", "all_purpose"))
        val repotted = AtomicBoolean(false)
        val undone = AtomicBoolean(false)
        withPlantServer({ path -> when (path) {
            "/api/plants" -> "[${JSONObject(plantFixture).put("pot_type", "terracotta")}]".takeIf { repotted.get() }
            "/api/plants/plant-one" -> JSONObject(plantFixture).put("pot_type", "terracotta").toString().takeIf { repotted.get() }
            "/api/plants/plant-one/repot" -> { repotted.set(true); JSONObject().put("event", repot).toString() }
            "/api/plants/plant-one/history" -> when {
                undone.get() -> "[${JSONObject(repot.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)}]"
                repotted.get() -> "[$repot]"
                else -> null
            }
            "/api/care-events/event-repot/undo" -> {
                repotted.set(false); undone.set(true)
                """{"event":$repot,"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}"""
            }
            // A Repot task gives the repot its own Care row, whose check undoes it.
            "/api/plants/plant-one/tasks" -> JSONObject(scheduleFixture).put("repot", JSONObject("""{"status":"due","dueOn":"2031-06-02","intervalDays":365,"lateDays":0,"postponed":null,"basedOn":[]}"""))
                .toString().takeIf { from == "row" }
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNode(hasText("Repot") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Repot Synthetic fern") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Repot Synthetic fern").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("14 cm terracotta pot with drainage holes").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { repo.state.value.plants.single().potType == "terracotta" }
            when (from) {
                "history" -> reveal(hasTestTag("undo:event-repot")).performClick()
                "row" -> reveal(hasContentDescription("Repot done for Synthetic fern, Synthetic site. Double tap to undo")).performClick()
                else -> compose.onNode(hasText("Undo") and hasClickAction()).performClick()
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("14 cm plastic pot with drainage holes").fetchSemanticsNodes().isNotEmpty() }
            // The Plants list shows the earlier pot too, whichever route undid the repot.
            compose.waitUntil(5_000) { repo.state.value.plants.single().potType == "plastic" }
        }
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aTaskAddedElsewhereWaitsThroughAnOwnReceiptUndo() = addedElsewhereWaitsThroughAnOwnUndo(fromHistory = false)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aTaskAddedElsewhereWaitsThroughAnOwnHistoryUndo() = addedElsewhereWaitsThroughAnOwnUndo(fromHistory = true)
    private fun addedElsewhereWaitsThroughAnOwnUndo(fromHistory: Boolean) {
        val older = JSONObject(eventFixture).put("id", "event-older").put("task_type", "fertilize").put("occurred_on", "2030-06-01").put("occurred_at", "2030-06-01T09:00:00.000Z")
        val watered = JSONObject(eventFixture).put("occurred_at", "2030-06-02T10:00:00.000Z")
        fun undone(event: JSONObject) = JSONObject(event.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        val history = java.util.concurrent.atomic.AtomicReference("[$older]")
        val added = AtomicBoolean(false)
        val mist = """{"status":"due","dueOn":"2030-06-02","intervalDays":10,"lateDays":0,"postponed":null,"basedOn":[],"custom_task_id":"custom-mist","name":"Synthetic misting"}"""
        val restored = """[{"event_id":"event-one","occurrence":{"plant_id":"plant-one","task_type":"water","custom_task_id":null,"due_on":"2030-06-01"}}]"""
        val lists = """"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}"""
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/tasks" -> JSONObject(scheduleFixture).put("customTasks", JSONObject().put("custom-mist", JSONObject(mist))).toString().takeIf { added.get() }
            "/api/plants/plant-one/history" -> history.get()
            "/api/plants/plant-one/tasks/water/complete" -> { history.set("[$watered,$older]")
                JSONObject().put("event", watered).put("linked_events", JSONArray()).put("task", JSONObject(scheduleFixture).getJSONObject("water").put("dueOn", "2030-06-09").put("lateDays", 0)).toString() }
            "/api/care-events/event-one/undo" -> { history.set("[${undone(watered)},$older]"); """{"event":$watered,"restored":$restored,$lists}""" }
            "/api/care-events/event-older/undo" -> { history.set("[${undone(older)}]"); """{"event":$older,"restored":[],$lists}""" }
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-older") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val places = listOf(hasTestTag("task:plant-one:water"), hasTestTag("task:plant-one:fertilize"), hasText("History") and isHeading())
            val before = places.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot }
            // Another member adds a due custom task; the own write's or Undo's reload brings it.
            added.set(true)
            if (fromHistory) compose.onNodeWithTag("undo:event-older").performClick()
            else {
                compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Undo") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Undo") and hasClickAction()).performClick()
            }
            compose.waitUntil(5_000) { (if (fromHistory) "event-older" else "event-one") in repo.state.value.undoneHere }
            compose.waitUntil(5_000) { repo.state.value.pending.isEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("Synthetic misting").assertDoesNotExist()
            assertEquals(before, places.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot })
        }
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anOwnBatchUndoKeepsTheHintsSpaceUntilAPull() = batchUndone(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anOwnBatchUndoKeepsTheHintsSpaceUntilAPullAtLargeText() = batchUndone(1.3f)
    private fun batchUndone(fontScale: Float) {
        val pulling = mutableStateOf(false)
        val hint = "Part of a care batch. Undo reverses the whole batch."
        val batch = arrayOf<Pair<String, Any>>("task_type" to "clean_leaves", "batch_id" to "batch-all", "batch_action" to "complete-all", "batch_plant_count" to 1,
            "batch_event_count" to 1, "occurred_at" to "2030-06-02T10:00:00.000Z")
        val middle = event("event-middle", "task_type" to "fertilize", "occurred_at" to "2030-06-02T09:00:00.000Z")
        val state = mutableStateOf(owner.copy(today = today, detail = detail(listOf(event("event-batch", *batch), middle))))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
            PlantDetailScreen(state.value, state.value.detail!!, {}, { _, _ -> }, {}, {}, refreshing = pulling.value) } } }
        reveal(hasText(hint)).assertIsDisplayed()
        val below = reveal(hasTestTag("undo:event-middle")).fetchSemanticsNode().boundsInRoot
        val undone = event("event-batch", *batch, "undone_at" to "2030-06-02T10:05:00Z", "can_undo" to false)
        compose.runOnIdle { state.value = state.value.copy(detail = detail(listOf(undone, middle)), undoneHere = setOf("event-batch", "batch-all")) }
        reveal(hasText("Undone")).assertIsDisplayed()
        compose.onNodeWithText(hint).assertDoesNotExist()
        assertEquals(below, compose.onNodeWithTag("undo:event-middle").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { pulling.value = true }
        compose.runOnIdle { pulling.value = false }
        assertTrue("the hint's space goes at the next pull", compose.onNodeWithTag("undo:event-middle").fetchSemanticsNode().boundsInRoot.top < below.top)
    }

    // A tall window shows the whole page, so positions compare without scrolling in between.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anUndoAnOwnRepotUndoTurnedOnWaitsInPlaceForALaterChangeElsewhere() = laterUndoChangeWaits(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anUndoAnOwnRepotUndoTurnedOnWaitsInPlaceForALaterChangeElsewhereAtLargeText() = laterUndoChangeWaits(1.3f)
    private fun laterUndoChangeWaits(fontScale: Float) {
        fun repot(id: String, at: String, vararg fields: Pair<String, Any>) = JSONObject(eventFixture).put("id", id).put("task_type", "repot").put("occurred_on", at.take(10))
            .put("occurred_at", at).put("previous_pot", JSONObject().put("pot_type", "plastic").put("pot_size_cm", 14).put("soil_type", "all_purpose"))
            .apply { fields.forEach { (key, value) -> put(key, value) } }
        val older = JSONObject(eventFixture).put("id", "event-older").put("task_type", "fertilize").put("occurred_on", "2030-05-31").put("occurred_at", "2030-05-31T09:00:00.000Z")
        val earlier = repot("event-repot-old", "2030-06-01T09:00:00.000Z", "can_undo" to false)
        val latest = repot("event-repot", "2030-06-02T10:00:00.000Z")
        val undone = JSONObject(latest.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        val history = java.util.concurrent.atomic.AtomicReference("[$latest,$earlier,$older]")
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> history.get()
            "/api/care-events/event-repot/undo" -> {
                history.set("[$undone,${JSONObject(earlier.toString()).put("can_undo", true)},$older]")
                """{"event":$latest,"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}"""
            }
            else -> null
        } }, paths = paths) { repo, showing ->
            compose.setContent { if (showing.value) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
                val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id) } } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-repot") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("undo:event-repot-old").assertDoesNotExist()
            compose.onNodeWithTag("undo:event-repot").performClick()
            // This device's own repot Undo turns the earlier repot's Undo on at once.
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-repot-old") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { repo.state.value.pending.isEmpty() }
            compose.waitForIdle()
            val places = listOf(hasTestTag("undo:event-repot-old"), hasTestTag("undo:event-older"))
            val before = places.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot }
            // Another member then repots, which turns that Undo off, and a reload that is no apply moment brings it.
            history.set("[${repot("event-theirs", "2030-06-02T11:00:00.000Z", "by_member" to "member-two", "by_name" to "Synthetic partner")},$undone,$earlier,$older]")
            repo.openPlant(plant.id, full = true)
            compose.waitForIdle()
            assertEquals(before, places.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot })
            compose.onNodeWithText("Repotted \u00b7 Synthetic partner").assertDoesNotExist()
            compose.onNodeWithTag("undo:event-repot-old").assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, ChangedElsewhere)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("/api/care-events/event-repot-old/undo" in paths)
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Repotted \u00b7 Synthetic partner").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("undo:event-repot-old").assertDoesNotExist()
        }
    }

    // A tall window composes every history entry, so the waits below can find them.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun historyUndoOffForAPlantRequestSaysWhyOnTapAndSendsNothing() {
        val online = AtomicBoolean(true)
        val older = JSONObject(eventFixture).put("id", "event-older").put("task_type", "fertilize").put("occurred_on", "2030-06-01")
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> "[$eventFixture,$older]"
            "/api/care-events/event-one/undo" -> """{"message":"Synthetic refusal"}"""
            else -> null
        } }, paths = paths, connected = { online.get() }, status = { if (it == "/api/care-events/event-one/undo") 403 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-older") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            // The page's opening reads (two plant loads, choices and sites) land now, so none can run after the refusal below and lift it.
            compose.waitUntil(5_000) { synchronized(paths) { "/api/sites" in paths && paths.count { it == "/api/plants/plant-one/photos" } == 2 } }
            // A plant read without a connection turns plant writes off while Today's state stays online.
            online.set(false)
            repo.openPlant(plant.id, full = true)
            compose.runOnIdle { assertFalse(repo.plantState.value.online); assertFalse(repo.state.value.offline) }
            reveal(hasTestTag("undo:event-older")).assertIsNotEnabled().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Changes need a connection.").fetchSemanticsNodes().isNotEmpty() }
            fun dismiss(text: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText(text)))
                .performSemanticsAction(SemanticsActions.Dismiss)
            dismiss("Changes need a connection.")
            online.set(true)
            repo.connectivityChanged(true)
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-one") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            // The reconnect's plant reload lands now for the same reason.
            compose.waitUntil(5_000) { synchronized(paths) { paths.count { it == "/api/plants/plant-one/photos" } == 3 } }
            // Fytorium refuses an Undo, so plant writes stay off until Try again succeeds.
            compose.onNodeWithTag("undo:event-one").performClick()
            compose.waitUntil(5_000) { repo.plantState.value.blocked }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic refusal").fetchSemanticsNodes().isNotEmpty() }
            dismiss("Synthetic refusal")
            compose.waitUntil(5_000) { repo.state.value.receipt == null }
            reveal(hasTestTag("undo:event-older")).assertIsNotEnabled().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Fytorium refused the last request. Try again first.").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals(listOf("/api/care-events/event-one/undo"), paths.filter { it.endsWith("/undo") }) }
        }
    }

    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun handleAllOffAfterARefusedPlantRequestSaysWhyOnTapAndSendsNothing() {
        val older = JSONObject(eventFixture).put("id", "event-older").put("task_type", "fertilize").put("occurred_on", "2030-05-20").put("due_on", "2030-05-20")
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> "[$older]"
            "/api/care-events/event-older/undo" -> """{"message":"Synthetic refusal"}"""
            else -> null
        } }, paths = paths, status = { if (it == "/api/care-events/event-older/undo") 403 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val handle = hasText("Handle all tasks")
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-older") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodes(handle and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            // The page's opening reads (two plant loads, choices and sites) land now, so none can run after the refusal below and lift it.
            compose.waitUntil(5_000) { synchronized(paths) { "/api/sites" in paths && paths.count { it == "/api/plants/plant-one/photos" } == 2 } }
            reveal(hasTestTag("undo:event-older")).performClick()
            compose.waitUntil(5_000) { repo.plantState.value.blocked }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic refusal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText("Synthetic refusal")))
                .performSemanticsAction(SemanticsActions.Dismiss)
            compose.waitUntil(5_000) { repo.state.value.receipt == null }
            compose.runOnIdle { assertTrue(repo.state.value.writable) }
            val refused = "Fytorium refused the last request. Try again first."
            reveal(handle).assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, refused)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(refused).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertFalse("/api/plants/plant-one/tasks/complete-all" in paths) }
        }
    }

    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun historyUndoDuringAGraveyardSaveSaysWhyOnTapAndSendsNothing() {
        val release = java.util.concurrent.CountDownLatch(1)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            withPlantServer({ path -> when (path) {
                "/api/plants/plant-one/history" -> "[${JSONObject(eventFixture).put("occurred_on", "2030-06-01")}]"
                "/api/plants/plant-one/graveyard" -> { release.await(10, java.util.concurrent.TimeUnit.SECONDS); plantFixture }
                else -> null
            } }, paths = paths) { repo, showing ->
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
                compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("undo:event-one") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Handle all tasks") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNodeWithText("Move to graveyard").performClick()
                compose.onNode(hasText("Move to graveyard") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(5_000) { repo.plantState.value.saving }
                reveal(hasTestTag("undo:event-one")).assertIsNotEnabled().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Wait for the current save to finish.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText("Wait for the current save to finish.")))
                    .performSemanticsAction(SemanticsActions.Dismiss)
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Wait for the current save to finish.").fetchSemanticsNodes().isEmpty() }
                reveal(hasText("Handle all tasks")).assertIsNotEnabled().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Wait for the current save to finish.").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { assertEquals(emptyList<String>(), paths.filter { it.endsWith("/undo") || it.endsWith("/complete-all") }) }
                release.countDown()
                compose.waitUntil(5_000) { !repo.plantState.value.saving }
            }
        } finally { release.countDown() }
    }

    @Test fun skipAndHistoryUndoOffOfflineOrForAnUpdateSayWhyOnTap() {
        val explained = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val state = mutableStateOf(owner.copy(today = today, offline = true))
        compose.setContent { FytoriumTheme { PlantDetailScreen(state.value, detail(listOf(event("event-one", "occurred_on" to "2030-05-20"))), {}, { action, _ -> sent += action }, {},
            { sent += "undo" }, enabled = false, onTask = { _, action -> sent += action }, onBlocked = { explained += it }) } }
        for ((blocked, why) in listOf(owner.copy(today = today, offline = true) to "Changes need a connection.",
            owner.copy(today = today, updateRequired = true) to "Update Fytorium to make changes.")) {
            compose.runOnIdle { state.value = blocked }
            reveal(hasContentDescription("More actions for Fertilize on Synthetic fern")).performClick()
            compose.onNodeWithContentDescription("Skip Fertilize for Synthetic fern").assertIsNotEnabled().performClick()
            reveal(hasTestTag("undo:event-one")).assertIsNotEnabled().performClick()
            reveal(hasText("Handle all tasks")).assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf(why, why, why), explained.toList()); explained.clear() }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
    }

    private fun withPlantServer(served: (String) -> String? = { null }, lists: String = taskListFixture("today"),
        paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()), connected: () -> Boolean = { true },
        status: (String) -> Int = { 200 }, block: (Repository, MutableState<Boolean>) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            val options = syntheticOptions()
            val choices = JSONObject().put("plant", JSONObject().put("choices", JSONObject(options.choices)).put("labels", JSONObject(options.labels))
                .put("numbers", JSONArray(options.numbers)).put("booleans", JSONArray(options.booleans)).put("health", JSONArray(options.health)))
                .put("nameLanguages", JSONObject(options.languages)).toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = request.requestUrl!!.encodedPath.also { paths += it }.let { path -> MockResponse().setResponseCode(status(path)).setBody(served(path) ?: when (path) {
                    "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    "/api/tasks" -> taskListFixture(request.requestUrl!!.queryParameter("view") ?: "today")
                    "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    "/api/choices" -> choices
                    "/api/sites" -> """[{"id":"site-one","name":"Synthetic site"}]"""
                    "/api/plants" -> "[$plantFixture]"
                    "/api/plants/plant-one" -> plantFixture
                    "/api/plants/plant-one/tasks" -> scheduleFixture
                    "/api/plants/plant-one/care-settings" -> """{"customIntervals":{}}"""
                    else -> "[]"
                }) }
            }
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            cache.save("member-one", mapOf("plants" to "[$plantFixture]", "today" to lists), 0)
            val repo = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, cache, connected)
            repo.restore()
            val showing = mutableStateOf(true)
            block(repo, showing)
            // A disposed screen's read already on Dispatchers.IO must finish before the database closes.
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            compose.waitUntil(5_000) { Running.count.get() == 0 }
            // Repository background work (refreshSchedules) holds workLock, so this read waits for it too.
            repo.refreshPlants()
        }
        db.close()
    }
}

// The plant schedule as GET /api/plants/:id/tasks sends it: built-in tasks carry no task_type.
val scheduleFixture = """{
 "water":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"postponed":null,"basedOn":[{"input":"wateringIntervalDays","value":7,"source":"open_plantbook","adjustment":"Species care profile sets the starting interval.","ruleId":null}]},
 "fertilize":{"status":"due","dueOn":"2030-06-07","intervalDays":9,"lateDays":0,"postponed":null,"basedOn":[{"input":"fertilizingIntervalDays","value":30,"source":"rule","adjustment":"Default","ruleId":"fertilize.default@1"},{"input":"customIntervals","value":9,"source":"override","adjustment":"Custom fertilize interval replaces the computed interval: 9 days."}]},
 "cut_back":{"status":"not_applicable","basedOn":[]},
 "customTasks":{}}"""
