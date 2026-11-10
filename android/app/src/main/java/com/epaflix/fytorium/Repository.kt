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
                     val receipt: Receipt? = null, val pending: Set<BusyKey> = emptySet(), val batches: Map<String, BatchReceipt> = emptyMap(),
                     val openedOccurrence: String? = null) {
    val writable get() = !offline && !serverUnavailable && !updateRequired && member != null
    val rows get() = listOfNotNull(today, upcoming).flatMap { list -> list.groups.values.flatten() + list.sections.flatMap { it.tasks } }
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
        mutable.value = mutable.value.copy(batches = state.value.rows.mapNotNull { it.completedEvent }.distinctBy { it.batchId }
            .mapNotNull { event -> receiptFor(event)?.let { event.batchId!! to it } }.toMap())
    }
    // A rename elsewhere reaches each cached copy only on its own refresh, so the most recently refreshed copy names the plant.
    private fun plantName(plantId: String): String {
        val current = state.value
        fun refreshed(resource: String) = current.member?.let { cache.get(it.id, resource)?.refreshedAt } ?: 0L
        return listOfNotNull(
            current.rows.firstOrNull { it.plantId == plantId }?.let { (current.lastUpdated ?: 0L) to it.plantName },
            current.plants.firstOrNull { it.id == plantId }?.let { refreshed("plants") to it.displayName },
            current.detail?.plant?.takeIf { it.id == plantId }?.let { refreshed("plant/$plantId") to it.displayName },
        ).maxByOrNull { it.first }?.second ?: "this plant"
    }
    private fun receiptFor(event: CareEvent): BatchReceipt? {
        if (event.batchId == null) return null
        val current = state.value
        val plant = plantName(event.plantId)
        val custom = current.rows.firstOrNull { it.customTaskId == event.batchTask }?.customTaskName
            ?: event.customTaskName?.takeIf { event.customTaskId == event.batchTask }
        return batchReceipt(event, plant, custom)
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
                    mutable.value = mutable.value.copy(offline = false, serverUnavailable = false, message = null, updateRequired = false)
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
            synchronized(monitor) { if (epoch == generation) saveLists(member, today, upcoming) }
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
    private inline fun <T> withPending(key: BusyKey, block: () -> T): T {
        synchronized(monitor) { mutable.value = mutable.value.copy(pending = mutable.value.pending + key) }
        try { return block() } finally { synchronized(monitor) { mutable.value = mutable.value.copy(pending = mutable.value.pending - key) } }
    }
    fun act(task: CareTask, action: String): Boolean {
        if (action == "undo") {
            val event = task.completedEvent?.takeIf { it.canUndo } ?: return false
            if (event.batchId == null) return undo(event, busy = BusyKey.Row(task.occurrence))
            showBatchReceipt(event)
            return true
        }
        return withPending(BusyKey.Row(task.occurrence)) { synchronized(workLock) {
            val member = state.value.member ?: return false
            val today = state.value.today?.today ?: return false
            if (action !in task.actions(today, member)) return false
            requireSafeId(task.plantId); requireSafeId(task.key)
            val completesFertilize = task.taskType == "fertilize" && action == "complete"
            val request = task.occurrence + ":" + action
            write("/api/plants/${task.plantId}/tasks/${task.key}/$action", body = JSONObject().put("request_id", requestId(request)),
                refreshSchedulesAfter = action != "complete" || completesFertilize, receipts = true) { result ->
                val response = JSONObject(result)
                val saved = response.getJSONObject("event")
                val watered = (response.optJSONArray("linked_events")?.length() ?: 0) > 0
                val next = response.optJSONObject("task")
                patchEvents(listOf(saved), next?.let { mapOf(task.key to it) } ?: emptyMap(), if (action == "complete") Patch.Complete else Patch.Postpone, task.occurrence)
                requestIds.remove(request)
                val text = if (action == "complete") doneText(task.taskType, task.customTaskName, task.plantName, watered = watered)
                    else (if (action == "snooze") "Snoozed " else "Skipped ") + task.plantName + next?.nullable("dueOn")?.let { " until " + shortDate(it) }.orEmpty()
                mutable.value = mutable.value.copy(receipt = Receipt(text, parseEvent(saved)))
            }.also { success -> if (success) reloadOpenPlant { it.plant.id == task.plantId } }
        } }
    }
    // The open plant page's history and schedule show the write as soon as it succeeds.
    private fun reloadOpenPlant(affected: (PlantDetail) -> Boolean) {
        state.value.detail?.takeIf(affected)?.let { openPlant(it.plant.id, full = it.careSettingsJson != null) }
    }
    // The plant page's occurrence lives here so a write moves it with the rows it patches, before any refresh can replace them.
    fun open(occurrence: String?) = synchronized(monitor) { mutable.value = mutable.value.copy(openedOccurrence = occurrence) }
    fun showBatchReceipt(event: CareEvent) = synchronized(monitor) {
        state.value.batches[event.batchId]?.let { mutable.value = mutable.value.copy(receipt = Receipt(it.text, event)) }
    }
    fun clearReceipt(receipt: Receipt) = synchronized(monitor) {
        if (state.value.receipt?.id == receipt.id) mutable.value = mutable.value.copy(receipt = null)
    }
    fun undoReceipt(receipt: Receipt): Boolean {
        clearReceipt(receipt)
        return receipt.undo?.let { undo(it) } ?: false
    }
    fun undo(event: CareEvent, plantsScreen: Boolean = false, busy: BusyKey = BusyKey.Row(event.id)): Boolean {
        if (!event.canUndo) return false
        val path = event.batchId?.let { "/api/task-batches/${requireSafeId(it)}/undo" } ?: "/api/care-events/${requireSafeId(event.id)}/undo"
        return withPending(busy) {
            write(path, body = null, plantsScreen = plantsScreen, receipts = true) { result ->
                undone(JSONObject(result))
                requestIds.keys.removeAll { it.startsWith(event.plantId + ":") || it == "all:${event.plantId}" }
                mutable.value = mutable.value.copy(receipt = Receipt("Undone", short = true))
            }.also { success -> if (success) {
                // A batch receipt names one of its plants, so the open page's history shows whether the batch included it.
                reloadOpenPlant { it.plant.id == event.plantId || (event.batchId != null && it.history.any { past -> past.batchId == event.batchId }) }
                // Undoing a repot restores the earlier pot, which the plant list shows.
                if (plantsScreen) refreshPlants()
            } }
        }
    }
    // The feed's rain card has only its batch id, and undoes it through the same write as every other Undo.
    fun undoBatch(batchId: String): Boolean =
        write("/api/task-batches/${requireSafeId(batchId)}/undo", body = null, receipts = true) { undone(JSONObject(it)) }
            .also { success -> if (success) reloadOpenPlant { it.history.any { past -> past.batchId == batchId } } }
    // An undo's response carries the member's lists, so every row it reversed shows its earlier state at once.
    private fun undone(response: JSONObject) {
        val member = state.value.member ?: return
        val opened = state.value.rows.firstOrNull { it.occurrence == state.value.openedOccurrence }
        saveLists(member, response.getJSONObject("today").toString(), response.getJSONObject("upcoming").toString())
        // The restored row has a new occurrence, so the plant page follows that plant's pending task.
        if (opened != null && state.value.rows.none { it.occurrence == opened.occurrence }) mutable.value = mutable.value.copy(openedOccurrence =
            state.value.rows.firstOrNull { it.plantId == opened.plantId && it.key == opened.key && it.completedEvent == null }?.occurrence ?: opened.occurrence)
    }
    private fun saveLists(member: Member, today: String, upcoming: String) {
        parseTaskList(today); parseTaskList(upcoming)
        cache.save(member.id, mapOf("today" to today, "upcoming" to upcoming), clock())
        loadCached()
    }
    fun handleAll(plantId: String, plantsScreen: Boolean = false): Boolean = withPending(BusyKey.HandleAll(plantId)) {
        write("/api/plants/${requireSafeId(plantId)}/tasks/complete-all", body = JSONObject().put("request_id", requestId("all:$plantId")),
            plantsScreen = plantsScreen, receipts = true) { result ->
            val json = JSONObject(result)
            val events = json.getJSONArray("events").mapObjects { it }
            val schedule = json.getJSONObject("tasks")
            val tasks = schedule.keys().asSequence().filter { it != "customTasks" }.associateWith { schedule.getJSONObject(it) } +
                (schedule.optJSONObject("customTasks")?.let { custom -> custom.keys().asSequence().associateWith { custom.getJSONObject(it) } } ?: emptyMap())
            patchEvents(events, tasks)
            requestIds.remove("all:$plantId")
            batchHandled(events)
        }
    }
    fun handleType(taskKey: String): Boolean {
        if (state.value.member?.role !in listOf(Role.owner, Role.family)) return false
        return withPending(BusyKey.Card(taskKey)) { write("/api/tasks/${requireSafeId(taskKey)}/complete-all",
            body = JSONObject().put("request_id", requestId("type:$taskKey")), refreshSchedulesAfter = true, receipts = true) { result ->
            val events = JSONObject(result).getJSONArray("events").mapObjects { it }
            patchEvents(events, emptyMap())
            requestIds.remove("type:$taskKey")
            batchHandled(events)
        } }
    }
    private fun batchHandled(events: List<JSONObject>) {
        val event = events.firstOrNull()?.let(::parseEvent)
        mutable.value = mutable.value.copy(receipt = if (event == null) Receipt("No tasks were due") else receiptFor(event)?.let { Receipt(it.text, event) })
    }
    fun saveSettings(settings: TaskSettings): Boolean {
        if (state.value.member?.role == Role.sitter) return false
        return write("/api/tasks/settings", "PUT", settings.json(), refreshAfter = true) {}
    }
    private enum class Patch { Complete, Postpone }
    // Until the next refresh replaces the lists, a write's response patches only the rows it acted on.
    private fun patchEvents(events: List<JSONObject>, tasks: Map<String, JSONObject>, mode: Patch = Patch.Complete, acting: String? = null) {
        val member = state.value.member ?: return
        val patched = mutableMapOf<String, String>()
        fun JSONObject.pendingFor(event: JSONObject) = completedEventId() == null &&
            getString("plant_id") == event.getString("plant_id") && taskKey() == event.taskKey()
        fun JSONObject.target(): JSONObject? = if (acting != null) events.single().takeIf { occurrence() == acting } else events.firstOrNull { pendingFor(it) }
        val snapshots = listOf("today", "upcoming").mapNotNull { view ->
            val row = cache.get(member.id, view) ?: return@mapNotNull null
            val json = JSONObject(row.json)
            fun patch(rows: JSONArray) = JSONArray(rows.mapObjects { it }.filter { task ->
                val before = task.occurrence()
                val event = task.target()
                if (event != null) {
                    tasks[task.taskKey()]?.let { next -> listOf("status", "dueOn", "intervalDays", "lateDays", "basedOn", "postponed").forEach { field ->
                        if (next.has(field)) task.put(field, next.get(field)) else task.remove(field)
                    } }
                    // A snoozed or skipped task is no longer due, so it leaves Today at once.
                    if (mode == Patch.Postpone && view == "today") return@filter false
                    if (mode == Patch.Complete) task.put("completed_event", event)
                }
                if (task.occurrence() != before) patched[before] = task.occurrence()
                true
            })
            json.getJSONArray("sections").mapObjects { it }.forEach { it.put("tasks", patch(it.getJSONArray("tasks"))) }
            val groups = json.getJSONObject("groups")
            groups.keys().asSequence().toList().forEach { groups.put(it, patch(groups.getJSONArray(it))) }
            view to json.toString()
        }.toMap()
        cache.save(member.id, snapshots, state.value.lastUpdated ?: 0)
        loadCached()
        mutable.value = mutable.value.copy(openedOccurrence = mutable.value.openedOccurrence?.let { patched[it] ?: it })
    }
    private fun write(path: String, method: String = "POST", body: JSONObject?, refreshAfter: Boolean = false, plantsScreen: Boolean = false,
                      refreshSchedulesAfter: Boolean = false, receipts: Boolean = false, applied: (String) -> Unit): Boolean = synchronized(workLock) {
        val epoch = synchronized(monitor) {
            val allowed = if (plantsScreen) plantMutable.value.enabled(state.value.member, state.value.updateRequired) else state.value.writable
            if (!allowed || !connected()) {
                if (!connected()) connectivityChanged(false)
                return false
            }
            // A care write busies only its own control and reports through receipts, so it leaves the plant screen's form gate alone.
            if (plantsScreen && !receipts) plantMutable.value = plantMutable.value.copy(saving = true, errors = emptyMap())
            else mutable.value = mutable.value.copy(message = null)
            generation
        }
        try {
            val result = read(path, method, body)
            synchronized(monitor) { if (epoch != generation) return false; applied(result) }
        } catch (error: Exception) {
            if (plantsScreen) plantFailed(error, epoch, writing = true) else failed(error, epoch)
            if (receipts) synchronized(monitor) { if (epoch == generation) {
                mutable.value = mutable.value.copy(message = null, receipt = Receipt((error as? ApiFailure)?.message ?: "Could not reach Fytorium. Try again."))
                if (plantsScreen) plantMutable.value = plantMutable.value.copy(errors = plantMutable.value.errors - "_form")
            } }
            return false
        }
        finally { synchronized(monitor) { if (epoch == generation && plantsScreen && !receipts) plantMutable.value = plantMutable.value.copy(saving = false) } }
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
    fun undoHistory(event: CareEvent): Boolean = undo(event, plantsScreen = true, busy = BusyKey.History(event.id))
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

internal fun JSONObject.taskKey(): String = taskKey(nullable("custom_task_id"), nullable("task_type"))
private fun JSONObject.completedEventId(): String? = optJSONObject("completed_event")?.getString("id")
private fun JSONObject.occurrence(): String = completedEventId() ?: taskOccurrence(getString("plant_id"), taskKey(), nullable("dueOn"), nullable("postponed"))
