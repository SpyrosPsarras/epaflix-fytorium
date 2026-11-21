package com.epaflix.fytorium

import android.graphics.Insets
import android.os.Looper
import android.os.Parcel
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.epaflix.fytorium.plants.*
import com.epaflix.fytorium.species.*
import com.github.takahirom.roborazzi.captureRoboImage
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
    @Test fun plantStatusExplanationStaysOpenAcrossRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme {
            BarTitle("Offline", "Synthetic connection explanation") { Text("Synthetic sprout") }
        } }
        compose.onNodeWithText("Offline").performClick()
        compose.onNodeWithText("Synthetic connection explanation").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Synthetic connection explanation").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Synthetic connection explanation").assertDoesNotExist()
    }

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
        // The top bar takes the name only once the headline has scrolled away.
        compose.onAllNodes(hasText(name) and isHeading()).assertCountEquals(1)
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
        compose.onNodeWithText("Move to the graveyard?").assertIsDisplayed()
        compose.assertDialogBody("Synthetic fern leaves Today and Upcoming. Its history and photos stay, and you can restore it later.")
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
        compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
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
        // Fields without a value fold under No value yet, each still open to a value of your own.
        reveal(hasText("No value yet (13)")).performClick()
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
        compose.onNodeWithText("Permanently delete this plant?").assertIsDisplayed()
        compose.assertDialogBody("Type Synthetic fern to confirm.")
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

    // C25: entering from Today's row whose occurrence another member skipped shows the fresh schedule, so the row acts on the occurrence the server has.
    @Test fun aListedRowANewerScheduleNoLongerHasGivesWayToItOnEntryAndNeverActsOnTheOld() {
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
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Skipped until Mon 3 Jun").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(ChangedElsewhere).assertCountEquals(0)
            reveal(mark).assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf("2030-06-03:complete"), acted) }
        }
    }

    // C25: entering, a listed event the newer history shows undone gives way to the fresh schedule; a later change elsewhere waits in place, off, and sends nothing until a pull.
    @Test fun aListedCompletionANewerHistoryShowsUndoneGivesWayOnEntryAndALaterChangeWaitsInPlace() =
        aListedEventANewerHistoryShowsUndone(eventFixture, "Water done for Synthetic fern, Synthetic site")
    @Test fun aListedSnoozeANewerHistoryShowsUndoneGivesWayOnEntryAndALaterChangeWaitsInPlace() = aListedEventANewerHistoryShowsUndone(
        JSONObject(eventFixture).put("kind", "snoozed").put("postponed_until", "2030-06-04").toString(), "Undo watering snooze for Synthetic fern, Synthetic site")

    private fun aListedEventANewerHistoryShowsUndone(listed: String, control: String) {
        val undone = JSONObject(listed).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        // Fertilize is due too, so Handle all names both tasks the server would handle.
        val due = JSONObject(scheduleFixture).apply { getJSONObject("fertilize").put("dueOn", "2030-06-02") }.toString()
        // Later another member waters it, which moves Water to its next date.
        val theirs = JSONObject(eventFixture).put("id", "event-theirs").put("by_member", "member-two").put("by_name", "Synthetic partner").put("can_undo", false)
        val watered = AtomicBoolean(false)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> if (watered.get()) "[$theirs,$undone]" else "[$undone]"
            "/api/plants/plant-one/tasks" -> if (watered.get()) JSONObject(due).apply { getJSONObject("water").put("dueOn", "2030-06-09").put("lateDays", 0) }.toString() else due
            else -> null
        } }, lists = taskListFixture("today", event = ""","completed_event":$listed"""), paths = paths) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            // Both opening plant loads land first, so the row below reflects the newest history.
            compose.waitUntil(5_000) { synchronized(paths) { "/api/sites" in paths && paths.count { it == "/api/plants/plant-one/photos" } == 2 } }
            compose.waitUntil(5_000) { repo.state.value.detail?.history?.singleOrNull()?.undoneAt != null }
            val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
            compose.waitUntil(5_000) { compose.onAllNodes(mark and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasContentDescription(control) or hasContentDescription("$control. Double tap to undo")).assertCountEquals(0)
            val handle = hasText("Handle all tasks")
            reveal(handle).assertIsEnabled().assert(hasContentDescription("Handle all Synthetic fern tasks: Water, Fertilize"))
            // The user has scrolled, so the next fresh read waits for a pull.
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
            watered.set(true)
            repo.openPlant(plant.id, full = true)
            compose.waitForIdle()
            val changed = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, ChangedElsewhere)
            reveal(mark).assertIsNotEnabled().assert(changed).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText(ChangedElsewhere)))
                .performSemanticsAction(SemanticsActions.Dismiss)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isEmpty() }
            reveal(handle).assertIsNotEnabled().assert(hasContentDescription("Handle all Synthetic fern tasks: Water, Fertilize")).assert(changed).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(ChangedElsewhere).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Due in 7 days").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals(emptyList<String>(), paths.filter { it.endsWith("/undo") || it.endsWith("/complete-all") || it.endsWith("/complete") }) }
        }
    }

    // C25, Sol's return path: an own skip the lists still show, undone elsewhere while the page was closed, gives way to the fresh schedule when the page opens again.
    @Test fun anOwnSkipUndoneElsewhereGivesWayToTheFreshScheduleWhenThePageOpensAgain() {
        val skip = JSONObject(eventFixture).put("id", "event-skip").put("kind", "skipped").put("postponed_until", "2030-06-08")
        val undone = AtomicBoolean(false)
        val skipped = JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-08").put("lateDays", 0).put("postponed", "skipped") }.toString()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun reads() = synchronized(paths) { paths.count { it == "/api/plants/plant-one/history" } }
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> "[${if (undone.get()) JSONObject(skip.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false) else skip}]"
            "/api/plants/plant-one/tasks" -> skipped.takeIf { !undone.get() }
            else -> null
        } }, lists = taskListFixture("today", due = "2030-06-08", event = ""","postponed":"skipped","completed_event":$skip,"postponed_until":"2030-06-08""""), paths = paths) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
            compose.onNodeWithTag("plant:plant-one").performClick()
            val undo = hasContentDescription("Undo watering skip for Synthetic fern, Synthetic site")
            compose.waitUntil(5_000) { compose.onAllNodes(undo and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("plant:plant-one").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            undone.set(true)
            val before = reads()
            compose.onNodeWithTag("plant:plant-one").performClick()
            compose.waitUntil(5_000) { reads() > before && repo.state.value.detail?.history?.singleOrNull()?.undoneAt != null }
            val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
            compose.waitUntil(5_000) { compose.onAllNodes(mark and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("1 day late")).assertIsDisplayed()
            compose.onAllNodes(undo).assertCountEquals(0)
        }
    }

    // C27: Back from a page the plant page opened returns to the same place, with Details as the user left it. The window leaves Photos in view at the bottom of the page.
    @Config(qualifiers = "w360dp-h1200dp-mdpi")
    @Test fun backFromAPageThePlantPageOpenedKeepsItsPlaceAndDetails() = withPlantServer({ path -> when (path) {
        "/api/species/910001" -> speciesNamesFixture
        "/api/species/910001/care" -> careFixture.replace("pending", "done")
        "/api/species/910001/outdoor-months" -> outdoorFixture
        else -> null
    } }) { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }, photosApi = api, photosConnected = { true }) } }
        // Both opening plant loads land first, so neither lands after a return.
        compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one/photos" } == 2 } && Running.count.get() == 0 }
        reveal(hasText("Details")).performClick()
        val names = hasText("Species names") and hasClickAction()
        val place = reveal(names).fetchSemanticsNode().boundsInRoot
        compose.waitUntil(5_000) { Running.count.get() == 0 }
        for ((open, opened) in listOf(names to (hasText("Common name") and hasSetTextAction()),
            (hasText("Care defaults for this species") and hasClickAction()) to (hasText("Override watering interval in days") and isEnabled()),
            hasText("See all") to (hasText("Synthetic fern updates") and isHeading()),
            hasContentDescription("Edit plant") to (hasText("Name") and hasSetTextAction()))) {
            compose.onNode(open).assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(opened).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(names).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            assertEquals("Back from the page opened by $open moved the plant page", place, compose.onNode(names).fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun repotFromTheEndOfDetailsKeepsTheItemOnScreen() = changedContentAboveDetailsKeepsTheItem(repot = true)

    @Test fun aSpeciesChangeResumedOnNamesKeepsThePlantPageItemOnBack() = changedContentAboveDetailsKeepsTheItem(repot = false)

    @Test fun repotAfterRecreationOnTheFormKeepsTheItemOnScreen() = changedContentAboveDetailsKeepsTheItem(repot = true, recreate = true)

    @Test fun aSpeciesChangeAfterRecreationOnNamesKeepsThePlantPageItemOnBack() = changedContentAboveDetailsKeepsTheItem(repot = false, recreate = true)

    private fun changedContentAboveDetailsKeepsTheItem(repot: Boolean, recreate: Boolean = false) {
        val changed = AtomicBoolean(false)
        val after = JSONObject(plantFixture).apply {
            if (repot) put("pot_size_cm", 25)
            else put("gbif_key", 910019).put("scientific_name", "Testoria nova Inventor").put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        }
        val repotted = JSONObject(eventFixture).put("id", "event-repot").put("task_type", "repot")
        val app = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one" -> after.toString().takeIf { changed.get() }
            "/api/plants" -> "[$after]".takeIf { changed.get() }
            "/api/plants/plant-one/repot" -> { changed.set(true); JSONObject().put("event", repotted).toString() }
            "/api/plants/plant-one/history" -> "[$repotted]".takeIf { repot && changed.get() }
            "/api/plants/plant-one/species-changes" -> """[{"id":"change-one","previous_name":"Testoria lucida Inventor","next_name":"Testoria nova Inventor","next_display_name":"Synthetic star","changed_at":"2030-06-02T09:00:00.000Z","changed_on":"2030-06-02","by_name":"Synthetic partner"}]""".takeIf { !repot && changed.get() }
            "/api/species/910001" -> speciesNamesFixture
            "/api/species/910019" -> speciesNamesFixture.replace("910001", "910019").replace("Synthetic lantern", "Synthetic star")
            else -> null
        } }) { repo, showing ->
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides app) {
                if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } } }
            compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one/photos" } == 2 } && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            val names = hasText("Species names") and hasClickAction()
            reveal(names)
            compose.waitUntil(5_000) { Running.count.get() == 0 }
            val place = compose.onNode(names).fetchSemanticsNode().boundsInRoot
            if (repot) {
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNode(hasText("Repot") and hasAnyAncestor(isPopup())).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Repot Synthetic fern") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                if (recreate) repeat(2) {
                    restoration.emulateSavedInstanceStateRestore()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Repot Synthetic fern") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                }
                compose.onNode(hasText("Pot size in cm") and hasSetTextAction()).performTextReplacement("25")
                compose.onNodeWithText("Repot Synthetic fern").performClick()
            } else {
                compose.onNode(names).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Common name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
                changed.set(true)
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                compose.waitUntil(5_000) { repo.state.value.detail?.plant?.attributesJson?.let { JSONObject(it).optLong("gbif_key") } == 910019L && Running.count.get() == 0 }
                if (recreate) {
                    restoration.emulateSavedInstanceStateRestore()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Common name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                    compose.onNodeWithText("Plants of this species without a name of their own show \u201cSynthetic star\u201d.").assertIsDisplayed()
                }
                compose.onNodeWithContentDescription("Back").performClick()
            }
            compose.waitUntil(5_000) { repo.state.value.detail?.plant?.attributesJson == after.toString() &&
                compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(names).assertIsDisplayed()
            assertEquals("return keeps the item at the end of unfolded Details", place, compose.onNode(names).fetchSemanticsNode().boundsInRoot)
        }
    }

    // C25, C27: Back from Care defaults returns to the kept place, and the plant's fresh read after that return still applies, as at any entry.
    @Config(qualifiers = "w360dp-h1200dp-mdpi")
    @Test fun theFreshReadAfterReturningToTheKeptPlaceApplies() = freshReadAfterReturningToTheKeptPlace(recreate = false)

    @Config(qualifiers = "w360dp-h1200dp-mdpi")
    @Test fun restoringThePlantPageByKeyDoesNotBlockItsFirstFreshRead() = freshReadAfterReturningToTheKeptPlace(recreate = true)

    private fun freshReadAfterReturningToTheKeptPlace(recreate: Boolean) {
        val changed = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            withPlantServer({ path -> when (path) {
                "/api/species/910001/care" -> careFixture.replace("pending", "done")
                "/api/species/910001/outdoor-months" -> outdoorFixture
                "/api/plants/plant-one/tasks" -> if (!changed.get()) null else { release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-05").put("lateDays", 0) }.toString() }
                else -> null
            } }) { repo, showing ->
                val restoration = StateRestorationTester(compose)
                restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                    PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } }
                compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one/photos" } == 2 } && Running.count.get() == 0 }
                reveal(hasText("Details")).performClick()
                val link = hasText("Care defaults for this species") and hasClickAction()
                reveal(link)
                compose.waitUntil(5_000) { Running.count.get() == 0 }
                val place = compose.onNode(link).fetchSemanticsNode().boundsInRoot
                compose.onNode(link).performClick()
                val opened = hasText("Override watering interval in days") and isEnabled()
                compose.waitUntil(5_000) { compose.onAllNodes(opened).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                if (recreate) {
                    restoration.emulateSavedInstanceStateRestore()
                    compose.waitUntil(5_000) { compose.onAllNodes(opened).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                }
                changed.set(true)
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(link).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(link).assertIsDisplayed()
                assertEquals("restoration keeps the item before the fresh read lands", place, compose.onNode(link).fetchSemanticsNode().boundsInRoot)
                release.countDown()
                compose.waitUntil(5_000) { repo.state.value.detail?.tasks?.get("water")?.dueOn == "2030-06-05" && Running.count.get() == 0 }
                compose.onNode(link).assertIsDisplayed()
                reveal(hasText("Due in 3 days")).assertIsDisplayed()
                reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).assertIsEnabled()
            }
        } finally { release.countDown() }
    }

    // C2, C27: a clamp after a return (window grows) is not a user scroll, so the fresh read still applies.
    @Config(qualifiers = "w360dp-h1200dp-mdpi")
    @Test fun theFreshReadAfterAReturnThatClampsTheKeptPlaceApplies() {
        val changed = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val window = mutableStateOf(700.dp)
        try {
            withPlantServer({ path -> when (path) {
                "/api/species/910001/care" -> careFixture.replace("pending", "done")
                "/api/species/910001/outdoor-months" -> outdoorFixture
                "/api/plants/plant-one/tasks" -> if (!changed.get()) null else { release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-05").put("lateDays", 0) }.toString() }
                else -> null
            } }) { repo, showing ->
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                    Box(Modifier.height(window.value)) { PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } } }
                compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one/photos" } == 2 } && Running.count.get() == 0 }
                reveal(hasText("Details")).performClick()
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Species names") and hasClickAction())
                compose.waitUntil(5_000) { Running.count.get() == 0 }
                val link = hasText("Care defaults for this species") and hasClickAction()
                compose.onNode(link).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                changed.set(true)
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(link).fetchSemanticsNodes().isNotEmpty() }
                val kept = compose.onNode(link).fetchSemanticsNode().boundsInRoot.top
                compose.runOnIdle { window.value = 1100.dp }
                compose.waitForIdle()
                assertTrue("the page clamped its kept place", compose.onNode(link).fetchSemanticsNode().boundsInRoot.top > kept)
                release.countDown()
                compose.waitUntil(5_000) { repo.state.value.detail?.tasks?.get("water")?.dueOn == "2030-06-05" && Running.count.get() == 0 }
                reveal(hasText("Due in 3 days")).assertIsDisplayed()
            }
        } finally { release.countDown() }
    }

    // C25, C2: an Undo made elsewhere while a page opened from the plant page is showing appears in Care and History on Back, without a scroll or pull; the tall window shows them all.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anUndoElsewhereDuringSpeciesNamesShowsWhenBackReturns() = undoElsewhereShowsOnReturn(hasText("Species names") and hasClickAction(),
        hasText("Common name") and hasSetTextAction())
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun anUndoElsewhereDuringPhotosShowsWhenBackReturns() = undoElsewhereShowsOnReturn(hasText("See all"), hasText("Synthetic fern updates") and isHeading())

    private fun undoElsewhereShowsOnReturn(open: SemanticsMatcher, opened: SemanticsMatcher) {
        val undone = AtomicBoolean(false)
        val watered = JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-09").put("lateDays", 0) }.toString()
        withPlantServer({ path -> when (path) {
            "/api/species/910001" -> speciesNamesFixture
            "/api/plants/plant-one/history" -> "[${if (undone.get()) JSONObject(eventFixture).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false) else eventFixture}]"
            "/api/plants/plant-one/tasks" -> watered.takeIf { !undone.get() }
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
            compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one/photos" } == 2 } && Running.count.get() == 0 }
            compose.onNodeWithTag("undo:event-one").assertIsEnabled()
            compose.onNodeWithText("Details").performClick()
            compose.onNode(open).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(opened).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            undone.set(true)
            compose.onNodeWithContentDescription("Back").performClick()
            val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
            compose.waitUntil(5_000) { compose.onAllNodes(mark and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 day late").assertIsDisplayed()
            compose.onNodeWithText("Undone").assertIsDisplayed()
            compose.onNodeWithTag("undo:event-one").assertDoesNotExist()
        }
    }

    // C27: Back to the grid drops the plant page's kept place, so opening the plant again starts at the top with Details folded.
    @Config(qualifiers = "w360dp-h1200dp-mdpi")
    @Test fun openingThePlantAgainFromTheGridStartsAtTheTopWithDetailsFolded() = withPlantServer { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
        val tile = hasTestTag("plant:plant-one")
        val cover = hasContentDescription("No cover photo for Synthetic fern")
        val loaded = hasContentDescription("Plant settings") and isEnabled()
        compose.onNode(tile).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        reveal(hasText("Details")).performClick()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Species names") and hasClickAction())
        assertTrue("the cover scrolled away", runCatching { compose.onNode(cover).assertIsDisplayed() }.isFailure)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        compose.onNode(tile).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        compose.onNode(cover).assertIsDisplayed()
        compose.onAllNodes(hasText("Species names")).assertCountEquals(0)
    }

    // C28: this device's own rename shows at once in the Care rows' spoken names too, though the lists still carry the old name.
    @Test fun anOwnRenameShowsAtOnceInTheCareRowsSpokenNames() {
        val renamed = AtomicBoolean(false)
        val after = JSONObject(plantFixture).put("display_name", "Synthetic ed").put("nickname", "Synthetic ed").toString()
        withPlantServer({ path -> after.takeIf { path == "/api/plants/plant-one" && renamed.get() } },
            respond = { request -> if (request.method == "PUT" && request.requestUrl!!.encodedPath == "/api/plants/plant-one") { renamed.set(true); MockResponse().setBody(after) } else null }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit plant").performClick()
            val name = hasText("Name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).performTextReplacement("Synthetic ed")
            compose.onNodeWithText("Save plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic ed") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasContentDescription("Mark Water done for Synthetic ed, Synthetic site")).assertIsEnabled()
            reveal(hasContentDescription("More actions for Water on Synthetic ed")).assertExists()
            compose.onAllNodes(hasContentDescription("Synthetic fern", substring = true)).assertCountEquals(0)
        }
    }

    // C25, Sol's selected-task path: Today's own Water completion, undone elsewhere, gives way to the fresh schedule when the plant page opens from it.
    @Test fun aSelectedOwnCompletionUndoneElsewhereGivesWayToTheFreshScheduleOnEntry() {
        val undone = JSONObject(eventFixture).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        withPlantServer({ path -> if (path == "/api/plants/plant-one/history") "[$undone]" else null },
            lists = taskListFixture("today", event = ""","completed_event":$eventFixture""")) { repo, showing ->
            val opened = repo.state.value.today!!.sections.single().tasks.single()
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, initialTask = care.currentTask(opened.occurrence)) } }
            val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
            compose.waitUntil(5_000) { compose.onAllNodes(mark and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("1 day late")).assertIsDisplayed()
            val check = "Water done for Synthetic fern, Synthetic site"
            compose.onAllNodes(hasContentDescription(check) or hasContentDescription("$check. Double tap to undo")).assertCountEquals(0)
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

    // C1: a connection change adds a line under the bar title, in a bar that keeps its height, so mid-page no row or control moves or is covered.
    @Test fun connectionChangesMoveNoCareRow() = connectionChangesMoveNoCareRow(1f)
    @Test fun connectionChangesMoveNoCareRowAtLargeText() = connectionChangesMoveNoCareRow(1.3f)
    private fun connectionChangesMoveNoCareRow(fontScale: Float) {
        val state = mutableStateOf(owner.copy(today = today, detail = detail()))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            FytoriumTheme { PlantsScreen(state.value, null, initialPlant = plant.id) } } }
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 350f) }
        compose.onNodeWithContentDescription("Mark Water done for Synthetic fern", substring = true).assertIsDisplayed()
        val places = listOf(hasTestTag("task:plant-one:water"), hasTestTag("task:plant-one:fertilize"), hasContentDescription("Handle all Synthetic fern tasks", substring = true),
            hasContentDescription("Mark Water done for Synthetic fern", substring = true), hasText("History") and isHeading())
        fun bounds() = places.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot } + list.fetchSemanticsNode().boundsInRoot
        val before = bounds()
        for (change in listOf(state.value.copy(offline = true), state.value.copy(serverUnavailable = true), state.value)) {
            compose.runOnIdle { state.value = change }
            val line = change.connectionLine()
            if (line != null) {
                val bar = compose.onNode(hasText(line) and hasClickAction()).assert(hasText(plant.displayName)).fetchSemanticsNode().boundsInRoot
                assertTrue("The line sits above the page", bar.bottom <= list.fetchSemanticsNode().boundsInRoot.top + 0.5f)
            }
            assertEquals("A row or control moved at ${fontScale}x text", before, bounds())
            if (line != null) compose.explains(line, change.connectionNotice(true)!!)
        }
        compose.onNode(hasText("Offline", substring = true) or hasText("isn't responding", substring = true)).assertDoesNotExist()
    }

    // C1: the Plants grid keeps every tile in place when the connection changes, and its line explains why.
    @Test fun connectionChangesMoveNoTileOnThePlantsGrid() {
        val plants = (1..10).map { plant.copy(id = "plant-$it", displayName = "Synthetic fern $it") }
        // The line names the last update's time; its explanation must not say it again.
        val state = mutableStateOf(owner.copy(plants = plants, today = today, lastUpdated = LocalDate.now().atTime(10, 42).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
        compose.setContent { FytoriumTheme { PlantsScreen(state.value, null) } }
        val grid = compose.onAllNodes(hasScrollAction()).onFirst()
        grid.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        val tile = SemanticsMatcher("plant tile") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("plant:") == true }
        fun bounds() = compose.onAllNodes(tile).fetchSemanticsNodes().map { it.boundsInRoot } + grid.fetchSemanticsNode().boundsInRoot
        val before = bounds()
        compose.runOnIdle { state.value = state.value.copy(offline = true) }
        val line = state.value.connectionLine()!!
        assertTrue("The line sits above the grid", compose.onNode(hasText(line) and hasClickAction()).assert(hasText("Plants")).fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNode(hasText("Search plants") and hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top)
        assertEquals("A tile moved when the connection went", before, bounds())
        compose.explains(line, "You can still see the plants saved on this phone. Reconnect and refresh to make changes.")
        compose.runOnIdle { state.value = state.value.copy(offline = false) }
        compose.onNode(hasText("Offline", substring = true)).assertDoesNotExist()
        assertEquals("A tile moved when the connection came back", before, bounds())
    }

    private val refused = "Fytorium refused the last request"
    private val speciesNamesFixture = """{"gbif_key":910001,"scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida","rank":"SPECIES",
        "name_fetch_needed":false,"name_fetch_failed":false,"common_name":"Synthetic lantern","names":[{"id":"name-house","name":"Synthetic lantern","language":"en","source":"owner"}]}"""
    // The opening reads land first, so none can run after a change below and lift it.
    private fun midList(repo: Repository, showing: State<Boolean>, paths: List<String>, fontScale: Float = 1f, page: Boolean = true): () -> List<androidx.compose.ui.geometry.Rect> {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id.takeIf { page }) } } }
        // The grid's resume reload reads the sites after a hop to the main thread, when no read is running, so the wait counts both sites reads.
        compose.waitUntil(5_000) { Running.count.get() == 0 && synchronized(paths) { "/api/sites" in paths &&
            if (page) paths.count { it == "/api/plants/plant-one/photos" } == 2 else paths.count { it == "/api/sites" } == 2 && repo.state.value.plants.size == 10 } }
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        return { compose.onAllNodes(hasAnyAncestor(hasScrollAction()) and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }.filter { !it.isEmpty } +
            list.fetchSemanticsNode().boundsInRoot }
    }
    private fun assertLineAbovePage(line: String) = assertTrue("The line sits above the page", compose.onNode(hasText(line) and hasClickAction()).fetchSemanticsNode().boundsInRoot.bottom <=
        compose.onAllNodes(hasScrollAction()).onFirst().fetchSemanticsNode().boundsInRoot.top + 0.5f)

    // C1b: a refused request shows in the bar's line, so no row, control or tile moves or is covered as it starts or ends.
    @Test fun refusalMovesNoCareRow() = refusalMovesNothing(1f, page = true)
    @Test fun refusalMovesNoCareRowAtLargeText() = refusalMovesNothing(1.3f, page = true)
    @Test fun refusalMovesNoTileOnThePlantsGrid() = refusalMovesNothing(1f, page = false)
    @Test fun refusalMovesNoTileOnThePlantsGridAtLargeText() = refusalMovesNothing(1.3f, page = false)
    private fun refusalMovesNothing(fontScale: Float, page: Boolean) {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val plants = (1..10).joinToString(",", "[", "]") { JSONObject(plantFixture).put("id", "plant-$it").put("display_name", "Synthetic fern $it").toString() }
        withPlantServer({ if (!page && it == "/api/plants") plants else null }, paths = paths,
            status = { if (it.endsWith("/graveyard")) 403 else 200 }) { repo, showing ->
            val bounds = midList(repo, showing, paths, fontScale, page)
            val before = bounds()
            fun assertStill(what: String) = assertEquals("A row, control or tile moved when $what at ${fontScale}x text", before, bounds())
            assertFalse(repo.plantCommand(if (page) plant.id else "plant-1", "graveyard"))
            compose.waitForIdle()
            assertStill("Fytorium refused a request")
            assertLineAbovePage(refused)
            compose.onNode(hasText(refused) and hasClickAction()).performClick()
            compose.onNode(hasText("Your permissions no longer allow this change", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { !repo.plantState.value.blocked && Running.count.get() == 0 }
            compose.onNode(hasText(refused)).assertDoesNotExist()
            assertStill("the refusal ended")
        }
    }

    // C1b: a refused plant read offers Try again in the line's explanation, leaves plant changes on, and clears once a read succeeds.
    @Test fun aRefusedPlantReadOffersTryAgainAndRecovers() {
        val refuse = AtomicBoolean(false)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer({ if (refuse.get() && it == "/api/plants/plant-one") """{"message":"Synthetic read refusal"}""" else null }, paths = paths,
            status = { if (refuse.get() && it == "/api/plants/plant-one") 403 else 200 }) { repo, showing ->
            midList(repo, showing, paths)
            refuse.set(true)
            repo.openPlant(plant.id, full = true)
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertTrue(repo.plantState.value.enabled(repo.state.value.member, false)) }
            assertLineAbovePage(refused)
            compose.onNode(hasText(refused) and hasClickAction()).performClick()
            compose.onNode(hasText("Synthetic read refusal") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            refuse.set(false)
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused)).fetchSemanticsNodes().isEmpty() && Running.count.get() == 0 }
            compose.runOnIdle { assertNull(repo.plantState.value.reads["plant/plant-one"]) }
        }
    }

    // C1b: a plant read Fytorium refuses in the background while a form is open shows in the line, never in the form, and moves no field as it starts or ends.
    @Test fun aRefusedBackgroundReadStaysOutOfEditRepotScheduleAndNames() = refusedReadWhileEditing(buried = false)
    // A refused save stays in its form after a later read succeeds, and the line clears.
    @Test fun aRefusedBackgroundReadStaysOutOfRestoreAndARefusedSaveStaysInIt() = refusedReadWhileEditing(buried = true)
    private fun refusedReadWhileEditing(buried: Boolean) {
        val refuse = AtomicBoolean(false)
        val read = "/api/plants/plant-one"
        val save = "/api/plants/plant-one/restore"
        val shown = if (buried) JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString() else plantFixture
        withPlantServer({ path -> when (path) {
            read -> if (refuse.get()) """{"message":"Synthetic read refusal"}""" else shown
            save -> """{"message":"Synthetic save refusal"}"""
            "/api/species/910001" -> speciesNamesFixture
            else -> null
        } }, status = { path -> if (refuse.get() && path == read) 403 else if (path == save) 409 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            fun menu(item: String) { compose.onNode(settings).performClick(); compose.onNode(hasText(item) and hasAnyAncestor(isPopup())).performClick() }
            val forms = if (buried) listOf({ menu("Restore plant") } to listOf(hasText("returns to Today and Upcoming", substring = true), hasText("Create a site"),
                hasText("Restore Synthetic fern to", substring = true)))
            else listOf(
                { compose.onNodeWithContentDescription("Edit plant").performClick() } to listOf(hasText("Name"), hasText("More details"), hasText("Save plant")),
                { menu("Repot") } to listOf(hasText("New pot and soil for Synthetic fern"), hasText("Pot size in cm"), hasText("Repot Synthetic fern")),
                { menu("This plant's schedule") } to listOf(hasText("Changes here apply only", substring = true), hasText("Water interval in days")),
                { reveal(hasText("Details")).performClick(); reveal(hasText("Species names")).performClick() } to
                    listOf(hasText("Your household's name"), hasText("Common name"), hasText("Add name")))
            for ((open, fields) in forms) {
                open()
                compose.waitUntil(5_000) { compose.onAllNodes(fields.last()).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { Running.count.get() == 0 }
                fun bounds() = fields.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot }
                val before = bounds()
                refuse.set(true)
                repo.openPlant(plant.id, full = true)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Synthetic read refusal")).assertDoesNotExist()
                assertEquals("A field moved when a background read was refused", before, bounds())
                compose.explains(refused, "Synthetic read refusal")
                refuse.set(false)
                repo.openPlant(plant.id, full = true)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused)).fetchSemanticsNodes().isEmpty() }
                assertEquals("A field moved when background reads recovered", before, bounds())
                if (!buried) {
                    compose.onNodeWithContentDescription("Back").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
                }
            }
            if (buried) {
                compose.onNode(hasText("Restore Synthetic fern to", substring = true)).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic save refusal")).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                val error = compose.onNode(hasText("Synthetic save refusal")).fetchSemanticsNode().boundsInRoot
                assertTrue("The refused save shows in the form, under its fields", error.top >= compose.onNode(hasText("Create a site")).fetchSemanticsNode().boundsInRoot.bottom)
                compose.onNode(hasText(refused) and hasClickAction()).assertIsDisplayed()
                repo.openPlant(plant.id, full = true)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused)).fetchSemanticsNodes().isEmpty() }
                compose.onNode(hasText("Synthetic save refusal")).assertIsDisplayed()
            }
        }
    }

    // C1b: Try again reloads the refused graveyard or species names read it was offered on; another read succeeding leaves the line.
    @Test fun tryAgainLoadsARefusedGraveyard() {
        val refuse = AtomicBoolean(false)
        val buried = JSONObject(plantFixture).put("id", "plant-buried").put("display_name", "Synthetic buried fern").put("status", "graveyard").put("graveyard_on", "2030-06-02")
        withPlantServer({ if (it == "/api/plants") (if (refuse.get()) """{"message":"Synthetic read refusal"}""" else "[$buried]") else null },
            status = { if (refuse.get() && it == "/api/plants") 403 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
            compose.waitUntil(5_000) { Running.count.get() == 0 && synchronized(paths()) { "/api/plants" in paths() && "/api/sites" in paths() } }
            refuse.set(true)
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Graveyard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNodeWithTag("plant:plant-buried").assertDoesNotExist()
            assertNotNull(repo.plantSites())
            compose.onNode(hasText(refused) and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText(refused) and hasClickAction()).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            // Fytorium answers only after the tap, so a graveyard read still on its way from opening the page cannot clear the line first.
            refuse.set(false)
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("plant:plant-buried")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(refused)).assertDoesNotExist()
        }
    }
    @Test fun tryAgainLoadsRefusedSpeciesNames() = tryAgainLoadsSpeciesNames(403, refused)
    @Test fun tryAgainLoadsSpeciesNamesFytoriumDidNotAnswer() = tryAgainLoadsSpeciesNames(503, "Fytorium isn't responding")
    private fun tryAgainLoadsSpeciesNames(status: Int, line: String) {
        val species = "/api/species/910001"
        val failing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        withPlantServer({ if (it in failing) """{"message":"Synthetic read refusal"}""" else if (it == species) speciesNamesFixture else null },
            status = { if (it in failing) status else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            // Every read the names page makes on entry fails, so the line shows in whatever order they land.
            failing += listOf(species, "/api/choices", "/api/sites")
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(line) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 &&
                synchronized(paths()) { species in paths() } }
            failing -= listOf("/api/choices", "/api/sites")
            assertNotNull(repo.plantSites())
            assertNotNull(repo.plantOptions())
            compose.onNode(hasText(line) and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("Loading names\u2026").assertIsDisplayed()
            failing.clear()
            compose.onNode(hasText(line) and hasClickAction()).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Your household added this name.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(line)).assertDoesNotExist()
        }
    }

    // C1b: a Graveyard opened offline keeps the offline line with Try again until it loads, and reconnecting loads it.
    @Test fun aGraveyardOpenedOfflineLoadsOnceTheConnectionReturns() {
        val online = AtomicBoolean(true)
        val buried = JSONObject(plantFixture).put("id", "plant-buried").put("display_name", "Synthetic buried fern").put("status", "graveyard").put("graveyard_on", "2030-06-02")
        withPlantServer({ if (it == "/api/plants") "[$buried]" else null }, connected = { online.get() }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
            compose.waitUntil(5_000) { Running.count.get() == 0 && synchronized(paths()) { "/api/plants" in paths() && "/api/sites" in paths() } }
            online.set(false)
            repo.connectivityChanged(false)
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Graveyard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No plants in the graveyard.").fetchSemanticsNodes().isNotEmpty() }
            // This read finds no connection like the page's own, and either one leaves the failure in place before the check.
            assertEquals(emptyList<Plant>(), repo.graveyard())
            compose.waitUntil(5_000) { Running.count.get() == 0 }
            compose.onNode(hasText("Offline", substring = true) and hasClickAction()).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            online.set(true)
            repo.connectivityChanged(true)
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("plant:plant-buried")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // C1b: a plant not saved on this phone keeps its loading body when Fytorium refuses it, does not answer or the phone is offline; the line says so with Try again until it loads.
    @Test fun anUncachedPlantFytoriumRefusesKeepsItsBodyAndLoadsFromTheLine() = uncachedPlantFails("refused")
    @Test fun anUncachedPlantFytoriumDidNotAnswerKeepsItsBodyAndLoadsFromTheLine() = uncachedPlantFails("unanswered")
    @Test fun anUncachedPlantOpenedOfflineKeepsItsBodyAndLoadsOnceTheConnectionReturns() = uncachedPlantFails("offline")
    private fun uncachedPlantFails(how: String) {
        val failing = AtomicBoolean(true)
        val read = "/api/plants/plant-one"
        withPlantServer({ if (failing.get() && it == read) """{"message":"Synthetic read refusal"}""" else null },
            status = { if (failing.get() && it == read) (if (how == "refused") 403 else 503) else 200 },
            connected = { how != "offline" || !failing.get() }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val line = hasText(when (how) { "refused" -> refused; "unanswered" -> "Fytorium isn't responding"; else -> "Offline" }, substring = true) and hasClickAction()
            fun body() = compose.onAllNodes(hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            compose.waitUntil(5_000) { compose.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            assertEquals("The failure changed the page body", listOf("Loading plant\u2026"), body())
            compose.onNode(line).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            failing.set(false)
            if (how == "offline") { compose.onNodeWithText("Close").performClick(); repo.connectivityChanged(true) }
            else compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodes(line).fetchSemanticsNodes().isEmpty() }
        }
    }

    private val speciesSearch = """{"search_id":"search-one","candidates":[{"gbif_key":910019,"scientific_name":"Testoria nova Inventor","canonical_name":"Testoria nova","rank":"SPECIES","match_type":"EXACT","confidence":99,"requires_confirmation":false,"matched_name":null,"matched_language":null,"common_name":"Synthetic star"}]}"""
    private val notResponding = hasText("Fytorium isn't responding") and hasClickAction()
    private val starResult = hasContentDescription("Synthetic star, Testoria nova. Choose this species.")

    @Test fun searchKeepsTheLastAnswerThroughProgressFailureAndDraftRestore() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val online = AtomicBoolean(true)
        withPlantServer(connected = online::get, respond = { request ->
            when (request.requestUrl?.queryParameter("q")) {
                "nova" -> MockResponse().setBody(speciesSearch)
                "unanswered" -> {
                    started.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    MockResponse().setResponseCode(503)
                }
                else -> null
            }
        }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            val star = hasText("Synthetic star")
            fun search(query: String) {
                field.performTextReplacement(query)
                compose.onNodeWithContentDescription("Search species").performClick()
            }
            search("nova")
            compose.waitUntil(5_000) { compose.onAllNodes(star).fetchSemanticsNodes().isNotEmpty() }
            try {
                search("unanswered")
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.onNode(star).assertIsDisplayed()
            } finally { release.countDown() }
            compose.waitUntil(5_000) { compose.onAllNodes(notResponding).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(star).assertIsDisplayed()
            online.set(false)
            repo.connectivityChanged(false)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Offline", substring = true).fetchSemanticsNodes().isNotEmpty() }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodes(star).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(star).assertIsDisplayed()
            compose.onNode(hasText("Species name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertTextContains("unanswered")
        }
    }

    @Test fun offlineSpeciesSearchKeepsTheLastAnswerAndSendsNoRequest() {
        val online = AtomicBoolean(true)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer(served = { path -> speciesSearch.takeIf { path == "/api/species/search" } }, paths = paths, connected = online::get) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            val search = compose.onNodeWithContentDescription("Search species")
            field.performTextInput("nova")
            search.performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { online.set(false); repo.connectivityChanged(false) }
            field.assertIsEnabled().performClick().assertIsFocused().performTextReplacement("zzqxw")
            search.assertIsEnabled().performClick()
            compose.waitUntil(5_000) { repo.plantState.value.reads["search"]?.offline == true && Running.count.get() == 0 }
            field.assertIsNotFocused().assertTextContains("zzqxw")
            compose.onNode(starResult).assertIsDisplayed().assertIsNotEnabled()
            compose.onNode(hasText("Offline", substring = true) and hasClickAction()).assertIsDisplayed()
            compose.onAllNodesWithText("No species called", substring = true).assertCountEquals(0)
            assertEquals(1, synchronized(paths) { paths.count { it == "/api/species/search" } })
            compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-search-offline.png")
        }
    }

    @Test fun aSearchUnansweredWhenThePageIsRecreatedIsSentAgain() {
        val releases = listOf(java.util.concurrent.CountDownLatch(1), java.util.concurrent.CountDownLatch(1))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val alba = """{"search_id":"search-two","candidates":[{"gbif_key":910022,"scientific_name":"Testoria alba Maker","canonical_name":"Testoria alba","rank":"SPECIES","match_type":"EXACT","confidence":100,"requires_confirmation":false,"common_name":"Synthetic snow"}]}"""
        // Calls 1 and 3 are held until the page has been recreated; calls 2 and 4 answer at once.
        withPlantServer(respond = { request ->
            if (request.requestUrl?.queryParameter("q") != "nova") null
            else when (calls.incrementAndGet()) {
                1, 3 -> { releases[calls.get() / 2].await(10, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setBody(speciesSearch) }
                2 -> MockResponse().setBody(speciesSearch)
                else -> MockResponse().setBody(alba)
            }
        }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            field.performTextInput("nova")
            val search = compose.onNodeWithContentDescription("Search species")
            try {
                search.performClick()
                compose.waitUntil(5_000) { calls.get() == 1 }
                restoration.emulateSavedInstanceStateRestore()
                releases[0].countDown()
                compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() && calls.get() == 2 }
                // The same query again: the earlier answer must not count as this submission's answer.
                compose.onNodeWithContentDescription("Search species").performClick()
                compose.waitUntil(5_000) { calls.get() == 3 }
                restoration.emulateSavedInstanceStateRestore()
                releases[1].countDown()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic snow").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(4, calls.get())
            } finally { releases.forEach { it.countDown() } }
        }
    }

    @Test fun aRetryAfterOtherReloadsDoesNotResendANewerSearchThatAnswered() {
        val holdPlants = AtomicBoolean(false)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val novaCalls = java.util.concurrent.atomic.AtomicInteger()
        withPlantServer(respond = { request ->
            when {
                request.requestUrl?.encodedPath == "/api/plants" && holdPlants.getAndSet(false) -> {
                    started.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    null
                }
                request.requestUrl?.queryParameter("q") == "unanswered" -> MockResponse().setResponseCode(503)
                request.requestUrl?.queryParameter("q") == "nova" -> MockResponse().setBody(speciesSearch).also { novaCalls.incrementAndGet() }
                else -> null
            }
        }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            fun search(query: String) {
                field.performTextReplacement(query)
                compose.onNodeWithContentDescription("Search species").performClick()
            }
            search("unanswered")
            compose.waitUntil(5_000) { compose.onAllNodes(notResponding).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            holdPlants.set(true)
            compose.onNode(notResponding).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // Queued behind the held reload read; it answers before the reload's own retry.
                search("nova")
            } finally { release.countDown() }
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { Running.count.get() == 0 && !repo.plantState.value.refreshing }
            compose.waitForIdle()
            assertEquals(1, novaCalls.get())
        }
    }

    @Test fun aRetryCutShortByRecreationIsSentAgainWithProgress() {
        val online = AtomicBoolean(false)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val retried = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        withPlantServer(connected = online::get, respond = { request ->
            if (request.requestUrl?.queryParameter("q") != "nova") null
            else if (calls.incrementAndGet() == 1) { retried.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setBody(speciesSearch) }
            else MockResponse().setBody(speciesSearch)
        }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            compose.runOnIdle { repo.connectivityChanged(false) }
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) } }
            compose.onNode(hasText("Species name") and hasSetTextAction()).performTextInput("nova")
            compose.onNodeWithContentDescription("Search species").performClick()
            compose.waitUntil(5_000) { repo.plantState.value.reads["search"]?.offline == true && Running.count.get() == 0 }
            // The reconnect reload retries the search; recreation cuts that retry short.
            compose.runOnIdle { online.set(true); repo.connectivityChanged(true) }
            val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
            try {
                compose.waitUntil(5_000) { retried.count == 0L }
                restoration.emulateSavedInstanceStateRestore()
                compose.waitUntil(5_000) { compose.onAllNodes(progress).fetchSemanticsNodes().isNotEmpty() }
            } finally { release.countDown() }
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, calls.get())
        }
    }

    @Test fun searchIsOffWhileASpeciesChangeSaves() {
        val saving = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        withPlantServer(respond = { request ->
            if (request.method == "POST" && request.requestUrl?.encodedPath == "/api/plants/plant-one/species") {
                saving.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS); MockResponse().setResponseCode(503)
            } else null
        }, served = { path -> speciesSearch.takeIf { path == "/api/species/search" } }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(settings).performClick()
            compose.onNodeWithText("Change species").performClick()
            val field = hasText("Species name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(field).performTextInput("nova")
            compose.onNodeWithContentDescription("Search species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(starResult).performScrollTo().performClick()
            try {
                compose.onNodeWithText("Change Synthetic fern to Synthetic star").performClick()
                assertTrue(saving.await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.onNodeWithText("Choose another species").performClick()
                val searchField = hasText("Species name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
                compose.onNode(searchField).assertIsNotEnabled()
                // Recreation forgets the screen's own saving flag, but the save is still running.
                restoration.emulateSavedInstanceStateRestore()
                compose.waitUntil(5_000) { compose.onAllNodes(searchField).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(searchField).assertIsNotEnabled()
            } finally { release.countDown() }
        }
    }

    @Test fun mixedSearchKeepsServerOrderAndReplacesTheAnswerOnNoMatch() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val mixed = """{"search_id":"5dec8cd4-5f18-4157-a8df-22207fe8a9f0","candidates":[
            {"gbif_key":910019,"scientific_name":"Testoria nova Inventor","canonical_name":"Testoria nova","rank":"SPECIES","match_type":"FUZZY","confidence":90,"requires_confirmation":true,"common_name":"Synthetic star","matched_name":"Synthetic starlight","matched_language":"en"},
            {"gbif_key":910022,"scientific_name":"Testoria alba Maker","canonical_name":"Testoria alba","rank":"SPECIES","match_type":"EXACT","confidence":100,"requires_confirmation":false}]}"""
        withPlantServer(respond = { request ->
            when (request.requestUrl?.queryParameter("q")) {
                "starlight" -> MockResponse().setBody(mixed)
                "zzqxw" -> {
                    started.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    MockResponse().setResponseCode(422).setBody("""{"message":"No matching species"}""")
                }
                else -> null
            }
        }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            fun search(query: String) {
                field.performTextReplacement(query)
                compose.onNodeWithContentDescription("Search species").performClick()
            }
            val legacy = hasContentDescription("Testoria alba, Testoria alba. Choose this species.")
            val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            val title = compose.onNode(hasText("Add plant") and isHeading())
            val alias = compose.onNodeWithText("Matches \u201cSynthetic starlight\u201d")
            search("starlight")
            compose.waitUntil(5_000) { compose.onAllNodes(legacy).fetchSemanticsNodes().isNotEmpty() }
            alias.assertIsDisplayed()
            val starBounds = compose.onNode(starResult).fetchSemanticsNode().boundsInRoot
            assertTrue(starBounds.bottom < compose.onNode(legacy).fetchSemanticsNode().boundsInRoot.top)
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            try {
                search("zzqxw")
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                progress.assertIsDisplayed()
                assertTrue(progress.fetchSemanticsNode().boundsInRoot.bottom < titleBounds.top)
                assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
                assertEquals(starBounds, compose.onNode(starResult).fetchSemanticsNode().boundsInRoot)
                alias.assertIsDisplayed()
            } finally { release.countDown() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No species called \u201czzqxw\u201d").fetchSemanticsNodes().isNotEmpty() }
            progress.assertDoesNotExist()
            compose.onNode(starResult).assertDoesNotExist()
            compose.onNode(legacy).assertDoesNotExist()
            compose.onNodeWithText(refused).assertDoesNotExist()
        }
    }

    @Test fun coldSearchRestoreKeepsTheDraftConfirmationAndListOffset() {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val template = JSONObject(speciesSearch).getJSONArray("candidates").getJSONObject(0).toString()
        val results = JSONArray((1..10).map { n -> JSONObject(template).put("gbif_key", 910110 + n)
            .put("common_name", "Synthetic plant $n").put("requires_confirmation", true) })
        val answer = JSONObject(speciesSearch).put("candidates", results).toString()
        withPlantServer(paths = paths, served = { path -> answer.takeIf { path == "/api/species/search" } }) { original, showing ->
            val screen = mutableStateOf(original to SaveableStateRegistry(null) { true })
            compose.setContent { if (showing.value) {
                val (repo, saved) = screen.value
                CompositionLocalProvider(LocalSaveableStateRegistry provides saved) {
                    FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialAdd = true) }
                }
            } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            field.performTextReplacement("nova")
            compose.onNodeWithContentDescription("Search species").performClick()
            val first = hasContentDescription("Synthetic plant 1, Testoria nova. Choose this species.")
            compose.waitUntil(5_000) { compose.onAllNodes(first).fetchSemanticsNodes().isNotEmpty() }
            field.performTextReplacement("Unsaved fern search")
            field.assertTextContains("Unsaved fern search")
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToIndex(7)
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 26f) }
            val card = compose.onNodeWithContentDescription("Synthetic plant 8, Testoria nova. Choose this species.")
            val offset = card.fetchSemanticsNode().positionInRoot.y - list.fetchSemanticsNode().positionInRoot.y
            val dialog = hasAnyAncestor(isDialog())
            card.performClick()
            compose.onNode(hasText("Synthetic plant 8") and dialog).assertIsDisplayed()
            val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val fresh = Repository(api, cache, { false }).apply { restore() }
            try {
                val restored = compose.runOnIdle {
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeMap(screen.value.second.performSave())
                        parcel.setDataPosition(0)
                        val saved = parcel.readHashMap(javaClass.classLoader, String::class.java, List::class.java)!!
                        showing.value = false
                        SaveableStateRegistry(saved) { true }
                    } finally { parcel.recycle() }
                }
                compose.runOnIdle { screen.value = fresh to restored; showing.value = true }
                compose.onNode(hasText("Synthetic plant 8") and dialog).assertIsDisplayed()
                compose.onNode(hasText("Back") and dialog).performClick()
                assertEquals(offset, card.fetchSemanticsNode().positionInRoot.y - list.fetchSemanticsNode().positionInRoot.y, 0.5f)
                list.performScrollToIndex(0)
                compose.onNodeWithText("Species name").assertTextContains("Unsaved fern search")
                assertEquals(1, synchronized(paths) { paths.count { it == "/api/species/search" } })
            } finally {
                compose.runOnIdle { showing.value = false }
                compose.waitUntil(5_000) { Running.count.get() == 0 }
                db.close()
            }
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeKeyboardLeavesSearchVisibleAndRestoresNavigation() = landscapeKeyboard(edit = false)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeKeyboardLeavesEditNameVisibleAndRestoresNavigation() = landscapeKeyboard(edit = true)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun addingASpeciesNameShowsItsReceiptWithTheKeyboardOpen() {
        withLandscapeKeyboard(served = { path -> when (path) {
            "/api/species/910001" -> """{"gbif_key":910001,"scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida","rank":"SPECIES","common_name":"Synthetic lantern","names":[]}"""
            "/api/species/910001/names" -> "{}"
            else -> null
        } }) { _, ime ->
            val plants = compose.onNode(hasText("Plants") and isSelectable())
            plants.performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Fytorium chose it from the names sources give.").fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Common name") and hasSetTextAction()).performClick().assertIsFocused().performTextInput("Synthetic star")
            ime(true)
            plants.assertDoesNotExist()
            val add = compose.onNode(hasText("Add name") and hasClickAction())
            add.performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Added the name \u201cSynthetic star\u201d").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Added the name \u201cSynthetic star\u201d").assertIsDisplayed()
            plants.assertDoesNotExist()
            compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-name-receipt-ime.png")
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun offlineAddExplainsWhyWithPlantSearchFocusedAndTheKeyboardOpen() {
        // Offline from the start, so the line keeps the cache's fixed time and the reference image never changes.
        val online = AtomicBoolean(false)
        withLandscapeKeyboard(connected = online::get) { repo, ime ->
            val plants = compose.onNode(hasText("Plants") and isSelectable())
            plants.performClick()
            val field = compose.onNode(hasText("Search plants") and hasSetTextAction()).performClick().assertIsFocused()
            compose.runOnIdle { online.set(false); repo.connectivityChanged(false) }
            ime(true)
            plants.assertDoesNotExist()
            // The Offline line joins the top bar first; the explanation must not move the field after that.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Offline", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val bounds = field.fetchSemanticsNode().boundsInRoot
            val message = "Adding a plant needs a connection."
            compose.onNode(hasContentDescription("Add plant") and hasStateDescription(message)).assertIsNotEnabled().performClick()
            compose.onNodeWithContentDescription(message).assertIsDisplayed()
            field.assertIsFocused().assertIsDisplayed()
            assertEquals(bounds, field.fetchSemanticsNode().boundsInRoot)
            plants.assertDoesNotExist()
            compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-offline-add-ime.png")
        }
    }

    private fun landscapeKeyboard(edit: Boolean) = withLandscapeKeyboard { _, ime ->
        val plants = compose.onNode(hasText("Plants") and isSelectable())
        plants.performClick()
        if (edit) {
            compose.onNodeWithTag("plant:plant-one").performClick()
            val action = hasContentDescription("Edit plant") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(action).performClick()
        } else compose.onNodeWithContentDescription("Add plant").performClick()
        val matcher = hasText(if (edit) "Name" else "Species name") and hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val field = reveal(matcher).performClick().assertIsFocused()
        val height = field.fetchSemanticsNode().size.height.toFloat()
        val keyboardTop = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom } - landscapeKeyboardHeight
        ime(true)
        plants.assertDoesNotExist()
        field.assertIsFocused().assertIsDisplayed()
        val visible = field.fetchSemanticsNode().boundsInRoot
        assertEquals("Focused field must not be clipped by the keyboard", height, visible.height, 1f)
        assertTrue("Focused field must sit above the keyboard: $visible, keyboard top $keyboardTop", visible.bottom <= keyboardTop)
        ime(false)
        plants.assertIsDisplayed().assertIsSelected()
    }

    private val landscapeKeyboardHeight = 174

    private fun withLandscapeKeyboard(served: (String) -> String? = { null }, connected: () -> Boolean = { true },
        test: (Repository, (Boolean) -> Unit) -> Unit) = withPlantServer(served = served, connected = connected) { repo, showing ->
        lateinit var view: View
        val app = RuntimeEnvironment.getApplication()
        val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val controller = CareController(app, credentials, repo, api)
        fun ime(visible: Boolean) = compose.runOnUiThread {
            val type = WindowInsets.Type.ime()
            val inset = if (visible) landscapeKeyboardHeight else 0
            val simulated = WindowInsets.Builder().setInsets(type, Insets.of(0, 0, 0, inset)).setVisible(type, visible).build()
            assertEquals(inset, simulated.getInsets(type).bottom)
            assertEquals(visible, simulated.isVisible(type))
            view.dispatchApplyWindowInsets(simulated)
        }
        try {
            compose.setContent { if (showing.value) {
                CheckLandscape(1f)
                view = LocalView.current
                val care by repo.state.collectAsState()
                FytoriumTheme { Box(Modifier.fillMaxSize().imePadding()) { AppShell(true, false, careState = care, controller = controller) } }
            } }
            test(repo, ::ime)
        } finally { controller.close() }
    }

    @Test fun noMatchUsesTheSubmittedQueryAndOffersIdentifyWithoutAStatusFailure() {
        withPlantServer(respond = { request ->
            if (request.requestUrl?.queryParameter("q") == "zzqxw") MockResponse().setResponseCode(422).setBody("""{"message":"No matching species"}""") else null
        }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { Surface { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialAdd = true, photosApi = api, photosConnected = { true }) } } }
            val field = compose.onNode(hasText("Species name") and hasSetTextAction())
            field.performTextReplacement("  zzqxw  ")
            compose.onNodeWithContentDescription("Search species").performClick()
            val miss = "No species called \u201czzqxw\u201d"
            compose.waitUntil(5_000) { compose.onAllNodesWithText(miss).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-search-no-match.png")
            field.performTextReplacement("testoria")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(miss).assertIsDisplayed()
            compose.onNodeWithText("Check the spelling, try the scientific name, or identify it from photos.").assertIsDisplayed()
            compose.onNodeWithText(refused).assertDoesNotExist()
            compose.onNodeWithText("Identify from photos").performClick()
            compose.onNodeWithText("Identify from photos?").assertIsDisplayed()
        }
    }
    private fun searchChangeSpecies() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Change species").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic fern is currently", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Species name") and hasSetTextAction()).assertIsNotFocused().performTextInput("nova")
        compose.onNodeWithContentDescription("Search species").performClick()
    }

    // C1b: a species search Fytorium did not answer shows in the line, and Try again runs it again.
    @Test fun tryAgainRunsASpeciesSearchFytoriumDidNotAnswer() {
        val failing = AtomicBoolean(true)
        withPlantServer({ if (it == "/api/species/search") speciesSearch else null }, status = { if (failing.get() && it == "/api/species/search") 503 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            searchChangeSpecies()
            compose.waitUntil(5_000) { compose.onAllNodes(notResponding).fetchSemanticsNodes().isNotEmpty() }
            failing.set(false)
            compose.onNode(notResponding).performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
        }
    }

    // C1b: a species search's failure belongs to the search page it ran on, so Change species and Add plant open again without it, also while their first query loads.
    @Test fun aFailedSearchStaysOffTheNextSearchPage() {
        val failing = AtomicBoolean(true)
        val holding = AtomicBoolean(false)
        val hold = java.util.concurrent.CountDownLatch(1)
        withPlantServer({ if (it == "/api/species/search") speciesSearch else null }, status = {
            if (holding.get() && it == "/api/species/search") hold.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (failing.get() && it == "/api/species/search") 503 else 200
        }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            searchChangeSpecies()
            compose.waitUntil(5_000) { compose.onAllNodes(notResponding).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            // Leaving with the typed query asks first (C19).
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Change species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic fern is currently", substring = true).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Add plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Species name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
            failing.set(false)
            holding.set(true)
            try {
                fun searches() = synchronized(paths()) { paths().count { it == "/api/species/search" } }
                val before = searches()
                compose.onNode(hasText("Species name") and hasSetTextAction()).performTextInput("nova")
                compose.onNodeWithContentDescription("Search species").performClick()
                compose.waitUntil(5_000) { searches() > before }
                compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
            } finally { hold.countDown() }
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // A pull or a return to the app reloads every read the page shows, so a failed choices read clears once Fytorium answers.
    @Test fun aPullOnTheGridReloadsAFailedRead() = reloadClearsAFailedRead("grid")
    @Test fun aPullOnThePlantPageReloadsAFailedRead() = reloadClearsAFailedRead("page")
    @Test fun returningToTheAppReloadsAFailedRead() = reloadClearsAFailedRead("resume")
    private fun reloadClearsAFailedRead(how: String) {
        val failing = AtomicBoolean(true)
        val app = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        withPlantServer(status = { if (failing.get() && it == "/api/choices") 503 else 200 }) { repo, showing ->
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides app) {
                if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id.takeIf { how == "page" }) } } }
            compose.waitUntil(5_000) { compose.onAllNodes(notResponding).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 &&
                (how != "page" || compose.onAllNodesWithTag("task:plant-one:water").fetchSemanticsNodes().isNotEmpty()) }
            failing.set(false)
            if (how == "resume") compose.runOnIdle {
                app.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
                app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            } else compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("isn't responding", substring = true)).fetchSemanticsNodes().isEmpty() }
        }
    }

    // C1b: a read's status shows only on pages that show what it read, so a refused species names read stays off the plant page Back returns to.
    @Test fun aRefusedSpeciesNamesReadStaysOffThePlantPage() {
        val species = "/api/species/910001"
        val refuse = AtomicBoolean(false)
        val holding = AtomicBoolean(false)
        val hold = java.util.concurrent.CountDownLatch(1)
        withPlantServer({ if (it == species) (if (refuse.get()) """{"message":"Synthetic read refusal"}""" else speciesNamesFixture) else null },
            status = {
                if (holding.get() && it in setOf("/api/choices", "/api/sites")) hold.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (refuse.get() && it == species) 403 else 200
            }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Your household added this name.").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            refuse.set(true)
            assertNull(repo.species(910001))
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(refused) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            // The plant page's own reads wait, so no read can succeed and lift a line before the check.
            holding.set(true)
            try {
                fun choices() = synchronized(paths()) { paths().count { it == "/api/choices" } }
                val before = choices()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings")).fetchSemanticsNodes().isNotEmpty() && choices() > before }
                compose.onNode(hasText(refused)).assertDoesNotExist()
            } finally { hold.countDown() }
            compose.waitUntil(5_000) { Running.count.get() == 0 }
            compose.onNode(hasText(refused)).assertDoesNotExist()
        }
    }

    // W1, C1b: the first 426 arriving through Species care, Sites or a photo request, from a plant page, a plant form or More, stops the whole app with the full-screen Update Fytorium.
    @Test fun aSpeciesCare426FromThePlantPageStopsTheApp() = updateStop({ it.startsWith("/api/species/") }) {
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        compose.onNodeWithTag("plant:plant-one").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        reveal(hasText("Details")).performClick()
        reveal(hasText("Care defaults for this species")).performClick()
    }
    @Test fun aSpeciesCare426FromMoreStopsTheApp() = updateStop({ it.startsWith("/api/species/") }) {
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Unmatched species").performClick()
    }
    @Test fun aSites426FromMoreStopsTheApp() = updateStop({ it == "/api/sites" }) {
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Sites").performClick()
    }
    // Restore reads the sites through the plant repository too, so only Create a site's own save gets the 426.
    @Test fun aSiteSave426FromRestoreStopsTheApp() = updateStop({ it == "/api/sites" }, post = true, served = ::restoreServed) {
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        compose.onNodeWithTag("plant:plant-one").performClick()
        openCreateASite()
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Synthetic porch")
        compose.onNodeWithText("Save site").performScrollTo().performClick()
    }
    @Test fun aJournal426StopsTheApp() = updateStop({ it == "/api/journal" }) {
        compose.onNode(hasText("Journal") and hasClickAction()).performClick()
    }
    @Test fun anIdentify426FromAddPlantStopsTheApp() = updateStop({ it == "/api/identification" }) {
        compose.onNodeWithContentDescription("Add plant").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Identify from photos") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Identify from photos").performClick()
    }
    private fun updateStop(refused: (String) -> Boolean, post: Boolean = false, served: (String) -> String? = { null }, open: () -> Unit) = withPlantServer(served,
        respond = { request -> MockResponse().setResponseCode(426).setBody("""{"error":"update_required","minAppVersion":"9.9.9"}""")
            .takeIf { refused(request.requestUrl!!.encodedPath) && (request.method == "POST") == post } }) { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
            // The controller's first refresh ends before any of these requests, so its success cannot lift a later stop.
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            open()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("This server needs a newer app. Install the latest Fytorium release.").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertTrue(repo.state.value.updateRequired) }
            compose.onNodeWithText("Care defaults for this species").assertDoesNotExist()
            compose.onNodeWithText("Unmatched species").assertDoesNotExist()
        } finally { controller.close() }
    }

    // C1b: a plant failure that is not a refusal names what happened instead of claiming Fytorium refused, and a save's own error never becomes the line.
    @Test fun aFailureThatIsNotARefusalSaysWhatHappened() {
        val request = mutableStateOf(PlantsRequestState(serverUnavailable = true, errors = mapOf("_form" to "Could not reach Fytorium. Try again.")))
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {}, request = request.value) } }
        compose.explains("Fytorium isn't responding", "Fytorium didn't answer the last plant request.")
        compose.onNode(hasText(refused, substring = true)).assertDoesNotExist()
        compose.runOnIdle { request.value = request.value.copy(serverUnavailable = false) }
        compose.onNode(hasText("Could not reach Fytorium", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
    }

    // C1b: losing and regaining the connection while a form is open moves no field, and a failed save keeps its error in the form.
    @Test fun connectionChangesMoveNoFieldWhileEditing() = connectionChangesMoveNoField(1f)
    @Test fun connectionChangesMoveNoFieldWhileEditingAtLargeText() = connectionChangesMoveNoField(1.3f)
    private fun connectionChangesMoveNoField(fontScale: Float) {
        val online = AtomicBoolean(true)
        val refuse = AtomicBoolean(false)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer(paths = paths, connected = { online.get() }, status = { if (refuse.get() && it == "/api/plants/plant-one") 403 else 200 }) { repo, showing ->
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            // Labels, not text actions, find the fields, because a disabled field has no text action.
            val name = hasText("Name")
            val forms = listOf(
                { compose.onNodeWithContentDescription("Edit plant").performClick() } to listOf(name, hasText("More details"), hasText("Save plant")),
                { compose.onNode(settings).performClick(); compose.onNode(hasText("Repot") and hasAnyAncestor(isPopup())).performClick() } to
                    listOf(hasText("New pot and soil for Synthetic fern"), hasText("Pot size in cm"), hasText("Repot Synthetic fern")),
                { compose.onNode(settings).performClick(); compose.onNode(hasText("This plant's schedule") and hasAnyAncestor(isPopup())).performClick() } to
                    listOf(hasText("Changes here apply only", substring = true), hasText("Water interval in days")))
            for ((open, fields) in forms) {
                open()
                compose.waitUntil(5_000) { compose.onAllNodes(fields.last()).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { Running.count.get() == 0 }
                fun bounds() = fields.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot }
                val before = bounds()
                online.set(false)
                repo.connectivityChanged(false)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Offline", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("A field moved when the connection went at ${fontScale}x text", before, bounds())
                assertTrue("The line sits above the form", compose.onNode(hasText("Offline", substring = true) and hasClickAction()).fetchSemanticsNode().boundsInRoot.bottom <= before.first().top)
                fun reads() = synchronized(paths) { paths.count { it == "/api/plants/plant-one" } }
                val offlineReads = reads()
                online.set(true)
                repo.connectivityChanged(true)
                repo.refresh()
                compose.waitUntil(5_000) { reads() > offlineReads && Running.count.get() == 0 &&
                    compose.onAllNodes(hasText("Offline", substring = true)).fetchSemanticsNodes().isEmpty() }
                assertEquals("A field moved when the connection came back at ${fontScale}x text", before, bounds())
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithContentDescription("Edit plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            refuse.set(true)
            compose.onNodeWithText("Save plant").performClick()
            compose.waitUntil(5_000) { repo.plantState.value.blocked }
            val error = compose.onNode(hasText("Your permissions no longer allow this change", substring = true)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The failed save shows in the form, under its fields", error.top >= compose.onNode(name).fetchSemanticsNode().boundsInRoot.bottom)
            refuse.set(false)
        }
    }

    // C1b: a plant read Fytorium does not answer while Today's state stays healthy shows in the bar's line, not above the page.
    @Test fun aPlantOnlyServerFailureShowsInTheBarLineAndMovesNothing() {
        val failing = AtomicBoolean(false)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer(paths = paths, status = { if (failing.get() && it == "/api/plants/plant-one") 503 else 200 }) { repo, showing ->
            val bounds = midList(repo, showing, paths)
            val before = bounds()
            failing.set(true)
            repo.openPlant(plant.id, full = true)
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(repo.state.value.serverUnavailable); assertFalse(repo.state.value.offline) }
            assertEquals("A row or control moved when a plant read failed", before, bounds())
            assertLineAbovePage("Fytorium isn't responding")
            compose.explains("Fytorium isn't responding", "Fytorium didn't answer the last plant request. Try again when it responds.")
            failing.set(false)
            repo.openPlant(plant.id, full = true)
            compose.waitForIdle()
            compose.onNode(hasText("isn't responding", substring = true)).assertDoesNotExist()
            assertEquals("A row or control moved when plant reads recovered", before, bounds())
        }
    }

    @Test fun theBarLineIsAtLeast48dpEachWay() {
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = today, detail = detail(), offline = true), null, initialPlant = plant.id) } }
        compose.onNode(hasText("Offline") and hasClickAction()).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun offTopBarControlsSayWhyOnTap() {
        val explained = mutableListOf<String>()
        val opened = mutableListOf<String>()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today, offline = true), detail(), { opened += "edit" }, { action, _ -> opened += action }, {}, {},
            enabled = false, onBlocked = { explained += it }, writeBlocked = "Changes need a connection.") } }
        compose.onNodeWithContentDescription("Edit plant").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Plant settings").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Delete plant").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("Changes need a connection.", "Changes need a connection."), explained); assertEquals(emptyList<String>(), opened) }
    }

    // C2: the first fresh read after opening from the cache applies once. A tall window composes the history entry.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aPageOpenedFromTheCacheTakesItsFirstFreshRead() {
        val release = java.util.concurrent.CountDownLatch(1)
        val opened = AtomicBoolean(false)
        // Another device undid this watering while the phone was elsewhere.
        val undone = JSONObject(eventFixture).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        try {
            withPlantServer({ path -> if (path != "/api/plants/plant-one/history") null
                else if (opened.get()) { release.await(10, java.util.concurrent.TimeUnit.SECONDS); "[$undone]" } else "[$eventFixture]" }) { repo, showing ->
                repo.openPlant(plant.id, full = true)
                opened.set(true)
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
                // The cached page is up and its entry moments have passed before the fresh read lands.
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("undo:event-one").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                release.countDown()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Undone").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("undo:event-one").assertDoesNotExist()
                reveal(hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")).assertIsEnabled()
            }
        } finally { release.countDown() }
    }

    // The window composes the history entry yet leaves the page room to scroll.
    @Config(qualifiers = "w360dp-h1100dp-mdpi")
    @Test fun aFirstFreshReadAfterTheUserScrolledWaitsForAPull() {
        val release = java.util.concurrent.CountDownLatch(1)
        val opened = AtomicBoolean(false)
        val undone = JSONObject(eventFixture).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        try {
            withPlantServer({ path -> if (path != "/api/plants/plant-one/history") null
                else if (opened.get()) { release.await(10, java.util.concurrent.TimeUnit.SECONDS); "[$undone]" } else "[$eventFixture]" }) { repo, showing ->
                repo.openPlant(plant.id, full = true)
                opened.set(true)
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("undo:event-one").fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
                release.countDown()
                compose.waitUntil(5_000) { repo.state.value.detail?.history?.singleOrNull()?.undoneAt != null }
                compose.waitForIdle()
                compose.onNodeWithText("Undone").assertDoesNotExist()
                reveal(hasTestTag("undo:event-one")).assertIsNotEnabled()
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Undone").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { release.countDown() }
    }

    // C25, C2: an Undo made elsewhere while the app was in the background shows in Care and History once the resume read lands, though the user scrolled before leaving.
    @Config(qualifiers = "w360dp-h1100dp-mdpi")
    @Test fun anUndoElsewhereWhileInTheBackgroundShowsWhenTheResumeReadLands() = undoElsewhereWhileInTheBackground(scrollWhileReading = false)
    // C2: a scroll after the resume, before its read lands, keeps the hold until a pull.
    @Config(qualifiers = "w360dp-h1100dp-mdpi")
    @Test fun aResumeReadAfterTheUserScrolledWaitsForAPull() = undoElsewhereWhileInTheBackground(scrollWhileReading = true)

    private fun undoElsewhereWhileInTheBackground(scrollWhileReading: Boolean) {
        val undone = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val watered = JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-09").put("lateDays", 0) }.toString()
        val app = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        fun reads() = synchronized(paths()) { paths().count { it == "/api/plants/plant-one/history" } }
        try {
            withPlantServer({ path -> when (path) {
                "/api/plants/plant-one/history" -> if (!undone.get()) "[$eventFixture]" else { release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    "[${JSONObject(eventFixture).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)}]" }
                "/api/plants/plant-one/tasks" -> watered.takeIf { !undone.get() }
                else -> null
            } }) { repo, showing ->
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides app) {
                    if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } } }
                compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one/photos" } == 2 } && Running.count.get() == 0 }
                compose.onNodeWithTag("undo:event-one").assertIsEnabled()
                val list = compose.onAllNodes(hasScrollAction()).onFirst()
                list.performTouchInput { swipeUp() }
                list.performScrollToIndex(0)
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
                undone.set(true)
                val before = reads()
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                // The resume moment has passed with the cached plant before its read lands.
                compose.waitUntil(5_000) { reads() > before }
                if (scrollWhileReading) list.performTouchInput { swipeUp() }
                compose.waitForIdle()
                release.countDown()
                val mark = hasContentDescription("Mark Water done for Synthetic fern, Synthetic site")
                fun fresh() = compose.onAllNodes(mark and isEnabled()).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("Undone").fetchSemanticsNodes().isNotEmpty()
                if (scrollWhileReading) {
                    compose.waitUntil(5_000) { repo.state.value.detail?.history?.singleOrNull()?.undoneAt != null && Running.count.get() == 0 }
                    compose.waitForIdle()
                    compose.onNodeWithText("Undone").assertDoesNotExist()
                    reveal(hasTestTag("undo:event-one")).assertIsNotEnabled()
                    reveal(hasContentDescription("Water done for Synthetic fern, Synthetic site", substring = true))
                    compose.onAllNodes(mark).assertCountEquals(0)
                    list.performScrollToIndex(0)
                    list.performTouchInput { swipeDown() }
                }
                compose.waitUntil(5_000) { fresh() }
                compose.onNodeWithText("1 day late").assertIsDisplayed()
                compose.onNodeWithTag("undo:event-one").assertDoesNotExist()
            }
        } finally { release.countDown() }
    }

    // C3: the name shows once at rest.
    @Test fun theTopBarTakesTheNameOnlyOnceTheHeadlineScrollsAway() {
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {}) } }
        val cover = compose.onNodeWithContentDescription("No cover photo for Synthetic fern").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onAllNodesWithText("Synthetic fern").fetchSemanticsNodes().single().boundsInRoot.top >= cover.bottom)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Details"))
        val title = compose.onAllNodesWithText("Synthetic fern").fetchSemanticsNodes().single()
        assertTrue("the bar shows the name", title.boundsInRoot.bottom <= 64f)
    }

    // C4: lateness wears Today's pill, and far dates read as dates.
    @Test fun lateRowsWearTodaysPillAndFarDatesReadAsDates() {
        val water = detail().scheduled("water")!!
        assertEquals("Due 3 Mar 2031", careStatus(water.copy(dueOn = "2031-03-03"), "2030-06-02"))
        assertEquals("Due in 14 days", careStatus(water.copy(dueOn = "2030-06-16"), "2030-06-02"))
        assertEquals("Due in 2 days", careStatus(water.copy(dueOn = "2030-06-04"), "2030-06-02"))
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {}) } }
        reveal(hasTestTag("task:plant-one:water")).assert(hasAnyDescendant(hasText("1d late")))
        compose.onNodeWithTag("task:plant-one:fertilize").assert(!hasAnyDescendant(hasText("late", substring = true)))
    }

    // C5: an early completion is not a task done for today.
    @Test fun handleAllSaysNothingDueTodayUnlessSomethingDueTodayWasDone() {
        val after = JSONObject(scheduleFixture).apply { getJSONObject("water").put("dueOn", "2030-06-16").put("lateDays", 0) }
        val showing = mutableStateOf(true)
        val page = mutableStateOf(PlantDetail(plant, listOf(event("event-early", "due_on" to "2030-06-09")), parseSchedule(after)))
        compose.setContent { FytoriumTheme { if (showing.value) PlantDetailScreen(owner.copy(today = today.copy(sections = emptyList())), page.value, {}, { _, _ -> }, {}, {}) } }
        reveal(hasText("Nothing due today")).assertIsDisplayed()
        compose.onNodeWithText("All done").assertDoesNotExist()
        compose.runOnIdle { showing.value = false; page.value = PlantDetail(plant, listOf(event("event-one")), parseSchedule(after)) }
        compose.runOnIdle { showing.value = true }
        reveal(hasText("All done")).assertIsDisplayed()
    }

    // C6: skip and snooze receipts and their Undo confirmations name the task.
    @Test fun skipAndSnoozeReceiptsNameTheTask() {
        assertEquals("Skipped cleaning leaves for Spider Plant until Tue 15 Dec", postponedText("skipped", "clean_leaves", null, "Spider Plant", "2026-12-15"))
        assertEquals("Snoozed Synthetic misting for Spider Plant until Tue 15 Dec", postponedText("snoozed", null, "Synthetic misting", "Spider Plant", "2026-12-15"))
        val skip = event("event-skip", "task_type" to "clean_leaves", "kind" to "skipped")
        assertEquals("Undid skip of cleaning leaves for Spider Plant", undoneText(skip, "Spider Plant", false))
    }

    // C18: skipping moves a task to today plus its interval, so Skip is not offered where that is the date the task already has.
    @Test fun skipIsOfferedOnlyWhereItWouldMoveTheDueDate() {
        val same = JSONObject(scheduleFixture).apply { getJSONObject("fertilize").put("dueOn", "2030-06-11") }
        val state = owner.copy(today = today, detail = PlantDetail(plant, emptyList(), parseSchedule(same)))
        compose.setContent { FytoriumTheme { PlantDetailScreen(state, state.detail!!, {}, { _, _ -> }, {}, {}) } }
        reveal(hasTestTag("task:plant-one:fertilize")).assertExists()
        compose.onAllNodes(hasContentDescription("More actions for Fertilize on Synthetic fern")).assertCountEquals(0)
        assertEquals(listOf("complete"), state.detail!!.scheduled("fertilize")!!.actions("2030-06-02", Member("member-one", Role.owner)))
        reveal(hasContentDescription("More actions for Water on Synthetic fern")).performClick()
        compose.onNode(hasContentDescription("Skip Water for Synthetic fern")).assertIsEnabled()
    }

    // C18: undoing this device's skip brings its row back, also when the skip had kept the date the task already had.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun undoingASkipThatKeptTheDueDateBringsTheRowBack() {
        val skip = JSONObject(eventFixture).put("id", "event-skip").put("task_type", "fertilize").put("kind", "skipped").put("due_on", "2030-06-11")
            .put("postponed_until", "2030-06-11").put("occurred_at", "2030-06-02T10:00:00.000Z")
        val undone = AtomicBoolean(false)
        fun schedule() = JSONObject(scheduleFixture).apply { getJSONObject("fertilize").put("dueOn", "2030-06-11").put("postponed", if (undone.get()) JSONObject.NULL else "skipped") }
        // The server names the unpostponed occurrence at the same date as the one this Undo brought back.
        val restored = """[{"event_id":"event-skip","occurrence":{"plant_id":"plant-one","task_type":"fertilize","custom_task_id":null,"due_on":"2030-06-11"}}]"""
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/tasks" -> schedule().toString()
            "/api/plants/plant-one/history" -> "[${if (undone.get()) JSONObject(skip.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false) else skip}]"
            "/api/care-events/event-skip/undo" -> { undone.set(true)
                """{"event":$skip,"task":${schedule().getJSONObject("fertilize")},"restored":$restored,"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}""" }
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } }
            val undo = hasContentDescription("Undo fertilizing skip for Synthetic fern, Synthetic site")
            compose.waitUntil(5_000) { compose.onAllNodes(undo and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(undo).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Mark Fertilize done for Synthetic fern, Synthetic site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(undo).assertDoesNotExist()
            compose.onAllNodesWithText(ChangedElsewhere).assertCountEquals(0)
        }
    }

    // C18: a snooze's Undo brings back its row as this device's own later writes left the task: here a liquid fertilizing watered the snoozed occurrence.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun undoingASnoozeAfterAnOwnLiquidFertilizingShowsTheWateringInItsRow() {
        fun event(id: String, type: String, kind: String, due: String) = JSONObject(eventFixture).put("id", id).put("task_type", type).put("kind", kind)
            .put("due_on", due).put("occurred_at", "2030-06-02T10:00:00.000Z")
        val snooze = event("event-snooze", "water", "snoozed", "2030-06-01").put("postponed_until", "2030-06-04")
        val fertilized = event("event-fertilize", "fertilize", "completed", "2030-06-07")
        val watered = event("event-water", "water", "completed", "2030-06-04").put("linked_event_id", "event-fertilize")
        fun task(type: String, due: String, postponed: String? = null) = JSONObject(scheduleFixture).getJSONObject(type).put("dueOn", due).put("lateDays", 0)
            .put("postponed", postponed ?: JSONObject.NULL)
        fun row(type: String, due: String, postponed: String? = null, done: JSONObject? = null) = task(type, due, postponed).put("task_type", type)
            .put("custom_task_id", JSONObject.NULL).put("plant_id", "plant-one").put("plant_name", "Synthetic fern").put("site_name", "Synthetic site")
            .apply { if (done != null) put("postponed", JSONObject.NULL).put("completed_event", done).put("postponed_until", done.opt("postponed_until")) }
        fun list(view: String, vararg sections: Pair<String, List<JSONObject>>) = JSONObject(taskListFixture(view)).put("view", view)
            .put("sections", JSONArray(sections.map { (key, rows) -> JSONObject().put("key", key).put("label", key).put("tasks", JSONArray(rows))
                .apply { if (view == "upcoming") put("day", key.substringBefore(":")) } }))
        val snoozed = row("water", "2030-06-04", done = snooze)
        val wateredRow = row("water", "2030-06-09", done = watered)
        val fertilizedRow = row("fertilize", "2030-06-11", done = fertilized)
        val step = AtomicInteger(0)
        val lists = listOf(
            list("today", "water" to listOf(row("water", "2030-06-01"))) to list("upcoming", "2030-06-02:water" to listOf(row("water", "2030-06-01")), "2030-06-07:fertilize" to listOf(row("fertilize", "2030-06-07"))),
            list("today", "water" to listOf(snoozed)) to list("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(row("water", "2030-06-04", "snoozed")),
                "2030-06-07:fertilize" to listOf(row("fertilize", "2030-06-07"))),
            list("today", "water" to listOf(snoozed, wateredRow), "fertilize" to listOf(fertilizedRow)) to list("upcoming", "2030-06-02:water" to listOf(snoozed),
                "2030-06-04:water" to listOf(wateredRow), "2030-06-07:fertilize" to listOf(fertilizedRow)),
            list("today", "water" to listOf(wateredRow), "fertilize" to listOf(fertilizedRow)) to list("upcoming", "2030-06-04:water" to listOf(wateredRow),
                "2030-06-07:fertilize" to listOf(fertilizedRow)))
        val schedules = listOf(JSONObject(scheduleFixture), JSONObject(scheduleFixture).put("water", task("water", "2030-06-04", "snoozed")),
            JSONObject(scheduleFixture).put("water", task("water", "2030-06-09")).put("fertilize", task("fertilize", "2030-06-11")))
        val undoneSnooze = JSONObject(snooze.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        val histories = listOf("[]", "[$snooze]", "[$watered,$fertilized,$snooze]", "[$watered,$fertilized,$undoneSnooze]")
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/tasks" -> schedules[minOf(step.get(), 2)].toString()
            "/api/plants/plant-one/history" -> histories[step.get()]
            "/api/plants/plant-one/tasks/water/snooze" -> { step.set(1); JSONObject().put("event", snooze).put("linked_events", JSONArray()).put("task", task("water", "2030-06-04", "snoozed")).toString() }
            "/api/plants/plant-one/tasks/fertilize/complete" -> { step.set(2)
                JSONObject().put("event", fertilized).put("linked_events", JSONArray().put(watered)).put("task", task("fertilize", "2030-06-11")).toString() }
            // Nothing is pending after the Undo, because the fertilizing's water completed the occurrence the snooze had postponed.
            "/api/care-events/event-snooze/undo" -> { step.set(3)
                JSONObject().put("event", snooze).put("task", task("water", "2030-06-09")).put("today", lists[3].first).put("upcoming", lists[3].second)
                    .put("restored", JSONArray().put(JSONObject().put("event_id", "event-snooze").put("occurrence", JSONObject.NULL))).toString() }
            else -> null
        } }, lists = lists[0].first.toString(), views = { view -> lists[step.get()].let { if (view == "today") it.first else it.second }.toString() }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } }
            val undoSnooze = hasContentDescription("Undo watering snooze for Synthetic fern, Synthetic site")
            val snoozeWater = hasContentDescription("Snooze Water for Synthetic fern, Synthetic site by 2 days")
            compose.waitUntil(5_000) { compose.onAllNodes(snoozeWater and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val place = reveal(hasTestTag("task:plant-one:water")).fetchSemanticsNode().boundsInRoot
            compose.onNode(snoozeWater).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(undoSnooze and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasContentDescription("Mark Fertilize done for Synthetic fern, Synthetic site")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Fertilize and water done for Synthetic fern", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { repo.state.value.pending.isEmpty() }
            compose.onNode(undoSnooze).assertIsEnabled().performClick()
            val water = hasContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo")
            compose.waitUntil(5_000) { compose.onAllNodes(water and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(undoSnooze).assertDoesNotExist()
            assertEquals(place, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
        }
    }

    // C18: after this device completed the occurrence its snooze had postponed, undoing the snooze shows that occurrence done in the row, once.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun undoingASnoozeWhoseOccurrenceThisDeviceCompletedShowsItDoneOnce() {
        fun event(id: String, kind: String, due: String) = JSONObject(eventFixture).put("id", id).put("task_type", "water").put("kind", kind)
            .put("due_on", due).put("occurred_at", "2030-06-02T10:00:00.000Z")
        val snooze = event("event-snooze", "snoozed", "2030-06-01").put("postponed_until", "2030-06-04")
        val completion = event("event-postponed", "completed", "2030-06-04")
        fun task(due: String, postponed: String? = null) = JSONObject(scheduleFixture).getJSONObject("water").put("dueOn", due).put("lateDays", 0)
            .put("postponed", postponed ?: JSONObject.NULL)
        fun row(due: String, postponed: String? = null, done: JSONObject? = null) = task(due, postponed).put("task_type", "water")
            .put("custom_task_id", JSONObject.NULL).put("plant_id", "plant-one").put("plant_name", "Synthetic fern").put("site_name", "Synthetic site")
            .apply { if (done != null) put("postponed", JSONObject.NULL).put("completed_event", done).put("postponed_until", done.opt("postponed_until")) }
        fun list(view: String, vararg sections: Pair<String, List<JSONObject>>) = JSONObject(taskListFixture(view)).put("view", view)
            .put("sections", JSONArray(sections.map { (key, rows) -> JSONObject().put("key", key).put("label", key).put("tasks", JSONArray(rows))
                .apply { if (view == "upcoming") put("day", key.substringBefore(":")) } }))
        val snoozed = row("2030-06-04", done = snooze)
        val completed = row("2030-06-11", done = completion)
        val step = AtomicInteger(0)
        val lists = listOf(
            list("today", "water" to listOf(row("2030-06-01"))) to list("upcoming", "2030-06-02:water" to listOf(row("2030-06-01"))),
            list("today", "water" to listOf(snoozed)) to list("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(row("2030-06-04", "snoozed"))),
            list("today", "water" to listOf(snoozed, completed)) to list("upcoming", "2030-06-02:water" to listOf(snoozed), "2030-06-04:water" to listOf(completed),
                "2030-06-11:water" to listOf(row("2030-06-11"))),
            list("today", "water" to listOf(completed)) to list("upcoming", "2030-06-04:water" to listOf(completed), "2030-06-11:water" to listOf(row("2030-06-11"))))
        val schedules = listOf(JSONObject(scheduleFixture), JSONObject(scheduleFixture).put("water", task("2030-06-04", "snoozed")),
            JSONObject(scheduleFixture).put("water", task("2030-06-11")))
        val undoneSnooze = JSONObject(snooze.toString()).put("undone_at", "2030-06-02T10:05:00Z").put("can_undo", false)
        val histories = listOf("[]", "[$snooze]", "[$completion,$snooze]", "[$completion,$undoneSnooze]")
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/tasks" -> schedules[minOf(step.get(), 2)].toString()
            "/api/plants/plant-one/history" -> histories[step.get()]
            "/api/plants/plant-one/tasks/water/snooze" -> { step.set(1); JSONObject().put("event", snooze).put("linked_events", JSONArray()).put("task", task("2030-06-04", "snoozed")).toString() }
            "/api/plants/plant-one/tasks/water/complete" -> { step.set(2); JSONObject().put("event", completion).put("linked_events", JSONArray()).put("task", task("2030-06-11")).toString() }
            // Nothing is pending after the Undo, because this device completed the occurrence the snooze had postponed.
            "/api/care-events/event-snooze/undo" -> { step.set(3)
                JSONObject().put("event", snooze).put("task", task("2030-06-11")).put("today", lists[3].first).put("upcoming", lists[3].second)
                    .put("restored", JSONArray().put(JSONObject().put("event_id", "event-snooze").put("occurrence", JSONObject.NULL))).toString() }
            else -> null
        } }, lists = lists[0].first.toString(), views = { view -> lists[step.get()].let { if (view == "today") it.first else it.second }.toString() }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, onTaskAction = { task, action -> repo.act(task, action) }) } }
            val undoSnooze = hasContentDescription("Undo watering snooze for Synthetic fern, Synthetic site")
            val snoozeWater = hasContentDescription("Snooze Water for Synthetic fern, Synthetic site by 2 days")
            compose.waitUntil(5_000) { compose.onAllNodes(snoozeWater and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val place = reveal(hasTestTag("task:plant-one:water")).fetchSemanticsNode().boundsInRoot
            compose.onNode(snoozeWater).performClick()
            fun postponed() = repo.state.value.upcoming?.sections.orEmpty().flatMap { it.tasks }.singleOrNull { it.postponed == "snoozed" }
            compose.waitUntil(5_000) { compose.onAllNodes(undoSnooze and isEnabled()).fetchSemanticsNodes().isNotEmpty() && repo.state.value.pending.isEmpty() && postponed() != null }
            // This device completes the postponed occurrence from Upcoming.
            assertTrue(repo.act(postponed()!!, "complete"))
            compose.waitUntil(5_000) { compose.onAllNodes(undoSnooze and isEnabled()).fetchSemanticsNodes().isNotEmpty() && repo.state.value.pending.isEmpty() }
            compose.onNode(undoSnooze).performClick()
            val done = hasContentDescription("Water done for Synthetic fern, Synthetic site. Double tap to undo")
            compose.waitUntil(5_000) { compose.onAllNodes(done and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(undoSnooze).assertDoesNotExist()
            compose.onAllNodesWithTag("task:plant-one:water").assertCountEquals(1)
            compose.onAllNodes(hasText("Undone") and hasAnyAncestor(hasTestTag("task:plant-one:water"))).assertCountEquals(0)
            assertEquals(place, compose.onNodeWithTag("task:plant-one:water").fetchSemanticsNode().boundsInRoot)
        }
    }

    // C7: a Based on entry never repeats its sentence.
    @Test fun basedOnDropsAReasonThatRepeatsTheExplanation() {
        val sentence = "Synthetic pots need a root-space check every two years."
        val schedule = JSONObject(scheduleFixture).apply { getJSONObject("water").put("basedOn", JSONArray().put(JSONObject()
            .put("input", "potSizeCm").put("value", 14).put("source", "rule").put("adjustment", sentence).put("ruleId", "repot.synthetic@1").put("reason", sentence))) }
        // Today does not list the plant, so its Water row comes from this schedule.
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today.copy(sections = emptyList())), PlantDetail(plant, emptyList(), parseSchedule(schedule)), {}, { _, _ -> }, {}, {}) } }
        reveal(hasText("Details")).performClick()
        reveal(hasText("Based on") and hasAnyAncestor(hasTestTag("basis:water"))).performClick()
        reveal(hasText(sentence))
        compose.onAllNodesWithText(sentence).assertCountEquals(1)
    }

    // C8: tiles clip only their cover, so a site's first letter keeps its stem.
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    @Test fun tileTextIsNotClippedByTheTileCorner() {
        var host: android.view.View? = null
        compose.setContent { host = androidx.compose.ui.platform.LocalView.current; FytoriumTheme { PlantsList(owner, listOf(plant.copy(siteName = "Bright synthetic room")), false, {}, {}, {}, {}) } }
        val text = compose.onNode(hasText("Bright synthetic room") and hasAnyAncestor(hasTestTag("plant:plant-one")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        var stem = 0
        compose.runOnUiThread {
            val image = android.graphics.Bitmap.createBitmap(host!!.width, host!!.height, android.graphics.Bitmap.Config.ARGB_8888)
            host!!.draw(android.graphics.Canvas(image))
            val left = text.left.toInt()
            val bottom = text.bottom.toInt()
            val background = image.getPixel(left, text.top.toInt() + 1)
            fun ink(color: Int) = listOf(16, 8, 0).any { shift -> kotlin.math.abs((color shr shift and 0xff) - (background shr shift and 0xff)) > 60 }
            // The rows 4 to 6.5 dp above the text's bottom edge, 3 dp from its left: the B's stem, which a 16 dp tile corner would cut.
            stem = (0 until 6).sumOf { x -> (bottom - 13 until bottom - 8).count { y -> ink(image.getPixel(left + x, y)) } }
            image.recycle()
        }
        assertTrue("the corner cut the site's first letter ($stem ink pixels)", stem > 3)
    }

    // C9: a new query starts at its first result.
    @Test fun aNewSearchStartsAtTheTopOfItsResults() {
        val plants = (0 until 30).map { index -> plant.copy(id = "plant-$index", displayName = "Synthetic fern $index", siteName = "Synthetic site ${'a' + index / 2}") }
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(plants = plants), null) } }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("plant:plant-29"))
        compose.onNode(hasText("Synthetic site a") and isHeading()).assertDoesNotExist()
        compose.onNode(hasText("Search plants") and hasSetTextAction()).performTextInput("fern")
        compose.onNode(hasText("Synthetic site a") and isHeading()).assertIsDisplayed()
    }

    // C10: system Back from an original photo returns to the updates, like the toolbar arrow. A tall window composes the photo strip.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun systemBackFromAnOriginalPhotoReturnsToTheUpdates() {
        val photo = """{"id":"photo-one","plant_id":"plant-one","plant_name":"Synthetic fern","note":"","health":null,"taken_on":"2030-06-01","is_cover":false}"""
        withPlantServer({ path -> when (path) { "/api/plants/plant-one/photos" -> "[$photo]"; "/api/photos/photo-one/original" -> "synthetic image bytes"; else -> null } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("See all")).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("See all")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Open original photo of Synthetic fern") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open original photo of Synthetic fern").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Photo") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNode(hasText("Synthetic fern updates") and isHeading()).assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("History") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // C12: the schedule and repot forms ask before dropping typed changes.
    @Test fun scheduleAndRepotAskBeforeDroppingTypedChanges() = withPlantServer { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        for ((item, field, typed) in listOf(Triple("This plant's schedule", "Water interval in days", "5"), Triple("Repot", "Pot size in cm", "16"))) {
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNode(hasText(item) and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(field) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(field) and hasSetTextAction()).performTextReplacement(typed)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(hasText(field) and hasSetTextAction()).assertTextContains(typed)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(field) and hasSetTextAction()).assertDoesNotExist()
        }
    }

    // C19: a link inside a form asks first, and Back from the page it opened returns to the form with what was typed.
    @Test fun careDefaultsFromTheScheduleAskFirstAndBackReturnsWithTheTypedValue() = withPlantServer({ path -> when (path) {
        "/api/species/910001/care" -> careFixture.replace("pending", "done")
        "/api/species/910001/outdoor-months" -> outdoorFixture
        else -> null
    } }) { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNode(hasText("This plant's schedule") and hasAnyAncestor(isPopup())).performClick()
        val water = hasText("Water interval in days") and hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(water).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(water).performTextReplacement("9")
        compose.onNode(hasText("Care defaults for this species") and hasClickAction()).performClick()
        compose.onNodeWithText("Open care defaults for this species?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNode(water).assertTextContains("9")
        compose.onNode(hasText("Care defaults for this species") and hasClickAction()).performClick()
        compose.onNodeWithText("Open care defaults").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(water).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(water).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(water).assertTextContains("9")
        // The form still holds what was typed, so its own Back still asks.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
    }

    // C19, C26: Identify from photos asks first, naming the plant, when a query was typed or a species was chosen, and Back from it returns to the species search with that query.
    @Config(shadows = [com.epaflix.fytorium.photos.JvmImageSources::class])
    @Test fun backFromIdentifyReturnsToTheSpeciesSearchWithItsQuery() = withPlantServer({ path -> if (path == "/api/identification") """{"remaining":20}""" else null },
        posted = { path -> if (path == "/api/identification") identified else null }) { repo, showing ->
        val picker = photoPicker()
        compose.setContent { if (showing.value) CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides picker) { FytoriumTheme {
            val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Change species").performClick()
        val query = hasText("Species name") and hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(query).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(query).performTextInput("nova")
        val identify = hasText("Identify from photos") and hasClickAction() and !hasAnyAncestor(isDialog())
        compose.onNode(identify).performClick()
        compose.onNodeWithText("Identify from photos?").assertIsDisplayed()
        compose.onNodeWithText("Your species search for Synthetic fern stays here when you come back.").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNode(query).assertTextContains("nova")
        compose.onNode(identify).performClick()
        compose.onNode(hasText("Identify from photos") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(query).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(query).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(query).assertTextContains("nova")
        // With nothing typed, a species chosen through Identify from photos is unsaved too, so Identify asks again.
        compose.onNode(query).performTextClearance()
        compose.onNode(identify).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose photos") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose photos").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Identify plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Identify plant").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose Testoria nebulosa") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose Testoria nebulosa").performScrollTo().performClick()
        compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().performClick()
        val chosen = hasText("Chosen species", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(chosen).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(identify).performClick()
        compose.onNodeWithText("Identify from photos?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNode(chosen).assertIsDisplayed()
    }

    // C19: leaving a plant form through the navigation bar asks first, as its Back does.
    @Test fun leavingAFormThroughTheNavigationBarAsksFirst() = withPlantServer { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNode(hasText("This plant's schedule") and hasAnyAncestor(isPopup())).performClick()
            val water = hasText("Water interval in days") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(water).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(water).performTextReplacement("9")
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(water).assertTextContains("9")
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(water).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        } finally { controller.close() }
    }

    // C19: a notification tap that opens Home asks first too, with the same question as Back.
    @Test fun aNotificationTapFromAFormWithChangesAsksFirst() = withPlantServer { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        val home = mutableIntStateOf(0)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }, homeRequest = home.intValue) } }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit plant").performClick()
            val name = hasText("Name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).performTextReplacement("Fern")
            compose.runOnIdle { home.intValue++ }
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(name).assertTextContains("Fern")
            compose.runOnIdle { home.intValue++ }
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("view-today")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).assertDoesNotExist()
        } finally { controller.close() }
    }

    // C19: a notification tap from the override dialog in Care defaults for this species, opened from the plant page, asks first with the dialog's question.
    @Test fun aNotificationTapFromTheOverrideDialogOnThePlantPageAsksFirst() = withPlantServer({ path -> when (path) {
        "/api/species/910001/care" -> careFixture.replace("pending", "done")
        "/api/species/910001/outdoor-months" -> outdoorFixture
        else -> null
    } }) { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        val home = mutableIntStateOf(0)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }, homeRequest = home.intValue) } }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Care defaults for this species")).performClick()
            val open = hasText("Override watering interval in days") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(open).performScrollTo().performClick()
            compose.onNodeWithText("Days").performTextReplacement("9")
            compose.runOnIdle { home.intValue++ }
            compose.assertDiscard("Your watering interval override for Testoria nebulosa Inventor is not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Days").assertTextContains("9")
            compose.runOnIdle { home.intValue++ }
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("view-today")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Days").assertDoesNotExist()
        } finally { controller.close() }
    }

    // C19: Discard from the navigation bar drops the form even when the tap stays on Plants, so Log out under More never follows unsaved changes.
    @Test fun logOutNeverFollowsAnEditWithChangesWithoutAsking() = withPlantServer { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit plant").performClick()
            val name = hasText("Name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).performTextReplacement("Fern")
            compose.onNode(hasText("More") and hasClickAction()).performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(name).assertTextContains("Fern")
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(name).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasText("More") and hasClickAction()).performClick()
            compose.onNodeWithText("Discard changes?").assertDoesNotExist()
            compose.onNodeWithText("Log out").assertIsDisplayed()
        } finally { controller.close() }
    }

    // The photo picker answers at once with a synthetic image.
    private fun photoPicker(): androidx.activity.result.ActivityResultRegistryOwner {
        val file = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "synthetic-identify.png")
        android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        return object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                    options: androidx.core.app.ActivityOptionsCompat?) { dispatchResult(requestCode, listOf(android.net.Uri.fromFile(file))) }
            }
        }
    }
    private val identified = """{"search_id":"synthetic-search","remaining":20,"candidates":[{"gbif_key":900001,"scientific_name":"Testoria nebulosa","canonical_name":"Testoria nebulosa","rank":"SPECIES","match_type":null,"confidence":null,"requires_confirmation":true,"source":"identification","score":0.91,"identification_name":"Testoria nebulosa","common_names":["Synthetic cloud plant"]}]}"""

    // C19: Add plant asks before Back drops a typed species query or a species chosen only through Identify from photos.
    @Config(shadows = [com.epaflix.fytorium.photos.JvmImageSources::class])
    @Test fun addPlantAsksBeforeDroppingATypedQueryOrASpeciesChosenThroughIdentify() {
        val picker = photoPicker()
        withPlantServer({ path -> if (path == "/api/identification") """{"remaining":20}""" else null },
            posted = { path -> if (path == "/api/identification") identified else null }) { repo, showing ->
            compose.setContent { if (showing.value) CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides picker) { FytoriumTheme {
                val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialAdd = true, photosApi = api, photosConnected = { true }) } } }
            val query = hasText("Species name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(query).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(query).performTextInput("nova")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to this new plant are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(query).assertTextContains("nova")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard").performClick()
            val add = hasContentDescription("Add plant") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(query).assertDoesNotExist()
            compose.onNode(add).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Identify from photos") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Identify from photos").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose photos") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Choose photos").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Identify plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Identify plant").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose Testoria nebulosa") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Choose Testoria nebulosa").performScrollTo().performClick()
            compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().performClick()
            val name = hasText("Name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to this new plant are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(name).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).assertDoesNotExist()
        }
    }

    // C19: Species names and Change species ask before Back drops a typed name or query.
    @Test fun speciesNamesAndChangeSpeciesAskBeforeDroppingWhatWasTyped() = withPlantServer({ path -> if (path == "/api/species/910001") speciesNamesFixture else null }) { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
        val settings = hasContentDescription("Plant settings") and isEnabled()
        compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
        val pages = listOf(
            { reveal(hasText("Details")).performClick(); reveal(hasText("Species names")).performClick() } to (hasText("Common name") and hasSetTextAction()),
            { compose.onNode(settings).performClick(); compose.onNode(hasText("Change species") and hasAnyAncestor(isPopup())).performClick() } to (hasText("Species name") and hasSetTextAction()))
        for ((open, field) in pages) {
            open()
            compose.waitUntil(5_000) { compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(field).performTextInput("Synthetic star")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(field).assertTextContains("Synthetic star")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(field).assertDoesNotExist()
        }
    }

    // C19: Restore asks before Back drops a site chosen there.
    @Test fun restoreAsksBeforeDroppingAChosenSite() {
        val buried = JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()
        val sites = """[{"id":"site-one","name":"Synthetic site"},{"id":"site-porch","name":"Synthetic porch"}]"""
        withPlantServer({ path -> when (path) { "/api/plants/plant-one" -> buried; "/api/sites" -> sites; else -> null } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(settings).performClick()
            compose.onNodeWithText("Restore plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Restore Synthetic fern to Synthetic site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Site") and hasText("Synthetic site")).performClick()
            compose.onNode(hasText("Synthetic porch") and hasAnyAncestor(isPopup())).performClick()
            val porch = hasText("Restore Synthetic fern to Synthetic porch")
            compose.onNode(porch).assertIsEnabled()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(porch).assertIsEnabled()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(porch).assertDoesNotExist()
        }
    }

    // C20: a site created from Restore is the site Restore then uses.
    @Test fun aSiteCreatedFromRestoreIsSelectedWhenTheFormReturns() {
        val buried = JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()
        val site = """{"id":"site-one","name":"Synthetic site","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":1}"""
        val porch = """{"id":"site-porch","name":"Synthetic porch","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":0}"""
        val created = AtomicBoolean(false)
        withPlantServer({ path -> when (path) { "/api/plants/plant-one" -> buried; "/api/sites" -> if (created.get()) "[$porch,$site]" else "[$site]"; else -> null } },
            posted = { path -> if (path == "/api/sites") { created.set(true); porch } else null }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Restore plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Restore Synthetic fern to Synthetic site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create a site").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Synthetic porch")
            compose.onNodeWithText("Save site").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Restore Synthetic fern to Synthetic porch") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // C26: Create a site from a Restore form with a changed site asks first, like the schedule's Care defaults link, and Back keeps the chosen site.
    @Test fun createASiteFromAChangedRestoreAsksFirstAndBackKeepsTheChoice() = changedRestore(1f)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun restoreLinkAndSitePickerWorkInLandscapeWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        changedRestore(1.3f)
    }

    private fun changedRestore(fontScale: Float) {
        val site = """{"id":"site-one","name":"Synthetic site","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":1}"""
        val porch = """{"id":"site-porch","name":"Synthetic porch","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":0}"""
        withPlantServer({ path -> when (path) { "/api/sites" -> "[$site,$porch]"; else -> restoreServed(path) } }) { repo, showing ->
            compose.setContent {
                if (fontScale == 1.3f) CheckLandscape(fontScale)
                if (showing.value) FytoriumTheme {
                    val care by repo.state.collectAsState()
                    PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true })
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Restore plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Restore Synthetic fern to Synthetic site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Site") and hasText("Synthetic site")).performScrollTo().performClick()
            compose.onNode(hasText("Synthetic porch") and hasAnyAncestor(isPopup())).performScrollTo().assertIsDisplayed().performClick()
            val chosen = hasText("Restore Synthetic fern to Synthetic porch") and isEnabled()
            compose.onNode(chosen).assertExists()
            val link = hasText("Create a site") and !hasAnyAncestor(isDialog())
            compose.onNode(link).performScrollTo().performClick()
            compose.onNodeWithText("Create a site?").assertIsDisplayed()
            compose.checkDialogTextScale("Create a site?", fontScale)
            compose.onNodeWithText("Synthetic fern is not restored yet. If you come back without creating a site, your choice stays. A site you create is chosen instead.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Keep editing").assertIsDisplayed().performClick()
            compose.onNode(chosen).assertExists()
            compose.onNodeWithText("Save site").assertDoesNotExist()
            compose.onNode(link).performScrollTo().performClick()
            compose.onNode(hasText("Create a site") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(chosen).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // C21: once Fytorium answers another plant request, the list loads what it left unanswered and its line clears without a tap.
    @Test fun theListLoadsAReadFytoriumLeftUnansweredOnceItAnswersAgain() {
        val down = AtomicBoolean(true)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        withPlantServer(paths = paths, status = { path -> if (down.get() && path == "/api/plants") 503 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Fytorium isn't responding").fetchSemanticsNodes().isNotEmpty() }
            down.set(false)
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Graveyard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Graveyard") and isHeading()).fetchSemanticsNodes().isNotEmpty() && !repo.plantState.value.refreshing }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Fytorium isn't responding").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { assertEquals(emptyMap<String, ReadFailure>(), repo.plantState.value.reads) }
        }
    }

    // C21: Species care loads what Fytorium left unanswered once Fytorium answers another request, clears its line without a tap and polls again.
    @Test fun speciesCareLoadsWhatFytoriumLeftUnansweredOnceItAnswersAgain() {
        val down = AtomicBoolean(false)
        val care = "/api/species/910001/care"
        withPlantServer({ path -> when (path) { care -> careFixture; "/api/species/910001/outdoor-months" -> outdoorFixture; else -> null } },
            status = { path -> if (down.get() && path.startsWith("/api/species/")) 503 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val state by repo.state.collectAsState()
                PlantsScreen(state, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            down.set(true)
            reveal(hasText("Details")).performClick()
            reveal(hasText("Care defaults for this species")).performClick()
            val failure = "Could not reach the server. Try again."
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(failure) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            down.set(false)
            repo.refresh()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText(failure)).assertCountEquals(0)
            val reads = synchronized(paths()) { paths().count { it == care } }
            compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == care } } > reads }
        }
    }

    // C22: the names page says only that names are still loading while a fetch runs, with no job or retry wording, and nothing once it gives up.
    @Test fun speciesNamesSayOnlyThatSomeNamesAreStillLoading() {
        fun species(fetching: Boolean, failed: Boolean) = parseSpecies("""{"gbif_key":910001,"scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida",
            "rank":"SPECIES","name_fetch_needed":$fetching,"name_fetch_failed":$failed,"common_name":null,"names":[]}""")
        val state = mutableStateOf(species(fetching = true, failed = false))
        compose.setContent { FytoriumTheme { SpeciesNames(state.value, syntheticOptions(), true, { _, _, _ -> }) } }
        val jobWords = listOf("background", "backoff", "attempt", "retry", "failure")
        reveal(hasText("Some names are still loading.")).assertIsDisplayed()
        for (word in jobWords) compose.onAllNodes(hasText(word, substring = true, ignoreCase = true)).assertCountEquals(0)
        compose.runOnIdle { state.value = species(fetching = false, failed = true) }
        compose.onNodeWithText("Some names are still loading.").assertDoesNotExist()
        for (word in jobWords) compose.onAllNodes(hasText(word, substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    // C23: a failed save's message appears under its button, so the button stays where the finger is.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aFailedSavesMessageNeverMovesItsButton() {
        val errors = mutableStateOf(emptyMap<String, String>())
        val form = mutableStateOf("edit")
        compose.setContent { FytoriumTheme { when (form.value) {
            "edit" -> PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, errors = errors.value, onSave = {})
            "repot" -> PlantRepotForm(plant, syntheticOptions(), true, errors = errors.value, onSave = {})
            else -> RestoreForm(plant.copy(status = "graveyard"), listOf(PlantSite("site-one", "Synthetic site")), true, {}, error = errors.value["_form"])
        } } }
        val failure = "Could not reach Fytorium. Try again."
        for ((name, button) in listOf("edit" to "Save plant", "repot" to "Repot Synthetic fern", "restore" to "Restore Synthetic fern to Synthetic site")) {
            compose.runOnIdle { form.value = name; errors.value = emptyMap() }
            val before = compose.onNodeWithText(button).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { errors.value = mapOf("_form" to failure) }
            val message = compose.onNodeWithText(failure).fetchSemanticsNode().boundsInRoot
            assertEquals("$name moved its button", before, compose.onNodeWithText(button).fetchSemanticsNode().boundsInRoot)
            assertTrue("$name shows the message under its button", message.top >= before.bottom)
        }
    }

    // C23: the same holds for a species change Fytorium does not accept.
    @Test fun aFailedSpeciesChangeLeavesItsButtonInPlace() {
        val search = """{"search_id":"search-one","candidates":[{"gbif_key":910019,"scientific_name":"Testoria nova Inventor","canonical_name":"Testoria nova","rank":"SPECIES","match_type":"EXACT","confidence":99,"requires_confirmation":false,"matched_name":null,"matched_language":null,"common_name":"Synthetic star"}]}"""
        withPlantServer({ path -> if (path == "/api/species/search") search else null }, status = { if (it == "/api/plants/plant-one/species") 503 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Change species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Species name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Species name") and hasSetTextAction()).performTextInput("nova")
            compose.onNodeWithContentDescription("Search species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(starResult).performScrollTo().performClick()
            val change = hasText("Change Synthetic fern to Synthetic star")
            val before = compose.onNode(change).fetchSemanticsNode().boundsInRoot
            compose.onNode(change).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Could not update Fytorium. Try again.").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodes(change and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, compose.onNode(change).fetchSemanticsNode().boundsInRoot)
            assertTrue(compose.onNodeWithText("Could not update Fytorium. Try again.").fetchSemanticsNode().boundsInRoot.top >= before.bottom)
        }
    }

    // C23: a site that vanished before Save shows next to Site, in space kept for it, so Save stays where it was.
    @Test fun aSiteThatVanishedBeforeSaveShowsNextToSiteWithoutMovingSave() {
        val vanished = AtomicBoolean(false)
        val message = "Choose a site in your household."
        withPlantServer(status = { path -> if (vanished.get() && path == "/api/plants/plant-one") 422 else 200 },
            posted = { path -> if (path == "/api/plants/plant-one") JSONObject().put("errors", JSONObject().put("site_id", message)).toString() else null }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit plant").performClick()
            val save = hasText("Save plant")
            compose.waitUntil(5_000) { compose.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            val before = compose.onNode(save).fetchSemanticsNode().boundsInRoot
            val site = compose.onNode(hasText("Site") and hasText("Synthetic site")).fetchSemanticsNode().boundsInRoot
            vanished.set(true)
            compose.onNode(save).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Save moved", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
            val error = compose.onNode(hasText(message)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The message sits at Site", error.top >= site.top && error.bottom <= compose.onNodeWithText("More details").fetchSemanticsNode().boundsInRoot.top)
        }
    }

    // C23: a refused field under More details opens it next to its field, below Save, so Save stays where it was.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aRefusedDetailsFieldOpensMoreDetailsWithoutMovingSave() {
        val refused = AtomicBoolean(false)
        val message = "Enter a valid date that is not in the future."
        withPlantServer(status = { path -> if (refused.get() && path == "/api/plants/plant-one") 422 else 200 },
            posted = { path -> if (path == "/api/plants/plant-one") JSONObject().put("errors", JSONObject().put("birthday", message)).toString() else null }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit plant").performClick()
            val save = hasText("Save plant")
            compose.waitUntil(5_000) { compose.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(hasText("Birthday", substring = true) and hasClickAction()).assertDoesNotExist()
            val before = compose.onNode(save).fetchSemanticsNode().boundsInRoot
            refused.set(true)
            compose.onNode(save).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Save moved", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
            val error = compose.onNode(hasText(message)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val birthday = compose.onNode(hasText("Birthday", substring = true) and hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The message sits under Birthday", error.top >= birthday.bottom && error.top - birthday.bottom < 48f)
        }
    }

    // C24: species care leads with known values and folds the rest, says Your value, answers overrides with bar receipts; Unmatched reads plainly.
    @Test fun speciesCareLeadsWithKnownValuesAndAnswersYourValuesWithReceipts() {
        val overridden = AtomicBoolean(false)
        fun care() = JSONObject(careFixture.replace("pending", "done")).apply {
            getJSONObject("species").put("canonical_name", "Testoria nebulosa").put("common_name", "Synthetic mistleaf")
            if (overridden.get()) getJSONObject("profile").put("wateringIntervalDays", JSONObject("""{"value":9,"source":"override","fetchedAt":"2030-06-02T12:00:00Z"}"""))
        }.toString()
        val unmatched = """[{"gbif_key":910002,"canonical_name":"Testoria nebulosa","common_name":"Synthetic mistleaf","status":"no_match"}]"""
        withPlantServer({ path -> when (path) {
            "/api/species/unmatched" -> unmatched
            "/api/species/910002/care" -> care()
            "/api/species/910002/outdoor-months" -> outdoorFixture
            "/api/species/910002/care/overrides/wateringIntervalDays" -> { overridden.set(!overridden.get()); "{}" }
            else -> null
        } }) { repo, showing ->
            val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
            try {
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                    AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
                compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
                compose.onNodeWithText("More").performClick()
                compose.onNodeWithText("Unmatched species").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic mistleaf") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Testoria nebulosa").assertIsDisplayed()
                compose.onNodeWithText("No care data found yet").assertIsDisplayed()
                for (gone in listOf("Open Plantbook", "no match", "no_match")) compose.onAllNodes(hasText(gone, substring = true)).assertCountEquals(0)
                compose.onNodeWithText("Species care for Synthetic mistleaf").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("care:light").assertExists()
                compose.onNodeWithTag("care:temperatureRangeC").assertDoesNotExist()
                for (gone in listOf("Source:", "water.base@1", "unknown", "fetched")) compose.onAllNodes(hasText(gone, substring = true)).assertCountEquals(0)
                compose.onNodeWithText("No value yet (13)").performScrollTo().performClick()
                compose.onNodeWithText("Override temperature range in \u00b0C").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("Override watering interval in days").performScrollTo().performClick()
                compose.onNode(hasText("Days") and hasSetTextAction()).performTextReplacement("9")
                compose.onNodeWithText("Save override").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved watering interval for Synthetic mistleaf").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Your value") and hasAnyAncestor(hasTestTag("care:wateringIntervalDays"))).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasText("Override saved", substring = true)).assertCountEquals(0)
                compose.onAllNodes(hasText("fetched", substring = true)).assertCountEquals(0)
                compose.onNodeWithText("Remove override for watering interval in days").performScrollTo().performClick()
                compose.onNodeWithText("Remove").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Removed your watering interval for Synthetic mistleaf").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Your value").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Sources").performScrollTo().performClick()
                compose.onNodeWithText("Watering interval in days: rules \u00b7 water.base@1").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Light: open plantbook \u00b7 fetched 1 Jun 2030").performScrollTo().assertIsDisplayed()
            } finally { controller.close() }
        }
    }

    // C13: an empty Name shows the name the plant uses now as a placeholder, never as its text, so TalkBack hears it as guidance and typing starts empty.
    @Test fun anEmptyNameShowsTheNameThePlantUsesNow() {
        val unnamed = plant.copy(displayName = "Synthetic trialleaf", attributesJson = JSONObject(plantFixture).put("nickname", JSONObject.NULL).put("display_name", "Synthetic trialleaf").toString())
        compose.setContent { FytoriumTheme { PlantForm(unnamed, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {}) } }
        fun text(value: String) = SemanticsMatcher("text '$value'") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == value }
        val name = compose.onNode(hasText("Name") and hasSetTextAction())
        name.assert(text("")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Empty, shows Synthetic trialleaf"))
        compose.onNodeWithText("Leave empty to keep using the species name").assertIsDisplayed()
        name.performTextInput("Fern")
        name.assert(text("Fern")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // C14: the delete confirmation ignores case and outer spaces and says what to type.
    @Test fun deleteAcceptsTheNameInAnyCaseAndSaysWhatToType() {
        val sent = mutableListOf<String>()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { action, body -> sent += action + ":" + body.optString("confirm_name") }, {}, {}) } }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Delete plant").performClick()
        compose.assertDialogBody("Type Synthetic fern to confirm.")
        compose.onNode(hasText("Plant name") and hasSetTextAction()).performTextInput("  synthetic FERN ")
        compose.onNodeWithText("Delete").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("delete:Synthetic fern"), sent) }
    }

    // C15: a graveyard plant offers Restore where Care would be.
    @Test fun aGraveyardPlantOffersRestoreWhereCareWouldBe() {
        val commands = mutableListOf<String>()
        val buried = detail().copy(plant = plant.copy(status = "graveyard", attributesJson = JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()))
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), buried, {}, { action, _ -> commands += action }, {}, {}) } }
        reveal(hasText("Restore Synthetic fern") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("restore"), commands) }
    }

    @Test fun createASiteFromRestoreOpensTheFormAndReturnsAfterSaving() {
        val buried = JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()
        val site = """{"id":"site-one","name":"Synthetic site","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":1}"""
        withPlantServer({ path -> when (path) { "/api/plants/plant-one" -> buried; "/api/sites" -> "[$site]"; else -> null } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Restore plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Create a site").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create a site").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("New site") and isHeading() or hasText("New site")).assertIsDisplayed()
            compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Synthetic porch")
            compose.onNodeWithText("Save site").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Create a site").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Restore Synthetic fern", substring = true) and hasClickAction()).assertExists()
        }
    }

    // A graveyard plant and the full site the Sites screen reads, for Restore's Create a site.
    private fun restoreServed(path: String) = when (path) {
        "/api/plants/plant-one" -> JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()
        "/api/sites" -> """[{"id":"site-one","name":"Synthetic site","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":1}]"""
        else -> null
    }
    private fun openCreateASite() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Restore plant").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Create a site").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create a site").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    // C19: Create a site from Restore asks before system Back, the top bar's Back or Cancel drops a typed name, and Discard returns to Restore.
    @Test fun createASiteFromRestoreAsksBeforeDroppingATypedName() = withPlantServer(::restoreServed) { repo, showing ->
        compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
        val name = hasText("Name") and hasSetTextAction()
        val restore = hasText("Restore Synthetic fern to Synthetic site") and isEnabled()
        for (leave in listOf<() -> Unit>({ compose.waitForIdle(); androidx.test.espresso.Espresso.pressBack() }, { compose.onNodeWithContentDescription("Back").performClick() },
            { compose.onNodeWithText("Cancel").performScrollTo().performClick() })) {
            if (compose.onAllNodes(restore).fetchSemanticsNodes().isEmpty()) openCreateASite() else {
                compose.onNodeWithText("Create a site").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNode(name).performTextInput("Synthetic porch")
            leave()
            compose.assertDiscard("Your changes to this new site are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(name).assertTextContains("Synthetic porch")
            leave()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(restore).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).assertDoesNotExist()
        }
    }

    // C19: leaving Create a site through the navigation bar asks first, with the site form's question.
    @Test fun leavingCreateASiteThroughTheNavigationBarAsksFirst() = withPlantServer(::restoreServed) { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            openCreateASite()
            val name = hasText("Name") and hasSetTextAction()
            compose.onNode(name).performTextInput("Synthetic porch")
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.assertDiscard("Your changes to this new site are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(name).assertTextContains("Synthetic porch")
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("view-today")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(name).assertDoesNotExist()
        } finally { controller.close() }
    }

    // C19: tapping Plants again on an unchanged form keeps the navigation bar's question once something is typed, on Edit and on Create a site from Restore.
    @Test fun typingAfterTappingPlantsAgainInEditStillAsks() = typingAfterTappingPlantsAgain(createSite = false)
    @Test fun typingAfterTappingPlantsAgainInCreateASiteStillAsks() = typingAfterTappingPlantsAgain(createSite = true)
    private fun typingAfterTappingPlantsAgain(createSite: Boolean) = withPlantServer({ if (createSite) restoreServed(it) else null }) { repo, showing ->
        val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
        try {
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNodeWithTag("plant:plant-one").performClick()
            if (createSite) openCreateASite() else {
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Edit plant").performClick()
            }
            val name = hasText("Name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            // The page's own reads land first, so only the typing could make the screen draw again after the tap.
            compose.waitUntil(5_000) { Running.count.get() == 0 }
            compose.onNode(hasText("Plants") and hasClickAction()).performClick()
            compose.onNode(name).performTextInput("Synthetic porch")
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.assertDiscard(if (createSite) "Your changes to this new site are not saved yet." else "Your changes to Synthetic fern are not saved yet.")
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(name).assertTextContains("Synthetic porch", substring = true)
        } finally { controller.close() }
    }

    // C19: Back right after a keystroke, before the screen has drawn it, still asks, on Edit and on Create a site from Restore.
    @Test fun backRightAfterTypingInEditAsks() = backRightAfterTyping(createSite = false)
    @Test fun backRightAfterTypingInCreateASiteAsks() = backRightAfterTyping(createSite = true)
    private fun backRightAfterTyping(createSite: Boolean) = withPlantServer({ if (createSite) restoreServed(it) else null }) { repo, showing ->
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent { if (showing.value) FytoriumTheme { back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
        if (createSite) openCreateASite() else {
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Edit plant").performClick()
        }
        val name = hasText("Name") and hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // No wait in between, so Back arrives before the typed name is composed.
        compose.onNode(name).performTextInput("Synthetic porch")
        compose.runOnUiThread { checkNotNull(back).onBackPressed() }
        compose.assertDiscard(if (createSite) "Your changes to this new site are not saved yet." else "Your changes to Synthetic fern are not saved yet.")
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNode(name).assertTextContains("Synthetic porch", substring = true)
    }

    // C23: a site save from Restore that fails on its way, with the connection dropped or a 503, shows its message under Save, which stays where it was.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aFailedSiteSaveFromRestoreLeavesSaveInPlace() {
        val failure = java.util.concurrent.atomic.AtomicReference<MockResponse>()
        withPlantServer(::restoreServed,
            respond = { request -> failure.get().takeIf { request.method == "POST" && request.requestUrl!!.encodedPath == "/api/sites" } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
            openCreateASite()
            compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Synthetic porch")
            val save = hasText("Save site")
            val before = compose.onNode(save).fetchSemanticsNode().boundsInRoot
            for ((response, message) in listOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) to "Could not reach the server. Try again.",
                MockResponse().setResponseCode(503) to "Could not save the site. Try again.")) {
                failure.set(response)
                compose.onNode(save).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasText("Refresh sites") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Save moved after: $message", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
                assertTrue("The message sits under Save", compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot.top >= before.bottom)
                compose.onNodeWithText("Refresh sites").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Save moved after Refresh sites", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
            }
        }
    }

    // C23: Fytorium's refusal of a site's name or temperature shows under that field, in space kept for it, so Save site stays where it was, at 1.0x and 1.3x text.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aRefusedSiteFieldLeavesSaveSiteInPlace() = refusedSiteFieldLeavesSaveSiteInPlace(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aRefusedSiteFieldAtLargeTextLeavesSaveSiteInPlace() = refusedSiteFieldLeavesSaveSiteInPlace(1.3f)
    private fun refusedSiteFieldLeavesSaveSiteInPlace(fontScale: Float) {
        val refusal = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val posts = AtomicInteger()
        withPlantServer(::restoreServed, respond = { request -> refusal.get()?.takeIf { request.method == "POST" && request.requestUrl!!.encodedPath == "/api/sites" }
            ?.let { posts.incrementAndGet(); MockResponse().setResponseCode(422).setBody(it) } }) { repo, showing ->
            compose.setContent { if (showing.value) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
                val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } } }
            openCreateASite()
            val save = hasText("Save site")
            val before = compose.onNode(save).fetchSemanticsNode().boundsInRoot
            // The server's own messages: readSite in src/lib/server/sites.ts. Each field's message must end above the next control.
            val whole = "Enter a whole number of °C or leave blank."
            val exceeds = "Minimum must not exceed maximum."
            val refusals = listOf(Triple("name", "Enter a site name.", "Location: indoor"), Triple("warm_min_c", exceeds, "Warm maximum °C"), Triple("cold_min_c", exceeds, "Cold maximum °C"),
                Triple("warm_min_c", whole, "Warm maximum °C"), Triple("warm_max_c", whole, "Cold minimum °C"), Triple("cold_min_c", whole, "Cold maximum °C"), Triple("cold_max_c", whole, "Draft"))
            refusals.forEachIndexed { index, (field, message, next) ->
                refusal.set(JSONObject().put("errors", JSONObject().put(field, message)).toString())
                compose.onNode(save).performClick()
                compose.waitUntil(5_000) { posts.get() == index + 1 && compose.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Save site moved after the $field refusal '$message' at ${fontScale}x", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
                val shown = compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("The $field message ends above $next", shown.bottom <= compose.onNodeWithText(next).fetchSemanticsNode().boundsInRoot.top)
            }
        }
    }

    // C1b: a sites read that fails in Create a site from Restore shows only in the top bar's line, with Try again, which loads the sites; nothing is added to the form.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aFailedSitesReadInCreateASiteShowsInTheLineAndTryAgainLoadsIt() {
        val down = AtomicBoolean(false)
        withPlantServer(::restoreServed, status = { if (down.get() && it == "/api/sites") 503 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Restore plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Restore Synthetic fern to Synthetic site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            down.set(true)
            compose.onNodeWithText("Create a site").performClick()
            val line = hasText("Sites didn't load") and hasClickAction()
            // The plant reads that opening the page started finish first, so none answers after the server is back and reloads the sites before the tap.
            compose.waitUntil(5_000) { compose.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            val controls = listOf("Name", "Location: indoor", "Light level: part sun", "Humidity: normal", "Grow light", "Warm minimum °C", "Warm maximum °C",
                "Cold minimum °C", "Cold maximum °C", "Draft", "Save site", "Cancel")
            fun layout() = controls.associateWith { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            val failed = layout()
            compose.onNodeWithText("Save site").assertIsNotEnabled()
            val failure = "Could not reach the server. Try again."
            for (inserted in listOf(failure, "Refresh sites")) compose.onAllNodes(hasText(inserted)).assertCountEquals(0)
            down.set(false)
            compose.onNode(line).performClick()
            compose.onNode(hasText(failure) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(line).assertCountEquals(0)
            assertEquals("Loading the sites moved the form", failed, layout())
        }
    }

    // C21: a sites read Fytorium left unanswered in Create a site from Restore loads again without a tap once Fytorium answers a plant request, also the reload after a reconnect; Save enables and the typed name stays.
    @Test fun createASiteLoadsAnUnansweredSitesReadOnceFytoriumAnswersAgain() = createASiteLoadsAnUnansweredSitesRead(reconnect = false)
    @Test fun createASiteLoadsAnUnansweredSitesReadOnceTheConnectionReturns() = createASiteLoadsAnUnansweredSitesRead(reconnect = true)
    private fun createASiteLoadsAnUnansweredSitesRead(reconnect: Boolean) {
        val down = AtomicBoolean(false)
        val online = AtomicBoolean(true)
        withPlantServer(::restoreServed, connected = { online.get() }, status = { if (down.get() && it == "/api/sites") 503 else 200 }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { online.get() }) } }
            openCreateASite()
            val name = hasText("Name") and hasSetTextAction()
            compose.onNode(name).performTextInput("Synthetic porch")
            down.set(true)
            compose.onNodeWithText("Save site").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Refresh sites") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Refresh sites").performScrollTo().performClick()
            val line = hasText("Sites didn't load") and hasClickAction()
            compose.waitUntil(5_000) { compose.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
            down.set(false)
            if (reconnect) {
                online.set(false)
                repo.connectivityChanged(false)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Offline") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                online.set(true)
                repo.connectivityChanged(true)
            } else repo.refresh()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(line).assertCountEquals(0)
            compose.onNode(name).assertTextContains("Synthetic porch")
        }
    }

    // C1b: going offline and back while Create a site from Restore is open shows only in the top bar's line, so no field or button moves, at 1.0x and 1.3x text.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun goingOfflineAndBackInCreateASiteFromRestoreMovesNothing() {
        val online = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        withPlantServer(::restoreServed) { repo, showing ->
            compose.setContent { if (showing.value) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) { FytoriumTheme {
                val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { online.value }) } } }
            openCreateASite()
            val controls = listOf("Name", "Location: indoor", "Light level: part sun", "Humidity: normal", "Grow light", "Warm minimum °C", "Warm maximum °C",
                "Cold minimum °C", "Cold maximum °C", "Draft", "Save site", "Cancel")
            fun layout() = controls.associateWith { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            for (scale in listOf(1f, 1.3f)) {
                compose.runOnIdle { fontScale.value = scale }
                compose.waitForIdle()
                val before = layout()
                compose.runOnIdle { online.value = false }
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Offline at ${scale}x moved the form", before, layout())
                compose.onNodeWithText("Offline").performClick()
                compose.onNodeWithText("This phone can't reach Fytorium. Reconnect to make changes.").assertIsDisplayed()
                compose.onNodeWithText("Close").performClick()
                compose.runOnIdle { online.value = true }
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Reconnecting at ${scale}x moved the form", before, layout())
                compose.onNodeWithText("Offline").assertDoesNotExist()
            }
        }
    }

    // C23, C29: Fytorium's refusal of a pot type or soil type shows under the Repot button, which stays where it was, and no space waits for it under the choices, at 1.0x and 1.3x text.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aRefusedPotOrSoilTypeLeavesRepotInPlace() = refusedPotOrSoilTypeLeavesRepotInPlace(1f)
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun aRefusedPotOrSoilTypeAtLargeTextLeavesRepotInPlace() = refusedPotOrSoilTypeLeavesRepotInPlace(1.3f)
    private fun refusedPotOrSoilTypeLeavesRepotInPlace(fontScale: Float) {
        val refusal = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val path = "/api/plants/plant-one/repot"
        // The server's own choices from src/lib/plants.ts, so the form keeps space for the refusal Fytorium really sends.
        val choices = syntheticOptions().let { it.copy(choices = it.choices + mapOf("pot_type" to listOf("plastic", "terracotta", "ceramic", "glass", "metal", "wood", "fabric", "in_ground", "other"),
            "soil_type" to listOf("all_purpose", "cactus", "orchid", "aroid", "other"))) }
        withPlantServer({ if (it == "/api/choices") choicesJson(choices) else null },
            status = { if (refusal.get() != null && it == path) 422 else 200 }, posted = { refusal.get()?.takeIf { _ -> it == path } }) { repo, showing ->
            compose.setContent { if (showing.value) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { FytoriumTheme {
                val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNode(hasText("Repot") and hasAnyAncestor(isPopup())).performClick()
            val repot = hasText("Repot Synthetic fern")
            compose.waitUntil(5_000) { compose.onAllNodes(repot and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            val before = compose.onNode(repot).fetchSemanticsNode().boundsInRoot
            // A choice without a supporting line is shorter than the size field with its one-line hint.
            val size = compose.onNode(hasText("Pot size in cm") and hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height
            for (label in listOf("Pot type", "Soil type")) assertTrue("Space waits under $label at ${fontScale}x", compose.onNode(hasText(label)).fetchSemanticsNode().boundsInRoot.height < size)
            // The server's own messages: potErrors in src/lib/server/plants.ts with the choices in src/lib/plants.ts.
            for ((field, message) in listOf("pot_type" to "Choose one of: plastic, terracotta, ceramic, glass, metal, wood, fabric, in_ground, other.",
                "soil_type" to "Choose one of: all_purpose, cactus, orchid, aroid, other.")) {
                refusal.set(JSONObject().put("errors", JSONObject().put(field, message)).toString())
                compose.onNode(repot).performClick()
                val shown = (if (field == "pot_type") "Pot type: " else "Soil type: ") + message
                compose.waitUntil(5_000) { compose.onAllNodesWithText(shown, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(repot and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                assertEquals("Repot moved after the $field refusal", before, compose.onNode(repot).fetchSemanticsNode().boundsInRoot)
                val bounds = compose.onNodeWithText(shown, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("The $field message sits under Repot", bounds.top >= before.bottom)
            }
        }
    }

    // C23: at 1.3x text, Fytorium's messages for Name and Site show in space kept for them, so Save stays where it was.
    @Config(qualifiers = "w360dp-h2400dp-mdpi")
    @Test fun nameAndSiteRefusalsAtLargeTextLeaveSaveInPlace() {
        val unnamed = plant.copy(id = "plant-unnamed", displayName = "Synthetic trialleaf",
            attributesJson = JSONObject(plantFixture).put("id", "plant-unnamed").put("nickname", JSONObject.NULL).put("display_name", "Synthetic trialleaf").toString())
        val shown = mutableStateOf(plant)
        val errors = mutableStateOf(emptyMap<String, String>())
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) { FytoriumTheme {
            PlantForm(shown.value, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, errors = errors.value, onSave = {}) } } }
        // The server's own messages: readPlant and writeError in src/lib/server/plants.ts.
        val refusals = listOf("nickname" to "Enter text or leave blank.", "site_id" to "Choose a site in your household.", "site_id" to "Active plants must have a site.")
        for (form in listOf(plant, unnamed)) for ((field, message) in refusals) {
            compose.runOnIdle { shown.value = form; errors.value = emptyMap() }
            val before = compose.onNodeWithText("Save plant").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { errors.value = mapOf(field to message) }
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
            assertEquals("${form.displayName}: '$message' moved Save", before, compose.onNodeWithText("Save plant").fetchSemanticsNode().boundsInRoot)
        }
    }

    // C19: Discard from the navigation bar also drops the form's refused save, so a later Add plant shows nothing of it.
    @Test fun discardFromTheNavigationBarDropsTheFormsRefusedSave() {
        val refusing = AtomicBoolean(false)
        val refused = "Plant data does not meet required constraints."
        withPlantServer({ path -> if (path == "/api/species/search") speciesSearch else null },
            status = { path -> if (refusing.get() && path == "/api/plants/plant-one") 422 else 200 },
            posted = { path -> if (path == "/api/plants/plant-one") JSONObject().put("errors", JSONObject().put("_form", refused)).toString() else null }) { repo, showing ->
            val controller = CareController(RuntimeEnvironment.getApplication(), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }, repo, api)
            try {
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                    AppShell(true, care.updateRequired, careState = care, controller = controller, sitesApi = api, sitesConnected = { true }) } }
                compose.waitUntil(5_000) { synchronized(paths()) { "/api/meta" in paths() } && !repo.state.value.busy }
                compose.onNode(hasText("Plants") and hasClickAction()).performClick()
                compose.onNodeWithTag("plant:plant-one").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Edit plant").performClick()
                val name = hasText("Name") and hasSetTextAction()
                compose.waitUntil(5_000) { compose.onAllNodes(name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(name).performTextReplacement("Fern")
                refusing.set(true)
                compose.onNodeWithText("Save plant").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText(refused).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                refusing.set(false)
                compose.onNode(hasText("Today") and hasClickAction()).performClick()
                compose.onNodeWithText("Discard").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("view-today")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Add plant").performClick()
                val query = hasText("Species name") and hasSetTextAction()
                compose.waitUntil(5_000) { compose.onAllNodes(query and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(query).performTextInput("nova")
                compose.onNodeWithContentDescription("Search species").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(starResult).performScrollTo().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Save plant").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                compose.onAllNodes(hasText(refused, substring = true)).assertCountEquals(0)
            } finally { controller.close() }
        }
    }

    // C16: species changes read by common names, give a receipt and say so in history.
    @Test fun aSpeciesChangeReadsByCommonNamesAndGivesAReceipt() {
        val changed = AtomicBoolean(false)
        val before = JSONObject(plantFixture).put("common_name", "Synthetic lantern")
        val after = JSONObject(plantFixture).put("gbif_key", 910019).put("scientific_name", "Testoria nova Inventor").put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        val search = """{"search_id":"search-one","candidates":[{"gbif_key":910019,"scientific_name":"Testoria nova Inventor","canonical_name":"Testoria nova","rank":"SPECIES","match_type":"EXACT","confidence":99,"requires_confirmation":false,"matched_name":null,"matched_language":null,"common_name":"Synthetic star"}]}"""
        val change = """[{"id":"change-one","previous_gbif_key":910001,"previous_name":"Testoria lucida Inventor","previous_display_name":"Synthetic lantern","next_gbif_key":910019,"next_name":"Testoria nova Inventor","next_display_name":"Synthetic star","changed_at":"2030-06-02T09:00:00.000Z","changed_on":"2030-06-02","by_name":"Synthetic member"}]"""
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one" -> (if (changed.get()) after else before).toString()
            "/api/species/search" -> search
            "/api/plants/plant-one/species" -> { changed.set(true); after.toString() }
            "/api/plants/plant-one/species-changes" -> if (changed.get()) change else "[]"
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Change species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic fern is currently Synthetic lantern (Testoria lucida).", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Species name") and hasSetTextAction()).performTextInput("nova")
            compose.onNodeWithContentDescription("Search species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(starResult).performScrollTo().performClick()
            compose.onNodeWithText("Chosen species: Synthetic star (Testoria nova)").assertIsDisplayed()
            compose.onAllNodes(hasText("Inventor", substring = true)).assertCountEquals(0)
            compose.onNodeWithText("Change Synthetic fern to Synthetic star").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Changed Synthetic fern to Synthetic star").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("History") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Species changed from Synthetic lantern to Synthetic star \u00b7 Synthetic member")).assertIsDisplayed()
        }
    }

    // C31: after a species change, Species names shows the new species' names, not the chosen species without names.
    @Test fun speciesNamesAfterASpeciesChangeShowTheNewSpeciesNames() {
        val changed = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val after = JSONObject(plantFixture).put("gbif_key", 910019).put("scientific_name", "Testoria nova Inventor").put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        val nova = speciesNamesFixture.replace("910001", "910019").replace("Testoria lucida", "Testoria nova").replace("Synthetic lantern", "Synthetic star")
        try {
            withPlantServer({ path -> when (path) {
                "/api/plants/plant-one" -> if (changed.get()) after.toString() else null
                "/api/species/search" -> speciesSearch
                "/api/plants/plant-one/species" -> { changed.set(true); after.toString() }
                "/api/species/910001" -> speciesNamesFixture
                "/api/species/910019" -> { release.await(10, java.util.concurrent.TimeUnit.SECONDS); nova }
                else -> null
            } }) { repo, showing ->
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
                val settings = hasContentDescription("Plant settings") and isEnabled()
                fun shows(name: String) = compose.onAllNodesWithText("Plants of this species without a name of their own show \u201c$name\u201d.").fetchSemanticsNodes().isNotEmpty()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                reveal(hasText("Details")).performClick()
                reveal(hasText("Species names")).performClick()
                compose.waitUntil(5_000) { shows("Synthetic lantern") }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(settings).performClick()
                compose.onNodeWithText("Change species").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Species name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Species name") and hasSetTextAction()).performTextInput("nova")
                compose.onNodeWithContentDescription("Search species").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(starResult).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(starResult).performScrollTo().performClick()
                compose.onNodeWithText("Change Synthetic fern to Synthetic star").performClick()
                val receipt = "Changed Synthetic fern to Synthetic star"
                compose.waitUntil(5_000) { compose.onAllNodesWithText(receipt).fetchSemanticsNodes().isNotEmpty() }
                // The receipt covers the bottom of the page, where the link scrolls into view.
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText(receipt))).performSemanticsAction(SemanticsActions.Dismiss)
                compose.waitUntil(5_000) { compose.onAllNodesWithText(receipt).fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                reveal(hasText("Species names") and hasClickAction()).performClick()
                compose.waitUntil(5_000) { synchronized(paths()) { "/api/species/910019" in paths() } }
                assertFalse("the old species' names show", shows("Synthetic lantern"))
                compose.onNodeWithText("No source has a common name for it yet.").assertDoesNotExist()
                compose.onNodeWithText("Loading names\u2026").assertIsDisplayed()
                release.countDown()
                compose.waitUntil(5_000) { shows("Synthetic star") }
                compose.onNodeWithText("No source has a common name for it yet.").assertDoesNotExist()
            }
        } finally { release.countDown() }
    }

    // C31: Species names reads the species on every open, so names changed elsewhere show when the page opens again.
    @Test fun speciesNamesReadTheSpeciesEachTimeTheyOpen() {
        val changed = AtomicBoolean(false)
        withPlantServer({ path -> if (path != "/api/species/910001") null
            else if (changed.get()) speciesNamesFixture.replace("Synthetic lantern", "Synthetic beacon") else speciesNamesFixture }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            fun shows(name: String) = compose.onAllNodesWithText("Plants of this species without a name of their own show \u201c$name\u201d.").fetchSemanticsNodes().isNotEmpty()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { shows("Synthetic lantern") }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            changed.set(true)
            reveal(hasText("Species names") and hasClickAction()).performClick()
            compose.waitUntil(5_000) { shows("Synthetic beacon") }
        }
    }

    // C33: Species names opens at the top on every open, for the same plant again and for another plant's species, however far the last one was scrolled.
    @Test fun speciesNamesOpenAtTheTopForEachPlantAndSpecies() {
        fun names(key: Int, common: String) = JSONObject(speciesNamesFixture).put("gbif_key", key).put("common_name", common).put("names", JSONArray().apply {
            put(JSONObject().put("id", "name-house").put("name", common).put("language", "en").put("source", "owner"))
            repeat(24) { put(JSONObject().put("id", "name-$it").put("name", "$common $it").put("language", "en").put("source", "owner")) }
        }).toString()
        val ivy = JSONObject(plantFixture).put("id", "plant-two").put("display_name", "Synthetic ivy").put("nickname", "Synthetic ivy").put("gbif_key", 910002).toString()
        withPlantServer({ path -> when (path) {
            "/api/plants" -> "[$plantFixture,$ivy]"
            "/api/plants/plant-two" -> ivy
            "/api/plants/plant-two/tasks" -> scheduleFixture
            "/api/plants/plant-two/care-settings" -> """{"customIntervals":{}}"""
            "/api/species/910001" -> names(910001, "Synthetic lantern")
            "/api/species/910002" -> names(910002, "Synthetic beacon")
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
            val loaded = hasContentDescription("Plant settings") and isEnabled()
            val link = hasText("Species names") and hasClickAction()
            fun openNames(common: String) {
                compose.waitUntil(5_000) { compose.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                if (compose.onAllNodes(link).fetchSemanticsNodes().isEmpty()) reveal(hasText("Details")).performClick()
                reveal(link).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Loading names\u2026").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(common, substring = true).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            }
            fun scrollDown(common: String) {
                reveal(hasText("Remove $common 23")).assertIsDisplayed()
                assertTrue("the names page scrolled", compose.onAllNodes(isHeading() and hasText(common)).fetchSemanticsNodes().isEmpty())
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("plant:plant-two")).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasTestTag("plant:plant-one")).performClick()
            openNames("Synthetic lantern")
            compose.onNode(isHeading() and hasText("Synthetic lantern")).assertIsDisplayed()
            scrollDown("Synthetic lantern")
            compose.onNodeWithContentDescription("Back").performClick()
            openNames("Synthetic lantern")
            compose.onNode(isHeading() and hasText("Synthetic lantern")).assertIsDisplayed()
            scrollDown("Synthetic lantern")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(loaded).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("plant:plant-two")).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasTestTag("plant:plant-two")).performClick()
            openNames("Synthetic beacon")
            compose.onNode(isHeading() and hasText("Synthetic beacon")).assertIsDisplayed()
            compose.onNodeWithText("Plants of this species without a name of their own show \u201cSynthetic beacon\u201d.").assertIsDisplayed()
        }
    }

    @Test fun anOpenNamesPageStartsAtTheTopWhenTheSpeciesChangesElsewhere() = namesScrollDuringASpeciesChangeElsewhere(pinned = false)
    @Test fun addingAPinnedNameStartsTheNewSpeciesAtTheTop() = namesScrollDuringASpeciesChangeElsewhere(pinned = true)

    private fun namesScrollDuringASpeciesChangeElsewhere(pinned: Boolean) {
        val changed = AtomicBoolean(false)
        val after = JSONObject(plantFixture).put("gbif_key", 910019).put("scientific_name", "Testoria nova Inventor")
            .put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        fun names(key: Int, common: String) = JSONObject(speciesNamesFixture).put("gbif_key", key).put("common_name", common).put("names", JSONArray().apply {
            repeat(24) { put(JSONObject().put("id", "source-$it").put("name", "$common source $it").put("language", "en").put("source", if (key == 910001) "wikidata" else "owner")) }
        }).toString()
        val app = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one" -> if (changed.get()) after.toString() else null
            "/api/plants" -> if (changed.get()) "[$after]" else null
            "/api/species/910001" -> names(910001, "Synthetic lantern")
            "/api/species/910019" -> names(910019, "Synthetic star")
            "/api/species/910001/names" -> """{"id":"name-added","name":"Synthetic glow","language":"en","source":"owner"}"""
            else -> null
        } }) { repo, showing ->
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides app) {
                if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            val list = compose.onNode(hasScrollToIndexAction())
            fun scrollPosition() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(isHeading() and hasText("Synthetic lantern")).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            if (pinned) compose.onNode(hasText("Common name") and hasSetTextAction()).performTextInput("Synthetic glow")
            reveal(hasText("Other names from sources (24)")).performClick()
            reveal(hasText("Synthetic lantern source 20 · Wikidata")).assertIsDisplayed()
            val scrolled = scrollPosition()
            assertTrue("the old species is scrolled", scrolled > 2f)
            val before = synchronized(paths()) { paths().count { it == "/api/plants/plant-one" } }
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            changed.set(true)
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/plants/plant-one" } > before } &&
                repo.state.value.detail?.plant?.attributesJson?.let { JSONObject(it).optLong("gbif_key") } == 910019L && Running.count.get() == 0 }
            if (pinned) {
                compose.onNodeWithText("This plant is now Synthetic star. You're adding a name for Synthetic lantern.").assertIsDisplayed()
                assertEquals("a pinned draft keeps its scroll position", scrolled, scrollPosition())
                val add = hasText("Add name to Synthetic lantern") and hasClickAction() and isEnabled()
                reveal(add).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Added the name “Synthetic glow” to Synthetic lantern").fetchSemanticsNodes().isNotEmpty() }
            }
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/species/910019" in paths() } && Running.count.get() == 0 &&
                compose.onAllNodesWithText("Loading names…").fetchSemanticsNodes().isEmpty() }
            compose.onNode(isHeading() and hasText("Synthetic star")).assertIsDisplayed()
            assertEquals("the new species starts at index zero with no offset", 0f, scrollPosition())
        }
    }

    // C19: a names read that lands while a household name is typed keeps the name, Back still asks, and only a successful add clears it.
    @Test fun speciesNamesKeepATypedNameWhenTheirReadLands() {
        val holding = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            withPlantServer({ path -> when (path) {
                "/api/species/910001" -> if (!holding.get()) speciesNamesFixture
                    else { release.await(10, java.util.concurrent.TimeUnit.SECONDS); speciesNamesFixture.replace("Synthetic lantern", "Synthetic beacon") }
                "/api/species/910001/names" -> """{"id":"name-added","name":"Synthetic star","language":"en","source":"owner"}"""
                else -> null
            } }) { repo, showing ->
                compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
                val settings = hasContentDescription("Plant settings") and isEnabled()
                val field = hasText("Common name") and hasSetTextAction()
                fun shows(name: String) = compose.onAllNodesWithText("Plants of this species without a name of their own show \u201c$name\u201d.").fetchSemanticsNodes().isNotEmpty()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                reveal(hasText("Details")).performClick()
                reveal(hasText("Species names")).performClick()
                compose.waitUntil(5_000) { shows("Synthetic lantern") }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
                holding.set(true)
                reveal(hasText("Species names") and hasClickAction()).performClick()
                compose.waitUntil(5_000) { synchronized(paths()) { paths().count { it == "/api/species/910001" } >= 2 } && compose.onAllNodes(field and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(shows("Synthetic lantern"))
                compose.onNode(field).performTextInput("Synthetic star")
                release.countDown()
                compose.waitUntil(5_000) { shows("Synthetic beacon") }
                compose.onNode(field).assertTextContains("Synthetic star")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithText("Add name").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Added the name \u201cSynthetic star\u201d").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(field and hasText("Synthetic star")).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Discard changes?").assertDoesNotExist()
            }
        } finally { release.countDown() }
    }

    // C19: a species change made elsewhere keeps a typed household name on the species it was typed for, and says why, until it is added or cleared.
    @Test fun aSpeciesChangeElsewhereKeepsATypedNameUntilItIsAdded() = typedNameDuringASpeciesChangeElsewhere(add = true)
    @Test fun aSpeciesChangeElsewhereKeepsATypedNameUntilItIsCleared() = typedNameDuringASpeciesChangeElsewhere(add = false)
    // C19, C1b: when a refusal takes the line, the add control and receipt still name the species and the explanation keeps the note.
    @Test fun aSpeciesChangeElsewhereNamesTheAddTargetWhenItsNamesReadIsRefused() = typedNameDuringASpeciesChangeElsewhere(add = true, refuse = true)

    private fun typedNameDuringASpeciesChangeElsewhere(add: Boolean, refuse: Boolean = false) {
        val changed = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val after = JSONObject(plantFixture).put("gbif_key", 910019).put("scientific_name", "Testoria nova Inventor").put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        val nova = speciesNamesFixture.replace("910001", "910019").replace("Testoria lucida", "Testoria nova").replace("Synthetic lantern", "Synthetic star")
        val app = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        fun plantReads() = synchronized(paths()) { paths().count { it == "/api/plants/plant-one" } }
        try {
            withPlantServer({ path -> when (path) {
                "/api/plants/plant-one" -> if (!changed.get()) null else { release.await(10, java.util.concurrent.TimeUnit.SECONDS); after.toString() }
                "/api/plants" -> if (changed.get()) "[$after]" else null
                "/api/species/910001" -> if (refuse && changed.get()) """{"message":"Synthetic names refusal"}""" else speciesNamesFixture
                "/api/species/910019" -> nova
                "/api/species/910001/names" -> """{"id":"name-added","name":"Synthetic glow","language":"en","source":"owner"}"""
                else -> null
            } }, status = { path -> if (refuse && changed.get() && path == "/api/species/910001") 403 else 200 }) { repo, showing ->
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides app) {
                    if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } } }
                val settings = hasContentDescription("Plant settings") and isEnabled()
                val field = hasText("Common name") and hasSetTextAction()
                val note = "This plant is now Synthetic star. You're adding a name for Synthetic lantern."
                val line = if (refuse) refused else note
                val addName = "Add name to Synthetic lantern"
                fun shows(name: String) = compose.onAllNodesWithText("Plants of this species without a name of their own show \u201c$name\u201d.").fetchSemanticsNodes().isNotEmpty()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                reveal(hasText("Details")).performClick()
                reveal(hasText("Species names")).performClick()
                compose.waitUntil(5_000) { shows("Synthetic lantern") && compose.onAllNodes(field and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(field).performTextInput("Synthetic glow")
                val place = compose.onNode(field).getBoundsInRoot()
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
                changed.set(true)
                val before = plantReads()
                compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                compose.waitUntil(5_000) { plantReads() > before }
                release.countDown()
                compose.waitUntil(5_000) { repo.state.value.detail?.plant?.attributesJson?.let { JSONObject(it).optLong("gbif_key") } == 910019L && Running.count.get() == 0 }
                compose.waitUntil(5_000) { shows("Synthetic star") || compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(field).assertTextContains("Synthetic glow")
                assertEquals(place, compose.onNode(field).getBoundsInRoot())
                assertTrue(shows("Synthetic lantern"))
                compose.onNodeWithText(line).assertIsDisplayed()
                compose.onNode(hasText(addName) and hasClickAction() and isEnabled()).assertIsDisplayed()
                if (refuse) compose.explains(refused, "Synthetic names refusal", note, "Add or clear the name to see the names for Synthetic star.")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
                compose.onNodeWithText("Keep editing").performClick()
                if (add) compose.onNodeWithText(addName).performClick() else compose.onNode(field).performTextClearance()
                if (add) compose.waitUntil(5_000) { compose.onAllNodesWithText("Added the name \u201cSynthetic glow\u201d to Synthetic lantern").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { shows("Synthetic star") }
                assertEquals(add, synchronized(paths()) { "/api/species/910001/names" in paths() })
                compose.onNodeWithText(note).assertDoesNotExist()
                compose.onNodeWithText(addName).assertDoesNotExist()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Discard changes?").assertDoesNotExist()
            }
        } finally { release.countDown() }
    }

    // C32: a species name that already ends with a full stop ends its sentence without a second one.
    @Test fun aSpeciesNameEndingInAFullStopEndsItsSentenceOnce() {
        val abbreviated = JSONObject(plantFixture).put("scientific_name", "Testoria lucida Inv.").apply { remove("canonical_name") }
        val species = """{"gbif_key":910001,"scientific_name":"Testoria lucida Inv.","rank":"SPECIES","name_fetch_needed":false,"name_fetch_failed":false,"common_name":null,"names":[]}"""
        withPlantServer({ path -> when (path) { "/api/plants/plant-one" -> abbreviated.toString(); "/api/species/910001" -> species; else -> null } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            val settings = hasContentDescription("Plant settings") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(settings).performClick()
            compose.onNodeWithText("Change species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic fern is currently Testoria lucida Inv. Care history", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No source has a common name for it yet.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Plants of this species without a name of their own show its scientific name, Testoria lucida Inv.").assertExists()
            compose.onAllNodes(hasText("..", substring = true)).assertCountEquals(0)
        }
    }

    // C17: species names lead with the name in use, fold source names, confirm removals and give receipts.
    @Test fun speciesNamesLeadWithTheNameInUseAndConfirmRemovals() {
        val added = AtomicBoolean(false)
        val removed = AtomicBoolean(false)
        fun species() = JSONObject("""{"gbif_key":910001,"scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida","rank":"SPECIES",
            "name_fetch_needed":false,"name_fetch_failed":false,"common_name":"Synthetic lantern"}""").put("names", JSONArray().apply {
            if (!removed.get()) put(JSONObject("""{"id":"name-house","name":"Synthetic lantern","language":"en","source":"owner","renamed_plant_count":2,"renamed_to":"Testoria lucida"}"""))
            if (added.get()) put(JSONObject("""{"id":"name-added","name":"Synthetic star","language":"en","source":"owner"}"""))
            repeat(24) { put(JSONObject().put("id", "name-$it").put("name", "Synthetic source name $it").put("language", "en").put("source", "wikidata")) }
        }).toString()
        withPlantServer({ path -> when (path) {
            "/api/species/910001" -> species()
            "/api/species/910001/names" -> { added.set(true); """{"id":"name-added","name":"Synthetic star","language":"en","source":"owner"}""" }
            "/api/species/910001/names/name-house" -> { removed.set(true); "{}" }
            "/api/species/910001/names/refresh" -> "{}"
            else -> null
        } }) { repo, showing ->
            compose.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Your household added this name.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Plants of this species without a name of their own show \u201cSynthetic lantern\u201d.").assertIsDisplayed()
            compose.onNodeWithText("Synthetic source name 1 \u00b7 Wikidata").assertDoesNotExist()
            compose.onNode(hasText("Common name") and hasSetTextAction()).performTextInput("Synthetic star")
            val namesList = compose.onNode(hasScrollToIndexAction())
            fun firstIndex() = namesList.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value().toInt()
            namesList.performScrollToIndex(2)
            val beforeAdd = firstIndex()
            assertTrue("adding from a scrolled list", beforeAdd > 0)
            compose.onNodeWithText("Add name").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Added the name \u201cSynthetic star\u201d").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            assertEquals("adding a name keeps the first visible index", beforeAdd, firstIndex())
            reveal(hasText("Other names from sources (24)")).performClick()
            val refresh = reveal(hasText("Refresh names")).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("Refresh names").performClick()
            compose.waitUntil(5_000) { synchronized(paths()) { "/api/species/910001" in paths().dropWhile { it != "/api/species/910001/names/refresh" } } && Running.count.get() == 0 }
            compose.waitForIdle()
            assertEquals("the list kept its place", refresh, compose.onNodeWithText("Refresh names").fetchSemanticsNode().boundsInRoot)
            namesList.performScrollToIndex(3)
            compose.onNodeWithText("Remove Synthetic lantern").assertIsDisplayed()
            val beforeRemove = firstIndex()
            assertTrue("removing from a scrolled list", beforeRemove > 0)
            compose.onNodeWithText("Remove Synthetic lantern").performClick()
            compose.onNodeWithText("2 plants show this name now and will show \u201cTestoria lucida\u201d instead.").assertIsDisplayed()
            compose.runOnIdle { assertFalse(removed.get()) }
            compose.onNodeWithText("Remove name").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Removed the name \u201cSynthetic lantern\u201d").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            assertEquals("removing a name keeps the first visible index", beforeRemove, firstIndex())
            compose.runOnIdle { assertTrue(removed.get()) }
        }
    }

    // D3: the count comes from the server for each name, so a name in use in another language reports the plants that show it there.
    @Test fun removingANameInAnotherLanguageCountsThePlantsThatShowIt() {
        val species = parseSpecies("""{"gbif_key":910001,"scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida","rank":"SPECIES",
            "name_fetch_needed":false,"name_fetch_failed":false,"common_name":"Synthetic lantern","names":[
            {"id":"name-en","name":"Synthetic lantern","language":"en","source":"owner","renamed_plant_count":2},
            {"id":"name-nb","name":"Syntetisk lykt","language":"nb","source":"owner","renamed_plant_count":2,"renamed_to":"Syntetisk lampe"},
            {"id":"name-spare","name":"Syntetisk lampe","language":"nb","source":"owner","renamed_plant_count":0}]}""")
        compose.setContent { FytoriumTheme { SpeciesNames(species, syntheticOptions(), true, { _, _, _ -> }) } }
        reveal(hasText("Remove Syntetisk lykt")).performClick()
        compose.onNodeWithText("2 plants show this name now and will show \u201cSyntetisk lampe\u201d instead.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        reveal(hasText("Remove Syntetisk lampe")).performClick()
        compose.onNodeWithText("No plant shows this name now, so removing it renames none.").assertIsDisplayed()
    }

    // D4: a held Restore whose plant was restored elsewhere keeps its place, looks off, says why on tap and sends nothing.
    @Test fun aHeldRestoreForAPlantRestoredElsewhereSaysSoAndSendsNothing() {
        val commands = mutableListOf<String>()
        val explained = mutableListOf<String>()
        val buried = detail().copy(plant = plant.copy(status = "graveyard", attributesJson = JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()))
        val shown = mutableStateOf(buried)
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), shown.value, {}, { action, _ -> commands += action }, {}, {}, onBlocked = { explained += it }) } }
        val restore = hasText("Restore Synthetic fern") and hasClickAction()
        val place = reveal(restore).assertIsEnabled().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { shown.value = detail() }
        reveal(restore).assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ChangedElsewhere)).performClick()
        assertEquals("Restore kept its place", place, compose.onNode(restore).fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(listOf(ChangedElsewhere), explained); assertEquals(emptyList<String>(), commands) }
    }

    // The server the current test runs against, for screens that call it directly and for waits on what it was asked.
    private lateinit var api: ApiClient
    private var recorded: MutableList<String> = mutableListOf()
    private fun paths() = recorded
    private fun choicesJson(options: PlantOptions) = JSONObject().put("plant", JSONObject().put("choices", JSONObject(options.choices)).put("labels", JSONObject(options.labels))
        .put("numbers", JSONArray(options.numbers)).put("booleans", JSONArray(options.booleans)).put("health", JSONArray(options.health)))
        .put("nameLanguages", JSONObject(options.languages)).toString()
    @Test fun editDraftSurvivesANewRepositoryWhosePlantLoadsAfterOptions() {
        val delaying = AtomicBoolean(false)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val freshDb = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        try {
            withPlantServer(respond = { request ->
                if (delaying.get() && request.requestUrl!!.encodedPath == "/api/plants/plant-one") {
                    started.countDown()
                    check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                }
                null
            }) { repo, showing ->
                var current = repo
                val restoration = StateRestorationTester(compose)
                restoration.setContent { if (showing.value) FytoriumTheme { val care by current.state.collectAsState()
                    PlantsScreen(care, current, initialPlant = plant.id) } }
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                compose.onNodeWithContentDescription("Edit plant").performClick()
                val name = hasText("Name") and hasSetTextAction()
                compose.waitUntil(5_000) { compose.onAllNodes(name).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                compose.onNode(name).performTextReplacement("Synthetic glow")
                val cache = CareCache(freshDb)
                cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
                val fresh = Repository(api, cache, { true })
                fresh.restore()
                assertNull(fresh.state.value.detail)
                current = fresh
                delaying.set(true)
                restoration.emulateSavedInstanceStateRestore()
                compose.waitUntil(5_000) { started.count == 0L }
                release.countDown()
                compose.waitUntil(5_000) { fresh.state.value.detail != null && Running.count.get() == 0 }
                compose.onNode(name).assertTextContains("Synthetic glow")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
            }
        } finally { release.countDown(); freshDb.close() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun anUnconfirmedBirthdaySurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true,
            onSave = {}, today = LocalDate.of(2030, 6, 2)) } }
        compose.onNodeWithText("More details").performClick()
        compose.onNode(hasText("Birthday:", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        val date = hasSetTextAction() and hasAnyAncestor(isDialog())
        compose.onNode(date).performTextReplacement("05202030")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Set birthday").assertIsDisplayed().performClick()
        compose.onNodeWithText("Birthday: 20 May 2030").assertIsDisplayed()
    }

    @Test fun aSpeciesCareDraftSavesToItsOriginalSpeciesAfterResumeAndRecreation() {
        val changed = AtomicBoolean(false)
        val after = JSONObject(plantFixture).put("gbif_key", 910019).put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        val posts = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val app = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one" -> after.toString().takeIf { changed.get() }
            "/api/species/910001/care", "/api/species/910019/care" -> JSONObject(careFixture.replace("pending", "done")).apply {
                put("species", JSONObject().put("gbif_key", if (path.contains("910019")) 910019 else 910001)
                    .put("scientific_name", "Testoria lucida Inventor").put("canonical_name", "Testoria lucida")
                    .put("common_name", if (path.contains("910019")) "Synthetic star" else "Synthetic trialleaf"))
            }.toString()
            "/api/species/910001/outdoor-months", "/api/species/910019/outdoor-months" -> outdoorFixture
            else -> null
        } }, respond = { request -> if (request.method == "PUT") {
            posts += request.requestUrl!!.encodedPath to request.body.readUtf8()
            MockResponse().setBody("{}")
        } else null }) { repo, showing ->
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides app) {
                if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                    PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Care defaults for this species")).performClick()
            val open = hasText("Override watering interval in days") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(open).performClick()
            val days = hasText("Days") and hasSetTextAction()
            compose.onNode(days).performTextReplacement("9")
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            changed.set(true)
            compose.runOnIdle { app.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            compose.waitUntil(5_000) { repo.state.value.detail?.plant?.attributesJson == after.toString() && Running.count.get() == 0 }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodes(days and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(days).assertTextContains("9")
            compose.onNodeWithText("This plant is now Synthetic star. You're editing care for Synthetic trialleaf.").assertExists()
            compose.onNodeWithText("Save override").performClick()
            compose.waitUntil(5_000) { synchronized(posts) { posts.isNotEmpty() } && Running.count.get() == 0 }
            assertEquals(listOf("/api/species/910001/care/overrides/wateringIntervalDays"), synchronized(posts) { posts.map { it.first } })
            assertEquals(9, JSONObject(posts.single().second).getInt("value"))
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic star").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun anOlderHistoryDayKeepsItsPlaceAfterRecreationAndRepot() {
        val changed = AtomicBoolean(false)
        val history = (1..8).map { n -> JSONObject(eventFixture).put("id", "older-$n").put("occurred_on", "2030-05-${30 - n}")
            .put("occurred_at", "2030-05-${30 - n}T09:00:00Z") }
        val repot = JSONObject(eventFixture).put("id", "new-repot").put("task_type", "repot")
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one/history" -> JSONArray((if (changed.get()) listOf(repot) else emptyList()) + history).toString()
            "/api/plants/plant-one/repot" -> { changed.set(true); JSONObject().put("event", repot).toString() }
            else -> null
        } }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { repo.state.value.detail?.history?.size == 8 && Running.count.get() == 0 }
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(9)
            val day = hasText("Tue 28 May") and isHeading()
            val place = compose.onNode(day).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNode(hasText("Repot") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Repot Synthetic fern").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Repot Synthetic fern") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNodeWithText("Repot Synthetic fern").performClick()
            compose.waitUntil(5_000) { repo.state.value.detail?.history?.size == 9 && compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            assertEquals(place, compose.onNode(day).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun aMissingSavedItemFallsBackToItsPreviousIndex() {
        val history = (1..12).map { event("older-$it") }
        var shown = detail(history)
        var scroll: PlantPageScroll? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme {
            val place = androidx.compose.runtime.saveable.rememberSaveable(saver = PlantPageScroll.Saver) { PlantPageScroll() }
            SideEffect { scroll = place }
            PlantDetailScreen(owner.copy(today = today), shown, {}, { _, _ -> }, {}, {}, scroll = place)
        } }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(8)
        compose.runOnIdle { assertEquals("history:older-1", scroll!!.list.layoutInfo.visibleItemsInfo.first().key) }
        shown = detail(listOf(event("replacement")) + history.drop(1))
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(8, scroll!!.list.firstVisibleItemIndex)
            assertEquals("history:replacement", scroll!!.list.layoutInfo.visibleItemsInfo.first().key)
        }
    }

    @Test fun aScheduleLinkQuestionSurvivesRecreation() = withPlantServer { repo, showing ->
        val restoration = StateRestorationTester(compose)
        restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("This plant's schedule").performClick()
        val field = hasText("Water interval in days") and hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        reveal(field).performTextReplacement("5")
        reveal(hasText("Care defaults for this species") and hasClickAction()).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Open care defaults for this species?").assertIsDisplayed()
    }

    @Test fun aGraveyardConfirmationSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Move to graveyard").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Move to the graveyard?").assertIsDisplayed()
        compose.assertDialogBody("Synthetic fern leaves Today and Upcoming. Its history and photos stay, and you can restore it later.")
    }

    @Test fun aSpeciesNameDraftRemainsVisibleWhenRecreatedOffline() {
        val online = AtomicBoolean(true)
        withPlantServer({ if (it == "/api/species/910001") speciesNamesFixture else null }, connected = { online.get() }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            val name = hasText("Common name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(name).performTextInput("Synthetic glow")
            online.set(false)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNode(hasText("Common name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertTextContains("Synthetic glow")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
        }
    }

    @Test fun aNameRemovalConfirmationSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SpeciesNames(parseSpecies(speciesNamesFixture), syntheticOptions(), true, { _, _, _ -> }) } }
        compose.onNodeWithText("Remove Synthetic lantern").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Remove name").assertIsDisplayed()
    }

    @Test fun anOpenChoiceSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { Choice("Synthetic choice", "one", mapOf("one" to "One", "two" to "Two")) {} } }
        compose.onNodeWithText("Synthetic choice").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("Two") and hasAnyAncestor(isPopup())).assertIsDisplayed()
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun scheduleLinkScrollsInsideInLandscapeWithLargeText() = withPlantServer { repo, showing ->
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); if (showing.value) FytoriumTheme {
            val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("This plant's schedule").performClick()
        val field = hasText("Water interval in days") and hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        reveal(field).performTextReplacement("5")
        reveal(hasText("Care defaults for this species") and hasClickAction()).performClick()
        compose.onNodeWithText("Your changes to Synthetic fern's schedule are not saved yet. They stay here when you come back.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open care defaults").assertIsDisplayed()
        compose.checkDialogTextScale("Open care defaults", 1.3f)
        compose.onNodeWithText("Keep editing").assertIsDisplayed().performClick()
        reveal(field).assertTextContains("5")
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun confirmationsScrollInsideInLandscapeWithLargeText() {
        val screen = mutableStateOf("graveyard")
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme {
            key(screen.value) { when (screen.value) {
                "graveyard" -> PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {})
                "names" -> SpeciesNames(parseSpecies(speciesNamesFixture), syntheticOptions(), true, { _, _, _ -> })
                "discard" -> DiscardDialog("Discard changes?", "Your changes to Synthetic fern are not saved yet.", {}, {})
                else -> BarTitle("Offline", "Synthetic connection explanation ".repeat(12)) { Text("Synthetic fern") }
            } }
        } }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Move to graveyard").performClick()
        compose.checkDialogTextScale("Move to graveyard", 1.3f)
        compose.onNodeWithText("Synthetic fern leaves Today and Upcoming. Its history and photos stay, and you can restore it later.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Move to graveyard").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { screen.value = "names" }
        reveal(hasText("Remove Synthetic lantern")).performClick()
        compose.onNodeWithText("Remove this name?").assertIsDisplayed()
        compose.onNodeWithText("Remove \u201cSynthetic lantern\u201d from this species?").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Synthetic lantern") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("No plant shows this name now, so removing it renames none.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove name").assertIsDisplayed()
        compose.checkDialogTextScale("Remove name", 1.3f)
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { screen.value = "discard" }
        compose.onNodeWithText("Your changes to Synthetic fern are not saved yet.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Discard").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").assertIsDisplayed()
        compose.checkDialogTextScale("Keep editing", 1.3f)
        compose.runOnIdle { screen.value = "status" }
        compose.onNodeWithText("Offline").performClick()
        compose.onNode(hasText("Synthetic connection explanation", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.checkDialogTextScale("Close", 1.3f)
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun birthdayPickerIsReachableInLandscapeWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme {
            PlantForm(plant.copy(attributesJson = JSONObject(plant.attributesJson).put("birthday", "2030-01-02").toString()), syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true,
                onSave = {}, today = LocalDate.of(2030, 1, 15))
        } }
        compose.onNodeWithText("More details").performScrollTo().performClick()
        compose.onNodeWithText("Birthday: 2 Jan 2030").performScrollTo().performClick()
        compose.onNodeWithText("Tuesday, January 1, 2030").performScrollTo().performClick()
        compose.onNodeWithText("Tuesday, January 1, 2030").assertIsSelected()
        compose.checkDialogTextScale("Set birthday", 1.3f)
        compose.onNodeWithContentDescription("Switch to text input mode").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Date, MM/DD/YYYY").performScrollTo().assertIsDisplayed()
        compose.checkDialogTextScale("Set birthday", 1.3f)
        compose.onNodeWithText("Set birthday").assertIsDisplayed().performClick()
        compose.onNodeWithText("Birthday: 1 Jan 2030").assertExists()
    }

    @Test fun deleteConfirmationKeepsMarginsOn360dpPortrait() = portraitDelete(360)

    @Config(qualifiers = "w411dp-h800dp-mdpi")
    @Test fun deleteConfirmationKeepsMarginsOn411dpPortrait() = portraitDelete(411)

    @Test fun deleteConfirmationOpensFullyOn360dpPortraitWithLargeText() = portraitDelete(360, 1.3f)

    @Config(qualifiers = "w411dp-h800dp-mdpi")
    @Test fun deleteConfirmationOpensFullyOn411dpPortraitWithLargeText() = portraitDelete(411, 1.3f)

    private val ordinaryDeleteName = "Synthetic fern ".repeat(3) + "Synthetic frond"
    private val wideDeleteName = "W".repeat(60)
    private val longDeleteName = "Synthetic fern ".repeat(13) + "frond"

    private fun portraitDelete(windowWidth: Int, fontScale: Float = 1f) {
        RuntimeEnvironment.setFontScale(fontScale)
        val names = listOf(plant.displayName, ordinaryDeleteName, wideDeleteName, longDeleteName)
        assertEquals(listOf(14, 60, 60, 200), names.map { it.length })
        var name by mutableStateOf(names.first())
        compose.setContent { FytoriumTheme {
            assertEquals(windowWidth, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)
            PlantDetailScreen(owner.copy(today = today), detail().copy(plant = plant.copy(displayName = name)), {}, { _, _ -> }, {}, {})
        } }
        for (candidate in names) {
            compose.runOnIdle { name = candidate }
            openPlantDelete()
            compose.checkDialogTextScale("Permanently delete this plant?", fontScale)
            val field = assertOpeningDelete(candidate, fullInstruction = candidate == plant.displayName || candidate == ordinaryDeleteName)
            val surface = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
            val before = surface.fetchSemanticsNode().boundsInRoot
            val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val left = (windowWidth - dialog.width) / 2 + before.left
            val right = windowWidth - (windowWidth - dialog.width) / 2 - before.right
            assertTrue("Left margin must be at least 24 dp, got $left in $windowWidth dp", left >= 24f)
            assertTrue("Right margin must be at least 24 dp, got $right in $windowWidth dp", right >= 24f)
            field.performTextInput(candidate)
            field.assertTextContains(candidate)
            val after = surface.fetchSemanticsNode().boundsInRoot
            assertEquals("Typing keeps the dialog surface width", before.width, after.width, 0f)
            if (fontScale == 1f) assertEquals("Typing keeps the dialog surface bounds at normal text size", before, after)
            compose.onNodeWithText("Delete").assertIsEnabled()
            if (candidate == plant.displayName && fontScale == 1f) compose.captureDialog("src/test/screenshots/native-plants-delete-portrait-$windowWidth.png")
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun deleteConfirmationKeepsTypedNameVisibleInLandscape() = landscapeDelete(1f)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun deleteConfirmationKeepsLargeTypedNameVisibleInLandscape() = landscapeDelete(1.3f)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun longNameDeleteStaysUsableInLandscapeWithLargeText() = longNameDialog("delete")

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun longNameGraveyardStaysUsableInLandscapeWithLargeText() = longNameDialog("graveyard")

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun longNameDiscardStaysUsableInLandscapeWithLargeText() = longNameDialog("discard")

    private fun longNameDialog(kind: String) {
        val name = longDeleteName
        assertEquals(200, name.length)
        assertEquals(name.trim(), name)
        val title = when (kind) {
            "delete" -> "Permanently delete this plant?"
            "graveyard" -> "Move to the graveyard?"
            else -> "Discard changes?"
        }
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme {
            if (kind == "discard") DiscardDialog("Discard changes?", "Your changes to $name are not saved yet.", {}, {})
            else PlantDetailScreen(owner.copy(today = today), detail().copy(plant = plant.copy(displayName = name)), {}, { _, _ -> }, {}, {})
        } }
        if (kind != "discard") {
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText(if (kind == "delete") "Delete plant" else "Move to graveyard").performClick()
        }
        val heading = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertTrue("Title line count must be at most two, got ${heading.lineCount}", heading.lineCount <= 2)
        assertTrue("The short title fits without an ellipsis", !heading.isLineEllipsized(heading.lineCount - 1))
        compose.onNode(hasText(name) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertDoesNotExist()
        val guidance = when (kind) {
            "delete" -> "This permanently deletes $name, all care history and photos. It cannot be undone."
            "graveyard" -> "$name leaves Today and Upcoming. Its history and photos stay, and you can restore it later."
            else -> "Your changes to $name are not saved yet."
        }
        compose.onNodeWithText(guidance).performScrollTo().assertIsDisplayed()
        if (kind == "delete") {
            compose.onNodeWithText("Type $name to confirm.").assertExists()
            val field = compose.onNode(hasText("Plant name") and hasSetTextAction())
            field.performScrollTo().performTextInput(name)
            field.assertTextContains(name).assertIsDisplayed()
            assertTrue("The confirmation field keeps its full height", field.fetchSemanticsNode().boundsInRoot.height >= 56f)
            val label = compose.onNode(hasText("Plant name") and !hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().textLayout()
            assertEquals("The floating label stays on one line", 1, label.lineCount)
            val typed = field.fetchSemanticsNode().textLayout()
            assertEquals(name, typed.layoutInput.text.text)
            assertTrue("Typed text fits the field vertically", typed.size.height <= field.fetchSemanticsNode().boundsInRoot.height)
        } else {
            compose.onNode(hasText(name, substring = true) and hasAnyAncestor(hasScrollAction())).performScrollTo().assertExists()
        }
        val confirm = when (kind) { "delete" -> "Delete"; "graveyard" -> "Move to graveyard"; else -> "Discard" }
        val cancel = when (kind) { "delete" -> "Cancel"; "graveyard" -> "Cancel"; else -> "Keep editing" }
        compose.onNodeWithText(confirm).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(cancel).assertIsDisplayed()
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val buttons = listOf(confirm, cancel).map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        buttons.forEach { assertTrue("Buttons fit inside the dialog", it.top >= dialog.top && it.bottom <= dialog.bottom) }
        compose.captureDialog("src/test/screenshots/native-plants-$kind-long-name-landscape-large.png")
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun sixtyCharacterDeleteOpensFullyInLandscape() = landscapeDelete(1f, ordinaryDeleteName)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun sixtyCharacterDeleteOpensFullyWithLargeText() = landscapeDelete(1.3f, ordinaryDeleteName)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun wideNameDeleteOpensWithFullField() = landscapeDelete(1f, wideDeleteName)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun wideNameDeleteOpensWithFullFieldWithLargeText() = landscapeDelete(1.3f, wideDeleteName)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun overflowingDeleteShowsOnlyEdgesWithMoreContent() = overflowingDelete(1.3f)

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun overflowingDeleteShowsEdgesWithNormalText() = overflowingDelete(1f)

    private fun overflowingDelete(fontScale: Float) {
        val name = longDeleteName
        assertEquals(200, name.length)
        assertEquals(name.trim(), name)
        openLandscapeDelete(name, fontScale)
        val field = assertOpeningDelete(name, fullInstruction = false, mustOverflow = true)
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(deleteWarning(name)).performScrollTo()
        compose.onNodeWithTag("dialog-more-below").assertDoesNotExist()
        compose.onNodeWithTag("dialog-more-above").assertIsDisplayed()
        assertDeleteButtonsShareRow()
        field.performScrollTo().performTextInput(name)
        field.assertTextContains(name)
        assertEquals("Typing keeps the dialog width", dialog.width, compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.width, 0f)
    }

    private fun openPlantDelete() {
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Delete plant").performClick()
    }

    private fun openLandscapeDelete(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { CheckLandscape(fontScale); FytoriumTheme {
            PlantDetailScreen(owner.copy(today = today), detail().copy(plant = plant.copy(displayName = name)), {}, { _, _ -> }, {}, {})
        } }
        openPlantDelete()
        compose.checkDialogTextScale("Permanently delete this plant?", fontScale)
    }

    private fun deleteWarning(name: String) = "This permanently deletes $name, all care history and photos. It cannot be undone."

    private fun assertOpeningDelete(name: String, fullInstruction: Boolean, mustOverflow: Boolean = false): SemanticsNodeInteraction {
        val field = compose.onNode(hasText("Plant name") and hasSetTextAction()).assertIsDisplayed()
        val viewport = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot
        fun assertFullyVisible(what: String, node: SemanticsNode) {
            val start = node.positionInRoot
            val size = node.size
            assertTrue("The whole $what is visible on open for '$name': $start, $size, viewport $viewport",
                start.x >= viewport.left && start.x + size.width <= viewport.right && start.y >= viewport.top && start.y + size.height <= viewport.bottom)
        }
        val openingField = field.fetchSemanticsNode()
        assertFullyVisible("field", openingField)
        val label = compose.onNode(hasText("Plant name") and !hasSetTextAction(), useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertFullyVisible("field label", label)
        val instructionText = "Type $name to confirm."
        val instruction = compose.onNodeWithText(instructionText).assertIsDisplayed().fetchSemanticsNode()
        val warning = compose.onNodeWithText(deleteWarning(name)).fetchSemanticsNode()
        assertTrue("The field comes before the instruction", openingField.positionInRoot.y + openingField.size.height <= instruction.positionInRoot.y)
        val instructionLayout = instruction.textLayout()
        val instructionBottom = instruction.positionInRoot.y + instructionLayout.size.height
        assertTrue("The instruction comes before the warning", instructionBottom <= warning.positionInRoot.y)
        if (fullInstruction) {
            assertFullyVisible("instruction", instruction)
            assertTrue("The full instruction text is visible on open", instructionBottom <= viewport.bottom)
            val lastLine = instructionLayout.lineCount - 1
            assertFalse("The instruction has no ellipsis", instructionLayout.isLineEllipsized(lastLine))
            assertEquals("The last line ends at the end of the instruction", instructionText.length, instructionLayout.getLineEnd(lastLine))
        }
        if (mustOverflow) compose.onNodeWithTag("dialog-more-below").assertIsDisplayed()
        compose.onNodeWithTag("dialog-more-above").assertDoesNotExist()
        assertDeleteButtonsShareRow()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        return field
    }

    private fun assertDeleteButtonsShareRow() {
        val buttons = listOf("Cancel", "Delete").map { compose.onNode(hasText(it) and hasAnyAncestor(isDialog())).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertEquals("Buttons share one row", buttons[0].center.y, buttons[1].center.y, 1f)
    }

    private fun landscapeDelete(fontScale: Float, name: String = "Synthetic fern") {
        assertEquals(name.trim(), name)
        assertTrue("The fixture has exactly 14 or 60 characters", name.length in listOf(14, 60))
        val wide = name == wideDeleteName
        openLandscapeDelete(name, fontScale)
        val field = assertOpeningDelete(name, fullInstruction = !wide, mustOverflow = wide)
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val surface = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        assertEquals("Landscape keeps the full 560 dp surface width", 560f, surface.fetchSemanticsNode().boundsInRoot.width, 0f)
        if (wide && fontScale == 1.3f) compose.captureDialog("src/test/screenshots/native-plants-delete-wide-name-landscape-large.png")
        field.performTextInput(name)
        field.assertTextContains(name)
        assertEquals("Typing keeps the dialog width", dialog.width, compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.width, 0f)
        assertEquals("The dialog uses the requested text scale", fontScale, field.fetchSemanticsNode().textLayout().layoutInput.density.fontScale, 0.01f)
        assertTrue("The confirmation field must not be compressed", field.fetchSemanticsNode().boundsInRoot.height >= 56f)
        field.performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed().assertIsEnabled()
        if (!wide) compose.captureDialog("src/test/screenshots/native-plants-delete-landscape-${if (fontScale == 1f) "normal" else "large"}${if (name.length == 60) "-60" else ""}.png")
    }

    @Test fun deleteConfirmationIsEmptyAfterCancel() = closedDeleteConfirmationReopensEmpty("cancel")
    @Test fun deleteConfirmationIsEmptyAfterBack() = closedDeleteConfirmationReopensEmpty("back")
    @Test fun deleteConfirmationIsEmptyAfterOutsideTap() = closedDeleteConfirmationReopensEmpty("outside")

    private fun closedDeleteConfirmationReopensEmpty(close: String) {
        val sent = mutableListOf<String>()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { command, _ -> sent += command }, {}, {}) } }
        fun open() {
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Delete plant").performClick()
        }
        open()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.assertDialogBody("Type Synthetic fern to confirm.")
        compose.onNodeWithText("Plant name").performTextInput("Synthetic fern")
        compose.onNodeWithText("Delete").assertIsEnabled()
        when (close) {
            "cancel" -> compose.onNodeWithText("Cancel").performClick()
            "back" -> {
                val dialog = checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
                compose.runOnIdle { checkNotNull(dialog.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher.onBackPressed() }
            }
            "outside" -> compose.runOnIdle {
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(0, 0, action, -100f, -100f, 0)
                    try { dialog.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            else -> error("Unknown close action")
        }
        compose.onNodeWithText("Permanently delete this plant?").assertDoesNotExist()
        open()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.assertDialogBody("Type Synthetic fern to confirm.")
        compose.onNodeWithText("Plant name").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.runOnIdle { assertTrue("closing never sends a delete", sent.isEmpty()) }
    }

    @Test fun deleteConfirmationDraftSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(today = today), detail(), {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Delete plant").performClick()
        compose.assertDialogBody("Type Synthetic fern to confirm.")
        compose.onNodeWithText("Plant name").performTextInput("Synthetic fern")
        compose.onNodeWithText("Delete").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Permanently delete this plant?").assertIsDisplayed()
        compose.assertDialogBody("Type Synthetic fern to confirm.")
        compose.onNodeWithText("Plant name").assertTextContains("Synthetic fern")
        compose.onNodeWithText("Delete").assertIsEnabled()
    }

    @Test fun editPlantDraftSurvivesRecreationAndBackStillAsks() = plantDraftSurvivesRecreation("Edit plant", "Name", "Synthetic glow")
    @Test fun repotDraftSurvivesRecreationAndBackStillAsks() = plantDraftSurvivesRecreation("Repot", "Pot size in cm", "25")
    @Test fun scheduleDraftSurvivesRecreationAndBackStillAsks() = plantDraftSurvivesRecreation("This plant's schedule", "Water interval in days", "5")
    @Test fun changeSpeciesQuerySurvivesRecreationAndBackStillAsks() = plantDraftSurvivesRecreation("Change species", "Species name", "Synthetic star")
    @Test fun addPlantQuerySurvivesRecreationAndBackStillAsks() = plantDraftSurvivesRecreation("Add plant", "Species name", "Synthetic star")

    @Test fun editPlantDraftRemainsVisibleWhenRecreatedOffline() = offlinePlantDraft("Edit plant", "Name", "Synthetic glow")
    @Test fun aScheduleDraftSurvivesItsReloadAfterRecreation() {
        val json = mutableStateOf("""{"customIntervals":{"water":7}}""")
        val restoration = StateRestorationTester(compose)
        var changed: () -> Boolean = { false }
        restoration.setContent { FytoriumTheme { PlantCareSettings(json.value, true, {}, onDirty = { changed = it }) } }
        val water = hasText("Water interval in days") and hasSetTextAction()
        compose.onNode(water).performTextReplacement("5")
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { json.value = """{"customIntervals":{"water":9}}""" }
        compose.onNode(water).assertTextContains("5")
        compose.runOnIdle { assertTrue(changed()) }
    }

    @Test fun scheduleDraftRemainsVisibleWhenRecreatedOffline() = offlinePlantDraft("This plant's schedule", "Water interval in days", "5")
    @Test fun repotDraftRemainsVisibleWhenRecreatedOffline() = offlinePlantDraft("Repot", "Pot size in cm", "25")
    @Test fun addPlantQueryRemainsVisibleWhenRecreatedOffline() = offlinePlantDraft("Add plant", "Species name", "Synthetic glow")

    private fun offlinePlantDraft(page: String, label: String, value: String) = plantDraftSurvivesRecreation(page, label, value, offline = true)

    private fun plantDraftSurvivesRecreation(page: String, label: String, value: String, offline: Boolean = false) {
        val online = AtomicBoolean(true)
        withPlantServer(connected = { online.get() }) { repo, showing ->
            var reloading = false
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(if (reloading) care.copy(detail = null) else care, repo, initialPlant = plant.id.takeUnless { page == "Add plant" }, initialAdd = page == "Add plant") } }
            if (page != "Add plant") {
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                if (page == "Edit plant") compose.onNodeWithContentDescription("Edit plant").performClick() else {
                    compose.onNodeWithContentDescription("Plant settings").performClick()
                    compose.onNode(hasText(page) and hasAnyAncestor(isPopup())).performClick()
                }
            }
            val field = hasText(label) and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(field and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(field).performTextReplacement(value)
            if (offline) { online.set(false); reloading = true }
            restoration.emulateSavedInstanceStateRestore()
            val restoredField = hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
            compose.waitUntil(5_000) { compose.onAllNodes(restoredField).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(restoredField).assertTextContains(value)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard(if (page == "Add plant") "Your changes to this new plant are not saved yet." else "Your changes to Synthetic fern are not saved yet.")
            if (offline) {
                restoration.emulateSavedInstanceStateRestore()
                compose.onNodeWithText("Keep editing").assertIsDisplayed()
            }
        }
    }

    @Test fun addPlantFormDraftSurvivesRecreationAndBackStillAsks() = chosenSpeciesSurvivesRecreation(add = true)
    @Test fun changeSpeciesChoiceSurvivesRecreationAndBackStillAsks() = chosenSpeciesSurvivesRecreation(add = false)
    @Test fun changeSpeciesConfirmationSurvivesRecreationAndBackStillAsks() = chosenSpeciesSurvivesRecreation(add = false, confirm = false)

    @Test fun addPlantFieldsRemainVisibleWhenRecreatedOffline() = chosenSpeciesSurvivesRecreation(add = true, offline = true)

    private fun chosenSpeciesSurvivesRecreation(add: Boolean, confirm: Boolean = true, offline: Boolean = false) {
        val recreated = AtomicBoolean(false)
        val online = AtomicBoolean(true)
        withPlantServer({ path ->
            JSONObject(identified).apply { getJSONArray("candidates").getJSONObject(0).put("common_name", "Synthetic cloud plant") }.toString().takeIf { path == "/api/species/search" }
        }, connected = { online.get() }, status = { path -> if (recreated.get() && path == "/api/species/search") 503 else 200 }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(care, repo, initialPlant = plant.id.takeUnless { add }, initialAdd = add) } }
            if (!add) {
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNode(hasText("Change species") and hasAnyAncestor(isPopup())).performClick()
            }
            val query = hasText("Species name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(query and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(query).performTextInput("Synthetic cloud plant")
            compose.onNodeWithContentDescription("Search species").performClick()
            val choose = hasContentDescription("Synthetic cloud plant, Testoria nebulosa. Choose this species.") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(choose).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(choose).performClick()
            if (confirm) compose.onNodeWithText("Yes, this one").performClick()
            if (add) {
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Synthetic glow")
            }
            recreated.set(true)
            if (offline) online.set(false)
            restoration.emulateSavedInstanceStateRestore()
            val kept = when { add -> hasText("Name") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText); confirm -> hasText("Chosen species: Synthetic cloud plant (Testoria nebulosa)"); else -> hasText("Is this your plant?") }
            compose.waitUntil(5_000) { compose.onAllNodes(kept).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            if (add) compose.onNode(kept).assertTextContains("Synthetic glow") else reveal(kept).assertIsDisplayed()
            if (!confirm) {
                compose.onNode(hasText("Back") and hasAnyAncestor(isDialog())).performClick()
                compose.onNode(query).assertTextContains("Synthetic cloud plant")
                reveal(choose).assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard(if (add) "Your changes to this new plant are not saved yet." else "Your changes to Synthetic fern are not saved yet.")
        }
    }

    @Test fun aRestoreChoiceRemainsVisibleWhenRecreatedOffline() {
        val online = AtomicBoolean(true)
        withPlantServer({ path -> when (path) {
            "/api/sites" -> """[{"id":"site-one","name":"Synthetic site"},{"id":"site-porch","name":"Synthetic porch"}]"""
            else -> restoreServed(path)
        } }, connected = { online.get() }) { repo, showing ->
            var reloading = false
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
                PlantsScreen(if (reloading) care.copy(detail = null) else care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Restore plant").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Restore Synthetic fern to Synthetic site").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(hasText("Site") and hasText("Synthetic site")).performClick()
            compose.onNode(hasText("Synthetic porch") and hasAnyAncestor(isPopup())).performClick()
            online.set(false)
            reloading = true
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Restore Synthetic fern to Synthetic porch") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Restore Synthetic fern to Synthetic porch").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
        }
    }

    @Test fun restoreSiteChoiceSurvivesRecreationAndBackStillAsks() = withPlantServer({ path -> when (path) {
        "/api/sites" -> """[{"id":"site-one","name":"Synthetic site"},{"id":"site-porch","name":"Synthetic porch"}]"""
        else -> restoreServed(path)
    } }) { repo, showing ->
        val restoration = StateRestorationTester(compose)
        restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Restore plant").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Restore Synthetic fern to Synthetic site").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Site") and hasText("Synthetic site")).performClick()
        compose.onNode(hasText("Synthetic porch") and hasAnyAncestor(isPopup())).performClick()
        restoration.emulateSavedInstanceStateRestore()
        val kept = hasText("Restore Synthetic fern to Synthetic porch") and isEnabled()
        compose.waitUntil(5_000) { compose.onAllNodes(kept).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        compose.onNode(kept).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
    }

    @Test fun createSiteDraftFromRestoreSurvivesRecreationAndBackStillAsks() = withPlantServer(::restoreServed) { repo, showing ->
        val restoration = StateRestorationTester(compose)
        restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, photosApi = api, photosConnected = { true }) } }
        openCreateASite()
        val name = hasText("Name") and hasSetTextAction()
        compose.onNode(name).performTextInput("Synthetic porch")
        compose.onNodeWithText("Grow light").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        reveal(name).assertTextContains("Synthetic porch")
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard("Your changes to this new site are not saved yet.")
    }

    @Test fun speciesNamesDraftAndPinnedSpeciesSurviveRecreationAndBackStillAsks() {
        val changed = AtomicBoolean(false)
        val after = JSONObject(plantFixture).put("gbif_key", 910019).put("canonical_name", "Testoria nova").put("common_name", "Synthetic star")
        withPlantServer({ path -> when (path) {
            "/api/plants/plant-one" -> after.toString().takeIf { changed.get() }
            "/api/species/910001" -> speciesNamesFixture
            "/api/species/910019" -> speciesNamesFixture.replace("910001", "910019").replace("Synthetic lantern", "Synthetic star")
            else -> null
        } }) { repo, showing ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState(); PlantsScreen(care, repo, initialPlant = plant.id) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            reveal(hasText("Details")).performClick()
            reveal(hasText("Species names")).performClick()
            val name = hasText("Common name") and hasSetTextAction()
            compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(name).performTextInput("Synthetic glow")
            changed.set(true)
            repo.openPlant(plant.id, full = true)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Add name to Synthetic lantern").fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodes(name and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
            compose.onNode(name).assertTextContains("Synthetic glow")
            compose.onNodeWithText("Add name to Synthetic lantern").assertIsDisplayed()
            compose.onNodeWithText("This plant is now Synthetic star. You're adding a name for Synthetic lantern.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic fern are not saved yet.")
        }
    }

    @Test fun speciesCareOverrideDraftSurvivesRecreationAndBackStillAsks() = overrideDraftSurvivesRecreation("watering interval in days", "Days", "9")
    @Test fun speciesCareRangeDraftSurvivesRecreationAndBackStillAsks() = overrideDraftSurvivesRecreation("temperature range in °C", "Minimum", "12")
    @Test fun speciesCareMonthChoiceSurvivesRecreationAndBackStillAsks() = overrideDraftSurvivesRecreation("pruning months", "January", null)

    private fun overrideDraftSurvivesRecreation(label: String, field: String, typed: String?) = withPlantServer({ path -> when (path) {
        "/api/species/910001/care" -> JSONObject(careFixture.replace("pending", "done")).apply {
            getJSONObject("profile").put("temperatureRangeC", JSONObject().put("value", JSONObject().put("min", 5).put("max", 30)).put("source", "rules"))
                .put("pruningMonths", JSONObject().put("value", JSONArray(listOf(6))).put("source", "rules"))
        }.toString()
        "/api/species/910001/outdoor-months" -> outdoorFixture
        else -> null
    } }) { repo, showing ->
        val restoration = StateRestorationTester(compose)
        restoration.setContent { if (showing.value) FytoriumTheme { val care by repo.state.collectAsState()
            PlantsScreen(care, repo, initialPlant = plant.id, speciesApi = api, speciesConnected = { true }) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Plant settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        reveal(hasText("Details")).performClick()
        reveal(hasText("Care defaults for this species")).performClick()
        val open = hasText("Override $label") and isEnabled()
        compose.waitUntil(5_000) { compose.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        reveal(open).performClick()
        val input = if (typed == null) hasContentDescription(field) else hasText(field) and hasSetTextAction()
        if (typed == null) compose.onNode(input).performClick() else compose.onNode(input).performTextReplacement(typed)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodes(input).fetchSemanticsNodes().isNotEmpty() && Running.count.get() == 0 }
        if (typed == null) compose.onNode(input).assertIsSelected() else compose.onNode(input).assertTextContains(typed)
        val dialog = checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        compose.runOnIdle { checkNotNull(dialog.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("Keep editing") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    private fun withPlantServer(served: (String) -> String? = { null }, lists: String = taskListFixture("today"),
        paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()), connected: () -> Boolean = { true },
        status: (String) -> Int = { 200 }, views: (String) -> String? = { null }, posted: (String) -> String? = { null },
        respond: (RecordedRequest) -> MockResponse? = { null }, block: (Repository, MutableState<Boolean>) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            val choices = choicesJson(syntheticOptions())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = request.requestUrl!!.encodedPath.also { paths += it }.let { path -> respond(request) ?: MockResponse().setResponseCode(status(path)).setBody((if (path == "/api/tasks") views(request.requestUrl!!.queryParameter("view") ?: "today") else null)
                    ?: posted(path).takeIf { request.method != "GET" } ?: served(path) ?: when (path) {
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
            api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            recorded = paths
            val repo = Repository(api, cache, connected)
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
