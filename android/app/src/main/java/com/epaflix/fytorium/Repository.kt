package com.epaflix.fytorium

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CareState(val member: Member? = null, val today: TaskList? = null, val upcoming: TaskList? = null,
                     val plants: List<Plant> = emptyList(), val detail: PlantDetail? = null,
                     val offline: Boolean = false, val serverUnavailable: Boolean = false, val lastUpdated: Long? = null, val busy: Boolean = false,
                     val message: String? = null, val updateRequired: Boolean = false,
                     val handled: Map<String, CareEvent> = emptyMap()) {
    val writable get() = !offline && !serverUnavailable && !busy && !updateRequired && member != null
    private fun updatedTime() = lastUpdated?.let {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    }
    fun offlineBanner(): String = updatedTime()?.let { "Offline, last updated $it" } ?: "Offline, not updated yet"
    fun serverBanner(): String = "Fytorium isn't responding. " + (updatedTime()?.let { "Showing data from $it" } ?: "Not updated yet")
}
private class ApiFailure(val status: Int, message: String) : Exception(message)
private class OfflineFailure : IOException("Offline")

// Blocking IO entry points run on Dispatchers.IO, shared by foreground and WorkManager.
class Repository(private val api: ApiClient, private val cache: CareCache, private val connected: () -> Boolean,
                 private val clock: () -> Long = System::currentTimeMillis, private val wipeThumbnails: () -> Unit = {}) {
    private val monitor = Any()
    private val workLock = Any()
    private var generation = 0
    private val requestIds = mutableMapOf<String, String>()
    private fun requestId(key: String) = synchronized(monitor) { requestIds.getOrPut(key) { UUID.randomUUID().toString() } }
    private val mutable = MutableStateFlow(CareState())
    val state = mutable.asStateFlow()
    private fun read(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val error = runCatching { JSONObject(text) }.getOrNull()
                throw ApiFailure(it.code, error?.optString("message")?.takeIf(String::isNotBlank)
                    ?: error?.optJSONObject("errors")?.let { fields -> fields.keys().asSequence().joinToString("\n") { key -> "$key: ${fields.getString(key)}" } }
                    ?: "Could not update Fytorium. Try again.")
            }
            text
        }
    fun restore() = synchronized(monitor) { loadCached() }
    private fun loadCached() {
        val member = cache.member() ?: return
        val today = cache.get(member.id, "today")
        mutable.value = mutable.value.copy(member = member, today = today?.let { parseTaskList(it.json) },
            upcoming = cache.get(member.id, "upcoming")?.let { parseTaskList(it.json) },
            plants = cache.get(member.id, "plants")?.let { JSONArray(it.json).mapObjects(::parsePlant) } ?: emptyList(),
            lastUpdated = today?.refreshedAt)
    }
    fun wipe() = synchronized(monitor) {
        generation++
        cache.wipe(); wipeThumbnails(); requestIds.clear()
        mutable.value = CareState()
    }
    fun connectivityChanged(online: Boolean) = synchronized(monitor) {
        // A connected transport does not prove the server is reachable. Only refresh clears offline.
        if (!online) mutable.value = mutable.value.copy(offline = true, serverUnavailable = false, message = "Actions are unavailable offline. Reconnect and refresh to make changes.")
    }
    fun refresh() = synchronized(workLock) refreshWork@ {
        val epoch = synchronized(monitor) { loadCached(); mutable.value = mutable.value.copy(busy = true, message = null); generation }
        try {
            if (!connected()) throw OfflineFailure()
            val identity = read("/api/me")
            val member = parseMember(identity)
            synchronized(monitor) {
                if (epoch != generation) return@refreshWork
                if (cache.member() != member) { cache.bind(identity); wipeThumbnails(); requestIds.clear(); mutable.value = CareState(member = member, busy = true, updateRequired = mutable.value.updateRequired) }
                else cache.bind(identity)
            }
            val today = read("/api/tasks", query = mapOf("view" to "today"))
            val upcoming = read("/api/tasks", query = mapOf("view" to "upcoming"))
            // The server denies plant lists/history to sitters. Shared headers come from task rows.
            val plants = if (member.role != Role.sitter) read("/api/plants") else JSONArray().toString()
            parseTaskList(today); parseTaskList(upcoming); JSONArray(plants).mapObjects(::parsePlant)
            synchronized(monitor) {
                if (epoch == generation) {
                    cache.save(member.id, mapOf("today" to today, "upcoming" to upcoming, "plants" to plants), clock())
                    loadCached()
                    mutable.value = mutable.value.copy(offline = false, serverUnavailable = false, message = null, updateRequired = false, handled = emptyMap())
                }
            }
        } catch (error: Exception) { failed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false) } }
    }
    private fun failed(error: Exception, epoch: Int) = synchronized(monitor) {
        if (epoch != generation) return@synchronized
        val offline = !connected() || error is OfflineFailure || error is java.net.UnknownHostException
        val unavailable = !offline && (error !is ApiFailure || error.status >= 500)
        mutable.value = mutable.value.copy(offline = if (offline || unavailable) offline else mutable.value.offline,
            serverUnavailable = if (offline || unavailable) unavailable else mutable.value.serverUnavailable,
            updateRequired = mutable.value.updateRequired || (error is ApiFailure && error.status == 426),
            message = if (error is ApiFailure && error.status < 500) error.message else null)
    }
    fun openPlant(id: String): PlantDetail? = synchronized(workLock) {
        requireSafeId(id)
        val member = state.value.member ?: return null
        val epoch = synchronized(monitor) { generation }
        val resource = "plant/$id"
        try {
            if (!connected() || state.value.offline) throw OfflineFailure()
            synchronized(monitor) { if (epoch != generation) return null; mutable.value = mutable.value.copy(busy = true, message = null) }
            val plant = read("/api/plants/$id")
            val history = if (member.role == Role.sitter) "[]" else read("/api/plants/$id/history")
            val tasks = read("/api/plants/$id/tasks")
            val json = JSONObject().put("plant", JSONObject(plant)).put("history", JSONArray(history)).put("tasks", JSONObject(tasks)).toString()
            synchronized(monitor) { if (epoch == generation) cache.save(member.id, mapOf(resource to json), clock()) }
        } catch (error: Exception) { failed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false) } }
        synchronized(monitor) {
            if (epoch != generation) return null
            val detail = cache.get(member.id, resource)?.let { row ->
                val j = JSONObject(row.json)
                PlantDetail(parsePlant(j.getJSONObject("plant")), j.getJSONArray("history").mapObjects(::parseEvent), parseSchedule(j.getJSONObject("tasks")))
            }
            mutable.value = mutable.value.copy(detail = detail)
            detail
        }
    }
    fun act(task: CareTask, action: String, pot: PotDetails? = null): Boolean = synchronized(workLock) {
        val member = state.value.member ?: return false
        val event = state.value.handled[task.plantId + ":" + task.key] ?: task.completedEvent
        if (action !in task.actions(state.value.today?.today ?: return false, member, event)) return false
        requireSafeId(task.plantId); requireSafeId(task.key)
        val path = when (action) {
            "undo" -> event?.let { if (it.batchId != null) "/api/task-batches/${requireSafeId(it.batchId)}/undo" else "/api/care-events/${requireSafeId(it.id)}/undo" } ?: return false
            "repot" -> "/api/plants/${task.plantId}/repot"
            else -> "/api/plants/${task.plantId}/tasks/${task.key}/$action"
        }
        if (action == "repot" && pot == null) return false
        return write(path, body = if (action == "undo") null else (pot?.json() ?: JSONObject()).put("request_id", requestId(task.plantId + ":" + task.key + ":" + action)), refreshAfter = action == "undo") { result ->
            val key = task.plantId + ":" + task.key
            val handled = if (action == "undo") state.value.handled.filterValues { it.id != event?.id && (event?.batchId == null || it.batchId != event.batchId) }
                else state.value.handled + (key to parseEvent(JSONObject(result).getJSONObject("event")))
            if (action != "undo") {
                patchEvents(listOf(JSONObject(result).getJSONObject("event")), JSONObject(result).optJSONObject("task")?.let { mapOf(task.key to it) } ?: emptyMap())
                requestIds.remove(task.plantId + ":" + task.key + ":" + action)
            }
            else {
                val undone = JSONObject().put("id", event!!.id).put("plant_id", task.plantId).put("task_type", task.taskType ?: JSONObject.NULL)
                    .put("custom_task_id", task.customTaskId ?: JSONObject.NULL).put("batch_id", event.batchId ?: JSONObject.NULL)
                patchEvents(listOf(undone), JSONObject(result).optJSONObject("task")?.let { mapOf(task.key to it) } ?: emptyMap(), undo = true)
                requestIds.keys.removeAll { it.startsWith(task.plantId + ":") || it == "all:${task.plantId}" }
            }
            mutable.value = mutable.value.copy(handled = handled)
        }
    }
    fun handleAll(plantId: String): Boolean = write("/api/plants/${requireSafeId(plantId)}/tasks/complete-all",
        body = JSONObject().put("request_id", requestId("all:$plantId"))) { result ->
        val json = JSONObject(result)
        val events = json.getJSONArray("events").mapObjects(::parseEvent)
        val schedule = json.getJSONObject("tasks")
        val tasks = schedule.keys().asSequence().filter { it != "customTasks" }.associateWith { schedule.getJSONObject(it) } +
            (schedule.optJSONObject("customTasks")?.let { custom -> custom.keys().asSequence().associateWith { custom.getJSONObject(it) } } ?: emptyMap())
        patchEvents(json.getJSONArray("events").mapObjects { it }, tasks)
        requestIds.remove("all:$plantId")
        mutable.value = mutable.value.copy(handled = state.value.handled + events.associateBy { it.plantId + ":" + (it.customTaskId ?: it.taskType) })
    }
    fun saveSettings(settings: TaskSettings): Boolean {
        if (state.value.member?.role == Role.sitter) return false
        return write("/api/tasks/settings", "PUT", settings.json(), refreshAfter = true) {}
    }
    private fun patchEvents(events: List<JSONObject>, tasks: Map<String, JSONObject>, undo: Boolean = false) {
        val member = state.value.member ?: return
        val snapshots = listOf("today", "upcoming").mapNotNull { view ->
            val row = cache.get(member.id, view) ?: return@mapNotNull null
            val json = JSONObject(row.json)
            fun patch(rows: JSONArray) {
                (0 until rows.length()).forEach { index ->
                    val task = rows.getJSONObject(index)
                    val key = task.nullable("custom_task_id") ?: task.nullable("task_type")
                    val event = events.firstOrNull {
                        if (undo && it.nullable("batch_id") != null) task.optJSONObject("completed_event")?.nullable("batch_id") == it.nullable("batch_id")
                        else it.getString("plant_id") == task.getString("plant_id") && (it.nullable("custom_task_id") ?: it.nullable("task_type")) == key
                    }
                    if (event != null) {
                        if (undo) task.remove("completed_event") else task.put("completed_event", event)
                        tasks[key]?.let { next -> listOf("status", "dueOn", "intervalDays", "lateDays", "basedOn").forEach { field ->
                            if (next.has(field)) task.put(field, next.get(field)) else task.remove(field)
                        } }
                    }
                }
            }
            json.getJSONArray("sections").mapObjects { patch(it.getJSONArray("tasks")) }
            val groups = json.getJSONObject("groups")
            groups.keys().forEach { patch(groups.getJSONArray(it)) }
            view to json.toString()
        }.toMap()
        cache.save(member.id, snapshots, state.value.lastUpdated ?: 0)
        loadCached()
    }
    private fun write(path: String, method: String = "POST", body: JSONObject?, refreshAfter: Boolean = false, applied: (String) -> Unit): Boolean = synchronized(workLock) {
        val epoch = synchronized(monitor) {
            if (!state.value.writable || !connected()) {
                if (!connected()) connectivityChanged(false)
                return false
            }
            mutable.value = mutable.value.copy(busy = true, message = null)
            generation
        }
        try {
            val result = read(path, method, body)
            synchronized(monitor) { if (epoch != generation) return false; applied(result) }
        } catch (error: Exception) { failed(error, epoch); return false }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false) } }
        if (refreshAfter) refresh()
        true
    }
    private fun requireSafeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return id
    }
}
