package com.epaflix.fytorium.feed

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRepositoryTest {
    private fun withFeed(test: (FeedRepository, MockWebServer, (Boolean) -> Unit) -> Unit) {
        val server = MockWebServer(); server.start()
        var online = true
        val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, { online })
        try { test(feed, server) { online = it } } finally { server.shutdown() }
    }
    private fun load(feed: FeedRepository, server: MockWebServer, role: Role = Role.owner) {
        server.enqueue(MockResponse().setBody(feedFixture))
        if (role != Role.sitter) server.enqueue(MockResponse().setBody(weatherFixture))
        feed.refresh(Member("synthetic-member", role))
    }
    @Test fun backgroundReadKeepsWritesEnabled() = withFeed { feed, server, _ ->
        load(feed, server)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path == "/api/feed") { started.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                return MockResponse().setBody(if (request.path == "/api/feed") feedFixture else weatherFixture)
            }
        }
        val reader = Thread { feed.refresh(feed.state.value.member) }.apply { start() }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(feed.state.value.refreshing); assertFalse(feed.state.value.saving)
            assertTrue("A background read disabled writes", feed.state.value.writable)
        } finally { release.countDown(); reader.join(5_000) }
    }
    @Test fun unknownKindsDoNotDiscardKnownCardsAndWarnings() = withFeed { feed, server, _ ->
        val json = org.json.JSONObject(feedFixture)
        json.getJSONArray("cards").put(org.json.JSONObject("""{"kind":"future_card"}"""))
        json.getJSONArray("cards").getJSONObject(4).getJSONArray("warnings").put(org.json.JSONObject("""{"kind":"future_warning"}"""))
        server.enqueue(MockResponse().setBody(json.toString())); server.enqueue(MockResponse().setBody(weatherFixture))
        feed.refresh(Member("synthetic-member", Role.owner))
        assertEquals(5, feed.state.value.cards.size)
        assertEquals(listOf("Too dark"), feed.state.value.cards.last().warnings.map { it.title })
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Fytorium").any { it.msg == "FEED_UNKNOWN_KIND" })
    }
    @Test fun validatedNetworkFailuresAreServerErrorsAndKeepLoadedCards() = withFeed { feed, server, online ->
        load(feed, server)
        for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("not json"),
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))) {
            server.enqueue(response); feed.refresh(feed.state.value.member)
            assertFalse(feed.state.value.offline)
            assertTrue(feed.state.value.unavailable)
            assertEquals(5, feed.state.value.cards.size)
            assertTrue(feed.state.value.writable)
        }
        val before = server.requestCount
        online(false); feed.refresh(feed.state.value.member)
        assertTrue(feed.state.value.offline); assertFalse(feed.state.value.unavailable)
        assertEquals(before, server.requestCount)
    }
    @Test fun unknownWeatherEventIsSkippedAndRainNumbersMatchSvelte() {
        assertEquals("6", rainAmount(6.0)); assertEquals("6.25", rainAmount(6.25))
        assertEquals("0", rainAmount(0.0)); assertEquals("0.001", rainAmount(0.001))
        val rain = org.json.JSONObject(feedFixture).getJSONArray("cards").getJSONObject(3)
        assertEquals("Rain: 6 mm on 2030-06-01.", parseFeedCard(rain)!!.lines.single())
        rain.getJSONObject("event").put("kind", "future_event")
        assertNull(parseFeedCard(rain))
    }
    @Test fun malformedBatchIdShowsErrorWithoutRequestOrCrash() = withFeed { feed, server, _ ->
        server.enqueue(MockResponse().setBody(feedFixture))
        server.enqueue(MockResponse().setBody(org.json.JSONObject(weatherFixture).put("lastRainBatch", org.json.JSONObject().put("id", "../bad")).toString()))
        feed.refresh(Member("synthetic-member", Role.owner))
        assertFalse(feed.undoRain())
        assertEquals("Rain watering could not be undone. Refresh to try again.", feed.state.value.message)
        assertEquals(2, server.requestCount)
    }
    @Test fun rainRetryKeepsRequestIdAndOnlyNotReadyReasonOffersMomentRetry() = withFeed { feed, server, _ ->
        load(feed, server)
        server.takeRequest(); server.takeRequest()
        for ((code, reason) in listOf(409 to "rain_report_not_ready", 409 to "no_outdoor_plants", 422 to "rain_report_not_ready")) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"reason":"$reason","message":"Synthetic refusal"}"""))
            assertFalse(feed.water())
            assertEquals(code == 409 && reason == "rain_report_not_ready", feed.state.value.message!!.contains("Try again in a moment"))
        }
        val ids = (1..3).map { org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("request_id") }
        assertEquals(1, ids.toSet().size)
        server.enqueue(MockResponse().setBody("""{"plantCount":1,"batchId":"batch-one"}"""))
        server.enqueue(MockResponse().setBody(feedFixture))
        server.enqueue(MockResponse().setBody(org.json.JSONObject(weatherFixture).put("rain", org.json.JSONObject("""{"plantCount":1,"precipitationMm":6,"batchId":"batch-one"}""")).put("lastRainBatch", org.json.JSONObject("""{"id":"batch-one","plantCount":1}""")).toString()))
        assertTrue(feed.water())
        assertEquals("Marked 1 outdoor plants watered.", feed.state.value.message)
        assertEquals("batch-one", feed.state.value.weather!!.lastBatchId)
        repeat(3) { server.takeRequest() }
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
        assertTrue(feed.undoRain())
        assertEquals("/api/task-batches/batch-one/undo", server.takeRequest().path)
        assertEquals("Rain watering undone.", feed.state.value.message)
        assertNull(feed.state.value.weather!!.lastBatchId)
    }
    @Test fun offlineFeedIsReadOnlyAndSettingsDoNotQueue() = withFeed { feed, server, online ->
        load(feed, server)
        online(false); feed.connectivityChanged(false)
        assertFalse(feed.water()); assertFalse(feed.saveSettings(feed.state.value.settings!!))
        assertFalse(feed.saveLocation(null)); assertFalse(feed.undoRain())
        assertEquals(2, server.requestCount)
        assertEquals(5, feed.state.value.cards.size)
        feed.wipe(); assertTrue(feed.state.value.cards.isEmpty()); assertNull(feed.state.value.settings)
    }
    @Test fun feedSettingsPersistAndFamilyCanReadButNotWriteLocation() = withFeed { feed, server, _ ->
        load(feed, server)
        server.takeRequest(); server.takeRequest()
        val settings = feed.state.value.settings!! + ("plant_warnings" to false)
        server.enqueue(MockResponse().setBody(settingsFixture))
        server.enqueue(MockResponse().setBody(org.json.JSONObject(feedFixture).put("settings", org.json.JSONObject(settings)).toString()))
        server.enqueue(MockResponse().setBody(weatherFixture))
        assertTrue(feed.saveSettings(settings))
        val save = server.takeRequest()
        assertEquals("PUT", save.method); assertEquals("/api/feed/settings", save.path)
        assertFalse(org.json.JSONObject(save.body.readUtf8()).getBoolean("plant_warnings"))
        assertFalse(feed.state.value.settings!!.getValue("plant_warnings"))
        repeat(2) { server.takeRequest() }
        load(feed, server, Role.family)
        server.enqueue(MockResponse().setBody("""{"location":{"latitude":0.5,"longitude":0.5,"label":"Synthetic location"}}"""))
        feed.loadLocation()
        assertEquals("Synthetic location", feed.state.value.location!!.label)
        val before = server.requestCount
        assertFalse(feed.saveLocation(null))
        assertEquals(before, server.requestCount)
        load(feed, server, Role.sitter)
        val sitterBefore = server.requestCount
        feed.loadLocation(); assertFalse(feed.saveSettings(settings)); assertFalse(feed.water())
        assertFalse(feed.state.value.locationLoaded)
        assertEquals(sitterBefore, server.requestCount)
    }
    @Test fun ownerCanSaveAndRemoveLocationAndValidationStaysOnServer() = withFeed { feed, server, _ ->
        load(feed, server)
        server.takeRequest(); server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"location":{"latitude":0.5,"longitude":0.5,"label":"Synthetic location"}}"""))
        server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
        assertTrue(feed.saveLocation(WeatherLocation(0.5, 0.5, "Synthetic location")))
        assertEquals(0.5, org.json.JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("location").getDouble("latitude"), 0.0)
        repeat(2) { server.takeRequest() }
        server.enqueue(MockResponse().setBody("""{"location":null}"""))
        server.enqueue(MockResponse().setBody(feedFixture)); server.enqueue(MockResponse().setBody(weatherFixture))
        assertTrue(feed.saveLocation(null))
        assertTrue(org.json.JSONObject(server.takeRequest().body.readUtf8()).isNull("location"))
        repeat(2) { server.takeRequest() }
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Latitude must be between -90 and 90."}"""))
        assertFalse(feed.saveLocation(WeatherLocation(100.0, 0.5, null)))
        assertEquals("Latitude must be between -90 and 90.", feed.state.value.message)
        assertFalse(feed.state.value.unavailable)
    }
    @Test fun logoutDiscardsAnInFlightFeedResponse() = withFeed { feed, server, _ ->
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path == "/api/feed") { started.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                return MockResponse().setBody(if (request.path == "/api/feed") feedFixture else weatherFixture)
            }
        }
        val reader = Thread { feed.refresh(Member("synthetic-owner", Role.owner)) }.apply { start() }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            feed.wipe(); release.countDown(); reader.join(5_000)
            assertFalse(reader.isAlive)
            assertNull(feed.state.value.member); assertNull(feed.state.value.weather)
            assertTrue(feed.state.value.cards.isEmpty())
        } finally { release.countDown(); reader.join(5_000) }
    }
    @Test fun feedUsesServerOrderAndSittersNeverFetchWeather() {
        val server = MockWebServer(); server.start()
        try {
            val credentials = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val feed = FeedRepository(ApiClient(server.url("").toString().trimEnd('/'), credentials) {}, { true })
            server.enqueue(MockResponse().setBody(feedFixture))
            server.enqueue(MockResponse().setBody(weatherFixture))
            feed.refresh(Member("synthetic-owner", Role.owner))
            assertEquals(listOf("late", "current", "cold", "rain", "warnings"), feed.state.value.cards.map { it.id })
            assertEquals("Synthetic outdoor fern", feed.state.value.cards[2].title)
            assertTrue(feed.state.value.cards[2].lines.first().contains("below 6"))
            assertEquals("/api/feed", server.takeRequest().path)
            assertEquals("/api/weather", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"cards":[{"id":"late","kind":"late_tasks","href":"/#today","count":2,"plants":[]}]}"""))
            feed.refresh(Member("synthetic-sitter", Role.sitter))
            assertEquals(listOf("late"), feed.state.value.cards.map { it.id })
            assertNull(feed.state.value.weather)
            assertNull(feed.state.value.settings)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}

internal const val settingsFixture = """{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true}"""
internal const val feedFixture = """{"settings":$settingsFixture,"cards":[
 {"id":"late","kind":"late_tasks","href":"/#today","count":2,"plants":[{"id":"fern","name":"Synthetic outdoor fern"}]},
 {"id":"current","kind":"current_weather","href":"/settings#weather-location-title","unavailableSince":null,"current":{"minC":0,"maxC":30,"fetchedAt":"2030-06-01T10:00:00Z"}},
 {"id":"cold","kind":"weather_alerts","href":"/plants/fern","event":{"kind":"low_temperature","plant_name":"Synthetic outdoor fern","starts_on":"2030-06-01","ends_on":"2030-06-07","payload":{"minC":0,"thresholdC":6,"hardiness":{"source":"override","value":6,"ruleId":null},"potRuleId":"hardiness.pot_offset@1"}}},
 {"id":"rain","kind":"weather_alerts","href":"/settings#weather-location-title","event":{"kind":"rain","starts_on":"2030-06-01","payload":{"precipitationMm":6}}},
 {"id":"warnings","kind":"plant_warnings","href":"/plants/fern","plantName":"Synthetic outdoor fern","warnings":[{"kind":"too_dark","reason":"The site is too dark for this plant.","inputs":[{"input":"effectiveLight","value":"part_shade","source":"rule","ruleId":"warning.effective_light@1"}]}]}
]}"""
internal const val weatherFixture = """{"locationSet":true,"forecastFetchedAt":"2030-06-01T10:00:00Z","rain":{"plantCount":1,"precipitationMm":6,"batchId":null},"lastRainBatch":null}"""
