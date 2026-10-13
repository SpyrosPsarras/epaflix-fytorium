package com.epaflix.fytorium

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShellRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredSitesAndUnmatchedSurviveFirstComposition() = navigation(null)
    @Test fun logoutClearsRestoredSitesAndUnmatched() = navigation("logout")
    @Test fun sitterClearsRestoredSitesAndUnmatched() = navigation("sitter")
    @Test fun differentMemberClearsRestoredSitesAndUnmatched() = navigation("member")

    private fun navigation(reset: String?) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[]")
            }
            server.start()
            val client = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
                tokens = Tokens("synthetic-access", "synthetic-refresh")
            }) {}
            val care = mutableStateOf(CareState(member = Member("owner", Role.owner)))
            val loggedIn = mutableStateOf(true)
            val api = mutableStateOf<ApiClient?>(null)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme {
                AppShell(loggedIn.value, false, careState = care.value, sitesApi = api.value, sitesConnected = { true })
            } }
            compose.onNodeWithText("More").performClick()
            // With no API attached, both saved routes can be set through their actual buttons.
            compose.onNodeWithText("Sites").performClick()
            compose.onNodeWithText("Unmatched species").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle {
                when (reset) {
                    "logout" -> loggedIn.value = false
                    "sitter" -> care.value = CareState(member = Member("owner", Role.sitter))
                    "member" -> care.value = CareState(member = Member("other", Role.owner))
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                loggedIn.value = true
                if (reset == "sitter") care.value = CareState(member = Member("owner", Role.owner))
                api.value = client
            }
            if (reset == null) {
                compose.onNodeWithText("Open Plantbook unmatched species").assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("A site groups plants that share the same conditions.").assertIsDisplayed()
            } else {
                compose.onNodeWithText("Log out").assertIsDisplayed()
                compose.onNodeWithText("Open Plantbook unmatched species").assertDoesNotExist()
                compose.onNodeWithText("A site groups plants that share the same conditions.").assertDoesNotExist()
            }
        }
    }
}
