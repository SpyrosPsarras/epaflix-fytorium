package com.epaflix.fytorium.feed

import com.epaflix.fytorium.Member
import com.epaflix.fytorium.Role
import com.epaflix.fytorium.batchReceipt
import com.epaflix.fytorium.mapObjects
import com.epaflix.fytorium.nullable
import com.epaflix.fytorium.shortDate
import java.time.LocalDate
import org.json.JSONObject

val feedLabels = linkedMapOf("late_tasks" to "Late tasks", "current_weather" to "Current weather",
    "weather_alerts" to "Weather alerts", "plant_warnings" to "Plant warnings")
data class FeedWarning(val kind: String, val text: String, val reason: String, val inputs: List<String>)
data class FeedCard(val id: String, val kind: String, val href: String, val text: String, val why: List<String>,
                    val rain: Boolean, val warnings: List<FeedWarning>, val startsOn: String?, val frost: Boolean) {
    fun headline(today: String?): String {
        val start = startsOn ?: return text
        val days = today?.let { LocalDate.parse(it).toEpochDay() - LocalDate.parse(start).toEpochDay() }
        return if (rain) listOf("Rain " + when (days) { 0L -> "today"; 1L -> "yesterday"; else -> "on " + shortDate(start) }, text)
            .filter { it.isNotEmpty() }.joinToString(", ")
        else (if (frost) "Frost " else "Cold ") + when { days == null || days < -1 -> "from " + shortDate(start); days == -1L -> "tomorrow"; else -> "today" } + ": " + text
    }
}
private val missingLabels = mapOf("site.light" to "site light", "pot.pot_type" to "pot type", "pot.pot_size_cm" to "pot size",
    "careProfile" to "species care data", "household.location" to "weather location")
