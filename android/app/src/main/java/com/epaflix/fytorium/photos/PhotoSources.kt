package com.epaflix.fytorium.photos

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.epaflix.fytorium.FytoriumApplication
import kotlinx.coroutines.launch
import java.io.File

internal data class PhotoSourceActions(val take: () -> Unit, val choose: () -> Unit)

@Composable internal fun rememberPhotoSources(multiple: Boolean, selected: List<String>, onPhotos: (List<Uri>) -> Unit, onError: (String) -> Unit): PhotoSourceActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var captures by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var picked by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val currentPhotos by rememberUpdatedState(onPhotos)
    val currentError by rememberUpdatedState(onError)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pending
        pending = null
        if (path != null) {
            val file = File(path)
            if (success) runCatching {
                File(path + ".draft").createNewFile()
                currentPhotos(listOf(FileProvider.getUriForFile(context, context.packageName + ".photos", file)))
            }.onFailure { deleteCameraCapture(file); captures = captures - path; currentError("Could not keep this photo. Try again.") }
            else { deleteCameraCapture(file); captures = captures - path }
        }
    }
    fun releasePicked(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun choosePhotos(uris: List<Uri>) {
        runCatching {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    picked = (picked + uri.toString()).distinct()
                } catch (_: SecurityException) {
                    // shortcut: a temporary gallery grant is lost on process death, so a restored draft cannot upload it; copy the input into draft storage when such providers matter.
                }
            }
            currentPhotos(uris)
        }.onFailure { currentError("Could not keep this photo. Choose it again or take another photo.") }
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) choosePhotos(listOf(uri))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) choosePhotos(uris)
    }
    LaunchedEffect(selected, pending, captures, picked) {
        captures = captures.filter { path ->
            val file = File(path)
            val keep = path == pending || FileProvider.getUriForFile(context, context.packageName + ".photos", file).toString() in selected
            if (!keep) deleteCameraCapture(file)
            keep
        }
        picked = picked.filter { uri ->
            val keep = uri in selected
            if (!keep) releasePicked(Uri.parse(uri))
            keep
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Rotation while the camera is open must not remove its output file.
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) {
                captures.forEach { deleteCameraCapture(File(it)) }; pending?.let { deleteCameraCapture(File(it)) }
                picked.forEach { releasePicked(Uri.parse(it)) }
            }
        }
    }
    return PhotoSourceActions(take = {
        scope.launch {
            // Startup cleanup must finish before a new capture can be created.
            (context.applicationContext as FytoriumApplication).capturesCleared.join()
            runCatching {
                val directory = File(context.cacheDir, "captures").apply { mkdirs() }
                val file = File.createTempFile("camera-", ".jpg", directory)
                captures = captures + file.path; pending = file.path
                camera.launch(FileProvider.getUriForFile(context, context.packageName + ".photos", file))
            }.onFailure { pending?.let { deleteCameraCapture(File(it)) }; pending = null; currentError("Could not open the camera. Try again.") }
        }
    }, choose = {
        runCatching {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (multiple) picker.launch(request) else singlePicker.launch(request)
        }
            .onFailure { currentError("Could not open the photo picker. Try again.") }
    })
}
