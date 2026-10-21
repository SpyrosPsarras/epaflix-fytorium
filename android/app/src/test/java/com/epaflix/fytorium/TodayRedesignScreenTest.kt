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
import androidx.compose.ui.unit.sp
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
    @Test fun selectedViewHasACentredTitleWithoutGreetingTipOrSummary() {
        val list = parseTaskList(taskListFixture("today"))
        val view = mutableStateOf("today")
        compose.setContent { FytoriumTheme { TaskScreen(CareState(member = Member("owner", Role.owner), today = list,
            upcoming = list.copy(view = "upcoming"), plants = listOf(parsePlant(org.json.JSONObject(plantFixture)))),
            view.value, onView = { view.value = it }) } }
        fun assertTitle(label: String) {
            val title = compose.onNode(hasText(label) and !hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertEquals(root.center.x, title.center.x, 1f)
        }
        assertTitle("Today")
        compose.onNodeWithText("Good morning").assertDoesNotExist()
        compose.onNode(hasText("Today's tasks:", substring = true)).assertDoesNotExist()
        for (tip in listOf("Check the soil before watering.", "Empty standing water from saucers.", "Turn pots for more even light.",
            "Check leaf undersides for pests.", "Use clean tools when pruning.")) compose.onNodeWithText(tip).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search plants").assertIsDisplayed()
        compose.onNodeWithContentDescription("My notifications").assertIsDisplayed()
        compose.onNodeWithTag("view-today").assertIsSelected()
        compose.onNodeWithTag("view-upcoming").performClick().assertIsSelected()
        assertTitle("Upcoming")
    }
    @Test fun cardHandlingRequiresADuePendingTaskAndAnEditingRole() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val base = CareState(member = Member("owner", Role.owner), today = list, upcoming = list,
            plants = listOf(parsePlant(org.json.JSONObject(plantFixture))))
        val state = mutableStateOf(base)
        val view = mutableStateOf("today")
        val handledTypes = mutableListOf<String>()
        compose.setContent { FytoriumTheme { TaskScreen(state.value, view.value, onHandleType = { handledTypes.add(it) }) } }
        val button = compose.onNodeWithContentDescription("Handle all Water tasks")
        button.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("water"), handledTypes) }
        compose.onNodeWithText("Handle all tasks").assertExists()
        for (role in Role.entries) {
            compose.runOnIdle { state.value = base.copy(member = Member("member", role)) }
            if (role == Role.sitter) button.assertDoesNotExist() else button.assertIsEnabled()
        }
        compose.runOnIdle { state.value = base; view.value = "upcoming" }
        button.assertDoesNotExist()
        compose.runOnIdle { view.value = "today" }
        val event = parseEvent(org.json.JSONObject(eventFixture))
        for (rows in listOf(listOf(task.copy(completedEvent = event)), listOf(task.copy(dueOn = "2030-06-03")),
            listOf(task.copy(dueOn = null)), listOf(task.copy(status = "not_applicable")))) {
            compose.runOnIdle { state.value = base.copy(today = list.copy(sections = listOf(TaskSection("water", "Water", rows)))) }
            button.assertDoesNotExist()
        }
        compose.runOnIdle { state.value = base.copy(handled = mapOf("plant-one:water" to event)) }
        button.assertDoesNotExist()
        for (blocked in listOf(base.copy(offline = true), base.copy(serverUnavailable = true), base.copy(saving = true), base.copy(updateRequired = true))) {
            compose.runOnIdle { state.value = blocked }
            button.assertExists().assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = base.copy(busy = true) }
        button.assertIsEnabled()
        val custom = task.copy(taskType = null, customTaskId = "91000000-0000-4000-8000-000000000001", customTaskName = "Rotate")
        compose.runOnIdle { state.value = base.copy(today = list.copy(sections = listOf(
            TaskSection("water", "Water", listOf(task.copy(completedEvent = event), task.copy(plantId = "plant-two", dueOn = "2030-06-02"))),
            TaskSection(custom.key, "Rotate", listOf(custom))))) }
        button.assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Handle all Rotate tasks").performClick()
        compose.runOnIdle { assertEquals(listOf("water", "water", "91000000-0000-4000-8000-000000000001"), handledTypes) }
    }
    @Test fun lateLabelBoundaries() {
        for ((days, label) in listOf(1 to "1d late", 59 to "59d late", 60 to "2mo late", 89 to "2mo late", 90 to "3mo late")) {
            assertEquals(label, lateLabel(days))
        }
    }
    @Test fun photoAndNameOpenThePlantWithoutCompletingOrSnoozing() {
        val task = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        var opened = 0
        val actions = mutableListOf<String>()
        compose.setContent { FytoriumTheme { TaskRow(task, CareState(member = Member("owner", Role.owner)), "2030-06-02", null,
            onAction = { actions.add(it) }, onOpen = { opened++ }) } }
        val open = compose.onNodeWithContentDescription("Open Synthetic fern").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertEquals("Open Synthetic fern", open.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label)
        compose.onNodeWithContentDescription("No cover photo for Synthetic fern", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("Synthetic fern", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, opened); assertTrue(actions.isEmpty()) }
        compose.onNodeWithContentDescription("Snooze 2 days").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Mark Water for Synthetic fern done").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(2, opened); assertEquals(listOf("snooze", "complete"), actions) }
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Based on").assertDoesNotExist()
    }
    @Test fun todayAndUpcomingForwardTheTappedTaskWithoutActing() {
        val list = parseTaskList(taskListFixture("today"))
        val task = list.sections.single().tasks.single()
        val view = mutableStateOf("today")
        val opened = mutableListOf<CareTask>()
        val actions = mutableListOf<String>()
        compose.setContent { FytoriumTheme { TaskScreen(CareState(member = Member("owner", Role.owner), today = list,
            upcoming = list.copy(view = "upcoming"), plants = listOf(parsePlant(org.json.JSONObject(plantFixture)))), view.value,
            onOpenTask = { opened.add(it) }, onAction = { _, action -> actions.add(action) }) } }
        for (selected in listOf("today", "upcoming")) {
            compose.runOnIdle { view.value = selected }
            compose.onNodeWithContentDescription("Open Synthetic fern").performClick()
        }
        compose.runOnIdle { assertEquals(listOf(task, task), opened); assertTrue(actions.isEmpty()) }
    }
    @Test fun lateLabelsUseDaysThenWholeMonths() {
        val template = parseTaskList(taskListFixture("today"))
        val days = mutableIntStateOf(1)
        compose.setContent { FytoriumTheme { TaskScreen(CareState(member = Member("owner", Role.owner), plants = listOf(parsePlant(org.json.JSONObject(plantFixture))),
            today = template.copy(sections = template.sections.map { section -> section.copy(tasks = section.tasks.map { it.copy(lateDays = days.intValue) }) })), "today") } }
        for ((lateDays, label) in listOf(1 to "1d late", 59 to "59d late", 60 to "2mo late", 89 to "2mo late", 90 to "3mo late")) {
            compose.runOnIdle { days.intValue = lateDays }
            compose.onNodeWithText(label).assertExists()
        }
    }
    @Test @Config(qualifiers = "w320dp-h800dp-mdpi") fun latePillStaysOnOneCentredLineAtNarrowWidth() {
        val template = parseTaskList(taskListFixture("today"))
        val list = template.copy(sections = template.sections.map { section -> section.copy(tasks = section.tasks.map { it.copy(lateDays = 46) }) })
        compose.setContent { FytoriumTheme { TaskScreen(CareState(member = Member("owner", Role.owner), today = list,
            plants = listOf(parsePlant(org.json.JSONObject(plantFixture)))), "today") } }
        val pill = compose.onNodeWithText("46d late", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        pill.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layouts.single().layoutInput.style.textAlign)
        compose.onRoot().captureRoboImage("src/test/screenshots/today-row-narrow.png")
    }
    @Test fun dayAndMonthPillsKeepPhotosAndNamesAligned() {
        val task = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
        val days = mutableIntStateOf(46)
        compose.setContent { FytoriumTheme { TaskRow(task.copy(lateDays = days.intValue), CareState(member = Member("owner", Role.owner)),
            "2030-06-02", null, onAction = {}) } }
        val nameLeft = compose.onNodeWithText(task.plantName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        for (lateDays in listOf(46, 89)) {
            compose.runOnIdle { days.intValue = lateDays }
            compose.onNodeWithTag("thumbnail:${task.coverPhotoId ?: task.plantId}", useUnmergedTree = true).assertWidthIsEqualTo(56.dp)
            assertEquals(nameLeft, compose.onNodeWithText(task.plantName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 0.01f)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(if (lateDays == 46) "46d late" else "2mo late", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().hasVisualOverflow)
        }
    }
    @Test @Config(qualifiers = "w320dp-h800dp-mdpi") fun longPlantNamesUseTwoLargerLinesWithEllipsis() {
        val task = parseTaskList(taskListFixture("today")).sections.single().tasks.single()
            .copy(plantName = "Synthetic fern with a very long name that must not push the care controls off the row")
        compose.setContent { FytoriumTheme { TaskRow(task, CareState(member = Member("owner", Role.owner)), "2030-06-02", null, onAction = {}) } }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(task.plantName, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2, layout.lineCount)
        assertTrue(layout.isLineEllipsized(1))
        assertEquals(18.sp, layout.layoutInput.style.fontSize)
        compose.onNodeWithContentDescription("Mark Water for ${task.plantName} done").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
    }
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
            siteName = plant.siteName!!, coverPhotoId = plant.coverPhotoId, lateDays = if (index == 0) 46 else if (index == 1) 89 else 0, dueOn = if (index == 0) "2030-04-17" else if (index == 1) "2030-03-05" else "2030-06-02") }
        val water = rows("water", 8)
        val base = TaskList("2030-06-02", "today", TaskSettings(), mapOf("water" to water), listOf(TaskSection("water", "Water", water), TaskSection("mist", "Mist", emptyList())))
        val upcoming = base.copy(view = "upcoming", sections = listOf(TaskSection("2030-06-04", "2030-06-04", rows("fertilize", 3).map { it.copy(dueOn = "2030-06-04", lateDays = 0) })))
        val full = CareState(member = Member("owner", Role.owner), today = base, upcoming = upcoming, plants = plants, lastUpdated = Instant.parse("2030-06-02T10:42:00Z").toEpochMilli())
        val state = mutableStateOf(full)
        val view = mutableStateOf("today")
        var adds = 0
        var imports = 0
        compose.setContent { FytoriumTheme(dark) { Scaffold(bottomBar = { NavigationBar {
            listOf("Today", "Plants", "Journal", "More").filter { state.value.member?.role != Role.sitter || it != "Journal" }.forEach { label ->
                NavigationBarItem(selected = label == "Today", onClick = {}, icon = { Icon(when (label) { "Today" -> Icons.Default.Check; "Plants" -> Icons.Default.Eco; "Journal" -> Icons.Default.Book; else -> Icons.Default.Menu }, contentDescription = null) }, label = { Text(label) })
            }
        } }) { padding -> Box(Modifier.padding(padding)) { TaskScreen(state.value, view.value, onView = { view.value = it },
            thumbnails = thumbnails, onAddPlant = { adds++ }, onImport = { imports++ }, deviceOnline = !state.value.offline) } } } }
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
        compose.onNode(hasText("Today") and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Good evening").assertDoesNotExist()
        compose.onNodeWithText("Today's tasks: 8 watering").assertDoesNotExist()
        compose.onNodeWithText("Mist").assertDoesNotExist()
        compose.onNodeWithText("46d late").assertIsDisplayed()
        compose.onNodeWithText("2mo late").assertIsDisplayed()
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
        compose.onNodeWithTag("view-today").assertDoesNotExist()
        compose.onNodeWithTag("view-upcoming").assertDoesNotExist()
        compose.onNodeWithContentDescription("Task view settings").assertDoesNotExist()
        capture("no-plants")
        compose.onNodeWithText("Add plant").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Import from Planta").performClick()
        assertEquals(1, adds); assertEquals(1, imports)
        compose.runOnIdle { state.value = full.copy(today = mixed, offline = true) }
        compose.onNodeWithText(state.value.offlineBanner()).assertExists()
        compose.onAllNodes(hasContentDescription("Mark Water for Fern done") or hasContentDescription("Mark Fertilize for Fern done"))
            .assertCountEquals(2).assertAll(isNotEnabled())
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
