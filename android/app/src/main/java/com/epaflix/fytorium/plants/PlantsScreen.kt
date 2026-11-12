package com.epaflix.fytorium.plants

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.epaflix.fytorium.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    var species by remember { mutableStateOf<Species?>(null) }
    var chosenName by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var formDirty by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val request = repository?.plantState?.collectAsState()?.value ?: PlantsRequestState()
    val enabled = request.enabled(state.member, state.updateRequired) && !saving
    val scope = rememberCoroutineScope()
    val detail = state.detail?.takeIf { it.plant.id == id }
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
        if (page == "form" && formDirty) { discarding = true; return }
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
    LaunchedEffect(page) { if (page != "form") formDirty = false }
    LaunchedEffect(state.member, page) {
        if (page in listOf("species-care", "photos", "identify") && state.member?.role == Role.sitter) page = if (id == null) "list" else "detail"
        loadOptions()
        if (page == "graveyard" && repository != null) graveyard = withContext(Dispatchers.IO) { repository.graveyard() }
    }
    // A restored activity keeps the names page but not its species, so the page loads it again.
    LaunchedEffect(page, detail?.plant?.id) {
        val key = detail?.plant?.attributesJson?.let { JSONObject(it).optLong("gbif_key") }?.takeIf { it > 0 }
        if (page == "names" && species?.key != key && key != null) run { species = withContext(Dispatchers.IO) { repository!!.species(key) } }
    }
    // Reads never change the form's saving gate or discard its remembered input.
    LaunchedEffect(id) { id?.let { refreshDetail(it) } }
    var wasOnline by remember { mutableStateOf(request.online) }
    LaunchedEffect(request.online) {
        if (request.online && !wasOnline) { withContext(Dispatchers.IO) { repository?.refreshPlants() }; id?.let { refreshDetail(it) } }
        wasOnline = request.online
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A refresh the user did not ask for shows nothing, so nothing on the page moves or flashes.
    val latestRefresh by rememberUpdatedState<() -> Unit>({ scope.launch { withContext(Dispatchers.IO) { repository?.refreshPlants() }; id?.let { refreshDetail(it) } } })
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
                if (options != null) { result = null; selection = null; page = when (action) { "repot-form" -> "repot"; "restore" -> "restore"; else -> "change-species" } }
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
                chosenName = candidate.headline
                page = if (identifyReturn == "change-species") "change-species" else "form"
            })
        return
    }
    if (page == "species-care" && detail != null && speciesApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.species.SpeciesCareRoute(speciesApi, JSONObject(detail.plant.attributesJson).getLong("gbif_key"), state, speciesConnected,
            onBack = { page = "detail"; run { refreshDetail(detail.plant.id) } }, onChanged = onCareChanged)
        return
    }
    val sitesApi = photosApi ?: speciesApi
    if (page == "sites" && sitesApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.sites.SitesRoute(sitesApi, state, photosConnected, onBack = { page = "restore"; run { loadOptions() } })
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
    val notices: @Composable () -> Unit = {
        PlantNotice(request, state.updateRequired, state)
        if (request.blocked) OutlinedButton(onClick = { run {
            withContext(Dispatchers.IO) { repository?.refreshPlants() }
            loadOptions()
            id?.let { refreshDetail(it) }
        } }, enabled = !saving && !reading, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) { Text("Try again") }
    }
    val refused = "Fytorium refused the last request. Try again first."
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
            if (!ownBar) {
                TopAppBar(title = { Heading(title) }, navigationIcon = {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                })
                notices()
            }
            when (page) {
                "list", "graveyard" -> PlantsList(state, if (page == "graveyard") graveyard else state.plants, page == "graveyard",
                    onPlant = { from = page; id = it; page = "detail" },
                    onAdd = { repository?.clearPlantErrors(); id = null; selection = null; result = null; run { loadOptions(); if (options != null) page = "search" } },
                    onGraveyard = { page = "graveyard" },
                    onRefresh = {
                        pulling = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { repository?.refreshPlants() }
                                if (page == "graveyard" && repository != null) graveyard = withContext(Dispatchers.IO) { repository.graveyard() }
                            } finally { pulling = false }
                        }
                    }, thumbnails = thumbnails, enabled = enabled, search = if (page == "graveyard") graveQuery else query,
                    focusSearch = focusPending && page == "list", onFocused = { focusPending = false }, onBack = ::back, refreshing = pulling,
                    addBlocked = addBlocked, onBlocked = ::explain, notice = notices)
                "detail" -> if (detail == null) PlantColumn {
                    when {
                        !request.online -> Text("This plant is not saved on this device yet. Connect to load it.")
                        request.errors["_form"] != null -> OutlinedButton(onClick = { id?.let { key -> run { refreshDetail(key) } } }, enabled = !reading,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Loading plant\u2026")
                        }
                    }
                } else PlantDetailScreen(state, detail,
                    onEdit = { repository?.clearPlantErrors(); run { loadOptions(); if (options != null) page = "form" } }, onCommand = ::command,
                    onSpecies = { page = "names" },
                    onUndo = { event -> scope.launch(Dispatchers.IO) { repository?.undoHistory(event) } }, thumbnails = thumbnails, enabled = enabled, options = options, onBack = ::back,
                    onSpeciesCare = speciesApi?.let { { page = "species-care" } }, todayTask = initialTask, onTask = onTaskAction, onBlocked = ::explain, notice = notices,
                    refreshing = pulling, onRefresh = {
                        pulling = true
                        scope.launch { try { withContext(Dispatchers.IO) { repository?.refresh() }; id?.let { refreshDetail(it) } } finally { pulling = false } }
                    }, writeBlocked = writeBlocked)
                "species-care" -> PlantColumn {
                    Text("Connect to load the care defaults for this species.")
                    OutlinedButton(onClick = { id?.let { key -> run { refreshDetail(key) } } }, enabled = !reading && !saving) { Text("Load plant") }
                }
                "search", "change-species" -> Column(Modifier.weight(1f)) {
                    if (photosApi != null && state.member?.role != Role.sitter) OutlinedButton(onClick = { identifyReturn = page; page = "identify" },
                        enabled = photosConnected() && !state.updateRequired && !saving, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) { Text("Identify from photos") }
                    if (page == "change-species") Text("${detail?.plant?.displayName} is currently ${detail?.plant?.scientificName}. Care history, photos and custom intervals will stay. The schedule will use the new species.", Modifier.padding(16.dp))
                    if (page == "change-species" && selection != null) {
                        Text("Chosen species: ${chosenName.ifBlank { species?.scientificName.orEmpty() }}", Modifier.padding(16.dp))
                        Button(onClick = { command("species", JSONObject(selection!!)) }, enabled = enabled, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                            Text("Change ${detail?.plant?.displayName ?: "plant"} to ${chosenName.ifBlank { species?.scientificName.orEmpty() }}")
                        }
                        TextButton(onClick = { selection = null }) { Text("Choose another species") }
                    } else SpeciesPicker(enabled, result, onSearch = { query -> run { result = withContext(Dispatchers.IO) { repository!!.searchSpecies(query) } } }, onSelect = { candidate, searchId, confirmed ->
                        selection = JSONObject().put("gbif_key", candidate.key).put("search_id", searchId).put("confirmed", confirmed).toString()
                        if (page == "change-species") { species = Species(candidate.key, candidate.scientificName, emptyList(), false, false); chosenName = candidate.headline }
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
                    plantName = detail.plant.displayName, onSpeciesCare = speciesApi?.let { { page = "species-care" } }, onBack = ::back, notice = notices) }
                    ?: PlantColumn { Text("Connect to load this plant's schedule.") }
                "restore" -> detail?.let { RestoreForm(it.plant, sites, enabled, { body -> command("restore-save", body) }, onCreateSite = { page = "sites" }) }
                "repot" -> detail?.let { d -> options?.let { PlantRepotForm(d.plant, it, enabled, errors = request.errors) { body -> command("repot", body) } } }
                "names" -> species?.takeIf { detail != null }?.let { s -> options?.let { choices -> SpeciesNames(s, choices, enabled) { action, body -> run(save = true) {
                    val success = withContext(Dispatchers.IO) { repository!!.speciesNameCommand(s.key, action, body) }
                    if (success) scope.launch { species = withContext(Dispatchers.IO) { repository!!.species(s.key) } }
                } } } } ?: PlantColumn { Text("Loading names\u2026") }
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

@Composable internal fun PlantNotice(request: PlantsRequestState, updateRequired: Boolean, care: CareState = CareState()) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (care.offline || care.serverUnavailable) OfflineNotice(care)
        else {
            if (!request.online) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = "Warning", tint = MaterialTheme.colorScheme.tertiary)
                    Text("Actions are unavailable offline. Cached plants remain readable.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            care.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (updateRequired) Text("Update Fytorium. This server needs a newer app.")
        PlantError(request.errors["_form"])
    }
}
