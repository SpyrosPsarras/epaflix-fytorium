package com.epaflix.fytorium.photos

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

@Composable fun PhotoSourceButtons(enabled: Boolean, take: () -> Unit, choose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = take, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Take photo") }
        OutlinedButton(onClick = choose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose photos") }
    }
}

internal data class PhotoSourceActions(val take: () -> Unit, val choose: () -> Unit)

@Composable internal fun rememberPhotoSources(multiple: Boolean, selected: List<String>, onPhotos: (List<Uri>) -> Unit, onError: (String) -> Unit): PhotoSourceActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var captures by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val currentPhotos by rememberUpdatedState(onPhotos)
    val currentError by rememberUpdatedState(onError)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pending
        pending = null
        if (path != null) {
            val file = File(path)
            if (success) currentPhotos(listOf(FileProvider.getUriForFile(context, context.packageName + ".photos", file)))
            else { file.delete(); captures = captures - path }
        }
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) currentPhotos(listOf(uri))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) currentPhotos(uris)
    }
    LaunchedEffect(selected, pending) {
        captures = captures.filter { path ->
            val file = File(path)
            val keep = path == pending || FileProvider.getUriForFile(context, context.packageName + ".photos", file).toString() in selected
            if (!keep) file.delete()
            keep
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Rotation while the camera is open must not remove its output file.
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) {
                captures.forEach { File(it).delete() }; pending?.let { File(it).delete() }
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
            }.onFailure { pending?.let { File(it).delete() }; pending = null; currentError("Could not open the camera. Try again.") }
        }
    }, choose = {
        runCatching {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (multiple) picker.launch(request) else singlePicker.launch(request)
        }
            .onFailure { currentError("Could not open the photo picker. Try again.") }
    })
}
