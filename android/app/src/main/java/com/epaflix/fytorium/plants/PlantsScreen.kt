package com.epaflix.fytorium.plants

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

private val formPages = setOf("search", "form", "care", "repot", "names", "change-species", "restore")
// Pages a form opens and returns from, so the form's changes stay unsaved while one is open.
private val formSubpages = setOf("species-care", "identify", "sites")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantsScreen(state: CareState, repository: Repository?, thumbnails: Thumbnails? = null, onBack: () -> Unit = {}, initialPlant: String? = null,
    speciesApi: ApiClient? = null, speciesConnected: () -> Boolean = { false }, onCareChanged: () -> Unit = {},
    photosApi: ApiClient? = null, photosConnected: () -> Boolean = { false }, initialAdd: Boolean = false,
    initialTask: CareTask? = null, returnToToday: Boolean = initialTask != null, onTaskAction: (CareTask, String) -> Unit = { _, _ -> },
    receipts: SnackbarHostState? = null, onReceiptGone: () -> Unit = {}, focusSearch: Boolean = false, onFormDirty: (() -> String?) -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf(if (initialPlant != null) "detail" else if (initialAdd && state.member?.role != Role.sitter) "search" else "list") }
    var id by rememberSaveable { mutableStateOf(initialPlant) }
    // The list a plant page was opened from, so Back returns there.
    var from by rememberSaveable { mutableStateOf("list") }
    var identifyReturn by rememberSaveable { mutableStateOf("search") }
    val query = rememberSaveable { mutableStateOf("") }
    val graveQuery = rememberSaveable { mutableStateOf("") }
    var focusPending by rememberSaveable { mutableStateOf(focusSearch) }
    var graveyard by remember { mutableStateOf<List<Plant>>(emptyList()) }
    var options by rememberSaveable { mutableStateOf<PlantOptions?>(null) }
    var sites by rememberSaveable { mutableStateOf<List<PlantSite>>(emptyList()) }
    var result by rememberSaveable { mutableStateOf<SpeciesSearch?>(null) }
    var searched by rememberSaveable { mutableStateOf<String?>(null) }
    var answeredQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var answerVersion by rememberSaveable { mutableIntStateOf(0) }
    // The latest submission until its request ends; a request cut short by recreation is sent again.
    var pendingSearch by rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    fun clearSearch() { result = null; searched = null; answeredQuery = null; pendingSearch = false }
    var species by rememberSaveable { mutableStateOf<Species?>(null) }
    var namesKey by rememberSaveable { mutableStateOf<Long?>(null) }
    val namesDraft = rememberSaveable { mutableStateOf("") }
    var chosenName by rememberSaveable { mutableStateOf("") }
    var chosenLabel by rememberSaveable { mutableStateOf("") }
    var chosenScientific by rememberSaveable { mutableStateOf("") }
    // Where the new plant's species came from, so Change returns there with its query, results and photos.
    var pickedFrom by rememberSaveable { mutableStateOf("search") }
    var sitesReturn by rememberSaveable { mutableStateOf("restore") }
    // Identify's first photo, copied into app storage so it survives until the plant is saved or discarded.
    var keepPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var keepPhoto by rememberSaveable { mutableStateOf(true) }
    fun dropKeepPhoto() { keepPhotoPath?.let { java.io.File(it).delete() }; keepPhotoPath = null; keepPhoto = true }
    fun choose(candidate: SpeciesCandidate) {
        chosenName = candidate.headline.ifBlank { candidate.scientificName }; chosenLabel = speciesLabel(candidate.commonName, candidate.canonicalName)
        chosenScientific = candidate.identificationCanonicalName ?: candidate.canonicalName
    }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    // The open form's check of what was typed, read at the moment something leaves it, so Back right after a keystroke still asks.
    var formChanged by remember { mutableStateOf({ false }) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    // A page opened from a form or the plant page returns to it as the user left it, with what was typed, chosen or unfolded.
    val forms = rememberSaveableStateHolder()
    val context = androidx.compose.ui.platform.LocalContext.current
    val detailScroll = key(id) { rememberSaveable(saver = PlantPageScroll.Saver) { PlantPageScroll() } }
    var speciesCareFrom by rememberSaveable { mutableStateOf("detail") }
    var leavingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreSite by rememberSaveable { mutableStateOf<String?>(null) }
    var subpageDraft by remember { mutableStateOf<() -> String?>({ null }) }
    // The plant photos page returns to the card the user left after the viewer, the form and the plant page.
    val photoGrid = rememberSaveable(id, saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    var reloading by remember { mutableStateOf(false) }
    var reloadedAt by remember { mutableIntStateOf(0) }
    val detail = state.detail?.takeIf { it.plant.id == id }
    var savedPlant by rememberSaveable(id) { mutableStateOf<Plant?>(null) }
    var savedCare by rememberSaveable(id) { mutableStateOf<String?>(null) }
    SideEffect { detail?.plant?.let { savedPlant = it }; detail?.careSettingsJson?.let { savedCare = it } }
    val careJson = detail?.careSettingsJson ?: savedCare
    val plant = detail?.plant ?: savedPlant?.takeIf { it.id == id }
    val speciesKey = plant?.attributesJson?.let { JSONObject(it).optLong("gbif_key") }?.takeIf { it > 0 }
    val request = (repository?.plantState?.collectAsState()?.value ?: PlantsRequestState()).let { all ->
        all.copy(reads = all.reads.filterKeys { it in pageReads(page, id, namesKey, searched != null) }) }
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
    suspend fun loadSpecies(key: Long) { withContext(Dispatchers.IO) { repository?.species(key) }?.takeIf { it.key == namesKey }?.let { species = it } }
    // A species chosen for a new plant or a species change is unsaved too, however it was chosen.
    fun unsaved() = formChanged() || (selection != null && (page == "change-species" || (page == "form" && id == null)))
    fun follow(link: String) {
        if (link == "species-care") speciesCareFrom = "care"
        if (link == "identify") identifyReturn = page
        page = link
    }
    // A link out of a form with changes asks first; the form keeps them for the way back.
    fun leaveFor(link: String) { if (unsaved()) leavingFor = link else follow(link) }
    fun back() {
        if (page in formPages && unsaved()) { discarding = true; return }
        repository?.clearPlantErrors()
        if (page == "search" || page == "change-species") clearSearch()
        when {
            page == "list" || (page == "detail" && returnToToday) -> onBack()
            page == "graveyard" -> page = "list"
            page == "detail" -> { forms.removeState("detail:$id"); page = from; id = null }
            page == "sites" -> page = sitesReturn
            page == "search" || id == null -> { page = "list"; id = null }
            else -> page = "detail"
        }
    }
    BackHandler { back() }
    LaunchedEffect(page) {
        if (page !in formPages && page !in formSubpages) formChanged = { false }
        if (page !in listOf("care", "species-care")) id?.let { forms.removeState("care:$it") }
        if (page !in listOf("search", "change-species", "identify", "sites") && !(page == "form" && id == null)) {
            listOf("search", "change-species").forEach { forms.removeState("search:$it") }
            forms.removeState("identify")
        }
    }
    val discardBody = if (id == null) "Your changes to this new plant are not saved yet." else "Your changes to ${plant?.displayName ?: "this plant"} are not saved yet."
    val currentDirty by rememberUpdatedState(onFormDirty)
    // The shell asks the same question before a tab or notification leaves a form with changes.
    SideEffect { onFormDirty { subpageDraft() ?: discardBody.takeIf { unsaved() } } }
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    // A search page opens without the last search's failure, so it cannot flash while the first query loads.
    LaunchedEffect(page) { if (page in listOf("search", "change-species") && searched == null) repository?.clearRead("search") }
    LaunchedEffect(state.member, page) {
        if (page in listOf("species-care", "photos", "photo-form", "identify") && state.member?.role == Role.sitter) page = if (id == null) "list" else "detail"
        loadOptions()
        if (page == "graveyard" && repository != null) graveyard = withContext(Dispatchers.IO) { repository.graveyard() }
    }
    // Adding a typed name to the species it was typed for is still valid, so a species change elsewhere must not drop it.
    LaunchedEffect(page, speciesKey) {
        if (page != "names") { namesKey = null; namesDraft.value = "" }
        else snapshotFlow { namesDraft.value.isNotBlank() }.collect { typed -> if (!typed || namesKey == null) namesKey = speciesKey }
    }
    // Every entry reads the plant's species, so a species change, names changed elsewhere and a restored activity all show the loaded names.
    LaunchedEffect(page, namesKey) {
        namesKey?.takeIf { page == "names" }?.let { key -> run { loadSpecies(key) } }
    }
    // Reads never change the form's saving gate or discard its remembered input. Every entry and return to the plant page reads the plant, so changes made elsewhere meanwhile show.
    LaunchedEffect(id, page == "detail") { id?.takeIf { page == "detail" }?.let { refreshDetail(it) } }
    suspend fun loadSearch(query: String) {
        if (searching || searched != query) return
        searching = true
        pendingSearch = true
        try {
            val answer = withContext(Dispatchers.IO) { repository?.searchSpecies(query) }
            if (searched == query) pendingSearch = false
            if (answer != null && searched == query) {
                result = answer
                answeredQuery = query
                answerVersion++
            }
        } finally { searching = false }
    }
    LaunchedEffect(page) { searched?.takeIf { pendingSearch && page in listOf("search", "change-species") }?.let { loadSearch(it) } }
    // Try again, a pull, app resume and a reconnect reload what the page shows, so a read that failed there loads once Fytorium answers.
    suspend fun reload() {
        val repo = repository ?: return
        withContext(Dispatchers.IO) { repo.refreshPlants() }
        loadOptions()
        id?.let { refreshDetail(it) }
        if (page == "graveyard") graveyard = withContext(Dispatchers.IO) { repo.graveyard() }
        namesKey?.takeIf { page == "names" }?.let { loadSpecies(it) }
        // The failure is read after the other reloads, so a newer search that answered meanwhile is not sent again.
        searched?.takeIf { page in listOf("search", "change-species") && "search" in repo.plantState.value.reads }?.let { loadSearch(it) }
    }
    // Once Fytorium answers a request from outside the last such reload, a page whose own reads it left unanswered loads them again without a tap.
    LaunchedEffect(request.answers) {
        if (!reloading && !pulling && request.answers > reloadedAt && request.reads.values.any { !it.refused && !it.offline }) {
            reloading = true
            scope.launch { try { reload() } finally { reloadedAt = repository?.plantState?.value?.answers ?: 0; reloading = false } }
        }
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
            "photos", "photo-form" -> page = action
            "care-form" -> page = "care"
            "repot-form", "restore", "change-species" -> run {
                loadOptions()
                if (options != null) { clearSearch(); selection = null; restoreSite = null; page = when (action) { "repot-form" -> "repot"; "restore" -> "restore"; else -> "change-species" } }
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
    if (page in listOf("photos", "photo-form", "identify") && photosApi != null && state.member != null && state.member.role != Role.sitter) {
        val photos = page != "identify"
        // Identify keeps its photos and results while the add form is open, so Change and Back return to them.
        forms.SaveableStateProvider(if (photos) "photos:$id" else "identify") { com.epaflix.fytorium.photos.PhotosRoute(photosApi, state, photosConnected, thumbnails,
            plantId = if (photos) id else null, plantName = if (photos) plant?.displayName else null, formOnly = page == "photo-form",
            identifying = page == "identify", onBack = { page = if (photos) "detail" else identifyReturn }, onUpdateRequired = { repository?.requireUpdate() },
            onFormDirty = { subpageDraft = it }, onReceipt = { repository?.showReceipt(it) },
            cached = { repository?.cachedJson(it) }, keep = { resource, json -> repository?.saveJson(resource, json) }, grid = photoGrid,
            onChanged = { scope.launch { withContext(Dispatchers.IO) { repository?.refreshPlants() }; id?.let { refreshDetail(it) } } },
            onFirstPhoto = { uri -> if (identifyReturn != "change-species" && uri != null) scope.launch {
                val copy = withContext(Dispatchers.IO) { runCatching { com.epaflix.fytorium.photos.keepDraftPhoto(context, android.net.Uri.parse(uri)) }.getOrNull() }
                keepPhotoPath?.takeIf { it != copy?.path }?.let { java.io.File(it).delete() }
                keepPhotoPath = copy?.path; keepPhoto = true
            } },
            onSelect = { candidate, searchId, confirmed ->
                selection = JSONObject().put("gbif_key", candidate.key).put("search_id", searchId).put("confirmed", confirmed).toString()
                choose(candidate); pickedFrom = "identify"
                page = if (identifyReturn == "change-species") "change-species" else "form"
            }) }
        return
    }
    if (page == "species-care" && plant != null && speciesApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.species.SpeciesCareRoute(speciesApi, JSONObject(plant.attributesJson).getLong("gbif_key"), state, speciesConnected,
            onBack = { page = speciesCareFrom; if (page == "care") run { refreshDetail(plant.id) } }, onChanged = onCareChanged, onUpdateRequired = { repository?.requireUpdate() },
            onReceipt = ::explain, answers = request.answers, onFormDirty = { subpageDraft = it })
        return
    }
    val sitesApi = photosApi ?: speciesApi
    if (page == "sites" && sitesApi != null && state.member != null && state.member.role != Role.sitter) {
        com.epaflix.fytorium.sites.SitesRoute(sitesApi, state, photosConnected, onBack = { page = sitesReturn; run { loadOptions() } }, creating = true,
            onCreated = { restoreSite = it }, onFormDirty = { subpageDraft = it }, onUpdateRequired = { repository?.requireUpdate() }, answers = request.answers)
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
    val ownBar = page in listOf("list", "graveyard") || (page == "detail" && detail != null) || (page == "care" && careJson != null)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val pinnedTo = species?.takeIf { page == "names" && it.key == namesKey && namesKey != speciesKey }?.let { it.commonName ?: it.canonicalName }
            val namesNote = pinnedTo?.takeIf { formChanged() }?.let { pinned -> detail?.plant?.let { plant ->
                val now = endSentence(JSONObject(plant.attributesJson).let { it.nullable("common_name") ?: it.nullable("canonical_name") } ?: plant.scientificName)
                "This plant is now $now You're adding a name for ${endSentence(pinned)}" to "Add or clear the name to see the names for $now" } }
            if (!ownBar) TopAppBar(title = { PlantBarTitle(state, request, ::retry, namesNote) { Heading(title) } }, expandedHeight = barHeight(), navigationIcon = {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
            when (page) {
                "list", "graveyard" -> PlantsList(state, if (page == "graveyard") graveyard else state.plants, page == "graveyard",
                    onPlant = { from = page; id = it; page = "detail" },
                    onAdd = { repository?.clearPlantErrors(); id = null; selection = null; clearSearch(); run { loadOptions(); if (options != null) page = "search" } },
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
                } else forms.SaveableStateProvider("detail:$id") { PlantDetailScreen(state, detail,
                    onEdit = { repository?.clearPlantErrors(); run { loadOptions(); if (options != null) page = "form" } }, onCommand = ::command,
                    onSpecies = { namesKey = speciesKey; page = "names" },
                    onUndo = { event -> scope.launch(Dispatchers.IO) { repository?.undoHistory(event) } }, thumbnails = thumbnails, enabled = enabled, options = options, onBack = ::back,
                    onSpeciesCare = speciesApi?.let { { speciesCareFrom = "detail"; page = "species-care" } }, todayTask = initialTask, onTask = onTaskAction, onBlocked = ::explain,
                    refreshing = pulling, onRefresh = {
                        pulling = true
                        scope.launch { try { withContext(Dispatchers.IO) { repository?.refresh() }; reload() } finally { pulling = false } }
                    }, writeBlocked = writeBlocked, request = request, onRetry = ::retry, scroll = detailScroll) }
                "species-care" -> PlantColumn {
                    Text("Connect to load the care defaults for this species.")
                    OutlinedButton(onClick = { id?.let { key -> run { refreshDetail(key) } } }, enabled = !reading && !saving) { Text("Load plant") }
                }
                "search", "change-species" -> Column(Modifier.weight(1f)) {
                    val identify: (() -> Unit)? = if (photosApi != null && state.member?.role != Role.sitter) { { leaveFor("identify") } } else null
                    val identifyEnabled = photosConnected() && !state.updateRequired && !saving
                    val current = plant?.takeIf { page == "change-species" }?.let { p ->
                        val attributes = JSONObject(p.attributesJson)
                        val label = speciesLabel(attributes.nullable("common_name"), attributes.nullable("canonical_name") ?: p.scientificName)
                        "${p.displayName} is currently ${endSentence(label)} Care history, photos and custom intervals will stay. The schedule will use the new species."
                    }
                    if (page == "change-species" && selection != null) {
                        identify?.let { Box(Modifier.padding(horizontal = 16.dp)) { IdentifyFromPhotos(identifyEnabled, it) } }
                        current?.let { Text(it, Modifier.padding(16.dp)) }
                        Text("Chosen species: ${chosenLabel.ifBlank { chosenName }}", Modifier.padding(16.dp))
                        Button(onClick = { command("species", JSONObject(selection!!)) }, enabled = enabled, modifier = Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                            Text("Change ${detail?.plant?.displayName ?: "plant"} to $chosenName")
                        }
                        Box(Modifier.padding(horizontal = 16.dp)) { PlantError(request.errors["_form"]) }
                        TextButton(onClick = { selection = null }) { Text("Choose another species") }
                    } else forms.SaveableStateProvider("search:$page") {
                        SpeciesPicker(enabled, result, onSearch = { query ->
                            if (!searching) { searched = query; run { loadSearch(query) } }
                        },
                            onSelect = { candidate, searchId, confirmed ->
                                selection = JSONObject().put("gbif_key", candidate.key).put("search_id", searchId).put("confirmed", confirmed).toString()
                                choose(candidate)
                                if (page != "change-species") { pickedFrom = "search"; dropKeepPhoto(); page = "form" }
                            }, imageApi = speciesApi, onDirty = { formChanged = it }, autoFocus = page == "search", searching = searching,
                            answeredQuery = answeredQuery, answerVersion = answerVersion, onIdentify = identify, identifyEnabled = identifyEnabled,
                            currentSpecies = current, searchEnabled = state.member != null && state.member.role != Role.sitter && !state.updateRequired && !saving && !request.saving)
                    }
                }
                "form" -> options?.takeIf { id == null || plant != null }?.let { choices -> forms.SaveableStateProvider("form:${id ?: "new"}") { PlantForm(if (id == null) null else plant, choices, sites, enabled,
                    errors = request.errors, onDirty = { formChanged = it }, today = state.today?.today?.let(java.time.LocalDate::parse) ?: java.time.LocalDate.now(),
                    species = selection?.takeIf { id == null }?.let { json -> { ChosenSpecies(speciesApi, JSONObject(json).getLong("gbif_key"), chosenName, chosenScientific) {
                        page = pickedFrom
                    } } },
                    nameStandIn = chosenName.takeIf { id == null && it.isNotBlank() }, onCreateSite = { sitesReturn = "form"; leaveFor("sites") }.takeIf { id == null },
                    createdSite = restoreSite.takeIf { id == null }, blocked = addBlocked.takeIf { id == null }, onBlocked = ::explain,
                    keepPhoto = keepPhotoPath?.takeIf { id == null }?.let { path -> { KeepPhoto(path, keepPhoto, enabled) { keepPhoto = it } } }, onSave = { input ->
                    selection?.takeIf { id == null }?.let { json ->
                        val chosen = JSONObject(json)
                        listOf("gbif_key", "search_id", "confirmed").forEach { input.put(it, chosen.get(it)) }
                    }
                    run(save = true) {
                        val previousId = id
                        val saved = withContext(Dispatchers.IO) { repository!!.savePlant(id, input) }
                        if (saved != null) {
                            val photo = keepPhotoPath?.takeIf { previousId == null && keepPhoto && photosApi != null }
                            id = saved.id; selection = null; formChanged = { false }; forms.removeState("form:new")
                            if (photo != null) scope.launch {
                                val uploaded = withContext(Dispatchers.IO) { com.epaflix.fytorium.photos.PhotoRepository(photosApi!!, { true }, photosConnected, saved.id)
                                    .let { photos -> photos.options() && photos.upload(context, android.net.Uri.fromFile(java.io.File(photo)), "", "", photos.state.value.today.orEmpty()) } }
                                if (!uploaded) repository?.showReceipt("Added ${saved.displayName} to ${saved.siteName ?: "your household"}. Its photo didn't upload.")
                                dropKeepPhoto(); refreshDetail(saved.id)
                            } else dropKeepPhoto()
                            scope.launch { if (previousId == saved.id) refreshDetail(saved.id); page = "detail" }
                        }
                    }
                }) } }
                "care" -> careJson?.let { json -> forms.SaveableStateProvider("care:$id") { PlantCareSettings(json, enabled, onSave = { body -> command("care-settings", body) }, tasks = detail?.tasks.orEmpty(),
                    plantName = plant?.displayName.orEmpty(), onSpeciesCare = speciesApi?.let { { leaveFor("species-care") } }, onBack = ::back,
                    barTitle = { title -> PlantBarTitle(state, request, ::retry, title = title) }, error = request.errors["_form"], onDirty = { formChanged = it }) } }
                    ?: PlantColumn { Text("Connect to load this plant's schedule.") }
                "restore" -> plant?.let { RestoreForm(it, sites, enabled, { body -> command("restore-save", body) }, onCreateSite = { sitesReturn = "restore"; leaveFor("sites") }, error = request.errors["_form"],
                    chosen = { restoreSite }, onChoose = { site -> restoreSite = site }, onDirty = { formChanged = it }) }
                "repot" -> plant?.let { p -> options?.let { PlantRepotForm(p, it, enabled, errors = request.errors, onSave = { body -> command("repot", body) }, onDirty = { formChanged = it }) } }
                // A pinned draft keeps its place until the page follows the plant's new species.
                "names" -> key(plant?.id, namesKey) { val namesList = rememberLazyListState()
                species?.takeIf { plant != null && it.key == namesKey }?.let { s -> options?.let { choices -> SpeciesNames(s, choices, enabled, list = namesList, error = request.errors["_form"], target = pinnedTo, draft = namesDraft,
                    onDirty = { formChanged = it }, onCommand = { action, body, onSuccess -> run(save = true) {
                    val receipt = when (action) {
                        "add" -> "Added the name \u201c${body.getString("name")}\u201d" + pinnedTo?.let { " to $it" }.orEmpty()
                        "refresh" -> null
                        else -> s.names.firstOrNull { it.id == action }?.let { "Removed the name \u201c${it.name}\u201d" }
                    }
                    val success = withContext(Dispatchers.IO) { repository!!.speciesNameCommand(s.key, action, body, receipt) }
                    if (success) { onSuccess(); scope.launch { loadSpecies(s.key) } }
                } }) } } ?: PlantColumn { Text("Loading names\u2026") } }
            }
        }
        // Progress the user asked for overlays the top edge, so nothing below it moves.
        if (reading || saving || searching || request.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        if (receipts == null) SnackbarHost(own, Modifier.align(Alignment.BottomCenter))
    }
    leavingFor?.let { link ->
        val name = detail?.plant?.displayName ?: "this plant"
        val (title, text, go) = when (link) {
            "species-care" -> Triple("Open care defaults for this species?", "Your changes to $name's schedule are not saved yet. They stay here when you come back.", "Open care defaults")
            "sites" -> if (sitesReturn == "form") Triple("Create a site?", "${chosenName.ifBlank { "This plant" }} is not added yet. Your choices stay here when you come back.", "Create a site")
                else Triple("Create a site?", "$name is not restored yet. If you come back without creating a site, your choice stays. A site you create is chosen instead.", "Create a site")
            else -> Triple("Identify from photos?", "Your species search for ${if (page == "change-species") name else "this new plant"} stays here when you come back.", "Identify from photos")
        }
        AlertDialog(onDismissRequest = { leavingFor = null }, title = { DialogTitle(title) }, text = { DialogBody { Text(text) } },
            confirmButton = { TextButton(onClick = { leavingFor = null; follow(link) }) { Text(go) } },
            dismissButton = { TextButton(onClick = { leavingFor = null }) { Text("Keep editing") } })
    }
    val newPlant = page == "form" && id == null
    if (discarding) DiscardDialog(if (newPlant) "Discard this plant?" else "Discard changes?", if (newPlant) "${chosenName.ifBlank { "This plant" }} is not added yet." else discardBody,
        onKeep = { discarding = false }) {
        discarding = false; formChanged = { false }; selection = null
        if (newPlant) { forms.removeState("form:new"); dropKeepPhoto() }
        back()
    }
}

@Composable internal fun DiscardDialog(title: String, body: String, onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep,
        title = { DialogTitle(title) }, text = { DialogBody { Text(body) } },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep editing") } })
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
@Composable internal fun PlantBarTitle(care: CareState, request: PlantsRequestState, onRetry: () -> Unit, note: Pair<String, String>? = null, title: @Composable () -> Unit) {
    val own = care.plantStatus(request)
    // A status takes the line, so its explanation carries the note too.
    val status = own.withNote(note)
    BarTitle(status?.first, status?.second.orEmpty(), retry = onRetry.takeIf { status?.first == refusedLine || request.reads.isNotEmpty() }, title = title)
}
