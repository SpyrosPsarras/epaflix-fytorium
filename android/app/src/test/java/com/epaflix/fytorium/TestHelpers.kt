package com.epaflix.fytorium

import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.drawToBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

@Composable
internal fun CheckLandscape(fontScale: Float) {
    val configuration = LocalConfiguration.current
    assertEquals(Configuration.ORIENTATION_LANDSCAPE, configuration.orientation)
    assertEquals(800, configuration.screenWidthDp)
    assertEquals(360, configuration.screenHeightDp)
    assertEquals(fontScale, configuration.fontScale, 0.01f)
    assertEquals(fontScale, LocalDensity.current.fontScale, 0.01f)
}

internal fun ComposeTestRule.checkDialogTextScale(label: String, fontScale: Float) {
    val text = onNode(hasText(label) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNode().textLayout()
    assertEquals("Dialog text scale for $label", fontScale, text.layoutInput.density.fontScale, 0.01f)
    checkDialogBody(label)
}

internal fun ComposeTestRule.checkDialogBody(label: String) {
    val dialog = onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode()
    fun bodies(node: SemanticsNode): List<SemanticsNode> =
        if (node.config.contains(SemanticsProperties.VerticalScrollAxisRange)) listOf(node)
        else node.children.flatMap(::bodies)
    for (body in bodies(dialog)) {
        val range = body.config[SemanticsProperties.VerticalScrollAxisRange]
        val children = body.children
        val extent = if (children.isEmpty()) 0f else children.maxOf { it.positionInRoot.y + it.size.height } - children.minOf { it.positionInRoot.y }
        assertEquals("No empty body band in $label", extent, body.size.height + range.maxValue(), 1f)
    }
}

internal fun SemanticsNodeInteractionsProvider.assertDiscard(body: String) {
    onNodeWithText("Discard changes?").assertIsDisplayed()
    assertDialogBody(body)
}

// W4-P18: a new plant's form asks with its species named.
internal fun SemanticsNodeInteractionsProvider.assertNewPlantDiscard() {
    onNodeWithText("Discard this plant?").assertIsDisplayed()
    onNode(hasText("is not added yet.", substring = true) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
}

internal fun SemanticsNodeInteractionsProvider.assertDialogBody(body: String) {
    onNode(hasText(body) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
}

internal fun inRow(plant: String, key: String = "water") = hasAnyAncestor(hasTestTag("task:$plant:$key"))

internal fun SemanticsNodeInteractionsProvider.explains(line: String, vararg explanation: String) {
    onNode(hasText(line) and hasClickAction()).assertIsDisplayed().performClick()
    explanation.forEach { onNode(hasText(it, substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed() }
    // The scrolling status carries the time once; its explanation does not repeat it.
    Regex("\\d{1,2}:\\d{2}").find(line)?.value?.let { time ->
        assertEquals("The explanation repeats $time", 1,
            onAllNodes(hasText(time, substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
    onNodeWithText("Close").performClick()
}

// Roborazzi 1.44's window fallback misses Compose dialogs. Draw their owning view directly.
internal fun ComposeTestRule.captureDialog(file: String) {
    checkDialogBody(file)
    val dialog = onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode()
    fun textLines(node: SemanticsNode): List<JSONObject> {
        val lines = if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
            val text = node.textLayout()
            val bounds = node.boundsInRoot
            listOf(JSONObject().put("text", text.layoutInput.text.text).put("bounds", bounds.toString()).put("lines", JSONArray((0 until text.lineCount).map { line ->
                JSONObject().put("text", text.layoutInput.text.text.substring(text.getLineStart(line), text.getLineEnd(line, visibleEnd = true)))
                    .put("visible", node.positionInRoot.y + text.getLineBottom(line) > bounds.top && node.positionInRoot.y + text.getLineTop(line) < bounds.bottom && bounds.height > 0)
            })))
        } else emptyList()
        return lines + node.children.flatMap(::textLines)
    }
    val configuration = org.robolectric.RuntimeEnvironment.getApplication().resources.configuration
    val surface = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNode()
    println("DIALOG_SEMANTICS " + JSONObject().put("file", file).put("windowDp", JSONArray(listOf(configuration.screenWidthDp, configuration.screenHeightDp)))
        .put("dialogBounds", dialog.boundsInRoot.toString()).put("surfaceBounds", surface.boundsInRoot.toString()).put("nodes", JSONArray(textLines(dialog))))
    val owner = dialog.dialogOwner()
    runOnIdle { owner.drawToBitmap().captureRoboImage(file) }
}

internal fun SemanticsNode.dialogOwner(): View =
    layoutInfo.javaClass.methods.first { it.name.startsWith("getOwner") }.invoke(layoutInfo) as View

internal fun SemanticsNode.textLayout(): TextLayoutResult =
    mutableListOf<TextLayoutResult>().also { config[SemanticsActions.GetTextLayoutResult].action!!(it) }.single()

internal fun CareTask.postponed(kind: String, until: String) =
    copy(postponedUntil = until, completedEvent = parseEvent(JSONObject(eventFixture)).copy(id = "event-$plantId", plantId = plantId, kind = kind, dueOn = dueOn))

// A screen fed states directly sees this device's write or Undo only with the transitions the repository records for it: to the row with the same plant, task and due date.
internal fun CareState.written(next: CareState): CareState {
    fun rows(state: CareState) = listOfNotNull(state.today, state.upcoming).flatMap { list -> list.sections.flatMap { it.tasks } }
    fun CareTask.acted() = Triple(plantId, key, completedEvent?.dueOn ?: dueOn)
    val after = rows(next)
    return next.copy(transitions = transitions + rows(this).filter { row -> after.none { it.occurrence == row.occurrence } }.mapNotNull { row ->
        after.firstOrNull { it.acted() == row.acted() }?.let { row.occurrence to it.occurrence } })
}

internal fun withCache(memberId: String = "member-one", test: (CareCache, ApiClient, MockWebServer) -> Unit) {
    val db = androidx.room.Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), CareDatabase::class.java).allowMainThreadQueries().build()
    val server = MockWebServer(); server.start()
    try {
        val cache = CareCache(db).apply { bind("""{"member":{"id":"$memberId","role":"owner"}}""") }
        test(cache, ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}, server)
    } finally { server.shutdown(); db.close() }
}
