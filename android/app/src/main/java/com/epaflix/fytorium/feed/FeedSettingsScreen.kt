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
                                 onReceipt: (String) -> Unit = {}, onFormDirty: (() -> String?) -> Unit = {}) {
    val state by feed.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.member) { withContext(Dispatchers.IO) { feed.refresh(state.member); feed.loadLocation() } }
    WeatherFeedScreen(state, care, online, onBack, onReceipt, onFormDirty,
        onRefresh = { scope.launch(Dispatchers.IO) { feed.refresh(state.member); feed.loadLocation() } },
        onSearch = { withContext(Dispatchers.IO) { feed.searchPlaces(it) } },
        onSaveLocation = { withContext(Dispatchers.IO) { feed.saveLocation(it) } },
        onSetting = { key, value -> withContext(Dispatchers.IO) { feed.saveSetting(key, value) } },
        failure = { feed.state.value.takeIf { !it.offline && !it.unavailable }?.message })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WeatherFeedScreen(state: FeedState, care: CareState?, online: Boolean, onBack: () -> Unit = {},
    onReceipt: (String) -> Unit = {}, onFormDirty: (() -> String?) -> Unit = {}, onRefresh: () -> Unit = {},
    onSearch: suspend (String) -> List<Place>? = { null }, onSaveLocation: suspend (WeatherLocation?) -> Boolean = { false },
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
    var pending by remember { mutableStateOf(mapOf<String, Boolean>()) }
    fun prefill() { latitude = state.location?.latitude?.toString().orEmpty(); longitude = state.location?.longitude?.toString().orEmpty(); label = state.location?.label.orEmpty() }
    fun typed() = formOpen && (latitude != state.location?.latitude?.toString().orEmpty() || longitude != state.location?.longitude?.toString().orEmpty() || label != state.location?.label.orEmpty())
    SideEffect { onFormDirty { "The location you typed is not saved.".takeIf { typed() } } }
    val currentDirty by rememberUpdatedState(onFormDirty)
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    androidx.activity.compose.BackHandler { if (typed()) discarding = true else onBack() }
    fun search(text: String = query) {
        val q = text.trim()
        if (q.length < 2) { queryError = "Type at least 2 letters."; return }
        if (!online) return
        queryError = null; searching = true
        scope.launch {
            try { onSearch(q)?.let { results = it; asked = q } } finally { searching = false }
        }
    }
    fun save(location: WeatherLocation?, receipt: String, after: () -> Unit) = scope.launch {
        placeError = null
        if (onSaveLocation(location)) { after(); onReceipt(receipt) }
        else placeError = failure()
    }
    val status = when {
        !online || state.offline -> (care?.copy(lastUpdated = state.updatedAt)?.connectionLine(false) ?: "Offline") to care?.connectionNotice(false).orEmpty()
        state.placesUnavailable -> "Place search isn't responding" to "Place search isn't responding. Try again in a minute."
        state.unavailable -> "Fytorium isn't responding" to "Fytorium didn't answer. Try again when it responds."
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = {
                if (state.placesUnavailable) asked?.let { search(it) } ?: Unit else onRefresh()
                Unit
            }.takeIf { status != null && online }) { Heading("Weather and feed") } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = { if (typed()) discarding = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
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
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = online && !state.saving, role = SemanticsRole.Button) {
                                        save(WeatherLocation(place.latitude, place.longitude, place.label), "Weather location set to ${place.label}") { results = null; query = ""; asked = null }
                                    })
                            }
                        }
                    }
                    PlantError(placeError)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!formOpen) TextButton(onClick = { prefill(); formOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enter coordinates instead") }
                        if (state.location != null) TextButton(onClick = { if (online) removing = true else onReceipt(changesOffline) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove location") }
                    }
                    if (formOpen) {
                        val keys = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
                        OutlinedTextField(latitude, { latitude = it; fieldErrors = fieldErrors - "latitude" }, label = { Text("Latitude") }, singleLine = true, keyboardOptions = keys,
                            supportingText = { Text(fieldErrors["latitude"] ?: "-90 to 90") }, isError = "latitude" in fieldErrors, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(longitude, { longitude = it; fieldErrors = fieldErrors - "longitude" }, label = { Text("Longitude") }, singleLine = true, keyboardOptions = keys,
                            supportingText = { Text(fieldErrors["longitude"] ?: "-180 to 180") }, isError = "longitude" in fieldErrors, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(label, { label = it.take(200) }, label = { Text("Place name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            if (!online) { onReceipt(changesOffline); return@Button }
                            val lat = latitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                            val lon = longitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                            fieldErrors = listOfNotNull(("latitude" to "Enter a number from -90 to 90.").takeIf { lat == null },
                                ("longitude" to "Enter a number from -180 to 180.").takeIf { lon == null }).toMap()
                            if (lat != null && lon != null) save(WeatherLocation(lat, lon, label.trim().ifEmpty { null }), "Weather location saved") { formOpen = false }
                        }, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = changesOffline }) { Text("Save location") }
                    }
                    Text("Place searches and the household coordinates go to Open-Meteo for weather.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Heading("Cards on Today", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
                Text("Only you see these choices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                cardSwitches.forEach { (key, title, description) ->
                    val on = pending[key] ?: state.settings?.get(key) == true
                    val canChange = online && !state.offline && state.settings != null
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(on, role = SemanticsRole.Switch) { value ->
                            if (!canChange) { onReceipt(changesOffline); return@toggleable }
                            pending = pending + (key to value)
                            scope.launch {
                                val saved = onSetting(key, value)
                                pending = pending - key
                                failure()?.takeIf { !saved }?.let { onReceipt("$title wasn't saved. $it") }
                            }
                        }.semantics { if (!canChange) stateDescription = changesOffline }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(title); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(on, onCheckedChange = null, enabled = canChange)
                    }
                }
            }
            if (searching || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { DialogTitle("Remove the household location?") },
        text = { DialogBody { Text("Weather cards and frost alerts stop until a location is set again.") } },
        confirmButton = { TextButton(onClick = { removing = false; save(null, "Removed the weather location") { formOpen = false } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } })
    if (discarding) DiscardDialog("Discard these coordinates?", "The location you typed is not saved.", onKeep = { discarding = false }) {
        discarding = false; formOpen = false; prefill(); onBack()
    }
}
