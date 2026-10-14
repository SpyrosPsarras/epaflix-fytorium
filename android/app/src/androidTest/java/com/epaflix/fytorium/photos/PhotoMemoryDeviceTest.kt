package com.epaflix.fytorium.photos

import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epaflix.fytorium.FytoriumApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PhotoMemoryDeviceTest {
    @Test fun largeContentUriDecodesAtUploadAndDisplaySizes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FytoriumApplication
        runBlocking { app.capturesCleared.join() }
        val file = File(app.cacheDir, "captures/synthetic-large.bmp").apply { parentFile!!.mkdirs() }
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
            println("A06 large photo PASS: 144 MB content URI, upload 2560x1920, preview 360x270")
        } finally { file.delete() }
    }
}
