package com.epaflix.fytorium

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.OutputStream

// Shared MediaStore files survive Gradle's uninstall; docker/e2e pulls them into .scratch/e2e-failures.
class FailureCapture : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = "${description.testClass.simpleName}-${description.methodName}"
        fun publish(file: String, mime: String, collection: Uri, folder: String, write: (OutputStream) -> Unit) {
            val resolver = instrumentation.targetContext.contentResolver
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            })!!
            resolver.openOutputStream(uri)!!.use(write)
        }
        runCatching {
            publish("$name.png", "image/png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/e2e-failures") {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            publish("$name.xml", "text/xml", MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/e2e-failures") {
                UiDevice.getInstance(instrumentation).dumpWindowHierarchy(it)
            }
            println("E2E failure capture saved: $name")
        }.onFailure { println("E2E failure capture failed: $it") }
    }
}

internal fun MainActivity.imeVisible(): Boolean? =
    ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())

internal fun MainActivity.hideIme() = WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())

internal fun ComposeContentTestRule.waitForState(
    description: String,
    timeoutMillis: Long = 30_000,
    details: () -> String = { "" },
    required: Boolean = true,
    condition: () -> Boolean
): Boolean {
    try {
        waitUntil(timeoutMillis, condition)
        return true
    } catch (error: ComposeTimeoutException) {
        val message = "Wait '$description' timed out after ${timeoutMillis}ms; ${stateDump()}; ${details()}"
        if (required) throw AssertionError(message, error)
        println("Optional $message")
        return false
    }
}

private fun ComposeContentTestRule.stateDump(): String {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FytoriumApplication
    val care = app.repository.state.value
    val plants = app.repository.plantState.value
    val feed = app.feed.state.value
    fun present(matcher: SemanticsMatcher) = runCatching { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    val tab = listOf("Today", "Plants", "Journal", "More").filter { present(hasText(it) and isSelected()) }
    val controls = listOf("Log in", "Create site", "Save site", "Species name", "Save plant", "Refresh care data", "Refresh settings", "About")
        .filter { present(hasText(it)) }
    val views = listOf("view-today", "view-upcoming").filter { present(hasTestTag(it) and isSelected()) }
    // Only allowlisted flags and schedule fields, never tokens, free-form messages or input text.
    val tasks = care.today?.sections?.flatMap { it.tasks }?.take(12)?.map {
        "${it.plantId}:${it.key}[completed=${it.completedEvent != null},late=${it.lateDays},due=${it.dueOn},water=${it.basedOn.firstOrNull { b -> b.input == "wateringIntervalDays" }?.let { b -> "${b.source}/${b.value as? Number}" }}]"
    }
    return "tab=$tab, view=$views, controls=$controls, loggedIn=${app.session.state.value.loggedIn}, connected=${app.care.connected()}, " +
        "care[member=${care.member != null},busy=${care.busy},offline=${care.offline},serverUnavailable=${care.serverUnavailable},messagePresent=${care.message != null},today=${care.today?.today},showCompleted=${care.today?.settings?.showCompleted},plants=${care.plants.size},detail=${care.detail?.plant?.id}/${care.detail?.plant?.status},tasks=$tasks], " +
        "plants[refreshing=${plants.refreshing},saving=${plants.saving},errorFields=${plants.errors.keys}], " +
        "feed[refreshing=${feed.refreshing},saving=${feed.saving},cards=${feed.cards.take(12).map { it.kind }},rainBatch=${feed.weather?.lastBatchId != null}]"
}

internal fun ComposeContentTestRule.scrollTaskClearOfReceipt(tag: String) {
    val row = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    val receiptTop = onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss)).fetchSemanticsNodes()
        .minOfOrNull { it.boundsInRoot.top } ?: return
    val overlap = row.fetchSemanticsNode().boundsInRoot.bottom - receiptTop + 16f
    if (overlap > 0f) onNodeWithTag("task-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, overlap) }
    row.assertIsDisplayed()
    check(row.fetchSemanticsNode().boundsInRoot.bottom < receiptTop) { "Task $tag is still behind the receipt" }
}

// A receipt covers the navigation bar, which ignores taps for half a second after it goes, so swipe it away and wait like a user.
internal fun ComposeContentTestRule.selectTab(title: String) {
    val receipt = hasTestTag("receipt")
    var swiped = false
    waitForState("Navigation bar takes taps for $title") {
        val covered = onAllNodes(receipt).fetchSemanticsNodes().isNotEmpty()
        if (covered && !swiped) {
            val text = onAllNodes(hasAnyAncestor(receipt), useUnmergedTree = true).fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            println("E2E tab $title: receipt $text covers the bar, swiping it away")
            onAllNodes(receipt).onFirst().performTouchInput { swipeRight() }
        }
        swiped = covered
        // The bar also hides while the keyboard is open, so wait until it is back.
        !covered && onAllNodes(hasTestTag("bar-paused")).fetchSemanticsNodes().isEmpty() &&
            onAllNodes(hasText(title) and hasClickAction() and !hasTestTag("view-today")).fetchSemanticsNodes().isNotEmpty()
    }
    onNode(hasText(title) and hasClickAction() and !hasTestTag("view-today")).performClick().assertIsSelected()
}

// Candidates show the fetched familiar name only for a species Fytorium already has (W1), so tell the caller which applies.
internal fun ComposeContentTestRule.waitForSpeciesNames(app: FytoriumApplication, key: Long): Boolean {
    val existed = app.api.request("/api/species/$key").use { it.code } != 404
    println("E2E species $key existed: $existed")
    if (existed) waitForState("Species $key names fetched", 30_000) {
        app.api.request("/api/species/$key").use { it.isSuccessful && !JSONObject(it.body!!.string()).getBoolean("name_fetch_needed") }
    }
    return existed
}

// Back would leave the Custom Tab if the keyboard had closed meanwhile; blurring the focused field closes it in place.
internal fun blurFocusedChromeField() {
    fun focusedField(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = when {
        node == null -> null
        node.isFocused && node.className == "android.widget.EditText" -> node
        else -> (0 until node.childCount).firstNotNullOfOrNull { focusedField(node.getChild(it)) }
    }
    val field = checkNotNull(focusedField(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)) { "No focused Chrome field to blur" }
    println("E2E keyboard: blurred focused field ${field.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)}")
}

internal fun ComposeContentTestRule.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitForState("Node ${matcher.description}") { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    return onNode(matcher)
}
