package com.epaflix.fytorium.sites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SiteScreenTest {
    @get:Rule val compose = createComposeRule()
    private val site = Site("site-one", mapOf("name" to "Synthetic room", "location" to "indoor", "light" to "part_sun", "humidity" to "normal",
        "warm_min_c" to "18", "warm_max_c" to "25", "cold_min_c" to "", "cold_max_c" to "20"), growLight = true, plantCount = 2, importedIncomplete = true)
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val state = mutableStateOf(SiteState(listOf(site), loaded = true))
        val online = mutableStateOf(true)
        var saved: Site? = null
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SitesScreen(state.value, online.value, onSave = { saved = it; false }, onDelete = {
                state.value = state.value.copy(errors = mapOf("_form" to "Move or remove the 2 plants on this site first.")); false
            })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("Indoor · 2 plants").assertIsDisplayed()
        compose.onNodeWithText("Create site").assertHeightIsAtLeast(48.dp)
        capture("sites")
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Confirm delete").performClick()
        compose.onNodeWithText("Move or remove the 2 plants on this site first.").assertIsDisplayed()
        capture("site-delete-refused")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { state.value = state.value.copy(errors = emptyMap()) }
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Name").assertTextContains("Synthetic room")
        capture("site-edit")
        compose.onNodeWithText("Grow light").performScrollTo().assertIsOn()
        compose.onNodeWithText("Cold minimum °C").performScrollTo().assertTextContains("")
        compose.onNodeWithText("Draft").performScrollTo().assertIsOff()
        capture("site-conditions")
        compose.onNodeWithText("Warm minimum °C").performScrollTo().performTextReplacement("26")
        compose.onNodeWithText("Save site").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("site-actions")
        compose.onNodeWithText("Save site").performClick()
        compose.runOnIdle {
            assertEquals("26", saved!!.fields["warm_min_c"])
            state.value = state.value.copy(errors = mapOf("warm_min_c" to "Minimum must not exceed maximum."))
        }
        compose.onNodeWithText("Warm minimum °C").performScrollTo()
        compose.onNodeWithText("Minimum must not exceed maximum.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithText("Create site").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(errors = emptyMap()) }
        compose.onNodeWithText("Location: indoor").assertIsDisplayed()
        compose.onNodeWithText("Light level: part sun").assertIsDisplayed()
        capture("site-create")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Save site").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").performScrollTo().assertIsDisplayed()
        capture("site-offline")
    }
    @Test fun aFailedReloadAfterSavingStaysVisibleOnTheList() {
        val state = mutableStateOf(SiteState(loaded = true))
        compose.setContent { FytoriumTheme { SitesScreen(state.value,
            onClear = { state.value = state.value.copy(errors = emptyMap()) }, onSave = {
                state.value = state.value.copy(blocked = true, errors = mapOf("_form" to "Could not reach the server. Try again.")); true
            }) } }
        compose.onNodeWithText("Create site").performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.onNodeWithText("Could not reach the server. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Create site").assertIsNotEnabled()
        compose.onNodeWithText("Refresh sites").assertIsEnabled()
    }
    @Test fun refreshAfterAFailedWriteKeepsTheUnsavedForm() {
        val state = mutableStateOf(SiteState(loaded = true))
        compose.setContent { FytoriumTheme { SitesScreen(state.value, onSave = {
            state.value = state.value.copy(blocked = true, errors = mapOf("_form" to "Could not reach the server. Try again.")); false
        }, onRefresh = { state.value = state.value.copy(blocked = false, errors = emptyMap()) }) } }
        compose.onNodeWithText("Create site").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic unsaved room")
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.onNodeWithText("Save site").assertIsNotEnabled()
        compose.onNodeWithText("Refresh sites").performScrollTo().performClick()
        compose.onNodeWithText("Name").assertTextContains("Synthetic unsaved room")
        compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
    }
    @Test fun careRefreshDoesNotDisableAnOnlineSiteForm() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[]"))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner)))
            compose.setContent { FytoriumTheme { SitesRoute(api, care.value, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create site").performClick()
            compose.runOnIdle { care.value = care.value.copy(busy = true) }
            compose.onNodeWithText("Name").assertIsEnabled().performTextReplacement("Synthetic online room")
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").assertDoesNotExist()
        }
    }
    @Test fun successfulSitesRefreshEnablesTheFormDespiteCareServerUnavailable() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[]"))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = CareState(member = Member("synthetic-member", Role.owner), serverUnavailable = true, offline = true)
            compose.setContent { FytoriumTheme { SitesRoute(api, care, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Could not reach the server. Try again.")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create site").assertIsNotEnabled()
            compose.onNodeWithText("Refresh sites").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create site").performClick()
            compose.onNodeWithText("Name").assertIsEnabled().performTextReplacement("Synthetic recovered room")
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun onlyOwnerAndFamilySeeSitesUnderMore() {
        val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Sites").assertIsDisplayed()
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.family)) }
        compose.onNodeWithText("Sites").assertIsDisplayed()
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
        compose.onNodeWithText("Sites").assertDoesNotExist()
    }
}
