package com.epaflix.fytorium.sites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.PlantError
import com.epaflix.fytorium.plants.savedFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SitesRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit, creating: Boolean = false, onCreated: (String) -> Unit = {},
    onFormDirty: (() -> String?) -> Unit = {}, onUpdateRequired: () -> Unit = {}, answers: Int = 0) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member) {
        SiteRepository(api, { currentCare.member == member && member != null && member.role != com.epaflix.fytorium.Role.sitter },
            { currentConnected() && !currentCare.updateRequired })
    }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    // Once Fytorium answers a plant request again, also the plant reload after a reconnect, a failed sites read loads again without a tap; the form keeps its input.
    LaunchedEffect(answers) { if (repository.state.value.run { readError != null && !busy }) withContext(Dispatchers.IO) { repository.refresh() } }
    SitesScreen(state, connected() && !care.updateRequired, onBack = onBack, creating = creating, onFormDirty = onFormDirty,
        onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onClear = repository::clearErrors,
        onSave = { withContext(Dispatchers.IO) { repository.save(it, onCreated) } },
        onDelete = { withContext(Dispatchers.IO) { repository.delete(it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SitesScreen(state: SiteState, online: Boolean = true, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onClear: () -> Unit = {}, onSave: suspend (Site) -> Boolean = { false }, onDelete: suspend (String) -> Boolean = { false }, creating: Boolean = false,
    onFormDirty: (() -> String?) -> Unit = {}) {
    var editor by rememberSaveable { mutableStateOf<Site?>(if (creating) Site() else null) }
    val forms = rememberSaveableStateHolder()
    // The open form's check of what was typed, read at the moment something leaves it, so Back right after a keystroke still asks.
    var changed by remember(editor) { mutableStateOf({ false }) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf<Site?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writable = online && state.loaded && !state.busy && !state.blocked && !state.updateRequired && !submitting
    fun closeEditor() { forms.removeState("editor"); editor = null; onClear(); if (creating) onBack() }
    fun leaveEditor() { if (changed()) discarding = true else closeEditor() }
    val discardTitle = editor?.let { if (it.id == null) "Discard this new site?" else "Discard changes to ${it.fields["name"]?.takeIf(String::isNotBlank) ?: "this site"}?" }
    val currentDirty by rememberUpdatedState(onFormDirty)
    // The shell asks the same question before a tab or notification leaves a form with changes.
    SideEffect { onFormDirty { discardTitle?.takeIf { changed() } } }
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    BackHandler { if (editor != null) leaveEditor() else onBack() }
    val status = if (!online) "Offline" to "This phone can't reach Fytorium. Reconnect to make changes." else state.readError?.let { "Sites didn't load" to it }
    Scaffold(topBar = {
        // Offline and a failed read show as a line under the title, in a bar that keeps its height, so neither moves the form or the list.
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { state.readError != null }) {
            Text(editor?.let { if (it.id == null) "New site" else "Edit site" } ?: "Sites") } }, expandedHeight = barHeight(),
            navigationIcon = {
                IconButton(onClick = { if (editor != null) leaveEditor() else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor != null) {
                forms.SaveableStateProvider("editor") {
                    SiteForm(editor!!, state.errors, writable, onCancel = ::leaveEditor, onDirty = { changed = it }, onSave = { input ->
                        scope.launch {
                            submitting = true
                            try { if (onSave(input)) closeEditor() } finally { submitting = false }
                        }
                    })
                }
                if (state.blocked && state.readError == null) OutlinedButton(onClick = onRefresh, enabled = !state.busy && !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh sites") }
            } else {
                Text("A site groups plants that share the same conditions.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onClear(); editor = Site() }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create site") }
                    OutlinedButton(onClick = onRefresh, enabled = !state.busy && !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh sites") }
                }
                if (state.loaded && state.sites.isEmpty()) Text("No sites yet. Create a room, garden or terrace for your plants.")
                for (site in state.sites) {
                    Card(Modifier.fillMaxWidth().testTag("site:${site.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(site.fields["name"].orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Text("${if (site.fields["location"] == "indoor") "Indoor" else "Outdoor"} · ${site.plantCount} ${if (site.plantCount == 1) "plant" else "plants"}")
                            if (site.importedIncomplete) Text("Imported site. Complete its conditions.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onClear(); confirming = null; editor = site }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
                                OutlinedButton(onClick = { onClear(); confirming = site }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
        // Progress overlays the top edge, so nothing in the form moves when a save starts or ends.
        if (state.busy || submitting) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    } }
    if (discarding) com.epaflix.fytorium.plants.DiscardDialog(discardTitle.orEmpty(), onKeep = { discarding = false }) { discarding = false; closeEditor() }
    confirming?.let { site ->
        fun dismiss() { if (!submitting && !state.busy) { confirming = null; onClear() } }
        AlertDialog(onDismissRequest = ::dismiss,
            title = { Text("Delete site?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delete \"${site.fields["name"]}\"? This removes the site and its settings permanently. Sites with plants cannot be deleted.")
                    PlantError(state.errors["_form"])
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        submitting = true
                        try { if (onDelete(site.id!!)) confirming = null } finally { submitting = false }
                    }
                }, enabled = writable) { Text("Confirm delete") }
            },
            dismissButton = { TextButton(onClick = ::dismiss, enabled = !submitting && !state.busy) { Text("Cancel") } })
    }
}

@Composable private fun SiteForm(initial: Site, errors: Map<String, String>, enabled: Boolean, onCancel: () -> Unit, onDirty: (() -> Boolean) -> Unit, onSave: (Site) -> Unit) {
    val fields = savedFields(initial.id) { initial.fields }
    var growLight by rememberSaveable(initial.id) { mutableStateOf(initial.growLight) }
    var draft by rememberSaveable(initial.id) { mutableStateOf(initial.draft) }
    fun input() = initial.copy(fields = fields.toMap(), growLight = growLight, draft = draft)
    SideEffect { onDirty { input() != initial } }
    fun field(key: String, value: String) { fields[key] = value }
    // shortcut: copies readSite's longest name and temperature messages to keep their space; upgrade when /api/sites sends its refusal texts or the server wording changes.
    OutlinedTextField(fields["name"].orEmpty(), { field("name", it) }, label = { Text("Name") }, enabled = enabled, isError = errors.containsKey("name"),
        supportingText = { PlantError(errors["name"], keep = "Enter a site name.") }, modifier = Modifier.fillMaxWidth())
    for ((key, choice) in siteChoices) {
        Choice(choice.first, fields[key].orEmpty(), choice.second.map { it to it.replace('_', ' ') }, enabled) { field(key, it) }
        PlantError(errors[key])
    }
    SiteToggle("Grow light", growLight, enabled) { growLight = it }
    PlantError(errors["grow_light"])
    Text("Temperature ranges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text("Warm period: heating-off or summer half of the year. Cold period: heating-on or winter half. Leave unknown bounds blank.")
    for ((key, label) in temperatureFields) {
        OutlinedTextField(fields[key].orEmpty(), { field(key, it) }, label = { Text(label) }, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = errors.containsKey(key),
            supportingText = { PlantError(errors[key], keep = "Enter a whole number of °C or leave blank.") }, modifier = Modifier.fillMaxWidth())
    }
    SiteToggle("Draft", draft, enabled) { draft = it }
    PlantError(errors["draft"])
    Button(onClick = { onSave(input()) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save site") }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    // A failed save's message goes under the buttons, so neither moves when it appears.
    PlantError(errors["_form"])
}

@Composable private fun SiteToggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(value, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}
