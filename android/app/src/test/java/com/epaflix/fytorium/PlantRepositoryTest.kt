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
    private var online = true
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
        assertNotNull(repo.plantState.value.errors["_form"])
        server.enqueue(MockResponse().setBody("""{"search_id":"s","candidates":[]}"""))
        repo.searchSpecies("fern")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":{"nickname":"Too long","pot_size_cm":"Invalid size"}}"""))
        assertNull(repo.savePlant("plant-one", JSONObject()))
        assertEquals(mapOf("nickname" to "Too long", "pot_size_cm" to "Invalid size"), repo.plantState.value.errors)
        assertFalse(repo.plantState.value.blocked)
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
            assertNotNull(repo.plantState.value.errors["_form"])
        }
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(repo.savePlant("plant-one", JSONObject()))
        assertFalse(repo.plantState.value.enabled(repo.state.value.member, false))
        assertTrue(repo.plantState.value.errors.getValue("_form").contains("permissions"))
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
        server.enqueue(MockResponse().setBody("""{"search_id":"synthetic-search","candidates":[{"gbif_key":910001,"canonical_name":"Testoria lucida","scientific_name":"Testoria lucida Inventor","rank":"SPECIES","match_type":"FUZZY","confidence":92,"requires_confirmation":true,"matched_name":"Δοκιμή","matched_language":"el"}]}"""))
        val result = repo.searchSpecies("Δοκμή")!!
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
        val online = repo.openPlant("plant-one", full = true)!!
        assertEquals(14, online.plant.potSizeCm)
        assertEquals(12, JSONObject(online.careSettingsJson!!).getJSONObject("customIntervals").getInt("water"))
        assertTrue(online.speciesChangesJson.contains("Testoria prior"))
        assertTrue(online.history.single().canUndo)
        this.online = false
        repo.connectivityChanged(false)
        assertEquals(online, repo.openPlant("plant-one", full = true))
        assertFalse(repo.undoHistory(online.history.single()))
        assertEquals(5, server.requestCount)
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
