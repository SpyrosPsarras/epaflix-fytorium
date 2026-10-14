package com.epaflix.fytorium.feed

import com.epaflix.fytorium.Member
import com.epaflix.fytorium.Role
import com.epaflix.fytorium.mapObjects
import com.epaflix.fytorium.nullable
import org.json.JSONObject

val feedLabels = linkedMapOf("late_tasks" to "Late tasks", "current_weather" to "Current weather",
    "weather_alerts" to "Weather alerts", "plant_warnings" to "Plant warnings")
data class FeedWarning(val title: String, val reason: String, val inputs: List<String>)
data class FeedLink(val id: String, val name: String)
data class FeedCard(val id: String, val kind: String, val href: String, val title: String, val lines: List<String>,
                    val rain: Boolean = false, val plants: List<FeedLink> = emptyList(), val warnings: List<FeedWarning> = emptyList())
data class Rain(val plantCount: Int, val precipitationMm: Double, val batchId: String?)
data class FeedWeather(val locationSet: Boolean, val forecastFetchedAt: String?, val rain: Rain?, val lastBatchId: String?)
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
    var plants = emptyList<FeedLink>()
    var warnings = emptyList<FeedWarning>()
    val lines = mutableListOf<String>()
    val title = when (kind) {
        "late_tasks" -> {
            plants = j.getJSONArray("plants").mapObjects { FeedLink(it.getString("id"), it.getString("name")) }
            val count = j.getInt("count")
            "$count " + if (count == 1) "task is late" else "tasks are late"
        }
        "current_weather" -> {
            j.nullable("unavailableSince")?.let { lines.add("Weather unavailable since $it. Showing last good data where available.") }
            val current = j.optJSONObject("current")
            if (current != null) {
                lines.add("Today: low ${current.get("minC")} °C, high ${current.get("maxC")} °C.")
                lines.add("Open-Meteo · Fetched ${current.getString("fetchedAt")}")
            } else if (j.isNull("unavailableSince")) lines.add("Weather is waiting for its background refresh.")
            "Current weather"
        }
        "weather_alerts" -> {
            val event = j.getJSONObject("event")
            val eventKind = event.getString("kind")
            if (eventKind !in listOf("rain", "low_temperature")) { unknownFeedKind(); return null }
            val payload = event.getJSONObject("payload")
            rain = eventKind == "rain"
            if (rain) {
                if (!payload.isNull("precipitationMm")) lines.add("Rain: ${rainAmount(payload.getDouble("precipitationMm"))} mm on ${event.getString("starts_on")}.")
                "Rain report"
            } else {
                lines.add("Low temperature ${payload.get("minC")} °C, below ${payload.get("thresholdC")} °C hardiness, ${event.getString("starts_on")} to ${event.getString("ends_on")}. Bring it in or protect it.")
                payload.optJSONObject("hardiness")?.let { h ->
                    lines.add("Hardiness source: ${h.getString("source").replace('_', ' ')} · ${h.get("value")} °C" +
                        (h.nullable("ruleId")?.let { " · $it" } ?: "") + (payload.nullable("potRuleId")?.let { " · $it" } ?: ""))
                    if (h.nullable("ruleId") == "hardiness.usda_uncertain@1") lines.add("USDA zone conversion is uncertain.")
                }
                event.nullable("plant_name") ?: "Low temperature alert"
            }
        }
        "plant_warnings" -> {
            val labels = mapOf("too_dark" to "Too dark", "too_bright" to "Too bright", "no_drainage" to "No drainage", "missing_info" to "Missing information")
            warnings = j.getJSONArray("warnings").mapObjects { w ->
                val label = labels[w.getString("kind")] ?: run { unknownFeedKind(); return@mapObjects null }
                FeedWarning(label, w.getString("reason"),
                w.getJSONArray("inputs").mapObjects { b -> "${b.getString("input")}: ${b.get("value")} · ${b.getString("source")}" +
                    (b.nullable("ruleId")?.let { " · $it" } ?: "") + (b.nullable("fetchedAt")?.let { " · Fetched $it" } ?: "") }) }.filterNotNull()
            j.getString("plantName")
        }
        else -> { unknownFeedKind(); return null }
    }
    return FeedCard(j.getString("id"), kind, j.getString("href"), title, lines, rain, plants, warnings)
}
internal fun parseWeather(j: JSONObject) = FeedWeather(j.getBoolean("locationSet"), j.nullable("forecastFetchedAt"),
    j.optJSONObject("rain")?.let { Rain(it.getInt("plantCount"), it.getDouble("precipitationMm"), it.nullable("batchId")) },
    j.optJSONObject("lastRainBatch")?.getString("id"))
