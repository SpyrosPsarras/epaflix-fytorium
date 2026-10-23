package com.epaflix.fytorium

import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    @Test fun typeBatchRetriesReuseRequestIdAndKeepEveryRowsUndo() {
        withRepository { repository, server, cache ->
            val events = listOf("plant-one", "plant-two").mapIndexed { index, plant ->
                org.json.JSONObject(eventFixture).put("id", "batch-event-$index").put("plant_id", plant)
                    .put("task_type", "clean_leaves").put("batch_id", "batch-one")
            }
            fun list(view: String, completed: Boolean = false): String {
                val json = org.json.JSONObject(taskListFixture(view).replace("water", "clean_leaves"))
                val rows = json.getJSONArray("sections").getJSONObject(0).getJSONArray("tasks")
                rows.put(org.json.JSONObject(rows.getJSONObject(0).toString()).put("plant_id", "plant-two"))
                if (completed) events.forEachIndexed { index, event -> rows.getJSONObject(index).put("completed_event", event) }
                json.getJSONObject("groups").put("clean_leaves", rows)
                return json.toString()
            }
            fun refreshResponses() {
                server.enqueue(MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"}}"""))
                server.enqueue(MockResponse().setBody(list("today")))
                server.enqueue(MockResponse().setBody(list("upcoming")))
                server.enqueue(MockResponse().setBody("[]"))
                server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Oslo"}"""))
            }
            refreshResponses(); repository.refresh()
            repeat(5) { server.takeRequest() }
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(repository.handleType("clean_leaves"))
            val failed = server.takeRequest()
            refreshResponses(); repository.refresh()
            repeat(5) { server.takeRequest() }
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "batch-one").put("events", org.json.JSONArray(events)).toString()))
            server.enqueue(MockResponse().setBody(list("today", completed = true)))
            server.enqueue(MockResponse().setBody(list("upcoming", completed = true)))
            assertTrue(repository.handleType("clean_leaves"))
            val success = server.takeRequest()
            assertEquals("POST", success.method)
            assertEquals("/api/tasks/clean_leaves/complete-all", success.path)
            val requestId = org.json.JSONObject(success.body.readUtf8()).getString("request_id")
            java.util.UUID.fromString(requestId)
            assertEquals(requestId, org.json.JSONObject(failed.body.readUtf8()).getString("request_id"))
            assertEquals("/api/tasks?view=today", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/tasks?view=upcoming", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            val handled = repository.state.value.handled
            assertEquals(setOf("plant-one:clean_leaves", "plant-two:clean_leaves"), handled.keys)
            for (row in repository.state.value.today!!.sections.single().tasks) {
                assertEquals(listOf("undo"), row.actions("2030-06-02", Member("member-one", Role.owner)))
            }
            assertTrue(cache.get("member-one", "today")!!.json.contains("batch-event-1"))
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "batch-two").put("events", org.json.JSONArray()).toString()))
            server.enqueue(MockResponse().setBody(list("today", completed = true)))
            server.enqueue(MockResponse().setBody(list("upcoming", completed = true)))
            assertTrue(repository.handleType("clean_leaves"))
            val nextId = org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("request_id")
            java.util.UUID.fromString(nextId)
            assertNotEquals(requestId, nextId)
            repeat(2) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(503))
            val row = repository.state.value.today!!.sections.single().tasks.first()
            assertTrue(repository.act(row, "undo"))
            assertEquals("/api/task-batches/batch-one/undo", server.takeRequest().path)
            assertEquals("/api/me", server.takeRequest().path)
            assertTrue(repository.state.value.handled.isEmpty())
            assertTrue(repository.state.value.today!!.sections.single().tasks.all { it.completedEvent == null })
        }
    }
    @Test fun typeBatchSupportsCustomTaskKeysAndRejectsSitterAndOfflineWrites() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, sitter = true); repository.refresh()
            assertFalse(repository.handleType("clean_leaves"))
            assertEquals(5, server.requestCount)
            enqueueRefresh(server); repository.refresh()
            repeat(10) { server.takeRequest() }
            repository.connectivityChanged(false)
            assertFalse(repository.handleType("clean_leaves"))
            assertEquals(10, server.requestCount)
            enqueueRefresh(server); repository.refresh()
            repeat(5) { server.takeRequest() }
            val customKey = "91000000-0000-4000-8000-000000000001"
            val event = org.json.JSONObject(eventFixture).put("task_type", org.json.JSONObject.NULL).put("custom_task_id", customKey).put("batch_id", "custom-batch")
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("batch_id", "custom-batch").put("events", org.json.JSONArray().put(event)).toString()))
            server.enqueue(MockResponse().setBody(taskListFixture("today")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
            assertTrue(repository.handleType(customKey))
            assertEquals("/api/tasks/$customKey/complete-all", server.takeRequest().path)
            assertEquals(customKey, repository.state.value.handled["plant-one:$customKey"]!!.customTaskId)
            repeat(2) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
    }
    @Test fun fertilizeDoneReloadsWaterScheduleWithoutManualRefresh() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            repeat(5) { server.takeRequest() }
            fun detail(due: String) {
                server.enqueue(MockResponse().setBody(plantFixture))
                server.enqueue(MockResponse().setBody("[]"))
                server.enqueue(MockResponse().setBody("""{"water":{"status":"due","dueOn":"$due","intervalDays":7,"lateDays":0,"basedOn":[]},"customTasks":{}}"""))
            }
            detail("2030-06-01"); repository.openPlant("plant-one")
            repeat(3) { server.takeRequest() }
            val task = repository.state.value.today!!.sections.single().tasks.single().copy(taskType = "fertilize")
            val event = org.json.JSONObject(eventFixture).put("task_type", "fertilize")
            server.enqueue(MockResponse().setBody(org.json.JSONObject().put("event", event)
                .put("task", org.json.JSONObject().put("status", "due").put("dueOn", "2030-06-16").put("intervalDays", 14).put("lateDays", 0).put("basedOn", org.json.JSONArray())).toString()))
            detail("2030-06-09")
            server.enqueue(MockResponse().setBody(taskListFixture("today", "2030-06-09")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming", "2030-06-09")))
            assertTrue(repository.act(task, "complete"))
            assertEquals("2030-06-09", repository.state.value.detail!!.tasks["water"]!!.dueOn)
            assertEquals("/api/plants/plant-one/tasks/fertilize/complete", server.takeRequest().path)
            assertEquals("/api/plants/plant-one", server.takeRequest().path)
            assertEquals("/api/plants/plant-one/history", server.takeRequest().path)
            assertEquals("/api/plants/plant-one/tasks", server.takeRequest().path)
            assertEquals("/api/tasks?view=today", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/tasks?view=upcoming", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) {
                    while (repository.state.value.upcoming!!.sections.single().tasks.single().dueOn != "2030-06-09") kotlinx.coroutines.delay(10)
                }
            }
            assertEquals("2030-06-09", repository.state.value.today!!.sections.single().tasks.single().dueOn)
            assertTrue(cache.get("member-one", "upcoming")!!.json.contains("2030-06-09"))
            assertEquals("fertilize", repository.state.value.handled["plant-one:fertilize"]!!.taskType)
        }
    }
    @Test fun logoutAndMemberChangeEraseEveryCachedResource() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        val cache = CareCache(db)
        val first = """{"member":{"id":"member-one","role":"owner"}}"""
        cache.bind(first)
        cache.save("member-one", mapOf("today" to taskListFixture("today"), "upcoming" to taskListFixture("upcoming"),
            "plants" to "[]", "plant/plant-one" to "{\"synthetic\":true}"), 1234)
        cache.bind("""{"member":{"id":"member-two","role":"sitter"}}""")
        for (resource in listOf("today", "upcoming", "plants", "plant/plant-one")) assertNull(cache.get("member-one", resource))
        assertEquals("member-two", cache.member()!!.id)
        cache.wipe()
        assertNull(cache.member())
        db.close()
    }
    @Test fun staleSaveAfterMemberRebindIsDiscarded() {
        val db = database()
        try {
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val pendingMember = cache.member()!!.id
            cache.bind("""{"member":{"id":"member-two","role":"family"}}""")
            cache.save("member-two", mapOf("plants" to "[]"), 1234)

            cache.save(pendingMember, mapOf("plants" to "[$plantFixture]", "today" to taskListFixture("today")), 5678)

            assertEquals("member-two", cache.member()!!.id)
            assertNull(cache.get(pendingMember, "plants"))
            assertNull(cache.get(pendingMember, "today"))
            assertNull(cache.get("member-two", "today"))
            assertEquals(CachedCare("member-two", "plants", "[]", 1234), cache.get("member-two", "plants"))
        } finally { db.close() }
    }
    @Test fun staleSaveAfterWipeIsDiscarded() {
        val db = database()
        try {
            val cache = CareCache(db)
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            val pendingMember = cache.member()!!.id
            cache.wipe()

            cache.save(pendingMember, mapOf("plants" to "[$plantFixture]", "today" to taskListFixture("today")), 5678)

            assertNull(cache.member())
            assertNull(cache.get(pendingMember, "plants"))
            assertNull(cache.get(pendingMember, "today"))
        } finally { db.close() }
    }
    @Test fun pendingPlantOptionsAfterCacheRebindAreDiscardedWithoutThrowing() {
        withRepository { repository, server, cache ->
            cache.bind("""{"member":{"id":"member-one","role":"owner"}}""")
            repository.restore()
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody("""{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":["good"]},"nameLanguages":{}}""")
                }
            }
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<com.epaflix.fytorium.plants.PlantOptions?> { repository.plantOptions() }
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                cache.bind("""{"member":{"id":"member-two","role":"family"}}""")
                release.countDown()

                assertNull(pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("member-two", cache.member()!!.id)
                assertNull(cache.get("member-one", "plant-choices"))
                assertNull(cache.get("member-two", "plant-choices"))
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }
    @Test fun incompatibleCacheSchemaIsDiscardedRatherThanBlockingStartup() {
        val context = RuntimeEnvironment.getApplication()
        val name = "synthetic-cache-migration.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CareDatabase::class.java, name)
            .fallbackToDestructiveMigration(dropAllTables = true).allowMainThreadQueries().build()
        val original = open()
        CareCache(original).bind("""{"member":{"id":"member-one","role":"owner"}}""")
        original.openHelper.writableDatabase.execSQL("PRAGMA user_version = 2")
        original.close()
        val migrated = open()
        assertNull(CareCache(migrated).member())
        migrated.close()
        context.deleteDatabase(name)
    }
    @Test fun thumbnailCacheEvictsLeastRecentlyUsedAndRejectsOversizedEntries() {
        val dir = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "synthetic-thumbnails").apply { deleteRecursively() }
        val cache = ThumbnailCache(dir, 10)
        cache.put("first", ByteArray(4) { 1 }); cache.put("second", ByteArray(4) { 2 })
        assertArrayEquals(ByteArray(4) { 1 }, cache.get("first"))
        cache.put("third", ByteArray(4) { 3 })
        assertNull(cache.get("second"))
        assertNotNull(cache.get("first")); assertNotNull(cache.get("third"))
        cache.put("huge", ByteArray(11))
        assertNull(cache.get("huge"))
        assertTrue(cache.sizeBytes() <= 10)
        cache.wipe(); assertEquals(0L, cache.sizeBytes())
    }
    @Test fun cachedPlantDetailsAndHistorySurviveProcessRestoreAndNetworkFailure() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            server.enqueue(MockResponse().setBody(plantFixture))
            server.enqueue(MockResponse().setBody("[$eventFixture]"))
            server.enqueue(MockResponse().setBody("""{"water":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[]},"customTasks":{}}"""))
            val detail = repository.openPlant("plant-one")!!
            assertEquals("Synthetic fern", detail.plant.displayName)
            assertEquals("event-one", detail.history.single().id)
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val restored = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, cache, { false })
            restored.restore()
            val offline = restored.openPlant("plant-one")!!
            assertFalse(restored.plantState.value.online)
            assertFalse(restored.state.value.offline)
            assertEquals(detail, offline)
            assertEquals(8, server.requestCount)
        }
    }
    @Test fun updateRequiredBlocksWritesWithoutCallingTheServerAgain() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setResponseCode(426).setBody("""{"error":"update_required","minAppVersion":"2.0.0"}"""))
            repository.refresh()
            assertTrue(repository.state.value.updateRequired)
            assertFalse(repository.act(task, "complete"))
            assertEquals(6, server.requestCount)
            for (status in listOf(503, 422)) {
                server.enqueue(MockResponse().setResponseCode(status)); repository.refresh()
                assertTrue(repository.state.value.updateRequired)
                assertFalse(repository.state.value.writable)
            }
            enqueueRefresh(server); repository.refresh()
            assertFalse(repository.state.value.updateRequired)
            assertTrue(repository.state.value.writable)
        }
    }
    @Test fun aRefreshInFlightCannotRestoreDataAfterLogout() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"}}""")
                }
            }
            val thread = Thread { repository.refresh() }.apply { start() }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repository.wipe()
            release.countDown(); thread.join(5000)
            assertFalse(thread.isAlive)
            assertNull(repository.state.value.today)
            assertNull(cache.member())
            assertEquals(6, server.requestCount)
        }
    }
    @Test fun completeKeepsUndoAndUndoRestoresTheServerTask() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server)
            repository.refresh()
            val original = repository.state.value.today!!.sections.single().tasks.single()
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":{"status":"due","dueOn":"2030-06-09","intervalDays":7,"lateDays":0,"basedOn":[]}}"""))
            assertTrue(repository.act(original, "complete"))
            val done = repository.state.value.today!!.sections.single().tasks.single()
            assertEquals(listOf("undo"), done.actions("2030-06-02", Member("member-one", Role.owner)))
            assertEquals("event-one", done.completedEvent!!.id)
            assertTrue(cache.get("member-one", "today")!!.json.contains("event-one"))
            server.enqueue(MockResponse().setBody("""{"event":$eventFixture,"task":{"status":"due","dueOn":"2030-06-01","intervalDays":7,"lateDays":1,"basedOn":[]}}"""))
            // A lost refresh after a successful undo must not leave another Undo in the cache.
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(repository.act(done, "undo"))
            assertTrue(repository.state.value.serverUnavailable)
            assertNull(repository.state.value.today!!.sections.single().tasks.single().completedEvent)
            val requests = (1..7).map { server.takeRequest() }
            assertEquals("/api/plants/plant-one/tasks/water/complete", requests[5].path)
            assertEquals("Bearer synthetic-access", requests[5].getHeader("Authorization"))
            java.util.UUID.fromString(org.json.JSONObject(requests[5].body.readUtf8()).getString("request_id"))
            assertEquals("/api/care-events/event-one/undo", requests[6].path)
        }
    }
    @Test fun logoutWipesDataEvenWhenTheRevokeRequestFails() {
        val db = database()
        val cache = CareCache(db)
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            lateinit var repository: Repository
            val api = ApiClient(server.url("").toString().trimEnd('/'), credentials) { repository.wipe() }
            val disk = ThumbnailCache(java.io.File(RuntimeEnvironment.getApplication().cacheDir, "logout-thumbnails"))
            repository = Repository(api, cache, { true }, wipeThumbnails = disk::wipe)
            enqueueRefresh(server); repository.refresh()
            disk.put("synthetic-thumb", ByteArray(8))
            server.enqueue(MockResponse().setResponseCode(503))
            api.logout()
            assertNull(credentials.tokens)
            assertNull(repository.state.value.today)
            assertNull(cache.member())
            assertNull(cache.get("member-one", "today"))
            assertNull(disk.get("synthetic-thumb"))
        }
        db.close()
    }
    @Test fun memberSwitchWipesPreviousDataBeforeAnOfflineRefreshCanPublishIt() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            server.enqueue(MockResponse().setBody("""{"member":{"id":"member-two","role":"family"}}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            repository.refresh()
            assertEquals("member-two", repository.state.value.member!!.id)
            assertNull(repository.state.value.today)
            assertNull(cache.get("member-one", "today"))
        }
    }
    @Test fun connectivityLossBlocksWritesAndAConnectedTransportNeedsSuccessfulRefresh() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server); repository.refresh()
            val task = repository.state.value.today!!.sections.single().tasks.single()
            repository.connectivityChanged(false)
            assertFalse(repository.act(task, "complete"))
            repository.connectivityChanged(true)
            assertTrue(repository.state.value.offline)
            enqueueRefresh(server); repository.refresh()
            assertFalse(repository.state.value.offline)
            assertEquals(10, server.requestCount)
        }
    }
    @Test fun sitterRefreshReadsAllowedTaskAndSharedPlantProjections() {
        withRepository { repository, server, _ ->
            enqueueRefresh(server, sitter = true); repository.refresh()
            assertEquals(Role.sitter, repository.state.value.member!!.role)
            assertTrue(repository.state.value.plants.isEmpty())
            assertEquals(listOf("/api/me", "/api/tasks?view=today", "/api/tasks?view=upcoming", "/api/plants", "/api/meta"), (1..5).map { server.takeRequest().path })
        }
    }
    @Test fun offlineBeforeFirstRefreshDoesNotInventATimestamp() {
        val db = database()
        MockWebServer().use { server ->
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, CareCache(db), { false })
            repository.refresh()
            assertEquals("Offline, not updated yet", repository.state.value.offlineBanner())
            assertTrue(repository.state.value.offline)
            assertFalse(repository.state.value.serverUnavailable)
            assertEquals(0, server.requestCount)
        }
        db.close()
    }
    @Test fun serverErrorsAndMalformedResponsesKeepCacheAndBlockWritesUntilSuccessfulRefresh() {
        withRepository { repository, server, cache ->
            enqueueRefresh(server); repository.refresh()
            val before = repository.state.value.today
            for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("not json"))) {
                val timestamp = repository.state.value.lastUpdated
                server.enqueue(response); repository.refresh()
                assertFalse(repository.state.value.offline)
                assertTrue(repository.state.value.serverUnavailable)
                assertEquals(before, repository.state.value.today)
                assertEquals(timestamp, repository.state.value.lastUpdated)
                assertTrue(repository.state.value.serverBanner().startsWith("Fytorium isn't responding. Showing data from "))
                val calls = server.requestCount
                assertFalse(repository.act(before!!.sections.single().tasks.single(), "complete"))
                assertEquals(calls, server.requestCount)
                assertNotNull(cache.get("member-one", "today"))
                enqueueRefresh(server); repository.refresh()
                assertFalse(repository.state.value.serverUnavailable)
                assertTrue(repository.state.value.writable)
            }
        }
    }
    @Test fun serverFailureBeforeFirstRefreshSaysNotUpdatedYet() {
        withRepository { repository, server, _ ->
            server.enqueue(MockResponse().setResponseCode(500)); repository.refresh()
            assertEquals("Fytorium isn't responding. Not updated yet", repository.state.value.serverBanner())
            assertFalse(repository.state.value.offline)
            assertTrue(repository.state.value.serverUnavailable)
        }
    }
    @Test fun validatedNetworkTreatsDnsRefusedAndUnansweredConnectionsAsServerFailures() {
        MockWebServer().use { server ->
            server.start()
            val timeoutUrl = server.url("").toString().trimEnd('/')
            val closed = MockWebServer().apply { start() }
            val closedUrl = closed.url("").toString().trimEnd('/')
            closed.shutdown()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            for (url in listOf("http://fytorium-does-not-exist.invalid", closedUrl, timeoutUrl)) {
                val db = database()
                val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
                val repository = Repository(ApiClient(url, credentials) {}, CareCache(db), { true })
                repository.refresh()
                assertFalse(repository.state.value.offline)
                assertTrue(repository.state.value.serverUnavailable)
                db.close()
            }
        }
    }
    @Test fun tokenRefreshFailuresUseTheValidatedConnectivitySeamAndKeepTheSession() {
        for (validated in listOf(true, false)) {
            val db = database()
            MockWebServer().use { server ->
                server.start()
                val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
                var online = true
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                        if (request.path == "/api/app/token") {
                            online = validated
                            return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        return MockResponse().setResponseCode(401)
                    }
                }
                val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, CareCache(db), { online })
                repository.refresh()
                assertEquals(!validated, repository.state.value.offline)
                assertEquals(validated, repository.state.value.serverUnavailable)
                assertEquals("synthetic-access", credentials.tokens!!.access)
                assertEquals("synthetic-refresh", credentials.tokens!!.refresh)
                assertEquals(2, server.requestCount)
            }
            db.close()
        }
        for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("malformed"),
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))) {
            withRepository { repository, server, _ ->
                server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(response)
                repository.refresh()
                assertFalse(repository.state.value.offline)
                assertTrue(repository.state.value.serverUnavailable)
            }
        }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
    private fun withRepository(block: (Repository, MockWebServer, CareCache) -> Unit) {
        val db = database(); val cache = CareCache(db)
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            block(Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, cache, { true }), server, cache)
        }
        db.close()
    }
    private fun enqueueRefresh(server: MockWebServer, sitter: Boolean = false) {
        server.enqueue(MockResponse().setBody("""{"member":{"id":"member-one","role":"${if (sitter) "sitter" else "owner"}"}}"""))
        server.enqueue(MockResponse().setBody(taskListFixture("today")))
        server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Oslo"}"""))
    }
    @Test fun successfulRefreshSurvivesOfflineAndNeverQueuesWrites() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
        MockWebServer().use { server ->
            server.start()
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val cache = CareCache(db)
            val repository = Repository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, cache, { true }, { 1_900_000_000_000L })
            server.enqueue(MockResponse().setBody("""{"member":{"id":"member-one","role":"owner"},"household":{"id":"household-one","name":"Synthetic"}}"""))
            server.enqueue(MockResponse().setBody(taskListFixture("today")))
            server.enqueue(MockResponse().setBody(taskListFixture("upcoming")))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Oslo"}"""))
            repository.refresh()
            assertEquals("Synthetic fern", repository.state.value.today!!.sections.single().tasks.single().plantName)
            assertFalse(repository.state.value.offline)
            server.enqueue(MockResponse().setResponseCode(503))
            repository.refresh()
            assertTrue(repository.state.value.serverUnavailable)
            assertFalse(repository.state.value.offline)
            assertEquals(1_900_000_000_000L, repository.state.value.lastUpdated)
            assertEquals("Synthetic fern", repository.state.value.today!!.sections.single().tasks.single().plantName)
            val requests = server.requestCount
            assertFalse(repository.act(repository.state.value.today!!.sections.single().tasks.single(), "complete"))
            assertEquals(requests, server.requestCount)
        }
        db.close()
    }
}

