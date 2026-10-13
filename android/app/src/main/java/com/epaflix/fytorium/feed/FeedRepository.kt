package com.epaflix.fytorium.feed

import com.epaflix.fytorium.*
import java.io.IOException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

private class FeedFailure(val status: Int, val reason: String?, message: String) : Exception(message)

// Memory only. Session changes discard the feed, settings and any in-flight result.
class FeedRepository(private val api: ApiClient, private val connected: () -> Boolean) {
    private val monitor = Any()
    private val workLock = Any()
    private var generation = 0
    private var rainRequestId: String? = null
    private var fastPollAttempts = 0
    private val mutable = MutableStateFlow(FeedState())
    val state = mutable.asStateFlow()
    fun wipe() = synchronized(monitor) { generation++; rainRequestId = null; fastPollAttempts = 0; mutable.value = FeedState() }
    fun connectivityChanged(online: Boolean) = synchronized(monitor) {
        mutable.value = mutable.value.copy(offline = !online, unavailable = if (online) mutable.value.unavailable else false)
    }
    internal suspend fun poll(lifecycle: Lifecycle, io: CoroutineDispatcher = Dispatchers.IO) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.map { Triple(it.member, it.offline, it.weather?.locationSet) }.distinctUntilChanged().collectLatest { (member, offline, locationSet) ->
                if (member == null || member.role == Role.sitter || offline || locationSet == false) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    val fast = synchronized(monitor) { state.value.weather?.locationSet == true &&
                        state.value.weather?.forecastFetchedAt == null && !state.value.unavailable && fastPollAttempts < 20 }
                    delay(if (fast) 3_000 else 30_000)
                    if (!connected()) { connectivityChanged(false); return@collectLatest }
                    if (!state.value.refreshing && !state.value.saving) {
                        if (fast) synchronized(monitor) { fastPollAttempts++ }
                        withContext(io) { refresh(member) }
                    }
                }
            }
        }
    }
    private fun read(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        if (!connected()) throw IOException("Offline")
        return api.request(path, method = method, body = body).use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                throw FeedFailure(it.code, json?.nullable("reason"), json?.nullable("message") ?: "Could not update the feed. Try again.")
            }
            JSONObject(text)
        }
    }
    fun refresh(member: Member?) = synchronized(workLock) refresh@ {
        val epoch = synchronized(monitor) {
            if (member != state.value.member) { generation++; rainRequestId = null; fastPollAttempts = 0; mutable.value = FeedState(member = member) }
            if (member == null) return@refresh
            mutable.value = mutable.value.copy(refreshing = true)
            generation
        }
        try {
            if (!connected()) throw IOException("Offline")
            val feed = read("/api/feed")
            val cards = feed.getJSONArray("cards").mapObjects(::parseFeedCard).filterNotNull()
                .filter { member!!.role != Role.sitter || it.kind == "late_tasks" }
            val settings = if (member!!.role == Role.sitter) null else feed.getJSONObject("settings").let { j -> feedLabels.keys.associateWith { j.getBoolean(it) } }
            val weather = if (member.role == Role.sitter) null else parseWeather(read("/api/weather"))
            synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(cards = cards, settings = settings,
                weather = weather, loaded = true, offline = !connected(), unavailable = false,
                message = if (mutable.value.unavailable) null else mutable.value.message) }
        } catch (error: Exception) { failed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(refreshing = false) } }
    }
    fun loadLocation() = synchronized(workLock) load@ {
        val epoch = synchronized(monitor) {
            if (!state.value.writable || !connected()) return@load
            mutable.value = mutable.value.copy(refreshing = true)
            generation
        }
        try {
            val result = read("/api/household/location")
            synchronized(monitor) { if (epoch == generation) applyLocation(result) }
        } catch (error: Exception) { failed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(refreshing = false) } }
    }
    private fun applyLocation(result: JSONObject) {
        val location = result.optJSONObject("location")?.let { WeatherLocation(it.getDouble("latitude"), it.getDouble("longitude"), it.nullable("label")) }
        mutable.value = mutable.value.copy(location = location, locationLoaded = true)
    }
    fun saveSettings(settings: Map<String, Boolean>): Boolean = write("/api/feed/settings", "PUT", JSONObject(settings), "Feed settings saved.") {}
    fun saveLocation(location: WeatherLocation?): Boolean {
        if (state.value.member?.role != Role.owner) return false
        return write("/api/household/location", "PUT", JSONObject().put("location", location?.json() ?: JSONObject.NULL),
            "Location saved. Weather refreshes in the background.", ::applyLocation)
    }
    fun water(): Boolean = synchronized(workLock) {
        val rain = state.value.weather?.rain ?: return false
        if (rain.batchId != null) return false
        val id = synchronized(monitor) { rainRequestId ?: java.util.UUID.randomUUID().toString().also { rainRequestId = it } }
        write("/api/weather/rain/water", "POST", JSONObject().put("request_id", id)) { result ->
            rainRequestId = null
            mutable.value = mutable.value.copy(message = "Marked ${result.getInt("plantCount")} outdoor plants watered.")
        }
    }
    fun undoRain(): Boolean {
        val id = state.value.weather?.lastBatchId ?: return false
        if (!id.matches(Regex("[A-Za-z0-9_-]+"))) {
            synchronized(monitor) { mutable.value = mutable.value.copy(message = "Rain watering could not be undone. Refresh to try again.") }
            return false
        }
        return write("/api/task-batches/$id/undo", "POST", null, "Rain watering undone.") { rainRequestId = null }
    }
    private fun write(path: String, method: String, body: JSONObject?, message: String? = null, applied: (JSONObject) -> Unit): Boolean = synchronized(workLock) write@ {
        val epoch = synchronized(monitor) {
            if (!state.value.writable || !connected()) { if (!connected()) connectivityChanged(false); return@write false }
            mutable.value = mutable.value.copy(saving = true, message = null)
            generation
        }
        try {
            val result = read(path, method, body)
            synchronized(monitor) {
                if (epoch != generation) return@write false
                applied(result)
                if (message != null) mutable.value = mutable.value.copy(message = message)
            }
        } catch (error: Exception) { failed(error, epoch); return@write false }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(saving = false) } }
        refresh(state.value.member)
        true
    }
    private fun failed(error: Exception, epoch: Int) = synchronized(monitor) {
        if (epoch != generation) return@synchronized
        val failure = classifyNetworkFailure(connected(), (error as? FeedFailure)?.status)
        mutable.value = mutable.value.copy(offline = failure.offline, unavailable = failure.serverUnavailable,
            message = when {
                error is FeedFailure && error.status == 409 && error.reason == "rain_report_not_ready" -> "Rain watering is not ready. Try again in a moment."
                error is FeedFailure -> error.message
                error is IOException -> "Could not reach Fytorium. Retry uses the same request."
                else -> "Fytorium isn't responding. Refresh to try again."
            })
    }
}
