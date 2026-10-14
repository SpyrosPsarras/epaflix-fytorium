package com.epaflix.fytorium.planta

import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.mapObjects
import com.epaflix.fytorium.nullable
import com.epaflix.fytorium.plants.SpeciesCandidate
import com.epaflix.fytorium.plants.SpeciesSearch
import com.epaflix.fytorium.plants.parseSpeciesSearch
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val endpoint = "/api/import/planta"
data class ImportRow(val id: String, val name: String, val scientificName: String, val site: String,
    val cultivar: String?, val pot: String, val size: String, val soil: String, val care: Map<String, String>,
    val status: String, val coverStatus: String, val coverError: String?, val plantId: String?,
    val candidates: List<SpeciesCandidate>, val selectedName: String?, val canCommit: Boolean, val canReview: Boolean)
data class PlantaState(val rows: List<ImportRow> = emptyList(), val loaded: Boolean = false,
    val loading: Boolean = false, val saving: Boolean = false, val blocked: Boolean = false,
    val updateRequired: Boolean = false, val error: String? = null, val complete: Boolean = false,
    val searchRow: String? = null, val search: SpeciesSearch? = null)
private class PlantaFailure(val status: Int, val display: String) : Exception()
private fun parseRows(text: String) = JSONArray(text).mapObjects { row ->
    val mapped = row.getJSONObject("mapped")
    val candidates = parseSpeciesSearch(JSONObject().put("search_id", "").put("candidates", row.getJSONArray("candidates")).toString()).candidates
    val care = mapped.getJSONObject("care")
    ImportRow(row.getString("id"), mapped.nullable("nickname") ?: mapped.getString("scientific_name"),
        mapped.getString("scientific_name"), mapped.getString("site_name"), mapped.nullable("cultivar"),
        mapped.getString("pot_type"), mapped.nullable("pot_size_cm") ?: "unknown", mapped.getString("soil_type"),
        care.keys().asSequence().associateWith { care.getString(it) }, row.getString("status"), row.getString("cover_status"),
        row.nullable("cover_error"), row.nullable("plant_id"), candidates, row.optJSONObject("selected")?.getString("canonical_name"),
        row.getBoolean("can_commit"), row.getBoolean("can_review"))
}

// The server owns staging. No code, token, review or search receipt is persisted here.
class PlantaRepository(private val api: ApiClient, private val allowed: () -> Boolean, private val connected: () -> Boolean) {
    private val mutable = MutableStateFlow(PlantaState())
    val state = mutable.asStateFlow()
    private fun request(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap(), sensitive: Boolean = false): String =
        api.request(path, method = method, body = body, query = query).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Never relay the body of a code exchange, including a provider's credential echo.
                val message = if (sensitive) when (response.code) {
                    422 -> "Planta supplied invalid plant data, or the app code is empty. Fetch again with a new code."
                    502 -> "Planta refused authorization or returned invalid data. Start again with a new app code."
                    else -> "Planta did not respond. Refresh, then try again with a new app code."
                } else runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw PlantaFailure(response.code, message ?: "Could not reach the server. Refresh to retry.")
            }
            text
        }
    private fun load() {
        val rows = parseRows(request(endpoint))
        if (allowed()) mutable.value = state.value.copy(rows = rows, loaded = true, blocked = false)
    }
    @Synchronized fun refresh() = run(false) { load() }
    @Synchronized fun fetch(code: String) = run(true) {
        val rows = parseRows(request(endpoint, "POST", JSONObject().put("code", code), sensitive = true))
        if (allowed()) mutable.value = state.value.copy(rows = rows, loaded = true, blocked = false,
            complete = false, searchRow = null, search = null)
    }
    @Synchronized fun confirm(rowId: String, key: Long, searchId: String? = null): Boolean = review(rowId,
        JSONObject().put("gbif_key", key).put("confirmed", true).apply { searchId?.let { put("search_id", it) } })
    @Synchronized fun skip(rowId: String): Boolean = review(rowId, JSONObject().put("skip", true))
    private fun review(rowId: String, body: JSONObject): Boolean {
        if (!allowed()) { mutable.value = PlantaState(); return false }
        if (state.value.rows.none { it.id == rowId && it.canReview }) return false
        return run(true) {
            require(rowId.matches(Regex("[A-Za-z0-9_-]+")))
            request("$endpoint/$rowId", "PUT", body)
            // Eligibility is household-wide. Reload it rather than computing it after a row update.
            load()
            if (allowed()) mutable.value = state.value.copy(searchRow = null, search = null, complete = false)
        }
    }
    @Synchronized fun search(rowId: String, query: String): Boolean {
        if (!allowed()) { mutable.value = PlantaState(); return false }
        if (state.value.blocked || state.value.updateRequired || state.value.rows.none { it.id == rowId && it.canReview }) return false
        return run(false) {
            mutable.value = state.value.copy(searchRow = rowId, search = null)
            val result = parseSpeciesSearch(request("/api/species/search", query = mapOf("q" to query)))
            if (allowed()) mutable.value = state.value.copy(search = result)
        }
    }
    @Synchronized fun commit(): Boolean {
        if (!allowed()) { mutable.value = PlantaState(); return false }
        if (state.value.rows.none { it.canCommit }) return false
        return run(true) {
            val rows = parseRows(request("$endpoint/commit", "POST"))
            if (allowed()) mutable.value = state.value.copy(rows = rows, complete = true, searchRow = null, search = null)
        }
    }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = PlantaState(); return false }
        if (write && (!state.value.loaded || state.value.blocked || state.value.updateRequired || state.value.saving)) return false
        mutable.value = state.value.copy(loading = !write, saving = write, error = null)
        return try {
            if (!connected()) throw IOException()
            action()
            allowed()
        } catch (error: Exception) {
            if (allowed()) mutable.value = state.value.copy(error = if (error is PlantaFailure) error.display else "Could not reach the server. Your staged plants remain available. Refresh to retry.",
                blocked = error !is PlantaFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = state.value.updateRequired || (error is PlantaFailure && error.status == 426))
            false
        } finally {
            mutable.value = if (allowed()) state.value.copy(loading = false, saving = false) else PlantaState()
        }
    }
}
