package com.epaflix.fytorium.feed

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.epaflix.fytorium.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedPollingTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    @Test fun onlyTheVisibleTodayFeedEnablesPolling() {
        assertTrue(homeFeedVisible("Today", "today", false))
        assertFalse("Upcoming hides the feed", homeFeedVisible("Today", "upcoming", false))
        assertFalse("Feed settings hide the feed", homeFeedVisible("Today", "today", true))
        for (tab in listOf("Plants", "Journal", "More")) assertFalse(homeFeedVisible(tab, "today", false))
    }
    @Test fun lifecycleAndConnectivityStopPollingAndFastPhaseCannotRestartPastItsCap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val server = MockWebServer()
        var online = true
        val noForecast = org.json.JSONObject(weatherFixture).put("forecastFetchedAt", org.json.JSONObject.NULL).toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path == "/api/feed") feedFixture else noForecast)
        }
        server.start()
        val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { online })
        val owner = Owner()
        try {
            feed.refresh(Member("synthetic-member", Role.owner))
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            var polling = backgroundScope.launch { feed.poll(owner.lifecycle, dispatcher) }
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(2, server.requestCount)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertEquals(4, server.requestCount)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals("Polling continued below STARTED", 4, server.requestCount)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent(); advanceTimeBy(19 * 3_000L); runCurrent()
            assertEquals(42, server.requestCount)
            advanceTimeBy(29_999); runCurrent()
            assertEquals("Fast phase exceeded 20 attempts", 42, server.requestCount)
            advanceTimeBy(1); runCurrent()
            assertEquals(44, server.requestCount)
            polling.cancel(); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals("Hidden Home kept polling", 44, server.requestCount)
            polling = backgroundScope.launch { feed.poll(owner.lifecycle, dispatcher) }
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertEquals("Returning from Upcoming reset the fast cap", 44, server.requestCount)
            online = false; feed.connectivityChanged(false)
            runCurrent(); advanceTimeBy(90_000); runCurrent()
            assertEquals("Offline polling did not stop", 44, server.requestCount)
            online = true; feed.connectivityChanged(true)
            runCurrent(); advanceTimeBy(30_000); runCurrent()
            assertEquals(46, server.requestCount)
            polling.cancel()
        } finally { server.shutdown(); Dispatchers.resetMain() }
    }
    @Test fun serverFailureBacksOffWithoutStoppingPolling() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val server = MockWebServer(); server.start()
        val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true })
        val owner = Owner()
        try {
            server.enqueue(MockResponse().setBody(feedFixture))
            server.enqueue(MockResponse().setBody(org.json.JSONObject(weatherFixture).put("forecastFetchedAt", org.json.JSONObject.NULL).toString()))
            feed.refresh(Member("synthetic-member", Role.owner))
            server.enqueue(MockResponse().setResponseCode(503))
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val polling = backgroundScope.launch { feed.poll(owner.lifecycle, dispatcher) }
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertTrue(feed.state.value.unavailable); assertFalse(feed.state.value.offline)
            advanceTimeBy(29_999); runCurrent()
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
            advanceTimeBy(1); runCurrent()
            assertEquals(5, server.requestCount)
            assertFalse(feed.state.value.unavailable)
            polling.cancel()
        } finally { server.shutdown(); Dispatchers.resetMain() }
    }
}
