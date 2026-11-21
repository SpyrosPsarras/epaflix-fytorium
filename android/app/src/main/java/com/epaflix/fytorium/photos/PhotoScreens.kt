package com.epaflix.fytorium.photos

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.BarTitle
import com.epaflix.fytorium.CareState
import com.epaflix.fytorium.DialogBody
import com.epaflix.fytorium.barHeight
import com.epaflix.fytorium.DialogTitle
import com.epaflix.fytorium.Thumbnails
import com.epaflix.fytorium.dayMonthYear
import com.epaflix.fytorium.plants.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// The card keeps its 4:3 space before the photo loads, so nothing below it moves.
@Composable private fun CardPhoto(photo: PhotoUpdate, thumbnails: Thumbnails?, modifier: Modifier = Modifier.fillMaxWidth(), onOpen: (() -> Unit)?) {
    val bytes by produceState<ByteArray?>(null, photo.id, thumbnails) { value = withContext(Dispatchers.IO) { thumbnails?.load(photo.id, "display") } }
    val label = "Photo of ${photo.plantName}, ${dayMonthYear(photo.takenOn)}"
    BoxWithConstraints(modifier.aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open photo") { onOpen() } else Modifier).semantics { contentDescription = label }) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val bitmap by produceState<Bitmap?>(null, bytes, width) {
            var decoded: Bitmap? = null
            try {
                bytes?.let { withContext(Dispatchers.Default) { decoded = decodePhotoPreview(it, width * 2, width * 2) } }
                value = decoded
                awaitDispose {}
            } finally { decoded?.recycle() }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
// Zoom stays between 1x and 5x, and the photo pans no further than its own edges.
internal fun clampZoom(scale: Float, x: Float, y: Float, fittedWidth: Float, fittedHeight: Float, width: Float, height: Float): Triple<Float, Float, Float> {
    val s = scale.coerceIn(1f, 5f)
    val maxX = maxOf(0f, (fittedWidth * s - width) / 2)
    val maxY = maxOf(0f, (fittedHeight * s - height) / 2)
    return Triple(s, x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}
// The display copy shows at once; the original replaces it at the same fitted size, so the photo never moves.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhotoViewer(photo: PhotoUpdate?, original: ByteArray?, online: Boolean, thumbnails: Thumbnails?, onBack: () -> Unit) {
    val display by produceState<ByteArray?>(null, photo?.id, thumbnails) { value = photo?.let { withContext(Dispatchers.IO) { thumbnails?.load(it.id, "display") } } }
    val hideTabs = com.epaflix.fytorium.LocalHideTabs.current
    DisposableEffect(hideTabs) { hideTabs.value = true; onDispose { hideTabs.value = false } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val width = with(density) { maxWidth.toPx() }
            val height = with(density) { maxHeight.toPx() }
            val bytes = original ?: display
            val bitmap by produceState<Bitmap?>(null, bytes) {
                var decoded: Bitmap? = null
                try {
                    bytes?.let { withContext(Dispatchers.Default) { decoded = decodePhotoPreview(it, (width * 2).toInt().coerceAtLeast(1), (height * 2).toInt().coerceAtLeast(1)) } }
                    value = decoded
                    awaitDispose {}
                } finally { decoded?.recycle() }
            }
            var scale by rememberSaveable { mutableFloatStateOf(1f) }
            var x by rememberSaveable { mutableFloatStateOf(0f) }
            var y by rememberSaveable { mutableFloatStateOf(0f) }
            val image = bitmap
            if (image != null) {
                val fit = minOf(width / image.width, height / image.height)
                fun clamp(next: Float, nx: Float, ny: Float) {
                    val (s, cx, cy) = clampZoom(next, nx, ny, image.width * fit, image.height * fit, width, height)
                    scale = s; x = cx; y = cy
                }
                Image(image.asImageBitmap(), photo?.let { "Photo of ${it.plantName}, ${dayMonthYear(it.takenOn)}" } ?: "Plant photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(image) { detectTapGestures(onDoubleTap = { tap ->
                            val next = if (scale > 1f) 1f else 2.5f
                            clamp(next, (tap.x - width / 2) * (1 - next), (tap.y - height / 2) * (1 - next))
                        }) }
                        .pointerInput(image) { detectTransformGestures { _, pan, zoom, _ -> clamp(scale * zoom, x + pan.x, y + pan.y) } }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y })
            }
        }
        val line = "Offline \u00b7 showing a smaller copy".takeIf { !online && original == null }
        TopAppBar(title = { BarTitle(line, "This phone can't reach Fytorium, so the full-size photo can't load. This copy is smaller.") {
            Text(photo?.let { "${it.plantName} \u00b7 ${dayMonthYear(it.takenOn)}" } ?: "Photo", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f), titleContentColor = Color.White,
                navigationIconContentColor = Color.White), expandedHeight = barHeight())
    }
}
@Composable private fun SourceTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 96.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(label)
        }
    }
}
internal fun photoStatus(care: CareState?, online: Boolean, state: PhotoState): Pair<String, String>? = when {
    !online -> (care?.connectionLine(false) ?: "Offline") to "This phone can't reach Fytorium. You can still see the photos shown here. Photo changes need a connection."
    state.error == null || state.saving -> null
    state.blocked -> "Fytorium isn't responding" to "${state.error} Pull down or tap Try again."
    else -> "Fytorium refused the last request" to state.error
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TakenDate(value: String, today: String?, enabled: Boolean, onChange: (String) -> Unit) {
    var showing by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { showing = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Taken on: " + (value.takeIf { it.isNotBlank() }?.let(::dayMonthYear) ?: "not set")) }
    if (showing) {
        val initial = remember(value) { runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial, selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = today != null && Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() <= today
        })
        DatePickerDialog(onDismissRequest = { showing = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; showing = false }, enabled = picker.selectedDateMillis != null) { Text("Set date") }
        }, dismissButton = { TextButton(onClick = { showing = false }) { Text("Cancel") } }) { DialogBody { DatePicker(picker) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PhotoScreen(state: PhotoState, online: Boolean, plantName: String?, selectedCount: Int,
    thumbnails: Thumbnails? = null, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onUpload: (String, String, String) -> Unit = { _, _, _ -> }, onChange: (String, String) -> Unit = { _, _ -> },
    onOriginal: (String) -> Unit = {}, onCloseOriginal: () -> Unit = {}, onPlant: (String) -> Unit = {},
    onFormDirty: (() -> String?) -> Unit = {}, onDiscard: () -> Unit = {}, originalOpen: Boolean = state.original != null,
    care: CareState? = null, refreshing: Boolean = false, onExplain: (String) -> Unit = {}, onAdd: (() -> Unit)? = null, openPhotoId: String? = null,
    formOnly: Boolean = false, onUploaded: () -> Unit = {}, selectedUri: String? = null, onTake: () -> Unit = {}, onChoose: () -> Unit = {}, onRemovePhoto: () -> Unit = {},
    // Held above the viewer and form, so the list returns to the card the user left.
    grid: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState()) {
    var form by rememberSaveable { mutableStateOf(formOnly) }
    var note by rememberSaveable { mutableStateOf("") }
    var health by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<PhotoUpdate?>(null) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    val discardBody = "Your photo update for ${plantName ?: "this plant"} is not saved yet."
    fun changed() = (form || formOnly) && (note.isNotEmpty() || health.isNotEmpty() || date.isNotEmpty() || selectedCount > 0)
    fun clear() { form = formOnly; note = ""; health = ""; date = ""; discarding = false; onDiscard(); if (formOnly) onBack() }
    SideEffect { onFormDirty { discardBody.takeIf { changed() } } }
    val currentDirty by rememberUpdatedState(onFormDirty)
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    val enabled = online && !state.blocked && !state.saving && !state.updateRequired
    val blockedReason = "Photo changes need a connection.".takeIf { !enabled && !state.saving }
    fun act(action: () -> Unit) { if (enabled) action() else blockedReason?.let(onExplain) }
    val add = onAdd ?: { form = true }
    // A lambda, not ::back: equal function references let Back keep the first composition's state, which skipped an open original.
    val back = { if (originalOpen) onCloseOriginal() else if (changed()) discarding = true else if (form && !formOnly) clear() else onBack() }
    BackHandler(onBack = back)
    if (originalOpen) PhotoViewer(state.photos.firstOrNull { it.id == openPhotoId }, state.original?.takeIf { state.originalId == openPhotoId }, online, thumbnails, back)
    else if (!form && !formOnly) Column(Modifier.fillMaxSize()) {
        val title = plantName?.let { "Photos of $it" } ?: "Journal"
        val status = photoStatus(care, online, state)
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { online && status != null }) { Heading(title) } },
            expandedHeight = barHeight(), navigationIcon = {
                if (plantName != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = { act(add) }, modifier = Modifier.semantics { if (!enabled) { disabled(); blockedReason?.let { stateDescription = it } } }) {
                    Icon(Icons.Default.Add, plantName?.let { "Add a photo of $it" } ?: "Add a photo update",
                        tint = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            })
        // A list read from the top keeps its newest card in view when one arrives, such as the photo just added.
        val newest = state.photos.firstOrNull()?.id
        val atTop = remember(newest) { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 }
        LaunchedEffect(newest) { if (atTop) grid.scrollToItem(0) }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.loaded && state.photos.isEmpty()) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("No photos yet", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { act(add) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add your first photo") }
            } else LazyVerticalGrid(GridCells.Adaptive(320.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.photos, key = { it.id }) { photo ->
                    val day = dayMonthYear(photo.takenOn)
                    Card(Modifier.fillMaxWidth()) {
                        CardPhoto(photo, thumbnails) { onOriginal(photo.id) }
                        Column(Modifier.padding(start = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (plantName == null) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onPlant(photo.plantId) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) {
                                        Text(photo.plantName)
                                    }
                                    Text(" \u00b7 $day", maxLines = 1)
                                } else Text(day, Modifier.weight(1f))
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    // A full 48 dp target, since IconButton's own node is 40 dp.
                                    Box(Modifier.size(48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { act { menu = true } }
                                        .semantics { contentDescription = "Actions for the $day photo of ${photo.plantName}"; if (!enabled) { disabled(); blockedReason?.let { stateDescription = it } } }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.MoreVert, null, tint = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                                    }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        if (!photo.cover) DropdownMenuItem(text = { Text("Use as cover") }, onClick = { menu = false; act { onChange(photo.id, "cover") } })
                                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; act { deleting = photo } })
                                    }
                                }
                            }
                            Column(Modifier.padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (photo.cover) Text("Cover photo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                photo.health?.let { Text("Health: ${it.replace('_', ' ')}") }
                                if (photo.note.isNotEmpty()) Text(photo.note)
                            }
                        }
                    }
                }
            }
        }
    } else Column(Modifier.fillMaxSize()) {
        val status = photoStatus(care, online, state)
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { online && status != null }) {
            Heading(plantName?.let { "Add a photo of $it" } ?: "Add a photo update") } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectedUri == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceTile("Take photo", Icons.Outlined.PhotoCamera, enabled, Modifier.weight(1f), onTake)
                    SourceTile("Choose photo", Icons.Outlined.PhotoLibrary, enabled, Modifier.weight(1f), onChoose)
                } else {
                    UriImage(selectedUri, Modifier.fillMaxWidth().aspectRatio(4f / 3f), "Chosen photo for ${plantName ?: "this plant"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        var replacing by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { replacing = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Replace") }
                            DropdownMenu(replacing, onDismissRequest = { replacing = false }) {
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = { replacing = false; onTake() })
                                DropdownMenuItem(text = { Text("Choose photo") }, onClick = { replacing = false; onChoose() })
                            }
                        }
                        OutlinedButton(onClick = onRemovePhoto, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                    }
                }
                TakenDate(date.ifBlank { state.today.orEmpty() }, state.today, enabled) { date = it }
                Choice("Photo health", health, mapOf("" to "Not set") + state.healthChoices.associateWith { it.replace('_', ' ') }, enabled) { health = it }
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                val ready = enabled && selectedUri != null && state.today != null
                Button(onClick = { if (ready) onUpload(note, health, date.ifBlank { state.today.orEmpty() }) else onExplain(if (selectedUri == null) "Choose a photo first." else blockedReason ?: "Photo changes need a connection.") },
                    colors = if (ready) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { if (!ready) disabled() }) { Text("Add photo update") }
                PlantError(state.error)
            }
            if (state.saving) state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (discarding) DiscardDialog("Discard changes?", discardBody, onKeep = { discarding = false }) { clear() }
    deleting?.let { photo ->
        val day = dayMonthYear(photo.takenOn)
        AlertDialog(onDismissRequest = { if (!state.saving) deleting = null }, title = { DialogTitle("Delete this photo?") },
            text = { DialogBody { CardPhoto(photo, thumbnails, Modifier.width(160.dp), null); Text("Delete the $day photo of ${photo.plantName}? This can't be undone."); PlantError(state.error) } },
            confirmButton = { TextButton(onClick = { onChange(photo.id, "delete") }, enabled = enabled) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.saving) { Text("Cancel") } })
    }
    LaunchedEffect(state.photos, state.loaded, state.loading) {
        if (state.loaded && !state.loading && deleting != null && state.photos.none { it.id == deleting!!.id }) deleting = null
    }
    // A successful upload closes the form. A failed upload keeps every entered field.
    var uploaded by remember { mutableIntStateOf(state.uploadRevision) }
    LaunchedEffect(state.uploadRevision) {
        if (uploaded != state.uploadRevision) { form = false; note = ""; health = ""; date = ""; onUploaded() }
        uploaded = state.uploadRevision
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun IdentificationScreen(state: PhotoState, online: Boolean, selected: List<String>, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onIdentify: () -> Unit = {}, onRemove: (Int) -> Unit = {}, onSelect: (SpeciesCandidate, String, Boolean) -> Unit = { _, _, _ -> },
    onTake: () -> Unit = {}, onChoose: () -> Unit = {}, onSearchByName: () -> Unit = onBack, onExplain: (String) -> Unit = {},
    onDiscard: () -> Unit = {}, care: CareState? = null, api: com.epaflix.fytorium.ApiClient? = null, onFormDirty: (() -> String?) -> Unit = {}) {
    var chosen by rememberSaveable { mutableStateOf<SpeciesCandidate?>(null) }
    var searchId by rememberSaveable { mutableStateOf<String?>(null) }
    var showing by rememberSaveable { mutableStateOf(state.result != null) }
    var details by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.result?.id) {
        state.result?.id?.let { if (searchId != it) chosen = null; showing = true } ?: run { if (!state.saving) showing = false }
    }
    val quotaGone = state.failureStatus == 429 || state.remaining == 0
    val enabled = online && !state.saving && !state.blocked && !state.updateRequired
    val back = { if (selected.isNotEmpty()) discarding = true else onBack() }
    BackHandler(onBack = back)
    val discardBody = "Your ${selected.size} ${if (selected.size == 1) "photo is" else "photos are"} not used yet."
    SideEffect { onFormDirty { discardBody.takeIf { selected.isNotEmpty() } } }
    val currentDirty by rememberUpdatedState(onFormDirty)
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    val status = when {
        !online -> (care?.connectionLine(false) ?: "Offline") to "This phone can't reach Fytorium. Identifying needs a connection."
        state.failureStatus == 503 -> "Pl@ntNet isn't responding" to (state.error ?: "Pl@ntNet didn't answer. Try again.")
        state.blocked && state.error != null -> "Fytorium isn't responding" to state.error
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = (if (state.failureStatus == 503) onIdentify else onRefresh).takeIf { online && status != null }) {
            Heading("Identify from photos") } }, expandedHeight = barHeight(),
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val result = state.result
                if (showing && result != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selected.forEachIndexed { index, uri -> UriThumb(uri, 56.dp, "Photo ${index + 1} of ${selected.size}") }
                    }
                    TextButton(onClick = { showing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change photos") }
                    Heading("Best matches")
                    result.candidates.forEach { candidate ->
                        SpeciesSearchCard(candidate, candidate.identificationCanonicalName ?: candidate.canonicalName, api, enabled, 96.dp, matchLines(candidate)) {
                            chosen = candidate; searchId = result.id
                        }
                    }
                } else {
                    Text("Add one to five photos of the same plant. Leaves and flowers work best.")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selected.forEachIndexed { index, uri ->
                            Box {
                                UriThumb(uri, 96.dp, "Photo ${index + 1} of ${selected.size}")
                                Box(Modifier.align(Alignment.TopEnd).size(48.dp).clickable(enabled = !state.saving, role = androidx.compose.ui.semantics.Role.Button) { onRemove(index) }
                                    .semantics { contentDescription = "Remove photo ${index + 1} of ${selected.size}" }, contentAlignment = Alignment.Center) {
                                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                                        Icon(Icons.Default.Close, null, Modifier.padding(4.dp).size(16.dp))
                                    }
                                }
                            }
                        }
                        if (selected.size < 5) {
                            SourceTile("Take photo", Icons.Outlined.PhotoCamera, enabled, Modifier.size(96.dp), onTake)
                            SourceTile("Choose photos", Icons.Outlined.PhotoLibrary, enabled, Modifier.size(96.dp), onChoose)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Photos go to Pl@ntNet to identify the plant, without location data.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
                    }
                    val ready = enabled && selected.isNotEmpty() && !quotaGone
                    Button(onClick = { if (ready) onIdentify() else onExplain(when {
                        quotaGone -> "No identifications left today."
                        selected.isEmpty() -> "Add a photo first."
                        else -> "Identifying needs a connection."
                    }) }, colors = if (ready) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)), modifier = Modifier.heightIn(min = 48.dp).semantics { if (!ready) disabled() }) { Text("Identify") }
                    state.remaining?.takeIf { it in 1..50 }?.let { Text("$it identifications left today.") }
                    when {
                        quotaGone -> {
                            Heading("No identifications left today.", MaterialTheme.typography.titleMedium)
                            Text("Search by name instead.")
                            OutlinedButton(onClick = onSearchByName, modifier = Modifier.heightIn(min = 48.dp)) { Text("Search by name") }
                        }
                        state.failureStatus == 422 && !state.blocked -> {
                            Heading("Pl@ntNet didn't recognise this plant.", MaterialTheme.typography.titleMedium)
                            Text("Try a clear photo of a leaf or flower, or search by name.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onChoose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change photos") }
                                OutlinedButton(onClick = onSearchByName, modifier = Modifier.heightIn(min = 48.dp)) { Text("Search by name") }
                            }
                        }
                        state.error != null && !state.blocked && state.failureStatus != 503 -> PlantError(state.error)
                    }
                }
            }
            if (state.saving) state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    chosen?.let { candidate ->
        ConfirmSearchSpecies(candidate, candidate.identificationCanonicalName ?: candidate.canonicalName, api, enabled, onBack = { chosen = null },
            lines = matchLines(candidate), yourPhoto = selected.firstOrNull()?.let { uri -> { modifier -> UriImage(uri, modifier, "Your photo") } }) {
            searchId?.let { chosen = null; onSelect(candidate, it, true) }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { DialogTitle("About identify photos") },
        text = { DialogBody { Text("Up to 5 photos, 15 MB each and 40 MB in total. Fytorium shrinks them and removes location and camera data before sending. Fytorium keeps a photo only if you tick Keep this photo when you add the plant.") } },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
    if (discarding) DiscardDialog("Discard these photos?", discardBody,
        onKeep = { discarding = false }) { discarding = false; onDiscard(); onBack() }
}

private fun matchLines(candidate: SpeciesCandidate) = listOfNotNull(
    candidate.score?.let { "${Math.round(it * 100)}% match" },
    candidate.canonicalName.takeIf { accepted -> candidate.identificationCanonicalName?.let { it != accepted } == true }?.let { "Now called $it" })

@Composable private fun UriThumb(uri: String, size: androidx.compose.ui.unit.Dp, description: String) =
    UriImage(uri, Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), description)

@Composable private fun UriImage(uri: String, modifier: Modifier, description: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = description }) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val bitmap by produceState<Bitmap?>(null, uri, width) {
            var decoded: Bitmap? = null
            try {
                withContext(Dispatchers.IO) { decoded = decodeUriPreview(context, android.net.Uri.parse(uri), width * 2, width * 2) }
                value = decoded
                awaitDispose {}
            } finally { decoded?.recycle() }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable internal fun FilePhoto(path: String, modifier: Modifier) =
    UriImage(android.net.Uri.fromFile(java.io.File(path)).toString(), modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), "Photo from Identify")
