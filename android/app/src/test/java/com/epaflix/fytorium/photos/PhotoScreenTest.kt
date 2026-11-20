package com.epaflix.fytorium.photos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi", shadows = [JvmImageSources::class])
class PhotoScreenTest {
    @get:Rule val compose = createComposeRule()
    private val photo = PhotoUpdate("photo-one", "plant-one", "Synthetic sprout", "Synthetic note", "good", "2026-10-13", false)
    private val addPhoto = hasContentDescription("Add a photo", substring = true)
    private fun openForm() = compose.onNode(addPhoto).performClick()
    private val chosen = hasContentDescription("Chosen photo", substring = true)
    private fun chosenPhotos() = compose.onAllNodes(chosen).fetchSemanticsNodes().size
    private fun photoAction(name: String) {
        compose.onNode(hasContentDescription("Actions for the", substring = true)).performScrollTo().performClick()
        compose.onNodeWithText(name).performClick()
    }
    @Test fun careFailuresAndRefreshesDoNotDisablePhotoControlsAndSittersSendNothing() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[$photoFixture]"))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner), busy = true, offline = true, serverUnavailable = true))
            compose.setContent { FytoriumTheme { PhotosRoute(api, care.value, { true }, null, onBack = {}) } }
            compose.waitUntil(5_000) { server.requestCount == 1 && compose.onAllNodesWithText("Synthetic note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(addPhoto).assertIsEnabled()
            compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
            compose.onNode(addPhoto).assertDoesNotExist()
            compose.onNodeWithText("Choose photos").assertDoesNotExist()
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun offlineAndOwnSavingDisableIdentificationButOwnReadsDoNot() {
        val state = mutableStateOf(PhotoState(loading = true))
        val online = mutableStateOf(true)
        val explained = mutableListOf<String>()
        compose.setContent { FytoriumTheme { IdentificationScreen(state.value, online.value, listOf("content://synthetic/one"), onExplain = { explained += it }) } }
        compose.onNodeWithText("Identify").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false, saving = true, progress = 0.5f) }
        compose.onNodeWithText("Identify").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(saving = false); online.value = false }
        compose.onNodeWithText("Identify").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("Identifying needs a connection."), explained) }
    }
    // C11: photo updates show readable dates.
    @Test fun photoUpdatesShowReadableDates() {
        compose.setContent { FytoriumTheme { PhotoScreen(PhotoState(listOf(photo, photo.copy(id = "photo-two", cover = true)), loaded = true), true, "Synthetic sprout", 0) } }
        compose.onAllNodesWithText("13 Oct 2026").assertCountEquals(2)
        compose.onNodeWithText("Cover photo").assertExists()
        compose.onAllNodes(hasText("2026-10-13", substring = true)).assertCountEquals(0)
    }
    // W4-P27-P29: photo-first cards, the plant as a link on the Journal, one menu of actions, + in the top bar.
    @Test fun journalCardsLinkThePlantAndKeepActionsInOneMenu() {
        var opened: String? = null
        var plantOpened: String? = null
        var command: String? = null
        compose.setContent { FytoriumTheme { PhotoScreen(PhotoState(listOf(photo.copy(cover = true)), loaded = true), true, null, 0,
            onOriginal = { opened = it }, onPlant = { plantOpened = it }, onChange = { _, action -> command = action }) } }
        compose.onNode(hasText("Journal") and isHeading()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Add a photo update").assertIsEnabled()
        compose.onNodeWithText("Refresh photos").assertDoesNotExist()
        compose.onNodeWithText("Cover photo").assertIsDisplayed()
        compose.onNodeWithText("Synthetic sprout").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Photo of Synthetic sprout, 13 Oct 2026").performClick()
        compose.runOnIdle { assertEquals("plant-one", plantOpened); assertEquals("photo-one", opened) }
        compose.onNodeWithContentDescription("Actions for the 13 Oct 2026 photo of Synthetic sprout").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Use as cover").assertDoesNotExist()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete the 13 Oct 2026 photo of Synthetic sprout? This can't be undone.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals("delete", command) }
    }

    @Test fun anEmptyJournalOffersTheFirstPhoto() {
        var added = 0
        compose.setContent { FytoriumTheme { PhotoScreen(PhotoState(loaded = true), true, null, 0, onAdd = { added++ }) } }
        compose.onNodeWithText("No photos yet").assertIsDisplayed()
        compose.onNodeWithText("Add your first photo").performClick()
        compose.runOnIdle { assertEquals(1, added) }
    }

    // W4-P35: offline the list stays and photo changes look off and say why.
    @Test fun offlinePhotoChangesExplainWhy() {
        val explained = mutableListOf<String>()
        compose.setContent { FytoriumTheme { PhotoScreen(PhotoState(listOf(photo), loaded = true), false, "Synthetic sprout", 0,
            care = CareState(member = Member("synthetic-member", Role.owner), offline = true), onExplain = { explained += it }) } }
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Add a photo of Synthetic sprout").assertIsNotEnabled().performClick()
        photoAction("Delete")
        compose.onNodeWithText("Delete this photo?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("Photo changes need a connection.", "Photo changes need a connection."), explained) }
    }

    // W4-P31: no photo yet, the button looks off and says what is missing.
    @Test fun addingWithoutAPhotoSaysChooseOneFirst() {
        val explained = mutableListOf<String>()
        var uploads = 0
        compose.setContent { FytoriumTheme { PhotoScreen(PhotoState(today = "2026-10-13"), true, "Synthetic sprout", 0, formOnly = true,
            onExplain = { explained += it }, onUpload = { _, _, _ -> uploads++ }) } }
        compose.onNode(hasText("Add a photo of Synthetic sprout") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Take photo").assertIsDisplayed()
        compose.onNodeWithText("Choose photo").assertIsDisplayed()
        compose.onNodeWithText("Add photo update").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Choose a photo first."), explained); assertEquals(0, uploads) }
    }

    // W4-P31, P33, P34: each change ends with a receipt that names the photo or plant.
    @Test fun photoChangesEndWithReceipts() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(when {
                    request.method == "DELETE" -> ""
                    request.method == "PUT" -> photoFixture
                    request.path == "/api/plants/plant-one/photos" -> "[$photoFixture]"
                    request.path == "/api/meta" -> """{"today":"2026-10-13"}"""
                    else -> """{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":[]},"nameLanguages":{}}"""
                }).setResponseCode(if (request.method == "DELETE") 204 else 200)
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val receipts = mutableListOf<String>()
            compose.setContent { FytoriumTheme { PhotosRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { true }, null,
                plantId = "plant-one", plantName = "Synthetic sprout", onBack = {}, onReceipt = { receipts += it }) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic note").fetchSemanticsNodes().isNotEmpty() }
            photoAction("Use as cover")
            compose.waitUntil(5_000) { receipts.size == 1 }
            photoAction("Delete")
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5_000) { receipts.size == 2 }
            assertEquals(listOf("Synthetic sprout's cover photo changed", "Deleted the 13 Oct 2026 photo of Synthetic sprout"), receipts)
        }
    }

    // W4-P35: the last list shows offline from the phone's copy.
    @Test fun theLastPhotoListShowsOffline() {
        val api = ApiClient("http://127.0.0.1:9", MemoryCredentials()) {}
        compose.setContent { FytoriumTheme { PhotosRoute(api, CareState(member = Member("synthetic-member", Role.owner), offline = true), { false }, null,
            plantId = "plant-one", plantName = "Synthetic sprout", onBack = {}, cached = { if (it == "photos/plant-one") "[$photoFixture]" else null }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic note").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
    }

    // W4-P30: zoom stays between 1x and 5x and pans no further than the photo's edges.
    @Test fun viewerZoomStaysInsideThePhoto() {
        assertEquals(Triple(5f, 0f, 0f), clampZoom(9f, 0f, 0f, 400f, 300f, 400f, 800f))
        assertEquals(Triple(1f, 0f, 0f), clampZoom(0.5f, 50f, 50f, 400f, 300f, 400f, 800f))
        // At 2x a 400 x 300 photo is 800 x 600 inside a 400 x 800 window: 200 px of side room, none above.
        assertEquals(Triple(2f, 200f, 0f), clampZoom(2f, 900f, 900f, 400f, 300f, 400f, 800f))
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun photoDeleteScrollsInsideInLandscapeWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme {
            PhotoScreen(PhotoState(listOf(photo), loaded = true), true, "Synthetic sprout", 0)
        } }
        photoAction("Delete")
        compose.onNodeWithText("Delete the 13 Oct 2026 photo of Synthetic sprout? This can't be undone.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.checkDialogTextScale("Delete", 1.3f)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    @Test fun photoDateIsReachableInLandscapeWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        compose.setContent { CheckLandscape(1.3f); FytoriumTheme {
            PhotoScreen(PhotoState(today = "2026-10-13"), true, "Synthetic sprout", 0)
        } }
        openForm()
        compose.onNodeWithText("Taken on: 13 Oct 2026").performScrollTo().performClick()
        compose.onNodeWithText("Thursday, October 1, 2026").performScrollTo().performClick()
        compose.onNodeWithText("Thursday, October 1, 2026").assertIsSelected()
        compose.checkDialogTextScale("Set date", 1.3f)
        compose.onNodeWithContentDescription("Switch to text input mode").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Date, MM/DD/YYYY").performScrollTo().assertIsDisplayed()
        compose.checkDialogTextScale("Set date", 1.3f)
        compose.onNodeWithText("Set date").assertIsDisplayed().performClick()
        compose.onNodeWithText("Taken on: 1 Oct 2026").assertExists()
    }

    @Test fun takenDateKeepsItsUnconfirmedSelectionAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { PhotoScreen(PhotoState(today = "2026-10-13"), true, "Synthetic sprout", 0) } }
        openForm()
        compose.onNodeWithText("Taken on: 13 Oct 2026").performScrollTo().performClick()
        compose.onNodeWithText("Monday, October 12, 2026").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Set date").performClick()
        compose.onNodeWithText("Taken on: 12 Oct 2026").assertExists()
    }

    @Test fun photoNoteAsksOnToolbarBackAndDiscardClearsIt() = discardPhoto(false, false)
    @Test fun recreatedPhotoNoteAsksOnSystemBackAndKeepsItsQuestion() = discardPhoto(true, true)
    @Test fun chosenPhotoAsksBeforeLeaving() = discardPhoto(false, false, selected = 1)
    @Test fun chosenHealthAsksBeforeLeaving() = discardPhoto(false, false, health = true)
    @Test fun chosenDateAsksBeforeLeaving() = discardPhoto(false, false, date = true)

    private fun discardPhoto(restore: Boolean, system: Boolean, selected: Int = 0, health: Boolean = false, date: Boolean = false) {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var back: androidx.activity.OnBackPressedDispatcher? = null
        restoration.setContent { FytoriumTheme {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PhotoScreen(PhotoState(today = "2026-10-13", healthChoices = listOf("good")), true, "Synthetic sprout", selected)
        } }
        openForm()
        when {
            health -> { compose.onNodeWithText("Photo health").performScrollTo().performClick(); compose.onNodeWithText("good").performClick() }
            date -> {
                compose.onNodeWithText("Taken on: 13 Oct 2026").performScrollTo().performClick()
                compose.onNodeWithText("Monday, October 12, 2026").performClick()
                compose.onNodeWithText("Set date").performClick()
            }
            selected == 0 -> compose.onNodeWithText("Note").performTextInput("Synthetic draft")
        }
        if (restore) restoration.emulateSavedInstanceStateRestore()
        if (system) compose.runOnIdle { checkNotNull(back).onBackPressed() }
        else compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard("Your photo update for Synthetic sprout is not saved yet.")
        restoration.emulateSavedInstanceStateRestore()
        compose.assertDiscard("Your photo update for Synthetic sprout is not saved yet.")
        compose.onNodeWithText("Keep editing").performClick()
        if (selected == 0 && !health && !date) compose.onNodeWithText("Synthetic draft").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        openForm()
        compose.onNodeWithText("Note").assertTextEquals("Note", "")
        compose.onNodeWithText("Photo health").assertTextContains("Not set")
        compose.onNodeWithText("Taken on: 13 Oct 2026").assertExists()
    }

    @Test fun photoDeleteQuestionSurvivesRecreationAndAnEmptyReload() {
        val state = mutableStateOf(PhotoState(listOf(photo), loaded = true))
        var deleted: String? = null
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { PhotoScreen(state.value, true, "Synthetic sprout", 0, onChange = { id, action ->
            if (action == "delete") { deleted = id; state.value = PhotoState(loaded = true) }
        }) } }
        photoAction("Delete")
        compose.runOnIdle { state.value = PhotoState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Delete this photo?").assertExists()
        compose.runOnIdle { state.value = PhotoState(listOf(photo), loaded = true) }
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil { deleted != null }
        assertEquals("photo-one", deleted)
        compose.onNodeWithText("Delete this photo?").assertDoesNotExist()
    }

    // W4-P20: photos as a strip with remove buttons, a short privacy line with Details, and Identify that says what is missing.
    @Test fun identifyInputShowsPhotosAndExplains() {
        val selected = mutableStateOf(listOf("content://synthetic/one", "content://synthetic/two"))
        val removed = mutableListOf<Int>()
        val explained = mutableListOf<String>()
        compose.setContent { FytoriumTheme { IdentificationScreen(PhotoState(remaining = 12), true, selected.value,
            onRemove = { removed += it }, onExplain = { explained += it }) } }
        compose.onNodeWithText("Add one to five photos of the same plant. Leaves and flowers work best.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo 2 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove photo 2 of 2").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Take photo").assertIsDisplayed()
        compose.onNodeWithText("Choose photos").assertIsDisplayed()
        compose.onNodeWithText("12 identifications left today.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("About identify photos").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { selected.value = listOf("content://synthetic/1", "content://synthetic/2", "content://synthetic/3", "content://synthetic/4", "content://synthetic/5") }
        compose.onNodeWithText("Choose photos").assertDoesNotExist()
        compose.runOnIdle { selected.value = emptyList() }
        compose.onNodeWithText("Identify").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(1), removed); assertEquals(listOf("Add a photo first."), explained) }
    }

    // W4-P21-P23: results replace the inputs; cards say how sure and the new name; the confirm shows both photos.
    @Test fun identifyResultsCompareYourPhotoWithTheReference() {
        val renamed = SpeciesCandidate(900002, "Testoria nova Inventor", "Testoria nova", "SPECIES", null, null, true, null, null, 0.97,
            "Testoria prior Inventor", commonName = "Synthetic star", identificationCanonicalName = "Testoria prior")
        var picked: Boolean? = null
        compose.setContent { FytoriumTheme { IdentificationScreen(PhotoState(result = SpeciesSearch("synthetic-search", listOf(renamed))), true,
            listOf("content://synthetic/one"), onSelect = { _, _, confirmed -> picked = confirmed }) } }
        compose.onNode(hasText("Best matches") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Add one to five photos of the same plant. Leaves and flowers work best.").assertDoesNotExist()
        compose.onNodeWithText("97% match").assertIsDisplayed()
        compose.onNodeWithText("Now called Testoria nova").assertIsDisplayed()
        compose.onNodeWithText("Testoria prior").assertIsDisplayed()
        compose.onNode(hasContentDescription("Choose this species.", substring = true)).performClick()
        compose.onNodeWithText("Your photo").assertIsDisplayed()
        compose.onNodeWithText("Reference photo").assertIsDisplayed()
        compose.onNodeWithText("Yes, this one").performClick()
        compose.runOnIdle { assertEquals(true, picked) }
        compose.onNodeWithText("Change photos").performClick()
        compose.onNodeWithText("Add one to five photos of the same plant. Leaves and flowers work best.").assertIsDisplayed()
    }

    // W4-P25: no match and a used-up quota answer in the page; Pl@ntNet down goes to the line with Try again.
    @Test fun identifyOutcomesSayWhatToDoNext() {
        val state = mutableStateOf(PhotoState(error = "No plant species could be identified.", failureStatus = 422))
        var identified = 0
        val explained = mutableListOf<String>()
        compose.setContent { FytoriumTheme { IdentificationScreen(state.value, true, listOf("content://synthetic/one"),
            onIdentify = { identified++ }, onExplain = { explained += it }) } }
        compose.onNodeWithText("Pl@ntNet didn't recognise this plant.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Search by name").assertIsDisplayed()
        compose.runOnIdle { state.value = PhotoState(error = "Quota exhausted.", failureStatus = 429) }
        compose.onNodeWithText("No identifications left today.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Identify").assertIsNotEnabled().performClick()
        compose.runOnIdle { state.value = PhotoState(error = "Pl@ntNet did not answer.", failureStatus = 503, blocked = true) }
        compose.onNodeWithText("Pl@ntNet isn't responding").performClick()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, identified); assertEquals(listOf("No identifications left today."), explained) }
    }

    // W4-P26: Back with photos chosen asks before dropping them.
    @Test fun identifyAsksBeforeDroppingPhotos() {
        var left = 0
        var dropped = 0
        compose.setContent { FytoriumTheme { IdentificationScreen(PhotoState(), true, listOf("content://synthetic/one", "content://synthetic/two"),
            onBack = { left++ }, onDiscard = { dropped++ }) } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDialogBody("Your 2 photos are not used yet.")
        compose.onNodeWithText("Discard these photos?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals(1, left); assertEquals(1, dropped) }
    }

    @Test fun identificationChoiceAndSearchSurviveRecreationWhileResultsReload() {
        val candidate = SpeciesCandidate(900001, "Testoria nebulosa", "Testoria nebulosa", "SPECIES", null, null, true, null, null, 0.91)
        val state = mutableStateOf(PhotoState(result = SpeciesSearch("synthetic-search", listOf(candidate))))
        var selected: String? = null
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { IdentificationScreen(state.value, true, listOf("content://synthetic/one"), onSelect = { chosen, search, confirmed ->
            assertEquals(900001L, chosen.key); assertTrue(confirmed); selected = search
        }) } }
        compose.onNode(hasContentDescription("Choose this species.", substring = true)).performScrollTo().performClick()
        compose.runOnIdle { state.value = PhotoState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Yes, this one").performClick()
        assertEquals("synthetic-search", selected)
    }

    @Test fun originalPhotoReopensAfterRecreationAndBackReturnsToGallery() {
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.path) {
                    "/api/photos/photo-one/original" -> okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes))
                    "/api/plants/plant-one/photos" -> okhttp3.mockwebserver.MockResponse().setBody("[$photoFixture]")
                    "/api/meta" -> okhttp3.mockwebserver.MockResponse().setBody("""{"today":"2026-10-13"}""")
                    else -> okhttp3.mockwebserver.MockResponse().setBody("""{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":[]},"nameLanguages":{}}""")
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme { PhotosRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { true }, null,
                plantId = "plant-one", plantName = "Synthetic sprout", onBack = {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo of Synthetic sprout, 13 Oct 2026").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Photo of Synthetic sprout, 13 Oct 2026").performScrollTo().performClick()
            val viewer = hasText("Synthetic sprout \u00b7 13 Oct 2026") and isHeading()
            compose.waitUntil(5_000) { compose.onAllNodes(viewer).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithContentDescription("Photo of Synthetic sprout, 13 Oct 2026").fetchSemanticsNodes().isNotEmpty() }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodes(viewer).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithContentDescription("Photo of Synthetic sprout, 13 Oct 2026").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Photos of Synthetic sprout").assertExists()
        }
    }

    @Test fun anOldCameraDraftSurvivesStartupAndRecreationAndUploads() = cameraDraft { file, restoration ->
        val app = org.robolectric.RuntimeEnvironment.getApplication() as FytoriumApplication
        assertTrue(file.setLastModified(System.currentTimeMillis() - 11 * 60 * 1000L))
        val orphan = java.io.File(file.parentFile, "synthetic-orphan.jpg").apply {
            writeText("synthetic orphan"); setLastModified(file.lastModified())
        }
        app.onCreate()
        kotlinx.coroutines.runBlocking { app.capturesCleared.join() }
        assertTrue("An unsaved draft must keep its old camera image", file.exists())
        assertFalse("Startup must still remove old unreferenced captures", orphan.exists())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(chosen).assertExists()
        assertTrue(preparePhoto(app, androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".photos", file)).isNotEmpty())
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Add photo update") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add photo update").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Photos of Synthetic sprout").fetchSemanticsNodes().isNotEmpty() && !file.exists() }
        openForm()
        compose.onNode(chosen).assertDoesNotExist()
        assertFalse("A successful upload must remove the capture", file.exists())
        assertFalse(java.io.File(file.path + ".draft").exists())
    }

    @Test fun replacingACameraDraftRemovesOnlyTheReplacedImage() = cameraDraft { first, _ ->
        compose.onNodeWithText("Replace").performScrollTo().performClick()
        compose.onNodeWithText("Take photo").performClick()
        compose.waitUntil(5_000) { chosenPhotos() == 1 && !first.exists() }
        assertFalse(java.io.File(first.path + ".draft").exists())
        val remaining = first.parentFile!!.listFiles().orEmpty().filter { it.extension == "jpg" }
        assertEquals(1, remaining.size)
        clearCameraCaptures(first.parentFile!!.parentFile, startup = true, now = System.currentTimeMillis() + 11 * 60 * 1000L)
        assertTrue("Replacing one image must retain the new draft", remaining.single().exists())
    }

    @Test fun discardingACameraDraftRemovesItsImageAndRetentionRecord() = cameraDraft { file, _ ->
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        assertTrue(file.exists())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(5_000) { !file.exists() }
        assertFalse(java.io.File(file.path + ".draft").exists())
    }

    @Test fun leavingTheRouteRemovesItsCameraDraftAndRetentionRecord() = cameraDraft(leaveRoute = true) { file, _ ->
        assertTrue(file.exists())
    }

    private fun cameraDraft(leaveRoute: Boolean = false, check: (java.io.File, androidx.compose.ui.test.junit4.StateRestorationTester) -> Unit) {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as FytoriumApplication
        kotlinx.coroutines.runBlocking { app.capturesCleared.join() }
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<String, Any>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
        val files = mutableListOf<java.io.File>()
        var activity: android.app.Activity? = null
        val showing = mutableStateOf(true)
        val camera = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                    options: androidx.core.app.ActivityOptionsCompat?) {
                    assertTrue(contract is androidx.activity.result.contract.ActivityResultContracts.TakePicture)
                    val uri = input as android.net.Uri
                    val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
                    app.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                    files += java.io.File(app.cacheDir, "captures").listFiles()!!.single { it.extension == "jpg" && it !in files }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { dispatchResult(requestCode, true) }
                }
            }
        }
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val uploads = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.method == "POST") {
                        assertEquals("/api/plants/plant-one/photos", request.path)
                        assertTrue(request.body.readUtf8().contains("filename=\"photo.jpg\""))
                        uploads.incrementAndGet()
                        return okhttp3.mockwebserver.MockResponse().setBody(photoFixture)
                    }
                    return okhttp3.mockwebserver.MockResponse().setBody(when (request.path) {
                        "/api/plants/plant-one/photos" -> "[]"
                        "/api/meta" -> """{"today":"2026-10-13"}"""
                        else -> """{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":[]},"nameLanguages":{}}"""
                    })
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
            restoration.setContent { if (showing.value) CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides camera) { FytoriumTheme {
                activity = androidx.activity.compose.LocalActivity.current
                PhotosRoute(api, CareState(member = Member("synthetic-member", Role.owner)), { true }, null,
                    plantId = "plant-one", plantName = "Synthetic sprout", onBack = {})
            } } }
            try {
                compose.waitUntil(5_000) { compose.onAllNodes(addPhoto and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                openForm()
                compose.onNodeWithText("Take photo").performClick()
                compose.waitUntil(5_000) { chosenPhotos() == 1 }
                org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
                check(files.single(), restoration)
                if (compose.onAllNodesWithText("Choose photo").fetchSemanticsNodes().isNotEmpty()) assertEquals(1, uploads.get())
                if (leaveRoute) {
                    org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", false)
                    compose.runOnIdle { showing.value = false }
                    compose.waitForIdle()
                    assertFalse(files.single().exists())
                    assertFalse(java.io.File(files.single().path + ".draft").exists())
                }
            } finally {
                org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", false)
                files.forEach { it.delete(); java.io.File(it.path + ".draft").delete() }
            }
        }
    }

    @Test fun chosenGalleryPhotosKeepAReadGrantThroughRecreationAndReleaseItOnRemoval() = galleryDraft(true)

    @Config(shadows = [TemporaryGalleryGrants::class])
    @Test fun galleryPhotoWithoutAPersistableGrantStillAttachesAndDiscards() = galleryDraft(false)

    private fun galleryDraft(persistable: Boolean) {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as FytoriumApplication
        val uri = android.net.Uri.parse("content://media/picker/synthetic-image")
        val picker = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                    options: androidx.core.app.ActivityOptionsCompat?) { dispatchResult(requestCode, uri) }
            }
        }
        var activity: android.app.Activity? = null
        var remove: () -> Unit = {}
        var error: String? = null
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides picker) {
            activity = androidx.activity.compose.LocalActivity.current
            var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
            remove = { selected = emptyList() }
            val sources = rememberPhotoSources(false, selected, onPhotos = { selected = it.map { photo -> photo.toString() } }, onError = { error = it })
            PhotoScreen(PhotoState(today = "2026-10-13"), true, "Synthetic sprout", selected.size, selectedUri = selected.firstOrNull(),
                onTake = sources.take, onChoose = sources.choose, onDiscard = { selected = emptyList() })
        } }
        openForm()
        compose.onNodeWithText("Choose photo").performClick()
        compose.onNode(chosen).assertExists()
        assertNull(error)
        assertEquals(if (persistable) listOf(uri) else emptyList<android.net.Uri>(), app.contentResolver.persistedUriPermissions.map { it.uri })
        try {
            org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNode(chosen).assertExists()
            assertEquals(if (persistable) listOf(uri) else emptyList<android.net.Uri>(), app.contentResolver.persistedUriPermissions.map { it.uri })
            compose.runOnIdle { remove() }
            compose.onNode(chosen).assertDoesNotExist()
            compose.waitUntil(5_000) { app.contentResolver.persistedUriPermissions.isEmpty() }
            compose.onNodeWithText("Choose photo").performClick()
            compose.onNode(chosen).assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.assertDiscard("Your photo update for Synthetic sprout is not saved yet.")
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { app.contentResolver.persistedUriPermissions.isEmpty() }
            openForm()
            compose.onNode(chosen).assertDoesNotExist()
            if (!persistable) assertEquals(0, org.robolectric.shadow.api.Shadow.extract<TemporaryGalleryGrants>(app.contentResolver).releaseAttempts)
        } finally { org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", false) }
    }

    @org.robolectric.annotation.Implements(android.content.ContentResolver::class)
    class TemporaryGalleryGrants : org.robolectric.shadows.ShadowContentResolver() {
        var releaseAttempts = 0
        @org.robolectric.annotation.Implementation
        override fun takePersistableUriPermission(uri: android.net.Uri, modeFlags: Int) {
            throw SecurityException("Synthetic provider offers only a temporary read grant")
        }
        @org.robolectric.annotation.Implementation
        override fun releasePersistableUriPermission(uri: android.net.Uri, modeFlags: Int) { releaseAttempts++ }
    }

    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val mode = mutableStateOf("gallery")
        val state = mutableStateOf(PhotoState(listOf(photo), loaded = true, today = "2026-10-13", healthChoices = listOf("poor", "fair", "good", "very_good")))
        var command = ""
        var selection: Boolean? = null
        val candidate = SpeciesCandidate(900001, "Testoria nebulosa", "Testoria nebulosa", "SPECIES", null, null, true, null, null, 0.91, "Testoria nebulosa", listOf("Synthetic cloud plant"))
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (mode.value == "identify") IdentificationScreen(state.value, true, listOf("content://synthetic/one"), onSelect = { _, _, confirmed -> selection = confirmed })
            else key(mode.value) { PhotoScreen(state.value, true, if (mode.value == "journal") null else "Synthetic sprout", 1,
                selectedUri = "content://synthetic/photo", onChange = { _, action -> command = action },
                onUpload = { _, _, _ -> state.value = state.value.copy(blocked = true, error = "Could not reach Fytorium. Try again.") },
                onRefresh = { state.value = state.value.copy(blocked = false, loading = true, error = null) }) }
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/a06-$name-${if (dark) "dark" else "light"}.png"
            if (name in listOf("date", "delete")) {
                compose.captureDialog(file)
            } else compose.onRoot().captureRoboImage(file)
        }
        compose.onNode(hasContentDescription("Actions for the", substring = true)).performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("gallery")
        photoAction("Use as cover")
        assertEquals("cover", command)
        photoAction("Delete")
        compose.onNodeWithText("Delete").assertIsDisplayed()
        capture("delete")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(addPhoto).assertIsDisplayed().performClick()
        compose.onNodeWithText("Replace").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Replace").assertIsDisplayed()
        capture("form")
        compose.onNodeWithText("Note").performScrollTo().performTextReplacement("Synthetic unsaved note")
        compose.onNodeWithText("Add photo update").performScrollTo().performClick()
        compose.onNodeWithText("Add photo update").assertIsNotEnabled()
        capture("failure")
        compose.onNodeWithText("Fytorium isn't responding").performClick()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Note").performScrollTo().assertTextContains("Synthetic unsaved note")
        compose.onNodeWithText("Add photo update").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Taken on: 13 Oct 2026").performScrollTo().performClick()
        compose.onNodeWithText("Tuesday, October 13, 2026").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithText("Wednesday, October 14, 2026", substring = true).assertIsNotEnabled()
        // Calendar mode marks the real current day, making its screenshot date-dependent.
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        capture("date")
        compose.onNodeWithContentDescription("Date, MM/DD/YYYY").performTextReplacement("10142026")
        compose.onNodeWithText("Set date").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { mode.value = "journal"; state.value = state.value.copy(loading = false) }
        compose.onNodeWithText("Journal").assertIsDisplayed()
        capture("journal")
        val bitmap = android.graphics.Bitmap.createBitmap(200, 150, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(90, 160, 100))
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        compose.runOnIdle { state.value = state.value.copy(original = bytes) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Plant photo").fetchSemanticsNodes().isNotEmpty() }
        capture("original")
        compose.runOnIdle { mode.value = "identify"; state.value = state.value.copy(original = null, saving = true, uploading = true, progress = 0.5f) }
        capture("identify-upload")
        compose.runOnIdle { state.value = state.value.copy(saving = false, result = SpeciesSearch("synthetic-search", listOf(candidate)), remaining = 20) }
        compose.onNodeWithText("91% match").performScrollTo().assertIsDisplayed()
        capture("identify-results")
        compose.onNode(hasContentDescription("Choose this species.", substring = true)).performScrollTo().performClick()
        assertNull(selection)
        capture("confirm")
        compose.onNodeWithText("Yes, this one").performClick()
        assertEquals(true, selection)
    }
}
