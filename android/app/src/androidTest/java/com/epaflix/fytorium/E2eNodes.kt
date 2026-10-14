package com.epaflix.fytorium

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry

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
        "care[member=${care.member != null},busy=${care.busy},offline=${care.offline},serverUnavailable=${care.serverUnavailable},messagePresent=${care.message != null},today=${care.today?.today},showCompleted=${care.today?.settings?.showCompleted},plants=${care.plants.size},detail=${care.detail?.plant?.id}/${care.detail?.plant?.status},handled=${care.handled.size},tasks=$tasks], " +
        "plants[refreshing=${plants.refreshing},saving=${plants.saving},errorFields=${plants.errors.keys}], " +
        "feed[refreshing=${feed.refreshing},saving=${feed.saving},cards=${feed.cards.take(12).map { it.kind }},rainBatch=${feed.weather?.lastBatchId != null}]"
}

internal fun ComposeContentTestRule.scrollTaskClearOfFabs(tag: String) {
    val row = onNodeWithTag(tag).performScrollTo()
    val fabTop = onNodeWithContentDescription("Add plant").fetchSemanticsNode().boundsInRoot.top
    val overlap = row.fetchSemanticsNode().boundsInRoot.bottom - fabTop + 16f
    if (overlap > 0f) onNodeWithTag("task-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, overlap) }
    row.assertIsDisplayed()
    check(row.fetchSemanticsNode().boundsInRoot.bottom < fabTop) { "Task $tag is still behind the FAB" }
}

internal fun ComposeContentTestRule.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitForState("Node ${matcher.description}") { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    return onNode(matcher)
}
