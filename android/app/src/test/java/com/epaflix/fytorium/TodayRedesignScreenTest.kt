package com.epaflix.fytorium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class TodayRedesignScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun allStatesLight() = screenshots(false)
    @Test fun allStatesDark() = screenshots(true)
    private fun screenshots(dark: Boolean) {
        compose.enableAccessibilityChecks()
        val plants = listOf("Fern", "Monstera", "Calathea", "Pothos", "Aloe", "Peace lily", "Rubber plant", "Palm").mapIndexed { index, name ->
            parsePlant(org.json.JSONObject(plantFixture)).copy(id = "a15-$index", displayName = name, siteName = "Living room", coverPhotoId = "a15-photo-$index")
        }
        val disk = ThumbnailCache(File(RuntimeEnvironment.getApplication().cacheDir, "a15-snapshot-${if (dark) "dark" else "light"}").apply { deleteRecursively() })
        plants.forEachIndexed { index, plant -> disk.put(plant.coverPhotoId!!, syntheticPhoto(index)) }
        val thumbnails = Thumbnails(ApiClient("https://example.invalid", MemoryCredentials()) {}, disk) { false }
        val template = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        fun rows(type: String, count: Int) = plants.take(count).mapIndexed { index, plant -> template.copy(taskType = type, plantId = plant.id, plantName = plant.displayName,
            siteName = plant.siteName!!, coverPhotoId = plant.coverPhotoId, lateDays = if (index == 0) 25 else if (index == 1) 3 else 0, dueOn = if (index < 2) "2030-05-08" else "2030-06-02") }
        val water = rows("water", 8)
        val base = TaskList("2030-06-02", "today", TaskSettings(), mapOf("water" to water), listOf(TaskSection("water", "Water", water), TaskSection("mist", "Mist", emptyList())))
        val upcoming = base.copy(view = "upcoming", sections = listOf(TaskSection("2030-06-04", "2030-06-04", rows("fertilize", 3).map { it.copy(dueOn = "2030-06-04", lateDays = 0) })))
        val full = CareState(member = Member("owner", Role.owner), today = base, upcoming = upcoming, plants = plants, timezone = "Europe/Oslo", lastUpdated = Instant.parse("2030-06-02T10:42:00Z").toEpochMilli())
        val state = mutableStateOf(full)
        val view = mutableStateOf("today")
        var adds = 0
        var imports = 0
        compose.setContent { FytoriumTheme(dark) { Scaffold(bottomBar = { NavigationBar {
            listOf("Today", "Plants", "Journal", "More").filter { state.value.member?.role != Role.sitter || it != "Journal" }.forEach { label ->
                NavigationBarItem(selected = label == "Today", onClick = {}, icon = { Icon(when (label) { "Today" -> Icons.Default.Check; "Plants" -> Icons.Default.Eco; "Journal" -> Icons.Default.Book; else -> Icons.Default.Menu }, contentDescription = null) }, label = { Text(label) })
            }
        } }) { padding -> Box(Modifier.padding(padding)) { TaskScreen(state.value, view.value, onView = { view.value = it },
            thumbnails = thumbnails, now = Instant.parse("2030-06-02T18:00:00Z"), onAddPlant = { adds++ }, onImport = { imports++ }, deviceOnline = !state.value.offline) } } } }
        fun awaitPhotos() {
            val list = if (view.value == "upcoming") state.value.upcoming else state.value.today
            val covers = list?.sections.orEmpty().flatMap { it.tasks }.filter { it.coverPhotoId != null }
            compose.waitUntil(5_000) {
                if (covers.isEmpty()) compose.onAllNodes(hasContentDescription("Photo of", substring = true)).fetchSemanticsNodes().isEmpty()
                else covers.none { task -> runCatching {
                    compose.onNode(hasContentDescription("No cover photo for ${task.plantName}") and hasAnyAncestor(hasTestTag("task:${task.plantId}:${task.key}"))).assertIsDisplayed()
                    true
                }.getOrDefault(false) }
            }
        }
        fun capture(name: String) {
            awaitPhotos()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            compose.onRoot().captureRoboImage("src/test/screenshots/a15-$name-${if (dark) "dark" else "light"}.png")
        }
        compose.onNodeWithText("Good evening").assertIsDisplayed()
        compose.onNodeWithText("Today's tasks: 8 watering").assertIsDisplayed()
        compose.onNodeWithText("Mist").assertDoesNotExist()
        compose.onNodeWithText("25d late").assertIsDisplayed()
        awaitPhotos()
        compose.onNodeWithContentDescription("Photo of Fern").assertIsDisplayed()
        capture("many-photos-late")
        val mixed = base.copy(sections = listOf(TaskSection("water", "Water", rows("water", 1)), TaskSection("fertilize", "Fertilize", rows("fertilize", 1))))
        compose.runOnIdle { state.value = full.copy(today = mixed) }
        capture("mixed-groups")
        compose.runOnIdle { view.value = "upcoming" }
        capture("upcoming")
        compose.runOnIdle { view.value = "today" }
        compose.runOnIdle { state.value = full.copy(today = base.copy(sections = emptyList(), groups = emptyMap())) }
        compose.onNodeWithText("Nothing to do today", substring = false).assertExists()
        compose.onNodeWithText("Next: Fertilize Fern · Tuesday, 4 June").assertIsDisplayed()
        capture("empty-day")
        compose.runOnIdle { state.value = state.value.copy(plants = emptyList(), upcoming = null) }
        compose.onNodeWithText("Add your first plant").assertIsDisplayed()
        capture("no-plants")
        compose.onNodeWithText("Add plant").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Import from Planta").performClick()
        assertEquals(1, adds); assertEquals(1, imports)
        compose.runOnIdle { state.value = full.copy(today = mixed, offline = true) }
        compose.onNodeWithText(state.value.offlineBanner()).assertExists()
        compose.onAllNodesWithContentDescription("Done").assertAll(isNotEnabled())
        compose.onNodeWithContentDescription("Add plant").assertIsNotEnabled()
        capture("offline")
        compose.runOnIdle { state.value = full.copy(member = Member("sitter", Role.sitter), today = mixed.copy(sections = mixed.sections.map { group -> group.copy(tasks = group.tasks.map { it.copy(siteName = "", basedOn = emptyList(), coverPhotoId = null) }) })) }
        compose.onNodeWithContentDescription("Task view settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add plant").assertDoesNotExist()
        compose.onNodeWithText("Living room").assertDoesNotExist()
        capture("sitter")
        compose.runOnIdle { state.value = full.copy(today = mixed, busy = true) }
        compose.onNodeWithContentDescription("Add plant").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("My notifications").assertIsEnabled()
        assertEquals(2, adds)
        disk.wipe()
    }
    private fun syntheticPhoto(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(android.graphics.Color.rgb(214 + seed * 3, 209, 185))
        paint.color = android.graphics.Color.rgb(86, 116 + seed * 7, 68)
        paint.strokeWidth = 6f
        canvas.drawLine(120f, 210f, 120f, 35f, paint)
        for (leaf in 0..4) {
            val y = 30f + leaf * 30f
            canvas.save(); canvas.rotate(if (leaf % 2 == 0) -35f else 35f, 120f, y + 25f)
            canvas.drawOval(RectF(if (leaf % 2 == 0) 48f else 118f, y, if (leaf % 2 == 0) 122f else 192f, y + 40f), paint)
            canvas.restore()
        }
        paint.color = android.graphics.Color.rgb(158, 86, 54)
        canvas.drawRoundRect(RectF(80f, 180f, 160f, 240f), 12f, 12f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
