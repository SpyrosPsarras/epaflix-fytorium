package com.epaflix.fytorium

import android.content.res.Configuration
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

fun runTodayScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val names = listOf("Fern", "Monstera", "Calathea", "Pothos", "Aloe", "Peace lily", "Rubber plant", "Palm").map { "A15 $it" }
    val siteName = "A15 living room"
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()) =
        app.api.request(path, method = method, body = body, query = query).use { response ->
            val text = response.body?.string().orEmpty()
            assertTrue("A15 $path: ${response.code}: $text", response.isSuccessful)
            text
        }
    fun rows(path: String) = JSONArray(json(path)).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun clock(at: String) {
        val connection = URL("http://app:3000/__test/clock").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use { it.write(JSONObject().put("action", "set").put("at", at).toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
        } finally { connection.disconnect() }
    }
    fun cleanup() {
        rows("/api/plants").filter { it.optString("nickname") in names }.forEach { plant ->
            json("/api/plants/${plant.getString("id")}", "DELETE", JSONObject().put("confirm_name", plant.getString("display_name")))
        }
        rows("/api/sites").filter { it.getString("name") == siteName }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
    }
    fun refresh() {
        app.repository.refresh()
        app.feed.refresh(app.repository.state.value.member)
        compose.waitForState("A15 refreshed from the local stack", 30_000) { !app.repository.state.value.busy && !app.repository.state.value.serverUnavailable }
    }
    val output = File(app.getExternalFilesDir(null), "a15-emulator").apply { mkdirs() }
    fun capture(name: String, dark: Boolean) {
        device.executeShellCommand("cmd uimode night ${if (dark) "yes" else "no"}")
        compose.waitForState("A15 ${if (dark) "dark" else "light"} theme", 30_000) {
            val night = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            night == if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        compose.waitForState("A15 care settled before $name capture", 30_000) { !app.repository.state.value.busy && !app.repository.state.value.saving }
        compose.waitForIdle(); device.waitForIdle()
        val bars = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        assertEquals(!dark, bars.isAppearanceLightStatusBars)
        assertEquals(!dark, bars.isAppearanceLightNavigationBars)
        if (name == "today-scrolled") {
            val last = app.repository.state.value.today!!.sections.last { it.tasks.isNotEmpty() }.tasks.last()
            compose.onNodeWithTag("task-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            val row = compose.onNodeWithTag("task:${last.plantId}:${last.key}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val fab = compose.onNodeWithContentDescription("Add plant").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("task-list").fetchSemanticsNode().boundsInRoot
            assertTrue("Last row must clear the FAB", row.bottom < fab.top)
            assertTrue("Viewport must extend behind the FAB", viewport.bottom > fab.bottom)
        } else {
            val list = compose.onNodeWithTag("task-list")
            val offset = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -offset) }
            assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
            compose.onNode(hasText(if (name == "upcoming") "Upcoming" else "Today") and !hasClickAction()).assertIsDisplayed()
        }
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        fun loadingThumbnails() = compose.onAllNodes(SemanticsMatcher.expectValue(ThumbnailLoading, true), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { !it.boundsInRoot.isEmpty }
        fun settled() = !app.repository.state.value.busy && !app.repository.state.value.saving &&
            compose.onAllNodes(progress).fetchSemanticsNodes().isEmpty() && loadingThumbnails().isEmpty()
        compose.waitForState("A15 spinner-free and visible thumbnails settled before $name capture", 30_000, details = {
            "progressNodes=${compose.onAllNodes(progress).fetchSemanticsNodes().map { "${it.boundsInRoot}/${it.config[SemanticsProperties.ProgressBarRangeInfo]}" }}, " +
                "loadingThumbnails=${loadingThumbnails().map { it.config[SemanticsProperties.TestTag] }}"
        }) {
            if (!settled()) false else {
                compose.waitForIdle(); device.waitForIdle()
                Thread.sleep(500) // Let SurfaceFlinger draw; a theme recreation may start another care refresh.
                settled()
            }
        }
        val captured = File(output, "$name-${if (dark) "dark" else "light"}.png")
        compose.onAllNodes(progress).assertCountEquals(0)
        assertTrue(device.takeScreenshot(captured))
        // Shared MediaStore pictures survive Gradle uninstall and are readable by adb.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, captured.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/a15-emulator")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        app.contentResolver.openOutputStream(uri)!!.use { target -> captured.inputStream().use { it.copyTo(target) } }
        assertEquals(1, app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
    }
    val previousTasks = JSONObject(json("/api/tasks/settings"))
    val previousFeed = JSONObject(json("/api/feed/settings"))
    try {
        cleanup()
        clock("2030-06-01T10:00:00Z")
        json("/api/tasks/settings", "PUT", TaskSettings().json())
        json("/api/feed/settings", "PUT", JSONObject().put("late_tasks", false).put("current_weather", false).put("weather_alerts", false).put("plant_warnings", false))
        val site = JSONObject(json("/api/sites", "POST", JSONObject().put("name", siteName).put("location", "indoor").put("light", "part_sun").put("humidity", "normal").put("grow_light", false).put("draft", false)))
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
        val ids = names.mapIndexed { index, name ->
            val plant = JSONObject("""{"pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
                .put("nickname", name).put("site_id", site.getString("id")).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
            val id = JSONObject(json("/api/plants", "POST", plant)).getString("id")
            val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(android.graphics.Color.rgb(214 + index * 3, 209, 185))
            paint.color = android.graphics.Color.rgb(86, 116 + index * 7, 68); paint.strokeWidth = 7f
            canvas.drawLine(160f, 280f, 160f, 40f, paint)
            for (leaf in 0..4) {
                val y = 40f + leaf * 40f
                canvas.save(); canvas.rotate(if (leaf % 2 == 0) -35f else 35f, 160f, y + 32f)
                canvas.drawOval(RectF(if (leaf % 2 == 0) 64f else 158f, y, if (leaf % 2 == 0) 163f else 256f, y + 54f), paint); canvas.restore()
            }
            paint.color = android.graphics.Color.rgb(158, 86, 54)
            canvas.drawRoundRect(RectF(105f, 240f, 215f, 320f), 16f, 16f, paint)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray(); bitmap.recycle()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("photo", "synthetic-a15-$index.jpg", bytes.toRequestBody("image/jpeg".toMediaType())).build()
            val photo = app.api.request("/api/plants/$id/photos", method = "POST", requestBody = body).use { response ->
                assertEquals(201, response.code); JSONObject(response.body!!.string())
            }
            json("/api/photos/${photo.getString("id")}/cover", "PUT")
            id
        }
        clock("2030-06-26T18:00:00Z")
        refresh()
        compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).performClick()
        compose.onNodeWithTag("view-today").performClick()
        val first = app.repository.state.value.today!!.sections.flatMap { it.tasks }.first { it.plantId in ids && it.taskType == "water" }
        assertEquals(25, first.lateDays); assertNotNull(first.coverPhotoId)
        compose.waitForNode(hasContentDescription("Photo of ${first.plantName}") and hasAnyAncestor(hasTestTag("task:${first.plantId}:water"))).assertIsDisplayed()
        capture("today", false); capture("today", true)
        capture("today-scrolled", false); capture("today-scrolled", true)
        fun openTask(task: CareTask): Float {
            val row = hasAnyAncestor(hasTestTag("task:${task.plantId}:${task.key}"))
            compose.onNode(row and hasContentDescription("Open ${task.plantName}")).performScrollTo()
            val position = compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.onNode(row and hasContentDescription("Open ${task.plantName}")).performClick()
            val block = "today-task:${task.plantId}:${task.key}"
            compose.waitForNode(hasTestTag(block)).assertIsDisplayed()
            compose.onNode(hasText("Plants") and hasClickAction()).assertIsSelected()
            compose.onNode(hasText(task.label) and hasAnyAncestor(hasTestTag(block))).assertIsDisplayed()
            return position
        }
        fun backToTasks(view: String, position: Float) {
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).assertIsSelected()
            compose.onNodeWithTag("view-$view").assertIsSelected()
            assertEquals(position, compose.onNodeWithTag("task-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        }
        val tapped = app.repository.state.value.today!!.sections.flatMap { it.tasks }.last { it.plantId in ids && it.taskType == "water" }
        val todayPosition = openTask(tapped)
        val block = hasAnyAncestor(hasTestTag("today-task:${tapped.plantId}:${tapped.key}"))
        compose.onNode(block and hasText("Based on")).performScrollTo().performClick()
        compose.onNode(block and hasText("Skip")).performScrollTo().assertIsEnabled().performClick()
        compose.waitForNode(block and hasText("Skipped")).assertIsDisplayed()
        compose.onNode(block and hasText("Skip")).assertDoesNotExist()
        compose.waitForState("Tapped task care action settled") { !app.repository.state.value.saving }
        compose.onNode(block and hasText("Undo")).performScrollTo().assertIsEnabled().performClick()
        compose.waitForNode(block and hasText("Skip")).assertIsDisplayed()
        val skipped = rows("/api/plants/${tapped.plantId}/history").filter { it.optString("task_type") == "water" && it.optString("kind") == "skipped" }
        assertTrue(skipped.any { !it.isNull("undone_at") })
        backToTasks("today", todayPosition)
        compose.onNode(hasText("Plants") and hasClickAction()).performClick()
        compose.onNodeWithText("Search plants").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNode(hasText("Today") and hasClickAction() and !hasTestTag("view-today")).performClick()
        compose.onNodeWithTag("view-upcoming").performScrollTo().performClick()
        compose.waitForNode(hasText("Next 14 calendar days, including today")).assertIsDisplayed()
        capture("upcoming", false); capture("upcoming", true)
        val future = app.repository.state.value.upcoming!!.sections.flatMap { it.tasks }.last { it.plantId in ids && it.dueOn!! > "2030-06-26" }
        val upcomingPosition = openTask(future)
        compose.onNode(hasText("Snooze 2 days") and hasAnyAncestor(hasTestTag("today-task:${future.plantId}:${future.key}"))).assertDoesNotExist()
        backToTasks("upcoming", upcomingPosition)
        for (id in ids) json("/api/plants/$id/tasks/complete-all", "POST", JSONObject().put("request_id", UUID.randomUUID().toString()))
        refresh()
        compose.onNodeWithTag("view-today").performScrollTo().performClick().assertIsSelected()
        assertTrue(app.repository.state.value.today!!.sections.all { it.tasks.isEmpty() })
        compose.waitForNode(hasText("Nothing to do today")).assertIsDisplayed()
        compose.onNode(hasText("Next:", substring = true)).assertIsDisplayed()
        capture("empty-day", false); capture("empty-day", true)
        println("A15 E2E PASS: synthetic covers and light/dark captures, Today task Skip/Undo, native plant page, Today/Upcoming Back view and scroll, no stale Plants route")
    } finally {
        device.executeShellCommand("cmd uimode night no")
        cleanup()
        json("/api/tasks/settings", "PUT", previousTasks)
        json("/api/feed/settings", "PUT", previousFeed)
        clock("2030-06-01T10:00:00Z")
        refresh()
    }
}
