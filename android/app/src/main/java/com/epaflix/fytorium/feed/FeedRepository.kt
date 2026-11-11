package com.epaflix.fytorium.feed

import com.epaflix.fytorium.*
import java.io.IOException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId
import org.json.JSONObject

private class FeedFailure(val status: Int, val reason: String?, message: String) : Exception(message)

// Session changes discard the feed, settings and any in-flight result. The member's offline cache keeps the last loaded feed for the next cold start.
class FeedRepository(private val api: ApiClient, private val connected: () -> Boolean, private val undoBatch: (String, String?) -> Boolean = { _, _ -> false },
                     private val cache: CareCache? = null) {
    private val monitor = Any()
    private val workLock = Any()
    private var generation = 0
    private var rainRequestId: String? = null
    private var fastPollAttempts = 0
    private val mutable = MutableStateFlow(FeedState())
    val state = mutable.asStateFlow()
    fun wipe() = synchronized(monitor) { generation++; rainRequestId = null; fastPollAttempts = 0; mutable.value = FeedState() }
    fun restore() = synchronized(monitor) { cache?.member()?.let(::switchTo) }
    private fun switchTo(member: Member?) {
        if (member == state.value.member) return
        generation++; rainRequestId = null; fastPollAttempts = 0
        mutable.value = member?.let(::cached) ?: FeedState()
    }
    private fun cached(member: Member): FeedState {
        val snapshot = cache?.get(member.id, "feed") ?: return FeedState(member = member)
        return runCatching { JSONObject(snapshot.json).let { parseFeed(member, it.getJSONObject("feed"), it.optJSONObject("weather")) } }.getOrElse { FeedState(member = member) }
    }
    private fun parseFeed(member: Member, feed: JSONObject, weather: JSONObject?): FeedState {
        val zone = cache?.get(member.id, "meta")?.let { runCatching { parseTimezone(it.json) }.getOrNull() } ?: ZoneId.systemDefault()
        val cards = feed.getJSONArray("cards").mapObjects { parseFeedCard(it, zone) }.filterNotNull().filter { member.role != Role.sitter || it.kind == "late_tasks" }
        val settings = if (member.role == Role.sitter) null else feed.getJSONObject("settings").let { j -> feedLabels.keys.associateWith { j.getBoolean(it) } }
        return FeedState(member = member, cards = cards, settings = settings, weather = weather?.let(::parseWeather), loaded = true)
    }
    fun connectivityChanged(online: Boolean) = synchronized(monitor) {
        // Going offline ends any earlier failure, so it never returns with the connection.
        mutable.value = if (online) mutable.value.copy(offline = false) else mutable.value.copy(offline = true, unavailable = false, message = null)
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
            switchTo(member)
            if (member == null) return@refresh
            mutable.value = mutable.value.copy(refreshing = true)
            generation
        }
        try {
            if (!connected()) throw IOException("Offline")
            val feed = read("/api/feed")
            val weather = if (member!!.role == Role.sitter) null else read("/api/weather")
            val next = parseFeed(member, feed, weather)
            synchronized(monitor) { if (epoch == generation) {
                mutable.value = mutable.value.copy(cards = next.cards, settings = next.settings, weather = next.weather, loaded = true,
                    offline = !connected(), unavailable = false, message = null)
                cache?.save(member.id, mapOf("feed" to JSONObject().put("feed", feed).put("weather", weather ?: JSONObject.NULL).toString()), System.currentTimeMillis())
            } }
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
            val batch = result.getString("batchId")
            // The card shows the receipt from the same fields a refresh or another device reads.
            if (result.isNull("undoneAt")) mutable.value = mutable.value.copy(weather = mutable.value.weather?.let {
                it.copy(rain = it.rain?.copy(batchId = batch), lastBatchId = batch, lastBatchPlantCount = result.getInt("plantCount"))
            })
        }
    }
    fun undoRain(): Boolean {
        val weather = state.value.weather
        val id = weather?.lastBatchId ?: return false
        if (!id.matches(Regex("[A-Za-z0-9_-]+"))) {
            synchronized(monitor) { mutable.value = mutable.value.copy(message = "Rain watering could not be undone. Refresh to try again.") }
            return false
        }
        // The care repository sends the Undo, shows its lists and confirms it or reports its failure in Today's receipt.
        return write(null, { if (undoBatch(id, weather.rainUndone)) JSONObject() else null }) { rainRequestId = null }
    }
    private fun write(path: String, method: String, body: JSONObject?, message: String? = null, applied: (JSONObject) -> Unit) =
        write(message, { read(path, method, body) }, applied)
    private fun write(message: String?, send: () -> JSONObject?, applied: (JSONObject) -> Unit): Boolean = synchronized(workLock) write@ {
        val epoch = synchronized(monitor) {
            if (!state.value.writable || !connected()) { if (!connected()) connectivityChanged(false); return@write false }
            mutable.value = mutable.value.copy(saving = true, message = null)
            generation
        }
        try {
            val result = send() ?: return@write false
            synchronized(monitor) {
                if (epoch != generation) return@write false
                applied(result)
            }
        } catch (error: Exception) { failed(error, epoch); return@write false }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(saving = false) } }
        refresh(state.value.member)
        // Set after the refresh, because a successful refresh clears earlier messages.
        if (message != null) synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(message = message) }
        true
    }
    private fun failed(error: Exception, epoch: Int) = synchronized(monitor) {
        if (epoch != generation) return@synchronized
        val failure = classifyNetworkFailure(connected(), (error as? FeedFailure)?.status)
        mutable.value = mutable.value.copy(offline = failure.offline, unavailable = failure.serverUnavailable,
            message = when {
                // Offline already says it on screen, and a stale failure would flash up when the connection returns.
                failure.offline -> null
                error is FeedFailure && error.status == 409 && error.reason == "rain_report_not_ready" -> "Rain watering is not ready. Try again in a moment."
                error is FeedFailure -> error.message
                error is IOException -> "Could not reach Fytorium. Retry uses the same request."
                else -> "Fytorium isn't responding. Refresh to try again."
            })
    }
}
