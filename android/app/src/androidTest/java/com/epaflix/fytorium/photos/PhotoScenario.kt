package com.epaflix.fytorium.photos

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.epaflix.fytorium.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

fun runPhotoScenario(compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>, app: FytoriumApplication) {
    fun json(path: String, method: String = "GET", body: JSONObject? = null, query: Map<String, String> = emptyMap()): String =
        app.api.request(path, method = method, body = body, query = query).use {
            val text = it.body?.string().orEmpty(); assertTrue("API $path: ${it.code}: $text", it.isSuccessful); text
        }
    fun rows(path: String, query: Map<String, String> = emptyMap()) = JSONArray(json(path, query = query)).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun scroll(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val node = compose.onNode(matcher)
        if (runCatching { node.assertIsDisplayed() }.isFailure) {
            if (runCatching { node.performScrollTo() }.isFailure)
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
        }
        return compose.onNode(matcher)
    }
    fun click(text: String) {
        try { compose.waitUntil(60_000) { runCatching { scroll(hasText(text) and hasClickAction()).assertIsEnabled(); true }.getOrDefault(false) } }
        catch (error: Throwable) { throw AssertionError("Waiting for $text: ${compose.onRoot().printToString()}", error) }
        scroll(hasText(text) and hasClickAction()).performClick()
    }
    fun cleanupPlants() {
        for (status in listOf("active", "graveyard")) rows("/api/plants", mapOf("status" to status)).filter {
            it.optString("nickname") in listOf("Synthetic A06 sprout", "Synthetic A06 identified sprout")
        }.forEach { json("/api/plants/${it.getString("id")}", "DELETE", JSONObject().put("confirm_name", it.getString("display_name"))) }
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(app.cacheDir, "captures/synthetic-a06.png").apply { parentFile!!.mkdirs() }
    val bitmap = Bitmap.createBitmap(3200, 1200, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(90, 160, 100))
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    val uri = FileProvider.getUriForFile(app, app.packageName + ".photos", file)
    fun chooseInjectedPhoto(label: String = "Choose photos") {
        val filter = IntentFilter().apply {
            addAction("android.provider.action.PICK_IMAGES")
            addAction("androidx.activity.result.contract.action.PICK_IMAGES")
            addAction(Intent.ACTION_GET_CONTENT)
            addDataType("image/*")
        }
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val monitor = instrumentation.addMonitor(filter, result, true)
        try {
            click(label)
            compose.waitUntil(30_000) { monitor.hits == 1 }
            compose.waitForIdle()
            assertEquals("Picker intent was intercepted exactly once", 1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    fun takeInjectedPhoto() {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return null
                @Suppress("DEPRECATION")
                val output = intent.getParcelableExtra<android.net.Uri>(android.provider.MediaStore.EXTRA_OUTPUT)!!
                assertEquals("content", output.scheme); assertEquals(app.packageName + ".photos", output.authority)
                val image = android.graphics.BitmapFactory.decodeFile(file.path)
                app.contentResolver.openOutputStream(output)!!.use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            click("Take photo")
            compose.waitUntil(30_000) { monitor.hits == 1 }
            compose.waitUntil(30_000) { runCatching { scroll(hasContentDescription("Remove photo 2 of 2")).assertIsDisplayed(); true }.getOrDefault(false) }
            compose.onNodeWithContentDescription("Remove photo 2 of 2").performClick()
            // Restore the camera photo to exercise a two-photo identification upload.
            click("Take photo")
            compose.waitUntil(30_000) { monitor.hits == 2 }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    var siteId: String? = null
    var failure: Throwable? = null
    try {
        cleanupPlants()
        rows("/api/sites").filter { it.getString("name") == "Synthetic A06 site" }.forEach { json("/api/sites/${it.getString("id")}", "DELETE") }
        siteId = JSONObject(json("/api/sites", "POST", JSONObject("""{"name":"Synthetic A06 site","location":"indoor","light":"part_shade","humidity":"normal","grow_light":false,"draft":false}"""))).getString("id")
        val search = JSONObject(json("/api/species/search", query = mapOf("q" to "Testoria lucida")))
        val plantBody = JSONObject("""{"nickname":"Synthetic A06 sprout","pot_type":"plastic","soil_type":"all_purpose","plant_size":"small","growth_stage":"young","fertilizer_type":"liquid","pot_size_cm":null,"window_distance_cm":null,"drainage":true,"grow_light":false,"near_ac":false,"near_heater":false}""")
            .put("site_id", siteId).put("search_id", search.getString("search_id")).put("gbif_key", 910001)
        val plantId = JSONObject(json("/api/plants", "POST", plantBody)).getString("id")
        app.repository.refreshPlants()
        compose.selectTab("Plants")
        click("Synthetic A06 sprout")
        click("See all")
        compose.onNodeWithContentDescription("Add a photo of Synthetic A06 sprout").performClick()
        chooseInjectedPhoto("Choose photo")
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithContentDescription("Chosen photo for Synthetic A06 sprout").assertExists(); true }.getOrDefault(false) }
        scroll(hasText("Note")).performTextReplacement("Synthetic A06 photo note")
        click("Photo health")
        compose.onNodeWithText("good").performClick()
        click("Add photo update")
        // The first photo becomes the cover without a tap.
        compose.waitUntil(30_000) { runCatching { scroll(hasText("Cover photo")).assertIsDisplayed(); true }.getOrDefault(false) }
        val photo = rows("/api/plants/$plantId/photos").single()
        assertTrue(photo.getBoolean("is_cover")); assertEquals("good", photo.getString("health")); assertEquals("Synthetic A06 photo note", photo.getString("note"))
        val photoId = photo.getString("id")
        app.api.request("/api/photos/$photoId/original").use { response ->
            assertTrue(response.isSuccessful)
            val uploaded = response.body!!.bytes()
            val image = android.graphics.BitmapFactory.decodeByteArray(uploaded, 0, uploaded.size)
            assertEquals(2560, image.width); assertEquals(960, image.height); image.recycle()
            assertFalse(uploaded.toString(Charsets.ISO_8859_1).contains("Exif"))
        }
        assertEquals(photoId, JSONObject(json("/api/plants/$plantId")).getString("cover_photo_id"))
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithContentDescription("Photo of Synthetic A06 sprout", substring = true).assertExists(); true }.getOrDefault(false)
        }
        scroll(hasContentDescription("Photo of Synthetic A06 sprout", substring = true)).assertIsDisplayed()
        compose.selectTab("Journal")
        compose.waitUntil(30_000) { runCatching { scroll(hasText("Synthetic A06 photo note")).assertIsDisplayed(); true }.getOrDefault(false) }
        compose.selectTab("Plants")
        compose.onNodeWithContentDescription("Add plant").performClick()
        click("Identify from photos")
        chooseInjectedPhoto()
        takeInjectedPhoto()
        click("Identify")
        compose.waitUntil(30_000) { runCatching { scroll(hasContentDescription("Testoria picta", substring = true)).performClick(); true }.getOrDefault(false) }
        compose.onNodeWithText("Save plant").assertDoesNotExist()
        click("Yes, this one")
        // The form opens once Identify's first photo is copied for Keep this photo.
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Name") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        scroll(hasText("Name") and hasSetTextAction()).performTextReplacement("Synthetic A06 identified sprout")
        compose.onNode(hasText("Site") and hasText("Synthetic A06 site")).assertIsDisplayed()
        click("Save plant")
        compose.waitUntil(30_000) { app.repository.state.value.detail?.plant?.displayName == "Synthetic A06 identified sprout" }
        val identified = rows("/api/plants").single { it.optString("nickname") == "Synthetic A06 identified sprout" }
        assertEquals(913001, identified.getInt("gbif_key"))
        // The kept photo becomes the cover in the plant list behind the Plants grid and Journal's picker, without a pull or another page.
        compose.waitForState("Identified plant's cover in the plant list", 30_000) {
            app.repository.state.value.plants.any { it.displayName == "Synthetic A06 identified sprout" && it.coverPhotoId != null }
        }
        println("A06 E2E PASS: picker-injected photo update, 2560px JPEG, cover, journal, fake Pl@ntNet ranked result, explicit confirmation, add from result")
    } catch (error: Throwable) { failure = error; throw error }
    finally {
        val cleanup = runCatching { cleanupPlants(); siteId?.let { json("/api/sites/$it", "DELETE") }; file.delete() }
        if (failure == null) cleanup.getOrThrow()
    }
    compose.selectTab("Today")
}
