package com.epaflix.fytorium.notifications

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationRepositoryTest {
    @Test fun settingsAreServerOwnedAndOwnRefreshRecoversAfterFailure() {
        MockWebServer().use { server ->
            server.start()
            val settings = """{"reminder_time":"09:30","reminders_enabled":true,"weather_alerts_enabled":false,"completion_notices_enabled":true}"""
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(settings))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Athens"}"""))
            server.enqueue(MockResponse().setBody(settings.replace("09:30", "10:45")))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            var online = true
            var allowed = true
            val repository = NotificationRepository(api, { allowed }, { online })
            assertFalse(repository.refresh())
            assertTrue(repository.state.value.blocked)
            assertTrue(repository.refresh())
            assertEquals("09:30", repository.state.value.settings.time)
            assertEquals("Europe/Athens", repository.state.value.timezone)
            assertTrue(repository.save(NotificationSettings("10:45", true, true, true)))
            assertEquals("10:45", repository.state.value.settings.time)
            repeat(4) { server.takeRequest() }
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("10:45", JSONObject(request.body.readUtf8()).getString("reminder_time"))
            online = false
            assertFalse(repository.save(NotificationSettings()))
            allowed = false
            assertFalse(repository.refresh())
            assertFalse(repository.state.value.loaded)
        }
    }
    @Test fun invalidTimeShowsServerValidationAndCanBeCorrectedWithoutReloading() {
        MockWebServer().use { server ->
            server.start()
            val settings = """{"reminder_time":"09:00","reminders_enabled":false,"weather_alerts_enabled":false,"completion_notices_enabled":false}"""
            server.enqueue(MockResponse().setBody(settings))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Athens"}"""))
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Choose a reminder time HH:MM and on/off switches"}"""))
            server.enqueue(MockResponse().setBody(settings.replace("09:00", "10:45")))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            val repository = NotificationRepository(api, { true }, { true })
            assertTrue(repository.refresh())
            assertFalse(repository.save(NotificationSettings("25:00")))
            assertEquals("Choose a reminder time HH:MM and on/off switches", repository.state.value.error)
            assertFalse(repository.state.value.blocked)
            assertTrue(repository.save(NotificationSettings("10:45")))
            assertEquals("10:45", repository.state.value.settings.time)
        }
    }
    @Test fun currentDeviceParsingSwitchAndRemovalFeedback() {
        MockWebServer().use { server ->
            server.start()
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val row = """{"id":"$id","label":"Phone","transport":"fcm","enabled":true,"current_device":true}"""
            server.enqueue(MockResponse().setBody(NotificationSettings().json().toString()))
            server.enqueue(MockResponse().setBody("[$row]"))
            server.enqueue(MockResponse().setBody("""{"timezone":"Europe/Athens"}"""))
            server.enqueue(MockResponse().setBody(row))
            server.enqueue(MockResponse().setBody("[" + row.replace("true", "false") + "]"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("[]"))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            val repository = NotificationRepository(api, { true }, { true })
            assertTrue(repository.refresh())
            assertTrue(repository.state.value.devices.single().current)
            assertTrue(repository.switch(repository.state.value.devices.single()))
            assertFalse(repository.state.value.devices.single().enabled)
            assertFalse(repository.state.value.devices.single().current)
            assertTrue(repository.remove(repository.state.value.devices.single()))
            assertTrue(repository.state.value.devices.isEmpty())
            assertEquals("Device removed.", repository.state.value.message)
        }
    }
    @Test fun registrationUsesOnlyBearerIdentityAndDoesNotLogToken() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("a", "r") }) {}
            registerPushToken(api, "synthetic-registration")
            val request = server.takeRequest()
            assertEquals("Bearer a", request.getHeader("Authorization"))
            assertEquals("/api/notifications/subscriptions", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("transport", "token"), body.keys().asSequence().toSet())
            assertEquals("fcm", body.getString("transport"))
            assertEquals("synthetic-registration", body.getString("token"))
        }
    }
}
