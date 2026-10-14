package com.epaflix.fytorium.household

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HouseholdRepositoryTest {
    @Test fun settingsUseWebBodiesAndFamilyCannotWriteHouseholdValues() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            var role = Role.owner
            val repo = HouseholdRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, HouseholdArea.Settings, { role }, { true })
            fun reads() {
                server.enqueue(MockResponse().setBody("""{"misting_enabled":false}"""))
                server.enqueue(MockResponse().setBody("""{"location":{"latitude":12.5,"longitude":45,"label":"Synthetic place"}}"""))
                server.enqueue(MockResponse().setBody("""{"late_tasks":true,"current_weather":false,"weather_alerts":true,"plant_warnings":true}"""))
            }
            reads(); repo.refresh(); repeat(3) { server.takeRequest() }
            assertFalse(repo.state.value.misting)
            assertEquals("12.5", repo.state.value.location["latitude"])
            server.enqueue(MockResponse().setBody("{}")); reads()
            assertTrue(repo.saveLocation(mapOf("latitude" to "", "longitude" to "", "label" to "")))
            assertTrue(JSONObject(server.takeRequest().body.readUtf8()).isNull("location"))
            repeat(3) { server.takeRequest() }
            role = Role.family
            assertFalse(repo.saveMisting(true))
            assertFalse(repo.saveLocation(emptyMap()))
            server.enqueue(MockResponse().setBody("{}")); reads()
            assertTrue(repo.saveFeed(repo.state.value.feed))
            assertEquals("/api/feed/settings", server.takeRequest().path)
            role = Role.sitter
            repo.refresh()
            assertFalse(repo.state.value.loaded)
        }
    }
    @Test fun customTaskTargetsAreImmutableAndValidationComesFromTheServer() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repo = HouseholdRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, HouseholdArea.Tasks, { Role.family }, { true })
            server.enqueue(MockResponse().setBody("""[{"id":"plant-one","display_name":"Synthetic fern","status":"active"}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":"graveyard-one","display_name":"Synthetic retired fern","status":"graveyard"}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":"site-one","name":"Synthetic site"}]"""))
            repo.refresh(); assertTrue(repo.state.value.loaded); repeat(3) { server.takeRequest() }
            assertEquals(listOf("plant-one", "graveyard-one"), repo.state.value.plants.map { it.id })
            val target = repo.state.value.sites.single()
            server.enqueue(MockResponse().setBody("[]")); repo.selectTarget(target); server.takeRequest()
            server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("""[{"id":"task-one","name":"Inspect","interval_days":14}]"""))
            assertTrue(repo.saveTask(CustomTask(name = "Inspect", interval = "14")))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("site-one", body.getString("site_id")); assertEquals(14, body.getInt("interval_days"))
            assertFalse(body.has("plant_id")); server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":{"interval_days":"Enter whole days from 1 to 365."}}"""))
            assertFalse(repo.saveTask(repo.state.value.tasks.single().copy(interval = "1.5")))
            assertEquals("Enter whole days from 1 to 365.", repo.state.value.errors["interval_days"])
            val edit = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(edit.has("site_id")); assertEquals("1.5", edit.getString("interval_days"))
        }
    }
    @Test fun membersUseServerCapabilitiesAndInviteTokensStayOutOfState() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            var connected = true
            val repo = HouseholdRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, HouseholdArea.Members, { Role.owner }, { connected })
            fun reads() {
                server.enqueue(MockResponse().setBody("""[{"id":"plant-one","display_name":"Synthetic fern","status":"active"}]"""))
                server.enqueue(MockResponse().setBody("""[{"id":"member-one","display_name":"Synthetic sitter","role":"sitter","plant_ids":["plant-one"],"role_options":["family","sitter"],"can_remove":true}]"""))
                server.enqueue(MockResponse().setBody("[]"))
            }
            reads(); repo.refresh()
            repeat(3) { server.takeRequest() }
            assertEquals(listOf("family", "sitter"), repo.state.value.members.single().roleOptions)
            server.enqueue(MockResponse().setBody("""{"link":"/invite/synthetic-private-token"}""")); reads()
            val link = repo.createInvite("sitter", setOf("plant-one"))
            assertEquals("/invite/synthetic-private-token", link)
            assertFalse(repo.state.value.toString().contains("synthetic-private-token"))
            val request = server.takeRequest()
            assertEquals("/api/household/invites", request.path)
            assertEquals("sitter", JSONObject(request.body.readUtf8()).getString("role"))
            repeat(3) { server.takeRequest() }
            connected = false
            assertFalse(repo.removeMember("member-one"))
            assertEquals(7, server.requestCount)
            connected = true
            assertFalse(repo.removeMember("member-one"))
            reads(); repo.refresh()
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"The last owner cannot be removed"}"""))
            assertFalse(repo.removeMember("member-one"))
            assertEquals("The last owner cannot be removed", repo.state.value.errors["_form"])
        }
    }
}
