package com.epaflix.fytorium

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

internal fun runPlantScenarios(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    fun scroll(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val node = compose.onNode(matcher)
        if (runCatching { node.assertIsDisplayed() }.isFailure) {
            if (runCatching { node.performScrollTo() }.isFailure)
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
        }
        return compose.onNode(matcher)
    }
    fun click(text: String) {
        // Lazy rows do not exist until scrolled into view.
        compose.waitUntil(30_000) { runCatching { scroll(hasText(text)).assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText(text).performClick()
    }
    fun input(label: String, value: String) { scroll(hasText(label)).performTextReplacement(value) }
    fun settings() { scroll(hasContentDescription("Plant settings")).performClick() }
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            assertTrue("API $path: ${it.code}: $text", it.isSuccessful); text
        }
    var siteId: String? = null
    var failure: Throwable? = null
    try {
        compose.waitUntil(30_000) { app.repository.state.value.member != null && !app.repository.state.value.busy }
        // Recover only this scenario's fixtures, including a plant interrupted in the graveyard.
        for (status in listOf("active", "graveyard")) JSONArray(json("/api/plants", query = mapOf("status" to status))).let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("nickname") in listOf("Synthetic A05 sprout", "Synthetic A05 edited sprout") }.forEach {
                json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", it.getString("display_name")))
            }
        }
        JSONArray(json("/api/sites")).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("name") == "Synthetic A05 site" }.forEach {
            json("/api/sites/${it.getString("id")}", "DELETE")
        } }
        siteId = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"Synthetic A05 site","location":"indoor","light":"part_shade","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        click("Add plant")
        input("Species name", "Testoria neblosa")
        click("Search species")
        click("Choose Testoria nebulosa")
        compose.onNodeWithText("Save plant").assertDoesNotExist()
        click("Confirm Testoria nebulosa")
        input("Nickname", "Synthetic A05 sprout")
        click("Site")
        compose.onNodeWithText("Synthetic A05 site").performClick()
        click("Save plant")
        compose.waitUntil(30_000) { app.repository.state.value.detail?.plant?.displayName == "Synthetic A05 sprout" && !app.repository.state.value.busy }
        val plantId = app.repository.state.value.detail!!.plant.id
        click("Edit plant")
        input("Nickname", "Synthetic A05 edited sprout")
        click("Pot type")
        compose.onNodeWithText("ceramic").performClick()
        input("Pot size in cm", "23")
        input("Window distance in cm", "90")
        click("Near AC")
        click("Save plant")
        compose.waitUntil(30_000) { app.repository.state.value.detail?.plant?.displayName == "Synthetic A05 edited sprout" && !app.repository.state.value.busy }
        val edited = JSONObject(json("/api/plants/$plantId"))
        assertEquals("ceramic", edited.getString("pot_type")); assertEquals(23, edited.getInt("pot_size_cm"))
        assertEquals(90, edited.getInt("window_distance_cm")); assertTrue(edited.getBoolean("near_ac")); assertEquals(siteId, edited.getString("site_id"))
        settings()
        click("Move to graveyard")
        compose.waitUntil(30_000) { app.repository.state.value.detail?.plant?.status == "graveyard" && !app.repository.state.value.busy }
        compose.onNodeWithText("Handle all tasks").assertDoesNotExist()
        settings()
        click("Restore plant")
        click("Restore to active plants")
        compose.waitUntil(30_000) { app.repository.state.value.detail?.plant?.status == "active" && !app.repository.state.value.busy }
        assertEquals("active", JSONObject(json("/api/plants/$plantId")).getString("status"))
        settings()
        click("Delete plant")
        compose.onNodeWithText("Permanently delete plant").assertIsNotEnabled()
        compose.onNodeWithText("Type the plant name to confirm").performTextInput("Synthetic A05 edited sprout")
        compose.onNodeWithText("Permanently delete plant").performClick()
        compose.waitUntil(30_000) { app.repository.state.value.plants.none { it.id == plantId } && app.repository.state.value.detail == null && !app.repository.state.value.busy }
        println("A05 E2E PASS: typo search, explicit confirmation, native add/edit with pot and placement, graveyard/restore, confirmed permanent deletion")
    } catch (error: Throwable) { failure = error; throw error } finally {
        val cleanup = runCatching {
            if (app.store.tokens != null) {
                for (status in listOf("active", "graveyard")) JSONArray(json("/api/plants", query = mapOf("status" to status))).let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("nickname") in listOf("Synthetic A05 sprout", "Synthetic A05 edited sprout") }.forEach {
                        json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", it.getString("display_name")))
                    }
                }
                siteId?.let { json("/api/sites/$it", "DELETE") }
            }
        }
        if (failure == null) cleanup.getOrThrow()
    }
    compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).performClick()
}
