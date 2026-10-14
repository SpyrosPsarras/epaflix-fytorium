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
    @Test fun namedWaitReportsStateAndPreservesOptionalProbes() {
        val failure = assertThrows(AssertionError::class.java) {
            compose.waitForState("Deliberately absent control", 1, details = { "not found" }) { false }
        }
        assertTrue(failure.cause is ComposeTimeoutException)
        assertTrue(failure.message!!.contains("Deliberately absent control"))
        assertTrue(failure.message!!.contains("loggedIn="))
        assertTrue(failure.message!!.contains("showCompleted="))
        assertTrue(failure.message!!.contains("not found"))
        assertFalse(compose.waitForState("Optional absent control", 1, required = false) { false })
        assertTrue(compose.waitForState("Present control", 1) { true })
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
        assertTrue(policy.isCleartextTrafficPermitted("fake-push"))
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
        compose.waitForState("Action $control ready", 30_000, details = { lastCheck }) {
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
        return this
    }

    // Enable the real stack login with E2E_LOGIN=1 ./scripts/android e2e.
    @Test fun chosenOidcSubjectCompletesUndoesAndReadsCachedTodayOffline() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("login") == "true")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Log in").waitForAction("Log in").performClick()
        compose.waitForState("Local OIDC page loaded in Chrome") {
            device.hasObject(By.pkg("com.android.chrome").text("Mock OAuth2 Server Sign-in"))
        }
        val subjectSelector = By.pkg("com.android.chrome").clazz("android.widget.EditText")
        compose.waitForState("OIDC subject input visible") { device.hasObject(subjectSelector) }
        val subject = device.findObject(subjectSelector)
        subject.waitForAction("OIDC subject input").text = "synthetic-a03-owner"
        compose.waitForState("OIDC subject entered", 10_000) { device.hasObject(By.text("synthetic-a03-owner")) }
        val keyboard = By.pkg("com.google.android.inputmethod.latin")
        if (compose.waitForState("Login keyboard appeared", 2_000, required = false) { device.hasObject(keyboard) }) {
            device.pressBack()
            compose.waitForState("Keyboard closed", 5_000) { !device.hasObject(keyboard) }
        }
        // Swipe the page gutter, not the claims textarea, without pulling to refresh.
        for (attempt in 1..8) {
            if (device.hasObject(By.text("Sign-in"))) break
            device.swipe(device.displayWidth * 95 / 100, device.displayHeight * 3 / 4,
                device.displayWidth * 95 / 100, device.displayHeight / 2, 80)
            compose.waitForState("OIDC Sign-in after swipe $attempt", 2_000, required = false) {
                device.hasObject(By.text("Sign-in"))
            }
        }
        compose.waitForState("OIDC Sign-in button visible") { device.hasObject(By.text("Sign-in")) }
        device.findObject(By.text("Sign-in")).waitForAction("OIDC Sign-in").click()
        compose.waitForState("OIDC redirect returned to Fytorium") {
            if (device.hasObject(By.pkg("com.epaflix.fytorium"))) true else {
                device.findObject(By.textContains("Open"))?.let { if (it.isEnabled && !it.visibleBounds.isEmpty) it.click() }
                false
            }
        }
        val app = compose.activity.application as FytoriumApplication
        try {
            compose.waitForState("Initial care refresh after login", 30_000) { app.repository.state.value.member != null && !app.repository.state.value.busy }
            compose.onNodeWithText("Plants").assertIsDisplayed()
            runPlantScenarios(compose, app)
            com.epaflix.fytorium.sites.runSiteScenario(compose, app)
            com.epaflix.fytorium.photos.runPhotoScenario(compose, app)
            com.epaflix.fytorium.feed.runFeedScenarios(app, compose)
            com.epaflix.fytorium.species.runSpeciesCareScenario(compose, app)
            com.epaflix.fytorium.planta.runPlantaScenario(compose, app)
            com.epaflix.fytorium.notifications.runNotificationScenario(compose, app)
            runTodayScenario(compose, app)
            // A09 switches identities and restores the owner on Today; A04 logs out last.
            com.epaflix.fytorium.household.runHouseholdScenario(compose, app)
            careScenarios(app, device)
        } finally { if (app.store.tokens != null) app.api.logout() }
    }

    private fun careScenarios(app: FytoriumApplication, device: UiDevice) {
        var plantId: String? = null
        var siteId: String? = null
        var previousSettings: JSONObject? = null
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
            previousSettings = JSONObject(json("/api/tasks/settings"))
            // Undo needs a completed row even if a network callback refreshes Today.
            json("/api/tasks/settings", "PUT", TaskSettings(showCompleted = true).json())
            clock("set", "2030-06-02T10:00:00Z")
            app.care.refresh()
            compose.waitForState("Today refreshed to June 2 with completed rows enabled", 30_000) {
                val state = app.repository.state.value
                !state.busy && state.today?.today == "2030-06-02" && state.today.settings == TaskSettings(showCompleted = true)
            }
            val waterTag = "task:$plantId:water"
            fun button(tag: String, matcher: SemanticsMatcher) = compose.onNode(matcher and hasAnyAncestor(hasTestTag(tag)))
            val completeWater = hasContentDescription("Mark Water for Synthetic A04 fern done")
            compose.waitForNode(hasText("1d late") and hasAnyAncestor(hasTestTag(waterTag))).performScrollTo().assertIsDisplayed()
            compose.scrollTaskClearOfFabs(waterTag)
            compose.waitForNode(completeWater and hasAnyAncestor(hasTestTag(waterTag))).waitForAction("$waterTag complete").performClick()
            compose.waitForState("Water completion applied to Today", 30_000) { !app.repository.state.value.busy && app.repository.state.value.today?.sections?.flatMap { it.tasks }?.any { it.plantId == plantId && it.taskType == "water" && it.completedEvent != null } == true }
            // A real refresh must not remove the row between completion and Undo.
            app.repository.refresh()
            compose.waitForNode(hasText("Undo") and hasAnyAncestor(hasTestTag(waterTag)))
            compose.scrollTaskClearOfFabs(waterTag)
            button(waterTag, hasText("Undo")).waitForAction("$waterTag Undo").performClick()
            try {
                compose.waitForState("Water undo restored one-day lateness", 30_000) { !app.repository.state.value.busy && app.repository.state.value.today?.sections?.flatMap { it.tasks }?.any { it.plantId == plantId && it.taskType == "water" && it.completedEvent == null && it.lateDays == 1 } == true }
            } catch (error: Throwable) {
                val state = app.repository.state.value
                val water = state.today?.sections?.flatMap { it.tasks }?.firstOrNull { it.plantId == plantId && it.taskType == "water" }
                throw AssertionError("Undo result: busy=${state.busy}, offline=${state.offline}, connected=${app.care.connected()}, messagePresent=${state.message != null}, completed=${water?.completedEvent != null}, lateDays=${water?.lateDays}", error)
            }
            val history = JSONArray(json("/api/plants/$plantId/history"))
            assertTrue("Server did not undo the care event", (0 until history.length()).any { val e = history.getJSONObject(it); e.optString("task_type") == "water" && !e.isNull("undone_at") })
            compose.onNodeWithTag("view-upcoming").waitForAction("view-upcoming").performClick()
            val future = app.repository.state.value.upcoming!!.sections.flatMap { it.tasks }.first { it.plantId == plantId && it.dueOn!! > "2030-06-02" }
            val futureTag = "task:$plantId:${future.key}"
            compose.waitForNode(hasTestTag(futureTag)).performScrollTo()
            button(futureTag, hasContentDescription("Snooze 2 days")).assertDoesNotExist()
            compose.onNodeWithTag("view-today").waitForAction("view-today").performClick()
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            app.care.refresh()
            compose.waitForState("Offline cached Today banner visible", 30_000) {
                runCatching {
                    compose.onNode(hasText("Offline, last updated", substring = true)).performScrollTo().assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            button(waterTag, completeWater).assertIsNotEnabled()
            button(waterTag, hasContentDescription("Snooze 2 days")).assertIsNotEnabled()
            // This negative-control click must stay disabled to prove it cannot queue a write.
            button(waterTag, completeWater).performScrollTo().performClick()
            assertEquals("Cached Today was lost in airplane mode", "Synthetic A04 fern", app.repository.state.value.today!!.sections.flatMap { it.tasks }.first { it.plantId == plantId }.plantName)
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            waitForGuestNetwork(app)
            app.care.refresh()
            compose.waitForState("Online refresh after airplane mode", 30_000) { !app.repository.state.value.busy && !app.repository.state.value.offline }
            compose.onNodeWithContentDescription("Add plant").assertIsEnabled()
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
            previousSettings?.let { json("/api/tasks/settings", "PUT", it) }
            clock("reset")
            app.api.logout()
            compose.waitForState("Logout cleared session", 30_000) { !app.session.state.value.loggedIn }
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
            compose.waitForState("Guest network, app DNS and HTTP ready", timeoutSeconds * 1_000) {
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
            throw AssertionError("Guest did not regain a validated network, app DNS and HTTP within ${timeoutSeconds}s; last probe type: ${lastFailure?.javaClass?.simpleName}", error)
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
