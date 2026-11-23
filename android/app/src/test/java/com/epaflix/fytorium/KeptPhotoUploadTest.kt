package com.epaflix.fytorium

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// W6-T14 (K9): a kept Identify photo uploads in the repository's own scope, with no page alive, and its file goes either way.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [com.epaflix.fytorium.photos.JvmImageSources::class])
class KeptPhotoUploadTest {
    private fun upload(accept: Boolean) = withCache { cache, api, server ->
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") posts += request.path!!
                return when (request.path) {
                    "/api/meta" -> MockResponse().setBody("""{"today":"2030-06-01","timezone":"Europe/Oslo"}""")
                    "/api/choices" -> MockResponse().setBody("""{"plant":{"choices":{},"labels":{},"numbers":[],"booleans":[],"health":["good"]},"nameLanguages":{}}""")
                    "/api/plants/plant-one/photos" -> if (request.method == "POST" && !accept) MockResponse().setResponseCode(500)
                        else MockResponse().setBody(if (request.method == "POST") com.epaflix.fytorium.photos.photoFixture else "[]")
                    "/api/plants" -> MockResponse().setBody("[$plantFixture]")
                    else -> MockResponse().setBody("[]")
                }
            }
        }
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val kept = java.io.File(java.io.File(app.noBackupFilesDir, "photo-drafts").apply { mkdirs() }, "synthetic.png")
        android.graphics.Bitmap.createBitmap(3000, 1000, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            kept.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        val repository = Repository(api, cache, { true })
        runBlocking { repository.uploadKeptPhoto(app, api, { true }, parsePlant(JSONObject(plantFixture)), kept.path).join() }
        assertEquals(listOf("/api/plants/plant-one/photos"), posts)
        assertFalse(kept.exists())
        assertEquals(if (accept) null else "Added Synthetic fern to Synthetic site. Its photo didn't upload.", repository.state.value.receipt?.text)
    }

    @Test fun aKeptPhotoUploadsWithoutItsPageAndItsFileGoes() = upload(accept = true)
    @Test fun aFailedKeptPhotoSaysSoAndItsFileGoes() = upload(accept = false)
}
