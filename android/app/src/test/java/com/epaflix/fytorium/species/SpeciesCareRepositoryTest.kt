package com.epaflix.fytorium.species

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal val careFixture = """{"species":{"gbif_key":910002,"scientific_name":"Testoria nebulosa Inventor"},"profile":{"wateringIntervalDays":{"value":14,"source":"rules","ruleId":"water.base@1","fetchedAt":null},"light":{"value":"part_shade","source":"open_plantbook","fetchedAt":"2030-06-01T12:00:00Z"}},"fetches":[{"source":"open_plantbook","status":"pending","matchStatus":"no_match"}]}"""
internal val outdoorFixture = """{"locationSet":false}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeciesCareRepositoryTest {
    private fun withCare(test: (SpeciesCareRepository, MockWebServer, (Boolean, Boolean) -> Unit) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            var allowed = true; var connected = true
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val repo = SpeciesCareRepository(api, 910002, { allowed }, { connected })
            server.enqueue(MockResponse().setBody(careFixture)); server.enqueue(MockResponse().setBody(outdoorFixture))
            assertTrue(repo.refresh())
            assertEquals("/api/species/910002/care", server.takeRequest().path)
            assertEquals("/api/species/910002/outdoor-months", server.takeRequest().path)
            test(repo, server) { a, c -> allowed = a; connected = c }
        }
    }
    @Test fun overrideAndResetReloadEffectiveValuesAndKeepProvenance() = withCare { repo, server, _ ->
        assertEquals("rules", repo.state.value.card!!.profile.getJSONObject("wateringIntervalDays").getString("source"))
        val overridden = JSONObject(careFixture).apply {
            getJSONObject("profile").put("wateringIntervalDays", JSONObject("""{"value":9,"source":"override","fetchedAt":"2030-06-02T12:00:00Z"}"""))
        }.toString()
        server.enqueue(MockResponse().setBody(overridden)); server.enqueue(MockResponse().setBody(overridden)); server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.override("wateringIntervalDays", 9))
        val put = server.takeRequest()
        assertEquals("PUT", put.method); assertEquals("/api/species/910002/care/overrides/wateringIntervalDays", put.path)
        assertEquals(9, JSONObject(put.body.readUtf8()).getInt("value"))
        assertEquals("override", repo.state.value.card!!.profile.getJSONObject("wateringIntervalDays").getString("source"))
        server.takeRequest(); server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setBody(careFixture)); server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.reset("wateringIntervalDays"))
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals("rules", repo.state.value.card!!.profile.getJSONObject("wateringIntervalDays").getString("source"))
    }
    @Test fun backgroundReadsDoNotDisableWritesAndFailedWritesKeepData() = withCare { repo, server, _ ->
        server.enqueue(MockResponse().setBody(careFixture).setBodyDelay(1, TimeUnit.SECONDS)); server.enqueue(MockResponse().setBody(outdoorFixture))
        val read = thread { repo.refresh() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(repo.state.value.loading)
        assertTrue(repo.state.value.writable)
        read.join(5_000); assertFalse(read.isAlive)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Invalid value for wateringIntervalDays"}"""))
        assertFalse(repo.override("wateringIntervalDays", -1))
        assertEquals("Invalid value for wateringIntervalDays", repo.state.value.error)
        assertNotNull(repo.state.value.card)
        assertTrue(repo.state.value.writable)
    }
    @Test fun offlineSitterAndLostSessionDoNotWriteOrQueueAndRecoveryIsExplicit() = withCare { repo, server, gates ->
        gates(true, false)
        assertFalse(repo.override("light", "full_sun"))
        gates(true, true)
        assertFalse(repo.reset("light"))
        server.enqueue(MockResponse().setBody(careFixture)); server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.refresh()); assertTrue(repo.state.value.writable)
        gates(false, true)
        assertFalse(repo.refresh()); assertFalse(repo.override("light", "full_sun"))
        assertNull(repo.state.value.card)
        assertEquals(4, server.requestCount)
    }
    @Test fun refreshAndRetryPreserveOverridesAndUnmatchedUsesTheServerStatus() = withCare { repo, server, _ ->
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody(careFixture)); server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.refreshCare())
        assertEquals("/api/species/910002/care/refresh", server.takeRequest().path)
        assertEquals("Refresh queued. Existing overrides are kept.", repo.state.value.message)
        server.takeRequest(); server.takeRequest()
        val unmatched = SpeciesCareRepository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
            tokens = Tokens("synthetic-access", "synthetic-refresh")
        }) {}, null, { true }, { true })
        server.enqueue(MockResponse().setBody("""[{"gbif_key":910002,"canonical_name":"Testoria nebulosa","status":"ambiguous"}]"""))
        assertTrue(unmatched.refresh())
        assertEquals("/api/species/unmatched", server.takeRequest().path)
        assertEquals(listOf(UnmatchedSpecies(910002, "Testoria nebulosa", "ambiguous")), unmatched.state.value.unmatched)
        server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("[]"))
        assertTrue(unmatched.retryMatch(910002))
        val retry = server.takeRequest()
        assertEquals("POST", retry.method); assertEquals("/api/species/910002/care/retry-match", retry.path)
        assertTrue(unmatched.state.value.unmatched.isEmpty())
    }
    @Test fun retryFailureUsesWebFallbackAndStopsPolling() = withCare { repo, server, _ ->
        assertTrue(repo.state.value.pendingFetch)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        assertFalse(repo.retryMatch())
        assertEquals("Could not retry. An owner and configured credentials are required.", repo.state.value.error)
        assertFalse(repo.state.value.pendingFetch)
    }
    @Test fun pollingEndsWhenFetchFinishesOrFails() = withCare { repo, server, _ ->
        assertTrue(repo.state.value.pendingFetch)
        server.enqueue(MockResponse().setBody(careFixture.replace("pending", "fetching")))
        server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.refresh()); assertTrue(repo.state.value.pendingFetch)
        server.enqueue(MockResponse().setBody(careFixture.replace("pending", "done")))
        server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.refresh()); assertFalse(repo.state.value.pendingFetch)
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(repo.refresh()); assertFalse(repo.state.value.pendingFetch)
    }
    @Test fun failedReadsRetainTheCardUntilSuccessfulRefreshAnd426StaysGated() = withCare { repo, server, _ ->
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}")); assertFalse(repo.refresh())
        assertNotNull(repo.state.value.card); assertFalse(repo.state.value.writable)
        server.enqueue(MockResponse().setBody(careFixture)); server.enqueue(MockResponse().setBody(outdoorFixture))
        assertTrue(repo.refresh()); assertTrue(repo.state.value.writable)
        server.enqueue(MockResponse().setResponseCode(426).setBody("""{"message":"Update Fytorium"}""")); assertFalse(repo.refresh())
        assertTrue(repo.state.value.updateRequired); assertFalse(repo.state.value.writable)
    }
}
