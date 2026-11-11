package com.epaflix.fytorium.feed

import com.epaflix.fytorium.Member
import com.epaflix.fytorium.Role
import com.epaflix.fytorium.batchReceipt
import com.epaflix.fytorium.mapObjects
import com.epaflix.fytorium.nullable
import com.epaflix.fytorium.shortDate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

val feedLabels = linkedMapOf("late_tasks" to "Late tasks", "current_weather" to "Current weather",
    "weather_alerts" to "Weather alerts", "plant_warnings" to "Plant warnings")
data class FeedWarning(val kind: String, val missing: List<String>, val reason: String, val details: List<String>, val technical: List<String>)
data class FeedCard(val id: String, val kind: String, val href: String, val text: String, val why: List<String>,
                    val rain: Boolean, val warnings: List<FeedWarning>, val startsOn: String?, val frost: Boolean,
                    val plant: String? = null, val technical: List<String> = emptyList()) {
    fun headline(today: String?): String {
        val start = startsOn ?: return text
        val days = today?.let { LocalDate.parse(it).toEpochDay() - LocalDate.parse(start).toEpochDay() }
        return if (rain) listOf("Rain " + when (days) { 0L -> "today"; 1L -> "yesterday"; else -> "on " + shortDate(start) }, text)
            .filter { it.isNotEmpty() }.joinToString(", ")
        else (plant ?: "Outdoor plants") + ": " + (if (frost) "frost " else "cold ") +
            when { days == null || days < -1 -> "from " + shortDate(start); days == -1L -> "tomorrow"; else -> "today" } + ", " + text
    }
}
data class WarningCard(val tag: String, val title: String, val href: String? = null, val why: List<String> = emptyList(),
                       val technical: List<String> = emptyList(), val plants: List<Pair<String, String>> = emptyList())
private val warningTitles = mapOf("too_dark" to "too dark", "too_bright" to "too bright", "no_drainage" to "no drainage")
private fun phrase(items: List<String>) = if (items.size == 1) items.single() else items.dropLast(1).joinToString(", ") + " and " + items.last()
// A fact is a warning kind, or one missing input of a missing_info warning, by its server key. Only titles put them into words.
private fun FeedWarning.facts() = if (kind == "missing_info") missing.ifEmpty { listOf(kind) } else listOf(kind)
private fun title(facts: List<String>) = warningTitles[facts.first()] ?: ("missing " + phrase(facts.map { missingLabels[it] ?: "information" }))
internal fun List<FeedCard>.warningCards(): List<WarningCard> {
    val plants = filter { it.kind == "plant_warnings" }
    val shared = plants.flatMap { card -> card.warnings.flatMap { it.facts() }.distinct().map { it to card } }
        .groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
    val groups = shared.map { (fact, cards) -> WarningCard("feed-plants-$fact",
        title(listOf(fact)).replaceFirstChar(Char::uppercase) + " for ${cards.size} plants",
        technical = cards.flatMap { card -> card.warnings.filter { fact in it.facts() }.flatMap { it.technical } }.distinct(),
        plants = cards.map { it.text to it.href }) }
    val singles = plants.flatMap { card -> card.warnings.mapNotNull { warning ->
        val left = warning.facts().filter { it !in shared }
        if (left.isEmpty()) null else WarningCard("feed-${card.id}-${warning.kind}", card.text + ": " + title(left),
            card.href, listOf(warning.reason) + warning.details, warning.technical)
    } }
    return groups + singles
}
internal fun readableTime(iso: String, zone: ZoneId): String? = runCatching {
    DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()).withZone(zone).format(OffsetDateTime.parse(iso).toInstant())
}.getOrNull()
private val inputLabels = mapOf("careProfile.light" to "Light the species needs", "site.light" to "Site light", "site.grow_light" to "Grow light at the site",
    "plant.grow_light" to "Grow light on the plant", "plant.window_distance_cm" to "Distance from the window (cm)", "effective_light" to "Effective light",
    "pot.pot_type" to "Pot type", "pot.drainage" to "Drainage")
private fun plainValue(value: Any?) = when (value) {
    true -> "yes"
    false -> "no"
    null, JSONObject.NULL, is JSONObject, is JSONArray -> "not set"
    else -> value.toString().replace('_', ' ')
}
private val missingLabels = mapOf("site.light" to "site light", "pot.pot_type" to "pot type", "pot.pot_size_cm" to "pot size",
    "careProfile" to "species care data", "household.location" to "weather location")
