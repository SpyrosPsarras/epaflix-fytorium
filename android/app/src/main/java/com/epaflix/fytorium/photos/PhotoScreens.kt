package com.epaflix.fytorium.photos

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.Thumbnails
import com.epaflix.fytorium.plants.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhotoBar(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Heading(title) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    })
}
@Composable private fun PhotoNotice(state: PhotoState, online: Boolean, onRefresh: () -> Unit, refreshLabel: String = "Refresh photos") {
    if (!online) Text("Actions are unavailable offline. Reconnect and refresh to make changes.")
    if (state.updateRequired) Text("Update Fytorium. This server needs a newer app.")
    PlantError(state.error)
    if (state.saving) {
        if (state.progress != null) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (!state.uploading) "Saving changes…" else if (state.progress == 1f) "Upload sent. Waiting for the server." else "Uploading…")
    } else if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    OutlinedButton(onClick = onRefresh, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(refreshLabel) }
}
@Composable private fun PhotoBitmap(bytes: ByteArray?, description: String) {
    if (bytes == null) { Text("No cached photo yet."); return }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = with(density) { 400.dp.roundToPx() }
        val preview by produceState<Pair<Boolean, Bitmap?>>(false to null, bytes, width, height) {
            var decoded: Bitmap? = null
            try {
                value = false to null
                withContext(Dispatchers.Default) { decoded = decodePhotoPreview(bytes, width, height) }
                value = true to decoded
                awaitDispose {}
            } finally { decoded?.recycle() }
        }
        val bitmap = preview.second
        if (bitmap != null) Image(bitmap.asImageBitmap(), description, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp))
        else Text(if (!preview.first) "Loading photo…" else "Could not display this photo.")
    }
}
@Composable private fun PhotoThumbnail(photo: PhotoUpdate, thumbnails: Thumbnails?) {
    val bytes by produceState<ByteArray?>(null, photo.id, thumbnails) { value = withContext(Dispatchers.IO) { thumbnails?.load(photo.id) } }
    PhotoBitmap(bytes, "Photo update of ${photo.plantName}")
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TakenDate(value: String, today: String?, enabled: Boolean, onChange: (String) -> Unit) {
    var showing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showing = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Taken on: $value") }
    if (showing) {
        val initial = remember(value) { runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial, selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = today != null && Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() <= today
        })
        DatePickerDialog(onDismissRequest = { showing = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; showing = false }, enabled = picker.selectedDateMillis != null) { Text("Set date") }
        }, dismissButton = { TextButton(onClick = { showing = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
}

@Composable fun PhotoScreen(state: PhotoState, online: Boolean, plantName: String?, selectedCount: Int,
    sourceControls: @Composable () -> Unit = {}, thumbnails: Thumbnails? = null, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onUpload: (String, String, String) -> Unit = { _, _, _ -> }, onChange: (String, String) -> Unit = { _, _ -> },
    onOriginal: (String) -> Unit = {}, onCloseOriginal: () -> Unit = {}, onPlant: (String) -> Unit = {}) {
    var form by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var health by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<PhotoUpdate?>(null) }
    val enabled = online && !state.blocked && !state.saving && !state.updateRequired
    fun back() { if (state.original != null) onCloseOriginal() else if (form) form = false else onBack() }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize()) {
        PhotoBar(if (form) "Add a photo update" else if (state.original != null) "Photo" else plantName?.let { "$it updates" } ?: "Plant journal", ::back)
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).then(if (form || state.original != null) Modifier.verticalScroll(scroll) else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoNotice(state, online, onRefresh)
            when {
                state.original != null -> PhotoBitmap(state.original, "Original plant photo")
                form -> {
                    sourceControls()
                    Text("Selected photos: $selectedCount")
                    Text("Up to 15 MB. Location and camera metadata are removed.")
                    OutlinedTextField(note, { note = it }, label = { Text("Note") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    Choice("Photo health", health, mapOf("" to "Not set") + state.healthChoices.associateWith { it.replace('_', ' ') }, enabled) { health = it }
                    TakenDate(date.ifBlank { state.today.orEmpty() }, state.today, enabled) { date = it }
                    Button(onClick = { onUpload(note, health, date.ifBlank { state.today.orEmpty() }) }, enabled = enabled && selectedCount > 0 && state.today != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add photo update") }
                }
                else -> {
                    if (plantName != null) Button(onClick = { form = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add a photo update") }
                    else Text("All photo updates, newest taken date first.")
                    if (state.loaded && state.photos.isEmpty()) Text("No photo updates yet.")
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.photos, key = { it.id }) { photo -> Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (plantName == null) TextButton(onClick = { onPlant(photo.plantId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(photo.plantName) }
                            else Heading(photo.plantName)
                            PhotoThumbnail(photo, thumbnails)
                            OutlinedButton(onClick = { onOriginal(photo.id) }, enabled = online && !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open original photo of ${photo.plantName}") }
                            Text(photo.takenOn + if (photo.cover) " · Cover photo" else "")
                            photo.health?.let { Text("Health: ${it.replace('_', ' ')}") }
                            if (photo.note.isNotEmpty()) Text(photo.note)
                            if (!photo.cover) OutlinedButton(onClick = { onChange(photo.id, "cover") }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use as cover") }
                            OutlinedButton(onClick = { deleting = photo }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete photo update") }
                        }
                    } }
                    }
                }
            }
        }
    }
    if (deleting != null) AlertDialog(onDismissRequest = { if (!state.saving) deleting = null }, title = { Text("Delete photo update?") },
        text = { Column { Text("Delete this photo update and its photos?"); PlantError(state.error) } },
        confirmButton = { TextButton(onClick = { onChange(deleting!!.id, "delete") }, enabled = enabled) { Text("Confirm delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.saving) { Text("Cancel") } })
    LaunchedEffect(state.photos) { if (deleting != null && state.photos.none { it.id == deleting!!.id }) deleting = null }
    // A successful upload closes the form. A failed upload keeps every entered field.
    var uploaded by remember { mutableIntStateOf(state.uploadRevision) }
    LaunchedEffect(state.uploadRevision) {
        if (uploaded != state.uploadRevision) { form = false; note = ""; health = ""; date = "" }
        uploaded = state.uploadRevision
    }
}

@Composable fun IdentificationScreen(state: PhotoState, online: Boolean, selectedCount: Int,
    sourceControls: @Composable () -> Unit = {}, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onIdentify: () -> Unit = {}, onRemove: (Int) -> Unit = {}, onSelect: (SpeciesCandidate, String, Boolean) -> Unit = { _, _, _ -> }) {
    var chosen by remember(state.result) { mutableStateOf<SpeciesCandidate?>(null) }
    val enabled = online && !state.saving && !state.blocked && !state.updateRequired
    BackHandler { if (chosen != null) chosen = null else onBack() }
    Column(Modifier.fillMaxSize()) {
        PhotoBar("Identify from photos") { if (chosen != null) chosen = null else onBack() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoNotice(state, online, onRefresh, "Refresh quota")
            state.remaining?.takeIf { it <= 50 }?.let { Text(if (it == 0) "Pl@ntNet identification quota is exhausted. Try again tomorrow or search by name." else "$it identifications remaining today.") }
            if (chosen != null) {
                val candidate = chosen!!
                Heading("Confirm species")
                Text("Pl@ntNet suggested ${candidate.identificationName ?: candidate.scientificName}, ${Math.round((candidate.score ?: 0.0) * 100)}% identification score.")
                Text("This match needs your confirmation. Is ${candidate.scientificName} the species you meant?")
                Button(onClick = { onSelect(candidate, state.result!!.id, true) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirm ${candidate.canonicalName}") }
                TextButton(onClick = { chosen = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to candidates") }
            } else {
                sourceControls()
                for (index in 0 until selectedCount) OutlinedButton(onClick = { onRemove(index) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove photo ${index + 1}") }
                Text("Take or upload one to five photos of the same plant. Photos are shrunk before upload, with a 15 MB per-photo and 40 MB total upload limit. Metadata is removed before sending to Pl@ntNet. These photos are not saved.")
                Button(onClick = onIdentify, enabled = enabled && selectedCount in 1..5, modifier = Modifier.heightIn(min = 48.dp)) { Text("Identify plant") }
                state.result?.let { result ->
                    Heading("Choose a species")
                    result.candidates.forEachIndexed { index, candidate ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Heading("${index + 1}. ${candidate.identificationName ?: candidate.scientificName}")
                            Text("${Math.round((candidate.score ?: 0.0) * 100)}% identification score")
                            if (candidate.commonNames.isNotEmpty()) Text(candidate.commonNames.joinToString(", "))
                            if (candidate.matchType != null) Text("GBIF accepted name: ${candidate.scientificName} · ${candidate.matchType.lowercase()} · ${candidate.confidence}% confidence · ${candidate.rank.lowercase()}")
                            Button(onClick = { chosen = candidate }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose ${candidate.canonicalName}") }
                        } }
                    }
                }
            }
            Text("Names from GBIF, CC BY 4.0.")
        }
    }
}
