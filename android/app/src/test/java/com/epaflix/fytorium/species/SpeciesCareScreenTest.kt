package com.epaflix.fytorium.species

import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import androidx.core.view.drawToBitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SpeciesCareScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun card(): CareCard = JSONObject(careFixture).let {
        CareCard(910002, "Testoria nebulosa Inventor", it.getJSONObject("profile"), it.getJSONArray("fetches").mapObjects { row -> row }, JSONObject(outdoorFixture),
            "Testoria nebulosa", "Synthetic mistleaf")
    }
    @Test fun anOverrideDiscardQuestionSurvivesRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SpeciesCareScreen(SpeciesCareState(card = card(), loaded = true)) } }
        compose.onNodeWithText("Override watering interval in days").performScrollTo().performClick()
        compose.onNodeWithText("Days").performTextReplacement("19")
        compose.onNodeWithText("Cancel").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Keep editing").assertIsDisplayed()
    }

    @Test fun anOverrideRemovalQuestionSurvivesRecreation() {
        val own = card().let { it.copy(profile = JSONObject(it.profile.toString()).apply { getJSONObject("wateringIntervalDays").put("source", "override") }) }
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { SpeciesCareScreen(SpeciesCareState(card = own, loaded = true)) } }
        compose.onNodeWithText("Remove override for watering interval in days").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Confirm reset").assertIsDisplayed()
    }

    @Test fun anOfflineRecreatedOverrideStillAsksAndDoesNotLeakIntoAnotherField() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(
                    if (request.path!!.endsWith("outdoor-months")) outdoorFixture else careFixture.replace("pending", "done"))
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val online = mutableStateOf(true)
            val answers = mutableIntStateOf(0)
            var exits = 0
            var dirty: () -> String? = { null }
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
            restoration.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("synthetic-member", Role.owner)),
                { online.value }, { exits++ }, answers = answers.intValue, onFormDirty = { dirty = it }) } }
            val open = hasText("Override watering interval in days") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(open).performScrollTo().performClick()
            compose.onNodeWithText("Days").performTextReplacement("19")
            compose.runOnIdle { online.value = false }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { assertNotNull(dirty()) }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Keep editing").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(0, exits); online.value = true; answers.intValue++ }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Days").assertTextContains("19")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText("No value yet", substring = true).performScrollTo().performClick()
            compose.onNodeWithText("Override source care text").performScrollTo().performClick()
            compose.onNode(hasText("Care text") and hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        }
    }

    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val state = mutableStateOf(SpeciesCareState(card = card(), loaded = true))
        val online = mutableStateOf(true)
        var value: Any? = null
        var reset: String? = null
        var retried: Long? = null
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SpeciesCareScreen(state.value, online.value, onOverride = { _, input -> value = input; true },
                onReset = { reset = it; true }, onRetry = { retried = it })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/$name-${if (dark) "dark" else "light"}.png"
            if (name in listOf("species-override", "species-reset")) {
                // Roborazzi window capture misses Compose dialogs, as in PlantsScreenTest.
                val layout = compose.onNode(isDialog()).fetchSemanticsNode().layoutInfo
                val owner = layout.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layout) as android.view.View
                compose.runOnIdle { owner.drawToBitmap().captureRoboImage(file) }
            } else compose.onRoot().captureRoboImage(file)
        }
        compose.onNodeWithContentDescription("Back").assertExists()
        capture("species-care")
        // Source checks and refreshes wait under Sources.
        compose.onNodeWithText("Sources").performScrollTo().performClick()
        compose.onNodeWithText("Refresh care data").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Watering interval in days: rules · water.base@1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Override watering interval in days").performScrollTo().performClick()
        compose.onNodeWithText("Override value for watering interval in days").assertIsDisplayed()
        compose.onNodeWithText("Days").performTextReplacement("9")
        capture("species-override")
        compose.onNodeWithText("Save override").performClick()
        compose.runOnIdle {
            assertEquals(9, value)
            val profile = JSONObject(state.value.card!!.profile.toString()).put("wateringIntervalDays", JSONObject("""{"value":9,"source":"override","fetchedAt":"2030-06-02T12:00:00Z"}"""))
            state.value = state.value.copy(card = state.value.card!!.copy(profile = profile))
        }
        // A value of your own reads as yours, never as fetched.
        compose.onNode(hasText("Your value") and hasAnyAncestor(hasTestTag("care:wateringIntervalDays"))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Watering interval in days: your value").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove override for watering interval in days").performScrollTo().performClick()
        capture("species-reset")
        compose.onNodeWithText("Confirm reset").performClick()
        compose.runOnIdle { assertEquals("wateringIntervalDays", reset); online.value = false }
        compose.onNodeWithText("Override watering interval in days").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Offline") and hasClickAction()).assertIsDisplayed()
        capture("species-offline")
        compose.runOnIdle { online.value = true; state.value = SpeciesCareState(unmatched = listOf(UnmatchedSpecies(910002, "Testoria nebulosa", "ambiguous", "Synthetic mistleaf")), loaded = true) }
        compose.onNode(hasText("Synthetic mistleaf") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("No clear care data match yet").assertIsDisplayed()
        capture("species-unmatched")
        compose.onNodeWithText("Look for care data again").performClick()
        compose.runOnIdle { assertEquals(910002L, retried) }
    }
    // C1b: the connection, a failure, a save's result and progress show in the bar, so no field or control moves as one starts or ends.
    @Test fun statusChangesMoveNoControl() = statusChangesMoveNoControl(1f)
    @Test fun statusChangesMoveNoControlAtLargeText() = statusChangesMoveNoControl(1.3f)
    private fun statusChangesMoveNoControl(fontScale: Float) {
        val state = mutableStateOf(SpeciesCareState(card = card(), loaded = true))
        val online = mutableStateOf(true)
        var refreshed = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            FytoriumTheme { SpeciesCareScreen(state.value, online.value, onRefresh = { refreshed++ }) } } }
        val page = compose.onNode(hasScrollAction())
        page.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        val controls = hasAnyAncestor(hasScrollAction()) and hasClickAction()
        fun places() = compose.onAllNodes(controls).fetchSemanticsNodes().map { it.boundsInRoot } + page.fetchSemanticsNode().boundsInRoot
        // A progress bar's semantic bounds reach past the bar it draws, so the check uses each node's centre.
        fun above(matcher: SemanticsMatcher, what: String) = assertTrue("$what sits above the page at ${fontScale}x text",
            compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.center.y <= page.fetchSemanticsNode().boundsInRoot.top)
        val before = places()
        assertTrue(before.size > 3)
        fun unmoved(change: String) = assertEquals("A field or control moved when $change at ${fontScale}x text", before, places())
        compose.runOnIdle { online.value = false }
        above(hasText("Offline") and hasClickAction(), "The offline line")
        unmoved("the connection went")
        compose.explains("Offline", "Reconnect and refresh to make changes.")
        compose.runOnIdle { online.value = true }
        compose.onNode(hasText("Offline", substring = true)).assertDoesNotExist()
        unmoved("the connection came back")
        val error = "Could not reach the server. Try again."
        compose.runOnIdle { state.value = state.value.copy(blocked = true, error = error) }
        above(hasText(error) and hasClickAction(), "The error")
        unmoved("an error showed")
        compose.onNode(hasText(error) and hasClickAction()).performClick()
        compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, refreshed); state.value = state.value.copy(blocked = false, error = null, saving = true) }
        compose.onNode(hasText(error)).assertDoesNotExist()
        above(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), "Progress")
        unmoved("the error cleared and progress showed")
        val saved = "Refresh queued. Existing overrides are kept."
        compose.runOnIdle { state.value = state.value.copy(saving = false, message = saved) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        above(hasText(saved) and hasClickAction(), "The result")
        unmoved("a result showed")
        compose.onNode(hasText(saved) and hasClickAction()).performClick()
        compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { state.value = state.value.copy(message = null) }
        compose.onNode(hasText(saved)).assertDoesNotExist()
        unmoved("the result cleared")
    }
    // C23: a failed save's message in the override and reset dialogs never moves their buttons, also when it is long and changes are off until a reload.
    @Test fun aFailedSaveInADialogLeavesItsButtonsInPlace() {
        val overridden = card().let { it.copy(profile = JSONObject(it.profile.toString()).put("wateringIntervalDays", JSONObject("""{"value":9,"source":"override","fetchedAt":null}"""))) }
        val state = mutableStateOf(SpeciesCareState(card = overridden, loaded = true))
        compose.setContent { FytoriumTheme { SpeciesCareScreen(state.value) } }
        for ((open, confirm) in listOf("Override watering interval in days" to "Save override", "Remove override for watering interval in days" to "Confirm reset")) {
            compose.runOnIdle { state.value = state.value.copy(error = null, blocked = false) }
            compose.onNodeWithText(open).performScrollTo().performClick()
            val before = compose.onNodeWithText(confirm).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { state.value = state.value.copy(error = longFailure, blocked = true) }
            compose.onNode(hasText(longFailure) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            assertEquals("$confirm moved", before, compose.onNodeWithText(confirm).fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("Cancel").performClick()
        }
    }
    private val longFailure = "Fytorium could not store this value because the synthetic care service stopped answering while it saved. Your value is still here, so try again once it answers."
    // C23: an override save that fails on its way (no connection, 503, a long message) shows its message and Retry without moving Save.
    @Test fun aFailedOverrideSaveNeverMovesSave() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val failure = java.util.concurrent.atomic.AtomicReference<okhttp3.mockwebserver.MockResponse>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                    request.method == "PUT" -> failure.get()
                    request.path!!.endsWith("/outdoor-months") -> okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture)
                    else -> okhttp3.mockwebserver.MockResponse().setBody(careFixture.replace("pending", "done"))
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}) } }
            val open = "Override watering interval in days"
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(open) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(open).performScrollTo().performClick()
            compose.onNodeWithText("Days").performTextReplacement("9")
            val save = hasText("Save override")
            val before = compose.onNode(save).fetchSemanticsNode().boundsInRoot
            val unreachable = "Could not reach the server. Try again."
            for ((response, message) in listOf(
                okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) to unreachable,
                okhttp3.mockwebserver.MockResponse().setResponseCode(503) to unreachable,
                okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody(JSONObject().put("message", longFailure).toString()) to longFailure)) {
                failure.set(response)
                compose.onNode(save).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Retry loading species")).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Save moved after: $message", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
                compose.onNode(hasText(message) and hasAnyAncestor(isDialog())).assertIsDisplayed()
                compose.onNodeWithText("Retry loading species").assertIsDisplayed().performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Save moved after Retry", before, compose.onNode(save).fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithText("Days").assertTextContains("9")
            }
        }
    }
    // C21: a read that fails while a failed save has turned changes off loads again once Fytorium answers another request, and that load turns changes back on.
    @Test fun aReadThatFailsWhileChangesAreOffLoadsOnceFytoriumAnswers() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val down = java.util.concurrent.atomic.AtomicBoolean(false)
            val reads = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.method == "GET" && request.path!!.endsWith("/care")) reads.incrementAndGet()
                    return when {
                        request.method == "PUT" || down.get() -> okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                        request.path!!.endsWith("/outdoor-months") -> okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture)
                        else -> okhttp3.mockwebserver.MockResponse().setBody(careFixture.replace("pending", "done"))
                    }
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val answers = mutableIntStateOf(0)
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}, answers = answers.intValue) } }
            val open = "Override watering interval in days"
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(open) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(open).performScrollTo().performClick()
            compose.onNodeWithText("Days").performTextReplacement("9")
            val save = hasText("Save override")
            compose.onNode(save).performClick()
            val retry = hasText("Retry loading species")
            compose.waitUntil(5_000) { compose.onAllNodes(retry and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            down.set(true)
            val before = reads.get()
            compose.onNode(retry).performClick()
            compose.waitUntil(5_000) { reads.get() > before && compose.onAllNodes(retry and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            down.set(false)
            compose.runOnIdle { answers.intValue++ }
            compose.waitUntil(5_000) { compose.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(retry).assertCountEquals(0)
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onAllNodes(hasText("Could not reach the server. Try again.")).assertCountEquals(0)
        }
    }
    @Test fun backgroundReadsKeepDraftsEnabledAndFailedWritesKeepTheEditor() {
        val state = mutableStateOf(SpeciesCareState(card = card(), loaded = true))
        compose.setContent { FytoriumTheme { SpeciesCareScreen(state.value, onOverride = { _, _ ->
            state.value = state.value.copy(loading = false, blocked = true, error = "Invalid value for wateringIntervalDays"); false
        }, onRefresh = { state.value = state.value.copy(blocked = false, error = null) }) } }
        compose.onNodeWithText("Override watering interval in days").performScrollTo().performClick()
        compose.onNodeWithText("Days").performTextReplacement("9")
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("Save override").assertIsEnabled().performClick()
        compose.onNodeWithText("Invalid value for wateringIntervalDays").assertIsDisplayed()
        compose.onNodeWithText("Days").assertTextContains("9")
        compose.onNodeWithText("Retry loading species").performClick()
        compose.onNodeWithText("Days").assertTextContains("9")
        compose.onNodeWithText("Save override").assertIsEnabled()
    }
    @Test fun onlyOwnerAndFamilySeeUnmatchedSpeciesUnderMore() {
        val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner)))
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care.value) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Unmatched species").assertIsDisplayed()
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.family)) }
        compose.onNodeWithText("Unmatched species").assertIsDisplayed()
        compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
        compose.onNodeWithText("Unmatched species").assertDoesNotExist()
    }
    @Test fun pendingCarePollsOnlyWhileStartedAndStopsWhenDone() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(careFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(careFixture.replace("pending", "done")))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply {
                tokens = Tokens("synthetic-access", "synthetic-refresh")
            }) {}
            val owner = object : androidx.lifecycle.LifecycleOwner {
                val registry = androidx.lifecycle.LifecycleRegistry(this)
                override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
            }
            compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            compose.setContent { CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}) }
            } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sources").performScrollTo().performClick()
            Thread.sleep(2_200)
            assertEquals(2, server.requestCount)
            compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
            compose.waitUntil(5_000) { server.requestCount == 4 }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Open Plantbook fetch: done")).fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(2_200)
            assertEquals(4, server.requestCount)
        }
    }
    // C1b: a poll that fails while an override or reset dialog is open changes nothing in it and shows in the line, which keeps it with Try again until a load succeeds; a save shows its own error in the dialog.
    @Test fun aFailedPollShowsInTheLineAndNeverInADialog() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val failing = java.util.concurrent.atomic.AtomicBoolean(false)
            val overridden = JSONObject(careFixture).apply {
                getJSONObject("profile").put("wateringIntervalDays", JSONObject("""{"value":9,"source":"override","fetchedAt":null}"""))
            }.toString()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                    failing.get() -> okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                    request.path!!.endsWith("/outdoor-months") -> okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture)
                    else -> okhttp3.mockwebserver.MockResponse().setBody(overridden)
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}) } }
            val failure = "Could not reach the server. Try again."
            fun dialog() = compose.onAllNodes(hasAnyAncestor(isDialog())).fetchSemanticsNodes().map {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString() to it.config.contains(SemanticsProperties.Disabled) }
            for ((open, confirm) in listOf("Override watering interval in days" to "Save override", "Remove override for watering interval in days" to "Confirm reset")) {
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(open) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(open).performScrollTo().performClick()
                compose.onNode(hasText(confirm) and isEnabled()).assertIsDisplayed()
                val before = dialog()
                failing.set(true)
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(failure) and hasClickAction() and !hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("The failed poll changed the open dialog", before, dialog())
                compose.onNodeWithText(confirm).assertIsEnabled().performClick()
                // Cancel stays off until the save has returned.
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(failure) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasText("Cancel") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Cancel").performClick()
                compose.onNode(isDialog()).assertDoesNotExist()
                failing.set(false)
                compose.onNode(hasText(failure) and hasClickAction()).assertIsDisplayed().performClick()
                compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText(failure)).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText(open).performScrollTo().assertIsEnabled()
                // Polling picks up again once the species loads.
                val loaded = server.requestCount
                compose.waitUntil(5_000) { server.requestCount >= loaded + 2 }
            }
        }
    }
    // C1b: a save that fails and turns changes off shows in its dialog, and after Cancel the line keeps it with Try again until a load succeeds.
    @Test fun aFailedSaveStaysInTheLineAfterCancel() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val failing = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                    failing.get() -> okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                    request.path!!.endsWith("/outdoor-months") -> okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture)
                    else -> okhttp3.mockwebserver.MockResponse().setBody(careFixture.replace("pending", "done"))
                }
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}) } }
            val failure = "Could not reach the server. Try again."
            val open = "Override watering interval in days"
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(open) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(open).performScrollTo().performClick()
            compose.onNodeWithText("Days").performTextReplacement("9")
            failing.set(true)
            compose.onNodeWithText("Save override").performClick()
            // Cancel stays off until the save has returned.
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(failure) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Cancel") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Discard").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            failing.set(false)
            compose.onNode(hasText(failure) and hasClickAction()).assertIsDisplayed().performClick()
            compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(failure)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText(open).performScrollTo().assertIsEnabled()
        }
    }
    // C19: the override dialog asks before Back, a tap outside it, Cancel or a notification tap drops a typed value, naming the field and species; Keep editing keeps it.
    @Test fun theOverrideDialogAsksBeforeDroppingATypedValue() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val care = JSONObject(careFixture.replace("pending", "done")).apply {
                getJSONObject("species").put("canonical_name", "Testoria nebulosa").put("common_name", "Synthetic mistleaf") }.toString()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(when (request.path) {
                    "/api/species/unmatched" -> """[{"gbif_key":910002,"canonical_name":"Testoria nebulosa","common_name":"Synthetic mistleaf","status":"no_match"}]"""
                    "/api/species/910002/care" -> care
                    "/api/species/910002/outdoor-months" -> outdoorFixture
                    else -> "{}"
                })
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val home = mutableIntStateOf(0)
            compose.setContent { FytoriumTheme { AppShell(true, false, careState = CareState(member = Member("synthetic-member", Role.owner)), sitesApi = api,
                sitesConnected = { true }, homeRequest = home.intValue) } }
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Unmatched species").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Species care for Synthetic mistleaf").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Species care for Synthetic mistleaf").performClick()
            val open = hasText("Override watering interval in days") and isEnabled()
            compose.waitUntil(5_000) { compose.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(open).performScrollTo().performClick()
            compose.onNodeWithText("Days").performTextReplacement("9")
            // Taken while the override dialog is the only one shown, so Back and the outside tap reach its window, not the discard question's.
            val dialog = checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            fun back() = compose.runOnIdle { checkNotNull(dialog.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher.onBackPressed() }
            fun tapOutside() = compose.runOnIdle {
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP))
                    android.view.MotionEvent.obtain(0, 0, action, 1f, 1f, 0).let { dialog.dispatchTouchEvent(it); it.recycle() }
            }
            val question = "Discard your watering interval for Synthetic mistleaf?"
            for (leave in listOf<() -> Unit>(::back, ::tapOutside, { compose.onNodeWithText("Cancel").performClick() }, { compose.runOnIdle { home.intValue++ } })) {
                leave()
                compose.onNodeWithText(question).assertIsDisplayed()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithText("Days").assertTextContains("9")
            }
            compose.runOnIdle { home.intValue++ }
            compose.onNodeWithText("Discard").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Days").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText(question).assertDoesNotExist()
        }
    }
    @Test fun careFailuresNeverGateSpeciesAndSessionChangesHideControls() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(careFixture))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture))
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
            val care = mutableStateOf(CareState(member = Member("synthetic-member", Role.owner), busy = true, offline = true, serverUnavailable = true))
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, care.value, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sources").performScrollTo().performClick()
            compose.onNodeWithText("Refresh care data").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Override watering interval in days").performScrollTo().assertIsEnabled()
            compose.runOnIdle { care.value = care.value.copy(member = Member("synthetic-member", Role.sitter)) }
            compose.onNodeWithText("Refresh care data").assertDoesNotExist()
            compose.onNodeWithText("Retry match").assertDoesNotExist()
            compose.onNodeWithText("Override watering interval in days").assertDoesNotExist()
        }
    }

    @Test fun speciesCareLeadsWithItsValuesAndFoldsItsSources() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val care = JSONObject(careFixture).apply { getJSONObject("species").put("canonical_name", "Testoria nebulosa").put("common_name", "Synthetic mistleaf") }.toString()
            repeat(2) { server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(care.replace("pending", "done"))); server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(outdoorFixture)) }
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { SpeciesCareRoute(api, 910002, CareState(member = Member("owner", Role.owner)), { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Override watering interval in days") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Synthetic mistleaf") and isHeading()).assertIsDisplayed()
            compose.onNodeWithText("Testoria nebulosa").assertIsDisplayed()
            compose.onAllNodes(hasText("Inventor", substring = true)).assertCountEquals(0)
            // Values lead; each known source waits under Sources, with a readable date.
            for (gone in listOf("Source: unknown", "Fetch time", "GBIF key", "Retry match", "Refresh care data", "2030-06-01T", "open plantbook")) compose.onAllNodes(hasText(gone, substring = true)).assertCountEquals(0)
            compose.onNodeWithText("Sources").performScrollTo().performClick()
            compose.onNodeWithText("Light: open plantbook \u00b7 fetched 1 Jun 2030").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("GBIF key: 910002").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Refresh care data").performScrollTo().assertIsDisplayed()
            // A minimum above the maximum says so while typing, blocks Save, and clears once the values are valid.
            compose.onNodeWithText("No value yet (13)").performScrollTo().performClick()
            compose.onNodeWithText("Override temperature range in \u00b0C").performScrollTo().performClick()
            compose.onNode(hasText("Minimum") and hasSetTextAction()).performTextReplacement("30")
            compose.onNode(hasText("Maximum") and hasSetTextAction()).performTextReplacement("20")
            compose.onNodeWithText("Minimum must be lower than maximum").assertIsDisplayed()
            compose.onNodeWithText("Save override").assertIsNotEnabled()
            compose.onNode(hasText("Maximum") and hasSetTextAction()).performTextReplacement("35")
            compose.onNodeWithText("Minimum must be lower than maximum").assertDoesNotExist()
            compose.onNodeWithText("Save override").assertIsEnabled()
        }
    }
}
