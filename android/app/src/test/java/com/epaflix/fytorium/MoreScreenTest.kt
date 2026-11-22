package com.epaflix.fytorium

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoreScreenTest {
    @get:Rule val compose = createComposeRule()
    private val names = MemberNames("Synthetic Ada", "Synthetic Owen")
    private fun plant(id: String) = Plant(id, "Synthetic $id", "Testoria nova", "active", null, null, null, null, null)

    // W5a-P1, P2: one list of rows under a header naming who is logged in; the owner sees every row, family no Members or Planta.
    @Test fun moreListsRowsUnderAHeaderThatNamesTheMember() {
        val role = mutableStateOf(Role.owner)
        val opened = mutableListOf<String>()
        compose.setContent { FytoriumTheme { MoreRoot(CareState(member = Member("synthetic", role.value), names = names), true, false, open = { opened += it }, onLogout = {}) } }
        compose.onNodeWithText("Synthetic Ada").assertIsDisplayed()
        compose.onNodeWithText("Owner").assertIsDisplayed()
        for (title in listOf("Notifications", "Weather and feed", "Sites", "Members", "Custom tasks", "Import from Planta", "Species without care data", "About"))
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Weather and feed").performScrollTo().assertHeightIsAtLeast(72.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("weather"), opened) }
        compose.runOnIdle { role.value = Role.family }
        compose.onNodeWithText("Family").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Members").assertDoesNotExist()
        compose.onNodeWithText("Import from Planta").assertDoesNotExist()
    }

    // Back from a row's page returns to the same place in More (Astra W5a r2).
    @Test fun backFromAboutKeepsThePlaceInMore() {
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("synthetic", Role.owner), names = names)) } }
        compose.onNode(isTab("More")).performClick()
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.onNodeWithText("Copy diagnostics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("About").assertIsDisplayed()
    }

    // W5a-P3: a sitter's More says whose plants they look after, and keeps only Notifications, About and Log out.
    @Test fun aSitterSeesWhosePlantsTheyLookAfter() {
        val plants = mutableStateOf(listOf(plant("one"), plant("two")))
        compose.setContent { FytoriumTheme { MoreRoot(CareState(member = Member("synthetic", Role.sitter), names = names, plants = plants.value), true, false, open = {}, onLogout = {}) } }
        compose.onNodeWithText("You look after 2 plants for Synthetic Owen.").assertIsDisplayed()
        compose.onNodeWithText("Ask Synthetic Owen to share more.").assertIsDisplayed()
        compose.onNodeWithText("Weather and feed").assertDoesNotExist()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.runOnIdle { plants.value = emptyList() }
        compose.onNodeWithText("No plants are shared with you yet.").assertIsDisplayed()
        compose.onNodeWithText("Ask Synthetic Owen to share some.").assertIsDisplayed()
    }

    // W5a-P5: Log out asks first, and says what offline means.
    @Test fun logOutAsksFirstAndSaysWhatOfflineMeans() {
        val online = mutableStateOf(true)
        var loggedOut = 0
        compose.setContent { FytoriumTheme { MoreRoot(CareState(member = Member("synthetic", Role.owner), names = names), online.value, false, open = {}, onLogout = { loggedOut++ }) } }
        compose.onNodeWithText("Log out").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Log out of Fytorium?").assertIsDisplayed()
        compose.onNodeWithText("You need a connection to log in again.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, loggedOut); online.value = false }
        compose.onNodeWithText("Log out").performScrollTo().performClick()
        compose.onNodeWithText("This phone is offline, so you can't log in again until it reconnects.", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Log out").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, loggedOut) }
    }

    // W5a-P6 to P8: the login line is reserved under the button; offline it says logging in needs a connection.
    @Test fun loginKeepsItsLineUnderTheButtonAndExplainsOffline() {
        val online = mutableStateOf(true)
        val busy = mutableStateOf(false)
        var logins = 0
        compose.setContent { FytoriumTheme { LoginPage(busy.value, loginError("not_member"), online.value) { logins++ } } }
        compose.onNodeWithText("This account isn't in a Fytorium household.", substring = true).assertIsDisplayed()
        val button = compose.onNodeWithText("Log in").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Log in").performClick()
        compose.runOnIdle { assertEquals(1, logins); busy.value = true }
        compose.onNodeWithText("Logging in…").assertIsNotEnabled()
        assertEquals(button, compose.onNodeWithText("Logging in…").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { busy.value = false; online.value = false }
        compose.onNodeWithText("You're logged out. Logging in needs a connection.").assertIsDisplayed()
        // TalkBack can still reach it; a tap explains through the line and opens nothing.
        compose.onNodeWithText("Log in").assertIsEnabled().assert(hasStateDescription("Logging in needs a connection.")).performClick()
        compose.runOnIdle { assertEquals(1, logins) }
        compose.onNodeWithText("Logging in needs a connection. Turn on Wi-Fi or mobile data, then tap Log in.").assertIsDisplayed()
        assertEquals("Login was cancelled.", loginError("access_denied"))
        assertEquals("Fytorium couldn't finish the login. Try again.", loginError("server_error"))
        assertEquals("Login didn't work. Try again.", loginError("invalid"))
    }

    // W5a-P10: About names both versions, the data sources and what diagnostics hold.
    @Test fun aboutNamesVersionsAndSources() {
        compose.setContent { FytoriumTheme { AboutPage("1.1.0") {} } }
        compose.onNodeWithText("Fytorium for Android " + BuildConfig.VERSION_NAME).assertIsDisplayed()
        compose.onNodeWithText("Server 1.1.0").assertIsDisplayed()
        compose.onNodeWithText("nothing about your plants or account", substring = true).assertIsDisplayed()
        for (source in listOf("GBIF", "Wikidata", "NorTaxa", "Open-Meteo", "Open Plantbook", "Perenual", "Pl@ntNet")) compose.onNodeWithText(source).assertExists()
        compose.onNodeWithText("Source code").assertExists()
    }

    // W5a-P11, P12: the stop says which version is needed, offers Obtainium only when installed, and keeps its line.
    @Test fun theUpdateStopSaysWhichVersionAndWhereToGetIt() {
        val obtainium = mutableStateOf<(() -> Unit)?>(null)
        var checks = 0
        compose.setContent { FytoriumTheme { UpdateStop("9.9.9", "Still needs 9.9.9 or newer. Checked 08:12.", false, true, obtainium.value, { checks++ }, {}) } }
        compose.onNodeWithText("This phone has Fytorium ${BuildConfig.VERSION_NAME}. Your household's server needs 9.9.9 or newer.").assertIsDisplayed()
        compose.onNodeWithText("Get the update").assertIsDisplayed()
        compose.onNodeWithText("Open Obtainium").assertDoesNotExist()
        compose.onNodeWithText("Check again").performClick()
        compose.runOnIdle { assertEquals(1, checks); obtainium.value = {} }
        compose.onNodeWithText("Open Obtainium").assertIsDisplayed()
        compose.onNodeWithText("Still needs 9.9.9 or newer. Checked 08:12.").assertIsDisplayed()
        compose.onNodeWithText("Log out").performClick()
        compose.onNodeWithText("Log out of Fytorium?").assertIsDisplayed()
    }
}
