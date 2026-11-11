package com.epaflix.fytorium.feed

import androidx.room.Room
import com.epaflix.fytorium.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRefreshTest {
    @Test fun slowFeedDoesNotDropTheNextCareRefresh() {
        val server = MockWebServer()
        val feedStarted = CountDownLatch(1)
        val releaseFeed = CountDownLatch(1)
        val nextTodayRead = CountDownLatch(1)
        val day = AtomicReference("2030-06-02")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path == "/api/me" -> """{"member":{"id":"synthetic-member","role":"owner"}}"""
                    request.path!!.startsWith("/api/tasks?") -> {
                        if (day.get() == "2030-06-03" && request.path!!.contains("view=today")) nextTodayRead.countDown()
                        taskListFixture(if (request.path!!.contains("view=upcoming")) "upcoming" else "today").replace("2030-06-02", day.get())
                    }
                    request.path == "/api/plants" -> "[]"
                    request.path == "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    request.path == "/api/feed" -> { feedStarted.countDown(); releaseFeed.await(5, TimeUnit.SECONDS); feedFixture }
                    request.path == "/api/weather" -> weatherFixture
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CareDatabase::class.java).allowMainThreadQueries().build()
        val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
        val repository = Repository(api, CareCache(db), { true })
        val controller = CareController(context, store, repository, api, FeedRepository(api, { true }))
        try {
            controller.refresh()
            assertTrue(feedStarted.await(5, TimeUnit.SECONDS))
            day.set("2030-06-03")
            controller.refresh()
            assertTrue("A slow feed dropped the next task refresh", nextTodayRead.await(2, TimeUnit.SECONDS))
        } finally { releaseFeed.countDown(); controller.close(); server.shutdown(); db.close() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(message, condition())
    }
    @Test fun aPlantPageHistoryUndoRefreshesTheFeed() {
        val server = MockWebServer()
        val feedReads = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path == "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    request.path!!.startsWith("/api/tasks?") -> taskListFixture(if (request.path!!.contains("view=upcoming")) "upcoming" else "today")
                    request.path == "/api/plants" -> "[]"
                    request.path == "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    request.path == "/api/feed" -> { feedReads.incrementAndGet(); feedFixture }
                    request.path == "/api/weather" -> weatherFixture
                    request.path == "/api/care-events/event-one/undo" ->
                        """{"event":$eventFixture,"today":${taskListFixture("today")},"upcoming":${taskListFixture("upcoming")}}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CareDatabase::class.java).allowMainThreadQueries().build()
        val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
        val repository = Repository(api, CareCache(db), { true })
        val feed = FeedRepository(api, { true })
        val controller = CareController(context, store, repository, api, feed)
        try {
            await("The first feed read finishes") { feed.state.value.loaded && !feed.state.value.refreshing && !repository.state.value.busy }
            val before = feedReads.get()
            assertTrue("The history Undo succeeds", repository.undoHistory(parseEvent(org.json.JSONObject(eventFixture))))
            await("The history Undo refreshes the feed") { feedReads.get() > before && !feed.state.value.refreshing }
        } finally { controller.close(); server.shutdown(); db.close() }
    }
    @Test fun aPullsSpinnerLastsUntilItsFeedRefreshEnds() {
        val server = MockWebServer()
        val holdFeed = java.util.concurrent.atomic.AtomicBoolean(false)
        val feedStarted = CountDownLatch(1)
        val releaseFeed = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path == "/api/me" -> """{"member":{"id":"member-one","role":"owner"}}"""
                    request.path!!.startsWith("/api/tasks?") -> taskListFixture(if (request.path!!.contains("view=upcoming")) "upcoming" else "today")
                    request.path == "/api/plants" -> "[]"
                    request.path == "/api/meta" -> """{"timezone":"Europe/Oslo"}"""
                    request.path == "/api/feed" -> { if (holdFeed.get()) { feedStarted.countDown(); releaseFeed.await(5, TimeUnit.SECONDS) }; feedFixture }
                    request.path == "/api/weather" -> weatherFixture
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CareDatabase::class.java).allowMainThreadQueries().build()
        val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
        val repository = Repository(api, CareCache(db), { true })
        val feed = FeedRepository(api, { true })
        val controller = CareController(context, store, repository, api, feed)
        try {
            await("The first refresh finishes") { feed.state.value.loaded && !feed.state.value.refreshing && !repository.state.value.busy }
            holdFeed.set(true)
            controller.refresh(pulled = true)
            assertTrue(feedStarted.await(5, TimeUnit.SECONDS))
            assertTrue("The spinner ended before the pull's feed refresh, so the strip would wait for the next pull", controller.pulling.value)
            releaseFeed.countDown()
            await("The spinner ends with the pull's feed refresh") { !controller.pulling.value }
            assertFalse(feed.state.value.refreshing)
        } finally { releaseFeed.countDown(); controller.close(); server.shutdown(); db.close() }
    }
    @Test fun onlyAPullShowsTheRefreshSpinner() {
        val server = MockWebServer()
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CareDatabase::class.java).allowMainThreadQueries().build()
        ServedLists(server, hold = { it.path == "/api/me" }).use { lists ->
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val repository = Repository(api, CareCache(db), { true })
            val controller = CareController(context, store, repository, api)
            try {
                lists.await { "/api/me" in lists.paths }
                assertTrue(repository.state.value.busy)
                assertFalse("A background refresh must not show the spinner", controller.pulling.value)
                controller.refresh(pulled = true)
                assertTrue(controller.pulling.value)
                lists.release()
                await("The spinner ends with the refresh that serves the pull") { !controller.pulling.value }
                await("The refresh finishes") { !repository.state.value.busy }
            } finally { lists.release(); controller.close(); server.shutdown(); db.close() }
        }
    }
}
