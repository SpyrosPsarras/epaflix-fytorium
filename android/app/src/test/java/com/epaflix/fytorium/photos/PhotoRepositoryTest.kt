package com.epaflix.fytorium.photos

import com.epaflix.fytorium.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val photoFixture = """{"id":"photo-one","plant_id":"plant-one","plant_name":"Synthetic sprout","note":"Synthetic note","health":"good","taken_on":"2026-10-13","is_cover":false}"""
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [JvmImageSources::class])
class PhotoRepositoryTest {
    @Test fun uploadsResizeAndReencodeBeforeAuthenticatedMultipartWithProgress() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val file = java.io.File(context.cacheDir, "synthetic-wide.png")
            val bitmap = android.graphics.Bitmap.createBitmap(3000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            assertTrue(preparePhoto(context, android.net.Uri.fromFile(file)).isNotEmpty())
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true }, "plant-one")
            server.enqueue(MockResponse().setBody(photoFixture)); server.enqueue(MockResponse().setBody("[$photoFixture]"))
            assertTrue(repository.upload(context, android.net.Uri.fromFile(file), "Synthetic note", "good", "2026-10-13"))
            val request = server.takeRequest()
            assertEquals("/api/plants/plant-one/photos", request.path)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            val bytes = request.body.readByteArray()
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("name=\"note\"")); assertTrue(text.contains("\r\n\r\nSynthetic note\r\n"))
            assertTrue(text.contains("name=\"health\"")); assertTrue(text.contains("\r\n\r\ngood\r\n"))
            assertTrue(text.contains("name=\"taken_on\"")); assertTrue(text.contains("\r\n\r\n2026-10-13\r\n"))
            val start = bytes.indices.first { it + 2 < bytes.size && bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xd8.toByte() }
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, start, bytes.size - start)
            assertEquals(2560, decoded.width); assertEquals(853, decoded.height); decoded.recycle()
            assertFalse(text.contains("Exif"))
            assertEquals(1f, repository.state.value.progress)
            file.delete()
        }
    }
    @Test fun cameraExifRotationIsAppliedAndGpsIsStrippedFromTheFreshJpeg() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "synthetic-exif.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(3000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        val exif = android.media.ExifInterface(file.path)
        exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "12/1,30/1,0/1")
        exif.setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(android.media.ExifInterface.TAG_MAKE, "Synthetic camera")
        exif.saveAttributes()
        val encoded = preparePhoto(context, android.net.Uri.fromFile(file))
        val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertEquals(853, decoded.width); assertEquals(2560, decoded.height); decoded.recycle()
        val stripped = android.media.ExifInterface(encoded.inputStream())
        assertNull(stripped.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
        assertNull(stripped.getAttribute(android.media.ExifInterface.TAG_MAKE))
        assertFalse(encoded.toString(Charsets.ISO_8859_1).contains("Exif"))
        file.delete()
    }
    @Test fun invalidPhotosCanBeReplacedWithoutTreatingThemAsANetworkFailure() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true }, "plant-one")
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val file = java.io.File(context.cacheDir, "synthetic-invalid.png").apply { writeText("not an image") }
            assertFalse(repository.upload(context, android.net.Uri.fromFile(file), "", "", "2026-10-13"))
            assertFalse(repository.state.value.blocked)
            assertEquals("Choose a decodable still image.", repository.state.value.error)
            assertEquals(0, server.requestCount)
            file.delete()
        }
    }
    @Test fun unreadablePhotosShowAnInputErrorWithoutBlockingRetry() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true }, "plant-one")
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val file = java.io.File(context.cacheDir, "synthetic-missing.jpg").apply { delete() }
            assertFalse(repository.upload(context, android.net.Uri.fromFile(file), "", "", "2026-10-13"))
            assertFalse(repository.state.value.blocked)
            assertEquals("Could not read this photo. Choose it again or take another photo.", repository.state.value.error)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun identificationKeepsRankedScoresAndRequiresConfirmationAndNeverSendsSitterOrOfflineUploads() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            var allowed = true; var online = true
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { allowed }, { online })
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val file = java.io.File(context.cacheDir, "synthetic-identify.png")
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val uri = android.net.Uri.fromFile(file)
            server.enqueue(MockResponse().setBody("""{"search_id":"synthetic-search","remaining":20,"candidates":[{"gbif_key":900001,"scientific_name":"Testoria nebulosa","canonical_name":"Testoria nebulosa","rank":"SPECIES","match_type":null,"confidence":null,"requires_confirmation":true,"source":"identification","score":0.91,"identification_name":"Testoria nebulosa","common_names":["Synthetic cloud plant"]}]}"""))
            assertTrue(repository.identify(context, listOf(uri)))
            val request = server.takeRequest()
            assertEquals("/api/identification", request.path)
            assertTrue(request.body.readUtf8().contains("name=\"images\""))
            assertEquals(0.91, repository.state.value.result!!.candidates.single().score!!, 0.001)
            assertTrue(repository.state.value.result!!.candidates.single().requiresConfirmation)
            assertEquals(20, repository.state.value.remaining)
            assertFalse(repository.identify(context, emptyList()))
            assertFalse(repository.identify(context, List(6) { uri }))
            online = false; assertFalse(repository.identify(context, listOf(uri)))
            allowed = false; online = true; assertFalse(repository.identify(context, listOf(uri)))
            assertEquals(1, server.requestCount)
            assertNull(repository.state.value.result)
            file.delete()
        }
    }
    @Test fun unavailablePhotoServerBlocksOnlyThisRepositoryUntilRefresh() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true })
            server.enqueue(MockResponse().setBody("[$photoFixture]")); repository.refresh()
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Synthetic unavailable"}""")); repository.change("photo-one", "cover")
            assertTrue(repository.state.value.blocked)
            assertFalse(repository.change("photo-one", "delete")); assertEquals(2, server.requestCount)
            assertEquals("Synthetic unavailable", repository.state.value.error)
            assertEquals(1, repository.state.value.photos.size)
            server.enqueue(MockResponse().setBody("[$photoFixture]")); assertTrue(repository.refresh())
            assertFalse(repository.state.value.blocked)
            server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setBody("[]"))
            assertTrue(repository.change("photo-one", "delete")); assertTrue(repository.state.value.photos.isEmpty())
        }
    }
    @Test fun originalsAreFetchedAgainAndOnlyKeptInMemory() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true })
            for (attempt in 1..2) {
                server.enqueue(MockResponse().setBody("synthetic-image-$attempt"))
                assertTrue(repository.openOriginal("photo-one"))
                assertEquals("synthetic-image-$attempt", repository.state.value.original!!.toString(Charsets.UTF_8))
                assertEquals("/api/photos/photo-one/original", server.takeRequest().path)
                repository.clearOriginal(); assertNull(repository.state.value.original)
            }
        }
    }
    @Test fun journalAndCoverUseServerOrderAndMessages() {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
            val repository = PhotoRepository(ApiClient(server.url("").toString().trimEnd('/'), store) {}, { true }, { true })
            server.enqueue(MockResponse().setBody("[$photoFixture]"))
            repository.refresh()
            assertEquals("/api/journal", server.takeRequest().path)
            assertEquals("Synthetic note", repository.state.value.photos.single().note)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Photo update not found"}"""))
            assertFalse(repository.change("photo-one", "cover"))
            assertEquals("Photo update not found", repository.state.value.error)
            assertEquals("/api/photos/photo-one/cover", server.takeRequest().path)
            assertFalse(repository.state.value.blocked)
        }
    }
}
