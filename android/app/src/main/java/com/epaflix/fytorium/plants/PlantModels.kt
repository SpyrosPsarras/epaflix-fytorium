package com.epaflix.fytorium.plants

import com.epaflix.fytorium.mapObjects
import com.epaflix.fytorium.nullable
import org.json.JSONObject

data class PlantsRequestState(val online: Boolean = true, val refreshing: Boolean = false, val saving: Boolean = false,
    val blocked: Boolean = false, val errors: Map<String, String> = emptyMap()) {
    fun enabled(member: com.epaflix.fytorium.Member?, updateRequired: Boolean) =
        online && member != null && member.role != com.epaflix.fytorium.Role.sitter && !updateRequired && !saving && !blocked
}

// Match acceptance belongs to the server, including partial names and all languages.
data class SpeciesCandidate(val key: Long, val scientificName: String, val canonicalName: String, val rank: String,
    val matchType: String?, val confidence: Int?, val requiresConfirmation: Boolean, val matchedName: String?, val matchedLanguage: String?,
    val score: Double? = null, val identificationName: String? = null, val commonNames: List<String> = emptyList())
data class SpeciesSearch(val id: String, val candidates: List<SpeciesCandidate>)
fun parseSpeciesSearch(json: String): SpeciesSearch {
    val j = JSONObject(json)
    return SpeciesSearch(j.getString("search_id"), j.getJSONArray("candidates").mapObjects {
        SpeciesCandidate(it.getLong("gbif_key"), it.getString("scientific_name"), it.getString("canonical_name"), it.getString("rank"),
            it.nullable("match_type"), if (it.isNull("confidence")) null else it.getInt("confidence"),
            it.getBoolean("requires_confirmation"), it.nullable("matched_name"), it.nullable("matched_language"),
            if (it.isNull("score")) null else it.getDouble("score"), it.nullable("identification_name"),
            it.optJSONArray("common_names")?.let { names -> (0 until names.length()).map { n -> names.getString(n) } }.orEmpty())
    })
}
data class PlantOptions(val choices: Map<String, List<String>>, val labels: Map<String, String>, val numbers: List<String>,
    val booleans: List<String>, val health: List<String>, val languages: Map<String, String>)
fun parsePlantOptions(json: String): PlantOptions {
    val j = JSONObject(json); val p = j.getJSONObject("plant"); val c = p.getJSONObject("choices")
    fun strings(key: String) = p.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun labels(obj: JSONObject) = obj.keys().asSequence().associateWith { obj.getString(it) }
    return PlantOptions(c.keys().asSequence().associateWith { key -> c.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } } },
        labels(p.getJSONObject("labels")), strings("numbers"), strings("booleans"), strings("health"), labels(j.getJSONObject("nameLanguages")))
}
data class PlantSite(val id: String, val name: String)
data class SpeciesName(val id: String, val name: String, val language: String, val source: String)
data class Species(val key: Long, val scientificName: String, val names: List<SpeciesName>, val fetching: Boolean, val failed: Boolean)
fun parseSpecies(json: String): Species {
    val j = JSONObject(json)
    return Species(j.getLong("gbif_key"), j.getString("scientific_name"), j.getJSONArray("names").mapObjects {
        SpeciesName(it.getString("id"), it.getString("name"), it.getString("language"), it.getString("source"))
    }, j.optBoolean("name_fetch_needed"), j.optBoolean("name_fetch_failed"))
}
