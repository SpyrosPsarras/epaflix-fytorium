package com.epaflix.fytorium

import android.content.ComponentName
import android.view.WindowManager
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class SpeciesSearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val field = hasText("Species name") and hasSetTextAction()
    private val first = SpeciesCandidate(910031, "Testoria nova Inventor", "Testoria nova", "SPECIES", "FUZZY", 92, true,
        "Synthetic starlight", "en", commonName = "Synthetic star")
    private val second = first.copy(key = 910032, scientificName = "Testoria nova Maker", matchType = "EXACT", requiresConfirmation = false)
    private val search = SpeciesSearch("synthetic-search", listOf(first, second))

    @Test fun addOpensWithItsSearchFieldFocused() {
        compose.setContent { FytoriumTheme { PlantsScreen(CareState(member = Member("member-one", Role.owner)), null, initialAdd = true) } }
        compose.onNode(field).assertIsFocused()
    }

    @Test fun searchFieldHasAnIconActionAndSupportingText() {
        compose.setContent { FytoriumTheme { SpeciesPicker(true, null, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("Species search").assertDoesNotExist()
        compose.onNodeWithText("Common or scientific name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search species").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText("Names from GBIF (CC BY 4.0) and Wikidata (CC0).").assertIsDisplayed()
    }

    @Test fun imeSearchSubmitsOnceAndLeavesTheQueryUnchanged() {
        val sent = mutableListOf<String>()
        compose.setContent { FytoriumTheme { SpeciesPicker(true, null, { sent += it }, { _, _, _ -> }) } }
        val input = compose.onNode(field)
        input.performTextInput("  nova  ")
        input.performImeAction()
        input.assertTextContains("  nova  ")
        compose.runOnIdle { assertEquals(listOf("nova"), sent) }
    }

    @Test fun hardwareEnterSubmitsOnceAndLeavesTheQueryUnchanged() {
        val sent = mutableListOf<String>()
        compose.setContent { FytoriumTheme { SpeciesPicker(true, null, { sent += it }, { _, _, _ -> }) } }
        val input = compose.onNode(field)
        input.performTextInput("nova")
        input.performKeyInput { pressKey(Key.Enter) }.assertTextContains("nova")
        compose.runOnIdle { assertEquals(listOf("nova"), sent) }
    }

    @Suppress("DEPRECATION")
    @Test fun mainWindowResizesRatherThanPansForTheKeyboard() {
        val app = RuntimeEnvironment.getApplication()
        val activity = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    @Test fun duplicateCardsReadTheirAuthorsAndTheExactCardIsOneTapTarget() {
        var selected: SpeciesCandidate? = null
        compose.setContent { FytoriumTheme { Surface { SpeciesPicker(true, search, {}, { candidate, _, _ -> selected = candidate }) } } }
        compose.onNodeWithText("Testoria nova Inventor").assertIsDisplayed()
        compose.onNodeWithText("Testoria nova Maker").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/native-plants-search-duplicate-names.png")
        compose.onNodeWithContentDescription("Synthetic star, Testoria nova Inventor. Choose this species.").assertHeightIsAtLeast(72.dp).assertHasClickAction()
        compose.onNodeWithContentDescription("Synthetic star, Testoria nova Maker. Choose this species.").assertHasClickAction().performClick()
        compose.onNodeWithText("Is this your plant?").assertDoesNotExist()
        compose.onAllNodes(hasText("No reference photo"), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(second, selected) }
    }

    // Offline the cards fade whole and a tap says why.
    @Test fun offlineResultCardsSayWhyOnTap() {
        val explained = mutableListOf<String>()
        var selected: SpeciesCandidate? = null
        compose.setContent { FytoriumTheme { Surface { SpeciesPicker(false, search, {}, { candidate, _, _ -> selected = candidate },
            blockedReason = "Choosing a species needs a connection.", onBlocked = { explained += it }) } } }
        compose.onNodeWithContentDescription("Synthetic star, Testoria nova Maker. Choose this species.")
            .assert(hasStateDescription("Choosing a species needs a connection.")).assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Choosing a species needs a connection."), explained); assertEquals(null, selected) }
    }

    @Test fun newAnswerStartsAtTheTopEvenWhenTheServerReturnsTheSameCards() {
        val revision = mutableIntStateOf(1)
        val candidates = (1L..12L).map { n -> first.copy(key = 910100 + n, commonName = "Synthetic result $n") }
        val result = SpeciesSearch("same-answer", candidates)
        compose.setContent { FytoriumTheme { SpeciesPicker(true, result, {}, { _, _, _ -> }, answerVersion = revision.intValue) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(8)
        compose.onNode(field).assertIsNotDisplayed()
        compose.runOnIdle { revision.intValue++ }
        compose.onNode(field).assertIsDisplayed()
        compose.onNodeWithText("Synthetic result 1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun confirmationFitsLandscapeAtNormalTextSize() = confirmationFits(1f)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun confirmationFitsLandscapeAtLargeTextSize() = confirmationFits(1.3f)

    private fun confirmationFits(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        var accepted = false
        compose.setContent { CheckLandscape(scale); FytoriumTheme { Surface {
            SpeciesPicker(true, search, {}, { _, _, confirmed -> accepted = confirmed })
        } } }
        val firstCard = compose.onNodeWithContentDescription("Synthetic star, Testoria nova Inventor. Choose this species.")
        firstCard.performScrollTo().performClick()
        val dialog = hasAnyAncestor(isDialog())
        val title = "Is this your plant?"
        compose.checkDialogTextScale(title, scale)
        compose.onNode(hasText("Back") and dialog).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Yes, this one") and dialog).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("dialog-more-below").assertIsDisplayed()
        compose.onNodeWithTag("dialog-more-above").assertDoesNotExist()
        compose.captureDialog("src/test/screenshots/native-plants-confirm-landscape-${if (scale == 1f) "normal" else "large"}.png")
        compose.onNode(hasText("Close match") and dialog, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dialog-more-above").assertIsDisplayed()
        compose.onNodeWithTag("dialog-more-below").assertDoesNotExist()
        compose.onNode(hasText("Back") and dialog).performClick()
        firstCard.assertIsDisplayed().performClick()
        compose.onNode(hasText("Yes, this one") and dialog).performClick()
        compose.runOnIdle { assertTrue(accepted) }
    }
}
