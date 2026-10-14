package com.epaflix.fytorium.household

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
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
class HouseholdScreenTest {
    @get:Rule val compose = createComposeRule()
    private val plant = TaskTarget("plant-one", "Synthetic fern", "plant")
    private val member = HouseholdMember("member-one", "Synthetic sitter", "sitter", setOf(plant.id), listOf("family", "sitter"), true)
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val area = mutableStateOf(HouseholdArea.Members)
        val state = mutableStateOf(HouseholdState(loaded = true, plants = listOf(plant), members = listOf(member),
            invites = listOf(HouseholdInvite("invite-one", "sitter", "2030-06-08", setOf(plant.id))),
            feed = feedLabels.keys.associateWith { true }))
        val online = mutableStateOf(true)
        var removes = 0
        var share: String? = null
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HouseholdScreen(area.value, state.value, Role.owner, online.value, onCreateInvite = { _, _ -> "/invite/synthetic-link" },
                onShare = { share = it }, onRemove = { removes++; true })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/a09-$name-${if (dark) "dark" else "light"}.png"
            if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
                val layout = compose.onNode(isDialog()).fetchSemanticsNode().layoutInfo
                val owner = layout.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layout) as android.view.View
                compose.runOnIdle { owner.drawToBitmap().captureRoboImage(file) }
            } else compose.onRoot().captureRoboImage(file)
        }
        capture("members")
        compose.onNodeWithText("Create invite").performClick()
        capture("invite-create")
        compose.onNodeWithText("Role: Family").performClick()
        compose.onNodeWithText("Sitter").performClick()
        compose.onNodeWithText("Synthetic fern").performClick()
        compose.onNodeWithText("Create invite link").performClick()
        capture("invite-link")
        compose.onNodeWithText("Share invite link").performClick()
        compose.runOnIdle { assertEquals("/invite/synthetic-link", share) }
        compose.onNodeWithContentDescription("Back").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Edit member").performScrollTo().performClick()
        capture("shares")
        compose.onNodeWithContentDescription("Back").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Remove member").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, removes) }
        capture("remove")
        compose.onNodeWithText("Confirm remove").performClick()
        compose.runOnIdle { assertEquals(1, removes) }
        compose.onNodeWithText("Revoke invite").performScrollTo().performClick()
        capture("revoke")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { area.value = HouseholdArea.Settings }
        capture("settings")
        compose.onNodeWithText("Save location").performScrollTo()
        capture("location")
        compose.onNodeWithText("Save household settings").performScrollTo()
        capture("misting")
        compose.runOnIdle { area.value = HouseholdArea.Tasks; state.value = state.value.copy(target = plant, tasks = listOf(CustomTask("task-one", "Inspect", "14"))) }
        capture("tasks")
        compose.onNodeWithText("Edit task").performScrollTo().performClick()
        capture("task-edit")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Save task").performScrollTo().assertIsNotEnabled()
        capture("offline")
        compose.runOnIdle { online.value = true }
        compose.onNodeWithContentDescription("Back").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Delete task").performScrollTo().performClick()
        capture("task-delete")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Add custom task").performScrollTo().performClick()
        capture("task-create")
        compose.onNodeWithContentDescription("Back").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(target = TaskTarget("site-one", "Synthetic site", "site")) }
        capture("site-tasks")
    }
    @Test fun eachRouteIgnoresCareBusyOfflineAndServerUnavailable() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic", Role.owner), busy = true, offline = true, serverUnavailable = true))
            val area = mutableStateOf(HouseholdArea.Members)
            fun enqueue(body: String) { server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body)) }
            enqueue("[]"); enqueue("[]"); enqueue("[]")
            compose.setContent { FytoriumTheme { key(area.value) { HouseholdRoute(api, area.value, care.value, { true }, {}) } } }
            fun waitEnabled(text: String) { compose.waitUntil(5_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
            waitEnabled("Create invite")
            compose.onNodeWithText("Create invite").performClick()
            compose.onNodeWithText("Create invite link").assertIsEnabled()
            enqueue("{\"misting_enabled\":true}"); enqueue("{\"location\":null}"); enqueue("{\"late_tasks\":true,\"current_weather\":true,\"weather_alerts\":true,\"plant_warnings\":true}")
            compose.runOnIdle { area.value = HouseholdArea.Settings }
            waitEnabled("Save my feed")
            compose.onNodeWithText("Save household settings").performScrollTo().assertIsEnabled()
            enqueue("[]"); enqueue("[]"); enqueue("[]")
            compose.runOnIdle { area.value = HouseholdArea.Tasks }
            waitEnabled("Target: ")
            compose.runOnIdle { care.value = care.value.copy(updateRequired = true) }
            compose.onNodeWithText("Target: ").assertIsNotEnabled()
        }
    }
    @Test fun sittersHaveNoHouseholdScreensAndFamilyHasNoMembers() {
        val role = mutableStateOf(Role.owner)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("synthetic", role.value))) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Members").assertIsDisplayed()
        compose.runOnIdle { role.value = Role.family }
        compose.onNodeWithText("Members").assertDoesNotExist()
        compose.onNodeWithText("Household settings").assertIsDisplayed()
        compose.onNodeWithText("Custom tasks").assertIsDisplayed()
        compose.runOnIdle { role.value = Role.sitter }
        compose.onNodeWithText("Household settings").assertDoesNotExist()
        compose.onNodeWithText("Custom tasks").assertDoesNotExist()
    }
}
