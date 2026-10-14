package com.epaflix.fytorium

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lightAndDarkThemesSetReadableSystemBarIcons() {
        val dark = mutableStateOf(false)
        var bars: androidx.core.view.WindowInsetsControllerCompat? = null
        compose.setContent { FytoriumTheme(dark.value) {
            val view = androidx.compose.ui.platform.LocalView.current
            bars = androidx.core.view.WindowCompat.getInsetsController((view.context as android.app.Activity).window, view)
            androidx.compose.material3.Text("System bars")
        } }
        compose.runOnIdle { assertTrue(bars!!.isAppearanceLightStatusBars); assertTrue(bars!!.isAppearanceLightNavigationBars); dark.value = true }
        compose.runOnIdle { assertFalse(bars!!.isAppearanceLightStatusBars); assertFalse(bars!!.isAppearanceLightNavigationBars) }
    }
    @Test fun homeSearchOpensTheExistingOfflinePlantSearch() {
        val plant = parsePlant(org.json.JSONObject(plantFixture))
        val care = CareState(member = Member("member-one", Role.owner), plants = listOf(plant), busy = true, offline = true, serverUnavailable = true)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care) } }
        compose.onNodeWithContentDescription("Search plants").assertIsEnabled().performClick()
        compose.onNodeWithText("Search plants").assertIsDisplayed().assertIsEnabled().performTextInput("Synthetic")
        compose.onNodeWithTag("plant:plant-one").assertExists()
    }
    @Test fun deviceConnectivityUpdatesAddWithoutATaskRefresh() {
        val online = mutableStateOf(true)
        val care = CareState(member = Member("member-one", Role.owner), busy = true, serverUnavailable = true)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care, deviceOnline = online.value) } }
        compose.onNodeWithContentDescription("Add plant").assertIsEnabled()
        compose.runOnIdle { online.value = false }
        compose.onNodeWithContentDescription("Add plant").assertIsNotEnabled()
        compose.runOnIdle { online.value = true }
        compose.onNodeWithContentDescription("Add plant").assertIsEnabled()
    }
    @Test fun moreKeepsSitesAndFeedSettingsIndependentOfCareFailures() {
        val feed = com.epaflix.fytorium.feed.FeedRepository(ApiClient("https://example.invalid", MemoryCredentials()) {}) { true }
        val care = CareState(member = Member("member-one", Role.owner), busy = true, offline = true, serverUnavailable = true)
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care, feed = feed) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Sites").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Feed and weather settings").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val signedIn = mutableStateOf(false)
        val update = mutableStateOf(false)
        val instance = mutableStateOf(0)
        var copies = 0
        compose.setContent { key(instance.value) { FytoriumTheme(dark) { AppShell(signedIn.value, update.value, onCopyDiagnostics = { copies++ }) } } }
        fun fresh(signed: Boolean = true, required: Boolean = false) {
            compose.runOnIdle { signedIn.value = signed; update.value = required; instance.value++ }
        }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/" + name + if (dark) "-dark.png" else "-light.png")
        }
        compose.onNodeWithText("Log in").assertHeightIsAtLeast(48.dp).assertHasClickAction().performClick()
        capture("login")
        fresh(signed = false, required = true)
        compose.onNodeWithText("Update Fytorium").assertIsDisplayed()
        capture("update")
        fresh()
        compose.onNodeWithText("No tasks yet.").assertIsDisplayed()
        capture("today")
        fresh()
        compose.onNodeWithText("Plants", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No plants yet.").assertIsDisplayed()
        capture("plants")
        fresh()
        compose.onNodeWithText("Journal", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No journal entries yet.").assertIsDisplayed()
        capture("journal")
        fresh()
        compose.onNodeWithText("More", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Log out").assertHeightIsAtLeast(48.dp)
        capture("more")
        fresh()
        compose.onNodeWithText("More", useUnmergedTree = true).performClick()
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Version " + BuildConfig.VERSION_NAME).assertIsDisplayed()
        compose.onNodeWithText("Copy diagnostics").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, copies)
        capture("about")
    }
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun followsDarkSystemSetting() {
        var background: Color? = null
        compose.setContent { FytoriumTheme { background = androidx.compose.material3.MaterialTheme.colorScheme.background } }
        compose.runOnIdle { assertEquals(DarkColors.background, background) }
    }
    @Test
    @Config(qualifiers = "w360dp-h800dp-notnight-mdpi")
    fun followsLightSystemSetting() {
        var background: Color? = null
        compose.setContent { FytoriumTheme { background = androidx.compose.material3.MaterialTheme.colorScheme.background } }
        compose.runOnIdle { assertEquals(LightColors.background, background) }
    }
    @Test fun textContrastMeetsWcagAA() {
        fun contrast(a: Color, b: Color): Double {
            val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
            return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        }
        for (scheme in listOf(LightColors, DarkColors)) {
            for ((foreground, background) in listOf(scheme.onPrimary to scheme.primary,
                scheme.onBackground to scheme.background, scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant, scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onSurface to scheme.surfaceContainerHigh, scheme.tertiary to scheme.surfaceContainerHigh)) {
                assertTrue("Text contrast below 4.5:1", contrast(foreground, background) >= 4.5)
            }
        }
    }
    @Test fun diagnosticsAreBoundedAndOnlyContainPredefinedEvents() {
        val diagnostics = Diagnostics(RuntimeEnvironment.getApplication())
        diagnostics.clear()
        repeat(100) { diagnostics.record(ErrorEvent.NETWORK_UNAVAILABLE) }
        val copied = diagnostics.copyText()
        assertEquals(65, copied.lines().size)
        assertTrue(copied.length < 8192)
        assertFalse(copied.contains("token")); assertFalse(copied.contains("verifier")); assertFalse(copied.contains("code="))
        diagnostics.clear()
        assertEquals("Fytorium " + BuildConfig.VERSION_NAME + "\n", diagnostics.copyText())
    }
}
