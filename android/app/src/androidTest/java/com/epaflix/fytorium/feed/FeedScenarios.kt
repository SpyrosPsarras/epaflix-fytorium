package com.epaflix.fytorium.feed

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.epaflix.fytorium.FytoriumApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

fun runFeedScenarios(app: FytoriumApplication, compose: ComposeContentTestRule) {
    val nickname = "Synthetic A08 outdoor fern"
    val siteName = "Synthetic A08 outdoor site"
    var plantId: String? = null
    var siteId: String? = null
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            assertTrue("API $path returned ${it.code}: $text", it.isSuccessful)
            text
        }
    val settings = JSONObject(json("/api/feed/settings"))
    val location = JSONObject(json("/api/household/location"))
    try {
        val oldPlants = JSONArray(json("/api/plants"))
        (0 until oldPlants.length()).map { oldPlants.getJSONObject(it) }.filter { it.optString("nickname") == nickname }.forEach {
            json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", nickname))
        }
        val oldSites = JSONArray(json("/api/sites"))
        (0 until oldSites.length()).map { oldSites.getJSONObject(it) }.filter { it.getString("name") == siteName }.forEach {
            json("/api/sites/${it.getString("id")}", "DELETE")
        }
        json("/api/feed/settings", "PUT", JSONObject("""{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true}"""))
        siteId = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"$siteName","location":"outdoor","light":"part_shade","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria weather 910008")))
        val plant = JSONObject("""{"nickname":"$nickname","pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":20,"window_distance_cm":100,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", 910008)
        plantId = JSONObject(json("/api/plants", "POST", plant)).getString("id")
        json("/api/species/910008/care/overrides/hardinessC", "PUT", JSONObject().put("value", 6))
        json("/api/species/910008/care/overrides/light", "PUT", JSONObject().put("value", "part_sun"))
        json("/api/household/location", "PUT", JSONObject("""{"location":{"latitude":0.5,"longitude":0.5,"label":"Synthetic A08 weather location"}}"""))
        val worker = java.net.URL("http://app:3000/__test/weather-worker").openConnection() as java.net.HttpURLConnection
        worker.requestMethod = "POST"; worker.connectTimeout = 10_000; worker.readTimeout = 30_000
        worker.setRequestProperty("Authorization", "Bearer " + app.store.tokens!!.access)
        worker.setRequestProperty("Content-Type", "application/json")
        try { assertEquals(200, worker.responseCode) } finally { worker.disconnect() }
        app.care.refresh()
        compose.waitUntil(45_000) { !app.feed.state.value.refreshing && !app.feed.state.value.saving && app.feed.state.value.cards.any { it.title == nickname && it.kind == "weather_alerts" } }
        val cold = app.feed.state.value.cards.first { it.title == nickname && it.kind == "weather_alerts" }
        compose.onNodeWithTag("feed-${cold.id}").performScrollTo()
        compose.onNode(hasText("Low temperature 0", substring = true) and hasAnyAncestor(hasTestTag("feed-${cold.id}"))).assertIsDisplayed()
        val rainCard = app.feed.state.value.cards.last { it.rain }
        compose.onNodeWithTag("feed-${rainCard.id}").performScrollTo()
        val count = app.feed.state.value.weather!!.rain!!.plantCount
        compose.onNodeWithText("Mark $count outdoor plants watered").performScrollTo().performClick()
        compose.waitUntil(45_000) { !app.feed.state.value.refreshing && !app.feed.state.value.saving && app.feed.state.value.weather?.lastBatchId != null }
        compose.onNodeWithText("Undo rain watering").performScrollTo().assertIsDisplayed()
        val watered = JSONArray(json("/api/plants/$plantId/history"))
        assertTrue("Rain batch absent from plant history", (0 until watered.length()).any { watered.getJSONObject(it).optString("task_type") == "water" && !watered.getJSONObject(it).isNull("batch_id") && watered.getJSONObject(it).isNull("undone_at") })
        compose.onNodeWithText("Undo rain watering").performClick()
        compose.waitUntil(45_000) { !app.feed.state.value.refreshing && !app.feed.state.value.saving && app.feed.state.value.weather?.lastBatchId == null }
        compose.onNodeWithText("Mark $count outdoor plants watered").performScrollTo().assertIsDisplayed()
        val undone = JSONArray(json("/api/plants/$plantId/history"))
        assertTrue("Server did not undo rain watering", (0 until undone.length()).any { !undone.getJSONObject(it).isNull("batch_id") && !undone.getJSONObject(it).isNull("undone_at") })
        println("A08 E2E PASS: outdoor cold alert from fake weather, rain mark watered and undo verified through API history")
    } finally {
        if (plantId != null) json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", nickname))
        if (siteId != null) json("/api/sites/$siteId", "DELETE")
        json("/api/feed/settings", "PUT", settings)
        json("/api/household/location", "PUT", location)
        app.care.refresh()
        compose.waitUntil(30_000) { !app.feed.state.value.refreshing && !app.feed.state.value.saving && app.feed.state.value.cards.none { it.title == nickname } }
    }
}
