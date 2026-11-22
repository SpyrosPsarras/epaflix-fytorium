package com.epaflix.fytorium.sites

import com.epaflix.fytorium.ApiClient
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal val temperatureFields = linkedMapOf("warm_min_c" to "Warm minimum °C", "warm_max_c" to "Warm maximum °C",
    "cold_min_c" to "Cold minimum °C", "cold_max_c" to "Cold maximum °C")
internal val siteChoices = linkedMapOf("location" to ("Location" to listOf("indoor", "outdoor")),
    "light" to ("Light" to listOf("full_shade", "part_shade", "part_sun", "full_sun")),
    "humidity" to ("Humidity" to listOf("dry", "normal", "humid")))

data class Site(val id: String? = null, val fields: Map<String, String> = mapOf("name" to "", "location" to "indoor",
    "light" to "part_sun", "humidity" to "normal"), val growLight: Boolean = false, val draft: Boolean = false,
    val plantCount: Int = 0, val importedIncomplete: Boolean = false) : java.io.Serializable {
    fun json(): JSONObject = JSONObject().apply {
        for (field in listOf("name", "location", "light", "humidity")) put(field, fields[field].orEmpty())
        put("grow_light", growLight); put("draft", draft)
        for (field in temperatureFields.keys) {
            val text = fields[field].orEmpty().trim()
            // Leave validation to the server, including invalid numeric input and reversed bounds.
            val number = text.toDoubleOrNull()?.takeIf { it.isFinite() }
            put(field, if (text.isEmpty()) JSONObject.NULL else number ?: text)
        }
    }
}
private fun parseSite(json: JSONObject) = Site(json.getString("id"),
    (listOf("name", "location", "light", "humidity") + temperatureFields.keys).associateWith {
        if (json.isNull(it)) "" else json.get(it).toString()
    }, json.getBoolean("grow_light"), json.getBoolean("draft"), json.optInt("plant_count"), json.optBoolean("imported_conditions_incomplete"))

data class SiteState(val sites: List<Site> = emptyList(), val busy: Boolean = false, val loaded: Boolean = false,
    val blocked: Boolean = false, val updateRequired: Boolean = false, val errors: Map<String, String> = emptyMap(), val readError: String? = null,
    val updatedAt: Long? = null, val created: String? = null)
private class SiteFailure(val status: Int, val errors: Map<String, String>) : Exception()

// The screen owns this repository for one member; the last list is kept in the care cache so the pages read offline (W5b-P27).
private const val cacheKey = "site-list"
class SiteRepository(private val api: ApiClient, private val allowed: () -> Boolean, private val connected: () -> Boolean,
    private val cached: (String) -> Pair<String, Long>? = { null }, private val keep: (String, String) -> Long? = { _, _ -> null }) {
    private val mutable = MutableStateFlow(SiteState())
    val state = mutable.asStateFlow()
    fun clearErrors() { mutable.value = mutable.value.copy(errors = emptyMap()) }
    private fun requireSafeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return id
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null,
        fallback: String = "Could not reach the server. Try again."): String =
        api.request(path, method = method, body = body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val errors = json?.optJSONObject("errors")?.let { fields -> fields.keys().asSequence().associateWith { fields.getString(it) } }
                    ?: mapOf("_form" to (json?.optString("message")?.takeIf { it.isNotBlank() } ?: fallback))
                throw SiteFailure(response.code, errors)
            }
            text
        }
    private fun parse(text: String) = JSONArray(text).let { list -> (0 until list.length()).map { parseSite(list.getJSONObject(it)) } }
    private fun load() {
        val text = request("/api/sites")
        val sites = parse(text)
        if (allowed()) { val at = keep(cacheKey, text) ?: System.currentTimeMillis(); mutable.value = mutable.value.copy(sites = sites, loaded = true, blocked = false, readError = null, updatedAt = at) }
    }
    @Synchronized fun refresh(): Boolean {
        if (!state.value.loaded && allowed()) cached(cacheKey)?.let { (json, at) -> runCatching { parse(json) }.getOrNull()?.let { mutable.value = mutable.value.copy(sites = it, loaded = true, updatedAt = at) } }
        return run(write = false) { load() }
    }
    @Synchronized fun save(site: Site, created: (String) -> Unit = {}): Boolean = run(write = true) {
        val saved = request(site.id?.let { "/api/sites/${requireSafeId(it)}" } ?: "/api/sites", if (site.id == null) "POST" else "PUT", site.json(),
            fallback = "Could not save the site. Try again.")
        // The site exists once the server accepted it, so a reply without its id only leaves it unselected.
        if (site.id == null) runCatching { JSONObject(saved).getString("id") }.onSuccess { mutable.value = mutable.value.copy(created = it); created(it) }
    }.also { if (it) refresh() }
    @Synchronized fun delete(id: String): Boolean = run(write = true) {
        request("/api/sites/${requireSafeId(id)}", "DELETE", fallback = "Could not delete the site. Try again.")
    }.also { if (it) refresh() }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = SiteState(); return false }
        if (write && (!state.value.loaded || state.value.blocked || state.value.updateRequired)) return false
        // A read, also the retry once Fytorium answers again, leaves the form's field and save errors to the next save.
        mutable.value = mutable.value.copy(busy = true, errors = if (write) emptyMap() else mutable.value.errors)
        return try {
            if (!connected()) throw IOException()
            action()
            allowed()
        } catch (error: Exception) {
            val errors = if (error is SiteFailure) error.errors else mapOf("_form" to "Could not reach the server. Try again.")
            // A failed read belongs to the page, so it stays until a read succeeds; only a write's failure goes into its form.
            if (allowed()) mutable.value = mutable.value.copy(errors = if (write) errors else mutable.value.errors,
                readError = if (write) state.value.readError else errors.values.firstOrNull() ?: "Could not reach the server. Try again.",
                blocked = error !is SiteFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = state.value.updateRequired || (error is SiteFailure && error.status == 426))
            false
        } finally {
            mutable.value = if (allowed()) mutable.value.copy(busy = false) else SiteState()
        }
    }
}
