package com.epaflix.fytorium

import com.epaflix.fytorium.plants.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
import androidx.core.view.drawToBitmap
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
        compose.onNodeWithText("Edit plant").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Search plants").assertDoesNotExist()
    }
    @Test fun ownerTappedRepotOffersSkipRepotAndProvenance() = tappedTask(Role.owner)
    @Test fun sitterTappedRepotOffersSkipWithoutOwnerSections() = tappedTask(Role.sitter)

    private fun tappedTask(role: Role) {
        val list = parseTaskList(taskListFixture("today", basis = """[{"input":"repotIntervalDays","value":365,"source":"rule","adjustment":"Synthetic repot interval","ruleId":"repot.synthetic@1"}]"""))
        val task = list.sections.single().tasks.single().copy(taskType = "repot")
        val state = mutableStateOf(owner.copy(member = Member("member-one", role), today = list))
        val actions = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val dark = mutableStateOf(false)
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme(dark.value) { Surface(Modifier.fillMaxSize()) {
            PlantDetailScreen(state.value, PlantDetail(plant, emptyList(), emptyMap()), {},
                { action, _ -> commands.add(action) }, {}, {}, todayTask = task, onTaskAction = { actions.add(it) })
        } } }
        val block = hasAnyAncestor(hasTestTag("today-task:${task.plantId}:${task.key}"))
        compose.onNode(block and hasText("Repot")).assertIsDisplayed()
        compose.onNode(block and hasText("Skip")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("skip"), actions)
        compose.onNode(block and hasText("Based on")).performScrollTo().performClick()
        compose.onNode(block and hasText("Source: rule · repot.synthetic@1")).performScrollTo().assertIsDisplayed()
        if (role == Role.sitter) {
            compose.onNodeWithText("Care tasks are on Today and Upcoming.").assertDoesNotExist()
            compose.onNodeWithText("Repot with new pot and soil").assertDoesNotExist()
            for (label in listOf("Edit plant", "Photo updates", "Species and site", "Pot and plant attributes", "Care history", "Handle all tasks"))
                compose.onNodeWithText(label).assertDoesNotExist()
            compose.onNodeWithContentDescription("Plant settings").assertDoesNotExist()
        } else {
            compose.onNode(block and hasText("Repot with new pot and soil")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf("repot-form"), commands)
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("today-task:${task.plantId}:${task.key}"))
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/today-plant-task-${role.name}-${if (night) "dark" else "light"}.png")
        }
        compose.runOnIdle { state.value = state.value.copy(handled = mapOf("${task.plantId}:${task.key}" to
            parseEvent(JSONObject(eventFixture)).copy(kind = "skipped", taskType = "repot"))) }
        compose.onNode(block and hasText("Skip")).assertDoesNotExist()
        compose.onNode(block and hasText("Repot with new pot and soil")).assertDoesNotExist()
        compose.onNode(block and hasText("Skipped")).performScrollTo().assertIsDisplayed()
        compose.onNode(block and hasText("Undo")).performScrollTo().performClick()
        assertEquals(listOf("skip", "undo"), actions)
        if (role != Role.sitter) scroll("Repot with new pot and soil").assertIsDisplayed().performClick()
        if (role != Role.sitter) assertEquals(listOf("repot-form", "repot-form"), commands)
    }
    @Test fun sitterPlantWithoutTappedTaskKeepsTodayAndUpcomingHint() {
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner.copy(member = Member("member-one", Role.sitter)),
            PlantDetail(plant, emptyList(), emptyMap()), {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText("Care tasks are on Today and Upcoming.").assertIsDisplayed()
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
            compose.onAllNodesWithText("Actions are unavailable", substring = true).assertCountEquals(1)
            compose.onNodeWithText("Skip").assertIsNotEnabled()
        }
        db.close()
    }
    @Test fun ownerRejectedTaskActionShowsMessageOnce() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val state = mutableStateOf(owner.copy(today = list, detail = PlantDetail(plant, emptyList(), emptyMap())))
        compose.setContent { FytoriumTheme { PlantsScreen(state.value, null, initialPlant = plant.id, initialTask = task,
            onTaskAction = { _, _ -> state.value = state.value.copy(message = "Synthetic care action rejected") }) } }
        compose.onNodeWithText("Skip").performClick()
        compose.onAllNodesWithText("Synthetic care action rejected").assertCountEquals(1)
    }
    @Test fun ownerTaskShowsServerUnavailableNotice() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        compose.setContent { FytoriumTheme { PlantsScreen(owner.copy(today = list, serverUnavailable = true,
            detail = PlantDetail(plant, emptyList(), emptyMap())), null, initialPlant = plant.id, initialTask = task) } }
        compose.onAllNodesWithText("Fytorium isn't responding.", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Actions are unavailable until Fytorium responds.", substring = true).assertCountEquals(1)
        compose.onNodeWithText("Skip").assertIsNotEnabled()
    }
    @Test fun tappedTaskUsesRefreshedRowsAndDisablesWritesOffline() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val event = parseEvent(JSONObject(eventFixture))
        val state = mutableStateOf(owner.copy(today = list))
        compose.setContent { FytoriumTheme { PlantDetailScreen(state.value, PlantDetail(plant, emptyList(), emptyMap()), {},
            { _, _ -> }, {}, {}, todayTask = task) } }
        val block = hasAnyAncestor(hasTestTag("today-task:${task.plantId}:${task.key}"))
        compose.onNode(block and hasText("Skip")).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(today = list.copy(sections = listOf(TaskSection("water", "Water", listOf(task.copy(completedEvent = event)))))) }
        compose.onNode(block and hasText("Skip")).assertDoesNotExist()
        compose.onNode(block and hasText("Done")).assertIsDisplayed()
        compose.onNode(block and hasText("Undo")).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(offline = true) }
        compose.onNode(block and hasText("Undo")).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(offline = false, today = list) }
        compose.onNode(block and hasText("Skip")).assertIsEnabled()
        compose.onNode(block and hasText("Undo")).assertDoesNotExist()
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
        scroll("No plants found.").assertExists()
        compose.onNodeWithText("Filter by site").assertDoesNotExist()
        scroll("Add plant").assertHeightIsAtLeast(48.dp)
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
        compose.onNodeWithText("Edit plant").assertDoesNotExist()
        compose.onNodeWithText("Care history").assertDoesNotExist()
        compose.onNodeWithText("Pot and plant attributes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Plant settings").assertDoesNotExist()
        compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
    }
    @Test fun wateringProvenanceOnThePlantPageShowsOverrideAndReset() {
        val rule = parseTaskList(taskListFixture("today", basis = """[{"input":"wateringIntervalDays","value":7,"source":"rule","adjustment":"Default watering interval","ruleId":"water.default@1"}]"""))
            .sections.single().tasks.single()
        val task = mutableStateOf(rule.copy(basedOn = rule.basedOn.map { it.copy(value = 9, source = "override", ruleId = null) }))
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, PlantDetail(plant, emptyList(), mapOf("water" to task.value)), {}, { _, _ -> }, {}, {}) } }
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
        var undone = false
        var deleted: String? = null
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme { PlantDetailScreen(owner, PlantDetail(plant, listOf(parseEvent(JSONObject(eventFixture)).copy(canUndo = allowed.value)), emptyMap()), {},
            { action, body -> if (action == "delete") deleted = body.getString("confirm_name") }, {}, { undone = true }, options = syntheticOptions()) } }
        scroll("Undo").performClick()
        assertTrue(undone)
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Delete plant").performClick()
        compose.onNodeWithText("Permanently delete plant").assertIsNotEnabled()
        compose.onNodeWithText("Type the plant name to confirm").performTextInput("Synthetic fern")
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
            compose.onNodeWithText("Nickname").performTextReplacement("Unsaved fern")
            server.enqueue(MockResponse().setBody(plantFixture).setBodyDelay(2, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"customTasks":{}}"""))
            server.enqueue(MockResponse().setBody("""{"customIntervals":{}}"""))
            server.enqueue(MockResponse().setBody("[]"))
            val worker = thread { repo.openPlant("plant-one", full = true) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(repo.plantState.value.refreshing)
            compose.onNodeWithText("Nickname").assertIsEnabled()
            compose.onNodeWithText("Cultivar").assertIsEnabled()
            scroll("Near AC").assertIsEnabled()
            worker.join(10_000)
            assertFalse(worker.isAlive)
            scroll("Nickname").assertIsEnabled()
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
            compose.onNodeWithText("Synthetic fern is currently Testoria lucida Inventor. Care history, photos and custom intervals will stay. The schedule will use the new species.").assertExists()
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
            compose.onNodeWithText("Save species").assertIsEnabled().performClick()
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
        scroll("Invalid pot").assertExists()
        scroll("Invalid soil").assertExists()
        scroll("Invalid size").assertExists()
    }
    private fun captureDialog(file: String) {
        // Roborazzi 1.44's window fallback misses Compose dialogs. Draw their owning view directly.
        val layout = compose.onNode(isDialog()).fetchSemanticsNode().layoutInfo
        val owner = layout.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layout) as android.view.View
        compose.runOnIdle { owner.drawToBitmap().captureRoboImage(file) }
    }
    @Test fun nativePlantsLight() = screenshots(false)
    @Test fun nativePlantsDark() = screenshots(true)
    private fun screenshots(dark: Boolean) {
        val screen = mutableStateOf("list")
        compose.enableAccessibilityChecks()
        compose.setContent { FytoriumTheme(dark) { Scaffold(topBar = {
            if (screen.value !in listOf("detail", "history", "delete", "menu")) TopAppBar(title = { Heading(when(screen.value) { "list" -> "Plants"; "form" -> "Edit plant"; "add", "search" -> "Add plant"; "detail", "history", "delete" -> plant.displayName; else -> screen.value.replaceFirstChar { it.uppercase() } }) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }) { padding -> Surface(Modifier.fillMaxSize().padding(padding)) {
            when (screen.value) {
                "list" -> PlantsList(owner, owner.plants, false, {}, {}, {}, {})
                "graveyard" -> PlantsList(owner, owner.plants.map { it.copy(status = "graveyard", attributesJson = JSONObject(it.attributesJson).put("graveyard_on", "2030-06-02").toString()) }, true, {}, {}, {}, {})
                "detail", "delete", "history", "menu" -> PlantDetailScreen(owner, PlantDetail(plant, listOf(parseEvent(JSONObject(eventFixture))), emptyMap(), """{"customIntervals":{"water":7}}"""), {}, { _, _ -> }, {}, {}, options = syntheticOptions())
                "search" -> SpeciesPicker(true, SpeciesSearch("synthetic-search", listOf(SpeciesCandidate(910001, "Testoria lucida Inventor", "Testoria lucida", "SPECIES", "FUZZY", 92, true, null, null))), {}, { _, _, _ -> })
                "restore" -> RestoreForm(plant, listOf(PlantSite("site-one", "Synthetic site")), true, {})
                "repot" -> PlantRepotForm(plant, syntheticOptions(), true, onSave = {})
                "add" -> PlantForm(null, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {})
                "birthday" -> PlantForm(plant.copy(attributesJson = JSONObject(plant.attributesJson).put("birthday", "2030-01-02").toString()), syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {})
                "form" -> PlantForm(plant, syntheticOptions(), listOf(PlantSite("site-one", "Synthetic site")), true, onSave = {})
                "care" -> PlantCareSettings("""{"customIntervals":{"water":7}}""", true, {})
                else -> SpeciesNames(Species(910001, "Testoria lucida Inventor", listOf(SpeciesName("name-one", "Synthetic flower", "en", "owner")), false, false), syntheticOptions(), true, { _, _ -> })
            }
        } } } }
        for (name in listOf("list", "graveyard", "detail", "search", "form", "care", "names", "restore", "repot", "add", "delete", "history", "birthday", "menu")) {
            compose.runOnIdle { screen.value = name }
            if (name == "menu") {
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNodeWithText("Care settings").assertIsEnabled()
            }
            if (name == "delete") {
                compose.onNodeWithContentDescription("Plant settings").performClick()
                compose.onNodeWithText("Delete plant").performClick()
            }
            if (name == "history") scroll("Care history")
            if (name == "birthday") scroll("Birthday: 2030-01-02").performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/native-plants-$name-${if (dark) "dark" else "light"}.png"
            if (name in listOf("delete", "birthday")) captureDialog(file)
            else compose.onRoot().captureRoboImage(file)
            if (name == "delete") compose.onNodeWithText("Cancel deletion").performClick()
            if (name == "birthday") compose.onNodeWithText("Cancel").performClick()
            if (name == "search") {
                scroll("Choose Testoria lucida").performClick()
                compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
                compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-confirm-${if (dark) "dark" else "light"}.png")
            }
        }
    }
}
fun syntheticOptions() = PlantOptions(mapOf("pot_type" to listOf("plastic", "terracotta"), "soil_type" to listOf("all_purpose", "aroid"), "plant_size" to listOf("small", "large"), "growth_stage" to listOf("young", "mature"), "fertilizer_type" to listOf("liquid", "sticks")),
    mapOf("pot_type" to "Pot type", "soil_type" to "Soil type", "plant_size" to "Plant size", "growth_stage" to "Growth stage", "fertilizer_type" to "Fertilizer type", "pot_size_cm" to "Pot size in cm", "window_distance_cm" to "Window distance in cm", "drainage" to "Drainage", "grow_light" to "Grow light", "near_ac" to "Near AC", "near_heater" to "Near heater"), listOf("pot_size_cm", "window_distance_cm"), listOf("drainage", "grow_light", "near_ac", "near_heater"), listOf("poor", "fair", "good", "very_good"), mapOf("en" to "English", "nb" to "Norwegian", "el" to "Greek", "la" to "Latin"))
