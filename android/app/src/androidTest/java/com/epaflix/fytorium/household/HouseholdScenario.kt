package com.epaflix.fytorium.household

import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.epaflix.fytorium.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// The owner stays logged in through an independent API client; the sitter uses the real app session.
private class ScenarioCredentials(initial: Tokens) : CredentialStore {
    private val state = MutableStateFlow<Tokens?>(initial)
    override val tokensState = state.asStateFlow()
    override var tokens: Tokens? get() = state.value; set(value) { state.value = value }
    override var pending: LoginAttempt? = null
    override fun clear() { tokens = null; pending = null }
}
fun runHouseholdScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val ownerStore = ScenarioCredentials(app.store.tokens!!)
    val owner = ApiClient(BuildConfig.SERVER_URL, ownerStore) {}
    val repo = HouseholdRepository(owner, HouseholdArea.Members, { Role.owner }, { app.care.connected() })
    val names = listOf("Synthetic A09 shared fern", "Synthetic A09 private fern")
    val siteName = "Synthetic A09 room"
    val subjectName = "synthetic-a09-sitter-" + java.util.UUID.randomUUID().toString()
    var memberId: String? = null
    var inviteId: String? = null
    var failure: Throwable? = null
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String = owner.request(path, method = method, body = body, query = query).use {
        // Never include a response body here: invite responses contain single-display tokens.
        assertTrue("A09 API request failed with status ${it.code}", it.isSuccessful)
        it.body?.string().orEmpty()
    }
    fun rows(text: String) = JSONArray(text).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
    fun cleanupPlants() {
        rows(json("/api/plants")).filter { it.optString("nickname") in names }.forEach { json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", it.getString("nickname"))) }
        rows(json("/api/sites")).filter { it.getString("name") == siteName }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
    }
    fun providerSignIn(subjectName: String) {
        assertTrue("A09 provider did not open", device.wait(Until.hasObject(By.pkg("com.android.chrome").text("Mock OAuth2 Server Sign-in")), 60_000))
        device.waitForIdle()
        val input = device.wait(Until.findObject(By.pkg("com.android.chrome").clazz("android.widget.EditText")), 30_000)
        assertNotNull("A09 subject input missing", input)
        input.text = subjectName
        assertTrue(device.wait(Until.hasObject(By.text(subjectName)), 10_000))
        val keyboard = By.pkg("com.google.android.inputmethod.latin")
        if (device.wait(Until.hasObject(keyboard), 2_000)) { device.pressBack(); device.wait(Until.gone(keyboard), 5_000) }
        var button = device.findObject(By.text("Sign-in"))
        repeat(8) {
            if (button == null) {
                device.swipe(device.displayWidth * 95 / 100, device.displayHeight * 3 / 4, device.displayWidth * 95 / 100, device.displayHeight / 2, 80)
                button = device.wait(Until.findObject(By.text("Sign-in")), 2_000)
            }
        }
        assertNotNull("A09 Sign-in missing", button)
        device.waitForIdle(); button!!.click()
    }
    try {
        cleanupPlants()
        val siteId = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"Synthetic A09 room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
        val plantIds = names.map { name ->
            val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
            val body = JSONObject("""{"pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
                .put("nickname", name).put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
            JSONObject(json("/api/plants", "POST", body)).getString("id")
        }
        repo.refresh()
        val previousMembers = repo.state.value.members.map { it.id }.toSet()
        val previousInvites = repo.state.value.invites.map { it.id }.toSet()
        val link = repo.createInvite("sitter", setOf(plantIds.first())) ?: error("A09 invite creation failed")
        inviteId = repo.state.value.invites.single { it.id !in previousInvites }.id
        assertFalse("Invite token entered diagnostics", app.session.copyDiagnostics().contains(link.substringAfterLast('/')))
        app.store.clear()
        compose.waitUntil(30_000) { !app.session.state.value.loggedIn && app.repository.state.value.member == null }
        compose.activity.runOnUiThread { CustomTabsIntent.Builder().build().launchUrl(compose.activity, Uri.parse(BuildConfig.SERVER_URL + link)) }
        providerSignIn(subjectName)
        // Confirm invite acceptance through the owner's API.
        compose.waitUntil(60_000) {
            repo.refresh()
            repo.state.value.members.firstOrNull { it.id !in previousMembers }?.also { memberId = it.id } != null
        }
        // Membership commits before the callback deletes the invite cookie in Chrome.
        assertTrue("A09 invite callback did not finish in Chrome", device.wait(Until.hasObject(By.pkg("com.android.chrome").textContains("· sitter")), 60_000))
        // Only this disposable guest's Chrome profile: no remembered subject, cookies or scrolled form.
        assertEquals("A09 Chrome test profile reset failed", "Success", device.executeShellCommand("pm clear com.android.chrome").trim())
        app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        assertTrue("A09 app did not foreground after browser acceptance", device.wait(Until.hasObject(By.pkg("com.epaflix.fytorium")), 30_000))
        compose.waitUntil(30_000) { runCatching { compose.onAllNodesWithText("Log in").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithText("Log in").performClick()
        providerSignIn(subjectName)
        device.wait(Until.findObject(By.textContains("Open")), 2_000)?.click()
        assertTrue("A09 app redirect did not return", device.wait(Until.hasObject(By.pkg("com.epaflix.fytorium")), 60_000))
        try { compose.waitUntil(60_000) { app.repository.state.value.member?.role == Role.sitter && !app.repository.state.value.busy } }
        catch (error: Throwable) { throw AssertionError("A09 app login timed out: loggedIn=${app.session.state.value.loggedIn}, role=${app.repository.state.value.member?.role}, busy=${app.repository.state.value.busy}, message=${app.session.state.value.message}", error) }
        memberId = app.repository.state.value.member!!.id
        assertEquals(setOf(plantIds.first()), app.repository.state.value.plants.map { it.id }.toSet())
        assertEquals(1, JSONObject(app.api.request("/api/plants/count").use { assertTrue(it.isSuccessful); it.body!!.string() }).getInt("count"))
        compose.onNodeWithText("Plants").performClick()
        compose.onNodeWithText(names.first()).assertIsDisplayed()
        compose.onNodeWithText(names.last()).assertDoesNotExist()
        compose.onNodeWithText("More").performClick()
        for (name in listOf("Members", "Household settings", "Custom tasks")) compose.onNodeWithText(name).assertDoesNotExist()
        // The separate API actor was idle through two browser flows. Open a fresh connection, never replay a write.
        owner.networkLost()
        val removed = repo.removeMember(memberId!!)
        assertTrue("A09 removal failed: ${repo.state.value.errors}", removed)
        app.care.refresh()
        compose.waitUntil(60_000) { !app.session.state.value.loggedIn && app.store.tokens == null && app.repository.state.value.member == null }
        compose.onNodeWithText("Log in").assertIsDisplayed()
        println("A09 E2E PASS: owner API invite for one plant, second OIDC subject accepts in Custom Tab and logs into app, only shared plant visible, owner removal returns app to login")
    } catch (error: Throwable) { failure = error; throw error } finally {
        owner.networkLost()
        if (memberId != null) runCatching { repo.refresh(); if (repo.state.value.members.any { it.id == memberId }) repo.removeMember(memberId!!) }
        if (inviteId != null) runCatching { repo.refresh(); if (repo.state.value.invites.any { it.id == inviteId }) repo.revokeInvite(inviteId!!) }
        val cleanup = runCatching {
            cleanupPlants()
            // Restore the owner on Today; the final care scenario revokes this session.
            app.store.tokens = ownerStore.tokens
            app.api.networkLost()
            device.executeShellCommand("am force-stop com.android.chrome")
            app.care.refresh()
            app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.epaflix.fytorium")), 30_000))
            compose.waitUntil(60_000) { app.repository.state.value.member?.role == Role.owner && !app.repository.state.value.busy }
            compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).performClick()
        }
        if (failure == null) cleanup.getOrThrow()
    }
}
