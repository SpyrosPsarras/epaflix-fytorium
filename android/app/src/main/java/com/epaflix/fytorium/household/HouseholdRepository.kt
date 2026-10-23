package com.epaflix.fytorium.household

import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.Role
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal val feedLabels = linkedMapOf("late_tasks" to "Late tasks", "current_weather" to "Current weather", "weather_alerts" to "Weather alerts", "plant_warnings" to "Plant warnings")
enum class HouseholdArea(val title: String) { Settings("Household settings"), Members("Members"), Tasks("Custom tasks") }
data class HouseholdMember(val id: String, val name: String, val role: String, val plantIds: Set<String>,
    val roleOptions: List<String>, val canRemove: Boolean)
data class HouseholdInvite(val id: String, val role: String, val expires: String, val plantIds: Set<String>)
data class TaskTarget(val id: String, val name: String, val kind: String)
data class CustomTask(val id: String? = null, val name: String = "", val interval: String = "")
data class HouseholdState(val loaded: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val blocked: Boolean = false, val updateRequired: Boolean = false, val errors: Map<String, String> = emptyMap(),
    val plants: List<TaskTarget> = emptyList(), val sites: List<TaskTarget> = emptyList(),
    val members: List<HouseholdMember> = emptyList(), val invites: List<HouseholdInvite> = emptyList(),
    val target: TaskTarget? = null, val tasks: List<CustomTask> = emptyList(),
    val location: Map<String, String> = emptyMap(), val feed: Map<String, Boolean> = emptyMap())
private class HouseholdFailure(val status: Int, val errors: Map<String, String>) : Exception()
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.strings(key: String) = getJSONArray(key).let { list -> (0 until list.length()).map { list.getString(it) } }

