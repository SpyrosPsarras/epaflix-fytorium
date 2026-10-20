package com.epaflix.fytorium.species

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.epaflix.fytorium.FytoriumApplication
import com.epaflix.fytorium.MainActivity
import com.epaflix.fytorium.waitForNode
import com.epaflix.fytorium.waitForState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

fun runSpeciesCareScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    val plantName = "Synthetic A10 fern"
    val siteName = "Synthetic A10 room"
    val speciesKey = 910002L
    val overridePath = "/api/species/$speciesKey/care/overrides/wateringIntervalDays"
    var plantId: String? = null
    var siteId: String? = null
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            assertTrue("API $path returned ${it.code}: $text", it.isSuccessful)
            text
        }
    fun rows(text: String): List<JSONObject> = JSONArray(text).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun enabled(text: String) {
        compose.waitForState("Species action $text enabled", 30_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    fun waterBasis() = app.repository.state.value.today?.sections?.flatMap { it.tasks }
        ?.firstOrNull { it.plantId == plantId && it.taskType == "water" }?.basedOn?.firstOrNull { it.input == "wateringIntervalDays" }
    fun openPlant() {
        compose.onNodeWithText("Plants").performClick()
        compose.waitForState("Species fixture plant row visible", 30_000) { compose.onAllNodesWithTag("plant:$plantId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plant:$plantId").performScrollTo().performClick()
        compose.waitForState("Species plant settings visible", 30_000) { compose.onAllNodesWithContentDescription("Plant settings").fetchSemanticsNodes().isNotEmpty() }
    }
    fun openCare() {
        compose.onNodeWithContentDescription("Plant settings").performClick()
        compose.onNodeWithText("Species care").performClick()
        enabled("Refresh care data")
    }
    fun showTodayRow() {
        compose.onNodeWithText("Today").performClick()
        val task = hasAnyAncestor(hasTestTag("task:$plantId:water"))
        compose.waitForNode(task and hasContentDescription("Open $plantName")).performScrollTo().assertHasClickAction().performClick()
        compose.onNode(task and hasText("Based on")).assertDoesNotExist()
        compose.onNode(task and hasText("Handle all tasks for $plantName")).assertDoesNotExist()
    }
    fun showPlantBasis() {
        openPlant()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("basis:water"))
        compose.onNode(hasAnyAncestor(hasTestTag("basis:water")) and hasText("Based on")).performScrollTo().performClick()
    }
    try {
        // Recover only this scenario's synthetic fixtures after an interrupted run.
        rows(json("/api/plants")).filter { it.optString("nickname") == plantName }.forEach {
            json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", plantName))
        }
        rows(json("/api/sites")).filter { it.getString("name") == siteName }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
        siteId = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"Synthetic A10 room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria neblosa")))
        val plant = JSONObject("""{"nickname":"Synthetic A10 fern","confirmed":true,"pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", speciesKey)
        plantId = JSONObject(json("/api/plants", "POST", plant)).getString("id")
        json(overridePath, "DELETE")
        // Wait for synthetic source fetches, so reset can be compared with the original provenance.
        compose.waitForState("Synthetic care source fetches finished", 30_000) {
            rows(JSONObject(json("/api/species/$speciesKey/care")).getJSONArray("fetches").toString())
                .none { it.getString("status") in listOf("pending", "fetching") }
        }
        json("/api/tasks/settings", "PUT", JSONObject("""{"sort":"task","filter":"all","show_completed":false}"""))
        app.care.refresh()
        compose.waitForState("Initial watering basis loaded", 30_000) { waterBasis() != null && !app.repository.state.value.busy }
        val original = waterBasis()!!
        assertEquals("rule", original.source)
        assertEquals(7, (original.value as Number).toInt())
        openPlant()
        openCare()
        compose.waitForNode(hasText("Override watering interval in days") and isEnabled()).performScrollTo().performClick()
        compose.onNodeWithText("Value").performTextReplacement("9")
        compose.onNodeWithText("Save override").performClick()
        compose.waitForState("Watering override applied to Today", 30_000) { waterBasis()?.source == "override" && (waterBasis()?.value as? Number)?.toInt() == 9 }
        showTodayRow()
        val overridden = JSONObject(json("/api/plants/$plantId/tasks")).getJSONObject("water").getJSONArray("basedOn")
        val basis = (0 until overridden.length()).map { overridden.getJSONObject(it) }.single { it.getString("input") == "wateringIntervalDays" }
        assertEquals("override", basis.getString("source")); assertEquals(9, basis.getInt("value"))
        showPlantBasis()
        val water = hasAnyAncestor(hasTestTag("basis:water"))
        compose.onNode(water and hasText("wateringIntervalDays")).performScrollTo().assertIsDisplayed()
        compose.onNode(water and hasText("9")).performScrollTo().assertIsDisplayed()
        compose.waitForNode(water and hasText("Source: override", substring = true)).performScrollTo().assertIsDisplayed()
        openCare()
        compose.waitForNode(hasText("Remove override for watering interval in days") and isEnabled()).performScrollTo().performClick()
        compose.onNodeWithText("Confirm reset").performClick()
        compose.waitForState("Watering basis reset to original", 30_000) { waterBasis()?.source == original.source && waterBasis()?.value == original.value }
        showTodayRow()
        val resetTasks = JSONObject(json("/api/plants/$plantId/tasks")).getJSONObject("water").getJSONArray("basedOn")
        val resetBasis = (0 until resetTasks.length()).map { resetTasks.getJSONObject(it) }.single { it.getString("input") == "wateringIntervalDays" }
        assertEquals(original.source, resetBasis.getString("source")); assertEquals((original.value as Number).toInt(), resetBasis.getInt("value"))
        val restored = JSONObject(json("/api/species/$speciesKey/care")).getJSONObject("profile").getJSONObject("wateringIntervalDays")
        assertEquals(original.source, restored.getString("source")); assertEquals((original.value as Number).toDouble(), restored.getDouble("value"), 0.0)
        showPlantBasis()
        compose.onNode(water and hasText("Source: override", substring = true)).assertDoesNotExist()
        compose.waitForNode(water and hasText("Source: rule · water.default@1")).performScrollTo().assertIsDisplayed()
        println("A10 E2E PASS: override 9 days, plant Based on shows override/9, Today has no inline actions, UI and API reset to the original value/source")
    } finally {
        if (plantId != null) {
            json(overridePath, "DELETE")
            json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", plantName))
        }
        if (siteId != null) json("/api/sites/$siteId", "DELETE")
        app.care.refresh()
    }
}
