package com.epaflix.fytorium

import androidx.room.Room
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlantRepositoryTest {
    @Volatile private var online = true
    private fun repository(block: (Repository, MockWebServer) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            server.dispatcher = object : QueueDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.requestUrl!!.encodedPath == "/api/tasks") MockResponse().setBody(taskListFixture(request.requestUrl!!.queryParameter("view")!!))
                    else super.dispatch(request)
            }
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val repo = Repository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, cache, { online })
            repo.restore(); block(repo, server)
        }
        db.close()
    }
    @Test fun plantReadFailuresDoNotPoisonCareAndKeepServerFieldErrors() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(repo.searchSpecies("fern"))
        assertFalse(repo.state.value.serverUnavailable)
        assertFalse(repo.state.value.offline)
        assertFalse(repo.plantState.value.blocked)
        assertTrue(repo.plantState.value.enabled(repo.state.value.member, false))
        assertNotNull(repo.plantState.value.reads["search"])
        assertNull(repo.plantState.value.errors["_form"])
        server.enqueue(MockResponse().setBody("""{"search_id":"s","candidates":[]}"""))
        repo.searchSpecies("fern")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":{"nickname":"Too long","pot_size_cm":"Invalid size"}}"""))
        assertNull(repo.savePlant("plant-one", JSONObject()))
        assertEquals(mapOf("nickname" to "Too long", "pot_size_cm" to "Invalid size"), repo.plantState.value.errors)
        assertFalse(repo.plantState.value.blocked)
    }
    @Test fun aValidSearchWithNoMatchClearsThePreviousSearchFailure() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(repo.searchSpecies("fern"))
        assertNotNull(repo.plantState.value.reads["search"])
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"No matching species"}"""))
        assertEquals(emptyList<com.epaflix.fytorium.plants.SpeciesCandidate>(), repo.searchSpecies("zzqxw")?.candidates)
        assertNull(repo.plantState.value.reads["search"])
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Search not allowed"}"""))
        assertNull(repo.searchSpecies("fern"))
        assertEquals("Search not allowed", repo.plantState.value.reads.getValue("search").message)
    }
    @Test fun careFailureDoesNotBlockPlantWritesOnValidatedNetwork() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(503))
        repo.refresh()
        assertTrue(repo.state.value.serverUnavailable)
        assertFalse(repo.state.value.writable)
        server.enqueue(MockResponse().setBody(plantFixture))
        server.enqueue(MockResponse().setBody("[$plantFixture]"))
        assertNotNull(repo.savePlant("plant-one", JSONObject().put("nickname", "fern")))
        awaitSchedules(repo)
        assertEquals(5, server.requestCount)
        assertFalse(repo.plantState.value.saving)
    }
    private fun awaitSchedules(repo: Repository) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (repo.state.value.upcoming == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull(repo.state.value.upcoming)
    }
    @Test fun scheduleChangingPlantWritesRefreshBothViewsWithoutGatingControls() = repository { repo, server ->
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath == "/api/tasks") {
                    requested.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    reads.incrementAndGet()
                    return MockResponse().setBody(taskListFixture(request.requestUrl!!.queryParameter("view")!!, "2030-06-09"))
                }
                return MockResponse().setBody(if (request.path == "/api/plants") "[$plantFixture]" else plantFixture)
            }
        }
        for ((index, action) in listOf("graveyard", "delete", "restore", "species", "care-settings", "repot").withIndex()) {
            assertTrue(repo.plantCommand("plant-one", action))
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            assertFalse(repo.state.value.busy)
            assertTrue(repo.state.value.writable)
            assertTrue(repo.plantState.value.enabled(repo.state.value.member, false))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reads.get() < (index + 1) * 2 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals((index + 1) * 2, reads.get())
        }
        awaitSchedules(repo)
        assertEquals("2030-06-09", repo.state.value.today!!.sections.first().tasks.first().dueOn)
        assertEquals("2030-06-09", repo.state.value.upcoming!!.sections.first().tasks.first().dueOn)
    }
    @Test fun backgroundReadErrorsKeepFormsEnabledButForbiddenWritesExplainTheGate() = repository { repo, server ->
        for (status in listOf(503, 403)) {
            server.enqueue(MockResponse().setResponseCode(status))
            repo.openPlant("plant-one", full = true)
            assertTrue(repo.plantState.value.enabled(repo.state.value.member, false))
            assertNotNull(repo.plantState.value.reads["plant/plant-one"])
            assertEquals(emptyMap<String, String>(), repo.plantState.value.errors)
        }
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(repo.savePlant("plant-one", JSONObject()))
        assertFalse(repo.plantState.value.enabled(repo.state.value.member, false))
        assertTrue(repo.plantState.value.errors.getValue("_form").contains("permissions"))
    }
    // A forbidden save keeps plant changes off through reads that fail, offline or unanswered, until a read succeeds.
    @Test fun aFailedReadKeepsAForbiddenSavesLock() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(repo.savePlant("plant-one", JSONObject()))
        assertTrue(repo.plantState.value.blocked)
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(repo.searchSpecies("fern"))
        assertTrue(repo.plantState.value.blocked)
        online = false
        assertNull(repo.plantSites())
        assertTrue(repo.plantState.value.blocked)
        online = true
        server.enqueue(MockResponse().setBody("""[{"id":"site-one","name":"Synthetic site"}]"""))
        assertNotNull(repo.plantSites())
        assertFalse(repo.plantState.value.blocked)
    }
    @Test fun requestGatesIgnoreBackgroundReadsButRespectSavingConnectivityAndSession() {
        val owner = Member("member-one", Role.owner)
        assertTrue(com.epaflix.fytorium.plants.PlantsRequestState(refreshing = true).enabled(owner, false))
        assertFalse(com.epaflix.fytorium.plants.PlantsRequestState(saving = true).enabled(owner, false))
        assertFalse(com.epaflix.fytorium.plants.PlantsRequestState(online = false).enabled(owner, false))
        assertFalse(com.epaflix.fytorium.plants.PlantsRequestState().enabled(owner, true))
        assertFalse(com.epaflix.fytorium.plants.PlantsRequestState().enabled(Member("sitter", Role.sitter), false))
    }
    @Test fun fuzzySearchKeepsServerConfirmationAndLanguageWithoutGuessing() = repository { repo, server ->
        server.enqueue(MockResponse().setBody("""{"search_id":"synthetic-search","candidates":[{"gbif_key":910001,"canonical_name":"Testoria lucida","scientific_name":"Testoria lucida Inventor","rank":"SPECIES","match_type":"FUZZY","confidence":92,"requires_confirmation":true,"matched_name":"Δοκιμή","matched_language":"el","common_name":"Synthetic lantern"}]}"""))
        val result = repo.searchSpecies("Δοκμή")!!
        assertEquals("Synthetic lantern", result.candidates.single().commonName)
        assertTrue(result.candidates.single().requiresConfirmation)
        assertEquals("el", result.candidates.single().matchedLanguage)
        assertEquals("synthetic-search", result.id)
        val request = server.takeRequest()
        assertEquals("Δοκμή", request.requestUrl!!.queryParameter("q"))
        assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
    }
    @Test fun fullDetailsCachePotCareSettingsAndSpeciesHistoryForOfflineReads() = repository { repo, server ->
        server.enqueue(MockResponse().setBody(plantFixture))
        server.enqueue(MockResponse().setBody("[$eventFixture]"))
        server.enqueue(MockResponse().setBody("""{"customTasks":{}}"""))
        server.enqueue(MockResponse().setBody("""{"customIntervals":{"water":12}}"""))
        server.enqueue(MockResponse().setBody("""[{"changed_on":"2030-06-01","previous_name":"Testoria prior","next_name":"Testoria lucida","by_name":"Synthetic member"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        val online = repo.openPlant("plant-one", full = true)!!
        assertEquals(14, online.plant.potSizeCm)
        assertEquals(12, JSONObject(online.careSettingsJson!!).getJSONObject("customIntervals").getInt("water"))
        assertTrue(online.speciesChangesJson.contains("Testoria prior"))
        assertTrue(online.history.single().canUndo)
        this.online = false
        repo.connectivityChanged(false)
        assertEquals(online, repo.openPlant("plant-one", full = true))
        assertFalse(repo.undoHistory(online.history.single()))
        assertEquals(6, server.requestCount)
    }
    // C1: offline shows under the top bar title, so an offline plant read leaves no form error that would stay after the connection returns.
    @Test fun anOfflinePlantReadLeavesNoFormError() = repository { repo, _ ->
        online = false
        repo.connectivityChanged(false)
        repo.openPlant("plant-one", full = true)
        assertFalse(repo.plantState.value.online)
        assertNull(repo.plantState.value.errors["_form"])
        assertEquals(true, repo.plantState.value.reads["plant/plant-one"]?.offline)
    }
    // Not every plant write reloads the plant, so the write's own answer ends a plant not-responding state.
    @Test fun aPlantWriteFytoriumAnswersEndsItsNotRespondingState() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(repo.speciesNameCommand(910001, "add", JSONObject().put("name", "Synthetic fern").put("language", "en")))
        assertTrue(repo.plantState.value.serverUnavailable)
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(repo.speciesNameCommand(910001, "add", JSONObject().put("name", "Synthetic fern").put("language", "en")))
        assertFalse(repo.plantState.value.serverUnavailable)
        assertEquals(2, server.requestCount)
    }
    // A read's failure stays with what it read: another read's success leaves it, and only its own next load clears it.
    @Test fun aReadFailureClearsOnlyWhenItsOwnReadLoads() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Synthetic read refusal"}"""))
        assertNull(repo.species(910001))
        server.enqueue(MockResponse().setBody("""[{"id":"site-one","name":"Synthetic site"}]"""))
        assertNotNull(repo.plantSites())
        val failure = repo.plantState.value.reads.getValue("species/910001")
        assertEquals("Synthetic read refusal", failure.message)
        assertTrue(failure.refused)
        assertEquals(setOf("species/910001"), repo.plantState.value.reads.keys)
        server.enqueue(MockResponse().setBody("""{"gbif_key":910001,"scientific_name":"Testoria lucida","names":[]}"""))
        assertNotNull(repo.species(910001))
        assertEquals(emptySet<String>(), repo.plantState.value.reads.keys)
    }
    // The open form is the only place a failed save shows, so a save whose connection drops mid-request keeps its form error.
    @Test fun aSaveThatLosesTheConnectionKeepsItsFormError() = repository { repo, server ->
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                online = false
                return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
        }
        assertNull(repo.savePlant("plant-one", JSONObject().put("nickname", "Synthetic fern")))
        assertFalse(repo.plantState.value.online)
        assertEquals("Could not reach Fytorium. Try again.", repo.plantState.value.errors["_form"])
    }
    private fun serve(server: MockWebServer, writes: MutableList<String> = mutableListOf(), responses: (String) -> String?) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method != "GET") synchronized(writes) { writes += "${request.method} $path ${request.body.readUtf8()}" }
                return responses(path)?.let { MockResponse().setBody(it) } ?: when (path) {
                    "/api/tasks" -> MockResponse().setBody(taskListFixture(request.requestUrl!!.queryParameter("view")!!))
                    "/api/plants" -> MockResponse().setBody("[$plantFixture]")
                    "/api/plants/plant-one" -> MockResponse().setBody(plantFixture)
                    "/api/plants/plant-one/tasks" -> MockResponse().setBody(scheduleFixture)
                    "/api/plants/plant-one/care-settings" -> MockResponse().setBody("""{"customIntervals":{}}""")
                    else -> MockResponse().setBody("[]")
                }
            }
        }
    }
    @Test fun fullDetailsCacheThePhotoStripForOfflineReads() = repository { repo, server ->
        serve(server) { path -> if (path == "/api/plants/plant-one/photos") """[{"id":"photo-two","plant_id":"plant-one","plant_name":"Synthetic fern","note":"","health":null,"taken_on":"2030-06-02","is_cover":false},
            {"id":"photo-one","plant_id":"plant-one","plant_name":"Synthetic fern","note":"","health":null,"taken_on":"2030-05-02","is_cover":true}]""" else null }
        assertEquals(listOf("photo-two", "photo-one"), repo.openPlant("plant-one", full = true)!!.photoIds)
        online = false
        repo.connectivityChanged(false)
        assertEquals(listOf("photo-two", "photo-one"), repo.openPlant("plant-one", full = true)!!.photoIds)
    }
    @Test fun movingToTheGraveyardGivesAReceiptWhoseUndoRestoresTheSameSite() = repository { repo, server ->
        val writes = mutableListOf<String>()
        // Both commands answer with the plant as it is now.
        serve(server, writes) { path -> when (path) {
            "/api/plants/plant-one/graveyard" -> JSONObject(plantFixture).put("status", "graveyard").put("graveyard_on", "2030-06-02").toString()
            "/api/plants/plant-one/restore" -> plantFixture
            else -> null
        } }
        repo.refreshPlants()
        assertTrue(repo.plantCommand("plant-one", "graveyard"))
        val receipt = repo.state.value.receipt!!
        assertEquals("Moved Synthetic fern to the graveyard", receipt.text)
        assertTrue(repo.undoReceipt(receipt))
        synchronized(writes) { assertEquals(listOf("POST /api/plants/plant-one/graveyard {}", """POST /api/plants/plant-one/restore {"site_id":"site-one"}"""), writes) }
        assertEquals("Restored Synthetic fern", repo.state.value.receipt!!.text)
    }
    @Test fun plantSavesSayWhatTheyDidAndARepotReceiptCanBeUndone() = repository { repo, server ->
        val writes = mutableListOf<String>()
        val repot = JSONObject(eventFixture).put("id", "event-repot").put("task_type", "repot").put("previous_pot", JSONObject().put("pot_type", "plastic"))
        serve(server, writes) { path -> when (path) {
            "/api/plants/plant-one/repot" -> """{"event":$repot,"task":null}"""
            "/api/care-events/event-repot/undo" -> """{"event":$repot,"restored":[],"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}"""
            else -> null
        } }
        repo.refreshPlants()
        assertNotNull(repo.savePlant("plant-one", JSONObject().put("nickname", "Synthetic fern")))
        assertEquals("Saved Synthetic fern", repo.state.value.receipt!!.text)
        assertTrue(repo.plantCommand("plant-one", "care-settings", JSONObject().put("customIntervals", JSONObject().put("water", 5))))
        assertEquals("Saved Synthetic fern's schedule", repo.state.value.receipt!!.text)
        assertTrue(repo.plantCommand("plant-one", "repot", JSONObject().put("pot_type", "terracotta").put("soil_type", "aroid").put("pot_size_cm", 16)))
        val receipt = repo.state.value.receipt!!
        assertEquals("Repotted Synthetic fern", receipt.text)
        assertNotNull(receipt.undo)
        assertTrue(repo.undoReceipt(receipt))
        synchronized(writes) { assertTrue(writes.any { it.startsWith("POST /api/care-events/event-repot/undo") }) }
    }
    private val repotEvent = JSONObject(eventFixture).put("id", "event-repot").put("task_type", "repot").put("previous_pot", JSONObject().put("pot_type", "plastic"))
    private val repotRow = CareTask("due", "2031-06-02", 365, 0, emptyList(), "repot", null, null, "plant-one", "Synthetic fern", "Synthetic site", parseEvent(repotEvent))
    @Test fun aFailedRepotUndoFromTodayShowsTodaysBannerAndTurnsCareWritesOff() {
        // A read timeout means Fytorium is not answering, or offline when the connection went with it.
        for ((stillOnline, timedOut) in listOf(true to false, true to true, false to true)) repository { repo, server ->
            online = true
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    online = stillOnline
                    return if (timedOut) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE) else MockResponse().setResponseCode(503)
                }
            }
            assertFalse(repo.act(repotRow, "undo"))
            assertEquals(!stillOnline, repo.state.value.offline)
            assertEquals(stillOnline, repo.state.value.serverUnavailable)
            assertFalse(repo.state.value.writable)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun aRepotUndoFromTodaySendsWhilePlantRequestsAreBlockedAndShowsTheEarlierPotInThePlantsList() = repository { repo, server ->
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(repo.savePlant("plant-one", JSONObject()))
        assertFalse(repo.plantState.value.enabled(repo.state.value.member, false))
        assertTrue(repo.state.value.writable)
        val writes = mutableListOf<String>()
        serve(server, writes) { path -> when (path) {
            "/api/care-events/event-repot/undo" -> """{"event":$repotEvent,"restored":[],"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}"""
            "/api/plants" -> "[${JSONObject(plantFixture).put("pot_type", "terracotta")}]"
            else -> null
        } }
        assertTrue(repo.act(repotRow, "undo"))
        synchronized(writes) { assertEquals(listOf("POST /api/care-events/event-repot/undo "), writes) }
        assertEquals("terracotta", repo.state.value.plants.single().potType)
    }
    @Test fun aPlantPageUndoOfLiquidFertilizingNamesItsWaterFromHistory() = repository { repo, server ->
        // The plant is hidden from Today and Upcoming, so only its history knows about the linked water.
        val fertilize = JSONObject(eventFixture).put("id", "event-fertilize").put("task_type", "fertilize")
        val water = JSONObject(eventFixture).put("id", "event-water").put("linked_event_id", "event-fertilize").put("can_undo", false)
        val empty = { view: String -> JSONObject(taskListFixture(view)).put("sections", org.json.JSONArray()).toString() }
        serve(server) { path -> when (path) {
            "/api/plants/plant-one/history" -> "[$fertilize,$water]"
            "/api/care-events/event-fertilize/undo" -> """{"event":$fertilize,"task":null,"restored":[],"today":${empty("today")},"upcoming":${empty("upcoming")}}"""
            else -> null
        } }
        val detail = repo.openPlant("plant-one")!!
        val task = detail.tasks.getValue("fertilize").copy(taskType = "fertilize", plantId = "plant-one", plantName = "Synthetic fern",
            siteName = "Synthetic site", completedEvent = detail.history.first())
        assertTrue(repo.act(task, "undo"))
        assertEquals("Undid fertilizing and watering for Synthetic fern", repo.state.value.receipt!!.text)
    }
    @Test fun completingATaskTheListsDoNotShowReadsTheListsBeforeItsRowIsFree() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val event = JSONObject(eventFixture).put("id", "event-leaves").put("task_type", "clean_leaves").put("due_on", "2030-06-20")
            serve(server) { path -> when (path) {
                "/api/tasks" -> { reads.incrementAndGet(); null }
                "/api/plants/plant-one/tasks/clean_leaves/complete" -> """{"event":$event,"task":null,"linked_events":[]}"""
                else -> null
            } }
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"sitter"}}""")
            cache.save("member-one", mapOf("today" to taskListFixture("today"), "upcoming" to taskListFixture("upcoming")), 0)
            val repo = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, cache, { true })
            repo.restore()
            // The sitter acts from the plant page, on a task the 14-day lists do not reach yet.
            assertNotNull(repo.openPlant("plant-one"))
            val leaves = CareTask("due", "2030-06-20", 30, 0, emptyList(), "clean_leaves", null, null, "plant-one", "Synthetic fern", "Synthetic site", null)
            assertTrue(repo.act(leaves, "complete"))
            // The server lists this member's completion of today, so the plant page shows it as done when its row is free again.
            assertEquals(2, reads.get())
        }
        db.close()
    }
    @Test fun plantPageWritesOnTasksTheListsDoNotShowRecordWhichOccurrenceEachRowBecame() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            // A location filter hides the plant from both lists, so only the plant page shows its rows.
            val hidden = JSONObject(taskListFixture("today")).put("sections", org.json.JSONArray()).toString()
            val schedule = JSONObject(scheduleFixture).put("clean_leaves", JSONObject("""{"status":"due","dueOn":"2030-06-20","intervalDays":30,"lateDays":0,"postponed":null,"basedOn":[]}""")).toString()
            val leaves = JSONObject(eventFixture).put("id", "event-leaves").put("task_type", "clean_leaves").put("due_on", "2030-06-20")
            val handled = JSONObject(eventFixture).put("id", "event-handled").put("batch_id", "batch-one").put("batch_action", "complete-all")
                .put("batch_plant_count", 1).put("batch_event_count", 1)
            serve(server) { path -> when (path) {
                "/api/tasks" -> hidden
                "/api/plants/plant-one/tasks" -> schedule
                "/api/plants/plant-one/tasks/clean_leaves/complete" -> """{"event":$leaves,"task":null,"linked_events":[]}"""
                "/api/care-events/event-leaves/undo" -> """{"event":$leaves,"restored":[{"event_id":"event-leaves","occurrence":{"plant_id":"plant-one","task_type":"clean_leaves","custom_task_id":null,"due_on":"2030-06-20"}}],"today":$hidden,"upcoming":$hidden}"""
                "/api/plants/plant-one/tasks/complete-all" -> """{"events":[$handled],"tasks":$schedule}"""
                else -> null
            } }
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            cache.save("member-one", mapOf("today" to hidden, "upcoming" to hidden), 0)
            val repo = Repository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, cache, { true })
            repo.restore()
            assertNotNull(repo.openPlant("plant-one", full = true))
            val pending = CareTask("due", "2030-06-20", 30, 0, emptyList(), "clean_leaves", null, null, "plant-one", "Synthetic fern", "Synthetic site", null)
            assertTrue(repo.act(pending, "complete"))
            assertTrue(pending.occurrence to "event-leaves" in repo.state.value.transitions)
            assertTrue(repo.act(pending.copy(completedEvent = parseEvent(leaves)), "undo"))
            assertTrue("event-leaves" to pending.occurrence in repo.state.value.transitions)
            assertTrue(repo.handleAll("plant-one", plantsScreen = true))
            assertTrue("plant-one:water:2030-06-01:null" to "event-handled" in repo.state.value.transitions)
        }
        db.close()
    }
    @Test fun allPlantFieldsAndClearValuesReachTheServerAndOfflineNeverWrites() = repository { repo, server ->
        val input = JSONObject("""{"site_id":"site-two","nickname":"Synthetic edited fern","cultivar":null,"birthday":null,"health":"fair","pot_type":"fabric","soil_type":"aroid","plant_size":"large","growth_stage":"mature","fertilizer_type":"sticks","pot_size_cm":28,"window_distance_cm":70,"drainage":true,"grow_light":false,"near_ac":true,"near_heater":false}""")
        server.enqueue(MockResponse().setBody(plantFixture))
        // Refresh fails, but a successful save still returns the plant, never replays the write.
        server.enqueue(MockResponse().setResponseCode(503))
        assertNotNull(repo.savePlant("plant-one", input))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(input.toString(), request.body.readUtf8())
        assertEquals("/api/plants/plant-one", request.path)
        awaitSchedules(repo)
        online = false
        repo.connectivityChanged(false)
        assertNull(repo.savePlant("plant-one", input))
        assertFalse(repo.plantCommand("plant-one", "graveyard"))
        assertEquals(4, server.requestCount)
    }
}