private fun warningText(warning: JSONObject, plant: String): String? = when (warning.getString("kind")) {
    "too_dark" -> "Too dark for $plant"
    "too_bright" -> "Too bright for $plant"
    "no_drainage" -> "No drainage for $plant"
    "missing_info" -> warning.getJSONArray("inputs").mapObjects { missingLabels[it.getString("input")] }.filterNotNull().ifEmpty { listOf("information") }
        .let { "Missing " + if (it.size == 1) it.single() else it.dropLast(1).joinToString(", ") + " and " + it.last() } + " for $plant"
    else -> null
}
data class Rain(val plantCount: Int, val precipitationMm: Double, val batchId: String?)
data class FeedWeather(val locationSet: Boolean, val forecastFetchedAt: String?, val rain: Rain?, val lastBatchId: String?,
                       val lastBatchPlantCount: Int? = null) {
    val rainReceipt get() = batchReceipt("rain", lastBatchPlantCount, lastBatchPlantCount)?.text
    val rainUndone get() = batchReceipt("rain", lastBatchPlantCount, lastBatchPlantCount)?.undone
}
data class WeatherLocation(val latitude: Double, val longitude: Double, val label: String?) {
    fun json() = JSONObject().put("latitude", latitude).put("longitude", longitude).put("label", label ?: JSONObject.NULL)
}
data class FeedState(val member: Member? = null, val cards: List<FeedCard> = emptyList(),
                     val weather: FeedWeather? = null, val settings: Map<String, Boolean>? = null,
                     val location: WeatherLocation? = null, val locationLoaded: Boolean = false,
                     val loaded: Boolean = false, val offline: Boolean = false, val unavailable: Boolean = false,
                     val refreshing: Boolean = false, val saving: Boolean = false, val message: String? = null) {
    val writable get() = member != null && member.role != Role.sitter && !offline && !saving
}
// Svelte renders numbers without a fractional suffix for whole values.
internal fun rainAmount(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
private fun unknownFeedKind() { android.util.Log.w("Fytorium", "FEED_UNKNOWN_KIND") }
internal fun parseFeedCard(j: JSONObject): FeedCard? {
    val kind = j.getString("kind")
    var rain = false
    var warnings = emptyList<FeedWarning>()
    val why = mutableListOf<String>()
    var startsOn: String? = null
    var frost = false
    val text = when (kind) {
        "late_tasks" -> {
            val count = j.getInt("count")
            "$count " + if (count == 1) "task is late" else "tasks are late"
        }
        "current_weather" -> {
            j.nullable("unavailableSince")?.let { why.add("Weather unavailable since $it. Showing last good data where available.") }
            val current = j.optJSONObject("current")
            if (current != null) {
                why.add("Today: low ${current.get("minC")} °C, high ${current.get("maxC")} °C.")
                why.add("Open-Meteo · Fetched ${current.getString("fetchedAt")}")
            } else if (j.isNull("unavailableSince")) why.add("Weather is waiting for its background refresh.")
            current?.let { "Today: low ${it.get("minC")} °C, high ${it.get("maxC")} °C" }
                ?: if (j.isNull("unavailableSince")) "Weather not loaded yet" else "Weather unavailable"
        }
        "weather_alerts" -> {
            val event = j.getJSONObject("event")
            val eventKind = event.getString("kind")
            if (eventKind !in listOf("rain", "low_temperature")) { unknownFeedKind(); return null }
            val payload = event.getJSONObject("payload")
            rain = eventKind == "rain"
            startsOn = event.getString("starts_on")
            if (rain) {
                if (!payload.isNull("precipitationMm")) why.add("Rain: ${rainAmount(payload.getDouble("precipitationMm"))} mm on ${event.getString("starts_on")}.")
                if (payload.isNull("precipitationMm")) "" else "${rainAmount(payload.getDouble("precipitationMm"))} mm"
            } else {
                val plant = event.nullable("plant_name") ?: "outdoor plants"
                frost = payload.optDouble("minC", Double.NaN) <= 0.0
                why.add("Low temperature ${payload.get("minC")} °C, below ${payload.get("thresholdC")} °C hardiness, ${event.getString("starts_on")} to ${event.getString("ends_on")}. Bring it in or protect it.")
                payload.optJSONObject("hardiness")?.let { h ->
                    why.add("Hardiness source: ${h.getString("source").replace('_', ' ')} · ${h.get("value")} °C" +
                        (h.nullable("ruleId")?.let { " · $it" } ?: "") + (payload.nullable("potRuleId")?.let { " · $it" } ?: ""))
                    if (h.nullable("ruleId") == "hardiness.usda_uncertain@1") why.add("USDA zone conversion is uncertain.")
                }
                if (payload.isNull("potRuleId")) "protect $plant" else "bring $plant inside"
            }
        }
        "plant_warnings" -> {
            val plant = j.getString("plantName")
            warnings = j.getJSONArray("warnings").mapObjects { w ->
                val sentence = warningText(w, plant) ?: run { unknownFeedKind(); return@mapObjects null }
                FeedWarning(w.getString("kind"), sentence, w.getString("reason"),
                    w.getJSONArray("inputs").mapObjects { b -> "${b.getString("input")}: ${b.get("value")} · ${b.getString("source")}" +
                        (b.nullable("ruleId")?.let { " · $it" } ?: "") + (b.nullable("fetchedAt")?.let { " · Fetched $it" } ?: "") }) }.filterNotNull()
            plant
        }
        else -> { unknownFeedKind(); return null }
    }
    return FeedCard(j.getString("id"), kind, j.getString("href"), text, why, rain, warnings, startsOn, frost)
}
internal fun parseWeather(j: JSONObject) = FeedWeather(j.getBoolean("locationSet"), j.nullable("forecastFetchedAt"),
    j.optJSONObject("rain")?.let { Rain(it.getInt("plantCount"), it.getDouble("precipitationMm"), it.nullable("batchId")) },
    j.optJSONObject("lastRainBatch")?.getString("id"), j.optJSONObject("lastRainBatch")?.getInt("plantCount"))
