package com.epaflix.fytorium

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreTest {
    private lateinit var server: MockWebServer
    private lateinit var app: FytoriumApplication

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        app = RuntimeEnvironment.getApplication() as FytoriumApplication
        val store = MemoryCredentials()
        // Replace lazy application dependencies before Activity creation, without production traffic.
        FytoriumApplication::class.java.getDeclaredField("store\$delegate").apply {
            isAccessible = true
            set(app, lazy { store })
        }
        FytoriumApplication::class.java.getDeclaredField("api\$delegate").apply {
            isAccessible = true
            set(app, lazy { ApiClient(server.url("").toString().trimEnd('/'), store) {} })
        }
    }
    @After fun teardown() { app.session.close(); server.shutdown() }

    @Test fun sessionFollowsStoredCredentialsWithoutAnActivity() {
        val session = app.session
        assertFalse(session.state.value.loggedIn)
        app.store.tokens = Tokens("synthetic-access", "synthetic-refresh")
        awaitCondition { session.state.value.loggedIn }
        app.store.clear()
        awaitCondition { !session.state.value.loggedIn }
        assertEquals(0, server.requestCount)
    }

    @Test fun processRestoreDoesNotReplayConsumedRedirect() {
        server.enqueue(MockResponse().setResponseCode(404))
        val diagnostics = Diagnostics(app).apply { clear() }
        val redirect = Uri.parse("$REDIRECT_URI?code=consumed&state=consumed")
        val intent = Intent(app, MainActivity::class.java).setData(redirect)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create(Bundle())
        try {
            assertEquals(redirect, controller.get().intent.data)
            assertEquals("/api/meta", nextRequest().path)
            awaitCondition { !app.session.state.value.busy }
            assertNull(app.store.pending)
            assertFalse(diagnostics.copyText().contains("LOGIN_REJECTED"))
            assertEquals(1, server.requestCount)
        } finally { controller.destroy() }
    }

    @Test fun unsuccessfulMetaChecksCannotClearAnUpdateRequirement() {
        val session = app.session
        server.enqueue(MockResponse().setResponseCode(426))
        session.start()
        assertEquals("/api/meta", nextRequest().path)
        awaitCondition { !session.state.value.busy && session.state.value.updateRequired }
        for (status in listOf(503, 404, 200)) {
            app.api.loginUrl()
            val state = app.store.pending!!.state
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"minAppVersion":"0.0.0"}"""))
            session.handleRedirect("$REDIRECT_URI?code=synthetic-code&state=$state")
            assertEquals("/api/app/token", nextRequest().path)
            assertEquals("/api/meta", nextRequest().path)
            awaitCondition { !session.state.value.busy }
            assertEquals(status != 200, session.state.value.updateRequired)
        }
    }

    @Test fun restoredActivityQueuesNewRedirectDuringVersionCheck() {
        val releaseVersion = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/meta") {
                    check(releaseVersion.await(10, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody("""{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","token_type":"Bearer"}""")
            }
        }
        app.api.loginUrl()
        val state = app.store.pending!!.state
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle())
        try {
            assertEquals("/api/meta", nextRequest().path)
            val redirect = Intent(app, MainActivity::class.java)
                .setData(Uri.parse("$REDIRECT_URI?code=synthetic-code&state=$state"))
            controller.newIntent(redirect)
            releaseVersion.countDown()
            val exchange = nextRequest()
            assertEquals("/api/app/token", exchange.path)
            assertEquals("POST", exchange.method)
            val body = org.json.JSONObject(exchange.body.readUtf8())
            assertEquals("authorization_code", body.getString("grant_type"))
            assertEquals("synthetic-code", body.getString("code"))
            awaitLoggedIn()
            assertNull(app.store.pending)
        } finally {
            releaseVersion.countDown()
            controller.destroy()
        }
    }

    @Test fun queuedRedirectSurvivesActivityRecreation() {
        val releaseVersion = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/meta") {
                    check(releaseVersion.await(10, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(404)
                }
                return tokenResponse()
            }
        }
        app.api.loginUrl()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle()).start().resume().visible()
        try {
            assertEquals("/api/meta", nextRequest().path)
            controller.newIntent(validRedirect())
            val session = app.session
            controller.recreate()
            assertSame(session, app.session)
            releaseVersion.countDown()
            awaitLoggedIn()
            assertNotNull(app.store.tokens)
            assertEquals(1, drainRequests().count { it.path == "/api/app/token" })
        } finally {
            releaseVersion.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun tokenExchangeFinishesAfterActivityRecreation() {
        val releaseExchange = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/app/token") {
                    check(releaseExchange.await(10, TimeUnit.SECONDS))
                    return tokenResponse()
                }
                return MockResponse().setResponseCode(404)
            }
        }
        app.api.loginUrl()
        val controller = Robolectric.buildActivity(MainActivity::class.java, validRedirect()).setup()
        try {
            assertEquals("/api/app/token", nextRequest().path)
            controller.recreate()
            assertNull(app.store.tokens)
            releaseExchange.countDown()
            awaitLoggedIn()
            assertNotNull(app.store.tokens)
            assertEquals(0, drainRequests().count { it.path == "/api/app/token" })
        } finally {
            releaseExchange.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun duplicateRedirectAfterLoginIsIgnored() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/api/app/token") tokenResponse() else MockResponse().setResponseCode(404)
        }
        val diagnostics = Diagnostics(app).apply { clear() }
        app.api.loginUrl()
        val redirect = validRedirect()
        val duplicate = Intent(redirect)
        val controller = Robolectric.buildActivity(MainActivity::class.java, redirect).setup()
        try {
            assertEquals("/api/app/token", nextRequest().path)
            assertEquals("/api/meta", nextRequest().path)
            awaitLoggedIn()
            controller.newIntent(duplicate)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(app.session.state.value.loggedIn)
            assertNull(app.session.state.value.message)
            assertFalse(diagnostics.copyText().contains("LOGIN_REJECTED"))
            assertTrue(ShadowLog.getLogsForTag("Fytorium").any {
                it.type == android.util.Log.DEBUG && it.msg == "REDIRECT_IGNORED"
            })
            assertEquals(2, server.requestCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun staleRedirectDoesNotConsumeTheValidPendingLogin() {
        val releaseVersion = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/meta") {
                    check(releaseVersion.await(10, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(404)
                }
                return tokenResponse()
            }
        }
        app.api.loginUrl()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle())
        try {
            assertEquals("/api/meta", nextRequest().path)
            controller.newIntent(Intent(app, MainActivity::class.java).setData(Uri.parse("$REDIRECT_URI?code=stale&state=stale")))
            controller.newIntent(validRedirect())
            releaseVersion.countDown()
            awaitLoggedIn()
            assertNull(app.session.state.value.message)
            assertNotNull(app.store.tokens)
            assertEquals(1, drainRequests().count { it.path == "/api/app/token" })
        } finally {
            releaseVersion.countDown()
            controller.destroy()
        }
    }

    private fun validRedirect() = Intent(app, MainActivity::class.java)
        .setData(Uri.parse("$REDIRECT_URI?code=synthetic-code&state=${app.store.pending!!.state}"))

    private fun tokenResponse() = MockResponse().setBody(
        """{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","token_type":"Bearer"}""")

    private fun awaitLoggedIn() = awaitCondition {
        app.session.state.value.let { it.loggedIn && !it.busy }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Session did not reach the expected state", condition())
    }

    private fun drainRequests(): List<RecordedRequest> = buildList {
        while (true) add(server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break)
    }

    private fun nextRequest(): RecordedRequest {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            server.takeRequest(50, TimeUnit.MILLISECONDS)?.let { return it }
        }
        throw AssertionError("No request reached MockWebServer")
    }
}
