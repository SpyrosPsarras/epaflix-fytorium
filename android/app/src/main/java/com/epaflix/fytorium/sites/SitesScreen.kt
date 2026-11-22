package com.epaflix.fytorium.sites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.Expander
import com.epaflix.fytorium.plants.Heading
import com.epaflix.fytorium.plants.PlantError
import com.epaflix.fytorium.plants.PlantTile
import com.epaflix.fytorium.plants.choiceLabel
import com.epaflix.fytorium.plants.lateDaysByPlant
import com.epaflix.fytorium.plants.savedFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val sitesOffline = "Changing sites needs a connection."

@Composable fun SitesRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit, creating: Boolean = false, onCreated: (String) -> Unit = {},
    onFormDirty: (() -> String?) -> Unit = {}, onUpdateRequired: () -> Unit = {}, answers: Int = 0, startSite: String? = null,
    thumbnails: Thumbnails? = null, onOpenPlant: (String) -> Unit = {}, onReceipt: (String) -> Unit = {}, onRefreshPlants: suspend () -> Unit = {},
    cached: (String) -> Pair<String, Long>? = { null }, keep: (String, String) -> Long? = { _, _ -> null }) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    // Keyed by the member id, never the member object: its hash changes per process (W5b-P29).
    val repository = remember(api, member?.id) {
        SiteRepository(api, { currentCare.member?.id == member?.id && member != null && currentCare.member?.role != com.epaflix.fytorium.Role.sitter },
            { currentConnected() && !currentCare.updateRequired }, cached, keep)
    }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } }
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    // Once Fytorium answers a plant request again, also the plant reload after a reconnect, a failed sites read loads again without a tap; the form keeps its input.
    com.epaflix.fytorium.household.ReloadOnAnswer(answers, state.busy, { repository.state.value.run { readError != null || blocked } }) { withContext(Dispatchers.IO) { repository.refresh() } }
    val online = connected() && !care.updateRequired
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) { if (online && !wasOnline) reload(); wasOnline = online }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestReload by rememberUpdatedState(::reload)
    DisposableEffect(lifecycle) {
        // Only a return to the app reloads; the first resume comes with the open, which already loads.
        var paused = false
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) paused = true else if (event == Lifecycle.Event.ON_RESUME && paused) latestReload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SitesScreen(state, online, onBack = onBack, creating = creating, onFormDirty = onFormDirty, care = care, startSite = startSite,
        thumbnails = thumbnails, onOpenPlant = onOpenPlant, onReceipt = onReceipt, createdId = { repository.state.value.created },
        onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onPull = { withContext(Dispatchers.IO) { repository.refresh() }; onRefreshPlants() },
        onClear = repository::clearErrors,
        onSave = { withContext(Dispatchers.IO) { repository.save(it, onCreated) } },
        onDelete = { withContext(Dispatchers.IO) { repository.delete(it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SitesScreen(state: SiteState, online: Boolean = true, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onClear: () -> Unit = {}, onSave: suspend (Site) -> Boolean = { false }, onDelete: suspend (String) -> Boolean = { false }, creating: Boolean = false,
    onFormDirty: (() -> String?) -> Unit = {}, care: CareState? = null, startSite: String? = null, thumbnails: Thumbnails? = null,
    onOpenPlant: (String) -> Unit = {}, onReceipt: (String) -> Unit = {}, onPull: suspend () -> Unit = { onRefresh() },
    createdId: () -> String? = { state.created }) {
    var editor by rememberSaveable { mutableStateOf<Site?>(if (creating) Site() else null) }
    // "list" or the id of the site whose page shows.
    var shown by rememberSaveable { mutableStateOf(startSite ?: "list") }
    val forms = rememberSaveableStateHolder()
    // The open form's check of what was typed, read at the moment something leaves it, so Back right after a keystroke still asks.
    var changed by remember(editor) { mutableStateOf({ false }) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf<Site?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = state.loaded && !state.busy && !state.blocked && !state.updateRequired && !submitting
    val writable = online && ready
    // Offline a write control keeps its look and says why on tap (W5b-P27).
    fun write(action: () -> Unit) { if (!online) onReceipt(sitesOffline) else if (ready) action() }
    val site = state.sites.firstOrNull { it.id == shown }
    fun nameOf(it: Site) = it.fields["name"]?.takeIf(String::isNotBlank) ?: "this site"
    fun closeEditor() { forms.removeState("editor"); editor = null; onClear(); if (creating) onBack() }
    fun leaveEditor() { if (changed()) discarding = true else closeEditor() }
    fun back() { when { editor != null -> leaveEditor(); shown != "list" && startSite == null -> shown = "list"; else -> onBack() } }
    val discardBody = editor?.let { if (it.id == null) "Your changes to this new site are not saved yet." else "Your changes to ${nameOf(it)} are not saved yet." }
    val currentDirty by rememberUpdatedState(onFormDirty)
    // The shell asks the same question before a tab or notification leaves a form with changes.
    SideEffect { onFormDirty { discardBody?.takeIf { changed() } } }
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    BackHandler { back() }
    val status = when {
        !online -> (care?.copy(lastUpdated = state.updatedAt)?.connectionLine(false) ?: "Offline") to "This phone can't reach Fytorium. You can still see the saved sites. $sitesOffline"
        state.readError != null -> "Sites didn't load" to state.readError
        // A refused or unanswered change pauses writes until a reload succeeds; Try again reloads.
        state.blocked -> "Sites can't be changed right now" to listOfNotNull(state.errors["_form"], "Try again to reload the sites.").joinToString(" ")
        else -> null
    }
    val title = editor?.let { if (it.id == null) "New site" else "Edit ${nameOf(it)}" } ?: site?.let(::nameOf) ?: "Sites"
    Scaffold(topBar = {
        // Offline and a failed read show as a line under the title, in a bar that keeps its height, so neither moves the form or the list.
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { online && (state.readError != null || state.blocked) }) { Text(title) } }, expandedHeight = barHeight(),
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                if (editor == null && shown == "list") IconButton(onClick = { write { onClear(); editor = Site() } }, enabled = ready || !online, modifier = Modifier.semantics { if (!online) stateDescription = sitesOffline }) {
                    Icon(Icons.Default.Add, contentDescription = "Create site") }
                if (editor == null && site != null) {
                    IconButton(onClick = { write { onClear(); editor = site } }, enabled = ready || !online, modifier = Modifier.semantics { if (!online) stateDescription = sitesOffline }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${nameOf(site)}") }
                    // Only an empty site can go, so the page never offers a delete the server would refuse (W5b-P7).
                    if (site.plantCount == 0) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Delete site") }, onClick = { menu = false; write { onClear(); confirming = site } })
                        }
                    }
                }
            })
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        if (editor != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            forms.SaveableStateProvider("editor") {
                SiteForm(editor!!, state.errors, writable, online, onCancel = ::leaveEditor, onDirty = { changed = it }, onOffline = { onReceipt(sitesOffline) }, onSave = { input ->
                    scope.launch {
                        submitting = true
                        try {
                            if (onSave(input)) {
                                val name = nameOf(input)
                                onReceipt(if (input.id == null) "Created $name" else "Saved $name")
                                // A site made from the list opens its own page; one made for a plant form goes back to that form.
                                if (input.id == null && !creating) createdId()?.let { shown = it }
                                closeEditor()
                            }
                        } finally { submitting = false }
                    }
                })
            }
        } else PullToRefreshBox(isRefreshing = pulling, onRefresh = { pulling = true; scope.launch { try { onPull() } finally { pulling = false } } }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (site != null) SitePage(site, care, thumbnails, onOpenPlant, onEdit = { write { onClear(); editor = site } })
                else if (shown == "list") {
                    if (state.loaded && state.sites.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("No sites yet", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { write { onClear(); editor = Site() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create a site") }
                    }
                    for (row in state.sites) ListItem(headlineContent = { Text(nameOf(row)) }, supportingContent = { Text(row.summary()) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("site:${row.id}")
                            .clickable(onClickLabel = "Open ${nameOf(row)}", role = androidx.compose.ui.semantics.Role.Button) { shown = row.id!! })
                }
            }
        }
        // Progress overlays the top edge, so nothing in the form moves when a save starts or ends.
        if (state.busy || submitting) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    } }
    if (discarding) com.epaflix.fytorium.plants.DiscardDialog("Discard changes?", discardBody.orEmpty(), onKeep = { discarding = false }) { discarding = false; closeEditor() }
    confirming?.let { doomed ->
        fun dismiss() { if (!submitting && !state.busy) { confirming = null; onClear() } }
        AlertDialog(onDismissRequest = ::dismiss,
            // A generic title, with the site named in the body, so a long name never crowds the title (W3).
            title = { DialogTitle("Delete site?") },
            text = { DialogBody {
                Text("${nameOf(doomed)} and its conditions are removed for good. Plants in the graveyard that were here keep their history; restoring one asks for a site.")
                PlantError(state.errors["_form"])
            } },
            confirmButton = {
                TextButton(onClick = {
                    write { scope.launch {
                        submitting = true
                        try { if (onDelete(doomed.id!!)) { confirming = null; shown = "list"; onReceipt("Deleted ${nameOf(doomed)}") } } finally { submitting = false }
                    } }
                }, enabled = !submitting && !state.busy, modifier = Modifier.semantics { if (!online) stateDescription = sitesOffline }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = ::dismiss, enabled = !submitting && !state.busy) { Text("Cancel") } })
    }
}

private fun Site.location() = if (fields["location"] == "outdoor") "Outdoor" else "Indoor"
internal fun Site.summary(): String = location() + " · " + when (plantCount) { 0 -> "No plants"; 1 -> "1 plant"; else -> "$plantCount plants" } +
    if (importedIncomplete) " · Conditions not set" else ""
private fun bounds(min: String?, max: String?): String? = when {
    !min.isNullOrBlank() && !max.isNullOrBlank() -> "$min to $max °C"
    !min.isNullOrBlank() -> "at least $min °C"
    !max.isNullOrBlank() -> "at most $max °C"
    else -> null
}

@Composable private fun SitePage(site: Site, care: CareState?, thumbnails: Thumbnails?, onOpenPlant: (String) -> Unit, onEdit: () -> Unit) {
    val name = site.fields["name"].orEmpty()
    // The cached plant list, so the page works offline.
    val plants = care?.plants.orEmpty().filter { JSONObject(it.attributesJson).optString("site_id") == site.id }.sortedBy { it.displayName.lowercase() }
    val late = care?.lateDaysByPlant().orEmpty()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val air = when (site.fields["humidity"]) { "dry" -> "Dry air"; "humid" -> "Humid air"; else -> "Normal humidity" }
        Text("${site.location()} · ${choiceLabel(site.fields["light"].orEmpty())} · $air")
        if (site.growLight) Text("Grow light")
        if (site.draft) Text("Draft from a door, window or vent")
        bounds(site.fields["warm_min_c"], site.fields["warm_max_c"])?.let { Text("Warm half of the year: $it") }
        bounds(site.fields["cold_min_c"], site.fields["cold_max_c"])?.let { Text("Cold half of the year: $it") }
        if (site.importedIncomplete) {
            Text("Imported from Planta with default conditions. Edit the site to set them.")
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit site") }
        }
        Heading("Plants (${plants.size})", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
        for (pair in plants.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (plant in pair) Box(Modifier.weight(1f)) { PlantTile(plant, late[plant.id] ?: 0, null, thumbnails) { onOpenPlant(plant.id) } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Text(if (plants.isEmpty()) "No plants here yet." else "To delete $name, move its ${plants.size} ${if (plants.size == 1) "plant" else "plants"} to another site or to the graveyard first.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SiteForm(initial: Site, errors: Map<String, String>, enabled: Boolean, online: Boolean, onCancel: () -> Unit, onDirty: (() -> Boolean) -> Unit,
    onOffline: () -> Unit, onSave: (Site) -> Unit) {
    val fields = savedFields(initial.id) { initial.fields }
    var growLight by rememberSaveable(initial.id) { mutableStateOf(initial.growLight) }
    var draft by rememberSaveable(initial.id) { mutableStateOf(initial.draft) }
    var temperatures by rememberSaveable(initial.id) { mutableStateOf(temperatureFields.keys.any { !initial.fields[it].isNullOrBlank() }) }
    // A refusal of a bound opens the section, so its message is never hidden.
    LaunchedEffect(errors) { if (temperatureFields.keys.any { it in errors }) temperatures = true }
    fun input() = initial.copy(fields = fields.toMap(), growLight = growLight, draft = draft)
    SideEffect { onDirty { input() != initial } }
    fun field(key: String, value: String) { fields[key] = value }
    // shortcut: copies readSite's longest name and temperature messages to keep their space; upgrade when /api/sites sends its refusal texts or the server wording changes.
    OutlinedTextField(fields["name"].orEmpty(), { field("name", it) }, label = { Text("Name") }, enabled = enabled, isError = errors.containsKey("name"),
        supportingText = { PlantError(errors["name"], keep = "Enter a site name.") }, modifier = Modifier.fillMaxWidth())
    for ((key, choice) in siteChoices) {
        com.epaflix.fytorium.plants.Choice(choice.first, fields[key].orEmpty(), choice.second.associateWith { choiceLabel(it) }, enabled) { field(key, it) }
        PlantError(errors[key])
    }
    SiteSwitch("Grow light", null, growLight, enabled) { growLight = it }
    PlantError(errors["grow_light"])
    SiteSwitch("Draft from a door, window or vent", "Moving air dries soil faster.", draft, enabled) { draft = it }
    PlantError(errors["draft"])
    Expander("Temperatures (optional)", temperatures) { temperatures = !temperatures }
    if (temperatures) {
        Text("Warm half: when the heating is off, or summer. Cold half: when the heating is on, or winter. Leave a bound blank if you don't know it.")
        for ((key, label) in temperatureFields) {
            // The text keyboard, so a minus sign can be typed.
            OutlinedTextField(fields[key].orEmpty(), { field(key, it) }, label = { Text(label) }, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), isError = errors.containsKey(key), singleLine = true,
                supportingText = { PlantError(errors[key], keep = "Enter a whole number of °C or leave blank.") }, modifier = Modifier.fillMaxWidth())
        }
    }
    Button(onClick = { if (!online) onOffline() else onSave(input()) }, enabled = enabled || !online,
        modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = sitesOffline }) { Text("Save site") }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    // A failed save's message goes under the buttons, so neither moves when it appears.
    PlantError(errors["_form"])
}

@Composable private fun SiteSwitch(label: String, supporting: String?, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}
