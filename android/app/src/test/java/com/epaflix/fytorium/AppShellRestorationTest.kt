package com.epaflix.fytorium

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import okhttp3.mockwebserver.*
import com.epaflix.fytorium.feed.*
import com.epaflix.fytorium.species.careFixture
import com.epaflix.fytorium.species.outdoorFixture
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    @Test fun photoDraftAsksBeforeTabExitAndRestoresItsDestination() = navigation(null, "photo-form", exit = "More")
    @Test fun photoDraftAsksBeforeNotificationExitAndRestoresItsDestination() = navigation(null, "photo-form", exit = "home")
    @Test fun restoredPhotoDraftAsksBeforeTabExit() = navigation(null, "photo-form", exit = "More", restoreBeforeExit = true)
    @Test fun restoredPhotoDraftAsksBeforeNotificationExit() = navigation(null, "photo-form", exit = "home", restoreBeforeExit = true)
    @Test fun pendingSiteDiscardRestoresItsTabDestination() = navigation(null, "site-draft", exit = "Plants")
    @Test fun pendingSiteDiscardRestoresItsNotificationDestination() = navigation(null, "site-draft", exit = "home")
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

    // W4-P32: Journal + picks a plant first; Back walks form, picker, Journal.
    @Test fun journalAddPicksAPlantThenBackWalksOut() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.requestUrl?.encodedPath) {
                    "/api/journal", "/api/plants/plant-one/photos" -> "[]"
                    "/api/meta" -> """{"today":"2030-06-01"}"""
                    else -> """{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":[]},"nameLanguages":{}}"""
                })
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val care = CareState(member = Member("member", Role.owner), plants = listOf(parsePlant(JSONObject(plantFixture))))
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = care, sitesApi = api, sitesConnected = { true }) } }
            compose.onNode(hasText("Journal") and hasClickAction() and !isHeading()).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No photos yet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Add a photo update").performClick()
            compose.onNode(hasText("Choose a plant") and isHeading()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Add plant").assertDoesNotExist()
            compose.onNodeWithText("Synthetic fern").performClick()
            compose.onNode(hasText("Add a photo of Synthetic fern") and isHeading()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasText("Choose a plant") and isHeading()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasText("Journal") and isHeading()).assertIsDisplayed()
        }
    }

    private fun sitterNavigation(route: String, roleChange: Boolean = false,
                                 initialRole: Role? = if (roleChange) Role.family else Role.sitter) {
        val plant = parsePlant(JSONObject(plantFixture))
        val care = mutableStateOf(CareState(member = initialRole?.let { Member("member", it) }, plants = listOf(plant),
            detail = PlantDetail(plant, emptyList(), emptyMap())))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        val tab = when (route) { "detail" -> "Plants"; "About" -> "More"; "upcoming" -> "Today"; else -> route }
        fun navigationTab(title: String) = compose.onNode(isTab(title))
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
            "More" -> compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
            "About" -> compose.onNodeWithText("Copy diagnostics").assertIsDisplayed()
            "detail" -> compose.onNodeWithText("No care tasks yet.").assertIsDisplayed()
            "upcoming" -> compose.onNodeWithTag("view-upcoming").assertIsSelected()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    // Opus W5a-opus-2: Notifications is not keyed by the member object, so a reread member keeps the open reminder time.
    @Test fun anOpenReminderTimeStaysWhenTheSameMemberIsReread() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.path) {
                    "/api/notifications/settings" -> """{"reminder_time":"09:00","reminders_enabled":false,"weather_alerts_enabled":false,"completion_notices_enabled":false}"""
                    "/api/meta" -> """{"today":"2030-06-01","timezone":"Europe/Oslo"}"""
                    else -> "[]"
                })
            }
            server.start()
            val client = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-reread", Role.owner)))
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value, sitesApi = client, sitesConnected = { true }) } }
            compose.onNode(hasText("More") and hasClickAction()).performClick()
            compose.onNode(hasText("Notifications") and hasClickAction()).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Times use Europe/Oslo time.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reminder time").performScrollTo().performClick()
            compose.onNodeWithText("Set").assertIsDisplayed()
            care.value = CareState(member = Member("synthetic-reread", Role.family))
            compose.waitForIdle()
            compose.onNodeWithText("Set").assertIsDisplayed()
        }
    }

    private fun navigation(reset: String?, route: String = "sites-unmatched", initialRole: Role = Role.owner,
                           exit: String? = null, restoreBeforeExit: Boolean = false) {
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
                    "/api/feed" -> """{"settings":{"late_tasks":true,"current_weather":true,"weather_alerts":true,"plant_warnings":true},"cards":[{"id":"plant-one","kind":"plant_warnings","href":"/plants/plant-one","plantName":"Synthetic fern","warnings":[{"kind":"no_drainage","reason":"Synthetic reason.","inputs":[]}]}]}"""
                    "/api/weather" -> """{"locationSet":false,"forecastFetchedAt":null,"rain":null,"lastRainBatch":null}"""
                    "/api/household/location" -> """{"location":null}"""
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
            val showShell = mutableStateOf(true)
            val homeRequest = mutableIntStateOf(0)
            var effects: Job? = null
            try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { if (showShell.value) {
                LaunchedEffect(Unit) { effects = coroutineContext[Job]?.parent }
                FytoriumTheme {
                    AppShell(loggedIn.value, false, careState = care.value, controller = controller, feed = feed, sitesApi = api.value, sitesConnected = { true }, homeRequest = homeRequest.intValue)
                }
            } }
            when (route) {
                "identify" -> {
                    compose.onNode(hasText("Plants") and hasClickAction()).performClick()
                    compose.onNodeWithContentDescription("Add plant").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("Identify from photos").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Identify from photos").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("20 identifications left today.").fetchSemanticsNodes().isNotEmpty() }
                }
                "journal", "journal-plant" -> {
                    compose.onNodeWithText("Journal").performClick()
                    if (route == "journal-plant") {
                        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Synthetic fern") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText("Synthetic fern").performScrollTo().performClick()
                    }
                }
                "photo-form" -> {
                    compose.onNodeWithTag("feed-plant-one-no_drainage").performScrollTo().performClick()
                    compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("See all"))
                    compose.onNodeWithText("See all").performClick()
                    compose.onNode(hasContentDescription("Add a photo", substring = true)).performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Note") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Note").performTextInput("Unsaved photo note")
                }
                "site-draft" -> {
                    compose.onNode(isTab("More")).performClick()
                    compose.onNodeWithText("Sites").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create site") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Create site").performClick()
                    compose.onNodeWithText("Name").performTextInput("Synthetic draft room")
                }
                "sites-unmatched" -> {
                    compose.onNode(isTab("More")).performClick()
                    // With no API attached, both saved routes can be set through their actual buttons.
                    compose.onNodeWithText("Sites").performClick()
                    compose.onNodeWithText("Species without care data").performClick()
                }
                "planta" -> {
                    compose.onNode(isTab("More")).performClick()
                    compose.onNodeWithText("Import from Planta").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic imported fern").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("One-time app code").performTextInput("synthetic-never-save")
                }
                "household-settings" -> {
                    compose.onNode(isTab("More")).performClick()
                    compose.onNodeWithText("Members").assertDoesNotExist()
                    compose.onNodeWithText("Weather and feed").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Today's weather") and isToggleable() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                }
                "household" -> {
                    compose.onNode(isTab("More")).performClick()
                    compose.onNodeWithText("Members").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodes(hasText("Create invite") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                }
                "species" -> {
                    compose.onNode(isTab("More")).performClick()
                    compose.onNodeWithText("Species without care data").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("Species care for Testoria nebulosa").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Species care for Testoria nebulosa").performScrollTo().performClick()
                }
                "feed-settings" -> {
                    compose.onNode(isTab("More")).performClick()
                    compose.onNodeWithText("Weather and feed").performClick()
                }
                else -> {
                    compose.onNodeWithTag("feed-plant-one-no_drainage").performScrollTo().performClick()
                    if (route == "plant-species") {
                        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Details"))
                        compose.onNodeWithText("Details").performClick()
                        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Care defaults for this species"))
                        compose.onNodeWithText("Care defaults for this species").performClick()
                    }
                }
            }
            if (exit != null) {
                val question = if (route == "site-draft") "Your changes to this new site are not saved yet." else "Your photo update for Synthetic fern is not saved yet."
                val draft = if (route == "site-draft") "Synthetic draft room" else "Unsaved photo note"
                if (restoreBeforeExit) restoration.emulateSavedInstanceStateRestore()
                if (exit == "home") compose.runOnIdle { homeRequest.intValue++ }
                else compose.onNode(hasText(exit) and isSelectable()).performClick()
                compose.onNodeWithText("Discard changes?").assertExists()
                compose.onNodeWithText(question).assertExists()
                restoration.emulateSavedInstanceStateRestore()
                compose.onNodeWithText("Discard changes?").assertExists()
                compose.onNodeWithText(question).assertExists()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithText(draft).assertExists()
                if (exit == "home") {
                    compose.runOnIdle { homeRequest.intValue = 0 }
                    compose.runOnIdle { homeRequest.intValue = 1 }
                }
                else compose.onNode(hasText(exit) and isSelectable()).performClick()
                restoration.emulateSavedInstanceStateRestore()
                compose.onNodeWithText("Discard").performClick()
                compose.onNode(hasText(if (exit == "home") "Today" else exit) and isSelectable() and !hasTestTag("view-today")).assertIsSelected()
                compose.onNode(hasText("Plants") and isSelectable()).performClick()
                compose.onNodeWithText(draft).assertDoesNotExist()
                return
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
                        compose.onNodeWithContentDescription("This is Testoria nebulosa").performScrollTo().assertIsEnabled()
                    }
                    "sites-unmatched" -> {
                        compose.onNodeWithText("These species of yours have no care data from sources yet. Fytorium's own estimates still apply.").assertIsDisplayed()
                        compose.onNodeWithContentDescription("Back").performClick()
                        compose.onNodeWithText("A site groups plants that share the same conditions.").assertIsDisplayed()
                    }
                    "household-settings" -> {
                        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Today's weather") and isToggleable() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNode(hasText("Today's weather") and isToggleable()).performScrollTo().assertIsDisplayed().assertIsEnabled()
                        compose.onNodeWithText("Only the owner can change it.").performScrollTo().assertIsDisplayed()
                        compose.onNodeWithText("Find your town").assertDoesNotExist()
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
                    "journal" -> compose.onNode(hasText("Journal") and isHeading()).assertIsDisplayed()
                    "photo-form" -> {
                        compose.onNodeWithText("Add a photo of", substring = true).assertIsDisplayed()
                        compose.onNodeWithText("Unsaved photo note").assertExists()
                        compose.onNodeWithText("Take photo").assertIsEnabled()
                        compose.onNodeWithText("Choose photo").assertIsEnabled()
                    }
                    "journal-plant" -> compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed().assertIsEnabled()
                    "species", "plant-species" -> compose.onNodeWithText("Care defaults for this species").assertIsDisplayed()
                    "feed-settings" -> compose.onNode(hasText("Today's weather") and isToggleable()).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    "feed-plant" -> compose.onNodeWithContentDescription("Edit plant").assertIsDisplayed().assertIsEnabled()
                }
            } else {
                if (reset == "sitter") {
                    val tab = when (route) { "journal" -> "Today"; "feed-plant", "plant-species", "photo-form", "journal-plant", "identify" -> "Plants"; else -> "More" }
                    compose.onNode(hasText(tab) and isSelectable() and !hasTestTag("view-today")).assertIsSelected()
                } else if (reset == "family" && route == "planta") compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
                else compose.onNodeWithContentDescription("Search plants").assertIsDisplayed()
                compose.onNodeWithText("One-time app code").assertDoesNotExist()
                compose.onNodeWithText("Synthetic imported fern").assertDoesNotExist()
                compose.onNode(hasText("Journal") and isHeading()).assertDoesNotExist()
                compose.onNodeWithText("Add a photo of", substring = true).assertDoesNotExist()
                compose.onNodeWithText("Unsaved photo note").assertDoesNotExist()
                compose.onNodeWithText("Identify").assertDoesNotExist()
                compose.onNodeWithText("Create invite").assertDoesNotExist()
                compose.onNode(hasText("Today's weather") and isToggleable()).assertDoesNotExist()
                compose.onNodeWithContentDescription("Edit plant").assertDoesNotExist()
                compose.onNodeWithText("Care defaults for this species").assertDoesNotExist()
                compose.onNode(isTab("More")).performClick()
                compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("These species of yours have no care data from sources yet. Fytorium's own estimates still apply.").assertDoesNotExist()
                compose.onNodeWithText("A site groups plants that share the same conditions.").assertDoesNotExist()
            }
            } finally {
                controller?.close()
                compose.runOnIdle { showShell.value = false }
                // Compose idle does not wait for cancelled effects' blocking IO to finish.
                compose.waitUntil(5_000) {
                    effects?.children?.none { it.isCancelled && !it.isCompleted } != false &&
                        controller?.repository?.state?.value?.busy != true
                }
                database?.close()
            }
        }
    }
}
