package com.epaflix.fytorium.photos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi", shadows = [JvmImageSources::class])
class PhotoScreenTest {
    @get:Rule val compose = createComposeRule()
    private val photo = PhotoUpdate("photo-one", "plant-one", "Synthetic sprout", "Synthetic note", "good", "2026-10-13", false)
    @Test fun careFailuresAndRefreshesDoNotDisablePhotoControlsAndSittersSendNothing() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("[$photoFixture]"))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner), busy = true, offline = true, serverUnavailable = true))
            compose.setContent { FytoriumTheme { PhotosRoute(api, care.value, { true }, null, onBack = {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Use as cover") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use as cover").performScrollTo().assertIsEnabled()
            compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
            compose.onNodeWithText("Use as cover").assertDoesNotExist()
            compose.onNodeWithText("Choose photos").assertDoesNotExist()
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun offlineAndOwnSavingDisableIdentificationButOwnReadsDoNot() {
        val state = mutableStateOf(PhotoState(loading = true))
        val online = mutableStateOf(true)
        compose.setContent { FytoriumTheme { IdentificationScreen(state.value, online.value, 1) } }
        compose.onNodeWithText("Refresh quota").assertExists()
        compose.onNodeWithText("Refresh photos").assertDoesNotExist()
        compose.onNodeWithText("Identify plant").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false, saving = true, progress = 0.5f) }
        compose.onNodeWithText("Identify plant").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(saving = false); online.value = false }
        compose.onNodeWithText("Identify plant").assertIsNotEnabled()
        compose.onNodeWithText("Actions are unavailable offline. Reconnect and refresh to make changes.").performScrollTo().assertIsDisplayed()
    }
    // C11: photo updates show readable dates.
    @Test fun photoUpdatesShowReadableDates() {
        compose.setContent { FytoriumTheme { PhotoScreen(PhotoState(listOf(photo, photo.copy(id = "photo-two", cover = true)), loaded = true), true, "Synthetic sprout", 0) } }
        compose.onNodeWithText("13 Oct 2026").assertExists()
        compose.onNodeWithText("13 Oct 2026 \u00b7 Cover photo").assertExists()
        compose.onAllNodes(hasText("2026-10-13", substring = true)).assertCountEquals(0)
    }
    @Test fun takenDateKeepsItsUnconfirmedSelectionAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { PhotoScreen(PhotoState(today = "2026-10-13"), true, "Synthetic sprout", 0) } }
        compose.onNodeWithText("Add a photo update").performClick()
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
        compose.onNodeWithText("Add a photo update").performClick()
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
        compose.onNodeWithText("Discard photo update for Synthetic sprout?").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Discard photo update for Synthetic sprout?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        if (selected == 0 && !health && !date) compose.onNodeWithText("Synthetic draft").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Add a photo update").performClick()
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
        compose.onNodeWithText("Delete photo update").performScrollTo().performClick()
        compose.runOnIdle { state.value = PhotoState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Confirm delete").assertExists()
        compose.runOnIdle { state.value = PhotoState(listOf(photo), loaded = true) }
        compose.onNodeWithText("Confirm delete").performClick()
        compose.waitUntil { deleted != null }
        assertEquals("photo-one", deleted)
        compose.onNodeWithText("Confirm delete").assertDoesNotExist()
    }

    @Test fun identificationChoiceAndSearchSurviveRecreationWhileResultsReload() {
        val candidate = SpeciesCandidate(900001, "Testoria nebulosa", "Testoria nebulosa", "SPECIES", null, null, true, null, null, 0.91)
        val state = mutableStateOf(PhotoState(result = SpeciesSearch("synthetic-search", listOf(candidate))))
        var selected: String? = null
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { IdentificationScreen(state.value, true, 1, onSelect = { chosen, search, confirmed ->
            assertEquals(900001L, chosen.key); assertTrue(confirmed); selected = search
        }) } }
        compose.onNodeWithText("Choose Testoria nebulosa").performScrollTo().performClick()
        compose.runOnIdle { state.value = PhotoState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().performClick()
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
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Open original photo of Synthetic sprout").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open original photo of Synthetic sprout").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Original plant photo").fetchSemanticsNodes().isNotEmpty() }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Original plant photo").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Synthetic sprout updates").assertExists()
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
        compose.onNodeWithText("Selected photos: 1").assertExists()
        assertTrue(preparePhoto(app, androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".photos", file)).isNotEmpty())
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Add photo update") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add photo update").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic sprout updates").fetchSemanticsNodes().isNotEmpty() && !file.exists() }
        compose.onNodeWithText("Add a photo update").performClick()
        compose.onNodeWithText("Selected photos: 0").assertExists()
        assertFalse("A successful upload must remove the capture", file.exists())
        assertFalse(java.io.File(file.path + ".draft").exists())
    }

    @Test fun replacingACameraDraftRemovesOnlyTheReplacedImage() = cameraDraft { first, _ ->
        compose.onNodeWithText("Take photo").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Selected photos: 1").fetchSemanticsNodes().isNotEmpty() && !first.exists() }
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
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Add a photo update") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Add a photo update").performClick()
                compose.onNodeWithText("Take photo").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Selected photos: 1").fetchSemanticsNodes().isNotEmpty() }
                org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
                check(files.single(), restoration)
                if (compose.onAllNodesWithText("Selected photos: 0").fetchSemanticsNodes().isNotEmpty()) assertEquals(1, uploads.get())
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
            PhotoScreen(PhotoState(today = "2026-10-13"), true, "Synthetic sprout", selected.size,
                sourceControls = { PhotoSourceButtons(true, sources.take, sources.choose) }, onDiscard = { selected = emptyList() })
        } }
        compose.onNodeWithText("Add a photo update").performClick()
        compose.onNodeWithText("Choose photos").performClick()
        compose.onNodeWithText("Selected photos: 1").assertExists()
        assertNull(error)
        assertEquals(if (persistable) listOf(uri) else emptyList<android.net.Uri>(), app.contentResolver.persistedUriPermissions.map { it.uri })
        try {
            org.robolectric.util.ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Selected photos: 1").assertExists()
            assertEquals(if (persistable) listOf(uri) else emptyList<android.net.Uri>(), app.contentResolver.persistedUriPermissions.map { it.uri })
            compose.runOnIdle { remove() }
            compose.onNodeWithText("Selected photos: 0").assertExists()
            compose.waitUntil(5_000) { app.contentResolver.persistedUriPermissions.isEmpty() }
            compose.onNodeWithText("Choose photos").performClick()
            compose.onNodeWithText("Selected photos: 1").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard photo update for Synthetic sprout?").assertExists()
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { app.contentResolver.persistedUriPermissions.isEmpty() }
            compose.onNodeWithText("Add a photo update").performClick()
            compose.onNodeWithText("Selected photos: 0").assertExists()
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
            if (mode.value == "identify") IdentificationScreen(state.value, true, 1, sourceControls = {
                PhotoSourceButtons(!state.value.saving, {}, {})
            }, onSelect = { _, _, confirmed -> selection = confirmed })
            else key(mode.value) { PhotoScreen(state.value, true, if (mode.value == "journal") null else "Synthetic sprout", 1,
                sourceControls = { PhotoSourceButtons(true, {}, {}) }, onChange = { _, action -> command = action },
                onUpload = { _, _, _ -> state.value = state.value.copy(blocked = true, error = "Could not reach Fytorium. Try again.") },
                onRefresh = { state.value = state.value.copy(blocked = false, loading = true, error = null) }) }
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/a06-$name-${if (dark) "dark" else "light"}.png"
            if (name in listOf("date", "delete")) {
                val layout = compose.onNode(isDialog()).fetchSemanticsNode().layoutInfo
                val owner = layout.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layout) as android.view.View
                compose.runOnIdle { owner.drawToBitmap().captureRoboImage(file) }
            } else compose.onRoot().captureRoboImage(file)
        }
        compose.onNodeWithText("Use as cover").performScrollTo().assertHeightIsAtLeast(48.dp)
        capture("gallery")
        compose.onNodeWithText("Use as cover").performClick()
        assertEquals("cover", command)
        compose.onNodeWithText("Delete photo update").performClick()
        compose.onNodeWithText("Confirm delete").assertIsDisplayed()
        capture("delete")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Add a photo update").assertIsDisplayed().performClick()
        compose.onNodeWithText("Take photo").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Choose photos").assertIsDisplayed()
        capture("form")
        compose.onNodeWithText("Note").performScrollTo().performTextReplacement("Synthetic unsaved note")
        compose.onNodeWithText("Add photo update").performScrollTo().performClick()
        compose.onNodeWithText("Add photo update").assertIsNotEnabled()
        capture("failure")
        compose.onNodeWithText("Refresh photos").performScrollTo().performClick()
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
        compose.onNodeWithText("Plant journal").assertIsDisplayed()
        capture("journal")
        val bitmap = android.graphics.Bitmap.createBitmap(200, 150, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(90, 160, 100))
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
        compose.runOnIdle { state.value = state.value.copy(original = bytes) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Original plant photo").fetchSemanticsNodes().isNotEmpty() }
        capture("original")
        compose.runOnIdle { mode.value = "identify"; state.value = state.value.copy(original = null, saving = true, uploading = true, progress = 0.5f) }
        capture("identify-upload")
        compose.runOnIdle { state.value = state.value.copy(saving = false, result = SpeciesSearch("synthetic-search", listOf(candidate)), remaining = 20) }
        compose.onNodeWithText("91% identification score").performScrollTo().assertIsDisplayed()
        capture("identify-results")
        compose.onNodeWithText("Choose Testoria nebulosa").performScrollTo().performClick()
        assertNull(selection)
        capture("confirm")
        compose.onNodeWithText("Confirm Testoria nebulosa").performScrollTo().performClick()
        assertEquals(true, selection)
    }
}
