package com.epaflix.fytorium.planta

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val importCandidate = """{"gbif_key":910002,"scientific_name":"Testoria nebulosa Inventor","canonical_name":"Testoria nebulosa","rank":"SPECIES","match_type":"FUZZY","confidence":96,"requires_confirmation":true,"matched_name":null,"matched_language":null}"""
internal const val importFixture = """{"id":"staging-one","planta_plant_id":"synthetic-planta","mapped":{"nickname":"Synthetic imported fern","cultivar":"Invented stripe","scientific_name":"Testoria neblosa","site_name":"Synthetic nook","pot_type":"terracotta","pot_size_cm":18,"soil_type":"cactus","care":{"water":"2026-06-01"},"has_cover":true},"cover_error":null,"cover_status":"pending","candidates":[$importCandidate],"selected":null,"status":"review","plant_id":null,"can_commit":false,"can_review":true}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlantaRepositoryTest {
    private fun repository(server: MockWebServer, allowed: () -> Boolean = { true }, connected: () -> Boolean = { true }) =
        PlantaRepository(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
            tokens = Tokens("synthetic-access", "synthetic-refresh")
        }) {}, allowed, connected)
    @Test fun fetchConfirmSearchSkipAndCommitUseServerEligibility() {
        MockWebServer().use { server ->
            server.start()
            val repo = repository(server)
            server.enqueue(MockResponse().setBody("[]")); repo.refresh(); server.takeRequest()
            server.enqueue(MockResponse().setBody("[$importFixture]"))
            assertTrue(repo.fetch("synthetic-code"))
            val fetch = server.takeRequest()
            assertEquals("POST", fetch.method)
            assertEquals("/api/import/planta", fetch.path)
            assertEquals("synthetic-code", JSONObject(fetch.body.readUtf8()).getString("code"))
            assertFalse(repo.state.value.toString().contains("synthetic-code"))
            assertFalse(repo.commit())
            server.enqueue(MockResponse().setBody("""{"search_id":"search-one","candidates":[$importCandidate]}"""))
            assertTrue(repo.search("staging-one", "Testoria typo"))
            assertEquals("/api/species/search?q=Testoria%20typo", server.takeRequest().path)
            assertTrue(repo.state.value.search!!.candidates.single().requiresConfirmation)
            val ready = JSONObject(importFixture).put("status", "confirmed").put("can_commit", true).put("selected", JSONObject(importCandidate))
            server.enqueue(MockResponse().setBody(ready.toString()))
            server.enqueue(MockResponse().setBody("[$ready]"))
            assertTrue(repo.confirm("staging-one", 910002, "search-one"))
            val confirm = server.takeRequest()
            assertEquals("PUT", confirm.method)
            assertEquals("/api/import/planta/staging-one", confirm.path)
            assertEquals("""{"gbif_key":910002,"confirmed":true,"search_id":"search-one"}""", confirm.body.readUtf8())
            assertEquals("/api/import/planta", server.takeRequest().path)
            assertNull(repo.state.value.search)
            val done = ready.put("status", "committed").put("can_commit", false).put("can_review", false).put("cover_status", "imported").put("plant_id", "plant-one")
            server.enqueue(MockResponse().setBody("[$done]"))
            assertTrue(repo.commit())
            assertEquals("/api/import/planta/commit", server.takeRequest().path)
            assertTrue(repo.state.value.complete)
            assertEquals("imported", repo.state.value.rows.single().coverStatus)
            assertFalse(repo.skip("staging-one"))
            assertFalse(repo.confirm("staging-one", 910002))
            server.enqueue(MockResponse().setBody("[$importFixture]")); assertTrue(repo.fetch("another-code")); server.takeRequest()
            server.enqueue(MockResponse().setBody(JSONObject(importFixture).put("status", "skipped").toString()))
            server.enqueue(MockResponse().setBody("[" + JSONObject(importFixture).put("status", "skipped") + "]"))
            assertTrue(repo.skip("staging-one"))
            assertEquals("""{"skip":true}""", server.takeRequest().body.readUtf8())
            server.takeRequest()
            assertEquals("skipped", repo.state.value.rows.single().status)
        }
    }
    @Test fun fetchDisplaysKnownServerErrorsWithoutRelayingArbitraryProviderText() {
        MockWebServer().use { server ->
            server.start()
            val repo = repository(server)
            val messages = listOf("Planta supplied an invalid or future last care date.",
                "Planta is rate-limiting this import. Try again later with a new app code.",
                "Planta refused authorization. Start again with a new app code.")
            for (message in messages) {
                server.enqueue(MockResponse().setBody("[$importFixture]")); assertTrue(repo.refresh())
                server.enqueue(MockResponse().setResponseCode(if (message.contains("rate-limiting")) 503 else 502)
                    .setBody(JSONObject().put("message", message).toString()))
                assertFalse(repo.fetch("synthetic-code"))
                assertEquals(message, repo.state.value.error)
                assertFalse(repo.state.value.blocked)
                server.enqueue(MockResponse().setBody("[$importFixture]"))
                assertTrue(repo.fetch("new-synthetic-code"))
                assertNull(repo.state.value.error)
            }
        }
    }
    @Test fun failedFetchKeepsReviewAndNeverEchoesCodeOrUpstreamTokens() {
        MockWebServer().use { server ->
            server.start()
            val repo = repository(server)
            server.enqueue(MockResponse().setBody("[$importFixture]")); repo.refresh()
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"message":"synthetic-code synthetic-planta-token"}"""))
            assertFalse(repo.fetch("synthetic-code"))
            assertEquals(1, repo.state.value.rows.size)
            assertFalse(repo.state.value.toString().contains("synthetic-code"))
            assertFalse(repo.state.value.toString().contains("synthetic-planta-token"))
            assertFalse(repo.state.value.blocked)
            server.enqueue(MockResponse().setBody("[$importFixture]"))
            assertTrue(repo.fetch("new-synthetic-code"))
            server.takeRequest(); server.takeRequest()
            assertEquals("new-synthetic-code", JSONObject(server.takeRequest().body.readUtf8()).getString("code"))
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun offlineUnauthorizedAndFailedReadsNeverWriteOrQueue() {
        MockWebServer().use { server ->
            server.start()
            var allowed = true
            var connected = true
            val repo = repository(server, { allowed }, { connected })
            server.enqueue(MockResponse().setBody("[$importFixture]")); repo.refresh()
            connected = false
            assertFalse(repo.fetch("synthetic-code")); assertFalse(repo.confirm("staging-one", 910002))
            connected = true
            assertFalse(repo.fetch("synthetic-code"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}")); assertFalse(repo.refresh())
            assertFalse(repo.skip("staging-one"))
            server.enqueue(MockResponse().setBody("[$importFixture]")); assertTrue(repo.refresh())
            allowed = false
            assertFalse(repo.refresh()); assertFalse(repo.fetch("synthetic-code")); assertFalse(repo.skip("staging-one"))
            assertTrue(repo.state.value.rows.isEmpty())
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun lostResponseAndUpdateRequiredPreserveRowsAndBlockWrites() {
        for (status in listOf(426, 503)) MockWebServer().use { server ->
            server.start()
            val repo = repository(server)
            server.enqueue(MockResponse().setBody("[$importFixture]")); repo.refresh()
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}")); assertFalse(repo.refresh())
            assertEquals(status == 426, repo.state.value.updateRequired)
            assertTrue(repo.state.value.blocked)
            if (status == 503) assertTrue(repo.state.value.error!!.startsWith("Fytorium isn't responding."))
            assertEquals(1, repo.state.value.rows.size)
            assertFalse(repo.confirm("staging-one", 910002))
            assertEquals(2, server.requestCount)
        }
        MockWebServer().use { server ->
            server.start()
            val repo = repository(server)
            server.enqueue(MockResponse().setBody("[$importFixture]")); repo.refresh()
            server.shutdown()
            assertFalse(repo.fetch("synthetic-secret-code"))
            assertTrue(repo.state.value.blocked)
            assertTrue(repo.state.value.error!!.startsWith("Fytorium isn't responding."))
            assertFalse(repo.state.value.toString().contains("synthetic-secret-code"))
        }
    }
    @Test fun aNewRepositoryResumesTheServerReviewWithoutACode() {
        MockWebServer().use { server ->
            server.start()
            repeat(2) {
                server.enqueue(MockResponse().setBody("[$importFixture]"))
                val repo = repository(server)
                assertTrue(repo.refresh())
                val row = repo.state.value.rows.single()
                assertEquals("Synthetic imported fern", row.name)
                assertEquals("pending", row.coverStatus)
                assertFalse(row.canCommit)
                assertTrue(row.canReview)
                assertEquals(96, row.candidates.single().confidence)
                assertTrue(row.candidates.single().requiresConfirmation)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/api/import/planta", request.path)
                assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
                assertEquals(0L, request.body.size)
            }
        }
    }
}