val plantFixture = """{"id":"plant-one","display_name":"Synthetic fern","scientific_name":"Testoria lucida Inventor","canonical_name":"Testoria lucida","status":"active","site_name":"Synthetic site","cover_photo_id":null,"pot_type":"plastic","pot_size_cm":14,"soil_type":"all_purpose","site_id":"site-one","nickname":"Synthetic fern","cultivar":null,"birthday":null,"drainage":true,"plant_size":"small","growth_stage":"young","window_distance_cm":null,"grow_light":false,"near_ac":false,"near_heater":false,"fertilizer_type":"liquid","health":null,"household_id":"household-one","gbif_key":910001,"created_at":"2030-06-01T10:00:00Z","attributes_as_of":"2030-06-01","graveyard_on":null,"rank":"SPECIES"}"""
val eventFixture = """{"id":"event-one","plant_id":"plant-one","task_type":"water","custom_task_id":null,"kind":"completed","occurred_on":"2030-06-02","by_member":"member-one","by_name":"Synthetic member","batch_id":null,"undone_at":null,"can_undo":true}"""

fun taskListFixture(view: String, due: String = "2030-06-01", event: String = "", basis: String = "[]"): String = """{
 "today":"2030-06-02","view":"$view","settings":{"sort":"task","filter":"all","show_completed":false},
 "groups":{},"sections":[{"key":"${if (view == "upcoming") maxOf(due, "2030-06-02") else "water"}","label":"${if (view == "upcoming") maxOf(due, "2030-06-02") else "Water"}","tasks":[{
 "status":"due","dueOn":"$due","intervalDays":7,"lateDays":1,"basedOn":$basis,
 "task_type":"water","custom_task_id":null,"plant_id":"plant-one","plant_name":"Synthetic fern","site_name":"Synthetic site"$event}]}]}"""
