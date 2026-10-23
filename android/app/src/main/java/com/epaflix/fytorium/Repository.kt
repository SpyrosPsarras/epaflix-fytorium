package com.epaflix.fytorium

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import com.epaflix.fytorium.plants.*

data class CareState(val member: Member? = null, val today: TaskList? = null, val upcoming: TaskList? = null,
                     val plants: List<Plant> = emptyList(), val detail: PlantDetail? = null,
                     val offline: Boolean = false, val serverUnavailable: Boolean = false, val lastUpdated: Long? = null, val busy: Boolean = false,
                     val message: String? = null, val updateRequired: Boolean = false,
                     val handled: Map<String, CareEvent> = emptyMap(), val saving: Boolean = false) {
    val writable get() = !offline && !serverUnavailable && !saving && !updateRequired && member != null
    private fun updatedTime() = lastUpdated?.let {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    }
    fun offlineBanner(): String = updatedTime()?.let { "Offline, last updated $it" } ?: "Offline, not updated yet"
    fun serverBanner(): String = "Fytorium isn't responding. " + (updatedTime()?.let { "Showing data from $it" } ?: "Not updated yet")
}
private class ApiFailure(val status: Int, message: String, val errors: Map<String, String> = emptyMap()) : Exception(message)
private class OfflineFailure : IOException("Offline")

