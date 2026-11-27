package com.epaflix.fytorium.platform

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HttpTest {
    private val server = MockWebServer().apply { start() }
    private val http = Http()
    @After fun stop() = server.shutdown()

    private fun attempt(method: String, body: String? = null, token: Boolean = false): Result<HttpResult> {
        val done = CompletableFuture<Result<HttpResult>>()
        http.enqueue(http.request(method, server.url("/api/x").toString(), mapOf("Content-Type" to "application/json"), body, token), false) { done.complete(it) }
        return done.get(30, TimeUnit.SECONDS)
    }
    private fun send(method: String, body: String? = null, token: Boolean = false) = attempt(method, body, token).getOrThrow()

    @Test fun bodylessRequestIsNotResentAfter503RetryAfterZero() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(503, send("DELETE").status)
        assertEquals(1, server.requestCount)
    }

    @Test fun writeIsNotResentAfter503RetryAfterZero() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(503, send("POST", "{}").status)
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectIsReturnedNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertEquals(302, send("GET").status)
        assertEquals(1, server.requestCount)
    }

    @Test fun cookiesAreNeitherStoredNorSent() {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "session=1"))
        server.enqueue(MockResponse())
        send("GET"); send("GET")
        server.takeRequest(); assertEquals(null, server.takeRequest().getHeader("Cookie"))
    }

    @Test fun tokenExchangeUsesAFreshConnectionAfterThePoolIsPrimed() {
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        send("GET"); send("GET")
        server.takeRequest(); val reused = server.takeRequest().sequenceNumber
        assertEquals(1, reused)
        send("POST", "{}", token = true)
        assertEquals(0, server.takeRequest().sequenceNumber)
    }

    @Test fun aDroppedTokenResponseIsNotResent() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(attempt("POST", "{\"grant_type\":\"authorization_code\"}", token = true).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun aDroppedWriteResponseIsNotResent() {
        server.enqueue(MockResponse().setBody("{}"))
        send("GET")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(attempt("POST", "{}").isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test fun pushRefreshDoesNotInheritThePeriodicBatteryConstraint() {
        assertFalse(RefreshWorker.pushConstraints.requiresBatteryNotLow())
        assertTrue(RefreshWorker.periodicConstraints.requiresBatteryNotLow())
    }
}
