package com.epaflix.fytorium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.epaflix.fytorium.plants.connectionNotice
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.epaflix.fytorium.feed.*
import com.epaflix.fytorium.plants.currentTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun homeFeedVisible(tab: String, taskView: String, feedSettings: Boolean) =
    tab == "Today" && taskView == "today" && !feedSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun AppShell(loggedIn: Boolean, updateRequired: Boolean, busy: Boolean = false, message: String? = null,
                         phoneOnline: Boolean = true, minAppVersion: String? = null, onCheckAgain: () -> Unit = {}, onObtainium: (() -> Unit)? = null,
                         onLogin: () -> Unit = {}, onLogout: () -> Unit = {}, onCopyDiagnostics: () -> Unit = {},
                         careState: CareState? = null, controller: CareController? = null, thumbnails: Thumbnails? = null,
                         feed: FeedRepository? = null,
                         sitesApi: ApiClient? = null, sitesConnected: () -> Boolean = { false }, homeRequest: Int = 0, deviceOnline: Boolean = sitesConnected()) {
    val loadedFeed = feed?.state?.collectAsState()?.value
    val feedState = loadedFeed?.let { if (it.member == careState?.member) it else FeedState(member = careState?.member) }
    val scope = rememberCoroutineScope()
    var feedSettings by rememberSaveable { mutableStateOf(false) }
    fun openFeedSettings() { feedSettings = true; scope.launch(Dispatchers.IO) { feed?.loadLocation() } }
    BackHandler(enabled = feedSettings) { feedSettings = false }
    var feedPlant by rememberSaveable { mutableStateOf<String?>(null) }
    var todayTaskKey by rememberSaveable { mutableStateOf<String?>(null) }
    fun openTask(occurrence: String?) { todayTaskKey = occurrence; controller?.open(occurrence) }
    // The repository moves the opened occurrence with this device's writes. The saved key follows it only so a restarted process can hand it back.
    val openedKey = todayTaskKey?.let { careState?.openedOccurrence ?: it }
    LaunchedEffect(openedKey) { if (todayTaskKey != null) todayTaskKey = openedKey }
    LaunchedEffect(controller) { if (careState?.openedOccurrence == null) controller?.open(todayTaskKey) }
    val todayTask = careState?.currentTask(openedKey)
    val todayScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val upcomingScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val moreScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var tab by rememberSaveable { mutableStateOf("Today") }
    var household by rememberSaveable { mutableStateOf<String?>(null) }
    var sites by rememberSaveable { mutableStateOf(false) }
    var journalPlantId by rememberSaveable { mutableStateOf<String?>(null) }
    // Journal +: "" is the plant picker, a plant id its photo form.
    var journalAdd by rememberSaveable { mutableStateOf<String?>(null) }
    val pickerQuery = rememberSaveable { mutableStateOf("") }
    val journalGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val pickerGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var unmatched by rememberSaveable { mutableStateOf(false) }
    var plantaImport by rememberSaveable { mutableStateOf(false) }
    var careSpecies by rememberSaveable { mutableStateOf<Long?>(null) }
    var addPlant by rememberSaveable { mutableStateOf(false) }
    var plantsSearch by rememberSaveable { mutableStateOf(false) }
    var taskView by rememberSaveable { mutableStateOf("today") }
    var notifications by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    // A Plants, Sites or Species care form names its discard question here, read when a tab or a notification leaves it, so it asks the same as its Back.
    var plantsDiscard by remember { mutableStateOf<() -> String?>({ null }) }
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    val homeHandled = key(homeRequest) { rememberSaveable { mutableStateOf(false) } }
    // Discard drops the Plants form even when the tap stays on Plants, so no form keeps changes the shell no longer asks about.
    var plantsDiscards by rememberSaveable { mutableIntStateOf(0) }
    fun select(title: String) { tab = title; addPlant = false; plantsSearch = false; notifications = false; about = false; sites = false; plantaImport = false; household = null; unmatched = false; careSpecies = null; feedSettings = false; feedPlant = null; openTask(null); journalPlantId = null; journalAdd = null }
    fun go(target: String) { select(if (target == "home") "Today" else target); if (target == "home") taskView = "today" }
    fun leave(target: String) { val title = plantsDiscard(); if (title != null) { leaving = title; destination = target } else go(target) }
    LaunchedEffect(homeRequest) { if (homeRequest > 0 && !homeHandled.value) { homeHandled.value = true; leave("home") } }
    var previousMemberId by rememberSaveable { mutableStateOf(careState?.member?.id) }
    var previousRole by rememberSaveable { mutableStateOf(careState?.member?.role) }
    LaunchedEffect(loggedIn, careState?.member) {
        val member = careState?.member
        val memberChanged = previousMemberId != null && member != null && previousMemberId != member.id
        val becameSitter = previousRole != null && previousRole != Role.sitter && member?.role == Role.sitter
        if (!loggedIn || memberChanged) {
            notifications = false
            todayScroll.scrollTo(0); upcomingScroll.scrollTo(0); moreScroll.scrollTo(0)
        }
        if (!loggedIn || memberChanged || member?.role == Role.sitter) {
            addPlant = false
            if (!loggedIn || memberChanged || tab == "Journal") {
                tab = "Today"; taskView = "today"; about = false
            }
            sites = false; unmatched = false; careSpecies = null; feedSettings = false; household = null; journalPlantId = null; journalAdd = null
            if (!loggedIn || memberChanged || becameSitter) { feedPlant = null; openTask(null); leaving = null; destination = null }
        }
        if (!loggedIn || memberChanged || (member != null && member.role != Role.owner)) plantaImport = false
        if (!loggedIn) { previousMemberId = null; previousRole = null }
        else if (member != null) { previousMemberId = member.id; previousRole = member.role }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feed, lifecycle, loggedIn, updateRequired, tab, taskView, feedSettings, careState?.member) {
        if (loggedIn && !updateRequired && careState != null && homeFeedVisible(tab, taskView, feedSettings)) feed?.poll(lifecycle)
    }
    BackHandler(enabled = about) { about = false }
    val receipts = remember { SnackbarHostState() }
    val receipt = receipts.currentSnackbarData
    val receiptsOnBar = remember { mutableSetOf<SnackbarData>() }
    var receiptFadedIn by remember { mutableStateOf<SnackbarData?>(null) }
    var barPaused by remember { mutableStateOf(false) }
    var receiptsGone by remember { mutableIntStateOf(0) }
    LaunchedEffect(receiptsGone) { if (receiptsGone > 0) { barPaused = true; delay(500); barPaused = false } }
    val showTabs = !WindowInsets.isImeVisible
    Scaffold(topBar = {
        if (!loggedIn || updateRequired || (careState == null) || (tab == "More" && !sites && !notifications && !plantaImport && !unmatched && household == null && !feedSettings)) {
            val line = careState?.takeIf { loggedIn && !updateRequired && !about }?.connectionLine(deviceOnline)
            TopAppBar(title = { BarTitle(line, careState?.connectionNotice(deviceOnline).orEmpty(), retry = { controller?.refresh(); Unit }.takeIf { line != null && deviceOnline }) {
                Text(if (!loggedIn || updateRequired) "Fytorium" else if (about) "About" else tab, style = MaterialTheme.typography.titleMedium) } },
                navigationIcon = { if (loggedIn && !updateRequired && about) IconButton(onClick = { about = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }
    }, bottomBar = {
        val tabBar: @Composable (Modifier) -> Unit = { modifier ->
            NavigationBar(modifier) {
                listOf("Today" to Icons.Default.Check, "Plants" to Icons.Default.Eco,
                    "Journal" to Icons.Default.Book, "More" to Icons.Default.Menu).filter { careState?.member?.role != Role.sitter || it.first != "Journal" }.forEach { (title, icon) ->
                    NavigationBarItem(selected = tab == title, onClick = { leave(title) },
                        icon = { Icon(icon, contentDescription = null) }, label = { Text(title) })
                }
            }
        }
        if (loggedIn && !updateRequired) Box {
            if (showTabs) tabBar(Modifier)
            // Today's receipts take the navigation bar's place, so the list above never changes size or hides a row.
            // The cover fades with the receipt, so the bar never shows a blank frame between its items and the receipt.
            SnackbarHost(receipts, if (showTabs) Modifier.matchParentSize() else Modifier.fillMaxWidth()) { data ->
                DisposableEffect(data) { receiptsOnBar += data; onDispose { receiptsOnBar -= data } }
                // shortcut: mirrors Material 1.3.2's private snackbar fade timings (150 ms in, after 75 ms out), re-check when Material is upgraded or exposes a fade-finished signal.
                LaunchedEffect(data) { delay(if (receiptsOnBar.size > 1) 225 else 150); receiptFadedIn = data }
                Surface(if (showTabs) Modifier.fillMaxSize() else Modifier.fillMaxWidth(), color = NavigationBarDefaults.containerColor) {
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { if (it != SwipeToDismissBoxValue.Settled) data.dismiss(); true })
                    SwipeToDismissBox(swipe, modifier = Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets)
                        .wrapContentHeight(Alignment.CenterVertically, unbounded = true), backgroundContent = {}) { BarSnackbar(data) }
                }
            }
            // Until the receipt has faded in, taps go to an invisible copy of the bar, so a tab the user still sees switches.
            if (showTabs && receipt != null && receiptFadedIn != receipt) tabBar(Modifier.alpha(0f).clearAndSetSemantics {})
            // A tap meant for a receipt that has just gone must not switch tabs.
            if (showTabs && barPaused) Box(Modifier.matchParentSize().testTag("bar-paused").pointerInput(Unit) {})
        }
        leaving?.let { body -> com.epaflix.fytorium.plants.DiscardDialog(if (body == "The location you typed is not saved.") "Discard these coordinates?" else "Discard changes?", body, onKeep = { leaving = null; destination = null }) {
            val target = destination
            leaving = null; destination = null; plantsDiscards++; controller?.repository?.clearPlantErrors(); target?.let(::go) } }
    }) { scaffoldPadding ->
        val padding = if (showTabs) scaffoldPadding else object : PaddingValues by scaffoldPadding {
            override fun calculateBottomPadding() = 0.dp
        }
        if (loggedIn && !updateRequired && tab == "More" && notifications && careState?.member != null && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                // Not keyed by member: a member change closes this screen in the shell, and a key flip during restore would drop open dialogs and the time being picked.
                com.epaflix.fytorium.notifications.NotificationRoute(sitesApi, careState, sitesConnected, onBack = { notifications = false },
                    onReceipt = { text -> scope.launch { receipts.showSnackbar(text); receiptsGone++ } }, onUpdateRequired = { controller?.repository?.requireUpdate() },
                    answers = controller?.repository?.plantState?.collectAsState()?.value?.answers ?: 0, live = { controller?.repository?.state?.value },
                    cached = { controller?.repository?.cachedJson(it, careState.member.id) },
                    keep = { resource, json -> controller?.repository?.saveJson(resource, json, careState.member.id) })
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && tab == "More" && plantaImport && careState?.member?.role == Role.owner && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                key(careState.member) {
                    com.epaflix.fytorium.planta.PlantaRoute(sitesApi, careState, sitesConnected, onBack = { plantaImport = false },
                        onPlants = { plantaImport = false; journalPlantId = null; tab = "Plants"; controller?.refresh() },
                        onPlant = { plantaImport = false; journalPlantId = it; tab = "Plants"; controller?.refresh() },
                        onSites = { plantaImport = false; sites = true })
                }
            }
            return@Scaffold
        }
        val householdArea = com.epaflix.fytorium.household.HouseholdArea.entries.firstOrNull { it.name == household }
        if (loggedIn && !updateRequired && tab == "More" && householdArea != null && careState?.member != null && sitesApi != null && careState.member.role != Role.sitter && (householdArea != com.epaflix.fytorium.household.HouseholdArea.Members || careState.member.role == Role.owner)) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                key(careState.member, householdArea) { com.epaflix.fytorium.household.HouseholdRoute(sitesApi, householdArea, careState, sitesConnected) { household = null } }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && tab == "More" && unmatched && careState?.member != null && careState.member.role != Role.sitter && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                key(careSpecies) {
                    com.epaflix.fytorium.species.SpeciesCareRoute(sitesApi, careSpecies, careState, sitesConnected,
                        onBack = { if (careSpecies != null) careSpecies = null else unmatched = false },
                        onChanged = { controller?.refresh() }, onSpecies = { careSpecies = it }, onUpdateRequired = { controller?.repository?.requireUpdate() },
                        onReceipt = { text -> scope.launch { receipts.showSnackbar(text); receiptsGone++ } },
                        answers = controller?.repository?.plantState?.collectAsState()?.value?.answers ?: 0, onFormDirty = { plantsDiscard = it })
                }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && feedSettings && feed != null && careState?.member?.role in listOf(Role.owner, Role.family)) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                // Not keyed by member: a member change closes this screen in the shell, and a key flip during restore would drop the typed draft.
                com.epaflix.fytorium.feed.WeatherFeedRoute(feed, careState, deviceOnline, onBack = { feedSettings = false },
                    onReceipt = { text -> scope.launch { receipts.showSnackbar(text); receiptsGone++ } }, onFormDirty = { plantsDiscard = it },
                    onUpdateRequired = { controller?.repository?.requireUpdate() }, answers = controller?.repository?.plantState?.collectAsState()?.value?.answers ?: 0)
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && tab == "More" && sites && careState?.member != null && careState.member.role != Role.sitter && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                com.epaflix.fytorium.sites.SitesRoute(sitesApi, careState, sitesConnected, onBack = { sites = false }, onFormDirty = { plantsDiscard = it },
                    onUpdateRequired = { controller?.repository?.requireUpdate() })
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && careState?.member != null && careState.member.role != Role.sitter && tab == "Journal" && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                // Journal receipts and explanations show over the navigation bar, as on Today and Plants.
                ReceiptSnackbar(careState.receipt, receipts, onUndo = {}, onDone = { controller?.repository?.clearReceipt(it) }, onGone = { receiptsGone++ })
                val adding = journalAdd
                val picked = adding?.takeIf { it.isNotEmpty() }?.let { id -> careState.plants.firstOrNull { it.id == id } }
                if (adding == "" || (adding != null && picked == null)) {
                    BackHandler { journalAdd = null }
                    com.epaflix.fytorium.plants.PlantsList(careState, careState.plants, false, onPlant = { journalAdd = it }, onAdd = {}, onGraveyard = {},
                        onRefresh = { controller?.refresh(pulled = true) }, thumbnails = thumbnails, search = pickerQuery, onBack = { journalAdd = null },
                        refreshing = controller?.pulling?.collectAsState()?.value == true, chooseTitle = "Choose a plant", grid = pickerGrid)
                } else key(careState.member, plantsDiscards, picked?.id) { com.epaflix.fytorium.photos.PhotosRoute(sitesApi, careState, sitesConnected, thumbnails,
                    plantId = picked?.id, plantName = picked?.displayName, formOnly = picked != null,
                    onBack = { if (picked != null) journalAdd = "" else tab = "Today" }, onPlant = { journalPlantId = it; tab = "Plants" },
                    onChanged = { controller?.refresh() }, onUpdateRequired = { controller?.repository?.requireUpdate() }, onFormDirty = { plantsDiscard = it },
                    onReceipt = { controller?.repository?.showReceipt(it) }, onAdd = { journalAdd = "" }, onUploaded = { journalAdd = null },
                    cached = { controller?.repository?.cachedJson(it) }, keep = { resource, json -> controller?.repository?.saveJson(resource, json) },
                    grid = if (picked == null) journalGrid else androidx.compose.foundation.lazy.grid.rememberLazyGridState()) }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && careState != null && tab in listOf("Today", "Plants")) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (tab == "Today") TaskScreen(careState, taskView, onView = { taskView = it },
                    scrollState = if (taskView == "upcoming") upcomingScroll else todayScroll,
                    onOpenTask = { openTask(it.occurrence); feedPlant = it.plantId; tab = "Plants" },
                    onRefresh = { controller?.refresh(pulled = true) }, refreshing = controller?.pulling?.collectAsState()?.value == true,
                    onAction = { task, action -> controller?.act(task, action) },
                    onHandleType = { controller?.handleType(it) }, onHandlePlant = { controller?.handleAll(it) }, onSettings = { controller?.settings(it) == true }, thumbnails = thumbnails,
                    onUndoReceipt = { controller?.undoReceipt(it) }, onReceiptDone = { controller?.clearReceipt(it) },
                    onSearch = { addPlant = false; feedPlant = null; journalPlantId = null; plantsSearch = true; tab = "Plants" },
                    onAddPlant = { addPlant = true; plantsSearch = false; tab = "Plants" }, onImport = { tab = "More"; plantaImport = true }, deviceOnline = deviceOnline,
                    onRetry = { controller?.refresh() }, receipts = receipts, onReceiptGone = { receiptsGone++ },
                    feed = feedState, feedContent = { shown ->
                        HomeFeed(shown, careState.today?.today, sites = careState.plants.associate { it.id to it.siteName },
                            onOpen = { href -> if (href.startsWith("/plants/")) { feedPlant = href.removePrefix("/plants/"); tab = "Plants" } else openFeedSettings() },
                            onWater = { scope.launch(Dispatchers.IO) { if (feed?.water() == true) controller?.rainWatered(feed.state.value.weather?.lastBatchId) } },
                            onUndo = { scope.launch(Dispatchers.IO) { feed?.undoRain() } })
                    })
                else key(careState.member?.id, careState.member?.role == Role.sitter, feedPlant, journalPlantId, addPlant, plantsDiscards) { com.epaflix.fytorium.plants.PlantsScreen(careState, controller?.repository, thumbnails,
                    onBack = { tab = "Today"; feedPlant = null; openTask(null); journalPlantId = null; addPlant = false; plantsSearch = false },
                    initialPlant = journalPlantId ?: feedPlant, initialAdd = addPlant, initialTask = todayTask, returnToToday = todayTaskKey != null,
                    onTaskAction = { task, action -> controller?.act(task, action) },
                    photosApi = sitesApi, photosConnected = sitesConnected,
                    speciesApi = sitesApi, speciesConnected = sitesConnected, onCareChanged = { controller?.refresh() },
                    receipts = receipts, onReceiptGone = { receiptsGone++ }, focusSearch = plantsSearch, onFormDirty = { plantsDiscard = it }) }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && tab == "More" && !about) {
            MoreRoot(careState ?: CareState(), deviceOnline, busy, open = { key ->
                when (key) {
                    "notifications" -> notifications = true
                    "weather" -> openFeedSettings()
                    "sites" -> sites = true
                    "members" -> household = com.epaflix.fytorium.household.HouseholdArea.Members.name
                    "custom" -> household = com.epaflix.fytorium.household.HouseholdArea.Tasks.name
                    "planta" -> plantaImport = true
                    "unmatched" -> unmatched = true
                    "about" -> about = true
                }
            }, onLogout = onLogout, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), scroll = moreScroll)
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                updateRequired -> UpdateStop(minAppVersion, message, busy, phoneOnline, onObtainium, onCheckAgain, onLogout)
                !loggedIn -> LoginPage(busy, message, phoneOnline, onLogin)
                tab == "More" && about -> AboutPage(produceState<String?>(null, about) { value = withContext(Dispatchers.IO) {
                    controller?.repository?.cachedJson("meta")?.first?.let { runCatching { org.json.JSONObject(it).optString("serverVersion").takeIf { v -> v.isNotBlank() } }.getOrNull() } } }.value) {
                    onCopyDiagnostics()
                    // Android 13 and later confirm a copy themselves.
                    if (android.os.Build.VERSION.SDK_INT < 33) scope.launch { receipts.showSnackbar("Diagnostics copied"); receiptsGone++ }
                }
                else -> {
                    Text(when (tab) { "Today" -> "No tasks yet."; "Plants" -> "No plants yet."; else -> "No journal entries yet." })
                }
            }
        }
    }
}

// Material's snackbar with two lines at most and no vertical margin, so it fits the navigation bar at 1.3x text; TalkBack reads it whole.
@Composable private fun BarSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    Snackbar(Modifier.padding(horizontal = 12.dp).testTag("receipt"),
        action = visuals.actionLabel?.let { label -> @Composable { TextButton(onClick = data::performAction,
            colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor)) { Text(label) } } },
        dismissAction = (@Composable { IconButton(onClick = data::dismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") } }).takeIf { visuals.withDismissAction }) {
        Text(visuals.message, Modifier.semantics { contentDescription = visuals.message }, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
