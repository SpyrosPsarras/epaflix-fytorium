package com.epaflix.fytorium.sites

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val siteFixture = """{"id":"site-one","name":"Synthetic room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":true,"draft":false,"warm_min_c":18,"warm_max_c":25,"cold_min_c":null,"cold_max_c":20,"plant_count":2,"imported_conditions_incomplete":true}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SiteRepositoryTest {
    private fun withSites(test: (SiteRepository, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = SiteRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true })
            server.enqueue(MockResponse().setBody("[$siteFixture]")); repository.refresh(); server.takeRequest()
            test(repository, server)
        }
    }
    @Test fun choicesMatchTheWebEnumsExactly() {
        val source = java.io.File("../../src/lib/sites.ts").readText()
        for ((field, export) in mapOf("location" to "locations", "light" to "lightLevels", "humidity" to "humidityLevels")) {
            val array = Regex("export const $export = \\[([^]]+)] as const;").find(source)
                ?: error("Missing web enum $export")
            val values = Regex("'([^']+)'").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList()
            assertEquals(field, values, siteChoices.getValue(field).second)
        }
    }
    @Test fun failedWritesWithoutServerMessagesUseTheWebFallbacks() = withSites { repository, server ->
        server.enqueue(MockResponse().setResponseCode(422).setBody("{}"))
        assertFalse(repository.save(Site()))
        assertEquals("Could not save the site. Try again.", repository.state.value.errors["_form"])
        server.enqueue(MockResponse().setResponseCode(409).setBody("not json"))
        assertFalse(repository.delete("site-one"))
        assertEquals("Could not delete the site. Try again.", repository.state.value.errors["_form"])
    }
    @Test fun writesWithoutAResponseUseTheNetworkFallback() {
        for (delete in listOf(false, true)) withSites { repository, server ->
            server.shutdown()
            assertFalse(if (delete) repository.delete("site-one") else repository.save(Site()))
            assertEquals("Could not reach the server. Try again.", repository.state.value.errors["_form"])
            assertTrue(repository.state.value.blocked)
        }
    }
    @Test fun createEditAndDeleteSendAllFieldsAndReloadServerCounts() = withSites { repository, server ->
        val original = repository.state.value.sites.single()
        for (id in listOf(null, "site-one")) {
            server.enqueue(MockResponse().setBody(siteFixture))
            server.enqueue(MockResponse().setBody("[$siteFixture]"))
            assertTrue(repository.save(original.copy(id = id, draft = true)))
            val request = server.takeRequest()
            assertEquals(if (id == null) "POST" else "PUT", request.method)
            assertEquals(if (id == null) "/api/sites" else "/api/sites/site-one", request.path)
            val body = org.json.JSONObject(request.body.readUtf8())
            assertEquals(10, body.length())
            assertEquals("Synthetic room", body.getString("name"))
            assertEquals("indoor", body.getString("location"))
            assertEquals("part_sun", body.getString("light"))
            assertEquals("normal", body.getString("humidity"))
            assertTrue(body.getBoolean("grow_light")); assertTrue(body.getBoolean("draft"))
            assertEquals(18, body.getInt("warm_min_c")); assertEquals(25, body.getInt("warm_max_c"))
            assertTrue(body.isNull("cold_min_c")); assertEquals(20, body.getInt("cold_max_c"))
            assertEquals("/api/sites", server.takeRequest().path)
        }
        server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setBody("[]"))
        assertTrue(repository.delete("site-one"))
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals("/api/sites", server.takeRequest().path)
        assertTrue(repository.state.value.sites.isEmpty())
    }
    @Test fun serverValidationAndDeleteRefusalAreKeptVerbatimWithoutLosingTheSite() = withSites { repository, server ->
        val site = repository.state.value.sites.single()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":{"name":"Enter a site name.","warm_min_c":"Minimum must not exceed maximum.","cold_min_c":"Enter a whole number of °C or leave blank."}}"""))
        assertFalse(repository.save(site.copy(fields = site.fields + mapOf("name" to "", "warm_min_c" to "26", "cold_min_c" to "not-a-number"))))
        assertEquals("Enter a site name.", repository.state.value.errors["name"])
        assertEquals("Minimum must not exceed maximum.", repository.state.value.errors["warm_min_c"])
        assertEquals("Enter a whole number of °C or leave blank.", repository.state.value.errors["cold_min_c"])
        assertEquals("not-a-number", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("cold_min_c"))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Move or remove the 2 plants on this site first."}"""))
        assertFalse(repository.delete("site-one"))
        assertEquals("Move or remove the 2 plants on this site first.", repository.state.value.errors["_form"])
        assertEquals(listOf(site), repository.state.value.sites)
        assertEquals(3, server.requestCount)
    }
    @Test fun sitterAndOfflineActionsNeverSendRequestsOrQueueChanges() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            var allowed = true
            var connected = true
            val repository = SiteRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { allowed }, { connected })
            server.enqueue(MockResponse().setBody("[$siteFixture]")); repository.refresh()
            connected = false
            assertFalse(repository.save(Site()))
            assertFalse(repository.delete("site-one"))
            connected = true
            assertFalse(repository.save(Site()))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("[$siteFixture]")); repository.refresh()
            assertEquals(2, server.requestCount)
            allowed = false
            repository.refresh()
            assertTrue(repository.state.value.sites.isEmpty())
            assertFalse(repository.save(Site())); assertFalse(repository.delete("site-one"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun failedReadsBlockWritesUntilAnExplicitSuccessfulRefresh() = withSites { repository, server ->
        for (status in listOf(503, 426)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}")); repository.refresh()
            assertFalse(repository.save(Site()))
            assertFalse(repository.delete("site-one"))
            assertEquals(1, repository.state.value.sites.size)
            assertTrue(repository.state.value.blocked)
            if (status == 426) assertTrue(repository.state.value.updateRequired)
            else {
                server.enqueue(MockResponse().setBody("[$siteFixture]")); repository.refresh()
                assertFalse(repository.state.value.blocked)
            }
        }
    }
    @Test fun listReadsServerCountsAndEveryCondition() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = SiteRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true })
            server.enqueue(MockResponse().setBody("[$siteFixture]"))
            repository.refresh()
            val site = repository.state.value.sites.single()
            assertEquals(2, site.plantCount)
            assertTrue(site.importedIncomplete)
            assertEquals("18", site.fields.getValue("warm_min_c"))
            assertEquals("", site.fields.getValue("cold_min_c"))
            assertTrue(site.growLight)
            val request = server.takeRequest()
            assertEquals("/api/sites", request.path)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
        }
    }
}