// Blocking IO entry points run on Dispatchers.IO, shared by foreground and WorkManager.
class Repository(private val api: ApiClient, private val cache: CareCache, private val connected: () -> Boolean,
                 private val clock: () -> Long = System::currentTimeMillis, private val wipeThumbnails: () -> Unit = {}) {
    private val monitor = Any()
    private val workLock = Any()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generation = 0
    private val requestIds = mutableMapOf<String, String>()
    private fun requestId(key: String) = synchronized(monitor) { requestIds.getOrPut(key) { UUID.randomUUID().toString() } }
    private val mutable = MutableStateFlow(CareState())
    val state = mutable.asStateFlow()
    private val plantMutable = MutableStateFlow(PlantsRequestState(online = connected()))
    val plantState = plantMutable.asStateFlow()
    fun clearPlantErrors() = synchronized(monitor) { plantMutable.value = plantMutable.value.copy(errors = emptyMap()) }
    private fun plantFailed(error: Exception, epoch: Int, writing: Boolean = false) = synchronized(monitor) {
        if (epoch != generation) return@synchronized
        val failure = classifyNetworkFailure(connected(), (error as? ApiFailure)?.status)
        plantMutable.value = plantMutable.value.copy(online = !failure.offline,
            blocked = error is ApiFailure && (error.status in listOf(401, 426) || (writing && error.status == 403)),
            errors = (error as? ApiFailure)?.errors?.takeIf { it.isNotEmpty() }
                ?: mapOf("_form" to if (writing && error is ApiFailure && error.status == 403)
                    "Your permissions no longer allow this change. Refresh your account before editing."
                    else (error.message ?: "Could not reach Fytorium. Try again.")))
        if (error is ApiFailure && error.status == 426) mutable.value = mutable.value.copy(updateRequired = true)
    }
    private fun read(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val error = runCatching { JSONObject(text) }.getOrNull()
                throw ApiFailure(it.code, error?.optString("message")?.takeIf(String::isNotBlank)
                    ?: error?.optJSONObject("errors")?.let { fields -> fields.keys().asSequence().joinToString("\n") { key -> "$key: ${fields.getString(key)}" } }
                    ?: "Could not update Fytorium. Try again.",
                    error?.optJSONObject("errors")?.let { fields -> fields.keys().asSequence().associateWith { key -> fields.optString(key) } } ?: emptyMap())
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
        plantMutable.value = PlantsRequestState(online = connected())
    }
    fun connectivityChanged(online: Boolean) = synchronized(monitor) {
        plantMutable.value = plantMutable.value.copy(online = online)
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
            // Sitters receive the server's shared active header projection, including plants without due tasks.
            val plants = read("/api/plants")
            parseTaskList(today); parseTaskList(upcoming); JSONArray(plants).mapObjects(::parsePlant)
            val meta = read("/api/meta")
            ZoneId.of(JSONObject(meta).getString("timezone"))
            synchronized(monitor) {
                if (epoch == generation) {
                    cache.save(member.id, mapOf("today" to today, "upcoming" to upcoming, "plants" to plants, "meta" to meta), clock())
                    loadCached()
                    mutable.value = mutable.value.copy(offline = false, serverUnavailable = false, message = null, updateRequired = false, handled = emptyMap())
                }
            }
        } catch (error: Exception) { failed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false) } }
    }
    private fun failed(error: Exception, epoch: Int) = synchronized(monitor) {
        if (epoch != generation) return@synchronized
        val failure = classifyNetworkFailure(connected(), (error as? ApiFailure)?.status)
        val offline = failure.offline
        val unavailable = failure.serverUnavailable
        mutable.value = mutable.value.copy(offline = if (offline || unavailable) offline else mutable.value.offline,
            serverUnavailable = if (offline || unavailable) unavailable else mutable.value.serverUnavailable,
            updateRequired = mutable.value.updateRequired || (error is ApiFailure && error.status == 426),
            message = if (error is ApiFailure && error.status < 500) error.message else null)
    }
    private fun refreshSchedules(epoch: Int) = synchronized(workLock) {
        val member = synchronized(monitor) { state.value.member?.takeIf { epoch == generation } } ?: return@synchronized
        try {
            if (!connected()) throw OfflineFailure()
            val today = read("/api/tasks", query = mapOf("view" to "today"))
            val upcoming = read("/api/tasks", query = mapOf("view" to "upcoming"))
            parseTaskList(today); parseTaskList(upcoming)
            synchronized(monitor) { if (epoch == generation) {
                cache.save(member.id, mapOf("today" to today, "upcoming" to upcoming), clock())
                mutable.value = mutable.value.copy(today = parseTaskList(today), upcoming = parseTaskList(upcoming))
            } }
        } catch (error: Exception) {
            synchronized(monitor) { if (epoch == generation) mutable.value = mutable.value.copy(message = error.message ?: "Could not refresh care tasks. Try again.") }
        }
    }
    fun openPlant(id: String, full: Boolean = false): PlantDetail? = synchronized(workLock) {
        requireSafeId(id)
        val member = state.value.member ?: return null
        val epoch = synchronized(monitor) { generation }
        val resource = "plant/$id"
        try {
            if (!connected()) throw OfflineFailure()
            synchronized(monitor) { if (epoch != generation) return null; plantMutable.value = plantMutable.value.copy(refreshing = true) }
            val plant = read("/api/plants/$id")
            val history = if (member.role == Role.sitter) "[]" else read("/api/plants/$id/history")
            val tasks = read("/api/plants/$id/tasks")
            val snapshot = JSONObject().put("plant", JSONObject(plant)).put("history", JSONArray(history)).put("tasks", JSONObject(tasks))
            if (full && member.role != Role.sitter) {
                snapshot.put("careSettings", JSONObject(read("/api/plants/$id/care-settings")))
                snapshot.put("speciesChanges", JSONArray(read("/api/plants/$id/species-changes")))
            }
            val json = snapshot.toString()
            synchronized(monitor) { if (epoch == generation) {
                cache.save(member.id, mapOf(resource to json), clock())
                plantMutable.value = plantMutable.value.copy(blocked = false, errors = emptyMap())
            } }
        } catch (error: Exception) { plantFailed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) plantMutable.value = plantMutable.value.copy(refreshing = false) } }
        synchronized(monitor) {
            if (epoch != generation) return null
            val detail = cache.get(member.id, resource)?.let { row ->
                val j = JSONObject(row.json)
                PlantDetail(parsePlant(j.getJSONObject("plant")), j.getJSONArray("history").mapObjects(::parseEvent), parseSchedule(j.getJSONObject("tasks")),
                    j.optJSONObject("careSettings")?.toString(), j.optJSONArray("speciesChanges")?.toString() ?: "[]")
            }
            mutable.value = mutable.value.copy(detail = detail)
            detail
        }
    }
    fun act(task: CareTask, action: String): Boolean = synchronized(workLock) {
        val member = state.value.member ?: return false
        val event = state.value.handled[task.plantId + ":" + task.key] ?: task.completedEvent
        if (action !in task.actions(state.value.today?.today ?: return false, member, event)) return false
        requireSafeId(task.plantId); requireSafeId(task.key)
        val path = when (action) {
            "undo" -> event?.let { if (it.batchId != null) "/api/task-batches/${requireSafeId(it.batchId)}/undo" else "/api/care-events/${requireSafeId(it.id)}/undo" } ?: return false
            else -> "/api/plants/${task.plantId}/tasks/${task.key}/$action"
        }
        val isFertilize = task.taskType == "fertilize"
        val completesFertilize = isFertilize && action == "complete"
        return write(path, body = if (action == "undo") null else JSONObject().put("request_id", requestId(task.plantId + ":" + task.key + ":" + action)), refreshAfter = action == "undo", refreshSchedulesAfter = completesFertilize) { result ->
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
            mutable.value = mutable.value.copy(handled = if (completesFertilize) handled - (task.plantId + ":water") else handled)
        }.also { success ->
            if (success && (completesFertilize || (isFertilize && action == "undo")) && state.value.detail?.plant?.id == task.plantId)
                openPlant(task.plantId, full = state.value.detail?.careSettingsJson != null)
        }
    }
    fun handleAll(plantId: String, plantsScreen: Boolean = false): Boolean = write("/api/plants/${requireSafeId(plantId)}/tasks/complete-all",
        body = JSONObject().put("request_id", requestId("all:$plantId")), plantsScreen = plantsScreen) { result ->
        val json = JSONObject(result)
        val events = json.getJSONArray("events").mapObjects(::parseEvent)
        val schedule = json.getJSONObject("tasks")
        val tasks = schedule.keys().asSequence().filter { it != "customTasks" }.associateWith { schedule.getJSONObject(it) } +
            (schedule.optJSONObject("customTasks")?.let { custom -> custom.keys().asSequence().associateWith { custom.getJSONObject(it) } } ?: emptyMap())
        patchEvents(json.getJSONArray("events").mapObjects { it }, tasks)
        requestIds.remove("all:$plantId")
        mutable.value = mutable.value.copy(handled = state.value.handled + events.associateBy { it.plantId + ":" + (it.customTaskId ?: it.taskType) })
    }
    fun handleType(taskKey: String): Boolean {
        if (state.value.member?.role !in listOf(Role.owner, Role.family)) return false
        return write("/api/tasks/${requireSafeId(taskKey)}/complete-all",
            body = JSONObject().put("request_id", requestId("type:$taskKey")), refreshSchedulesAfter = true) { result ->
            val events = JSONObject(result).getJSONArray("events")
            val handled = events.mapObjects(::parseEvent).associateBy { it.plantId + ":" + (it.customTaskId ?: it.taskType) }
            patchEvents(events.mapObjects { it }, emptyMap())
            requestIds.remove("type:$taskKey")
            mutable.value = mutable.value.copy(handled = state.value.handled + handled)
        }
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
    private fun write(path: String, method: String = "POST", body: JSONObject?, refreshAfter: Boolean = false, plantsScreen: Boolean = false, refreshSchedulesAfter: Boolean = false, applied: (String) -> Unit): Boolean = synchronized(workLock) {
        val epoch = synchronized(monitor) {
            val allowed = if (plantsScreen) plantMutable.value.enabled(state.value.member, state.value.updateRequired) else state.value.writable
            if (!allowed || !connected()) {
                if (!connected()) connectivityChanged(false)
                return false
            }
            if (plantsScreen) plantMutable.value = plantMutable.value.copy(saving = true, errors = emptyMap())
            else mutable.value = mutable.value.copy(busy = true, saving = true, message = null)
            generation
        }
        try {
            val result = read(path, method, body)
            synchronized(monitor) { if (epoch != generation) return false; applied(result) }
        } catch (error: Exception) { if (plantsScreen) plantFailed(error, epoch, writing = true) else failed(error, epoch); return false }
        finally { synchronized(monitor) { if (epoch == generation) {
            if (plantsScreen) plantMutable.value = plantMutable.value.copy(saving = false)
            else mutable.value = mutable.value.copy(busy = false, saving = false)
        } } }
        if (refreshSchedulesAfter || (refreshAfter && plantsScreen)) background.launch { refreshSchedules(epoch) }
        if (refreshAfter) {
            if (plantsScreen) {
                refreshPlants()
            } else refresh()
        }
        true
    }
    private fun <T> plantRead(path: String, query: Map<String, String> = emptyMap(), parse: (String) -> T): T? = synchronized(workLock) {
        val epoch = synchronized(monitor) { generation }
        if (state.value.member == null || state.value.member?.role == Role.sitter) return null
        try {
            if (!connected()) throw OfflineFailure()
            val result = parse(read(path, query = query))
            synchronized(monitor) {
                if (epoch != generation) null else { plantMutable.value = plantMutable.value.copy(blocked = false, errors = emptyMap()); result }
            }
        } catch (error: Exception) { plantFailed(error, epoch); null }
    }
    fun refreshPlants() = synchronized(workLock) {
        val member = state.value.member ?: return@synchronized
        val epoch = synchronized(monitor) { plantMutable.value = plantMutable.value.copy(refreshing = true); generation }
        try {
            if (!connected()) throw OfflineFailure()
            val json = read("/api/plants")
            val plants = JSONArray(json).mapObjects(::parsePlant)
            synchronized(monitor) { if (epoch == generation) {
                cache.save(member.id, mapOf("plants" to json), clock())
                mutable.value = mutable.value.copy(plants = plants)
                plantMutable.value = plantMutable.value.copy(blocked = false, errors = emptyMap())
            } }
        } catch (error: Exception) { plantFailed(error, epoch) }
        finally { synchronized(monitor) { if (epoch == generation) plantMutable.value = plantMutable.value.copy(refreshing = false) } }
    }
    fun plantOptions(): PlantOptions? = synchronized(workLock) {
        val member = state.value.member ?: return@synchronized null
        val epoch = synchronized(monitor) { generation }
        val json = plantRead("/api/choices") { text -> parsePlantOptions(text); text }
        synchronized(monitor) {
            if (epoch != generation) return@synchronized null
            if (json != null) cache.save(member.id, mapOf("plant-choices" to json), clock())
            cache.get(member.id, "plant-choices")?.let { parsePlantOptions(it.json) }
        }
    }
    fun plantSites(): List<PlantSite>? = plantRead("/api/sites") { JSONArray(it).mapObjects { site -> PlantSite(site.getString("id"), site.getString("name")) } }
    fun searchSpecies(query: String): SpeciesSearch? = plantRead("/api/species/search", mapOf("q" to query), ::parseSpeciesSearch)
    fun species(key: Long): Species? { require(key > 0); return plantRead("/api/species/$key", parse = ::parseSpecies) }
    fun graveyard(): List<Plant> = synchronized(workLock) {
        val member = state.value.member ?: return emptyList()
        if (member.role == Role.sitter) return emptyList()
        val epoch = synchronized(monitor) { generation }
        val json = plantRead("/api/plants", mapOf("status" to "graveyard")) { text -> JSONArray(text).mapObjects(::parsePlant); text }
        synchronized(monitor) {
            if (epoch != generation) return emptyList()
            if (json != null) cache.save(member.id, mapOf("graveyard" to json), clock())
            cache.get(member.id, "graveyard")?.let { JSONArray(it.json).mapObjects(::parsePlant) } ?: emptyList()
        }
    }
    fun savePlant(id: String?, body: JSONObject): Plant? {
        if (state.value.member?.role == Role.sitter) return null
        var saved: Plant? = null
        write(if (id == null) "/api/plants" else "/api/plants/${requireSafeId(id)}", if (id == null) "POST" else "PUT", body, refreshAfter = true, plantsScreen = true) {
            saved = parsePlant(JSONObject(it))
        }
        return saved
    }
    fun plantCommand(id: String, action: String, body: JSONObject = JSONObject()): Boolean {
        if (state.value.member?.role == Role.sitter) return false
        require(action in listOf("graveyard", "restore", "delete", "species", "care-settings", "repot"))
        val path = "/api/plants/${requireSafeId(id)}" + if (action == "delete") "" else "/$action"
        val payload = if (action == "repot") body.put("request_id", requestId("plant-repot:$id")) else body
        return write(path, if (action == "delete") "DELETE" else if (action == "care-settings") "PUT" else "POST", payload, refreshAfter = true, plantsScreen = true) {
            if (action == "delete") mutable.value = mutable.value.copy(detail = null)
            if (action == "repot") requestIds.remove("plant-repot:$id")
        }
    }
    fun undoHistory(event: CareEvent): Boolean {
        if (!event.canUndo) return false
        val path = event.batchId?.let { "/api/task-batches/${requireSafeId(it)}/undo" } ?: "/api/care-events/${requireSafeId(event.id)}/undo"
        return write(path, body = null, refreshAfter = true, plantsScreen = true) {}
    }
    fun speciesNameCommand(key: Long, action: String, body: JSONObject = JSONObject()): Boolean {
        if (state.value.member?.role == Role.sitter) return false
        require(key > 0)
        val suffix = when (action) { "add" -> ""; "refresh" -> "/refresh"; else -> "/${requireSafeId(action)}" }
        return write("/api/species/$key/names$suffix", if (action in listOf("add", "refresh")) "POST" else "DELETE", body, plantsScreen = true) {}
    }
    private fun requireSafeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return id
    }
}
