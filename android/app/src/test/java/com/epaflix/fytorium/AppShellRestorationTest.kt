package com.epaflix.fytorium

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import okhttp3.mockwebserver.*
import com.epaflix.fytorium.feed.*
import com.epaflix.fytorium.species.careFixture
import com.epaflix.fytorium.species.outdoorFixture
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class AppShellRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredSitesAndUnmatchedSurviveFirstComposition() = navigation(null)
    @Test fun logoutClearsRestoredSitesAndUnmatched() = navigation("logout")
    @Test fun sitterClearsRestoredSitesAndUnmatched() = navigation("sitter")
    @Test fun differentMemberClearsRestoredSitesAndUnmatched() = navigation("member")

    @Test fun restoredSpeciesCardSurvivesFirstComposition() = navigation(null, "species")
    @Test fun logoutClearsRestoredSpeciesCard() = navigation("logout", "species")
    @Test fun sitterClearsRestoredSpeciesCard() = navigation("sitter", "species")
    @Test fun differentMemberClearsRestoredSpeciesCard() = navigation("member", "species")
    @Test fun restoredFeedSettingsSurviveFirstComposition() = navigation(null, "feed-settings")
    @Test fun logoutClearsRestoredFeedSettings() = navigation("logout", "feed-settings")
    @Test fun sitterClearsRestoredFeedSettings() = navigation("sitter", "feed-settings")
    @Test fun differentMemberClearsRestoredFeedSettings() = navigation("member", "feed-settings")
    @Test fun restoredFeedPlantSurvivesFirstComposition() = navigation(null, "feed-plant")
    @Test fun logoutClearsRestoredFeedPlant() = navigation("logout", "feed-plant")
    @Test fun sitterClearsRestoredFeedPlant() = navigation("sitter", "feed-plant")
    @Test fun differentMemberClearsRestoredFeedPlant() = navigation("member", "feed-plant")
    @Test fun restoredPlantSpeciesCareSurvivesFirstComposition() = navigation(null, "plant-species")
    @Test fun logoutClearsRestoredPlantSpeciesCare() = navigation("logout", "plant-species")
    @Test fun sitterClearsRestoredPlantSpeciesCare() = navigation("sitter", "plant-species")
    @Test fun differentMemberClearsRestoredPlantSpeciesCare() = navigation("member", "plant-species")
    @Test fun sameMemberChangingToFamilyKeepsFeedPlantNavigation() = navigation("family", "feed-plant")

    @Test fun sitterKeepsPlantsAcrossRestore() = sitterNavigation("Plants")
    @Test fun sitterKeepsMoreAcrossRestore() = sitterNavigation("More")
    @Test fun sitterKeepsAboutAcrossRestore() = sitterNavigation("About")
    @Test fun sitterKeepsOpenPlantAcrossRestore() = sitterNavigation("detail")
    @Test fun sitterKeepsUpcomingAcrossRestore() = sitterNavigation("upcoming")
    @Test fun becomingSitterKeepsPlants() = sitterNavigation("Plants", roleChange = true)
    @Test fun becomingSitterKeepsMore() = sitterNavigation("More", roleChange = true)
    @Test fun becomingSitterKeepsAbout() = sitterNavigation("About", roleChange = true)
    @Test fun becomingSitterKeepsUpcoming() = sitterNavigation("upcoming", roleChange = true)
    @Test fun becomingSitterClosesJournal() = sitterNavigation("Journal", roleChange = true)

    private fun sitterNavigation(route: String, roleChange: Boolean = false) {
        val plant = parsePlant(JSONObject(plantFixture))
        val care = mutableStateOf(CareState(member = Member("member", if (roleChange) Role.family else Role.sitter), plants = listOf(plant),
            detail = PlantDetail(plant, emptyList(), emptyMap())))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        val tab = when (route) { "detail" -> "Plants"; "About" -> "More"; "upcoming" -> "Today"; else -> route }
        fun navigationTab(title: String) = compose.onNode(hasText(title) and hasClickAction() and !hasTestTag("view-today"))
        navigationTab(tab).performClick()
        when (route) {
            "About" -> compose.onNodeWithText("About").performClick()
            "detail" -> compose.onNodeWithTag("plant:${plant.id}").performClick()
            "upcoming" -> compose.onNodeWithTag("view-upcoming").performClick()
        }
        restoration.emulateSavedInstanceStateRestore()
        if (roleChange) compose.runOnIdle { care.value = care.value.copy(member = Member("member", Role.sitter)) }
        navigationTab(if (route == "Journal") "Today" else tab).assertIsSelected()
        when (route) {
            "Journal" -> compose.onNodeWithText("Journal").assertDoesNotExist()
            "Plants" -> compose.onNodeWithTag("plant:${plant.id}").assertIsDisplayed()
            "More" -> compose.onNodeWithText("Log out").assertIsDisplayed()
            "About" -> compose.onNodeWithText("Copy diagnostics").assertIsDisplayed()
            "detail" -> compose.onNodeWithText("Care tasks are on Today and Upcoming.").assertIsDisplayed()
            "upcoming" -> compose.onNodeWithTag("view-upcoming").assertIsSelected()
        }
    }

    private fun navigation(reset: String?, route: String = "sites-unmatched") {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.path) {
                    "/api/feed" -> """{"settings":{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true},"cards":[{"id":"late","kind":"late_tasks","href":"/#today","count":1,"plants":[{"id":"plant-one","name":"Synthetic fern"}]}]}"""
                    "/api/weather" -> """{"locationSet":false,"forecastFetchedAt":null,"rain":null,"lastRainBatch":null}"""
                    "/api/household/location" -> """{"location":null}"""
                    "/api/species/unmatched" -> """[{"gbif_key":910002,"canonical_name":"Testoria nebulosa","status":"no_match"}]"""
                    "/api/species/910002/care", "/api/species/910001/care" -> JSONObject(careFixture).put("fetches", org.json.JSONArray()).toString()
                    "/api/species/910002/outdoor-months", "/api/species/910001/outdoor-months" -> outdoorFixture
                    else -> "[]"
                })
            }
            server.start()
            val client = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
                tokens = Tokens("synthetic-access", "synthetic-refresh")
            }) {}
            val plant = parsePlant(JSONObject(plantFixture))
            val initialCare = CareState(member = Member("owner", if (reset == "sitter") Role.family else Role.owner), plants = listOf(plant),
                detail = PlantDetail(plant, emptyList(), emptyMap()), busy = true, offline = true, serverUnavailable = true)
            val care = mutableStateOf(initialCare)
            val feed = FeedRepository(client, { true }).apply { refresh(initialCare.member) }
            val loggedIn = mutableStateOf(true)
            val api = mutableStateOf<ApiClient?>(if (route == "sites-unmatched") null else client)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme {
                AppShell(loggedIn.value, false, careState = care.value, feed = feed, sitesApi = api.value, sitesConnected = { true })
            } }
            when (route) {
                "sites-unmatched" -> {
                    compose.onNodeWithText("More").performClick()
                    // With no API attached, both saved routes can be set through their actual buttons.
                    compose.onNodeWithText("Sites").performClick()
                    compose.onNodeWithText("Unmatched species").performClick()
                }
                "species" -> {
                    compose.onNodeWithText("More").performClick()
                    compose.onNodeWithText("Unmatched species").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("Species care for Testoria nebulosa").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Species care for Testoria nebulosa").performScrollTo().performClick()
                }
                "feed-settings" -> {
                    compose.onNodeWithText("More").performClick()
                    compose.onNodeWithText("Feed and weather settings").performClick()
                }
                else -> {
                    compose.onNodeWithText("Synthetic fern").performScrollTo().performClick()
                    if (route == "plant-species") {
                        compose.onNodeWithContentDescription("Plant settings").performClick()
                        compose.onNodeWithText("Species care").performClick()
                    }
                }
            }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle {
                when (reset) {
                    "logout" -> loggedIn.value = false
                    "sitter" -> care.value = initialCare.copy(member = Member("owner", Role.sitter))
                    "member" -> care.value = initialCare.copy(member = Member("other", Role.owner))
                    "family" -> care.value = initialCare.copy(member = Member("owner", Role.family))
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                loggedIn.value = true
                if (reset == "sitter") care.value = initialCare
                api.value = client
            }
            if (reset == null || reset == "family") {
                when (route) {
                    "sites-unmatched" -> {
                        compose.onNodeWithText("Open Plantbook unmatched species").assertIsDisplayed()
                        compose.onNodeWithContentDescription("Back").performClick()
                        compose.onNodeWithText("A site groups plants that share the same conditions.").assertIsDisplayed()
                    }
                    "species", "plant-species" -> compose.onNodeWithText("Species care").assertIsDisplayed()
                    "feed-settings" -> compose.onNodeWithText("Save my feed").assertIsDisplayed().assertIsEnabled()
                    "feed-plant" -> compose.onNodeWithText("Edit plant").assertIsDisplayed().assertIsEnabled()
                }
            } else {
                if (reset == "sitter") {
                    val tab = if (route in listOf("feed-plant", "plant-species")) "Plants" else "More"
                    compose.onNode(hasText(tab) and hasClickAction()).assertIsSelected()
                } else compose.onNodeWithText("Home feed").assertIsDisplayed()
                compose.onNodeWithText("Save my feed").assertDoesNotExist()
                compose.onNodeWithText("Edit plant").assertDoesNotExist()
                compose.onNodeWithText("Species care").assertDoesNotExist()
                compose.onNode(hasText("More") and hasClickAction()).performClick()
                compose.onNodeWithText("Log out").assertIsDisplayed()
                compose.onNodeWithText("Open Plantbook unmatched species").assertDoesNotExist()
                compose.onNodeWithText("A site groups plants that share the same conditions.").assertDoesNotExist()
            }
        }
    }
}
