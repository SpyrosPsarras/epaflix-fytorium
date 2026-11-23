package com.epaflix.fytorium.planta

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.Heading
import com.epaflix.fytorium.plants.PlantError
import com.epaflix.fytorium.plants.SpeciesCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val importOffline = "Importing needs a connection."
internal const val plantaPortal = "https://getplanta.com/apps/manage"
private val pots = mapOf("plastic" to "Plastic pot", "terracotta" to "Terracotta pot", "ceramic" to "Ceramic pot", "glass" to "Glass pot",
    "metal" to "Metal pot", "wood" to "Wood pot", "fabric" to "Fabric pot", "other" to "Other pot", "in_ground" to "In the ground")
private val soils = mapOf("all_purpose" to "All-purpose soil", "cactus" to "Cactus soil", "orchid" to "Orchid mix", "aroid" to "Aroid mix", "other" to "Other soil")
private val careWords = listOf("water" to "Last watered", "fertilize" to "Last fertilized", "clean_leaves" to "Leaves last cleaned", "repot" to "Last repotted")

// "Plastic pot · 17 cm · All-purpose soil", leaving out what Planta did not say.
internal fun potLine(row: ImportRow): String? = listOfNotNull(pots[row.pot], row.size.takeIf { it.toIntOrNull() != null }?.let { "$it cm" }, soils[row.soil])
    .takeIf { it.isNotEmpty() }?.joinToString(" · ")
// shortcut: timestamps show in the phone's time zone, not the household's; upgrade when CareState carries the household zone.
internal fun careDay(value: String): String {
    val day = if (value.length == 10) java.time.LocalDate.parse(value) else java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return if (day.year == java.time.LocalDate.now().year) dayMonth(day.toString()) else dayMonthYear(day.toString())
}
internal fun careLines(row: ImportRow) = careWords.mapNotNull { (key, words) -> row.care[key]?.let { "$words ${careDay(it)}" } }
private fun photoLine(row: ImportRow) = when (row.coverStatus) {
    "imported" -> "Photo imported"
    "pending" -> "Photo comes with the import"
    "failed" -> "Photo not imported: ${row.coverError.orEmpty()} Fetch again to retry."
    "skipped" -> "Photo skipped"
    // The tile already says when Planta has no photo (Opus W5b-opus-8).
    else -> null
}
private fun plants(n: Int) = if (n == 1) "1 plant" else "$n plants"

