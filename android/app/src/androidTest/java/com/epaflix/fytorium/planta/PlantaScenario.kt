package com.epaflix.fytorium.planta

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

fun runPlantaScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    val names = setOf("Test import amber", "Test import blue")
    val siteName = "Test imported nook"
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            // Never include a request or response body in a Planta scenario failure.
            assertTrue("A11 API $path returned ${it.code}", it.isSuccessful)
            text
        }
    fun rows(text: String) = JSONArray(text).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun cleanup() {
        // These exact names belong only to the stack's synthetic Planta fixtures.
        for (status in listOf("active", "graveyard")) rows(json("/api/plants", query = mapOf("status" to status)))
            .filter { it.optString("nickname") in names }.forEach {
                json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", it.getString("display_name")))
            }
        rows(json("/api/sites")).filter { it.getString("name") == siteName }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
    }
    fun action(label: String) = compose.waitForNode(hasText(label) and isEnabled()).performScrollTo()
    try {
        cleanup()
        compose.onNodeWithText("More").performClick()
        compose.waitForNode(hasText("Import from Planta") and hasClickAction()).performScrollTo().performClick()
        action("One-time app code").performTextInput("synthetic-browser-import")
        action("Fetch Planta plants").performClick()
        compose.waitForState("A11 fake Planta staging loaded", 30_000) {
            compose.onAllNodesWithText("Test import amber").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
        val staged = rows(json("/api/import/planta"))
        val fuzzy = staged.single { it.getJSONObject("mapped").getString("nickname") == "Test import amber" }
        assertEquals("review", fuzzy.getString("status"))
        assertFalse(fuzzy.getBoolean("can_commit"))
        assertEquals("pending", fuzzy.getString("cover_status"))
        assertTrue(rows(fuzzy.getJSONArray("candidates").toString()).any { it.getLong("gbif_key") == 910002L && it.getBoolean("requires_confirmation") })
        compose.onNodeWithText("Import confirmed plants").performScrollTo().assertIsNotEnabled()
        // Actual Activity restoration re-fetches server staging without another code exchange.
        compose.activityRule.scenario.recreate()
        compose.waitForState("A11 review resumed after recreation", 30_000) {
            compose.onAllNodes(hasText("Confirm Testoria nebulosa") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
        assertEquals(staged.map { it.getString("id") }, rows(json("/api/import/planta")).map { it.getString("id") })
        action("Confirm Testoria nebulosa").performClick()
        compose.waitForState("A11 fuzzy species confirmed", 30_000) {
            compose.onAllNodesWithText("Confirmed species: Testoria nebulosa").fetchSemanticsNodes().isNotEmpty()
        }
        action("Import confirmed plants").performClick()
        compose.waitForState("A11 commit complete", 30_000) { compose.onAllNodesWithText("Import complete.").fetchSemanticsNodes().isNotEmpty() }
        val committed = rows(json("/api/import/planta"))
        assertTrue(committed.all { it.getString("status") == "committed" && !it.getBoolean("can_commit") })
        assertEquals("imported", committed.single { it.getString("id") == fuzzy.getString("id") }.getString("cover_status"))
        val plantId = committed.single { it.getString("id") == fuzzy.getString("id") }.getString("plant_id")
        action("View imported plants").performClick()
        compose.waitForState("A11 imported plant in Plants", 30_000) { compose.onAllNodesWithTag("plant:$plantId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plant:$plantId").performScrollTo().assertIsDisplayed()
        assertEquals("Test import amber", JSONObject(json("/api/plants/$plantId")).getString("nickname"))
        println("A11 E2E PASS: fake Planta fetch, resumed review, fuzzy confirmation, cover, commit and imported plant in Plants")
    } finally {
        cleanup()
        app.care.refresh()
    }
}
