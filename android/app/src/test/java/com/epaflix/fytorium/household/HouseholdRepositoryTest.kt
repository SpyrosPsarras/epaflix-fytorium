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
    @Test fun customTaskTargetsAreImmutableAndValidationComesFromTheServer() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repo = CustomTaskRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true })
            fun reads(tasks: String = "[]") {
                server.enqueue(MockResponse().setBody(tasks))
                server.enqueue(MockResponse().setBody("""[{"id":"site-one","name":"Synthetic site","plant_count":2}]"""))
            }
            // W5b SV2: one household-wide list, with each target named.
            reads("""[{"id":"task-one","name":"Inspect","interval_days":14,"plant_id":null,"site_id":"site-one","target_name":"Synthetic site","target_site_name":null,"target_status":null,"target_plant_count":2}]""")
            repo.refresh(); assertTrue(repo.state.value.loaded)
            assertEquals("/api/custom-tasks", server.takeRequest().path); server.takeRequest()
            assertEquals(2, repo.state.value.tasks.single().targetPlants)
            assertEquals(listOf("Synthetic site"), repo.state.value.sites.map { it.name })
            server.enqueue(MockResponse().setBody("{}")); reads()
            assertTrue(repo.save(null, TaskFor("site", "site-one", "Synthetic site"), "Wipe", 14))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("site-one", body.getString("site_id")); assertEquals(14, body.getInt("interval_days"))
            assertFalse(body.has("plant_id")); repeat(2) { server.takeRequest() }
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":{"name":"Enter a name from 1 to 60 characters."}}"""))
            assertFalse(repo.save("task-one", TaskFor("site", "site-one", "Synthetic site"), "Inspect", 7))
            assertEquals("Enter a name from 1 to 60 characters.", repo.state.value.errors["name"])
            // A saved task's target never changes, so an edit sends no target.
            val edit = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(edit.has("site_id")); assertFalse(edit.has("plant_id")); assertEquals(7, edit.getInt("interval_days"))
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
            server.enqueue(MockResponse().setBody("""{"id":"invite-one","role":"sitter","expires_at":"2030-06-08T10:00:00Z","link":"/invite/synthetic-private-token"}""")); reads()
            val created = repo.createInvite("sitter", setOf("plant-one"))
            assertEquals("/invite/synthetic-private-token", created?.link)
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
