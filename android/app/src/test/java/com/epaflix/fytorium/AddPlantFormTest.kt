package com.epaflix.fytorium

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.epaflix.fytorium.plants.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

// W4-P10-P16, P24: the add plant form.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2000dp-mdpi")
class AddPlantFormTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.parse("2030-06-10")
    private val site = PlantSite("site-one", "Synthetic site")

    private fun form(sites: List<PlantSite> = listOf(site), plant: Plant? = null, blocked: String? = null, onCreateSite: ((String) -> Unit)? = {},
        explained: MutableList<String> = mutableListOf(), keepPhoto: (@Composable () -> Unit)? = null, onSave: (JSONObject) -> Unit) {
        compose.setContent { FytoriumTheme { PlantForm(plant, syntheticOptions(), sites, true, onSave = onSave, today = today,
            species = if (plant == null) {{ ChosenSpecies(null, 910001, "Synthetic lantern", "Testoria lucida") {} }} else null,
            nameStandIn = "Synthetic lantern".takeIf { plant == null }, onCreateSite = onCreateSite.takeIf { plant == null },
            blocked = blocked, onBlocked = { explained += it }, keepPhoto = keepPhoto) } }
    }

    @Test fun detailsStartNotSetAndSaveAsNull() {
        var saved: JSONObject? = null
        form { saved = it }
        compose.onNodeWithText("Synthetic lantern").assertExists()
        compose.onNodeWithText("Change").assertExists()
        compose.onNode(hasText("Site") and hasText("Synthetic site")).assertExists()
        compose.onNodeWithText("More details").performClick()
        for (label in listOf("Pot type", "Soil type", "Plant size", "Growth stage", "Fertilizer type", "Drainage"))
            compose.onNode(hasText(label) and hasText("Not set")).performScrollTo().assertExists()
        compose.onNodeWithText("Save plant").performScrollTo().performClick()
        compose.runOnIdle {
            for (key in listOf("pot_type", "soil_type", "plant_size", "growth_stage", "fertilizer_type", "drainage")) assertTrue(key, saved!!.isNull(key))
            assertEquals("site-one", saved!!.getString("site_id"))
            assertFalse(saved!!.has("last_watered_on"))
        }
    }

    // An edit of one field keeps the unset details unset.
    @Test fun editingKeepsUnsetDetailsUnset() {
        val attributes = JSONObject(plantFixture).apply { for (key in listOf("pot_type", "soil_type", "plant_size", "growth_stage", "fertilizer_type", "drainage")) put(key, JSONObject.NULL) }
        var saved: JSONObject? = null
        form(plant = parsePlant(attributes)) { saved = it }
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("Synthetic renamed")
        compose.onNodeWithText("Save plant").performScrollTo().performClick()
        compose.runOnIdle { for (key in listOf("pot_type", "soil_type", "plant_size", "growth_stage", "fertilizer_type", "drainage")) assertTrue(key, saved!!.isNull(key)) }
    }

    // Choosing a last watering counts as an entry, so Back asks first.
    @Test fun aLastWateringMakesTheFormChanged() {
        var changed: () -> Boolean = { false }
        compose.setContent { FytoriumTheme { PlantForm(null, syntheticOptions(), listOf(site), true, onSave = {}, today = today, onDirty = { changed = it }) } }
        compose.runOnIdle { assertFalse(changed()) }
        compose.onNodeWithText("Yesterday").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(changed()) }
    }

    @Test fun saveWithoutASiteSaysWhyAndSendsNothing() {
        var saves = 0
        form(sites = listOf(site, PlantSite("site-two", "Synthetic balcony"))) { saves++ }
        compose.onNodeWithText("Save plant").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Choose a site in your household.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun noSitesOffersToCreateOne() {
        var creating = 0
        form(sites = emptyList(), onCreateSite = { creating++ }) {}
        compose.onNodeWithText("Plants live in a site, such as Kitchen or Balcony.").assertIsDisplayed()
        compose.onNodeWithText("Create a site").performClick()
        compose.runOnIdle { assertEquals(1, creating) }
    }

    // Sol W5b-sol-4: with sites already there, a new one can still be made from the form.
    @Test fun withSitesTheFormStillOffersToCreateOne() {
        var creating = 0
        form(onCreateSite = { creating++ }) {}
        compose.onNodeWithText("Create a site").performScrollTo().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        compose.runOnIdle { assertEquals(1, creating) }
    }

    @Test fun lastWateredSendsTheChosenDay() {
        var saved: JSONObject? = null
        form { saved = it }
        compose.onNodeWithText("Don't know").assertIsSelected()
        compose.onNodeWithText("Yesterday").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Save plant").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("2030-06-09", saved!!.getString("last_watered_on")) }
        compose.onNodeWithText("About a week ago").performScrollTo().performClick()
        compose.onNodeWithText("Save plant").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("2030-06-03", saved!!.getString("last_watered_on")) }
    }

    @Test fun blockedSaveLooksOffAndSaysWhy() {
        val explained = mutableListOf<String>()
        var saves = 0
        form(blocked = "Adding a plant needs a connection.", explained = explained) { saves++ }
        compose.onNodeWithText("Save plant").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Adding a plant needs a connection."), explained); assertEquals(0, saves) }
    }

    @Test fun keepThisPhotoIsTickedByDefault() {
        val keep = mutableStateOf(true)
        form(keepPhoto = { KeepPhoto("/nonexistent/synthetic.jpg", keep.value, true) { keep.value = it } }) {}
        compose.onNode(hasText("Keep this photo") and isToggleable()).assertIsOn().performClick()
        compose.runOnIdle { assertFalse(keep.value) }
        compose.onNodeWithText("It becomes the plant's first photo.").assertExists()
    }
}
