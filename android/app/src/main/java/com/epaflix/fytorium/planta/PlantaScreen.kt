package com.epaflix.fytorium.planta

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.SpeciesCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun PlantaRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit,
    onPlants: () -> Unit, onPlant: (String) -> Unit, onSites: () -> Unit = {}) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member) { PlantaRepository(api,
        { member != null && member.role == com.epaflix.fytorium.Role.owner && currentCare.member == member && !currentCare.updateRequired },
        { currentConnected() }) }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    fun command(action: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { action() } } }
    PlantaScreen(state, connected() && !care.updateRequired && care.member?.role == com.epaflix.fytorium.Role.owner,
        onBack, onRefresh = { command { repository.refresh() } }, onFetch = { code -> withContext(Dispatchers.IO) { repository.fetch(code) } },
        onConfirm = { id, key, receipt -> withContext(Dispatchers.IO) { repository.confirm(id, key, receipt) } },
        onSkip = { id -> withContext(Dispatchers.IO) { repository.skip(id) } }, onSearch = { id, query -> command { repository.search(id, query) } },
        onCommit = { withContext(Dispatchers.IO) { repository.commit() } }, onPlants = onPlants, onPlant = onPlant, onSites = onSites)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantaScreen(state: PlantaState, online: Boolean = true, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onFetch: suspend (String) -> Unit = {}, onConfirm: suspend (String, Long, String?) -> Unit = { _, _, _ -> }, onSkip: suspend (String) -> Unit = {},
    onSearch: (String, String) -> Unit = { _, _ -> }, onCommit: suspend () -> Unit = {}, onPlants: () -> Unit = {}, onPlant: (String) -> Unit = {},
    onSites: () -> Unit = {}) {
    // A Planta code must not enter saved state, even on rotation or a failed fetch.
    var code by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val writable = online && state.loaded && !state.saving && !state.blocked && !state.updateRequired && !submitting
    fun submit(action: suspend () -> Unit) {
        if (submitting || !writable) return
        submitting = true
        scope.launch { try { action() } finally { submitting = false } }
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Import from Planta") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Create a one-time app code in the Planta App Portal. Fytorium reads the official API, not your password.")
            Text("A new fetch replaces this review and updates Planta-sourced species, nickname, cultivar, site, pot and soil, including your edits. Local settings stay. Covers are downloaded only once.")
            if (!online) Text("Actions are unavailable offline. Reconnect and refresh to make changes.")
            if (state.updateRequired) Text("Update Fytorium. This server needs a newer app.")
            if (state.loading || state.saving || submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            OutlinedTextField(code, { code = it.take(1000) }, label = { Text("One-time app code") }, enabled = writable,
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
            Button(onClick = { val entered = code; code = ""; searching = null; submit { onFetch(entered) } },
                enabled = writable && code.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Fetch Planta plants") }
            OutlinedButton(onClick = onRefresh, enabled = online && !state.loading && !state.saving && !submitting && !state.updateRequired,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh review") }
            if (state.complete) {
                Text("Import complete.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = onPlants, modifier = Modifier.heightIn(min = 48.dp)) { Text("View imported plants") }
            }
            if (state.loaded && state.rows.isEmpty()) Text("No staged plants. Fetch from Planta to start a review.")
            if (state.rows.isNotEmpty()) {
                Text("Review plants", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("Confirm a species or skip each plant. This review survives a restart. A failed commit can be retried without another app code.")
            }
            for (row in state.rows) {
                Card(Modifier.fillMaxWidth().testTag("import:${row.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text("${row.scientificName} · ${row.site}")
                        row.cultivar?.let { Text("Cultivar: $it") }
                        Text("Pot: ${row.pot.replace('_', ' ')} · ${row.size} cm · Soil: ${row.soil.replace('_', ' ')}")
                        for ((task, date) in row.care) Text("Last ${task.replace('_', ' ')}: $date")
                        Text(when (row.coverStatus) {
                            "imported" -> "Cover imported"
                            "pending" -> "Cover will be downloaded when importing"
                            "failed" -> "Cover not imported: ${row.coverError.orEmpty()} Fetch again to retry the cover."
                            "skipped" -> "Cover skipped"
                            else -> "No Planta cover"
                        })
                        Text(when (row.status) {
                            "committed" -> "Imported"
                            "confirmed" -> "Confirmed species: ${row.selectedName.orEmpty()}"
                            "skipped" -> "Skipped"
                            else -> "Needs review"
                        })
                        if (row.canReview) {
                            if (row.status == "review" || searching == row.id) {
                                Text("Confirm the species you meant, or search for another.")
                                if (row.candidates.isEmpty()) Text("No species candidates. Search again or skip this plant.")
                                for (candidate in row.candidates) ImportCandidate(candidate, writable) { submit { onConfirm(row.id, candidate.key, null) } }
                            }
                            OutlinedButton(onClick = { searching = if (searching == row.id) null else row.id }, enabled = writable,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Search again") }
                            OutlinedButton(onClick = { submit { onSkip(row.id) } }, enabled = writable && row.status != "skipped",
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip plant") }
                            if (searching == row.id) key(row.id) {
                                var query by remember { mutableStateOf("") }
                                OutlinedTextField(query, { query = it }, label = { Text("Search species") }, enabled = writable, modifier = Modifier.fillMaxWidth())
                                Button(onClick = { onSearch(row.id, query) }, enabled = writable && query.isNotBlank(),
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Find species") }
                                if (state.searchRow == row.id) state.search?.let { result ->
                                    if (result.candidates.isEmpty()) Text("No species found. Try another name.")
                                    for (candidate in result.candidates) ImportCandidate(candidate, writable, searched = true) {
                                        submit { onConfirm(row.id, candidate.key, result.id) }
                                    }
                                }
                            }
                        } else row.plantId?.let { id ->
                            OutlinedButton(onClick = { onPlant(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open imported plant") }
                        }
                    }
                }
            }
            if (state.rows.isNotEmpty()) Button(onClick = { submit { onCommit() } }, enabled = writable && state.rows.any { it.canCommit },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Import confirmed plants") }
            TextButton(onClick = { uriHandler.openUri("https://www.gbif.org") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Species names from GBIF, CC BY 4.0")
            }
            Text("New sites use default conditions.")
            TextButton(onClick = onSites, modifier = Modifier.heightIn(min = 48.dp)) { Text("Complete them in Sites") }
        }
    }
}

@Composable private fun ImportCandidate(candidate: SpeciesCandidate, enabled: Boolean, searched: Boolean = false, onConfirm: () -> Unit) {
    Text("${candidate.scientificName} · ${candidate.matchType?.lowercase() ?: "stored species"}" + (candidate.confidence?.let { " · $it% confidence" } ?: ""))
    if (candidate.requiresConfirmation) Text("Confirmation required")
    Button(onClick = onConfirm, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Confirm ${if (searched) "searched " else ""}${candidate.canonicalName}")
    }
}
