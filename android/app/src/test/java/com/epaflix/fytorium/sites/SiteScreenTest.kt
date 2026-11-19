package com.epaflix.fytorium.sites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SiteScreenTest {
    @get:Rule val compose = createComposeRule()
    private val site = Site("site-one", mapOf("name" to "Synthetic room", "location" to "indoor", "light" to "part_sun", "humidity" to "normal",
        "warm_min_c" to "18", "warm_max_c" to "25", "cold_min_c" to "", "cold_max_c" to "20"), growLight = true, plantCount = 2, importedIncomplete = true)
    @Test fun aSiteChoiceStaysOpenAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var choice = ""
        restoration.setContent { FytoriumTheme {
            Choice("Synthetic condition", "indoor", listOf("indoor" to "Indoor", "outdoor" to "Outdoor"), true) { choice = it }
        } }
        compose.onNodeWithText("Synthetic condition: Indoor").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Outdoor").performClick()
        assertEquals("outdoor", choice)
    }

    @Test fun aNewSiteDraftInTheSitesTabSurvivesRecreation() = siteDraftSurvivesRecreation(edit = false)
    @Test fun anExistingSiteDraftInTheSitesTabSurvivesRecreation() = siteDraftSurvivesRecreation(edit = true)

    private fun siteDraftSurvivesRecreation(edit: Boolean) {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var saved: Site? = null
        restoration.setContent { FytoriumTheme { SitesScreen(SiteState(listOf(site), loaded = true), onSave = { saved = it; true }) } }
        compose.onNodeWithText(if (edit) "Edit" else "Create site").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
        compose.onNodeWithText("Grow light").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(if (edit) "Edit site" else "New site").assertIsDisplayed()
        compose.onNodeWithText("Name").performScrollTo().assertTextContains("Synthetic porch")
        compose.onNodeWithText("Grow light").performScrollTo().let { if (edit) it.assertIsOff() else it.assertIsOn() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard(if (edit) "Your changes to Synthetic room are not saved yet." else "Your changes to this new site are not saved yet.")
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(if (edit) "site-one" else null, saved!!.id)
            assertEquals("Synthetic porch", saved!!.fields["name"])
        }
    }

    @Test fun aSiteDiscardQuestionSurvivesRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SitesScreen(SiteState(listOf(site), loaded = true)) } }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
        compose.onNodeWithContentDescription("Back").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Keep editing").assertIsDisplayed()
    }

    @Test fun aSiteDeletionQuestionSurvivesAnOfflineReload() {
        val state = mutableStateOf(SiteState(listOf(site), loaded = true))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SitesScreen(state.value, online = state.value.loaded) } }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { state.value = SiteState() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Delete site?").assertIsDisplayed()
        compose.onNodeWithText("This permanently deletes Synthetic room and its settings if it has no plants.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm delete").assertIsNotEnabled()
    }

    @Test fun anOfflineRecreatedSiteEditorKeepsItsDraftAndDiscardsItBeforeCreate() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(
                    """[{"id":"site-one","name":"Synthetic room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false}]""")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val online = mutableStateOf(true)
            val answers = mutableIntStateOf(0)
            var dirty: () -> String? = { null }
            var exits = 0
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme { SitesRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { online.value },
                { exits++ }, answers = answers.intValue, onFormDirty = { dirty = it }) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit").performClick()
            compose.onNodeWithText("Name").performTextReplacement("Synthetic porch")
            compose.runOnIdle { online.value = false }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Edit site").assertIsDisplayed()
            compose.onNodeWithText("Name").assertTextContains("Synthetic porch")
            compose.runOnIdle { assertEquals("Your changes to Synthetic room are not saved yet.", dirty()) }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your changes to Synthetic room are not saved yet.")
            compose.onNodeWithText("Discard").performClick()
            compose.runOnIdle { assertEquals(0, exits); online.value = true; answers.intValue++ }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create site").performClick()
            compose.onNode(hasText("Name") and hasSetTextAction()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        }
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun siteDeletionStaysUsableInLandscapeWithLargeText() = landscapeDeletion("Synthetic room", "site-delete-landscape-large")

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun longNameSiteDeletionStaysUsableInLandscapeWithLargeText() = landscapeDeletion("Synthetic room ".repeat(15).take(200), "site-delete-long-name-landscape-large")

    private fun landscapeDeletion(name: String, screenshot: String) {
        val current = site.copy(fields = site.fields + ("name" to name))
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme { SitesScreen(SiteState(listOf(current), loaded = true)) } }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        val title = compose.onNodeWithText("Delete site?", useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertTrue("The short title fits in two lines", title.lineCount <= 2 && !title.isLineEllipsized(title.lineCount - 1))
        compose.onNodeWithText("This permanently deletes $name and its settings if it has no plants.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(name) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Confirm delete").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.checkDialogTextScale("Confirm delete", 1.3f)
        compose.captureDialog("src/test/screenshots/$screenshot.png")
    }

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
            val file = "src/test/screenshots/$name-${if (dark) "dark" else "light"}.png"
            if (name == "site-delete-refused") compose.captureDialog(file) else compose.onRoot().captureRoboImage(file)
        }
        compose.onNodeWithText("Indoor · 2 plants").assertIsDisplayed()
        compose.onNodeWithText("Create site").assertHeightIsAtLeast(48.dp)
        capture("sites")
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Confirm delete").performClick()
        compose.onNodeWithText("Move or remove the 2 plants on this site first.").assertIsDisplayed()
        compose.onNodeWithText("This permanently deletes Synthetic room and its settings if it has no plants.").performScrollTo().assertIsDisplayed()
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
        compose.assertDiscard("Your changes to Synthetic room are not saved yet.")
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Create site").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(errors = emptyMap()) }
        compose.onNodeWithText("Location: indoor").assertIsDisplayed()
        compose.onNodeWithText("Light level: part sun").assertIsDisplayed()
        capture("site-create")
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Save site").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Offline").assertIsDisplayed()
        capture("site-offline")
        compose.onNodeWithText("Offline").performClick()
        compose.onNodeWithText("This phone can't reach Fytorium. Reconnect to make changes.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }
    // C1b: a reload that fails after a save shows in the top bar's line on the list.
    @Test fun aFailedReloadAfterSavingStaysVisibleOnTheList() {
        val state = mutableStateOf(SiteState(loaded = true))
        compose.setContent { FytoriumTheme { SitesScreen(state.value,
            onClear = { state.value = state.value.copy(errors = emptyMap()) }, onSave = {
                state.value = state.value.copy(blocked = true, readError = "Could not reach the server. Try again."); true
            }) } }
        compose.onNodeWithText("Create site").performClick()
        compose.onNodeWithText("Save site").performScrollTo().performClick()
        compose.explains("Sites didn't load", "Could not reach the server. Try again.")
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
            compose.onNodeWithText("Offline").assertDoesNotExist()
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
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Sites didn't load") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create site").assertIsNotEnabled()
            compose.onNodeWithText("Refresh sites").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sites didn't load").assertDoesNotExist()
            compose.onNodeWithText("Create site").performClick()
            compose.onNodeWithText("Name").assertIsEnabled().performTextReplacement("Synthetic recovered room")
            compose.onNodeWithText("Save site").performScrollTo().assertIsEnabled()
            assertEquals(2, server.requestCount)
        }
    }
    // C19: under More, leaving a site form with changes through the top bar's Back, Cancel or the navigation bar asks first.
    @Test fun leavingASiteFormWithChangesAsksFirst() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(if (request.path == "/api/sites")
                    """[{"id":"site-one","name":"Synthetic room","location":"indoor","light":"part_sun","humidity":"normal","grow_light":false,"draft":false,"plant_count":2}]""" else "{}")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("synthetic-member", Role.owner)), sitesApi = api, sitesConnected = { true }) } }
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Sites").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit").performClick()
            compose.onNodeWithText("Name").performTextReplacement("Synthetic den")
            val question = "Your changes to Synthetic room are not saved yet."
            for (leave in listOf<() -> Unit>({ compose.onNodeWithContentDescription("Back").performClick() }, { compose.onNodeWithText("Cancel").performScrollTo().performClick() },
                { compose.onNode(hasText("Today") and hasClickAction()).performClick() })) {
                leave()
                compose.onNodeWithText("Discard changes?").assertIsDisplayed()
                compose.onNodeWithText(question).assertIsDisplayed()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithText("Name").assertTextContains("Synthetic den")
            }
            compose.onNode(hasText("Today") and hasClickAction()).performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Save site").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Discard changes?").assertDoesNotExist()
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
