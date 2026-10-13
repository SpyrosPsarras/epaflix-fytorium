package com.epaflix.fytorium.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

internal fun clearCameraCaptures(
    cacheDir: File,
    startup: Boolean = false,
    now: Long = System.currentTimeMillis(),
    delete: (File) -> Boolean = { it.delete() },
) {
    fun reportFailure() { runCatching { android.util.Log.w("Fytorium", "CAPTURE_CLEANUP_FAILED") } }
    runCatching {
        File(cacheDir, "captures").listFiles()?.forEach { capture ->
            capture.walkBottomUp().forEach { file ->
                runCatching {
                    // Keep recent files and their parent directories after process recreation.
                    if (startup && (if (file.isDirectory) file.listFiles()?.isNotEmpty() != false
                        else file.lastModified() > now - 10 * 60 * 1000L)) return@runCatching
                    if (!delete(file)) reportFailure()
                }.onFailure { reportFailure() }
            }
        }
    }.onFailure { reportFailure() }
}

internal class PhotoInputException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

// ImageDecoder subsamples the URI directly and applies EXIF orientation; fresh JPEGs carry no source metadata.
fun preparePhoto(context: Context, uri: Uri): ByteArray = try {
    val source = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path)))
        else ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        require(!info.isAnimated)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 2560) decoder.setTargetSize(maxOf(1, (info.size.width.toLong() * 2560 / longest).toInt()), maxOf(1, (info.size.height.toLong() * 2560 / longest).toInt()))
    }
    try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }.toByteArray() }
    finally { bitmap.recycle() }
} catch (error: OutOfMemoryError) { throw PhotoInputException("This photo is too large to process. Choose a smaller photo.", error) }
catch (error: ImageDecoder.DecodeException) { throw PhotoInputException("Choose a decodable still image.", error) }
catch (error: IOException) { throw PhotoInputException("Could not read this photo. Choose it again or take another photo.", error) }
catch (error: Exception) { throw PhotoInputException("Choose a decodable still image.", error) }

internal fun decodePhotoPreview(bytes: ByteArray, width: Int, height: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1.0, width.toDouble() / info.size.width, height.toDouble() / info.size.height)
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
    }
} catch (_: IOException) { null } catch (_: OutOfMemoryError) { null }

// Bound downloaded originals; camera inputs are decoded directly from their URI.
internal fun readPhotoBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count == -1) break
        output.write(buffer, 0, count)
    }
    require(output.size() <= limit)
    return output.toByteArray()
}

internal class UploadBody(private val body: RequestBody, private val progress: (Float) -> Unit) : RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun writeTo(sink: BufferedSink) {
        var sent = 0L
        progress(0f)
        val counting = object : ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount); sent += byteCount
                progress((sent.toFloat() / contentLength()).coerceIn(0f, 1f))
            }
        }.buffer()
        body.writeTo(counting); counting.flush()
    }
}
