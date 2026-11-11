package com.epaflix.fytorium

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue

// Holds matching requests until release(), so tests can act while a write is in flight.
internal class HeldServer(server: MockWebServer, held: (RecordedRequest) -> Boolean, respond: (RecordedRequest) -> MockResponse) : AutoCloseable {
    private val latch = java.util.concurrent.CountDownLatch(1)
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    val paths: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    init {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add(request.path!!)
                if (held(request)) check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return respond(request)
            }
        }
    }
    fun submit(action: () -> Boolean): java.util.concurrent.Future<Boolean> = executor.submit(java.util.concurrent.Callable(action))
    fun release() = latch.countDown()
    fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
    override fun close() { latch.countDown(); executor.shutdownNow() }
}

internal class ServedLists(server: MockWebServer, @Volatile var today: String = taskListFixture("today"),
                           @Volatile var upcoming: String = taskListFixture("upcoming"), @Volatile var plants: String = "[]",
                           hold: (RecordedRequest) -> Boolean = { false }, role: String = "owner") : AutoCloseable {
    val responses = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val held = HeldServer(server, hold) { request ->
        when (val path = request.requestUrl!!.encodedPath) {
            "/api/me" -> MockResponse().setBody("""{"member":{"id":"member-one","role":"$role"}}""")
            "/api/tasks" -> MockResponse().setBody(if (request.requestUrl!!.queryParameter("view") == "today") today else upcoming)
            "/api/plants" -> MockResponse().setBody(plants)
            "/api/meta" -> MockResponse().setBody("""{"timezone":"Europe/Oslo"}""")
            else -> responses[path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
    }
    val paths get() = held.paths
    fun await(condition: () -> Boolean) = held.await(condition)
    fun release() = held.release()
    override fun close() = held.close()
}
