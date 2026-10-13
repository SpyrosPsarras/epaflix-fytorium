package com.epaflix.fytorium.sites

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

fun runSiteScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    val names = listOf("Synthetic A07 room", "Synthetic A07 terrace")
    val plantName = "Synthetic A07 fern"
    var siteId: String? = null
    var plantId: String? = null
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            assertTrue("API $path returned ${it.code}: $text", it.isSuccessful)
            text
        }
    fun rows(text: String): List<JSONObject> = JSONArray(text).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
    fun waitText(text: String) { compose.waitForState("Site text $text visible", 30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    fun enabled(text: String) {
        compose.waitForState("Site action $text enabled", 30_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    fun type(label: String, value: String) {
        enabled(label)
        compose.onNodeWithText(label).performScrollTo()
        compose.onNodeWithText(label).performTextReplacement(value)
    }
    try {
        rows(json("/api/plants")).filter { it.optString("nickname") == plantName }.forEach {
            json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", plantName))
        }
        rows(json("/api/sites")).filter { it.getString("name") in names }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Sites").performClick()
        enabled("Create site")
        compose.onNodeWithText("Create site").performScrollTo().performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        waitText("Enter a site name.")
        type("Name", names[0])
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOff().performClick()
        for ((label, value) in listOf("Warm minimum °C" to "18", "Warm maximum °C" to "25", "Cold minimum °C" to "5", "Cold maximum °C" to "20")) {
            type(label, value)
        }
        compose.onNodeWithText("Draft").performScrollTo().performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        waitText(names[0])
        val created = rows(json("/api/sites")).single { it.getString("name") == names[0] }
        siteId = created.getString("id")
        assertEquals(18, created.getInt("warm_min_c")); assertEquals(25, created.getInt("warm_max_c"))
        assertEquals(5, created.getInt("cold_min_c")); assertEquals(20, created.getInt("cold_max_c"))
        assertTrue(created.getBoolean("grow_light")); assertTrue(created.getBoolean("draft"))
        val tag = "site:$siteId"
        fun button(text: String) = compose.waitForNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)) and isEnabled())
        button("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Name").assertTextContains(names[0])
        type("Name", names[1])
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val keyboard = androidx.test.uiautomator.By.pkg("com.google.android.inputmethod.latin")
        if (compose.waitForState("Site form keyboard appeared", 2_000, required = false) { device.hasObject(keyboard) }) {
            device.pressBack()
            compose.waitForState("Site form keyboard closed", 5_000) { !device.hasObject(keyboard) }
        }
        for ((label, old, value) in listOf(Triple("Location", "indoor", "outdoor"), Triple("Light level", "part sun", "full sun"), Triple("Humidity", "normal", "humid"))) {
            compose.onNodeWithText("$label: $old").performScrollTo().performClick()
            waitText(value)
            compose.onNodeWithText(value).performClick()
        }
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOn().performClick()
        type("Cold minimum °C", "")
        compose.onNodeWithText("Draft").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        waitText(names[1])
        val edited = JSONObject(json("/api/sites/$siteId"))
        assertEquals("outdoor", edited.getString("location")); assertEquals("full_sun", edited.getString("light"))
        assertEquals("humid", edited.getString("humidity")); assertTrue(edited.isNull("cold_min_c"))
        assertFalse(edited.getBoolean("grow_light")); assertFalse(edited.getBoolean("draft"))
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
        val plant = JSONObject("""{"nickname":"Synthetic A07 fern","pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
        plantId = JSONObject(json("/api/plants", "POST", plant)).getString("id")
        compose.onNodeWithText("Refresh sites").performScrollTo().performClick()
        waitText("Outdoor · 1 plant")
        button("Delete").performScrollTo().performClick()
        enabled("Confirm delete")
        compose.onNodeWithText("Confirm delete").performClick()
        waitText("Move or remove the 1 plant on this site first.")
        compose.onNodeWithText("Move or remove the 1 plant on this site first.").assertIsDisplayed()
        assertEquals(names[1], JSONObject(json("/api/sites/$siteId")).getString("name"))
        json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", plantName)); plantId = null
        enabled("Confirm delete")
        compose.onNodeWithText("Confirm delete").performClick()
        compose.waitForState("Deleted site row absent", 30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        assertFalse(rows(json("/api/sites")).any { it.getString("id") == siteId })
        siteId = null
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Today").performClick()
        println("A07 E2E PASS: create with all fields, edit conditions, server plant count, delete refused with plant, delete after removal")
    } finally {
        if (plantId != null) json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", plantName))
        if (siteId != null) json("/api/sites/$siteId", "DELETE")
    }
}
