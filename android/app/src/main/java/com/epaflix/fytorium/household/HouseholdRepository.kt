package com.epaflix.fytorium.household

import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.Role
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class HouseholdArea(val title: String) { Members("Members"), Tasks("Custom tasks") }
data class HouseholdMember(val id: String, val name: String, val role: String, val plantIds: Set<String>,
    val roleOptions: List<String>, val canRemove: Boolean)
data class HouseholdInvite(val id: String, val role: String, val expires: String, val plantIds: Set<String>)
data class CreatedInvite(val id: String, val role: String, val expiresAt: String, val link: String)
data class TaskTarget(val id: String, val name: String, val kind: String)
data class HouseholdState(val loaded: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val blocked: Boolean = false, val updateRequired: Boolean = false, val errors: Map<String, String> = emptyMap(),
    val plants: List<TaskTarget> = emptyList(),
    val members: List<HouseholdMember> = emptyList(), val invites: List<HouseholdInvite> = emptyList(),
    val readError: String? = null, val updatedAt: Long? = null)
private class HouseholdFailure(val status: Int, val errors: Map<String, String>) : Exception()
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.strings(key: String) = getJSONArray(key).let { list -> (0 until list.length()).map { list.getString(it) } }

// One screen and member own this state. The last members and invites stay in the care cache for offline reading (W5b-P27); invite links never enter it.
class HouseholdRepository(private val api: ApiClient, private val area: HouseholdArea,
    private val role: () -> Role?, private val connected: () -> Boolean,
    private val cached: (String) -> Pair<String, Long>? = { null }, private val keep: (String, String) -> Long? = { _, _ -> null }) {
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
    private fun plants(): List<TaskTarget> = JSONArray(request("/api/plants")).objects().map { TaskTarget(it.getString("id"), it.getString("display_name"), "plant") }
    private fun load() = loadMembers()
    private fun parseMembers(json: JSONObject) = JSONArray(json.getString("members")).objects().map {
        HouseholdMember(it.getString("id"), it.getString("display_name"), it.getString("role"), it.strings("plant_ids").toSet(), it.strings("role_options"), it.getBoolean("can_remove"))
    } to JSONArray(json.getString("invites")).objects().map {
        HouseholdInvite(it.getString("id"), it.getString("role"), it.getString("expires_at"), it.strings("plant_ids").toSet())
    }
    private fun loadMembers() {
        val plants = plants()
        val json = JSONObject().put("members", request("/api/household/members")).put("invites", request("/api/household/invites"))
        val (members, invites) = parseMembers(json)
        if (allowed()) { val at = keep("members", json.toString()) ?: System.currentTimeMillis()
            mutable.value = state.value.copy(plants = plants, members = members, invites = invites, loaded = true, blocked = false, readError = null, updatedAt = at) }
    }
    @Synchronized fun refresh(): Boolean {
        if (!state.value.loaded && allowed()) cached("members")?.let { (json, at) ->
            runCatching { parseMembers(JSONObject(json)) }.getOrNull()?.let { (members, invites) ->
                mutable.value = state.value.copy(members = members, invites = invites, loaded = true, updatedAt = at) } }
        return run(false) { load() }.also { if (it) mutable.value = state.value.copy(readError = null) }
    }
    @Synchronized fun createInvite(role: String, plants: Set<String>): CreatedInvite? {
        var created: CreatedInvite? = null
        val success = run(true) { created = JSONObject(request("/api/household/invites", "POST",
            JSONObject().put("role", role).put("plant_ids", JSONArray(if (role == "sitter") plants.toList() else emptyList<String>())))).let {
                CreatedInvite(it.getString("id"), it.getString("role"), it.getString("expires_at"), it.getString("link")) } }
        if (success) refresh()
        return if (success && allowed()) created else null
    }
    @Synchronized fun saveMember(member: HouseholdMember, role: String, plants: Set<String>) = command("/api/household/members/${id(member.id)}", "PUT",
        JSONObject().put("plant_ids", JSONArray(if (role == "sitter") plants.toList() else emptyList<String>())).apply { if (role != member.role || role == "family") put("role", role) })
    @Synchronized fun removeMember(id: String) = command("/api/household/members/${id(id)}", "DELETE")
    @Synchronized fun revokeInvite(id: String) = command("/api/household/invites/${id(id)}", "DELETE").also { if (it) InviteLinks.drop(id) }
    private fun command(path: String, method: String, body: JSONObject? = null): Boolean = run(true) { request(path, method, body) }.also { if (it) refresh() }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = HouseholdState(); return false }
        if (write && (!state.value.loaded || state.value.blocked || state.value.updateRequired)) return false
        mutable.value = state.value.copy(loading = !write, saving = write, errors = if (write) emptyMap() else state.value.errors)
        return try {
            if (!connected()) throw IOException()
            action()
            allowed()
        } catch (error: Exception) {
            val errors = if (error is HouseholdFailure) error.errors else mapOf("_form" to "Could not reach Fytorium. Try again.")
            // A failed read belongs to the page's line; only a write's failure goes into its form.
            if (allowed()) mutable.value = state.value.copy(
                errors = if (write) errors else state.value.errors,
                readError = if (write) state.value.readError else errors.values.firstOrNull(),
                blocked = error !is HouseholdFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = state.value.updateRequired || (error is HouseholdFailure && error.status == 426))
            false
        } finally { mutable.value = if (allowed()) state.value.copy(loading = false, saving = false) else HouseholdState() }
    }
}
