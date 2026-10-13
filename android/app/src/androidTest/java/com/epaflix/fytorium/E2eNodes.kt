package com.epaflix.fytorium

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun ComposeContentTestRule.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntil(30_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    return onNode(matcher)
}
