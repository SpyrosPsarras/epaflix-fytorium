package com.epaflix.fytorium

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun AuthenticatedThumbnail(api: ApiClient?, path: String?, description: String, placeholder: String, size: Dp = 120.dp) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, api, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (api == null || path == null) null else runCatching {
                api.request(path).use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.byteStream()?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 1_048_576) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: return@use null
                    if (bytes.size > 1_048_576) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), description, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    else Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) { Text(placeholder) }
}

private data class PhotoCredit(val creator: String, val license: String, val source: String)
private data class ReferenceInfo(val credit: PhotoCredit?, val commonName: String?)
@Composable fun ReferencePhoto(api: ApiClient?, key: Long, name: String, showName: Boolean = false, displayPhoto: Boolean = true, size: Dp = 120.dp) {
    val path = "/api/species/$key/photo"
    val info by produceState<ReferenceInfo?>(null, api, key) {
        value = null
        value = withContext(Dispatchers.IO) {
            if (api == null) null else runCatching {
                api.request("$path/credit").use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = JSONObject(response.body?.string().orEmpty())
                    val photo = body.optJSONObject("photo")
                    ReferenceInfo(photo?.let { PhotoCredit(it.getString("creator"), it.getString("license"), it.getString("source_url")) }, body.nullable("common_name"))
                }
            }.getOrNull()
        }
    }
    val uriHandler = LocalUriHandler.current
    Column(if (displayPhoto) Modifier.width(size) else Modifier) {
        if (displayPhoto) AuthenticatedThumbnail(api, if (info?.credit != null) path else null, "Reference photo of $name", "No reference photo", size)
        if (showName) {
            Text(info?.commonName ?: name, style = if (displayPhoto) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold, minLines = 2)
            Text(if (info?.commonName != null) name else "", style = MaterialTheme.typography.bodySmall,
                minLines = 1)
        }
        if (displayPhoto) {
            val creditHeight = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() * 3 + 16.dp }
            Box(Modifier.fillMaxWidth().heightIn(min = creditHeight.coerceAtLeast(48.dp))) {
                info?.credit?.let { attribution ->
                    TextButton(onClick = { uriHandler.openUri(attribution.source) }, modifier = Modifier.fillMaxWidth().heightIn(min = creditHeight.coerceAtLeast(48.dp))) {
                        Text("${attribution.license} · Photo: ${attribution.creator}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
