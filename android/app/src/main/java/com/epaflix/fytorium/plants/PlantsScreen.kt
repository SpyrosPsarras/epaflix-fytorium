package com.epaflix.fytorium.plants

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.epaflix.fytorium.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val formPages = setOf("form", "care", "repot")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantsScreen(state: CareState, repository: Repository?, thumbnails: Thumbnails? = null, onBack: () -> Unit = {}, initialPlant: String? = null,
    speciesApi: ApiClient? = null, speciesConnected: () -> Boolean = { false }, onCareChanged: () -> Unit = {},
    photosApi: ApiClient? = null, photosConnected: () -> Boolean = { false }, initialAdd: Boolean = false,
    initialTask: CareTask? = null, returnToToday: Boolean = initialTask != null, onTaskAction: (CareTask, String) -> Unit = { _, _ -> },
    receipts: SnackbarHostState? = null, onReceiptGone: () -> Unit = {}, focusSearch: Boolean = false) {
    var page by rememberSaveable { mutableStateOf(if (initialPlant != null) "detail" else if (initialAdd && state.member?.role != Role.sitter) "search" else "list") }
    var id by rememberSaveable { mutableStateOf(initialPlant) }
    // The list a plant page was opened from, so Back returns there.
    var from by rememberSaveable { mutableStateOf("list") }
    var identifyReturn by rememberSaveable { mutableStateOf("search") }
    val query = rememberSaveable { mutableStateOf("") }
    val graveQuery = rememberSaveable { mutableStateOf("") }
    var focusPending by rememberSaveable { mutableStateOf(focusSearch) }
    var graveyard by remember { mutableStateOf<List<Plant>>(emptyList()) }
    var options by remember { mutableStateOf<PlantOptions?>(null) }
    var sites by remember { mutableStateOf<List<PlantSite>>(emptyList()) }
    var result by remember { mutableStateOf<SpeciesSearch?>(null) }
    var searched by rememberSaveable { mutableStateOf<String?>(null) }
    var species by remember { mutableStateOf<Species?>(null) }
    var chosenName by rememberSaveable { mutableStateOf("") }
    var chosenLabel by rememberSaveable { mutableStateOf("") }
    val namesList = rememberLazyListState()
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var formDirty by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val detail = state.detail?.takeIf { it.plant.id == id }
    val speciesKey = detail?.plant?.attributesJson?.let { JSONObject(it).optLong("gbif_key") }?.takeIf { it > 0 }
    val request = (repository?.plantState?.collectAsState()?.value ?: PlantsRequestState()).let { all ->
        all.copy(reads = all.reads.filterKeys { it in pageReads(page, id, speciesKey, searched != null) }) }
    val enabled = request.enabled(state.member, state.updateRequired) && !saving
    val scope = rememberCoroutineScope()
    val own = remember { SnackbarHostState() }
    val host = receipts ?: own
    fun explain(message: String) { scope.launch { host.showSnackbar(message); onReceiptGone() } }
    fun run(save: Boolean = false, work: suspend () -> Unit) {
        if (repository == null || saving || (save && !enabled)) return
        if (save) saving = true else reading = true
        scope.launch { try { work() } finally { if (save) saving = false else reading = false } }
    }
    suspend fun loadOptions() {
        if (state.member?.role != Role.sitter && repository != null) {
            val loaded = withContext(Dispatchers.IO) { repository.plantOptions() to repository.plantSites() }
            if (loaded.first != null) options = loaded.first
            if (loaded.second != null) sites = loaded.second!!
        }
    }
    suspend fun refreshDetail(key: String) { withContext(Dispatchers.IO) { repository?.openPlant(key, full = true) } }
    fun back() {
        if (page in formPages && formDirty) { discarding = true; return }
        repository?.clearPlantErrors()
        when {
            page == "list" || (page == "detail" && returnToToday) -> onBack()
            page == "graveyard" -> page = "list"
            page == "detail" -> { page = from; id = null }
            page == "sites" -> page = "restore"
            page == "search" || id == null -> { page = "list"; id = null }
            else -> page = "detail"
        }
    }
    BackHandler { back() }
    LaunchedEffect(page) { if (page !in formPages) formDirty = false }
    // A search page opens without the last search's failure, so it cannot flash while the first query loads.
    LaunchedEffect(page) { if (page in listOf("search", "change-species") && searched == null) repository?.clearRead("search") }
    LaunchedEffect(state.member, page) {
        if (page in listOf("species-care", "photos", "identify") && state.member?.role == Role.sitter) page = if (id == null) "list" else "detail"
        loadOptions()
        if (page == "graveyard" && repository != null) graveyard = withContext(Dispatchers.IO) { repository.graveyard() }
    }
    // A restored activity keeps the names page but not its species, so the page loads it again.
    LaunchedEffect(page, detail?.plant?.id) {
        if (page == "names" && species?.key != speciesKey && speciesKey != null) run { species = withContext(Dispatchers.IO) { repository!!.species(speciesKey) } }
    }
    // Reads never change the form's saving gate or discard its remembered input.
    LaunchedEffect(id) { id?.let { refreshDetail(it) } }
    // Try again, a pull, app resume and a reconnect reload what the page shows, so a read that failed there loads once Fytorium answers.
    suspend fun reload() {
        val repo = repository ?: return
        withContext(Dispatchers.IO) { repo.refreshPlants() }
        loadOptions()
        id?.let { refreshDetail(it) }
        if (page == "graveyard") graveyard = withContext(Dispatchers.IO) { repo.graveyard() }
        speciesKey?.takeIf { page == "names" }?.let { key -> withContext(Dispatchers.IO) { repo.species(key) }?.let { species = it } }
        searched?.takeIf { "search" in request.reads }?.let { query -> withContext(Dispatchers.IO) { repo.searchSpecies(query) }?.let { result = it } }
    }
    var wasOnline by remember { mutableStateOf(request.online) }
    LaunchedEffect(request.online) {
        if (request.online && !wasOnline) reload()
        wasOnline = request.online
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A refresh the user did not ask for shows nothing, so nothing on the page moves or flashes.
    val latestRefresh by rememberUpdatedState<() -> Unit>({ scope.launch { reload() } })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latestRefresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun command(action: String, body: JSONObject) {
        val key = id ?: return
        repository?.clearPlantErrors()
        when (action) {
            "photos" -> page = "photos"
            "care-form" -> page = "care"
            "repot-form", "restore", "change-species" -> run {
                loadOptions()
                if (options != null) { result = null; selection = null; searched = null; page = when (action) { "repot-form" -> "repot"; "restore" -> "restore"; else -> "change-species" } }
            }
            "handle-all" -> scope.launch { if (withContext(Dispatchers.IO) { repository?.handleAll(key, plantsScreen = true) } == true) refreshDetail(key) }
            else -> run(save = true) {
                val success = withContext(Dispatchers.IO) { repository!!.plantCommand(key, if (action == "restore-save") "restore" else action, body) }
                if (success) {
                    if (action == "delete") { id = null; page = from }
                    else scope.launch { refreshDetail(key); page = "detail" }
                }
            }
        }
    }
    if (page in listOf("photos", "identify") && photosApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.photos.PhotosRoute(photosApi, state, photosConnected, thumbnails,
            plantId = if (page == "photos") id else null, plantName = if (page == "photos") detail?.plant?.displayName else null,
            identifying = page == "identify", onBack = { page = if (page == "photos") "detail" else identifyReturn },
            onChanged = { scope.launch { withContext(Dispatchers.IO) { repository?.refreshPlants() }; id?.let { refreshDetail(it) } } },
            onSelect = { candidate, searchId, confirmed ->
                selection = JSONObject().put("gbif_key", candidate.key).put("search_id", searchId).put("confirmed", confirmed).toString()
                species = Species(candidate.key, candidate.scientificName, emptyList(), false, false)
                chosenName = candidate.headline; chosenLabel = speciesLabel(candidate.commonName, candidate.canonicalName)
                page = if (identifyReturn == "change-species") "change-species" else "form"
            })
        return
    }
    if (page == "species-care" && detail != null && speciesApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.species.SpeciesCareRoute(speciesApi, JSONObject(detail.plant.attributesJson).getLong("gbif_key"), state, speciesConnected,
            onBack = { page = "detail"; run { refreshDetail(detail.plant.id) } }, onChanged = onCareChanged, onUpdateRequired = { repository?.requireUpdate() })
        return
    }
    val sitesApi = photosApi ?: speciesApi
    if (page == "sites" && sitesApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.sites.SitesRoute(sitesApi, state, photosConnected, onBack = { page = "restore"; run { loadOptions() } }, creating = true)
        return
    }
    val title = when (page) {
        "detail" -> detail?.plant?.displayName ?: "Plant"
        "search" -> "Add plant"; "form" -> if (id == null) "Add plant" else "Edit plant"
        "change-species" -> "Change species"; "care" -> "This plant's schedule"; "repot" -> "Repot plant"
        "restore" -> "Restore ${detail?.plant?.displayName ?: "plant"}"; "species-care" -> "Care defaults for this species"; else -> "Species names"
    }
    ReceiptSnackbar(state.receipt, host, onUndo = { receipt -> scope.launch(Dispatchers.IO) { repository?.undoReceipt(receipt) } },
        onDone = { repository?.clearReceipt(it) }, onGone = onReceiptGone)
    fun retry() = run { reload() }
    val refused = "$refusedLine. Try again first."
    val addBlocked = when {
        !request.online -> "Adding a plant needs a connection."
        state.updateRequired -> "Update Fytorium to add plants."
        request.blocked -> refused
        else -> null
    }
    // History Undo writes through this screen's gate, so a tap names its reasons too.
    val writeBlocked = state.needs("Changes", "make changes", verb = "need", online = request.online) ?: refused.takeIf { request.blocked }
        ?: "Wait for the current save to finish.".takeIf { saving || request.saving }
    val ownBar = page in listOf("list", "graveyard") || (page == "detail" && detail != null) || (page == "care" && detail?.careSettingsJson != null)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!ownBar) TopAppBar(title = { PlantBarTitle(state, request, ::retry) { Heading(title) } }, expandedHeight = barHeight(), navigationIcon = {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
            when (page) {
                "list", "graveyard" -> PlantsList(state, if (page == "graveyard") graveyard else state.plants, page == "graveyard",
                    onPlant = { from = page; id = it; page = "detail" },
                    onAdd = { repository?.clearPlantErrors(); id = null; selection = null; result = null; searched = null; run { loadOptions(); if (options != null) page = "search" } },
                    onGraveyard = { page = "graveyard" },
                    onRefresh = {
                        pulling = true
                        scope.launch { try { reload() } finally { pulling = false } }
                    }, thumbnails = thumbnails, enabled = enabled, search = if (page == "graveyard") graveQuery else query,
                    focusSearch = focusPending && page == "list", onFocused = { focusPending = false }, onBack = ::back, refreshing = pulling,
                    addBlocked = addBlocked, onBlocked = ::explain, request = request, onRetry = ::retry)
                // Offline and failed reads show only in the line, so this body stays the same until the plant loads.
                "detail" -> if (detail == null) PlantColumn {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Loading plant\u2026")
                    }
                } else PlantDetailScreen(state, detail,
                    onEdit = { repository?.clearPlantErrors(); run { loadOptions(); if (options != null) page = "form" } }, onCommand = ::command,
                    onSpecies = { page = "names" },
                    onUndo = { event -> scope.launch(Dispatchers.IO) { repository?.undoHistory(event) } }, thumbnails = thumbnails, enabled = enabled, options = options, onBack = ::back,
                    onSpeciesCare = speciesApi?.let { { page = "species-care" } }, todayTask = initialTask, onTask = onTaskAction, onBlocked = ::explain,
                    refreshing = pulling, onRefresh = {
                        pulling = true
                        scope.launch { try { withContext(Dispatchers.IO) { repository?.refresh() }; reload() } finally { pulling = false } }
                    }, writeBlocked = writeBlocked, request = request, onRetry = ::retry)
                "species-care" -> PlantColumn {
                    Text("Connect to load the care defaults for this species.")
                    OutlinedButton(onClick = { id?.let { key -> run { refreshDetail(key) } } }, enabled = !reading && !saving) { Text("Load plant") }
                }
                "search", "change-species" -> Column(Modifier.weight(1f)) {
                    if (photosApi != null && state.member?.role != Role.sitter) OutlinedButton(onClick = { identifyReturn = page; page = "identify" },
                        enabled = photosConnected() && !state.updateRequired && !saving, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) { Text("Identify from photos") }
                    val current = detail?.plant?.let { plant -> JSONObject(plant.attributesJson).let { speciesLabel(it.nullable("common_name"), it.nullable("canonical_name") ?: plant.scientificName) } }
                    if (page == "change-species") Text("${detail?.plant?.displayName} is currently $current. Care history, photos and custom intervals will stay. The schedule will use the new species.", Modifier.padding(16.dp))
                    if (page == "change-species" && selection != null) {
                        Text("Chosen species: ${chosenLabel.ifBlank { chosenName }}", Modifier.padding(16.dp))
                        Box(Modifier.padding(horizontal = 16.dp)) { PlantError(request.errors["_form"]) }
                        Button(onClick = { command("species", JSONObject(selection!!)) }, enabled = enabled, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                            Text("Change ${detail?.plant?.displayName ?: "plant"} to ${chosenName.ifBlank { species?.scientificName.orEmpty() }}")
                        }
                        TextButton(onClick = { selection = null }) { Text("Choose another species") }
                    } else SpeciesPicker(enabled, result, onSearch = { query -> searched = query; run { result = withContext(Dispatchers.IO) { repository!!.searchSpecies(query) } } }, onSelect = { candidate, searchId, confirmed ->
                        selection = JSONObject().put("gbif_key", candidate.key).put("search_id", searchId).put("confirmed", confirmed).toString()
                        if (page == "change-species") { species = Species(candidate.key, candidate.scientificName, emptyList(), false, false); chosenName = candidate.headline
                            chosenLabel = speciesLabel(candidate.commonName, candidate.canonicalName) }
                        else page = "form"
                    }, imageApi = speciesApi)
                }
                "form" -> options?.let { choices -> PlantForm(if (id == null) null else detail?.plant, choices, sites, enabled, errors = request.errors, onDirty = { formDirty = it }, onSave = { input ->
                    selection?.takeIf { id == null }?.let { json ->
                        val chosen = JSONObject(json)
                        listOf("gbif_key", "search_id", "confirmed").forEach { input.put(it, chosen.get(it)) }
                    }
                    run(save = true) {
                        val previousId = id
                        val saved = withContext(Dispatchers.IO) { repository!!.savePlant(id, input) }
                        if (saved != null) {
                            id = saved.id; selection = null; formDirty = false
                            scope.launch { if (previousId == saved.id) refreshDetail(saved.id); page = "detail" }
                        }
                    }
                }) }
                "care" -> detail?.careSettingsJson?.let { PlantCareSettings(it, enabled, onSave = { body -> command("care-settings", body) }, tasks = detail.tasks,
                    plantName = detail.plant.displayName, onSpeciesCare = speciesApi?.let { { page = "species-care" } }, onBack = ::back,
                    barTitle = { title -> PlantBarTitle(state, request, ::retry, title) }, error = request.errors["_form"], onDirty = { formDirty = it }) }
                    ?: PlantColumn { Text("Connect to load this plant's schedule.") }
                "restore" -> detail?.let { RestoreForm(it.plant, sites, enabled, { body -> command("restore-save", body) }, onCreateSite = { page = "sites" }, error = request.errors["_form"]) }
                "repot" -> detail?.let { d -> options?.let { PlantRepotForm(d.plant, it, enabled, errors = request.errors, onSave = { body -> command("repot", body) }, onDirty = { dirty -> formDirty = dirty }) } }
                "names" -> species?.takeIf { detail != null }?.let { s -> options?.let { choices -> SpeciesNames(s, choices, enabled, list = namesList, error = request.errors["_form"], onCommand = { action, body -> run(save = true) {
                    val receipt = when (action) {
                        "add" -> "Added the name \u201c${body.getString("name")}\u201d"
                        "refresh" -> null
                        else -> s.names.firstOrNull { it.id == action }?.let { "Removed the name \u201c${it.name}\u201d" }
                    }
                    val success = withContext(Dispatchers.IO) { repository!!.speciesNameCommand(s.key, action, body, receipt) }
                    if (success) scope.launch { species = withContext(Dispatchers.IO) { repository!!.species(s.key) } }
                } }) } } ?: PlantColumn { Text("Loading names\u2026") }
            }
        }
        // Progress the user asked for overlays the top edge, so nothing below it moves.
        if (reading || saving || request.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        if (receipts == null) SnackbarHost(own, Modifier.align(Alignment.BottomCenter))
    }
    if (discarding) AlertDialog(onDismissRequest = { discarding = false },
        title = { Text(if (id == null) "Discard this new plant?" else "Discard changes to ${detail?.plant?.displayName ?: "this plant"}?") },
        text = { Text("Your changes are not saved yet.") },
        confirmButton = { TextButton(onClick = { discarding = false; formDirty = false; back() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text("Keep editing") } })
}

internal fun CareState.connectionNotice(online: Boolean): String? = when {
    offline || !online -> "This phone can't reach Fytorium. You can still see the plants saved on this phone. Reconnect and refresh to make changes."
    serverUnavailable -> "Fytorium didn't answer. You can still see the plants saved on this phone. Actions are unavailable until Fytorium responds. Refresh to try again."
    else -> null
}
private const val refusedLine = "Fytorium refused the last request"
// The reads each page shows, so its line names only failures of what it shows.
private fun pageReads(page: String, id: String?, speciesKey: Long?, searched: Boolean) = listOfNotNull(id?.takeIf { page !in listOf("list", "graveyard") }?.let { "plant/$it" }) +
    when (page) {
        "list" -> listOf("plants", "choices")
        "graveyard" -> listOf("graveyard")
        "care" -> emptyList()
        "search", "change-species" -> listOfNotNull("search".takeIf { searched })
        "restore" -> listOf("sites")
        "form" -> listOf("choices", "sites")
        "names" -> listOf("choices", "species/$speciesKey")
        else -> listOf("choices")
    }
// One line under the top bar title carries every page-level status, so none moves or covers the page.
internal fun CareState.plantStatus(request: PlantsRequestState): Pair<String, String>? {
    val reads = request.reads.values
    val online = request.online && reads.none { it.offline }
    val refusals = reads.filter { it.refused }.map { it.message } + listOfNotNull(request.errors["_form"]?.takeIf { request.refused || request.blocked })
    return connectionLine(online)?.let { it to connectionNotice(online).orEmpty() } ?: when {
        request.serverUnavailable || reads.any { !it.refused } -> "Fytorium isn't responding" to "Fytorium didn't answer the last plant request. Try again when it responds."
        request.blocked || refusals.isNotEmpty() ->
            refusedLine to (refusals.distinct() + listOfNotNull("Plant changes stay off until Try again succeeds.".takeIf { request.blocked })).joinToString("\n")
        else -> message?.let { it to it }
    }
}
@Composable internal fun PlantBarTitle(care: CareState, request: PlantsRequestState, onRetry: () -> Unit, title: @Composable () -> Unit) {
    val status = care.plantStatus(request)
    BarTitle(status?.first, status?.second.orEmpty(), retry = onRetry.takeIf { status?.first == refusedLine || request.reads.isNotEmpty() }, title = title)
}