private fun parseWarning(w: JSONObject, zone: ZoneId): FeedWarning? {
    val kind = w.getString("kind")
    if (kind !in warningTitles && kind != "missing_info") return null
    val inputs = w.getJSONArray("inputs").mapObjects { it }
    return FeedWarning(kind, if (kind == "missing_info") inputs.map { it.getString("input") }.filter { it in missingLabels } else emptyList(),
        w.getString("reason").replace('_', ' '),
        if (kind == "missing_info") emptyList() else inputs.mapNotNull { b -> inputLabels[b.getString("input")]?.let { label ->
            "$label: ${plainValue(b.opt("value"))}" + (b.nullable("fetchedAt")?.let { readableTime(it, zone) }?.let { ", checked $it" } ?: "") } },
        inputs.flatMap { b -> listOfNotNull(b.nullable("ruleId"), b.nullable("source")) }.distinct())
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
internal fun plainNumber(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
private fun JSONObject.degrees(key: String) = optDouble(key).let { if (it.isNaN()) opt(key).toString() else plainNumber(it) }
private fun unknownFeedKind() { android.util.Log.w("Fytorium", "FEED_UNKNOWN_KIND") }
internal fun parseFeedCard(j: JSONObject, zone: ZoneId = ZoneId.systemDefault()): FeedCard? {
    val kind = j.getString("kind")
    var rain = false
    var warnings = emptyList<FeedWarning>()
    val why = mutableListOf<String>()
    var startsOn: String? = null
    var frost = false
    var subject: String? = null
    val technical = mutableListOf<String>()
    val text = when (kind) {
        "late_tasks" -> {
            val count = j.getInt("count")
            "$count " + if (count == 1) "task is late" else "tasks are late"
        }
        "current_weather" -> {
            j.nullable("unavailableSince")?.let { why.add("Weather unavailable" + readableTime(it, zone)?.let { time -> " since $time" }.orEmpty() + ". Showing last good data where available.") }
            val current = j.optJSONObject("current")
            if (current != null) {
                why.add("Today: low ${current.get("minC")} °C, high ${current.get("maxC")} °C.")
                readableTime(current.getString("fetchedAt"), zone)?.let { why.add("Checked $it") }
                technical.add("Open-Meteo")
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
                if (!payload.isNull("precipitationMm")) why.add("Rain: ${plainNumber(payload.getDouble("precipitationMm"))} mm on ${event.getString("starts_on")}.")
                if (payload.isNull("precipitationMm")) "" else "${plainNumber(payload.getDouble("precipitationMm"))} mm"
            } else {
                subject = event.nullable("plant_name")
                frost = payload.optDouble("minC", Double.NaN) <= 0.0
                why.add("Low temperature ${payload.degrees("minC")} °C, below ${payload.degrees("thresholdC")} °C hardiness, " +
                    "${shortDate(event.getString("starts_on"))} to ${shortDate(event.getString("ends_on"))}. Bring it in or protect it.")
                payload.optJSONObject("hardiness")?.let { h ->
                    why.add("Hardiness: ${h.degrees("value")} °C")
                    technical += listOfNotNull(h.getString("source"), h.nullable("ruleId"))
                    if (h.nullable("ruleId") == "hardiness.usda_uncertain@1") why.add("USDA zone conversion is uncertain.")
                }
                payload.nullable("potRuleId")?.let { technical += it }
                when {
                    payload.isNull("potRuleId") -> if (subject == null) "protect them" else "protect it"
                    else -> if (subject == null) "bring them inside" else "bring inside"
                }
            }
        }
        "plant_warnings" -> {
            warnings = j.getJSONArray("warnings").mapObjects { w -> parseWarning(w, zone) ?: run { unknownFeedKind(); null } }.filterNotNull()
            j.getString("plantName")
        }
        else -> { unknownFeedKind(); return null }
    }
    return FeedCard(j.getString("id"), kind, j.getString("href"), text, why, rain, warnings, startsOn, frost, subject, technical.distinct())
}
internal fun parseWeather(j: JSONObject) = FeedWeather(j.getBoolean("locationSet"), j.nullable("forecastFetchedAt"),
    j.optJSONObject("rain")?.let { Rain(it.getInt("plantCount"), it.getDouble("precipitationMm"), it.nullable("batchId")) },
    j.optJSONObject("lastRainBatch")?.getString("id"), j.optJSONObject("lastRainBatch")?.getInt("plantCount"))
