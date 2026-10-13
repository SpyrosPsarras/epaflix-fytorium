package com.epaflix.fytorium

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.json.JSONObject
import org.json.JSONArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchesAndShowsLogin() {
        compose.onNodeWithText("Fytorium").assertIsDisplayed()
        compose.onNodeWithText("Log in").assertIsDisplayed()
    }
    @Test fun recreationKeepsProcessClientAndStore() {
        val app = compose.activity.application as FytoriumApplication
        val client = app.api
        val store = app.store
        compose.activityRule.scenario.recreate()
        assertSame(client, (compose.activity.application as FytoriumApplication).api)
        assertSame(store, (compose.activity.application as FytoriumApplication).store)
    }
    @Test fun debugCleartextIsRestrictedToExactE2eHosts() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        assertTrue(policy.isCleartextTrafficPermitted("app"))
        assertTrue(policy.isCleartextTrafficPermitted("oidc"))
        for (host in listOf("app.evil", "sub.app", "sub.oidc", "example.com", "fytorium.epaflix.com", "127.0.0.1")) {
            assertFalse(host, policy.isCleartextTrafficPermitted(host))
        }
    }
    @Test fun emulatorReachesStackAndOidcProvider() {
        for (url in listOf("http://app:3000/healthz", "http://oidc:8080/default/.well-known/openid-configuration")) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
        }
    }
    @Test fun credentialsRoundTripWithoutPlaintextAndClearOnLogout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context)
        store.clear()
        store.tokens = Tokens("synthetic-e2e-access", "synthetic-e2e-refresh")
        store.pending = Pkce.create()
        assertEquals("synthetic-e2e-access", KeystoreCredentialStore(context).tokens!!.access)
        val raw = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("synthetic-e2e-access"))
        assertFalse(raw.contains(store.pending!!.verifier))
        store.clear()
        assertNull(store.tokens); assertNull(store.pending)
    }
    private fun <T : Any> T.waitForAction(control: String): T {
        var lastCheck = "not found"
        try {
            compose.waitUntil(30_000) {
                runCatching {
                    lastCheck = "not found"
                    when (this) {
                        is SemanticsNodeInteraction -> {
                            assertExists()
                            lastCheck = "not displayed"
                            assertIsDisplayed()
                            val app = compose.activity.application as FytoriumApplication
                            val busy = app.repository.state.value.busy
                            val connected = app.care.connected()
                            lastCheck = "not enabled (busy=$busy, connected=$connected)"
                            assertIsEnabled()
                            // The rendered enabled state can lag an in-flight refresh under load.
                            check(!busy)
                            check(!app.session.state.value.loggedIn || connected)
                        }
                        is UiObject2 -> {
                            lastCheck = "not displayed"
                            check(!visibleBounds.isEmpty)
                            lastCheck = "not enabled"
                            check(isEnabled)
                        }
                        else -> error("Unsupported action node: $this")
                    }
                    true
                }.getOrDefault(false)
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Action '$control' timed out after 30s: $lastCheck", error)
        }
        return this
    }

    // Enable the real stack login with E2E_LOGIN=1 ./scripts/android e2e.
    @Test fun chosenOidcSubjectCompletesUndoesAndReadsCachedTodayOffline() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("login") == "true")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Log in").waitForAction("Log in").performClick()
        assertTrue("Local OIDC page did not load in Chrome", device.wait(Until.hasObject(By.pkg("com.android.chrome").text("Mock OAuth2 Server Sign-in")), 30_000))
        device.waitForIdle()
        val subject = device.wait(Until.findObject(By.pkg("com.android.chrome").clazz("android.widget.EditText")), 30_000)
        assertNotNull("OIDC subject input did not appear in the Custom Tab", subject)
        subject.waitForAction("OIDC subject input").text = "synthetic-a03-owner"
        assertTrue("OIDC subject was not entered", device.wait(Until.hasObject(By.text("synthetic-a03-owner")), 10_000))
        val keyboard = By.pkg("com.google.android.inputmethod.latin")
        if (device.wait(Until.hasObject(keyboard), 2_000)) {
            device.pressBack()
            assertTrue("Keyboard did not close", device.wait(Until.gone(keyboard), 5_000))
        }
        // Swipe the page gutter, not the claims textarea, without pulling to refresh.
        var signIn = device.findObject(By.text("Sign-in"))
        for (attempt in 1..8) {
            if (signIn != null) break
            device.swipe(device.displayWidth * 95 / 100, device.displayHeight * 3 / 4,
                device.displayWidth * 95 / 100, device.displayHeight / 2, 80)
            signIn = device.wait(Until.findObject(By.text("Sign-in")), 2_000)
        }
        assertNotNull("OIDC Sign-in button missing", signIn)
        device.waitForIdle()
        signIn.waitForAction("OIDC Sign-in").click()
        device.wait(Until.findObject(By.textContains("Open")), 2_000)?.waitForAction("Open redirect")?.click()
        val returned = device.wait(Until.hasObject(By.pkg("com.epaflix.fytorium")), 30_000)
        assertTrue("OIDC redirect did not return to Fytorium", returned)
        val app = compose.activity.application as FytoriumApplication
        try {
            try { compose.waitUntil(30_000) { app.repository.state.value.member != null && !app.repository.state.value.busy } }
            catch (error: Throwable) { throw AssertionError("Initial care refresh: member=${app.repository.state.value.member}, busy=${app.repository.state.value.busy}, offline=${app.repository.state.value.offline}, message=${app.repository.state.value.message}, signedIn=${app.store.tokens != null}", error) }
            compose.onNodeWithText("Plants").assertIsDisplayed()
            com.epaflix.fytorium.sites.runSiteScenario(compose, app)
            com.epaflix.fytorium.household.runHouseholdScenario(compose, app)
            careScenarios(app, device)
        } finally { if (app.store.tokens != null) app.api.logout() }
    }

    private fun careScenarios(app: FytoriumApplication, device: UiDevice) {
        var plantId: String? = null
        var siteId: String? = null
        var failure: Throwable? = null
        fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
            app.api.request(path, method = method, body = body, query = query).use {
                val text = it.body?.string().orEmpty()
                assertTrue("API $path returned ${it.code}: $text", it.isSuccessful)
                text
            }
        try {
            // Recover only this scenario's synthetic fixtures after an interrupted run.
            val previousPlants = JSONArray(json("/api/plants"))
            (0 until previousPlants.length()).map { previousPlants.getJSONObject(it) }.filter { it.optString("nickname") == "Synthetic A04 fern" }.forEach {
                json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", "Synthetic A04 fern"))
            }
            val previousSites = JSONArray(json("/api/sites"))
            (0 until previousSites.length()).map { previousSites.getJSONObject(it) }.filter { it.getString("name") == "Synthetic A04 site" }.forEach {
                json("/api/sites/${it.getString("id")}", "DELETE")
            }
            // Login, all API reads and writes use the real server. Only the clock uses a __test route.
            clock("set", "2030-06-01T10:00:00Z")
            siteId = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"Synthetic A04 site","location":"indoor","light":"part_shade","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
            val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
            val plant = JSONObject("""{"nickname":"Synthetic A04 fern","pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
                .put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
            plantId = JSONObject(json("/api/plants", "POST", plant)).getString("id")
            json("/api/tasks/settings", "PUT", JSONObject("""{"sort":"task","filter":"all","show_completed":false}"""))
            clock("set", "2030-06-02T10:00:00Z")
            app.care.refresh()
            compose.waitUntil(30_000) { !app.repository.state.value.busy && app.repository.state.value.today?.today == "2030-06-02" }
            val waterTag = "task:$plantId:water"
            fun button(tag: String, label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)))
            compose.onNode(hasText("1d late") and hasAnyAncestor(hasTestTag(waterTag))).performScrollTo().assertIsDisplayed()
            button(waterTag, "Done").performScrollTo().waitForAction("$waterTag Done").performClick()
            compose.waitUntil(30_000) { !app.repository.state.value.busy && app.repository.state.value.today?.sections?.flatMap { it.tasks }?.any { it.plantId == plantId && it.taskType == "water" && it.completedEvent != null } == true }
            button(waterTag, "Undo").performScrollTo().waitForAction("$waterTag Undo").performClick()
            try {
                compose.waitUntil(30_000) { !app.repository.state.value.busy && app.repository.state.value.today?.sections?.flatMap { it.tasks }?.any { it.plantId == plantId && it.taskType == "water" && it.completedEvent == null && it.lateDays == 1 } == true }
            } catch (error: Throwable) {
                val state = app.repository.state.value
                val water = state.today?.sections?.flatMap { it.tasks }?.firstOrNull { it.plantId == plantId && it.taskType == "water" }
                throw AssertionError("Undo result: busy=${state.busy}, offline=${state.offline}, connected=${app.care.connected()}, message=${state.message}, completed=${water?.completedEvent != null}, lateDays=${water?.lateDays}", error)
            }
            val history = JSONArray(json("/api/plants/$plantId/history"))
            assertTrue("Server did not undo the care event", (0 until history.length()).any { val e = history.getJSONObject(it); e.optString("task_type") == "water" && !e.isNull("undone_at") })
            compose.onNodeWithTag("view-upcoming").waitForAction("view-upcoming").performClick()
            val future = app.repository.state.value.upcoming!!.sections.flatMap { it.tasks }.first { it.plantId == plantId && it.dueOn!! > "2030-06-02" }
            val futureTag = "task:$plantId:${future.key}"
            compose.onNodeWithTag(futureTag).performScrollTo()
            button(futureTag, "Snooze 2 days").assertDoesNotExist()
            compose.onNodeWithTag("view-today").waitForAction("view-today").performClick()
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            app.care.refresh()
            compose.waitUntil(30_000) {
                runCatching {
                    compose.onNode(hasText("Offline, last updated", substring = true)).performScrollTo().assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            button(waterTag, "Done").assertIsNotEnabled()
            button(waterTag, "Snooze 2 days").assertIsNotEnabled()
            // This negative-control click must stay disabled to prove it cannot queue a write.
            button(waterTag, "Done").performScrollTo().performClick()
            assertEquals("Cached Today was lost in airplane mode", "Synthetic A04 fern", app.repository.state.value.today!!.sections.flatMap { it.tasks }.first { it.plantId == plantId }.plantName)
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            waitForGuestNetwork(app)
            app.care.refresh()
            compose.waitUntil(30_000) { !app.repository.state.value.busy && !app.repository.state.value.offline }
            assertEquals("Offline click queued a write", history.length(), JSONArray(json("/api/plants/$plantId/history")).length())
            println("A04 E2E PASS: late complete/undo, future snooze absent, cached Today in airplane mode, writes disabled and not queued")
        } catch (error: Throwable) { failure = error; throw error } finally {
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
            val cleanup = runCatching {
            waitForGuestNetwork(app)
            if (plantId != null) json("/api/plants/$plantId", "DELETE", JSONObject().put("confirm_name", "Synthetic A04 fern"))
            if (siteId != null) json("/api/sites/$siteId", "DELETE")
            clock("reset")
            app.api.logout()
            compose.waitUntil(30_000) { !app.session.state.value.loggedIn }
            assertNull(app.repository.state.value.member)
            assertTrue(androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(CareController.WORK_NAME).get().all { it.state == androidx.work.WorkInfo.State.CANCELLED })
            }
            if (failure == null) cleanup.getOrThrow()
        }
    }
    private fun waitForGuestNetwork(app: FytoriumApplication) {
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        val timeoutSeconds = InstrumentationRegistry.getArguments().getString("guestNetworkTimeoutSeconds")?.toLong() ?: 180L
        require(timeoutSeconds in 1..3600) { "Guest network timeout must be from 1 to 3600 seconds" }
        var lastFailure: Throwable? = null
        try {
            compose.waitUntil(timeoutSeconds * 1_000) {
                val network = connectivity.activeNetwork
                if (network == null || !app.care.connected()) false else runCatching {
                    // Probe readiness only, never retry a repository refresh or scenario API call.
                    check(java.net.InetAddress.getAllByName("app").isNotEmpty())
                    val connection = java.net.URL("http://app:3000/healthz").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    connection.setRequestProperty("Connection", "close")
                    try { connection.responseCode == 200 && network == connectivity.activeNetwork && app.care.connected() }
                    finally { connection.disconnect() }
                }.getOrElse { lastFailure = it; false }
            }
        } catch (error: Throwable) {
            throw AssertionError("Guest did not regain a validated network, app DNS and HTTP within ${timeoutSeconds}s; last probe: $lastFailure", error)
        }
    }
    private fun clock(action: String, at: String? = null) {
        val connection = java.net.URL("http://app:3000/__test/clock").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        try {
            connection.outputStream.use { it.write(JSONObject().put("action", action).apply { if (at != null) put("at", at) }.toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
        } finally { connection.disconnect() }
    }
}
