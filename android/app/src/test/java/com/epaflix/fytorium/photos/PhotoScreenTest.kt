package com.epaflix.fytorium.photos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
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
        compose.onNodeWithText("Taken on: 2026-10-13").performScrollTo().performClick()
        capture("date")
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