@Composable fun PlantaRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit,
    onPlants: () -> Unit, onPlant: (String) -> Unit, onSites: () -> Unit = {}, onReceipt: (String) -> Unit = {}, onUpdateRequired: () -> Unit = {}, answers: Int = 0) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    // Keyed by the member id, never the member object: its hash changes per process (W5b-P29).
    val repository = remember(api, member?.id) { PlantaRepository(api,
        { member != null && member.role == com.epaflix.fytorium.Role.owner && currentCare.member?.id == member.id && !currentCare.updateRequired },
        { currentConnected() }) }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } }
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    com.epaflix.fytorium.household.ReloadOnAnswer(answers, state.loading || state.saving, { repository.state.value.run { readError != null || blocked } }) { withContext(Dispatchers.IO) { repository.refresh() } }
    val online = connected() && !care.updateRequired && care.member?.role == com.epaflix.fytorium.Role.owner
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) { if (online && !wasOnline) reload(); wasOnline = online }
    com.epaflix.fytorium.household.ResumeReload(::reload)
    LaunchedEffect(repository, state.complete, state.rows.map { it.status }) {
        if (state.complete || (state.rows.any { it.status == "committed" } && state.rows.none { it.status in setOf("review", "confirmed") })) withContext(Dispatchers.IO) { repository.checkSites() } }
    LaunchedEffect(repository, state.rows.map { it.id to it.status }) {
        for (row in state.rows.filter { it.status == "review" }) launch(Dispatchers.IO) { repository.suggest(row.id) }
    }
    fun command(action: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { action() } } }
    PlantaScreen(state, online, onBack, onRefresh = ::reload, siteNames = care.plants.mapNotNull { it.siteName }.distinct(), onPull = { withContext(Dispatchers.IO) { repository.refresh() } },
        onFetch = { code -> withContext(Dispatchers.IO) { repository.fetch(code) } },
        onConfirm = { id, key, receipt -> withContext(Dispatchers.IO) { repository.confirm(id, key, receipt) } },
        onSkip = { id -> withContext(Dispatchers.IO) { repository.skip(id) } }, onSearch = { id, query -> command { repository.search(id, query) } },
        // The receipt counts the rows this import committed (W5b-P24).
        onCommit = { withContext(Dispatchers.IO) { val before = repository.state.value.rows.filter { it.status == "committed" }.map { it.id }.toSet()
            if (repository.commit()) repository.state.value.rows.count { it.status == "committed" && it.id !in before } else null } }, onPlants = onPlants, onPlant = onPlant, onSites = onSites, imageApi = api, onReceipt = onReceipt)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun PlantaScreen(state: PlantaState, online: Boolean = true, onBack: () -> Unit = {}, onRefresh: () -> Unit = {}, onPull: suspend () -> Unit = { onRefresh() },
    onFetch: suspend (String) -> Boolean = { false }, onConfirm: suspend (String, Long, String?) -> Unit = { _, _, _ -> }, onSkip: suspend (String) -> Unit = {},
    onSearch: (String, String) -> Unit = { _, _ -> }, onCommit: suspend () -> Int? = { null }, onPlants: () -> Unit = {}, onPlant: (String) -> Unit = {},
    onSites: () -> Unit = {}, imageApi: ApiClient? = null, onReceipt: (String) -> Unit = {}, siteNames: List<String> = emptyList()) {
    // Planta's site name as the household writes it, when it matches one ("kitchen" is Kitchen; Sol W5b-sol-6).
    fun siteOf(row: ImportRow) = siteNames.firstOrNull { it.trim().equals(row.site.trim(), ignoreCase = true) } ?: row.site
    // A Planta code must not enter saved state, even on rotation or a failed fetch.
    var code by remember { mutableStateOf("") }
    var codeOpen by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf(listOf<String>()) }
    val searching = remember { mutableStateMapOf<String, Boolean>() }
    // Each plant's species query outlives its search area being hidden (Astra W5b-astra-5).
    val searches = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val page = rememberScrollState()
    // A review shown again starts empty, which would clamp its restored place to the top; it goes back there once loaded (Sol W6-sol-3).
    var place by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        if (place > 0) { kotlinx.coroutines.withTimeoutOrNull(2_000) { snapshotFlow { page.maxValue }.first { it >= place } }; page.scrollTo(place) }
        snapshotFlow { page.value }.collect { place = it }
    }
    var submitting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(state.rows) { searching.clear() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val ready = state.loaded && !state.saving && !state.blocked && !state.updateRequired && !submitting
    val writable = online && ready
    val reviewRows = state.rows.filter { it.status == "review" }
    // can_commit is household-wide (every row or none), so the bar counts the confirmed rows and uses the flag only to allow the import.
    val importable = state.rows.count { it.status == "confirmed" }
    val canImport = state.rows.any { it.canCommit }
    val skipped = state.rows.count { it.status == "skipped" }
    val reviewTargets = remember(state.rows.map { it.id }) { state.rows.associate { it.id to BringIntoViewRequester() } }
    var viewport by remember { mutableIntStateOf(0) }
    // A page-tall area from the heading down, so the heading lands at the top instead of the bottom edge.
    fun scrollTo(id: String) { scope.launch { reviewTargets[id]?.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, viewport.toFloat())) } }
    // Offline a change explains on tap instead of failing (W5b-P27).
    fun submit(action: suspend () -> Unit) {
        if (!online) { onReceipt(importOffline); return }
        if (submitting || !ready) return
        submitting = true
        scope.launch { try { action() } finally { submitting = false } }
    }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // Go hands focus back too, so no focus ring lands on Back when the field goes (Opus W5b-opus-5).
    fun fetch() { if (code.isBlank()) return; val entered = code; code = ""; searching.clear(); focus.clearFocus(); submit { if (onFetch(entered)) codeOpen = false } }
    BackHandler(onBack = onBack)
    val status = when {
        !online -> "Offline" to "This phone can't reach Fytorium. $importOffline"
        state.readError != null -> "The review didn't load" to state.readError
        state.blocked -> "The review can't be changed right now" to listOfNotNull(state.error, "Try again to reload the review.").joinToString(" ")
        else -> null
    }
    // An import finished earlier reads as finished when the page reopens (Sol W5b-sol-8).
    val done = state.complete || (state.rows.any { it.status == "committed" } && state.rows.none { it.status in setOf("review", "confirmed") })
    val reviewing = state.rows.isNotEmpty() && !done
    Scaffold(topBar = {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { online && (state.readError != null || state.blocked) }) { Text("Import from Planta") } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            PullToRefreshBox(isRefreshing = pulling, onRefresh = { pulling = true; scope.launch { try { onPull() } finally { pulling = false } } }, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().onSizeChanged { viewport = it.height }.verticalScroll(page).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (done) {
                        Heading("Import complete", MaterialTheme.typography.titleLarge)
                        // Off while a fetch or import runs, so leaving cannot cut it short (Sol W6-S15).
                        Button(onClick = onPlants, enabled = !state.saving && !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("View imported plants") }
                        if (state.incompleteSites) {
                            Text("New sites from Planta start with default conditions.")
                            OutlinedButton(onClick = onSites, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check sites") }
                        }
                    }
                    // W5b-P21: the code step leads until a review exists, then folds into one button.
                    if (state.rows.isNotEmpty() && !codeOpen) TextButton(onClick = { codeOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fetch again with a new code") }
                    else {
                        Text("Get a one-time app code from the Planta App Portal, then paste it here. Fytorium reads your plants through Planta's official API, never your Planta password.")
                        TextButton(onClick = { uriHandler.openUri(plantaPortal) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Planta App Portal") }
                        OutlinedTextField(code, { code = it.filterNot(Char::isWhitespace).take(1000) }, label = { Text("One-time app code") }, enabled = writable,
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { fetch() }), modifier = Modifier.fillMaxWidth())
                        Button(onClick = { if (!online) onReceipt(importOffline) else fetch() }, enabled = (writable && code.isNotBlank()) || !online,
                            colors = offButtonColors(!online), modifier = Modifier.heightIn(min = 48.dp).offReason(importOffline.takeIf { !online })) { Text("Fetch plants") }
                        PlantError(state.error.takeIf { !state.blocked })
                        Text("Fetching again updates plants you imported before: species, cultivar, site, pot and soil change to Planta's. A name you gave a plant in Fytorium stays until its name in Planta changes.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.rows.isNotEmpty()) {
                        Heading("Review", MaterialTheme.typography.titleLarge, Modifier.padding(top = 8.dp))
                        val parts = listOfNotNull(
                            state.rows.count { it.status == "confirmed" }.takeIf { it > 0 }?.let { "$it ready" },
                            reviewRows.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "needs" else "need"} a choice" },
                            skipped.takeIf { it > 0 }?.let { "$it skipped" },
                            state.rows.count { it.status == "committed" }.takeIf { it > 0 }?.let { "$it imported" })
                        Text("${plants(state.rows.size)}: ${parts.joinToString(", ")}")
                    }
                    for (row in state.rows.sortedBy { if (it.status == "review") 0 else 1 }) key(row.id) {
                        val target = reviewTargets.getValue(row.id)
                        if (row.status == "review") ReviewCard(searches, row.copy(site = siteOf(row)), state, imageApi, writable, searching[row.id] ?: row.candidates.isEmpty(), target,
                            onToggleSearch = { searching[row.id] = !(searching[row.id] ?: row.candidates.isEmpty()) },
                            onConfirm = { key, receipt -> submit { onConfirm(row.id, key, receipt) } }, onSkip = { submit { onSkip(row.id) } }, onSearch = { onSearch(row.id, it) })
                        else CompactRow(searches, row.copy(site = siteOf(row)), state, imageApi, writable, open = row.id in opened, searchOpen = searching[row.id] ?: false, target = target,
                            onToggle = { opened = if (row.id in opened) opened - row.id else opened + row.id },
                            onToggleSearch = { searching[row.id] = !(searching[row.id] ?: false) },
                            onConfirm = { key, receipt -> submit { onConfirm(row.id, key, receipt) } }, onSkip = { submit { onSkip(row.id) } },
                            onSearch = { onSearch(row.id, it) }, onPlant = onPlant)
                    }
                    if (state.loaded && state.rows.isEmpty() && !done) Text("No plants to review yet. Fetch from Planta to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uriHandler.openUri("https://www.gbif.org") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Species names from GBIF, CC BY 4.0") }
                }
            }
            com.epaflix.fytorium.ReportBusy(state.saving || submitting)
            if (state.loading || state.saving || submitting) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        // W5b-P23: the import bar stays at the bottom while a review is open, and names the plants still to decide.
        if (reviewing) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("import-bar")) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (reviewRows.isNotEmpty()) FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("Still to choose:")
                    for (row in reviewRows.take(2)) TextButton(onClick = { scrollTo(row.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(row.name) }
                    if (reviewRows.size > 2) Text("and ${reviewRows.size - 2} more")
                } else if (skipped > 0 && importable > 0) Text(if (skipped == 1) "1 skipped plant stays out." else "$skipped skipped plants stay out.")
                val label = when { importing -> "Importing\u2026"; importable == 0 && reviewRows.isEmpty() -> "Nothing to import"; importable == 0 -> "Import plants"; else -> "Import ${plants(importable)}" }
                val blockedBy = when { reviewRows.isNotEmpty() -> "Choose a species or skip the plants above first."; !online -> importOffline; else -> null }
                Button(onClick = {
                    if (blockedBy != null) onReceipt(blockedBy)
                    else if (importable > 0 && canImport) submit { importing = true; try { onCommit()?.let { onReceipt("Imported ${plants(it)}"); page.animateScrollTo(0) } } finally { importing = false } }
                }, enabled = !importing && (importable > 0 || reviewRows.isNotEmpty()),
                    colors = if (blockedBy != null) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import-button").semantics { blockedBy?.let { stateDescription = it } }) { Text(label) }
                // A fetch's failure shows under Fetch; the bar shows the rest.
                PlantError(state.error.takeIf { !state.blocked && !codeOpen })
            }
        }
    } }
}

@Composable private fun ReviewCard(searches: androidx.compose.runtime.saveable.SaveableStateHolder, row: ImportRow, state: PlantaState, imageApi: ApiClient?, enabled: Boolean, searchOpen: Boolean, target: BringIntoViewRequester,
    onToggleSearch: () -> Unit, onConfirm: (Long, String?) -> Unit, onSkip: () -> Unit, onSearch: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("import:${row.id}"), border = BorderStroke(2.dp, MaterialTheme.colorScheme.error)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthenticatedThumbnail(imageApi, if (row.hasCover) "/api/import/planta/${row.id}/cover" else null, "Planta photo of ${row.name}", "No Planta photo", 160.dp)
            // The bar's names scroll this heading to the top, so the choices below it show without another scroll (Sol W5b-sol-7).
            Text(row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.bringIntoViewRequester(target).testTag("import-heading:${row.id}").semantics { heading() })
            Text("From Planta: " + listOf(row.scientificName, row.site).filter(String::isNotBlank).joinToString(" · "))
            Text("Choose the species", style = MaterialTheme.typography.titleMedium)
            val suggestion = state.suggestions[row.id]
            for (candidate in (row.candidates + suggestion?.candidates.orEmpty()).distinctBy { it.key }) ImportCandidate(candidate, enabled, imageApi = imageApi,
                suggested = suggestion?.suggestedKey == candidate.key) { onConfirm(candidate.key, null) }
            suggestion?.genus?.let { genus ->
                OutlinedCard(onClick = { onConfirm(genus.key, null) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Not sure: import as ${genus.canonicalName} (any kind)")
                        if (suggestion.suggestedKey == genus.key) Text("Looks most like your photo", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text("You can change the species later.")
            }
            if (row.candidates.isEmpty() && suggestion?.candidates.isNullOrEmpty()) Text("No match found. Search for the species or skip this plant.")
            TextButton(onClick = onToggleSearch, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(when { searchOpen -> "Hide species search"; row.candidates.isEmpty() -> "Search for a species"; else -> "Search for another species" }) }
            if (searchOpen) searches.SaveableStateProvider("search:${row.id}") { SpeciesSearchArea(row, state, imageApi, enabled, onConfirm, onSearch) }
            TextButton(onClick = onSkip, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip plant") }
            Details(row)
        }
    }
}

@Composable private fun CompactRow(searches: androidx.compose.runtime.saveable.SaveableStateHolder, row: ImportRow, state: PlantaState, imageApi: ApiClient?, enabled: Boolean, open: Boolean, searchOpen: Boolean, target: BringIntoViewRequester,
    onToggle: () -> Unit, onToggleSearch: () -> Unit, onConfirm: (Long, String?) -> Unit, onSkip: () -> Unit, onSearch: (String) -> Unit, onPlant: (String) -> Unit) {
    val (badge, readyLook) = when (row.status) { "confirmed" -> "Ready" to true; "skipped" -> "Skipped" to false; else -> "Imported" to false }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(target).testTag("import:${row.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClickLabel = if (open) "Hide details" else "Show details", onClick = onToggle)
            .then(if (readyLook) Modifier.testTag("import-ready:${row.id}") else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AuthenticatedThumbnail(imageApi, if (row.hasCover) "/api/import/planta/${row.id}/cover" else null, "Planta photo of ${row.name}", "No Planta photo", 56.dp)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("import-heading:${row.id}").semantics { heading() })
                Text(listOfNotNull(row.selectedName ?: row.scientificName, row.site).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (readyLook) {
                val readyColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF86EFAC) else Color(0xFF166534)
                Icon(Icons.Filled.CheckCircle, null, tint = readyColor, modifier = Modifier.size(24.dp))
            }
            Text(badge, style = MaterialTheme.typography.labelLarge)
        }
        if (open) Column(Modifier.padding(start = 68.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.status == "confirmed") row.selectedKey?.let { ReferencePhoto(imageApi, it, row.selectedName.orEmpty(), showName = true, displayPhoto = false, commonName = row.selectedCommonName) }
            Details(row)
            if (row.canReview) {
                TextButton(onClick = onToggleSearch, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (row.status == "skipped") "Undo skip" else "Change species or skip") }
                if (searchOpen) {
                    if (row.status == "skipped") for (candidate in row.candidates) ImportCandidate(candidate, enabled, imageApi = imageApi) { onConfirm(candidate.key, null) }
                    if (row.status == "confirmed") TextButton(onClick = onSkip, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip plant") }
                    searches.SaveableStateProvider("search:${row.id}") { SpeciesSearchArea(row, state, imageApi, enabled, onConfirm, onSearch) }
                }
            } else row.plantId?.let { id -> OutlinedButton(onClick = { onPlant(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open imported plant") } }
        }
        HorizontalDivider()
    }
}

@Composable private fun Details(row: ImportRow) {
    row.cultivar?.let { Text("Cultivar: $it") }
    potLine(row)?.let { Text(it) }
    for (line in careLines(row)) Text(line)
    photoLine(row)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun SpeciesSearchArea(row: ImportRow, state: PlantaState, imageApi: ApiClient?, enabled: Boolean, onConfirm: (Long, String?) -> Unit, onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(query, { query = it }, label = { Text("Search species") }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
        modifier = Modifier.fillMaxWidth())
    Button(onClick = { onSearch(query) }, enabled = enabled && query.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Find species") }
    if (state.searchRow == row.id) state.search?.let { result ->
        if (result.candidates.isEmpty()) Text("No species found. Try another name.")
        for (candidate in result.candidates) ImportCandidate(candidate, enabled, imageApi = imageApi) { onConfirm(candidate.key, result.id) }
    }
}

@Composable private fun ImportCandidate(candidate: SpeciesCandidate, enabled: Boolean, imageApi: ApiClient? = null, suggested: Boolean = false, onConfirm: () -> Unit) {
    OutlinedCard(onClick = onConfirm, enabled = enabled,
        border = BorderStroke(if (suggested) 2.dp else 1.dp, if (suggested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "This is ${candidate.headline}" }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReferencePhoto(imageApi, candidate.key, candidate.canonicalName, showName = true, size = 240.dp, commonName = candidate.commonName, matchNote = candidate.matchNote)
            val colours = ButtonDefaults.buttonColors()
            Surface(color = if (enabled) colours.containerColor else colours.disabledContainerColor,
                contentColor = if (enabled) colours.contentColor else colours.disabledContentColor, shape = MaterialTheme.shapes.extraLarge) {
                Text("This is my plant", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            }
            if (suggested) Text("Looks most like your photo", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
