package com.epaflix.fytorium.photos

import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.epaflix.fytorium.FytoriumApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [JvmImageSources::class])
class PhotoMemoryTest {
    @Test fun startupAndLogoutEraseOrphanedCapturesButLeaveOtherCacheFiles() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as FytoriumApplication
        app.capturesCleared.join()
        val directory = File(app.cacheDir, "captures").apply { mkdirs() }
        val unrelated = File(app.cacheDir, "synthetic-other-cache").apply { writeText("keep") }
        try {
            val old = System.currentTimeMillis() - 11 * 60 * 1000L
            File(directory, "orphan.jpg").apply { writeText("synthetic GPS capture"); setLastModified(old) }
            File(directory, "nested/orphan.jpg").apply { parentFile!!.mkdirs(); writeText("synthetic capture"); setLastModified(old) }
            val recent = File(directory, "pending/camera.jpg").apply { parentFile!!.mkdirs(); writeText("in-flight capture") }
            app.onCreate()
            app.capturesCleared.join()
            assertTrue(recent.exists())
            assertFalse(File(directory, "orphan.jpg").exists())
            assertFalse(File(directory, "nested").exists())
            assertTrue(unrelated.exists())
            File(directory, "logout.jpg").writeText("synthetic capture")
            withContext(Dispatchers.IO) { app.api.logout() }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(unrelated.exists())
        } finally { directory.deleteRecursively(); unrelated.delete() }
    }
    @Test fun failedCaptureDeletionDoesNotCrashStartupOrSkipLogoutWipe() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as FytoriumApplication
        app.capturesCleared.join()
        val directory = File(app.cacheDir, "captures").apply { mkdirs() }
        val blocked = File(directory, "undeletable.jpg").apply {
            writeText("synthetic capture"); setLastModified(System.currentTimeMillis() - 11 * 60 * 1000L)
        }
        try {
            for (throws in listOf(false, true)) {
                app.captureDeleter = { file ->
                    if (file == blocked) {
                        if (throws) throw SecurityException("must not be logged")
                        false
                    } else file.delete()
                }
                val deletable = File(directory, "deletable.jpg").apply {
                    writeText("synthetic capture"); setLastModified(blocked.lastModified())
                }
                app.onCreate()
                app.capturesCleared.join()
                assertFalse(app.capturesCleared.isCancelled)
                assertTrue(blocked.exists())
                assertFalse(deletable.exists())
                val thumbnail = File(app.cacheDir, "thumbnails/wipe-marker").apply {
                    parentFile!!.mkdirs(); writeText("synthetic thumbnail")
                }
                withContext(Dispatchers.IO) { app.api.logout() }
                assertTrue(blocked.exists())
                assertFalse("Repository logout wipe must clear thumbnails", thumbnail.exists())
            }
        } finally { app.captureDeleter = { it.delete() }; directory.deleteRecursively() }
    }
    @Test fun largeContentUriIsSubsampledWithoutBufferingTheInput() {
        val app = RuntimeEnvironment.getApplication() as FytoriumApplication
        runBlocking { app.capturesCleared.join() }
        val file = File(app.cacheDir, "captures/synthetic-large.bmp").apply { parentFile!!.mkdirs() }
        // Write a 144 MB, 48-megapixel BMP one scanline at a time, without allocating its bitmap.
        val width = 8000; val height = 6000
        val row = ByteArray(width * 3) { 90 }
        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte()); put('M'.code.toByte()); putInt(54 + row.size * height)
            putInt(0); putInt(54); putInt(40); putInt(width); putInt(height)
            putShort(1); putShort(24); putInt(0); putInt(row.size * height)
            putInt(0); putInt(0); putInt(0); putInt(0)
        }.array()
        try {
            file.outputStream().buffered().use { output -> output.write(header); repeat(height) { output.write(row) } }
            assertTrue(file.length() > 64 * 1024 * 1024)
            val uri = FileProvider.getUriForFile(app, app.packageName + ".photos", file)
            val bytes = preparePhoto(app, uri)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(2560, bitmap.width); assertEquals(1920, bitmap.height) }
            finally { bitmap.recycle() }
            val preview = requireNotNull(decodePhotoPreview(bytes, 360, 400))
            try { assertEquals(360, preview.width); assertEquals(270, preview.height) }
            finally { preview.recycle() }
        } finally { file.delete() }
    }
}
