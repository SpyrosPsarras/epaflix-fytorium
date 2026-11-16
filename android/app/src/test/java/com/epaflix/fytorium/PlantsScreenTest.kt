package com.epaflix.fytorium

import com.epaflix.fytorium.plants.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class PlantsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val plant = parsePlant(JSONObject(plantFixture))
    private val owner = CareState(member = Member("member-one", Role.owner), plants = listOf(plant))
    private fun scroll(text: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithText(text)
        if (runCatching { node.assertIsDisplayed() }.isFailure) {
            if (runCatching { node.performScrollTo() }.isFailure)
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
        }
        return compose.onNodeWithText(text)
    }
    @Test fun feedPlantLinkOpensNativeDetailDespiteUnrelatedCareFailure() {
        val care = owner.copy(busy = true, offline = true, serverUnavailable = true,
            detail = PlantDetail(plant, emptyList(), emptyMap()))
        compose.setContent { FytoriumTheme { PlantsScreen(care, null, initialPlant = plant.id) } }
        compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Search plants").assertDoesNotExist()
    }
    private fun menu(task: String) = compose.onNodeWithContentDescription("More actions for $task on Synthetic fern")
    private fun openMenu(task: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription("More actions for $task on Synthetic fern"))
        menu(task).performClick()
    }
    private fun skipEnabled(task: String = "Water"): Boolean {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription("More actions for $task on Synthetic fern"))
        menu(task).performClick()
        val enabled = compose.onNodeWithText("Skip").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        return enabled
    }
    @Test fun ownerTappedRepotOffersSkipRepotAndProvenance() = tappedTask(Role.owner)
    @Test fun sitterTappedRepotOffersSkipWithoutOwnerSections() = tappedTask(Role.sitter)

    private fun tappedTask(role: Role) {
        val list = parseTaskList(taskListFixture("today", basis = """[{"input":"repotIntervalDays","value":365,"source":"rule","adjustment":"Synthetic repot interval","ruleId":"repot.synthetic@1"}]"""))
        val task = list.sections.single().tasks.single().copy(taskType = "repot")
        val state = mutableStateOf(owner.copy(member = Member("member-one", role), today = list))
        val opened = mutableStateOf(task)
        val actions = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val dark = mutableStateOf(false)
        compose.enableAccessibilityChecks()
        // Opening another occurrence from Today or Upcoming opens the plant page anew.
        compose.setContent { FytoriumTheme(dark.value) { Surface(Modifier.fillMaxSize()) { key(opened.value.occurrence) {
            PlantDetailScreen(state.value, PlantDetail(plant, emptyList(), emptyMap()), {},
                { action, _ -> commands.add(action) }, {}, {}, todayTask = opened.value, onTask = { _, action -> actions.add(action) })
        } } } }
        val row = hasAnyAncestor(hasTestTag("task:${task.plantId}:${task.key}"))
        compose.onNode(row and hasText("Repot")).assertIsDisplayed()
        menu("Repot").performClick()
        compose.onNodeWithText("Skip").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("skip"), actions)
        scroll("Details").performClick()
        compose.onNode(hasAnyAncestor(hasTestTag("basis:repot")) and hasText("Based on")).performScrollTo().performClick()
        compose.onNode(hasAnyAncestor(hasTestTag("basis:repot")) and hasText("Source: rule · repot.synthetic@1")).performScrollTo().assertIsDisplayed()
        if (role == Role.sitter) {
            openMenu("Repot")
            compose.onNodeWithText("Repot with new pot and soil").assertDoesNotExist()
            androidx.test.espresso.Espresso.pressBack()
            for (label in listOf("Photos", "About this plant", "History")) compose.onNode(hasText(label) and isHeading()).assertDoesNotExist()
            compose.onNodeWithText("Handle all tasks").assertDoesNotExist()
            compose.onNodeWithContentDescription("Edit plant").assertDoesNotExist()
            compose.onNodeWithContentDescription("Plant settings").assertDoesNotExist()
        } else {
            openMenu("Repot")
            compose.onNodeWithText("Repot with new pot and soil").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf("repot-form"), commands)
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("task:${task.plantId}:${task.key}"))
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/today-plant-task-${role.name}-${if (night) "dark" else "light"}.png")
        }
        // The occurrence a skip postponed, opened from Upcoming, offers Done and no Undo; the Undo is on the skip's own row.
        val next = task.copy(dueOn = "2030-06-09", lateDays = 0, postponed = "skipped")
        compose.runOnIdle { state.value = state.value.copy(today = list.copy(sections = emptyList()),
            upcoming = list.copy(view = "upcoming", sections = listOf(TaskSection("2030-06-09", "2030-06-09", listOf(next)))))
            opened.value = next }
        compose.onNode(row and hasText("Skipped until Sun 9 Jun")).performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Undo", substring = true)).assertDoesNotExist()
        compose.onNode(hasContentDescription("by 2 days", substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark Repot done for Synthetic fern, Synthetic site").performScrollTo().performClick()
        assertEquals(listOf("skip", "complete"), actions)
        if (role != Role.sitter) { openMenu("Repot"); compose.onNodeWithText("Repot with new pot and soil").performClick() }
        if (role != Role.sitter) assertEquals(listOf("repot-form", "repot-form"), commands)
    }
    @Test fun sitterPlantWithoutTappedTaskKeepsTodayAndUpcomingHint() {
        // A sitter's plant page lists the plant's care rows from every entry route; with none shared, it says so.
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(member = Member("member-one", Role.sitter)),
            PlantDetail(plant, emptyList(), emptyMap()), {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText("No care tasks yet.").assertIsDisplayed()
        compose.onNodeWithText("Care tasks are on Today and Upcoming.").assertDoesNotExist()
    }
    @Test fun ownerOfflineTaskShowsOnlyOneUnavailableNotice() = offlineTaskNotice(Role.owner)
    @Test fun sitterOfflineTaskShowsOnlyOneUnavailableNotice() = offlineTaskNotice(Role.sitter)

    private fun offlineTaskNotice(role: Role) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"${role.name}"}}""")
            val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, cache, { false })
            repository.restore()
            val list = parseTaskList(taskListFixture("today"))
            val task = list.sections.single().tasks.single()
            compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(member = Member("member-one", role), today = list, offline = true,
                detail = PlantDetail(plant, emptyList(), emptyMap())), repository, initialPlant = plant.id, initialTask = task) } }
            compose.onNode(hasText("Offline") and hasClickAction()).performClick()
            compose.onAllNodesWithText("can't reach Fytorium", substring = true).assertCountEquals(1)
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Mark Water done for Synthetic fern, Synthetic site").assertIsNotEnabled()
            assertFalse(skipEnabled())
        }
        db.close()
    }
    @Test fun ownerRejectedTaskActionShowsMessageOnce() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val state = mutableStateOf(owner.copy(today = list, detail = PlantDetail(plant, emptyList(), emptyMap())))
        compose.setContent { FytoriumTheme { PlantsScreen(state.value, null, initialPlant = plant.id, initialTask = task,
            onTaskAction = { _, _ -> state.value = state.value.copy(message = "Synthetic care action rejected") }) } }
        menu("Water").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onAllNodesWithText("Synthetic care action rejected").assertCountEquals(1)
    }
    @Test fun ownerTaskShowsServerUnavailableNotice() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = list, serverUnavailable = true,
            detail = PlantDetail(plant, emptyList(), emptyMap())), null, initialPlant = plant.id, initialTask = task) } }
        compose.onNode(hasText("Fytorium isn't responding") and hasClickAction()).performClick()
        compose.onAllNodesWithText("Fytorium didn't answer.", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Actions are unavailable until Fytorium responds.", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Close").performClick()
        assertFalse(skipEnabled())
    }
    @Test fun tappedTaskUsesRefreshedRowsAndDisablesWritesOffline() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val event = parseEvent(JSONObject(eventFixture))
        val state = mutableStateOf(owner.copy(today = list))
        val opened = mutableStateOf(task)
        compose.setContent { FytoriumTheme { PlantDetailScreen(state.value, PlantDetail(plant, emptyList(), emptyMap()), {},
            { _, _ -> }, {}, {}, todayTask = opened.value) } }
        val undo = "Water done for Synthetic fern, Synthetic site. Double tap to undo"
        assertTrue(skipEnabled())
        // Completing the opened occurrence on this device moves it to its completion row.
        compose.runOnIdle { state.value = state.value.copy(today = list.copy(sections = listOf(TaskSection("water", "Water", listOf(task.copy(completedEvent = event))))),
            transitions = listOf(task.occurrence to event.id))
            opened.value = task.copy(completedEvent = event) }
        menu("Water").assertDoesNotExist()
        compose.onNodeWithText("Done today").assertIsDisplayed()
        compose.onNodeWithContentDescription(undo).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(offline = true) }
        compose.onNodeWithContentDescription("Water done for Synthetic fern, Synthetic site").assertIsNotEnabled()
        // Undo moves it back to the pending occurrence.
        compose.runOnIdle { state.value = state.value.copy(offline = false, today = list, transitions = state.value.transitions + (event.id to task.occurrence)); opened.value = task }
        assertTrue(skipEnabled())
        compose.onNodeWithContentDescription(undo).assertDoesNotExist()
    }
    @Test fun todayDetailBackCallsTheParentExactlyOnce() {
        val task = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        var backs = 0
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(detail = PlantDetail(plant, emptyList(), emptyMap())), null,
            initialPlant = plant.id, initialTask = task, onBack = { backs++ }) } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
    @Test fun listSearchUsesDisplayAndScientificNamesWithoutASiteFilter() {
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantsList(owner, owner.plants, false, {}, {}, {}, {}) } }
        compose.onNodeWithText("Search plants").performTextInput("Inventor")
        scroll("Synthetic fern").assertExists()
        compose.onNodeWithText("Search plants").performTextReplacement("missing")
        scroll("No plant called \u201cmissing\u201d").assertExists()
        compose.onNodeWithText("Filter by site").assertDoesNotExist()
        // The top bar's Add icon keeps a 48 dp touch target, which the accessibility checks verify.
        compose.onNodeWithContentDescription("Add plant").assertHasClickAction()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun fuzzyAndPartialCandidatesNeedExplicitServerRequestedConfirmation() {
        val result = SpeciesSearch("synthetic-search", listOf(SpeciesCandidate(910001, "Testoria lucida Inventor", "Testoria lucida", "SPECIES", "FUZZY", 92, true, "Δοκιμή", "el")))
        var accepted = false
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { SpeciesPicker(true, result, {}, { _, _, confirmed -> accepted = confirmed }) } }
        scroll("Choose Testoria lucida").performClick()
        assertFalse(accepted)
        scroll("Confirm Testoria lucida").performClick()
        assertTrue(accepted)
    }
    @Test fun searchCardsHeadlineTheCommonNameAndOnlyFuzzyMatchesShowCloseness() {
        val fuzzy = SpeciesCandidate(910001, "Testoria lucida Inventor", "Testoria lucida", "SPECIES", "FUZZY", 92, true, "Δοκιμή", "el", commonName = "Synthetic lantern")
        val stored = SpeciesCandidate(910002, "Testoria nebulosa Inventor", "Testoria nebulosa", "SPECIES", null, null, false, "Synthetic cloud", "en")
        val exact = SpeciesCandidate(910003, "Testoria aurea Inventor", "Testoria aurea", "SPECIES", "EXACT", 99, false, null, null, commonName = "Synthetic gold")
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { SpeciesPicker(true, SpeciesSearch("synthetic-search", listOf(fuzzy, stored, exact)), {}, { _, _, _ -> }) } }
        compose.onNode(hasText("Synthetic lantern") and isHeading()).assertIsDisplayed()
        scroll("Testoria lucida").assertExists()
        scroll("Close match, 92%").assertExists()
        compose.onNode(hasText("Testoria nebulosa") and isHeading()).performScrollTo().assertExists()
        compose.onNode(hasText("Synthetic gold") and isHeading()).performScrollTo().assertExists()
        compose.onAllNodes(hasText("Close match", substring = true)).assertCountEquals(1)
        for (gone in listOf("Matched", "Stored species", "confidence", "EXACT", "SPECIES")) compose.onAllNodes(hasText(gone, substring = true)).assertCountEquals(0)
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun aSearchCardSaysWhichOtherNameMatchedAndChoosesByItsHeadline() {
        val alias = SpeciesCandidate(910004, "Testoria ovata Inventor", "Testoria ovata", "SPECIES", null, null, false, "Synthetic rubberleaf", "en", commonName = "Synthetic jade")
        val spelled = SpeciesCandidate(910005, "Testoria fasciata Inventor", "Testoria fasciata", "SPECIES", null, null, false, "synthetic-snake LEAF", "en", commonName = "Synthetic snake leaf")
        val latin = SpeciesCandidate(910006, "Testoria aurea Inventor", "Testoria aurea", "SPECIES", null, null, false, "TESTORIA aurea", "en")
        var chosen: SpeciesCandidate? = null
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { SpeciesPicker(true, SpeciesSearch("synthetic-search", listOf(alias, spelled, latin)), {}, { candidate, _, _ -> chosen = candidate }) } }
        scroll("Matches \u201cSynthetic rubberleaf\u201d").assertExists()
        compose.onAllNodes(hasText("Matches", substring = true)).assertCountEquals(1)
        scroll("Choose Synthetic jade").assertHasClickAction()
        scroll("Choose Testoria aurea").assertHasClickAction()
        scroll("Choose Synthetic snake leaf").performClick()
        compose.runOnIdle { assertEquals(spelled, chosen) }
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun historyUndoNamesWhatItReversesAndUndoneBatchesLoseTheirHint() {
        fun event(id: String, vararg fields: Pair<String, Any>) = parseEvent(JSONObject(eventFixture).put("id", id).apply { fields.forEach { (key, value) -> put(key, value) } })
        val batch = arrayOf("batch_id" to "batch-one", "batch_plant_count" to 1, "batch_event_count" to 3, "batch_action" to "complete-all", "batch_task" to JSONObject.NULL)
        val history = listOf(event("single"), event("snoozed", "task_type" to "fertilize", "kind" to "snoozed"), event("batched", *batch),
            event("undone", *batch, "undone_at" to "2030-06-02T10:05:00Z", "can_undo" to false))
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, PlantDetail(plant, history, emptyMap()), {}, { _, _ -> }, {}, {}, options = syntheticOptions()) } }
        // Scrolled to the last entry, all four history entries are composed.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Undone"))
        compose.onAllNodes(hasText("Part of a care batch. Undo reverses the whole batch.")).assertCountEquals(1)
        for ((id, label) in listOf("single" to "Undo watering", "snoozed" to "Undo fertilizing snooze", "batched" to "Undo all 3")) {
            compose.onNodeWithTag("undo:$id").assert(hasText(label))
        }
        compose.onNodeWithTag("undo:undone").assertDoesNotExist()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun historyUndoNamesWhatEachKindOfEventReverses() {
        fun event(id: String, vararg fields: Pair<String, Any>) = parseEvent(JSONObject(eventFixture).put("id", id).apply { fields.forEach { (key, value) -> put(key, value) } })
        fun batch(action: String, plants: Int, events: Int) = arrayOf<Pair<String, Any>>("batch_id" to "batch-$action", "batch_action" to action, "batch_plant_count" to plants, "batch_event_count" to events)
        val custom = arrayOf<Pair<String, Any>>("task_type" to JSONObject.NULL, "custom_task_id" to "custom-one")
        val labels = listOf(
            event("skipped", "task_type" to "repot", "kind" to "skipped") to "Undo repotting skip",
            event("custom", *custom, "custom_task_name" to "Synthetic misting") to "Undo Synthetic misting",
            event("nameless", *custom) to "Undo custom task",
            event("liquid", "task_type" to "fertilize") to "Undo fertilizing and watering",
            event("linked", "linked_event_id" to "liquid") to "Undo watering",
            event("alone", "task_type" to "fertilize") to "Undo fertilizing",
            event("card", "task_type" to "fertilize", *batch("complete-type", 2, 4), "batch_task" to "fertilize") to "Undo all 2",
            event("rain", *batch("rain", 5, 5)) to "Undo all 5",
            event("handled", *batch("complete-all", 1, 3)) to "Undo all 3",
            event("uncounted", "batch_id" to "batch-old") to "Undo all",
        )
        val names = mapOf("card" to "Undo fertilizing and watering for 2 plants, including Synthetic fern",
            "rain" to "Undo watering for 5 outdoor plants, including Synthetic fern", "handled" to "Undo 3 tasks for Synthetic fern")
        val undoneWater = event("dried", "linked_event_id" to "alone", "undone_at" to "2030-06-02T10:05:00Z", "can_undo" to false)
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, PlantDetail(plant, labels.map { it.first } + undoneWater, emptyMap()), {}, { _, _ -> }, {}, {}, options = syntheticOptions()) } }
        // History shows its latest ten entries until asked for all of them.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Show all history (11)"))
        compose.onNodeWithText("Show all history (11)").performClick()
        for ((event, label) in labels) {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("undo:${event.id}"))
            compose.onNodeWithTag("undo:${event.id}").assert(hasText(label))
            names[event.id]?.let { compose.onNodeWithTag("undo:${event.id}").assert(hasContentDescription(it)) }
        }
    }
    @Test fun plantPageShowsTheCareReceiptWithUndo() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val receipt = Receipt("Skipped watering for Synthetic fern until Tue 4 Jun", parseEvent(JSONObject(eventFixture).put("kind", "skipped")))
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = list, receipt = receipt, detail = PlantDetail(plant, emptyList(), emptyMap())), null,
            initialPlant = plant.id, initialTask = task) } }
        compose.onNodeWithText("Skipped watering for Synthetic fern until Tue 4 Jun").assertIsDisplayed()
        compose.onNode(hasText("Undo") and hasClickAction() and !hasAnyAncestor(hasTestTag("today-task:${task.plantId}:${task.key}"))).assertIsDisplayed()
    }
    @Test fun plantPageCareWriteBusiesOnlyItsOwnControl() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val history = listOf(parseEvent(JSONObject(eventFixture).put("task_type", "fertilize")),
            parseEvent(JSONObject(eventFixture).put("id", "event-two").put("task_type", "fertilize").put("occurred_on", "2030-05-20")))
        val state = mutableStateOf(owner.copy(today = list, pending = setOf(BusyKey.HandleAll("plant-one")), detail = PlantDetail(plant, history, emptyMap())))
        compose.setContent { FytoriumTheme { PlantsScreen(state.value, null, initialPlant = plant.id, initialTask = task) } }
        fun undo(id: String = "event-one"): SemanticsNodeInteraction {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("undo:$id"))
            return compose.onNodeWithTag("undo:$id")
        }
        scroll("Handle all tasks").assertIsNotEnabled()
        assertTrue(skipEnabled())
        undo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(pending = setOf(BusyKey.Row(task.occurrence))) }
        assertFalse(skipEnabled())
        compose.onNodeWithContentDescription("Saving Water for Synthetic fern, Synthetic site").assertExists()
        scroll("Handle all tasks").assertIsEnabled()
        undo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(pending = setOf(BusyKey.History("event-one"))) }
        undo().assertIsNotEnabled()
        undo("event-two").assertIsEnabled()
        assertTrue(skipEnabled())
    }
    @Test fun confirmationBoundsStayFixedThroughDelayedPhotoCreditAndEqualSearchResults() {
        val creditReady = java.util.concurrent.CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!request.path.orEmpty().endsWith("/credit")) {
                        val image = java.io.ByteArrayOutputStream().apply {
                            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, this)
                        }
                        return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(okio.Buffer().write(image.toByteArray()))
                    }
                    if (!creditReady.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    return MockResponse().setBody("""{"common_name":"Synthetic cloudleaf","photo":{"creator":"Synthetic photographer","license":"CC BY 4.0","source_url":"https://www.gbif.org/occurrence/1"}}""")
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            val candidate = SpeciesCandidate(910002, "Testoria nebulosa Inventor", "Testoria nebulosa", "SPECIES", "FUZZY", 92, true, null, null)
            val result = mutableStateOf(SpeciesSearch("synthetic-search", listOf(candidate)), neverEqualPolicy())
            var accepted = false
            compose.setContent { FytoriumTheme {
                SpeciesPicker(true, result.value, {}, { selected, searchId, confirmed ->
                    assertEquals(candidate, selected)
                    assertEquals("synthetic-search", searchId)
                    accepted = confirmed
                }, imageApi = api)
            } }
            try {
                scroll("Choose Testoria nebulosa").performClick()
                val confirm = scroll("Confirm Testoria nebulosa")
                val before = confirm.fetchSemanticsNode().boundsInRoot
                compose.runOnIdle { result.value = result.value.copy(candidates = result.value.candidates.toList()) }
                confirm.assertExists()
                creditReady.countDown()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("CC BY 4.0 · Photo: Synthetic photographer").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Reference photo of Testoria nebulosa").fetchSemanticsNodes().isNotEmpty() }
                val after = compose.onNodeWithText("Confirm Testoria nebulosa").fetchSemanticsNode().boundsInRoot
                println("A05 photo credit confirmation bounds: before=$before after=$after")
                assertEquals(before, after)
                assertFalse(accepted)
                scroll("Confirm Testoria nebulosa").performClick()
                compose.runOnIdle { assertTrue(accepted) }
            } finally { creditReady.countDown() }
        }
    }
    @Test fun sittersHaveHeadersButNoPrivateDetailOrMutationControls() {
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(member = Member("sitter-one", Role.sitter)), PlantDetail(plant, emptyList(), emptyMap()), {}, { _, _ -> }, {}, {}, options = syntheticOptions()) } }
        compose.onNodeWithContentDescription("Edit plant").assertDoesNotExist()
        compose.onNode(hasText("History") and isHeading()).assertDoesNotExist()
        compose.onNode(hasText("About this plant") and isHeading()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Plant settings").assertDoesNotExist()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun wateringProvenanceOnThePlantPageShowsOverrideAndReset() {
        val rule = parseTaskList(taskListFixture("today", basis = """[{"input":"wateringIntervalDays","value":7,"source":"rule","adjustment":"Default watering interval","ruleId":"water.default@1"}]"""))
            .sections.single().tasks.single()
        val task = mutableStateOf(rule.copy(basedOn = rule.basedOn.map { it.copy(value = 9, source = "override", ruleId = null) }))
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, PlantDetail(plant, emptyList(), mapOf("water" to task.value)), {}, { _, _ -> }, {}, {}) } }
        scroll("Details").performClick()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("basis:water"))
        val water = hasAnyAncestor(hasTestTag("basis:water"))
        compose.onNode(water and hasText("Based on")).performScrollTo().performClick()
        compose.onNode(water and hasText("wateringIntervalDays")).performScrollTo().assertIsDisplayed()
        compose.onNode(water and hasText("9")).performScrollTo().assertIsDisplayed()
        compose.onNode(water and hasText("Source: override")).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { task.value = rule }
        compose.onNode(water and hasText("Source: rule · water.default@1")).performScrollTo().assertIsDisplayed()
        compose.onNode(water and hasText("7")).performScrollTo().assertIsDisplayed()
        compose.onNode(water and hasText("Source: override")).assertDoesNotExist()
    }
    @Test fun historyUndoAndPermanentDeletionFollowServerFlagAndExactName() {
        val allowed = mutableStateOf(true)
        val pulling = mutableStateOf(false)
        var undone = false
        var deleted: String? = null
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, PlantDetail(plant, listOf(parseEvent(JSONObject(eventFixture)).copy(canUndo = allowed.value)), emptyMap()), {},
            { action, body -> if (action == "delete") deleted = body.getString("confirm_name") }, {}, { undone = true }, options = syntheticOptions(), refreshing = pulling.value) } }
        scroll("Undo watering").performClick()
        assertTrue(undone)
        compose.runOnIdle { allowed.value = false }
        // A flag that changed elsewhere disables the Undo in place until the next pull.
        compose.onNodeWithText("Undo watering").assertIsNotEnabled()
        compose.runOnIdle { pulling.value = true }
        compose.runOnIdle { pulling.value = false }
        compose.onNodeWithText("Undo watering").assertDoesNotExist()
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Delete plant").performClick()
        compose.onNodeWithText("Permanently delete plant").assertIsNotEnabled()
        compose.onNodeWithText("Type Synthetic fern to confirm").performTextInput("Synthetic fern")
        compose.onNodeWithText("Permanently delete plant").assertIsEnabled().performClick()
        assertEquals("Synthetic fern", deleted)
    }
    @Test fun editingFieldsStayEnabledAndKeepUnsavedInputDuringAnActualDetailRefresh() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val repo = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, cache, { true })
            repo.restore()
            compose.setContent { FytoriumTheme {
                val request by repo.plantState.collectAsState()
                val care by repo.state.collectAsState()
                PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), request.enabled(care.member, care.updateRequired), onSave = {})
            } }
            compose.onNodeWithText("Name").performTextReplacement("Unsaved fern")
            scroll("More details").performClick()
            server.enqueue(MockResponse().setBody(plantFixture).setBodyDelay(2, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"customTasks":{}}"""))
            server.enqueue(MockResponse().setBody("""{"customIntervals":{}}"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[]"))
            val worker = thread { repo.openPlant("plant-one", full = true) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(repo.plantState.value.refreshing)
            compose.onNodeWithText("Name").assertIsEnabled()
            scroll("Cultivar").assertIsEnabled()
            scroll("Near AC").assertIsEnabled()
            worker.join(10_000)
            assertFalse(worker.isAlive)
            scroll("Name").assertIsEnabled()
            compose.onNodeWithText("Unsaved fern").assertExists()
        }
        db.close()
    }
    @Test fun changingSpeciesNeedsSaveAfterConfirmationAndRetainsTheWebExplanation() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            val options = syntheticOptions()
            val choices = JSONObject().put("plant", JSONObject().put("choices", JSONObject(options.choices)).put("labels", JSONObject(options.labels))
                .put("numbers", org.json.JSONArray(options.numbers)).put("booleans", org.json.JSONArray(options.booleans)).put("health", org.json.JSONArray(options.health)))
                .put("nameLanguages", JSONObject(options.languages)).toString()
            val writes = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl!!.encodedPath) {
                        "/api/choices" -> choices
                        "/api/sites" -> "[]"
                        "/api/plants" -> "[$plantFixture]"
                        "/api/plants/plant-one" -> plantFixture
                        "/api/plants/plant-one/tasks" -> "{\"customTasks\":{}}"
                        "/api/plants/plant-one/care-settings" -> "{\"customIntervals\":{}}"
                        "/api/species/search" -> """{"search_id":"s","candidates":[{"gbif_key":910002,"canonical_name":"Testoria prior","scientific_name":"Testoria prior Inventor","rank":"SPECIES","requires_confirmation":true}]}"""
                        "/api/plants/plant-one/species" -> { writes.incrementAndGet(); plantFixture }
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            cache.save("member-one", mapOf("plants" to "[$plantFixture]"), 0)
            val repo = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, cache, { true })
            repo.restore()
            val dark = mutableStateOf(false)
            compose.enableAccessibilityChecks()
            compose.setContent { FytoriumTheme(dark.value) { val care by repo.state.collectAsState(); PlantsScreen(care, repo) } }
            scroll("Synthetic fern").performClick()
            compose.waitUntil(5_000) { repo.state.value.detail != null }
            compose.onNodeWithContentDescription("Plant settings").performClick()
            compose.onNodeWithText("Change species").performClick()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Species name").assertExists(); true }.getOrDefault(false) }
            compose.onNodeWithText("Synthetic fern is currently Testoria lucida. Care history, photos and custom intervals will stay. The schedule will use the new species.").assertExists()
            compose.onNodeWithText("Species name").performTextInput("Testoria prior")
            scroll("Search species").performClick()
            compose.waitUntil(5_000) { runCatching { scroll("Choose Testoria prior").assertExists(); true }.getOrDefault(false) }
            scroll("Choose Testoria prior").performClick()
            assertEquals(0, writes.get())
            scroll("Confirm Testoria prior").performClick()
            assertEquals(0, writes.get())
            for (night in listOf(false, true)) {
                compose.runOnIdle { dark.value = night }
                compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
                compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-species-change-${if (night) "dark" else "light"}.png")
            }
            compose.onNodeWithText("Change Synthetic fern to Testoria prior").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { writes.get() == 1 }
        }
        db.close()
    }
    @Test fun serverFieldErrorsAppearBesideFormAndRepotFields() {
        val repot = mutableStateOf(false)
        compose.setContent { FytoriumTheme {
            if (repot.value) PlantRepotForm(plant, syntheticOptions(), true, mapOf("pot_type" to "Invalid pot", "soil_type" to "Invalid soil", "pot_size_cm" to "Invalid size"), {})
            else PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, mapOf("nickname" to "Too long", "birthday" to "Invalid birthday", "near_ac" to "Invalid switch"), {})
        } }
        scroll("Too long").assertExists()
        scroll("Invalid birthday").assertExists()
        scroll("Invalid switch").assertExists()
        compose.runOnIdle { repot.value = true }
        // C29: Repot shows a refused pot or soil type under its button, named by its field.
        scroll("Pot type: Invalid pot").assertExists()
        scroll("Soil type: Invalid soil").assertExists()
        scroll("Invalid size").assertExists()
    }
    @Test fun nativePlantsLight() = screenshots(false)
    @Test fun nativePlantsDark() = screenshots(true)
    private fun screenshots(dark: Boolean) {
        val screen = mutableStateOf("list")
        // The plant page shows the care rows, history and photos the server sends for this plant.
        val today = parseTaskList(taskListFixture("today"))
        val history = listOf(parseEvent(JSONObject(eventFixture).put("occurred_at", "2030-06-02T08:00:00.000Z")),
            parseEvent(JSONObject(eventFixture).put("id", "event-two").put("task_type", "fertilize").put("kind", "snoozed").put("occurred_on", "2030-06-01")
                .put("occurred_at", "2030-06-01T09:00:00.000Z").put("can_undo", false)))
        val page = PlantDetail(plant, history, parseSchedule(JSONObject(scheduleFixture)), """{"customIntervals":{"fertilize":9}}""",
            """[{"changed_on":"2030-05-20","changed_at":"2030-05-20T12:00:00.000Z","previous_name":"Testoria prior","next_name":"Testoria lucida","by_name":"Synthetic member"}]""",
            listOf("photo-one", "photo-two"))
        val pageState = owner.copy(today = today)
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme(dark) { Scaffold(topBar = {
            if (screen.value !in listOf("detail", "history", "delete", "menu", "list", "graveyard", "care", "graveyard-page", "details", "bury")) TopAppBar(title = { Heading(when(screen.value) { "list" -> "Plants"; "form", "unnamed" -> "Edit plant"; "add", "search" -> "Add plant"; "detail", "history", "delete" -> plant.displayName; else -> screen.value.replaceFirstChar { it.uppercase() } }) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }) { padding -> Surface(Modifier.fillMaxSize().padding(padding)) {
            when (screen.value) {
                "list" -> PlantsList(pageState, owner.plants + plant.copy(id = "plant-two", displayName = "Synthetic ivy", siteName = "Synthetic balcony"), false, {}, {}, {}, {})
                "graveyard" -> PlantsList(owner, owner.plants.map { it.copy(status = "graveyard", attributesJson = JSONObject(it.attributesJson).put("graveyard_on", "2030-06-02").toString()) }, true, {}, {}, {}, {})
                "detail", "delete", "history", "menu", "details", "bury" -> PlantDetailScreen(pageState, page, {}, { _, _ -> }, {}, {}, options = syntheticOptions(), onSpeciesCare = {})
                "graveyard-page" -> PlantDetailScreen(owner, page.copy(plant = plant.copy(status = "graveyard", attributesJson = JSONObject(plant.attributesJson).put("graveyard_on", "2030-06-02").toString()),
                    tasks = emptyMap()), {}, { _, _ -> }, {}, {}, options = syntheticOptions(), onSpeciesCare = {})
                "search" -> SpeciesPicker(true, SpeciesSearch("synthetic-search", listOf(SpeciesCandidate(910001, "Testoria lucida Inventor", "Testoria lucida", "SPECIES", "FUZZY", 92, true, null, null, commonName = "Synthetic lantern"))), {}, { _, _, _ -> })
                "restore" -> RestoreForm(plant, listOf(PlantSite("site-one", "Synthetic site")), true, {})
                "repot" -> PlantRepotForm(plant, syntheticOptions(), true, onSave = {})
                "add" -> PlantForm(null, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {})
                "birthday" -> PlantForm(plant.copy(attributesJson = JSONObject(plant.attributesJson).put("birthday", "2030-01-02").toString()), syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true,
                    onSave = {}, today = java.time.LocalDate.of(2030, 1, 15))
                "form" -> PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {})
                "care" -> PlantCareSettings("""{"customIntervals":{"fertilize":9}}""", true, {}, tasks = page.tasks, plantName = plant.displayName, onSpeciesCare = {}, onBack = {})
                "unnamed" -> PlantForm(plant.copy(displayName = "Synthetic trialleaf", attributesJson = JSONObject(plant.attributesJson).put("nickname", JSONObject.NULL).toString()),
                    syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {})
                else -> SpeciesNames(Species(910001, "Testoria lucida Inventor", listOf(SpeciesName("name-one", "Synthetic flower", "en", "owner", 2),
                    SpeciesName("name-two", "Trialleaf", "en", "gbif")), false, "Testoria lucida", "Synthetic flower"), syntheticOptions(), true, { _, _, _ -> })
            }
        } } } }
        for (name in listOf("list", "graveyard", "detail", "search", "form", "care", "names", "restore", "repot", "add", "delete", "history", "birthday", "menu",
            "graveyard-page", "details", "bury", "more-details", "unnamed")) {
            compose.runOnIdle { screen.value = if (name == "more-details") "form" else name }
            if (name == "menu") {
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNodeWithText("This plant's schedule").assertIsEnabled()
            }
            if (name == "delete" || name == "bury") {
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNodeWithText(if (name == "delete") "Delete plant" else "Move to graveyard").performClick()
            }
            if (name == "history") compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Species changed", substring = true))
            if (name == "details") { scroll("Details").performClick(); compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("basis:water")) }
            if (name == "birthday") { scroll("More details").performClick(); scroll("Birthday: 2 Jan 2030").performClick() }
            if (name == "more-details") scroll("More details").performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/native-plants-$name-${if (dark) "dark" else "light"}.png"
            if (name in listOf("delete", "birthday", "bury")) compose.captureDialog(file)
            else compose.onRoot().captureRoboImage(file)
            if (name == "delete") compose.onNodeWithText("Cancel deletion").performClick()
            if (name == "birthday" || name == "bury") compose.onNodeWithText("Cancel").performClick()
            if (name == "menu") compose.onNodeWithText("This plant's schedule").performClick()
            if (name == "search") {
                scroll("Choose Synthetic lantern").performClick()
                compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
                compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-confirm-${if (dark) "dark" else "light"}.png")
            }
        }
    }
}
fun syntheticOptions() = PlantOptions(mapOf("pot_type" to listOf("plastic", "terracotta"), "soil_type" to listOf("all_purpose", "aroid"), "plant_size" to listOf("small", "large"), "growth_stage" to listOf("young", "mature"), "fertilizer_type" to listOf("liquid", "sticks")),
    mapOf("pot_type" to "Pot type", "soil_type" to "Soil type", "plant_size" to "Plant size", "growth_stage" to "Growth stage", "fertilizer_type" to "Fertilizer type", "pot_size_cm" to "Pot size in cm", "window_distance_cm" to "Window distance in cm", "drainage" to "Drainage", "grow_light" to "Grow light", "near_ac" to "Near AC", "near_heater" to "Near heater"), listOf("pot_size_cm", "window_distance_cm"), listOf("drainage", "grow_light", "near_ac", "near_heater"), listOf("poor", "fair", "good", "very_good"), mapOf("en" to "English", "nb" to "Norwegian", "el" to "Greek", "la" to "Latin"))
