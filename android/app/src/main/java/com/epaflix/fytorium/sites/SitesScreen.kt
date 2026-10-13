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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.epaflix.fytorium.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SitesRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member) {
        SiteRepository(api, { currentCare.member == member && member != null && member.role != com.epaflix.fytorium.Role.sitter },
            { currentConnected() && !currentCare.updateRequired })
    }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    SitesScreen(state, connected() && !care.updateRequired, onBack = onBack,
        onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onClear = repository::clearErrors,
        onSave = { withContext(Dispatchers.IO) { repository.save(it) } },
        onDelete = { withContext(Dispatchers.IO) { repository.delete(it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SitesScreen(state: SiteState, online: Boolean = true, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onClear: () -> Unit = {}, onSave: suspend (Site) -> Boolean = { false }, onDelete: suspend (String) -> Boolean = { false }) {
    var editor by remember { mutableStateOf<Site?>(null) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writable = online && state.loaded && !state.busy && !state.blocked && !state.updateRequired && !submitting
    fun closeEditor() { editor = null; onClear() }
    BackHandler { if (editor != null) closeEditor() else onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(editor?.let { if (it.id == null) "New site" else "Edit site" } ?: "Sites") },
            navigationIcon = {
                IconButton(onClick = { if (editor != null) closeEditor() else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!online) Text("Actions are unavailable offline. Reconnect and refresh to make changes.")
            if (state.updateRequired) Text("Update Fytorium. This server needs a newer app.")
            if (state.busy || submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (editor != null) {
                if (state.blocked || !online) OutlinedButton(onClick = onRefresh, enabled = !state.busy && !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh sites") }
                key(editor!!.id) {
                    SiteForm(editor!!, state.errors, writable, onCancel = ::closeEditor, onSave = { input ->
                        scope.launch {
                            submitting = true
                            try { if (onSave(input)) editor = null } finally { submitting = false }
                        }
                    })
                }
            } else {
                Text("A site groups plants that share the same conditions.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onClear(); editor = Site() }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create site") }
                    OutlinedButton(onClick = onRefresh, enabled = !state.busy && !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh sites") }
                }
                if (state.loaded && state.sites.isEmpty()) Text("No sites yet. Create a room, garden or terrace for your plants.")
                if (confirming == null) SiteError(state.errors["_form"])
                for (site in state.sites) {
                    Card(Modifier.fillMaxWidth().testTag("site:${site.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(site.fields["name"].orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Text("${if (site.fields["location"] == "indoor") "Indoor" else "Outdoor"} · ${site.plantCount} ${if (site.plantCount == 1) "plant" else "plants"}")
                            if (site.importedIncomplete) Text("Imported site. Complete its conditions.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onClear(); confirming = null; editor = site }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
                                OutlinedButton(onClick = { onClear(); confirming = site.id }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    state.sites.firstOrNull { it.id == confirming }?.let { site ->
        fun dismiss() { if (!submitting && !state.busy) { confirming = null; onClear() } }
        AlertDialog(onDismissRequest = ::dismiss,
            title = { Text("Delete site?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delete \"${site.fields["name"]}\"? This removes the site and its settings permanently. Sites with plants cannot be deleted.")
                    SiteError(state.errors["_form"])
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

@Composable private fun SiteError(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable private fun SiteForm(initial: Site, errors: Map<String, String>, enabled: Boolean, onCancel: () -> Unit, onSave: (Site) -> Unit) {
    var input by remember(initial) { mutableStateOf(initial) }
    fun field(key: String, value: String) { input = input.copy(fields = input.fields + (key to value)) }
    SiteError(errors["_form"])
    OutlinedTextField(input.fields["name"].orEmpty(), { field("name", it) }, label = { Text("Name") },
        enabled = enabled, isError = errors.containsKey("name"), supportingText = errors["name"]?.let { { SiteError(it) } }, modifier = Modifier.fillMaxWidth())
    for ((key, choice) in siteChoices) {
        Choice(choice.first, input.fields[key].orEmpty(), choice.second.map { it to it.replace('_', ' ') }, enabled) { field(key, it) }
        SiteError(errors[key])
    }
    SiteToggle("Grow light", input.growLight, enabled) { input = input.copy(growLight = it) }
    SiteError(errors["grow_light"])
    Text("Temperature ranges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text("Warm period: heating-off or summer half of the year. Cold period: heating-on or winter half. Leave unknown bounds blank.")
    for ((key, label) in temperatureFields) {
        OutlinedTextField(input.fields[key].orEmpty(), { field(key, it) }, label = { Text(label) }, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = errors.containsKey(key),
            supportingText = errors[key]?.let { { SiteError(it) } }, modifier = Modifier.fillMaxWidth())
    }
    SiteToggle("Draft", input.draft, enabled) { input = input.copy(draft = it) }
    SiteError(errors["draft"])
    Button(onClick = { onSave(input) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save site") }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
}

@Composable private fun SiteToggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(value, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}
