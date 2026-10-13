package com.epaflix.fytorium.photos

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNativeImageDecoder
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

// Robolectric's native decoder cannot decode file descriptors on Linux. Map them without a heap copy.
@Implements(ImageDecoder::class, minSdk = 28, callNativeMethodsByDefault = true)
class JvmImageSources : ShadowNativeImageDecoder() {
    companion object {
        @JvmStatic @Implementation
        fun createSource(file: File): ImageDecoder.Source = FileInputStream(file).use { mappedSource(it) }
        @JvmStatic @Implementation
        fun createSource(resolver: ContentResolver, uri: Uri): ImageDecoder.Source =
            (requireNotNull(resolver.openInputStream(uri)) as FileInputStream).use { mappedSource(it) }
        private fun mappedSource(input: FileInputStream): ImageDecoder.Source =
            ImageDecoder.createSource(input.channel.map(FileChannel.MapMode.READ_ONLY, 0, input.channel.size()))
    }
}
