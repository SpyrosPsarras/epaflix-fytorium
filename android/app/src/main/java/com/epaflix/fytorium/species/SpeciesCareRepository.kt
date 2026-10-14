package com.epaflix.fytorium.species

import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.mapObjects
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CareCard(val key: Long, val name: String, val profile: JSONObject, val fetches: List<JSONObject>, val outdoor: JSONObject)
data class UnmatchedSpecies(val key: Long, val name: String, val status: String)
data class SpeciesCareState(val card: CareCard? = null, val unmatched: List<UnmatchedSpecies> = emptyList(),
    val loaded: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val blocked: Boolean = false, val updateRequired: Boolean = false, val error: String? = null, val message: String? = null) {
    val pendingFetch get() = error == null && !blocked && !updateRequired &&
        card?.fetches?.any { it.optString("status") in listOf("pending", "fetching") } == true
    val writable get() = loaded && !saving && !blocked && !updateRequired
}
private class CareFailure(val status: Int, val text: String) : Exception()

// No Room cache. A route owns these reads for one member and species.
class SpeciesCareRepository(private val api: ApiClient, private val key: Long?, private val allowed: () -> Boolean,
    private val connected: () -> Boolean) {
    init { require(key == null || key in 1..2147483647L) }
    private val mutable = MutableStateFlow(SpeciesCareState())
    val state = mutable.asStateFlow()
    fun clearMessage() { mutable.value = mutable.value.copy(error = null, message = null) }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String =
        api.request(path, method = method, body = body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw CareFailure(response.code,
                runCatching { JSONObject(text).optString("message").takeIf { it.isNotBlank() } }.getOrNull()
                    ?: "Could not reach the server. Try again.")
            text
        }
    private fun load() {
        if (key == null) {
            val rows = JSONArray(request("/api/species/unmatched")).mapObjects {
                UnmatchedSpecies(it.getLong("gbif_key"), it.getString("canonical_name"), it.getString("status"))
            }
            if (allowed()) mutable.value = mutable.value.copy(unmatched = rows, loaded = true, blocked = false)
        } else {
            val json = JSONObject(request("/api/species/$key/care"))
            val outdoor = JSONObject(request("/api/species/$key/outdoor-months"))
            val card = CareCard(key, json.getJSONObject("species").getString("scientific_name"), json.getJSONObject("profile"),
                json.getJSONArray("fetches").mapObjects { it }, outdoor)
            if (allowed()) mutable.value = mutable.value.copy(card = card, loaded = true, blocked = false)
        }
    }
    @Synchronized fun refresh() = run(write = false) { load() }
    @Synchronized fun override(field: String, value: Any): Boolean = change("overrides/${safeField(field)}", "PUT", JSONObject().put("value", value), "Override saved.")
    @Synchronized fun reset(field: String): Boolean = change("overrides/${safeField(field)}", "DELETE", null, "Override removed.")
    @Synchronized fun refreshCare(): Boolean = change("refresh", "POST", null, "Refresh queued. Existing overrides are kept.")
    @Synchronized fun retryMatch(speciesKey: Long = requireNotNull(key)): Boolean {
        require(speciesKey in 1..2147483647L)
        return run(write = true, fallback = "Could not retry. An owner and configured credentials are required.") {
            request("/api/species/$speciesKey/care/retry-match", "POST")
            if (allowed()) mutable.value = mutable.value.copy(message = "Match retry queued.")
        }.also { if (it) refresh() }
    }
    private fun safeField(field: String): String { require(careLabels.containsKey(field)); return field }
    private fun change(path: String, method: String, body: JSONObject?, message: String): Boolean = run(write = true) {
        request("/api/species/${requireNotNull(key)}/care/$path", method, body)
        if (allowed()) mutable.value = mutable.value.copy(message = message)
    }.also { if (it) refresh() }
    private fun run(write: Boolean, fallback: String? = null, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = SpeciesCareState(); return false }
        if (write && !state.value.writable) return false
        mutable.value = mutable.value.copy(loading = !write, saving = write, error = null)
        return try {
            if (!connected()) throw IOException()
            action()
            allowed()
        } catch (error: Exception) {
            if (allowed()) mutable.value = mutable.value.copy(error = fallback ?: if (error is CareFailure) error.text else "Could not reach the server. Try again.",
                blocked = error !is CareFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = state.value.updateRequired || (error is CareFailure && error.status == 426))
            false
        } finally {
            mutable.value = if (allowed()) mutable.value.copy(loading = false, saving = false) else SpeciesCareState()
        }
    }
}

// Labels and order mirror src/lib/care.ts. Values, sources and rules are server-owned.
internal val careLabels = linkedMapOf(
    "wateringIntervalDays" to "Watering interval in days", "fertilizingIntervalDays" to "Fertilizing interval in days",
    "light" to "Light", "humidityNeed" to "Humidity need", "temperatureRangeC" to "Temperature range in °C",
    "soilMoistureRangePercent" to "Soil moisture sensor range in %", "careText" to "Source care text",
    "hardinessC" to "Minimum survival temperature in °C", "hardiness" to "Hardiness zones", "poisonousToPets" to "Poisonous to pets",
    "poisonousToHumans" to "Poisonous to humans", "cycle" to "Lifecycle", "dimensions" to "Size in cm",
    "pruningMonths" to "Pruning months", "pests" to "Common pests", "indoor" to "Indoor")
internal val careChoices = mapOf("light" to listOf("full_shade", "part_shade", "part_sun", "full_sun"),
    "humidityNeed" to listOf("low", "medium", "high"), "cycle" to listOf("annual", "biennial", "perennial"),
    "indoor" to listOf("true", "false"), "poisonousToPets" to listOf("true", "false"), "poisonousToHumans" to listOf("true", "false"))
internal val monthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
internal fun displayCareValue(field: String, value: Any?): String = when (value) {
    null, JSONObject.NULL -> "unknown"
    is Boolean -> if (value) "Yes" else "No"
    is JSONObject -> "${value.get("min")} to ${value.get("max") }"
    is JSONArray -> if (value.length() == 0) "None reported" else (0 until value.length()).joinToString(", ") {
        if (field == "pruningMonths") monthNames[value.getInt(it) - 1] else value.get(it).toString()
    }
    else -> value.toString().replace('_', ' ')
}
