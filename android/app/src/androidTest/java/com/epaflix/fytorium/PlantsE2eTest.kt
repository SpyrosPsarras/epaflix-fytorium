package com.epaflix.fytorium

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
        compose.waitForState("Plant action $text enabled", 30_000) { runCatching { scroll(hasText(text)).assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText(text).performClick()
    }
    fun input(label: String, value: String) {
        // Opening a form can finish its options read after the preceding click.
        compose.waitForState("Plant input $label enabled", 30_000) { runCatching { scroll(hasText(label)).assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText(label).performTextReplacement(value)
    }
    fun settings() {
        compose.waitForState("Plant settings enabled", 30_000) { runCatching { scroll(hasContentDescription("Plant settings")).assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithContentDescription("Plant settings").performClick()
    }
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty()
            assertTrue("API $path: ${it.code}: $text", it.isSuccessful); text
        }
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun keyboard(visible: Boolean) = compose.waitForState("Plant keyboard visible: $visible", 10_000) {
        compose.activity.imeVisible() == visible
    }
    fun titleBounds(title: String) = compose.onNode(hasText(title) and isHeading()).fetchSemanticsNode().boundsInWindow
    val plantsTab = compose.onNode(hasText("Plants") and isSelectable())
    fun checkKeyboard(title: String, label: String, status: SemanticsMatcher? = null, whileOpen: () -> Unit = {}) {
        keyboard(false)
        val field = hasText(label) and hasSetTextAction()
        compose.waitForState("$title input $label enabled") { compose.onAllNodes(field and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val before = titleBounds(title)
        val statusLine = status?.let { compose.onNode(it) }
        val statusBefore = statusLine?.assertIsDisplayed()?.fetchSemanticsNode()?.boundsInWindow
        scroll(field).performClick()
        keyboard(true)
        assertEquals(before, titleBounds(title))
        if (statusLine != null) assertEquals(statusBefore, statusLine.assertIsDisplayed().fetchSemanticsNode().boundsInWindow)
        plantsTab.assertDoesNotExist()
        compose.onNode(field).assertIsDisplayed().assertIsFocused()
        whileOpen()
        compose.runOnUiThread { compose.activity.hideIme() }
        keyboard(false)
        assertEquals(before, titleBounds(title))
        plantsTab.assertIsDisplayed()
    }
    var siteId: String? = null
    var failure: Throwable? = null
    try {
        compose.waitForState("Plant scenario initial refresh", 30_000) { app.repository.state.value.member != null && !app.repository.state.value.busy }
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
        val nebulosa = if (compose.waitForSpeciesNames(app, 910002)) "Synthetic cloudleaf" else "Testoria nebulosa"
        compose.selectTab("Plants")
        // Real airplane mode, so no background read can clear the Offline line while the keyboard opens.
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        app.repository.connectivityChanged(false)
        try {
            compose.waitForState("Network gone in airplane mode", 30_000) { !app.care.connected() }
            val offline = hasText("Offline", substring = true) and hasClickAction()
            compose.waitForNode(offline)
            checkKeyboard("Plants", "Search plants", status = offline)
        } finally {
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
            app.repository.connectivityChanged(true)
            app.repository.refresh()
        }
        // The network comes back in steps after airplane mode; wait until the app has it and has refreshed, so the tap is not explained away.
        compose.waitForState("Online again after airplane mode", 30_000) {
            app.care.connected() && app.repository.state.value.let { !it.offline && !it.busy } && app.repository.plantState.value.online
        }
        compose.waitForState("Add plant enabled", 30_000) { compose.onAllNodes(hasContentDescription("Add plant") and !hasStateDescription("Adding a plant needs a connection.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Add plant").performClick()
        compose.waitForState("Search gets initial focus", 10_000) {
            compose.onAllNodes(hasText("Species name") and hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        keyboard(true)
        val addTitleWithKeyboard = titleBounds("Add plant")
        val searchList = compose.onNode(hasScrollToIndexAction())
        val searchHeight = searchList.fetchSemanticsNode().boundsInWindow.height
        plantsTab.assertDoesNotExist()
        input("Species name", "Testoria neblosa")
        compose.onNodeWithContentDescription("Search species").assertIsEnabled()
        assertTrue("Enter key injected", device.pressEnter())
        keyboard(false)
        assertEquals(addTitleWithKeyboard, titleBounds("Add plant"))
        assertTrue("App content area shrinks for the keyboard", searchHeight < searchList.fetchSemanticsNode().boundsInWindow.height)
        plantsTab.assertIsDisplayed()
        compose.onNode(hasText("Species name") and hasSetTextAction()).assertTextContains("Testoria neblosa")
        val candidate = hasContentDescription("$nebulosa, Testoria nebulosa. Choose this species.")
        compose.waitForState("Typo search result enabled", 30_000) { compose.onAllNodes(candidate and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        scroll(candidate).performClick()
        compose.onNodeWithText("Is this your plant?").assertIsDisplayed()
        compose.onNodeWithText("Save plant").assertDoesNotExist()
        click("Yes, this one")
        input("Name", "Synthetic A05 sprout")
        compose.onNode(hasText("Site") and hasText("Synthetic A05 site")).assertIsDisplayed()
        click("Save plant")
        compose.waitForState("Added plant detail", 30_000) { app.repository.state.value.detail?.plant?.displayName == "Synthetic A05 sprout" && !app.repository.state.value.busy }
        val plantId = app.repository.state.value.detail!!.plant.id
        compose.waitForState("Edit plant enabled", 30_000) { compose.onAllNodes(hasContentDescription("Edit plant") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Edit plant").performClick()
        checkKeyboard("Edit plant", "Name") { input("Name", "Synthetic A05 edited sprout") }
        click("More details")
        click("Pot type")
        compose.onNodeWithText("Ceramic").performClick()
        input("Pot size in cm", "23")
        input("Window distance in cm", "90")
        // Typing reopens the keyboard; let the layout settle before tapping a switch below the fields.
        compose.runOnUiThread { compose.activity.hideIme() }
        keyboard(false)
        click("Near AC")
        compose.waitForState("Near AC on", 10_000) { compose.onAllNodes(hasText("Near AC") and isOn()).fetchSemanticsNodes().isNotEmpty() }
        click("Save plant")
        compose.waitForState("Edited plant detail", 30_000) { app.repository.state.value.detail?.plant?.displayName == "Synthetic A05 edited sprout" && !app.repository.state.value.busy }
        val edited = JSONObject(json("/api/plants/$plantId"))
        assertEquals("ceramic", edited.getString("pot_type")); assertEquals(23, edited.getInt("pot_size_cm"))
        assertEquals(90, edited.getInt("window_distance_cm")); assertTrue(edited.getBoolean("near_ac")); assertEquals(siteId, edited.getString("site_id"))
        click("Details")
        click("Species names")
        checkKeyboard("Species names", "Common name")
        compose.onNodeWithContentDescription("Back").performClick()
        settings()
        click("This plant's schedule")
        checkKeyboard("This plant's schedule", "Water interval in days")
        compose.onNodeWithContentDescription("Back").performClick()
        settings()
        click("Move to graveyard")
        compose.onNode(hasText("Synthetic A05 edited sprout leaves Today and Upcoming. Its history and photos stay, and you can restore it later.") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Move to graveyard") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForState("Plant moved to graveyard", 30_000) { app.repository.state.value.detail?.plant?.status == "graveyard" && !app.repository.state.value.busy }
        compose.onNodeWithText("Handle all tasks").assertDoesNotExist()
        settings()
        click("Restore plant")
        click("Restore Synthetic A05 edited sprout to Synthetic A05 site")
        compose.waitForState("Plant restored to active", 30_000) { app.repository.state.value.detail?.plant?.status == "active" && !app.repository.state.value.busy }
        assertEquals("active", JSONObject(json("/api/plants/$plantId")).getString("status"))
        settings()
        click("Delete plant")
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNode(hasText("Type Synthetic A05 edited sprout to confirm.") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Plant name").performTextInput("Synthetic A05 edited sprout")
        compose.onNodeWithText("Delete").performClick()
        compose.waitForState("Plant deleted and detail cleared", 30_000) { app.repository.state.value.plants.none { it.id == plantId } && app.repository.state.value.detail == null && !app.repository.state.value.busy }
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
    compose.selectTab("Today")
}
