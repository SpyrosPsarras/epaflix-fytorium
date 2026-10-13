package com.epaflix.fytorium.plants

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantsScreen(state: CareState, repository: Repository?, thumbnails: Thumbnails? = null, onBack: () -> Unit = {}, initialPlant: String? = null) {
    var page by rememberSaveable { mutableStateOf(if (initialPlant == null) "list" else "detail") }
    var id by rememberSaveable { mutableStateOf(initialPlant) }
    var graveyard by remember { mutableStateOf<List<Plant>>(emptyList()) }
    var options by remember { mutableStateOf<PlantOptions?>(null) }
    var sites by remember { mutableStateOf<List<PlantSite>>(emptyList()) }
    var result by remember { mutableStateOf<SpeciesSearch?>(null) }
    var species by remember { mutableStateOf<Species?>(null) }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val request = repository?.plantState?.collectAsState()?.value ?: PlantsRequestState()
    val enabled = request.enabled(state.member, state.updateRequired) && !saving
    val scope = rememberCoroutineScope()
    val detail = state.detail?.takeIf { it.plant.id == id }
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
        repository?.clearPlantErrors()
        if (page == "list") onBack()
        else if (page in listOf("graveyard", "detail", "search") || id == null) { page = "list"; id = null }
        else page = "detail"
    }
    BackHandler { back() }
    LaunchedEffect(state.member) { loadOptions() }
    // Reads never change the form's saving gate or discard its remembered input.
    LaunchedEffect(id) { id?.let { refreshDetail(it) } }
    var wasOnline by remember { mutableStateOf(request.online) }
    LaunchedEffect(request.online) {
        if (request.online && !wasOnline) { withContext(Dispatchers.IO) { repository?.refreshPlants() }; id?.let { refreshDetail(it) } }
        wasOnline = request.online
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRefresh by rememberUpdatedState<() -> Unit>({ run { withContext(Dispatchers.IO) { repository?.refreshPlants() }; id?.let { refreshDetail(it) } } })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latestRefresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun command(action: String, body: JSONObject) {
        val key = id ?: return
        repository?.clearPlantErrors()
        when (action) {
            "care-form" -> page = "care"
            "repot-form", "restore", "change-species" -> run {
                loadOptions()
                if (options != null) { result = null; selection = null; page = when (action) { "repot-form" -> "repot"; "restore" -> "restore"; else -> "change-species" } }
            }
            else -> run(save = true) {
                val success = withContext(Dispatchers.IO) {
                    if (action == "handle-all") repository!!.handleAll(key, plantsScreen = true)
                    else repository!!.plantCommand(key, if (action == "restore-save") "restore" else action, body)
                }
                if (success) {
                    if (action == "delete") { id = null; page = "list" }
                    else scope.launch { refreshDetail(key); page = "detail" }
                }
            }
        }
    }
    val title = when (page) {
        "list" -> "Plants"; "graveyard" -> "Graveyard"; "detail" -> detail?.plant?.displayName ?: "Plant"
        "search" -> "Add plant"; "form" -> if (id == null) "Add plant" else "Edit plant"
        "change-species" -> "Change species"; "care" -> "Care settings"; "repot" -> "Repot plant"
        "restore" -> "Restore plant"; else -> "Species names"
    }
    Column(Modifier.fillMaxSize()) {
        if (page != "detail" || detail == null) TopAppBar(title = { Heading(title) }, navigationIcon = {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
        if (reading || saving || request.refreshing || request.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        PlantNotice(request, state.updateRequired)
        if (request.blocked) OutlinedButton(onClick = { run {
            withContext(Dispatchers.IO) { repository?.refreshPlants() }
            loadOptions()
            id?.let { refreshDetail(it) }
        } }, enabled = !saving && !reading, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) { Text("Refresh plants") }
        when (page) {
            "list", "graveyard" -> PlantsList(state, if (page == "graveyard") graveyard else state.plants, page == "graveyard",
                onPlant = { id = it; page = "detail" },
                onAdd = { repository?.clearPlantErrors(); id = null; selection = null; result = null; run { loadOptions(); if (options != null) page = "search" } },
                onGraveyard = { page = "graveyard"; run { graveyard = withContext(Dispatchers.IO) { repository!!.graveyard() } } },
                onRefresh = { run { withContext(Dispatchers.IO) { repository!!.refreshPlants() }; if (page == "graveyard") graveyard = withContext(Dispatchers.IO) { repository!!.graveyard() }; loadOptions() } }, thumbnails = thumbnails, enabled = enabled)
            "detail" -> if (detail == null) PlantColumn { Text("No cached details yet. Connect to load this plant.") } else PlantDetailScreen(state, detail,
                onEdit = { repository?.clearPlantErrors(); run { loadOptions(); if (options != null) page = "form" } }, onCommand = ::command,
                onSpecies = { run { loadOptions(); species = withContext(Dispatchers.IO) { repository!!.species(JSONObject(detail.plant.attributesJson).getLong("gbif_key")) }; if (species != null) page = "names" } },
                onUndo = { event -> run(save = true) {
                    val success = withContext(Dispatchers.IO) { repository!!.undoHistory(event) }
                    if (success) scope.launch { refreshDetail(event.plantId) }
                } }, thumbnails = thumbnails, enabled = enabled, options = options, onBack = ::back)
            "search", "change-species" -> Column(Modifier.weight(1f)) {
                if (page == "change-species") Text("${detail?.plant?.displayName} is currently ${detail?.plant?.scientificName}. Care history, photos and custom intervals will stay. The schedule will use the new species.", Modifier.padding(16.dp))
                if (page == "change-species" && selection != null) {
                    Text("Chosen species: ${species?.scientificName.orEmpty()}", Modifier.padding(16.dp))
                    Button(onClick = { command("species", JSONObject(selection!!)) }, enabled = enabled, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) { Text("Save species") }
                    TextButton(onClick = { selection = null }) { Text("Choose another species") }
                } else SpeciesPicker(enabled, result, onSearch = { query -> run { result = withContext(Dispatchers.IO) { repository!!.searchSpecies(query) } } }, onSelect = { candidate, searchId, confirmed ->
                    selection = JSONObject().put("gbif_key", candidate.key).put("search_id", searchId).put("confirmed", confirmed).toString()
                    if (page == "change-species") species = Species(candidate.key, candidate.scientificName, emptyList(), false, false)
                    else page = "form"
                })
            }
            "form" -> options?.let { choices -> PlantForm(if (id == null) null else detail?.plant, choices, sites, enabled, errors = request.errors) { input ->
                selection?.takeIf { id == null }?.let { json ->
                    val chosen = JSONObject(json)
                    listOf("gbif_key", "search_id", "confirmed").forEach { input.put(it, chosen.get(it)) }
                }
                run(save = true) {
                    val previousId = id
                    val saved = withContext(Dispatchers.IO) { repository!!.savePlant(id, input) }
                    if (saved != null) {
                        id = saved.id; selection = null
                        scope.launch { if (previousId == saved.id) refreshDetail(saved.id); page = "detail" }
                    }
                }
            } }
            "care" -> detail?.careSettingsJson?.let { PlantCareSettings(it, enabled) { command("care-settings", it) } }
            "restore" -> detail?.let { RestoreForm(it.plant, sites, enabled) { command("restore-save", it) } }
            "repot" -> detail?.let { d -> options?.let { PlantRepotForm(d.plant, it, enabled, errors = request.errors) { command("repot", it) } } }
            "names" -> species?.let { s -> options?.let { choices -> SpeciesNames(s, choices, enabled) { action, body -> run(save = true) {
                val success = withContext(Dispatchers.IO) { repository!!.speciesNameCommand(s.key, action, body) }
                if (success) scope.launch { species = withContext(Dispatchers.IO) { repository!!.species(s.key) } }
            } } } }
        }
    }
}

@Composable internal fun PlantNotice(request: PlantsRequestState, updateRequired: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (!request.online) Text("Actions are unavailable offline. Cached plants remain readable.")
        if (updateRequired) Text("Update Fytorium. This server needs a newer app.")
        PlantError(request.errors["_form"])
    }
}
