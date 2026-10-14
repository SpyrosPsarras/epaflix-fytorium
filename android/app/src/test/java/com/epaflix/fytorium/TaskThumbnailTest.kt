package com.epaflix.fytorium

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp-mdpi")
class TaskThumbnailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstAndThirdPhotosAppearWhileSecondIsStillLoading() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val disk = ThumbnailCache(File(RuntimeEnvironment.getApplication().cacheDir, "progressive-thumbnails").apply { deleteRecursively() })
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/photos/photo-2/thumb") {
                        started.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                    }
                    return MockResponse().setBody(Buffer().write(bytes))
                }
            }
            server.start()
            val thumbnails = Thumbnails(ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials()) {}, disk) { true }
            val template = parseTaskList(taskListFixture("today"))
            val tasks = (1..3).map { index -> template.sections.single().tasks.single().copy(
                plantId = "plant-$index", plantName = "Plant $index", coverPhotoId = "photo-$index", lateDays = 0) }
            val today = template.copy(sections = listOf(TaskSection("water", "Water", tasks)), groups = mapOf("water" to tasks))
            val plants = tasks.map { parsePlant(org.json.JSONObject(plantFixture)).copy(id = it.plantId) }
            val state = CareState(member = Member("owner", Role.owner), plants = plants, today = today, upcoming = today.copy(view = "upcoming"))
            try {
                compose.setContent { FytoriumTheme { TaskScreen(state, "today", thumbnails = thumbnails, now = Instant.parse("2030-06-02T18:00:00Z")) } }
                assertTrue("Second thumbnail request reached latch", started.await(5, TimeUnit.SECONDS))
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("Photo of Plant 1").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithContentDescription("Photo of Plant 3").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Photo of Plant 1").assertIsDisplayed()
                compose.onNodeWithContentDescription("Photo of Plant 3").assertIsDisplayed()
                compose.onNodeWithContentDescription("No cover photo for Plant 2").assertIsDisplayed()
                compose.onNodeWithContentDescription("Photo of Plant 2").assertDoesNotExist()
                compose.onNodeWithTag("thumbnail:photo-2", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(ThumbnailLoading, true))
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
                assertEquals("Today and Upcoming share each photo request", 3, server.requestCount)
                release.countDown()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo of Plant 2").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Photo of Plant 2").assertIsDisplayed()
                compose.onAllNodes(SemanticsMatcher.expectValue(ThumbnailLoading, true), useUnmergedTree = true).assertCountEquals(0)
            } finally {
                release.countDown()
            }
        }
        disk.wipe()
    }
}
