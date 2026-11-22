package com.epaflix.fytorium.sites

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.epaflix.fytorium.FytoriumApplication
import com.epaflix.fytorium.MainActivity
import com.epaflix.fytorium.hideIme
import com.epaflix.fytorium.imeVisible
import com.epaflix.fytorium.selectTab
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
        compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(value))) }
    }
    try {
        rows(json("/api/plants")).filter { it.optString("nickname") == plantName }.forEach {
            json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", plantName))
        }
        rows(json("/api/sites")).filter { it.getString("name") in names }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
        // Sites live in the Plants tab (W5b-P1, P2).
        compose.selectTab("Plants")
        compose.waitForNode(hasContentDescription("More options")).performClick()
        compose.onNodeWithText("Sites").performClick()
        compose.waitForNode(hasContentDescription("Create site") and isEnabled()).performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        waitText("Enter a site name.")
        val name = compose.onNode(hasText("Name") and hasSetTextAction())
        name.performScrollTo().performClick().performTextReplacement(names[0])
        compose.waitForState("Site form keyboard opened") { compose.activity.imeVisible() == true }
        compose.runOnUiThread { compose.activity.hideIme() }
        compose.waitForState("Site form keyboard closed before Grow light") { compose.activity.imeVisible() == false }
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Temperatures (optional)").performScrollTo().performClick()
        for ((label, value) in listOf("Warm minimum °C" to "18", "Warm maximum °C" to "25", "Cold minimum °C" to "5", "Cold maximum °C" to "20")) {
            type(label, value)
        }
        compose.onNodeWithText("Draft from a door, window or vent").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        // A site made from the list opens its own page.
        compose.waitForNode(hasContentDescription("Edit ${names[0]}") and isEnabled())
        val created = rows(json("/api/sites")).single { it.getString("name") == names[0] }
        siteId = created.getString("id")
        assertEquals(18, created.getInt("warm_min_c")); assertEquals(25, created.getInt("warm_max_c"))
        assertEquals(5, created.getInt("cold_min_c")); assertEquals(20, created.getInt("cold_max_c"))
        assertTrue(created.getBoolean("grow_light")); assertTrue(created.getBoolean("draft"))
        compose.onNodeWithContentDescription("Edit ${names[0]}").performClick()
        compose.onNodeWithText("Name").assertTextContains(names[0])
        type("Name", names[1])
        for ((label, value) in listOf("Location" to "Outdoor", "Light" to "Full sun", "Humidity" to "Humid")) {
            compose.onNode(hasText(label) and hasAnyAncestor(hasScrollAction())).performScrollTo().performClick()
            compose.waitForNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
        }
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOn().performClick()
        type("Cold minimum °C", "")
        compose.onNodeWithText("Draft from a door, window or vent").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.waitForNode(hasContentDescription("Edit ${names[1]}") and isEnabled())
        val edited = JSONObject(json("/api/sites/$siteId"))
        assertEquals("outdoor", edited.getString("location")); assertEquals("full_sun", edited.getString("light"))
        assertEquals("humid", edited.getString("humidity")); assertTrue(edited.isNull("cold_min_c"))
        assertFalse(edited.getBoolean("grow_light")); assertFalse(edited.getBoolean("draft"))
        waitText("Outdoor · Full sun · Humid air")
        // An empty site offers Delete; a plant that arrives meanwhile is refused inside the dialog, which stays (W5b-P7).
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete site").performClick()
        enabled("Delete")
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
        val plant = JSONObject("""{"nickname":"Synthetic A07 fern","pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
        plantId = JSONObject(json("/api/plants", "POST", plant)).getString("id")
        compose.onNodeWithText("Delete").performClick()
        waitText("Move or remove the 1 plant on this site first.")
        compose.onNodeWithText("Delete site?").assertIsDisplayed()
        assertEquals(names[1], JSONObject(json("/api/sites/$siteId")).getString("name"))
        json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", plantName)); plantId = null
        enabled("Delete")
        compose.onNodeWithText("Delete").performClick()
        val tag = "site:$siteId"
        compose.waitForState("Back on the Sites list without the deleted site", 30_000) {
            compose.onAllNodesWithContentDescription("Create site").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        assertFalse(rows(json("/api/sites")).any { it.getString("id") == siteId })
        siteId = null
        compose.onNodeWithContentDescription("Back").performClick()
        compose.selectTab("Today")
        println("A07 E2E PASS: create with all fields from Plants, edit conditions, a plant added during delete is refused, delete after removal")
    } finally {
        if (plantId != null) json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", plantName))
        if (siteId != null) json("/api/sites/$siteId", "DELETE")
    }
}
