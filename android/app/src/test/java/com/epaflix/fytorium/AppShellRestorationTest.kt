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

    @Test fun restoredPlantaReviewSurvivesFirstCompositionForOwner() = navigation(null, "planta")
    @Test fun logoutClearsRestoredPlanta() = navigation("logout", "planta")
    @Test fun sitterClearsRestoredPlanta() = navigation("sitter", "planta")
    @Test fun familyClearsRestoredPlanta() = navigation("family", "planta")
    @Test fun differentMemberClearsRestoredPlanta() = navigation("member", "planta")

    @Test fun restoredHouseholdSurvivesFirstCompositionForOwner() = navigation(null, "household")
    @Test fun familyKeepsHouseholdSettingsAcrossRestore() = navigation(null, "household-settings", Role.family)
    @Test fun logoutClearsRestoredHousehold() = navigation("logout", "household")
    @Test fun sitterClearsRestoredHousehold() = navigation("sitter", "household")
    @Test fun differentMemberClearsRestoredHousehold() = navigation("member", "household")

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

    @Test fun restoredIdentificationSurvivesFirstComposition() = navigation(null, "identify")
    @Test fun logoutClearsRestoredIdentification() = navigation("logout", "identify")
    @Test fun sitterClearsRestoredIdentification() = navigation("sitter", "identify")
    @Test fun differentMemberClearsRestoredIdentification() = navigation("member", "identify")
    @Test fun familyCanIdentifyAcrossRestore() = navigation(null, "identify", Role.family)

    @Test fun restoredJournalSurvivesFirstComposition() = navigation(null, "journal")
    @Test fun logoutClearsRestoredJournal() = navigation("logout", "journal")
    @Test fun sitterClearsRestoredJournal() = navigation("sitter", "journal")
    @Test fun differentMemberClearsRestoredJournal() = navigation("member", "journal")
    @Test fun familyKeepsJournalAcrossRestore() = navigation(null, "journal", Role.family)
    @Test fun restoredPhotoFormKeepsDraft() = navigation(null, "photo-form")
    @Test fun logoutClearsRestoredPhotoForm() = navigation("logout", "photo-form")
    @Test fun sitterClearsRestoredPhotoForm() = navigation("sitter", "photo-form")
    @Test fun differentMemberClearsRestoredPhotoForm() = navigation("member", "photo-form")
    @Test fun familyCanAddPhotosAcrossRestore() = navigation(null, "photo-form", Role.family)
    @Test fun sameMemberChangingToFamilyKeepsPhotoForm() = navigation("family", "photo-form")
    @Test fun restoredJournalPlantLinkSurvivesFirstComposition() = navigation(null, "journal-plant")
    @Test fun logoutClearsRestoredJournalPlantLink() = navigation("logout", "journal-plant")
    @Test fun sitterClearsRestoredJournalPlantLink() = navigation("sitter", "journal-plant")
    @Test fun differentMemberClearsRestoredJournalPlantLink() = navigation("member", "journal-plant")

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

    @Test fun unknownRoleLoadingAsSitterClosesJournal() = sitterNavigation("Journal", roleChange = true, initialRole = null)
    @Test fun unknownRoleLoadingAsSitterKeepsPlants() = sitterNavigation("Plants", roleChange = true, initialRole = null)

    private fun sitterNavigation(route: String, roleChange: Boolean = false,
                                 initialRole: Role? = if (roleChange) Role.family else Role.sitter) {
        val plant = parsePlant(JSONObject(plantFixture))
        val care = mutableStateOf(CareState(member = initialRole?.let { Member("member", it) }, plants = listOf(plant),
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

    private fun navigation(reset: String?, route: String = "sites-unmatched", initialRole: Role = Role.owner) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.path) {
                    "/api/import/planta" -> "[${com.epaflix.fytorium.planta.importFixture}]"
                    "/api/journal" -> "[" + JSONObject(com.epaflix.fytorium.photos.photoFixture).put("plant_name", "Synthetic fern") + "]"
                    "/api/plants/plant-one/photos" -> "[]"
                    "/api/choices" -> """{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":["good"]},"nameLanguages":{}}"""
                    "/api/identification" -> """{"remaining":20}"""
                    "/api/me" -> """{"member":{"id":"owner","role":"${initialRole.name}"}}"""
                    "/api/tasks?view=today" -> taskListFixture("today")
                    "/api/tasks?view=upcoming" -> taskListFixture("upcoming")
                    "/api/plants" -> "[$plantFixture]"
                    "/api/meta" -> """{"today":"2030-06-01","timezone":"Europe/Oslo"}"""
                    "/api/feed" -> """{"settings":{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true},"cards":[{"id":"late","kind":"late_tasks","href":"/#today","count":1,"plants":[{"id":"plant-one","name":"Synthetic fern"}]}]}"""
                    "/api/weather" -> """{"locationSet":false,"forecastFetchedAt":null,"rain":null,"lastRainBatch":null}"""
                    "/api/household/location" -> """{"location":null}"""
                    "/api/household/settings" -> """{"misting_enabled":true}"""
                    "/api/feed/settings" -> """{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true}"""
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
            val initialCare = CareState(member = Member("owner", if (reset == "sitter" && route !in listOf("household", "planta")) Role.family else initialRole), plants = listOf(plant),
                detail = PlantDetail(plant, emptyList(), emptyMap()), busy = true, offline = true, serverUnavailable = true)
            val care = mutableStateOf(initialCare)
            val feed = FeedRepository(client, { true }).apply { refresh(initialCare.member) }
            val loggedIn = mutableStateOf(true)
            val api = mutableStateOf<ApiClient?>(if (route == "sites-unmatched") null else client)
            val database = if (route == "identify") androidx.room.Room.inMemoryDatabaseBuilder(
                org.robolectric.RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build() else null
            val controller = database?.let {
                val cache = CareCache(it)
                cache.bind("""{"member":{"id":"owner","role":"${initialCare.member!!.role.name}"}}""")
                val repository = Repository(client, cache, { true }).apply { restore() }
                CareController(org.robolectric.RuntimeEnvironment.getApplication(), MemoryCredentials().apply {
                    tokens = Tokens("synthetic-access", "synthetic-refresh")
                }, repository, client)
            }
            try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme {
                AppShell(loggedIn.value, false, careState = care.value, controller = controller, feed = feed, sitesApi = api.value, sitesConnected = { true })
            } }
            when (route) {
                "identify" -> {
                    compose.onNode(hasText("Plants") and hasClickAction()).performClick()
                    compose.onNodeWithText("Add plant").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("Identify from photos").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Identify from photos").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("20 identifications remaining today.").fetchSemanticsNodes().isNotEmpty() }
                }
                "journal", "journal-plant" -> {
                    compose.onNodeWithText("Journal").performClick()
                    if (route == "journal-plant") {
                        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic fern") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText("Synthetic fern").performScrollTo().performClick()
                    }
                }
                "photo-form" -> {
                    compose.onNodeWithText("Synthetic fern").performScrollTo().performClick()
                    compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("View all updates"))
                    compose.onNodeWithText("View all updates").performClick()
                    compose.onNodeWithText("Add a photo update").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Note") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Note").performTextInput("Unsaved photo note")
                }
                "sites-unmatched" -> {
                    compose.onNodeWithText("More").performClick()
                    // With no API attached, both saved routes can be set through their actual buttons.
                    compose.onNodeWithText("Sites").performClick()
                    compose.onNodeWithText("Unmatched species").performClick()
                }
                "planta" -> {
                    compose.onNodeWithText("More").performClick()
                    compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic imported fern").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("One-time app code").performTextInput("synthetic-never-save")
                }
                "household-settings" -> {
                    compose.onNodeWithText("More").performClick()
                    compose.onNodeWithText("Members").assertDoesNotExist()
                    compose.onNodeWithText("Household settings").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save my feed") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                }
                "household" -> {
                    compose.onNodeWithText("More").performClick()
                    compose.onNodeWithText("Members").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create invite") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
            if (reset == null || (reset == "family" && route != "planta")) {
                when (route) {
                    "planta" -> {
                        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic imported fern").fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText("One-time app code").assertTextEquals("One-time app code", "")
                        compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().assertIsEnabled()
                    }
                    "sites-unmatched" -> {
                        compose.onNodeWithText("Open Plantbook unmatched species").assertIsDisplayed()
                        compose.onNodeWithContentDescription("Back").performClick()
                        compose.onNodeWithText("A site groups plants that share the same conditions.").assertIsDisplayed()
                    }
                    "household-settings" -> {
                        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save my feed") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText("Save my feed").assertIsDisplayed().assertIsEnabled()
                        compose.onNodeWithText("Misting reminders for the household").performScrollTo().assertIsNotEnabled()
                        compose.onNodeWithText("Save household settings").assertDoesNotExist()
                        compose.onNodeWithText("Create invite").assertDoesNotExist()
                    }
                    "household" -> {
                        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create invite") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText("Create invite").assertIsDisplayed().assertIsEnabled()
                    }
                    "identify" -> {
                        compose.onNodeWithText("Identify from photos").assertIsDisplayed()
                        compose.onNodeWithText("Take photo").assertIsEnabled()
                        compose.onNodeWithText("Choose photos").assertIsEnabled()
                    }
                    "journal" -> compose.onNodeWithText("Plant journal").assertIsDisplayed()
                    "photo-form" -> {
                        compose.onNodeWithText("Add a photo update").assertIsDisplayed()
                        compose.onNodeWithText("Unsaved photo note").assertExists()
                        compose.onNodeWithText("Take photo").assertIsEnabled()
                        compose.onNodeWithText("Choose photos").assertIsEnabled()
                    }
                    "journal-plant" -> compose.onNodeWithText("Edit plant").assertIsDisplayed().assertIsEnabled()
                    "species", "plant-species" -> compose.onNodeWithText("Species care").assertIsDisplayed()
                    "feed-settings" -> compose.onNodeWithText("Save my feed").assertIsDisplayed().assertIsEnabled()
                    "feed-plant" -> compose.onNodeWithText("Edit plant").assertIsDisplayed().assertIsEnabled()
                }
            } else {
                if (reset == "sitter") {
                    val tab = when (route) { "journal" -> "Today"; "feed-plant", "plant-species", "photo-form", "journal-plant", "identify" -> "Plants"; else -> "More" }
                    compose.onNode(hasText(tab) and hasClickAction() and !hasTestTag("view-today")).assertIsSelected()
                } else if (reset == "family" && route == "planta") compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
                else compose.onNodeWithContentDescription("Search plants").assertIsDisplayed()
                compose.onNodeWithText("One-time app code").assertDoesNotExist()
                compose.onNodeWithText("Synthetic imported fern").assertDoesNotExist()
                compose.onNodeWithText("Plant journal").assertDoesNotExist()
                compose.onNodeWithText("Add a photo update").assertDoesNotExist()
                compose.onNodeWithText("Unsaved photo note").assertDoesNotExist()
                compose.onNodeWithText("Identify plant").assertDoesNotExist()
                compose.onNodeWithText("Create invite").assertDoesNotExist()
                compose.onNodeWithText("Save my feed").assertDoesNotExist()
                compose.onNodeWithText("Edit plant").assertDoesNotExist()
                compose.onNodeWithText("Species care").assertDoesNotExist()
                compose.onNode(hasText("More") and hasClickAction()).performClick()
                compose.onNodeWithText("Log out").assertIsDisplayed()
                compose.onNodeWithText("Open Plantbook unmatched species").assertDoesNotExist()
                compose.onNodeWithText("A site groups plants that share the same conditions.").assertDoesNotExist()
            }
            } finally { controller?.close(); database?.close() }
        }
    }
}
