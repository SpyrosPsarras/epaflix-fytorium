package com.epaflix.fytorium.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.DiscardDialog
import com.epaflix.fytorium.plants.Heading
import com.epaflix.fytorium.plants.PlantError
import com.epaflix.fytorium.plants.connectionNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The switches on Android; late_tasks stays as stored, since Today lists late tasks itself (W5a-P20).
internal val cardSwitches = listOf(
    Triple("current_weather", "Today's weather", "Low and high for the household location"),
    Triple("weather_alerts", "Weather alerts", "Frost, cold and rain for outdoor plants"),
    Triple("plant_warnings", "Plant warnings", "Light, drainage and missing details"))
internal const val changesOffline = "Changing settings needs a connection."
internal fun placeLine(location: WeatherLocation?) = location?.let { it.label ?: "%.2f, %.2f".format(java.util.Locale.ROOT, it.latitude, it.longitude) }
    ?: "No location set. Weather cards and frost alerts need one."

// W5a-P14 to P21, P30 to P33: household location first, then the cards on Today; switches save on change.
@Composable fun WeatherFeedRoute(feed: FeedRepository, care: CareState?, online: Boolean, onBack: () -> Unit,
                                 onReceipt: (String) -> Unit = {}, onFormDirty: (() -> String?) -> Unit = {}, onUpdateRequired: () -> Unit = {}, answers: Int = 0) {
    val state by feed.state.collectAsState()
    val scope = rememberCoroutineScope()
    // W5a-P31: reload on open, on resume and on reconnect; W5a-P13: a 426 shows the full stop.
    LaunchedEffect(state.member, online) { if (online) withContext(Dispatchers.IO) { feed.refresh(state.member); feed.loadLocation() } }
    var resumed by remember(feed) { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(feed) { if (resumed) scope.launch(Dispatchers.IO) { feed.refresh(feed.state.value.member); feed.loadLocation() }; resumed = true; onPauseOrDispose {} }
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    // A read that failed loads again once Fytorium answers another request, and every 15 seconds while the screen shows the outage.
    LaunchedEffect(answers) { feed.state.value.takeIf { it.unavailable || it.readError != null || it.locationError != null }?.let { withContext(Dispatchers.IO) { feed.refresh(it.member); feed.loadLocation() } } }
    val outage = state.unavailable || state.readError != null || state.locationError != null
    LaunchedEffect(outage, online) {
        while (outage && online) { kotlinx.coroutines.delay(15_000); withContext(Dispatchers.IO) { feed.refresh(feed.state.value.member); feed.loadLocation() } }
    }
    WeatherFeedScreen(state, care, online, onBack, onReceipt, onFormDirty,
        onRefresh = { scope.launch(Dispatchers.IO) { feed.refresh(state.member); feed.loadLocation() } },
        onSearch = { withContext(Dispatchers.IO) { feed.searchPlaces(it) } },
        onSaveLocation = { withContext(Dispatchers.IO) { feed.saveLocation(it) } },
        onSetting = { key, value -> withContext(Dispatchers.IO) { feed.saveSetting(key, value) } },
        failure = { feed.state.value.takeIf { !it.offline && !it.unavailable }?.message })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun WeatherFeedScreen(state: FeedState, care: CareState?, online: Boolean, onBack: () -> Unit = {},
    onReceipt: (String) -> Unit = {}, onFormDirty: (() -> String?) -> Unit = {}, onRefresh: () -> Unit = {},
    onSearch: suspend (String) -> FeedRepository.PlaceAnswer = { FeedRepository.PlaceAnswer(null) }, onSaveLocation: suspend (WeatherLocation?) -> Boolean = { false },
    onSetting: suspend (String, Boolean) -> Boolean = { _, _ -> false }, failure: () -> String? = { null }) {
    if (state.member?.role !in listOf(Role.owner, Role.family)) return
    val scope = rememberCoroutineScope()
    val owner = state.member?.role == Role.owner
    var query by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var results by rememberSaveable { mutableStateOf<List<Place>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var queryError by rememberSaveable { mutableStateOf<String?>(null) }
    var placeError by rememberSaveable { mutableStateOf<String?>(null) }
    var formOpen by rememberSaveable { mutableStateOf(false) }
    var latitude by rememberSaveable { mutableStateOf(state.location?.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(state.location?.longitude?.toString().orEmpty()) }
    var label by rememberSaveable { mutableStateOf(state.location?.label.orEmpty()) }
    var fieldErrors by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    // Whether discarding leaves the page (Back) or only folds the form (Cancel coordinates).
    var discardLeaves by rememberSaveable { mutableStateOf(true) }
    val pending = remember { Pending<Boolean>() }
    val enqueue = rememberWriteQueue()
    fun prefill() { latitude = state.location?.latitude?.toString().orEmpty(); longitude = state.location?.longitude?.toString().orEmpty(); label = state.location?.label.orEmpty() }
    fun typed() = formOpen && (latitude != state.location?.latitude?.toString().orEmpty() || longitude != state.location?.longitude?.toString().orEmpty() || label != state.location?.label.orEmpty())
    SideEffect { onFormDirty { "The location you typed is not saved.".takeIf { typed() } } }
    val currentDirty by rememberUpdatedState(onFormDirty)
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    androidx.activity.compose.BackHandler { if (typed()) { discardLeaves = true; discarding = true } else onBack() }
    // The last query sent, apart from the one whose results show, so Try again repeats it and an older reply never replaces a newer one.
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var searches by remember { mutableIntStateOf(0) }
    // Place search's own outage, from the latest search only.
    var searchDown by rememberSaveable { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun search(text: String = query) {
        val q = text.trim()
        if (q.length < 2) { queryError = "Type at least 2 letters."; return }
        if (q.length > 100) { queryError = "Type at most 100 letters."; return }
        if (!online) return
        queryError = null; searching = true; submitted = q
        keyboard?.hide(); focus.clearFocus()
        val mine = ++searches
        scope.launch {
            val answer = onSearch(q)
            if (mine == searches) { answer.places?.let { results = it; asked = q }; searchDown = answer.unavailable; searching = false }
        }
    }
    var savingLocation by remember { mutableStateOf(false) }
    fun save(location: WeatherLocation?, receipt: String, after: () -> Unit) = scope.launch {
        placeError = null; savingLocation = true
        try {
            if (onSaveLocation(location)) { after(); onReceipt(receipt) }
            else placeError = failure()
        } finally { savingLocation = false }
    }
    val status = when {
        !online || state.offline -> (care?.copy(lastUpdated = state.updatedAt)?.connectionLine(false) ?: "Offline") to care?.connectionNotice(false).orEmpty()
        searchDown -> "Place search isn't responding" to "Place search isn't responding. Try again in a minute."
        state.unavailable -> "Fytorium isn't responding" to "Fytorium didn't answer. Try again when it responds."
        (state.readError ?: state.locationError) != null -> "Settings didn't load" to (state.readError ?: state.locationError).orEmpty()
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = {
                if (searchDown) submitted?.let { search(it) } ?: Unit else onRefresh()
                Unit
            }.takeIf { status != null && online }) { Heading("Weather and feed") } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = { if (typed()) { discardLeaves = true; discarding = true } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Heading("Household location", MaterialTheme.typography.titleMedium)
                Text(placeLine(state.location))
                if (!owner) Text("Only ${care?.names?.owner ?: "the owner"} can change it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    OutlinedTextField(query, { query = it; queryError = null }, label = { Text("Find your town") },
                        supportingText = { Text(queryError ?: "Town or city") }, isError = queryError != null, singleLine = true, enabled = online,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth().then(if (online) Modifier else Modifier.clickable { onReceipt("Searching needs a connection.") }
                            .semantics { stateDescription = "Searching needs a connection." }))
                    results?.let { found ->
                        if (found.isEmpty()) {
                            Heading("No place called \u201c${asked.orEmpty()}\u201d", MaterialTheme.typography.titleSmall)
                            Text("Check the spelling, or enter coordinates instead.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else Column {
                            found.forEach { place ->
                                ListItem(headlineContent = { Text(place.name) }, supportingContent = { Text(place.where) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !savingLocation, role = SemanticsRole.Button) {
                                        if (!online) { onReceipt(changesOffline); return@clickable }
                                        save(WeatherLocation(place.latitude, place.longitude, place.label), "Weather location set to ${place.label}") { results = null; query = ""; asked = null }
                                    })
                            }
                        }
                    }
                    if (!formOpen) PlantError(placeError)
                    // At large text the second button moves to its own line instead of wrapping its words.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The first button keeps its place: it opens the form, then folds it again.
                        TextButton(onClick = { if (!formOpen) { prefill(); formOpen = true } else if (typed()) { discardLeaves = false; discarding = true } else formOpen = false },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            // Both labels take the space, so Remove location never moves when the form opens or folds.
                            Box(contentAlignment = Alignment.Center) {
                                Text("Enter coordinates instead", Modifier.alpha(if (formOpen) 0f else 1f).then(if (formOpen) Modifier.clearAndSetSemantics {} else Modifier))
                                Text("Cancel coordinates", Modifier.alpha(if (formOpen) 1f else 0f).then(if (formOpen) Modifier else Modifier.clearAndSetSemantics {}))
                            }
                        }
                        if (state.location != null) TextButton(onClick = { if (online) removing = true else onReceipt(changesOffline) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove location") }
                    }
                    if (formOpen) {
                        val keys = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
                        // Offline or while it saves, the draft stays but can't change.
                        val locked = !online || savingLocation
                        Box { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(latitude, { latitude = it; fieldErrors = fieldErrors - "latitude" }, label = { Text("Latitude") }, singleLine = true, keyboardOptions = keys, readOnly = locked, enabled = online,
                            supportingText = { Text(fieldErrors["latitude"] ?: "-90 to 90") }, isError = "latitude" in fieldErrors, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(longitude, { longitude = it; fieldErrors = fieldErrors - "longitude" }, label = { Text("Longitude") }, singleLine = true, keyboardOptions = keys, readOnly = locked, enabled = online,
                            supportingText = { Text(fieldErrors["longitude"] ?: "-180 to 180") }, isError = "longitude" in fieldErrors, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(label, { label = it.take(200) }, label = { Text("Place name (optional)") }, singleLine = true, readOnly = locked, enabled = online, modifier = Modifier.fillMaxWidth())
                        }
                        // Offline the fields look off, and a tap on them says why.
                        if (!online) Box(Modifier.matchParentSize().clickable(onClickLabel = "Explain") { onReceipt(changesOffline) }.semantics { stateDescription = changesOffline })
                        }
                        Button(onClick = {
                            if (!online) { onReceipt(changesOffline); return@Button }
                            val lat = latitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                            val lon = longitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                            fieldErrors = listOfNotNull(("latitude" to "Enter a number from -90 to 90.").takeIf { lat == null },
                                ("longitude" to "Enter a number from -180 to 180.").takeIf { lon == null }).toMap()
                            // Only the draft that was saved closes; a value typed meanwhile keeps the form open.
                            val draft = Triple(latitude, longitude, label)
                            // New coordinates under the old place's name would name the wrong town, so an untouched name goes with them.
                            // Compared as numbers, so "60.392990" for 60.39299 is not a move.
                            val moved = lat != state.location?.latitude || lon != state.location?.longitude
                            val name = label.trim().ifEmpty { null }?.takeUnless { moved && label == state.location?.label.orEmpty() }
                            if (lat != null && lon != null) save(WeatherLocation(lat, lon, name), "Weather location saved") {
                                if (Triple(latitude, longitude, label) == draft) formOpen = false
                            }
                        }, enabled = !savingLocation, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = changesOffline }) { Text("Save location") }
                        // A refusal shows under Save, in a line that keeps its space.
                        PlantError(placeError, keep = "Fytorium refused this location.")
                    }
                    Text("Place searches and the household coordinates go to Open-Meteo for weather.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Heading("Cards on Today", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
                Text("Only you see these choices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                cardSwitches.forEach { (key, title, description) ->
                    val on = pending[key] ?: (state.settings?.get(key) == true)
                    val canChange = online && !state.offline && state.settings != null
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(on, role = SemanticsRole.Switch) { value ->
                            if (!canChange) { onReceipt(changesOffline); return@toggleable }
                            val ticket = pending.start(key, value)
                            enqueue {
                                val saved = onSetting(key, value)
                                pending.end(key, ticket)
                                failure()?.takeIf { !saved }?.let { onReceipt("$title wasn't saved. $it") }
                            }
                        }.semantics { if (!canChange) stateDescription = changesOffline }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(title); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(on, onCheckedChange = null, enabled = canChange)
                    }
                }
            }
            com.epaflix.fytorium.ReportBusy(state.saving)
            if (searching || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { DialogTitle("Remove the household location?") },
        text = { DialogBody { Text("Weather cards and frost alerts stop until a location is set again.") } },
        confirmButton = { TextButton(onClick = { removing = false; save(null, "Removed the weather location") { formOpen = false } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } })
    if (discarding) DiscardDialog("Discard these coordinates?", "The location you typed is not saved.", onKeep = { discarding = false }) {
        discarding = false; formOpen = false; prefill(); if (discardLeaves) onBack()
    }
}