// One screen and member own this in-memory state. Settings and invite links never enter Room.
class HouseholdRepository(private val api: ApiClient, private val area: HouseholdArea,
    private val role: () -> Role?, private val connected: () -> Boolean) {
    private val mutable = MutableStateFlow(HouseholdState())
    val state = mutable.asStateFlow()
    fun clearErrors() { mutable.value = mutable.value.copy(errors = emptyMap()) }
    private fun allowed() = role()?.let { it != Role.sitter && (area != HouseholdArea.Members || it == Role.owner) } == true
    private fun id(id: String): String { require(id.matches(Regex("[A-Za-z0-9_-]+"))); return id }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        api.request(path, method = method, body = body, query = query).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val errors = json?.optJSONObject("errors")?.let { fields -> fields.keys().asSequence().associateWith { fields.getString(it) } }
                    ?: mapOf("_form" to (json?.optString("message")?.takeIf { it.isNotBlank() } ?: "Could not reach Fytorium. Try again."))
                throw HouseholdFailure(response.code, errors)
            }
            text
        }
    private fun plants(includeGraveyard: Boolean = false): List<TaskTarget> {
        val plants = JSONArray(request("/api/plants")).objects()
        val graveyard = if (includeGraveyard) JSONArray(request("/api/plants", query = mapOf("status" to "graveyard"))).objects() else emptyList()
        return (plants + graveyard).map { TaskTarget(it.getString("id"), it.getString("display_name") + if (it.getString("status") == "graveyard") " · Graveyard" else "", "plant") }
    }
    private fun load() {
        when (area) {
            HouseholdArea.Settings -> {
                val location = JSONObject(request("/api/household/location")).optJSONObject("location")
                val feed = JSONObject(request("/api/feed/settings"))
                if (allowed()) mutable.value = state.value.copy(loaded = true, blocked = false,
                    location = listOf("latitude", "longitude", "label").associateWith { if (location == null || location.isNull(it)) "" else location.get(it).toString() },
                    feed = feedLabels.keys.associateWith { feed.getBoolean(it) })
            }
            HouseholdArea.Members -> loadMembers()
            HouseholdArea.Tasks -> {
                val plants = plants(includeGraveyard = true)
                val sites = JSONArray(request("/api/sites")).objects().map { TaskTarget(it.getString("id"), it.getString("name"), "site") }
                if (allowed()) mutable.value = state.value.copy(plants = plants, sites = sites)
                state.value.target?.let { loadTasks(it) }
                if (allowed()) mutable.value = state.value.copy(loaded = true, blocked = false)
            }
        }
    }
    private fun loadMembers() {
        val plants = plants()
        val members = JSONArray(request("/api/household/members")).objects().map {
            HouseholdMember(it.getString("id"), it.getString("display_name"), it.getString("role"), it.strings("plant_ids").toSet(), it.strings("role_options"), it.getBoolean("can_remove"))
        }
        val invites = JSONArray(request("/api/household/invites")).objects().map {
            HouseholdInvite(it.getString("id"), it.getString("role"), it.getString("expires_at").take(10), it.strings("plant_ids").toSet())
        }
        if (allowed()) mutable.value = state.value.copy(plants = plants, members = members, invites = invites, loaded = true, blocked = false)
    }
    @Synchronized fun refresh() = run(false) { load() }
    @Synchronized fun saveLocation(fields: Map<String, String>): Boolean {
        if (role() != Role.owner) return false
        val location = if (fields["latitude"].isNullOrBlank() && fields["longitude"].isNullOrBlank()) JSONObject.NULL else JSONObject().apply {
            for (key in listOf("latitude", "longitude")) {
                val text = fields[key].orEmpty().trim()
                put(key, text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: if (text.isBlank()) JSONObject.NULL else text)
            }
            put("label", fields["label"].orEmpty())
        }
        return command("/api/household/location", "PUT", JSONObject().put("location", location))
    }
    @Synchronized fun saveFeed(fields: Map<String, Boolean>) = command("/api/feed/settings", "PUT", JSONObject(fields))
    private fun loadTasks(target: TaskTarget) {
        val tasks = JSONArray(request("/api/custom-tasks", query = mapOf("${target.kind}_id" to id(target.id)))).objects()
            .map { CustomTask(it.getString("id"), it.getString("name"), it.getInt("interval_days").toString()) }
        if (allowed()) mutable.value = state.value.copy(target = target, tasks = tasks)
    }
    @Synchronized fun selectTarget(target: TaskTarget) = run(false) {
        // Clear old rows before fetching another target so a failed read cannot write to the old target.
        mutable.value = state.value.copy(target = target, tasks = emptyList(), blocked = true)
        loadTasks(target)
        if (allowed()) mutable.value = state.value.copy(blocked = false)
    }
    @Synchronized fun saveTask(task: CustomTask): Boolean {
        val target = state.value.target ?: return false
        val body = JSONObject().put("name", task.name).put("interval_days", task.interval.toIntOrNull() ?: task.interval)
        if (task.id == null) body.put("${target.kind}_id", target.id)
        return run(true) { request(task.id?.let { "/api/custom-tasks/${id(it)}" } ?: "/api/custom-tasks", if (task.id == null) "POST" else "PUT", body) }
            .also { if (it) run(false) { loadTasks(target) } }
    }
    @Synchronized fun deleteTask(task: CustomTask): Boolean {
        val target = state.value.target ?: return false
        val taskId = task.id ?: return false
        return run(true) { request("/api/custom-tasks/${id(taskId)}", "DELETE") }.also { if (it) run(false) { loadTasks(target) } }
    }
    @Synchronized fun createInvite(role: String, plants: Set<String>): String? {
        var link: String? = null
        val success = run(true) { link = JSONObject(request("/api/household/invites", "POST",
            JSONObject().put("role", role).put("plant_ids", JSONArray(if (role == "sitter") plants.toList() else emptyList<String>())))).getString("link") }
        if (success) refresh()
        return if (success && allowed()) link else null
    }
    @Synchronized fun saveMember(member: HouseholdMember, role: String, plants: Set<String>) = command("/api/household/members/${id(member.id)}", "PUT",
        JSONObject().put("plant_ids", JSONArray(if (role == "sitter") plants.toList() else emptyList<String>())).apply { if (role != member.role || role == "family") put("role", role) })
    @Synchronized fun removeMember(id: String) = command("/api/household/members/${id(id)}", "DELETE")
    @Synchronized fun revokeInvite(id: String) = command("/api/household/invites/${id(id)}", "DELETE")
    private fun command(path: String, method: String, body: JSONObject? = null): Boolean = run(true) { request(path, method, body) }.also { if (it) refresh() }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = HouseholdState(); return false }
        if (write && (!state.value.loaded || state.value.blocked || state.value.updateRequired)) return false
        mutable.value = state.value.copy(loading = !write, saving = write, errors = emptyMap())
        return try {
            if (!connected()) throw IOException()
            action()
            allowed()
        } catch (error: Exception) {
            if (allowed()) mutable.value = state.value.copy(
                errors = if (error is HouseholdFailure) error.errors else mapOf("_form" to "Could not reach Fytorium. Try again."),
                blocked = error !is HouseholdFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = state.value.updateRequired || (error is HouseholdFailure && error.status == 426))
            false
        } finally { mutable.value = if (allowed()) state.value.copy(loading = false, saving = false) else HouseholdState() }
    }
}
