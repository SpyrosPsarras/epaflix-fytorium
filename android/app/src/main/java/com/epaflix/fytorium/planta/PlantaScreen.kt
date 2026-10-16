package com.epaflix.fytorium.planta

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    LaunchedEffect(repository, state.rows.map { it.id to it.status }) {
        for (row in state.rows.filter { it.status == "review" }) launch(Dispatchers.IO) { repository.suggest(row.id) }
    }
    fun command(action: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { action() } } }
    PlantaScreen(state, connected() && !care.updateRequired && care.member?.role == com.epaflix.fytorium.Role.owner,
        onBack, onRefresh = { command { repository.refresh() } }, onFetch = { code -> withContext(Dispatchers.IO) { repository.fetch(code) } },
        onConfirm = { id, key, receipt -> withContext(Dispatchers.IO) { repository.confirm(id, key, receipt) } },
        onSkip = { id -> withContext(Dispatchers.IO) { repository.skip(id) } }, onSearch = { id, query -> command { repository.search(id, query) } },
        onCommit = { withContext(Dispatchers.IO) { repository.commit() } }, onPlants = onPlants, onPlant = onPlant, onSites = onSites, imageApi = api)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantaScreen(state: PlantaState, online: Boolean = true, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onFetch: suspend (String) -> Unit = {}, onConfirm: suspend (String, Long, String?) -> Unit = { _, _, _ -> }, onSkip: suspend (String) -> Unit = {},
    onSearch: (String, String) -> Unit = { _, _ -> }, onCommit: suspend () -> Unit = {}, onPlants: () -> Unit = {}, onPlant: (String) -> Unit = {},
    onSites: () -> Unit = {}, imageApi: ApiClient? = null) {
    // A Planta code must not enter saved state, even on rotation or a failed fetch.
    var code by remember { mutableStateOf("") }
    val searching = remember { mutableStateMapOf<String, Boolean>() }
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(state.rows) { searching.clear() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val writable = online && state.loaded && !state.saving && !state.blocked && !state.updateRequired && !submitting
    val reviewRows = state.rows.filter { it.status == "review" }
    val reviewTargets = remember(state.rows.map { it.id }) { state.rows.associate { it.id to BringIntoViewRequester() } }
    fun scrollToReview(id: String) { scope.launch { reviewTargets.getValue(id).bringIntoView() } }
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
            Button(onClick = { val entered = code; code = ""; searching.clear(); submit { onFetch(entered) } },
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
                Text("Plants marked Needs review need one tap: choose the matching species or skip. Everything else is ready.")
            }
            ReviewSummary(reviewRows, Modifier.testTag("review-summary:top"), ::scrollToReview)
            for (row in state.rows.sortedBy { if (it.status == "review") 0 else 1 }) {
                key(row.id) {
                    val searchOpen = searching[row.id] ?: (row.status == "review" && row.candidates.isEmpty())
                    Card(Modifier.fillMaxWidth().testTag("import:${row.id}"),
                        border = if (row.status == "review") BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.bringIntoViewRequester(reviewTargets.getValue(row.id)).testTag("import-heading:${row.id}").semantics { heading() })
                            if (row.status == "confirmed") {
                                val readyColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF86EFAC) else Color(0xFF166534)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, "Ready to import", tint = readyColor, modifier = Modifier.size(48.dp).testTag("import-ready:${row.id}"))
                                    Text("Ready to import", style = MaterialTheme.typography.headlineSmall, color = readyColor)
                                }
                                row.selectedKey?.let { ReferencePhoto(imageApi, it, row.selectedName.orEmpty(), showName = true, displayPhoto = false) }
                                    ?: Text(row.selectedName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                            AuthenticatedThumbnail(imageApi, if (row.hasCover) "/api/import/planta/${row.id}/cover" else null,
                                "Planta photo of ${row.name}", "No Planta photo", if (row.status == "review") 280.dp else 120.dp)
                            Text(if (row.status == "confirmed") row.site else "${row.scientificName} · ${row.site}")
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
                            if (row.status != "confirmed") Text(when (row.status) {
                                "committed" -> "Imported"
                                "skipped" -> "Skipped. This plant will not be imported."
                                else -> "Needs review"
                            })
                            if (row.canReview) {
                                if (row.status == "review" || (row.status == "skipped" && searchOpen)) {
                                    if (row.candidates.isEmpty()) Text("No match found. Search for the species or skip this plant.")
                                    else Text("Which picture looks like your plant?", style = MaterialTheme.typography.titleMedium)
                                    val suggestion = state.suggestions[row.id]
                                    for (candidate in (row.candidates + suggestion?.candidates.orEmpty()).distinctBy { it.key }) ImportCandidate(candidate, writable, imageApi = imageApi,
                                        suggested = suggestion?.suggestedKey == candidate.key) { submit { onConfirm(row.id, candidate.key, null) } }
                                    suggestion?.genus?.let { genus ->
                                        OutlinedCard(onClick = { submit { onConfirm(row.id, genus.key, null) } }, enabled = writable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                            Column(Modifier.padding(16.dp)) {
                                                Text("Not sure: import as ${genus.canonicalName} (any kind)")
                                                if (suggestion.suggestedKey == genus.key) Text("Looks most like your photo", style = MaterialTheme.typography.titleSmall)
                                            }
                                        }
                                        Text("You can change the species later.")
                                    }
                                }
                                if (row.status == "review") TextButton(onClick = { submit { onSkip(row.id) } }, enabled = writable,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip plant") }
                                TextButton(onClick = { searching[row.id] = !searchOpen }, enabled = writable,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(when (row.status) {
                                        "skipped" -> "Undo skip"
                                        "review" -> if (row.candidates.isEmpty()) "Search for a species" else "Search for another species"
                                        else -> "Change species or skip"
                                    }, style = MaterialTheme.typography.bodySmall) }
                                if (searchOpen) key(row.id) {
                                    if (row.status == "skipped") Text("Undo skip: choose a picture or search for a species below.")
                                    if (row.status == "confirmed") TextButton(onClick = { submit { onSkip(row.id) } }, enabled = writable,
                                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip plant") }
                                    var query by remember { mutableStateOf("") }
                                    OutlinedTextField(query, { query = it }, label = { Text("Search species") }, enabled = writable, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { onSearch(row.id, query) }, enabled = writable && query.isNotBlank(),
                                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Find species") }
                                    if (state.searchRow == row.id) state.search?.let { result ->
                                        if (result.candidates.isEmpty()) Text("No species found. Try another name.")
                                        for (candidate in result.candidates) ImportCandidate(candidate, writable, imageApi = imageApi) {
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
            }
            ReviewSummary(reviewRows, Modifier.testTag("review-summary:bottom"), ::scrollToReview)
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

@Composable private fun ReviewSummary(rows: List<ImportRow>, modifier: Modifier, onPlant: (String) -> Unit) {
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text("${rows.size} ${if (rows.size == 1) "plant still needs" else "plants still need"} review:",
            color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        for (row in rows) TextButton(onClick = { onPlant(row.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(row.name) }
    }
}

@Composable private fun ImportCandidate(candidate: SpeciesCandidate, enabled: Boolean, imageApi: ApiClient? = null, suggested: Boolean = false, onConfirm: () -> Unit) {
    OutlinedCard(onClick = onConfirm, enabled = enabled,
        border = BorderStroke(if (suggested) 2.dp else 1.dp, if (suggested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "This is ${candidate.canonicalName}" }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReferencePhoto(imageApi, candidate.key, candidate.canonicalName, showName = true, size = 240.dp)
            val colours = ButtonDefaults.buttonColors()
            Surface(color = if (enabled) colours.containerColor else colours.disabledContainerColor,
                contentColor = if (enabled) colours.contentColor else colours.disabledContentColor, shape = MaterialTheme.shapes.extraLarge) {
                Text("This is my plant", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            }
            if (suggested) Text("Looks most like your photo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
