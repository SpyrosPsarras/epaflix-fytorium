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
}
