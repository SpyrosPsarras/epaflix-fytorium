package com.epaflix.fytorium

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.coroutines.flow.asStateFlow

class MemoryCredentials : CredentialStore {
    private val savedTokens = kotlinx.coroutines.flow.MutableStateFlow<Tokens?>(null)
    override val tokensState = savedTokens.asStateFlow()
    override var tokens: Tokens?
        get() = savedTokens.value
        set(value) { savedTokens.value = value }
    override var pending: LoginAttempt? = null
    override fun clear() { tokens = null; pending = null }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthTest {
    private val pair = """{"access_token":"synthetic-new","refresh_token":"synthetic-next","token_type":"Bearer","expires_in":3600,"device_id":"test"}"""
    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = Pkce.create()
        assertEquals(43, first.verifier.length)
        assertEquals(43, first.state.length)
        assertNotEquals(first.state, Pkce.create().state)
        assertNotEquals(first.state, first.verifier)
    }
    @Test fun stateRedirectAndExpiryAreValidated() {
        val login = LoginAttempt("synthetic-verifier", "synthetic-state", 100)
        val valid = "$REDIRECT_URI?code=synthetic-code&state=synthetic-state"
        assertEquals("synthetic-code", login.codeFrom(valid, 101))
        listOf(valid.replace("synthetic-state", "wrong"), valid + "&state=synthetic-state",
            valid + "&code=other", valid + "#fragment", valid + "&error=access_denied",
            valid.replace(":/", "://evil/"), valid.replace("oauth2redirect", "other"),
            valid.replace("com.epaflix.fytorium", "other"), "$REDIRECT_URI?state=synthetic-state",
            "$REDIRECT_URI?code=x&state=%XX").forEach { assertNull(login.codeFrom(it, 101)) }
        assertNull(login.codeFrom(valid, 600_101))
        assertNull(login.codeFrom(valid, 99))
    }
    @Test fun loginMatchesContractAndConsumesPendingState() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials()
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val url = okhttp3.HttpUrl.Companion.run { api.loginUrl().toHttpUrl() }
            assertEquals("android", url.queryParameter("app"))
            assertEquals(REDIRECT_URI, url.queryParameter("redirect_uri"))
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals(store.pending!!.challenge, url.queryParameter("code_challenge"))
            val state = store.pending!!.state
            server.enqueue(MockResponse().setBody(pair))
            assertTrue(api.completeLogin("$REDIRECT_URI?code=synthetic-code&state=$state"))
            val request = server.takeRequest()
            assertEquals("/api/app/token", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            val json = org.json.JSONObject(request.body.readUtf8())
            assertEquals("authorization_code", json.getString("grant_type"))
            assertEquals("synthetic-code", json.getString("code"))
            assertEquals(url.queryParameter("code_challenge"), Pkce.challenge(json.getString("code_verifier")))
            assertTrue(json.has("device_name"))
            assertNull(store.pending)
            assertFalse(api.completeLogin("$REDIRECT_URI?code=synthetic-code&state=$state"))
            assertEquals("synthetic-new", store.tokens!!.access)
        }
    }
    @Test fun tokenExchangesNeverReuseIdleConnections() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials()
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) { fail("Session lost") }
            server.enqueue(MockResponse().setBody("{}"))
            api.request("/api/meta").use { it.body!!.string() }
            assertEquals(0, server.takeRequest().sequenceNumber)
            api.loginUrl()
            val state = store.pending!!.state
            server.enqueue(MockResponse().setBody(pair))
            assertTrue(api.completeLogin("$REDIRECT_URI?code=synthetic-code&state=$state"))
            val login = server.takeRequest()
            assertEquals("/api/app/token", login.path)
            assertEquals("Login must not reuse the startup API socket", 0, login.sequenceNumber)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(pair))
            server.enqueue(MockResponse().setBody("{}"))
            api.request("/api/meta").use { assertEquals(200, it.code) }
            server.takeRequest()
            val refresh = server.takeRequest()
            assertEquals("/api/app/token", refresh.path)
            assertEquals("Refresh must not reuse an API or prior token socket", 0, refresh.sequenceNumber)
            server.takeRequest()
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun networkLossDiscardsPooledSocketsWithoutRetryingRequests() {
        MockWebServer().use { server ->
            server.start()
            val api = ApiClient(server.url("").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'), MemoryCredentials()) {}
            repeat(2) {
                server.enqueue(MockResponse().setBody("{}"))
                api.request("/api/meta").use { it.body!!.string() }
                assertEquals(it, server.takeRequest().sequenceNumber)
            }
            api.networkLost()
            server.enqueue(MockResponse().setBody("{}"))
            api.request("/api/meta").use { it.body!!.string() }
            assertEquals("Recovered network must not reuse the old socket", 0, server.takeRequest().sequenceNumber)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertThrows(IOException::class.java) { api.request("/api/app/logout", post = true).close() }
            assertEquals("Network recovery must not enable transport retries", 4, server.requestCount)
        }
    }
    @Test fun rejectedStateNeverReachesServer() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials()
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            api.loginUrl()
            assertFalse(api.completeLogin("$REDIRECT_URI?code=x&state=wrong"))
            assertEquals(0, server.requestCount)
            assertNotNull(store.pending)
        }
    }
    @Test fun unauthorizedRefreshesOnceAndRotatesTokens() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-old", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) { fail("Session lost") }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(pair))
            server.enqueue(MockResponse().setBody("{}"))
            api.request("/api/meta").use { assertEquals(200, it.code) }
            assertEquals("Bearer synthetic-old", server.takeRequest().getHeader("Authorization"))
            val refresh = server.takeRequest()
            assertEquals("/api/app/token", refresh.path)
            assertEquals("refresh_token", org.json.JSONObject(refresh.body.readUtf8()).getString("grant_type"))
            assertEquals("Bearer synthetic-new", server.takeRequest().getHeader("Authorization"))
            assertEquals("synthetic-next", store.tokens!!.refresh)
        }
    }
    @Test fun onlyInvalidRefreshCredentialsClearSession() {
        for ((status, error) in listOf(400 to "invalid_grant", 401 to "invalid_token")) MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-old", "synthetic-refresh") }
            var lost = 0
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) { lost++ }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$error"}"""))
            api.request("/api/meta").use { assertEquals(401, it.code); it.body!!.string() }
            assertNull(store.tokens)
            assertEquals(1, lost)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun retry401DoesNotDiscardRotatedTokens() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-old", "synthetic-refresh") }
            var lost = false
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) { lost = true }
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody(pair)); server.enqueue(MockResponse().setResponseCode(401))
            api.request("/api/meta").close()
            assertFalse(lost); assertEquals("synthetic-next", store.tokens!!.refresh); assertEquals(3, server.requestCount)
        }
    }
    @Test fun retryableRefreshFailuresKeepTokens() {
        val failures = listOf(
            MockResponse().setResponseCode(429).setBody("""{"error":"invalid_grant"}"""),
            MockResponse().setResponseCode(500).setBody("""{"error":"invalid_token"}"""),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(400).setBody("""{"error":"server_error"}"""),
            MockResponse().setBody("{}"),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        for (failure in failures) MockWebServer().use { server ->
            server.start()
            val original = Tokens("synthetic-old", "synthetic-refresh")
            val store = MemoryCredentials().apply { tokens = original }
            // The fixture listens only on IPv4, not localhost's alternative IPv6 route.
            val baseUrl = server.url("").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            val api = ApiClient(baseUrl, store) { fail("Retryable error ended session") }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(failure)
            assertThrows(IOException::class.java) { api.request("/api/meta").close() }
            assertSame(original, store.tokens)
            assertEquals(2, server.requestCount)
            // A later retry must still be able to rotate the same retained token.
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(pair))
            server.enqueue(MockResponse().setBody("{}"))
            api.request("/api/meta").use { assertEquals(200, it.code) }
            assertEquals("synthetic-next", store.tokens!!.refresh)
            val requests = (1..5).map { server.takeRequest() }
            val refreshTokens = requests.filter { it.path == "/api/app/token" }
                .map { org.json.JSONObject(it.body.readUtf8()).getString("refresh_token") }
            assertEquals(listOf("synthetic-refresh", "synthetic-refresh"), refreshTokens)
        }
    }
    @Test fun concurrentClientsSharingStoreUseExactlyOneRefresh() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-old", "synthetic-refresh") }
            val clients = (1..2).map { ApiClient(server.url("").toString().trimEnd('/'), store) { fail("Lost session") } }
            val bothUnauthorized = CountDownLatch(2)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/app/token") return MockResponse().setBody(pair)
                    if (request.getHeader("Authorization") == "Bearer synthetic-old") {
                        bothUnauthorized.countDown()
                        check(bothUnauthorized.await(10, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(401)
                    }
                    return MockResponse().setBody("{}")
                }
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val jobs = clients.map { api -> executor.submit<Int> { api.request("/api/meta").use { it.code } } }
                jobs.forEach { assertEquals(200, it.get(15, TimeUnit.SECONDS).toInt()) }
                assertEquals(5, server.requestCount)
                val requests = (1..5).map { server.takeRequest() }
                assertEquals(1, requests.count { it.path == "/api/app/token" })
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun logoutRevokesAndClearsEvenOnServerFailure() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh"); pending = Pkce.create() }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            server.enqueue(MockResponse().setResponseCode(500))
            api.logout()
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("/api/app/logout", request.path)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            assertNull(store.tokens); assertNull(store.pending)
        }
    }
    @Test fun versionCheckReadsTheRealMetaShape() {
        val meta = """{"minAppVersion":null,"serverVersion":"synthetic-sha","today":"2030-06-02","timezone":"Europe/Oslo","role":"sitter"}"""
        assertFalse(requiresUpdate(200, meta, "1.0.0"))
        assertTrue(requiresUpdate(200, meta.replace("null", "\"1.0.1-rc.1\""), "1.0.0"))
        assertFalse(requiresUpdate(200, meta.replace("null", "\"1.0.0+build.1\""), "1.0.0"))
    }
    @Test fun versionCheckHandlesMissingEndpointAndSemanticVersions() {
        assertFalse(requiresUpdate(404, "", "1.0.0"))
        assertTrue(requiresUpdate(426, "", "1.0.0"))
        assertTrue(requiresUpdate(200, """{"minAppVersion":"1.10.0"}""", "1.9.0"))
        assertFalse(requiresUpdate(200, """{"minAppVersion":"1.0.0"}""", "1.0.0"))
        assertFalse(requiresUpdate(200, """{"minAppVersion":"0.9.0"}""", "1.0.0"))
    }
}
