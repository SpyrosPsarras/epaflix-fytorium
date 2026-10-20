package com.epaflix.fytorium

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp-mdpi")
class TaskThumbnailTest {
    @get:Rule val compose = createComposeRule()

    private val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    private val requests = mutableListOf<String>()
    private val pending = (1..5).associate { "photo-$it" to CompletableDeferred<Bitmap?>() }
    private val state = mutableStateOf(careState())
    private val view = mutableStateOf("today")

    private fun careState(count: Int = 3): CareState {
        val template = parseTaskList(taskListFixture("today"))
        val tasks = (1..count).map { index -> template.sections.single().tasks.single().copy(
            plantId = "plant-$index", plantName = "Plant $index", coverPhotoId = "photo-$index", lateDays = 0) }
        val today = template.copy(sections = listOf(TaskSection("water", "Water", tasks)), groups = mapOf("water" to tasks))
        val plants = tasks.map { parsePlant(org.json.JSONObject(plantFixture)).copy(id = it.plantId) }
        return CareState(member = Member("owner", Role.owner), plants = plants, today = today, upcoming = today.copy(view = "upcoming"))
    }
    private fun show() {
        compose.setContent { FytoriumTheme {
            TaskScreen(state.value, view.value, thumbnailLoader = { id ->
                requests.add(id)
                // A duplicate request stays pending, so a refresh cannot hide a refetch behind an instant result.
                if (requests.count { it == id } > 1) CompletableDeferred<Bitmap?>().await() else pending.getValue(id).await()
            })
        } }
        compose.waitForIdle()
    }
    private fun finish(vararg ids: Int) {
        compose.runOnIdle { ids.forEach { pending.getValue("photo-$it").complete(bitmap) } }
        compose.waitForIdle()
    }
    private fun noSpinners() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }
    private fun loading(id: Int, expected: Boolean) {
        compose.onNodeWithTag("thumbnail:photo-$id", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(ThumbnailLoading, expected))
    }

    @Test fun firstAndThirdPhotosAppearWhileSecondIsStillLoading() {
        show()
        finish(1, 3)
        compose.onNodeWithContentDescription("Photo of Plant 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo of Plant 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("No cover photo for Plant 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo of Plant 2").assertDoesNotExist()
        loading(2, true)
        noSpinners()
        assertEquals("Today and Upcoming share each photo request", listOf("photo-1", "photo-2", "photo-3"), requests)
        finish(2)
        compose.onNodeWithContentDescription("Photo of Plant 2").assertIsDisplayed()
        loading(2, false)
    }

    @Test fun refreshAndViewSwitchKeepPhotosWithoutPlaceholderFramesOrDuplicateInFlightRequests() {
        show()
        finish(1, 3)
        compose.mainClock.autoAdvance = false
        for (screen in listOf("today", "upcoming", "today")) {
            compose.runOnIdle { state.value = state.value.copy(lastUpdated = (state.value.lastUpdated ?: 0) + 1); view.value = screen }
            repeat(5) {
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithContentDescription("Photo of Plant 1").assertIsDisplayed()
                compose.onNodeWithContentDescription("Photo of Plant 3").assertIsDisplayed()
                compose.onNodeWithContentDescription("No cover photo for Plant 1").assertDoesNotExist()
                compose.onNodeWithContentDescription("No cover photo for Plant 3").assertDoesNotExist()
                loading(2, true)
                assertEquals(listOf("photo-1", "photo-2", "photo-3"), requests)
            }
        }
        compose.mainClock.autoAdvance = true
        finish(2)
        compose.onNodeWithContentDescription("Photo of Plant 2").assertIsDisplayed()
        assertEquals(3, requests.size)
    }

    @Test fun failedLoadSettlesAsPlaceholderWithoutSpinnerAndCanRetryOnRefresh() {
        show()
        compose.runOnIdle { pending.getValue("photo-2").complete(null) }
        compose.waitForIdle()
        loading(2, false)
        compose.onNodeWithContentDescription("No cover photo for Plant 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo of Plant 2").assertDoesNotExist()
        noSpinners()
        compose.runOnIdle { state.value = state.value.copy(lastUpdated = 1) }
        compose.waitForIdle()
        loading(2, true)
        assertEquals(2, requests.count { it == "photo-2" })
        assertEquals(1, requests.count { it == "photo-1" })
        assertEquals(1, requests.count { it == "photo-3" })
    }

    @Test fun atMostFourLoadsRunAtOnce() {
        state.value = careState(5)
        show()
        assertEquals(4, requests.size)
        finish(1)
        assertEquals(5, requests.size)
        finish(2, 3, 4, 5)
        compose.onAllNodes(SemanticsMatcher.expectValue(ThumbnailLoading, true), useUnmergedTree = true).assertCountEquals(0)
    }
}
