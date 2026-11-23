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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.flow.first
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

internal fun homeFeedVisible(tab: String, taskView: String, covered: Boolean) =
    tab == "Today" && taskView == "today" && !covered

// Each tab's pages above its root, saved as plain strings (W6-P1).
private val stacksSaver = listSaver<SnapshotStateMap<String, List<String>>, String>(
    save = { stacks -> stacks.flatMap { (tab, routes) -> listOf(tab, routes.joinToString("\n")) } },
    restore = { saved -> mutableStateMapOf<String, List<String>>().apply { saved.chunked(2).forEach { (tab, routes) -> put(tab, routes.split("\n").filter { it.isNotEmpty() }) } } })

// Whether the page drawing this has left its tab's stack, so a hidden page keeps its files (W6-P19). Outside the shell a page that leaves composition has closed.
internal val LocalPageLeft = staticCompositionLocalOf<() -> Boolean> { { true } }
// Pages with a write in flight; a tab tap waits for them to end (W6-P21).
internal val LocalPageBusy = staticCompositionLocalOf<SnapshotStateMap<Any, Boolean>?> { null }
@Composable internal fun ReportBusy(busy: Boolean) {
    val pages = LocalPageBusy.current ?: return
    val page = remember { Any() }
    SideEffect { if (busy) pages[page] = true else pages.remove(page) }
    DisposableEffect(pages) { onDispose { pages.remove(page) } }
}

private fun allowed(route: String, role: Role?) = when {
    role == Role.sitter -> route.substringBefore('/') in listOf("row", "plant", "find", "notifications", "about")
    role != Role.owner -> route !in listOf("planta", "members", "sites")
    else -> true
}

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
    val role = careState?.member?.role
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
    val journalGrid = rememberSaveable(saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    var tab by rememberSaveable { mutableStateOf("Today") }
    var taskView by rememberSaveable { mutableStateOf("today") }
    val stacks = rememberSaveable(saver = stacksSaver) { mutableStateMapOf() }
    val pages = rememberSaveableStateHolder()
    // A sitter's pages differ from the same member's owner pages, so a role change to or from sitter starts them fresh.
    val scopeKey = "${careState?.member?.id}/${role == Role.sitter}"
    fun stack(t: String) = stacks[t].orEmpty()
    fun pageKey(t: String, index: Int, route: String?) = "$scopeKey/$t/$index/${route ?: "root"}"
    fun liveKeys() = listOf("Today", "Plants", "Journal", "More").flatMap { t -> listOf(pageKey(t, 0, null)) + stack(t).mapIndexed { i, r -> pageKey(t, i + 1, r) } }.toSet()
    fun push(route: String, t: String = tab) { stacks[t] = stack(t) + route }
    fun pop(t: String = tab, count: Int = 1) {
        val routes = stack(t)
        routes.indices.reversed().take(count).forEach { pages.removeState(pageKey(t, it + 1, routes[it])) }
        stacks[t] = routes.dropLast(count)
        // An invite link lives only while its page is open (W5b-P11).
        if ("members" in routes.takeLast(count)) com.epaflix.fytorium.household.InviteLinks.clear()
        if (t == "Today" && stacks[t].orEmpty().none { it.startsWith("row/") }) openTask(null)
    }
    fun popAll(t: String) = pop(t, stack(t).size)
    var plantsReselects by rememberSaveable { mutableIntStateOf(0) }
    var plantsShowAll by rememberSaveable { mutableIntStateOf(0) }
    // A Plants, Sites or Species care form names its discard question here, read when a reselect or a notification closes it, so it asks the same as its Back.
    var plantsDiscard by remember { mutableStateOf<() -> String?>({ null }) }
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    // The question of a form left open in a hidden tab, so Log out asks it before dropping the form.
    val drafts = rememberSaveable(saver = stacksSaver) { mutableStateMapOf() }
    fun show(t: String) {
        if (t != tab) plantsDiscard().let { question -> if (question != null) drafts[tab] = listOf(question) else drafts.remove(tab) }
        drafts.remove(t); tab = t
    }
    val busyPages = remember { mutableStateMapOf<Any, Boolean>() }
    var pendingTab by remember { mutableStateOf<String?>(null) }
    fun goHome() { popAll("Today"); show("Today"); taskView = "today" }
    fun reselectNow() {
        if (stack(tab).isNotEmpty()) { popAll(tab); return }
        when (tab) {
            "Today" -> scope.launch { (if (taskView == "upcoming") upcomingScroll else todayScroll).animateScrollTo(0) }
            "Plants" -> plantsReselects++
            "Journal" -> scope.launch { journalGrid.animateScrollToItem(0) }
            else -> scope.launch { moreScroll.animateScrollTo(0) }
        }
    }
    fun showAllPlants(origin: String) { stack(origin).lastIndexOf("planta").takeIf { it >= 0 }?.let { pop(origin, stack(origin).size - it) }
        popAll("Plants"); plantsShowAll++; controller?.refresh(); show("Plants") }
    // Asks the open page's question first, as its Back would (W6-P18).
    fun ask(target: String, then: () -> Unit) { val title = plantsDiscard(); if (title != null) { leaving = title; destination = target } else then() }
    fun go(target: String) = when {
        target == "home" -> goHome()
        target == "reselect" -> reselectNow()
        target.startsWith("plants:") -> showAllPlants(target.removePrefix("plants:"))
        target.startsWith("logout:") -> onLogout()
        else -> Unit
    }
    fun tabTap(title: String) = when {
        busyPages.isNotEmpty() -> pendingTab = title
        title == tab -> { pendingTab = null; ask("reselect") { reselectNow() } }
        else -> { pendingTab = null; show(title) }
    }
    LaunchedEffect(pendingTab, busyPages.isEmpty()) { if (busyPages.isEmpty()) pendingTab?.let { pendingTab = null; tabTap(it) } }
    // A question about an unsent invite link means nothing once the link is gone, as after process death: go where the tap was going (Astra W5b-astra-9).
    LaunchedEffect(leaving) {
        if (leaving?.startsWith("You can't see this link again.") == true && com.epaflix.fytorium.household.InviteLinks.isEmpty()) {
            val target = destination; leaving = null; destination = null; target?.let(::go)
        }
    }
    val homeHandled = key(homeRequest) { rememberSaveable { mutableStateOf(false) } }
    var homePending by rememberSaveable { mutableStateOf(false) }
    // A notification shows Today; only a page over Today is asked about (W6-P16).
    LaunchedEffect(homeRequest) { if (homeRequest > 0 && !homeHandled.value) { homeHandled.value = true; homePending = true } }
    var homeAsk by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(homePending) { if (homePending) { snapshotFlow { busyPages.isEmpty() }.first { it }; show("Today"); homePending = false; homeAsk = true } }
    fun logOut() { drafts.entries.firstOrNull()?.let { (t, question) -> leaving = question.first(); destination = "logout:$t" } ?: onLogout() }
    LaunchedEffect(homeAsk) { if (homeAsk) { homeAsk = false; if (stack("Today").isNotEmpty()) ask("home") { goHome() } else goHome() } }
    var previousMemberId by rememberSaveable { mutableStateOf(careState?.member?.id) }
    var previousRole by rememberSaveable { mutableStateOf(careState?.member?.role) }
    LaunchedEffect(loggedIn, careState?.member) {
        val member = careState?.member
        val memberChanged = previousMemberId != null && member != null && previousMemberId != member.id
        val becameSitter = previousRole != null && previousRole != Role.sitter && member?.role == Role.sitter
        val leftSitter = previousRole == Role.sitter && member != null && member.role != Role.sitter
        if (!loggedIn || memberChanged || becameSitter || leftSitter) {
            // Every page of the member or role before goes, so switching back starts fresh.
            val before = "$previousMemberId/${previousRole == Role.sitter}"
            listOf("Today", "Plants", "Journal", "More").forEach { t ->
                pages.removeState("$before/$t/0/root"); stack(t).forEachIndexed { i, r -> pages.removeState("$before/$t/${i + 1}/$r") }
            }
            if (!loggedIn || memberChanged) stacks.clear()
            else if (becameSitter) stacks.keys.toList().forEach { t -> stacks[t] = stack(t).takeWhile { it in listOf("notifications", "about") } }
            drafts.clear()
            if (!leftSitter) { com.epaflix.fytorium.household.InviteLinks.clear(); openTask(null); leaving = null; destination = null }
        }
        if (!loggedIn || memberChanged) {
            tab = "Today"; taskView = "today"
            todayScroll.scrollTo(0); upcomingScroll.scrollTo(0); moreScroll.scrollTo(0)
        }
        if (member != null) {
            // A route the member may no longer open goes, with every page above it.
            stacks.keys.toList().forEach { t -> stack(t).indexOfFirst { !allowed(it, member.role) }.takeIf { it >= 0 }?.let { pop(t, stack(t).size - it); drafts.remove(t) } }
            if (member.role == Role.sitter) { popAll("Journal"); if (tab == "Journal") tab = "Today" }
        }
        if (!loggedIn) { previousMemberId = null; previousRole = null }
        else if (member != null) { previousMemberId = member.id; previousRole = member.role }
    }
    val route = stack(tab).lastOrNull()?.takeIf { allowed(it, role) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feed, lifecycle, loggedIn, updateRequired, tab, taskView, stack("Today").isEmpty(), careState?.member) {
        if (loggedIn && !updateRequired && careState != null && homeFeedVisible(tab, taskView, stack("Today").isNotEmpty())) feed?.poll(lifecycle)
    }
    // Back above a root closes the page; at the Plants, Journal and More roots it shows Today; Today's root leaves the app (W6-P9).
    BackHandler(enabled = loggedIn && !updateRequired && (route != null || tab != "Today")) { if (route != null) pop() else show("Today") }
    val receipts = remember { SnackbarHostState() }
    val receipt = receipts.currentSnackbarData
    val receiptsOnBar = remember { mutableSetOf<SnackbarData>() }
    var receiptFadedIn by remember { mutableStateOf<SnackbarData?>(null) }
    var barPaused by remember { mutableStateOf(false) }
    var receiptsGone by remember { mutableIntStateOf(0) }
    LaunchedEffect(receiptsGone) { if (receiptsGone > 0) { barPaused = true; delay(500); barPaused = false } }
    val showTabs = !WindowInsets.isImeVisible
    val about = route == "about"
    Scaffold(topBar = {
        if (!loggedIn || updateRequired || (careState == null) || (tab == "More" && (route == null || about))) {
            val line = careState?.takeIf { loggedIn && !updateRequired && !about }?.connectionLine(deviceOnline)
            TopAppBar(title = { BarTitle(line, careState?.connectionNotice(deviceOnline).orEmpty(), retry = { controller?.refresh(); Unit }.takeIf { line != null && deviceOnline }) {
                Text(if (!loggedIn || updateRequired) "Fytorium" else if (about) "About" else tab, style = MaterialTheme.typography.titleMedium) } },
                navigationIcon = { if (loggedIn && !updateRequired && about) IconButton(onClick = { pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }
    }, bottomBar = {
        val tabBar: @Composable (Modifier) -> Unit = { modifier ->
            NavigationBar(modifier) {
                listOf("Today" to Icons.Default.Check, "Plants" to Icons.Default.Eco,
                    "Journal" to Icons.Default.Book, "More" to Icons.Default.Menu).filter { role != Role.sitter || it.first != "Journal" }.forEach { (title, icon) ->
                    NavigationBarItem(selected = tab == title, onClick = { tabTap(title) },
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
        leaving?.let { body -> val (title, keepLabel, leaveLabel) = com.epaflix.fytorium.household.leaveQuestion(body)
            com.epaflix.fytorium.plants.LeaveDialog(title, body, keepLabel, leaveLabel, onKeep = {
                destination?.takeIf { it.startsWith("logout:") }?.let { show(it.substringAfter(':')) }
                leaving = null; destination = null }) {
            val target = destination
            leaving = null; destination = null; controller?.repository?.clearPlantErrors(); target?.let(::go) } }
    }) { scaffoldPadding ->
        val padding = if (showTabs) scaffoldPadding else object : PaddingValues by scaffoldPadding {
            override fun calculateBottomPadding() = 0.dp
        }
        if (!loggedIn || updateRequired || (careState == null && tab != "More")) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    updateRequired -> UpdateStop(minAppVersion, message, busy, phoneOnline, onObtainium, onCheckAgain, onLogout)
                    !loggedIn -> LoginPage(busy, message, phoneOnline, onLogin)
                    else -> Text(when (tab) { "Today" -> "No tasks yet."; "Plants" -> "No plants yet."; else -> "No journal entries yet." })
                }
            }
            return@Scaffold
        }
        val care = careState ?: CareState()
        val shownKey = pageKey(tab, stack(tab).size, route).takeIf { route != null || stack(tab).isEmpty() } ?: pageKey(tab, 0, null)
        val onReceipt: (String) -> Unit = { text -> scope.launch { receipts.showSnackbar(text); receiptsGone++ } }
        val answers = controller?.repository?.plantState?.collectAsState()?.value?.answers ?: 0
        val member = care.member
        val pulling = controller?.pulling?.collectAsState()?.value == true
        @Composable fun plantsScreen(initialPlant: String? = null, initialAdd: Boolean = false, initialSites: Boolean = false, row: Boolean = false, pushed: Boolean = true) =
            com.epaflix.fytorium.plants.PlantsScreen(care, controller?.repository, thumbnails,
                onBack = { if (pushed) pop() else show("Today") },
                initialPlant = initialPlant, initialAdd = initialAdd, initialSites = initialSites, initialTask = todayTask.takeIf { row }, pushed = pushed,
                reselect = if (pushed) 0 else plantsReselects, showAll = if (pushed) 0 else plantsShowAll,
                onTaskAction = { task, action -> controller?.act(task, action) },
                photosApi = sitesApi, photosConnected = sitesConnected,
                speciesApi = sitesApi, speciesConnected = sitesConnected, onCareChanged = { controller?.refresh() },
                receipts = receipts, onReceiptGone = { receiptsGone++ }, onFormDirty = { plantsDiscard = it })
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            CompositionLocalProvider(LocalPageLeft provides { shownKey !in liveKeys() }, LocalPageBusy provides busyPages) { pages.SaveableStateProvider(shownKey) {
                when {
                    route == null && tab == "Today" -> TaskScreen(care, taskView, onView = { taskView = it },
                        scrollState = if (taskView == "upcoming") upcomingScroll else todayScroll,
                        onOpenTask = { openTask(it.occurrence); push("row/${it.plantId}") },
                        onRefresh = { controller?.refresh(pulled = true) }, refreshing = pulling,
                        onAction = { task, action -> controller?.act(task, action) },
                        onHandleType = { controller?.handleType(it) }, onHandlePlant = { controller?.handleAll(it) }, onSettings = { controller?.settings(it) == true }, thumbnails = thumbnails,
                        onUndoReceipt = { controller?.undoReceipt(it) }, onReceiptDone = { controller?.clearReceipt(it) },
                        onSearch = { push("find") }, onAddPlant = { push("add") }, onImport = { push("planta") }, deviceOnline = deviceOnline,
                        onRetry = { controller?.refresh() }, receipts = receipts, onReceiptGone = { receiptsGone++ },
                        feed = feedState, feedContent = { shown ->
                            HomeFeed(shown, care.today?.today, sites = care.plants.associate { it.id to it.siteName },
                                onOpen = { href -> if (href.startsWith("/plants/")) push("plant/" + href.removePrefix("/plants/")) else { push("weather"); scope.launch(Dispatchers.IO) { feed?.loadLocation() } } },
                                onWater = { scope.launch(Dispatchers.IO) { if (feed?.water() == true) controller?.rainWatered(feed.state.value.weather?.lastBatchId) } },
                                onUndo = { scope.launch(Dispatchers.IO) { feed?.undoRain() } })
                        })
                    route == null && tab == "Plants" -> plantsScreen(pushed = false)
                    route == null && tab == "Journal" && member != null && sitesApi != null -> {
                        // Journal receipts and explanations show over the navigation bar, as on Today and Plants.
                        ReceiptSnackbar(care.receipt, receipts, onUndo = {}, onDone = { controller?.repository?.clearReceipt(it) }, onGone = { receiptsGone++ })
                        com.epaflix.fytorium.photos.PhotosRoute(sitesApi, care, sitesConnected, thumbnails,
                            onBack = { show("Today") }, onPlant = { push("plant/$it") },
                            onChanged = { controller?.refresh() }, onUpdateRequired = { controller?.repository?.requireUpdate() }, onFormDirty = { plantsDiscard = it },
                            onReceipt = { controller?.repository?.showReceipt(it) }, onAdd = { push("pick") },
                            cached = { controller?.repository?.cachedJson(it) }, keep = { resource, json -> controller?.repository?.saveJson(resource, json) }, grid = journalGrid)
                    }
                    route == null && tab == "More" -> MoreRoot(care, deviceOnline, busy, open = { key ->
                        if (key == "weather") scope.launch(Dispatchers.IO) { feed?.loadLocation() }
                        push(key)
                    }, onLogout = ::logOut, modifier = Modifier.fillMaxSize(), scroll = moreScroll)
                    route == null -> Unit
                    route.startsWith("row/") -> plantsScreen(initialPlant = route.removePrefix("row/"), row = true)
                    route.startsWith("plant/") -> plantsScreen(initialPlant = route.removePrefix("plant/"))
                    route == "add" -> plantsScreen(initialAdd = true)
                    route == "sites" -> plantsScreen(initialSites = true)
                    route == "find" -> {
                        val query = rememberSaveable { mutableStateOf("") }
                        var focus by rememberSaveable { mutableStateOf(true) }
                        com.epaflix.fytorium.plants.PlantsList(care, care.plants, false, onPlant = { push("plant/$it") }, onAdd = {}, onGraveyard = {},
                            onRefresh = { controller?.refresh(pulled = true) }, thumbnails = thumbnails, search = query, onBack = { pop() },
                            focusSearch = focus, onFocused = { focus = false }, refreshing = pulling, chooseTitle = "Find a plant",
                            grid = rememberSaveable(saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) { androidx.compose.foundation.lazy.grid.LazyGridState() })
                    }
                    route == "pick" -> com.epaflix.fytorium.plants.PlantsList(care, care.plants, false, onPlant = { push("photo/$it") }, onAdd = {}, onGraveyard = {},
                        onRefresh = { controller?.refresh(pulled = true) }, thumbnails = thumbnails, search = rememberSaveable { mutableStateOf("") }, onBack = { pop() },
                        refreshing = pulling, chooseTitle = "Choose a plant",
                        grid = rememberSaveable(saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) { androidx.compose.foundation.lazy.grid.LazyGridState() })
                    route.startsWith("photo/") && sitesApi != null -> {
                        val picked = care.plants.firstOrNull { it.id == route.removePrefix("photo/") }
                        ReceiptSnackbar(care.receipt, receipts, onUndo = {}, onDone = { controller?.repository?.clearReceipt(it) }, onGone = { receiptsGone++ })
                        if (picked == null) LaunchedEffect(Unit) { pop() }
                        else com.epaflix.fytorium.photos.PhotosRoute(sitesApi, care, sitesConnected, thumbnails,
                            plantId = picked.id, plantName = picked.displayName, formOnly = true, onBack = { pop() }, onPlant = { push("plant/$it") },
                            onChanged = { controller?.refresh() }, onUpdateRequired = { controller?.repository?.requireUpdate() }, onFormDirty = { plantsDiscard = it },
                            onReceipt = { controller?.repository?.showReceipt(it) }, onUploaded = { popAll("Journal") },
                            cached = { controller?.repository?.cachedJson(it) }, keep = { resource, json -> controller?.repository?.saveJson(resource, json) })
                    }
                    route == "weather" && feed != null -> com.epaflix.fytorium.feed.WeatherFeedRoute(feed, care, deviceOnline, onBack = { pop() },
                        onReceipt = onReceipt, onFormDirty = { plantsDiscard = it }, onUpdateRequired = { controller?.repository?.requireUpdate() }, answers = answers)
                    route == "about" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AboutPage(produceState<String?>(null) { value = withContext(Dispatchers.IO) {
                            controller?.repository?.cachedJson("meta")?.first?.let { runCatching { org.json.JSONObject(it).optString("serverVersion").takeIf { v -> v.isNotBlank() } }.getOrNull() } } }.value) {
                            onCopyDiagnostics()
                            // Android 13 and later confirm a copy themselves.
                            if (android.os.Build.VERSION.SDK_INT < 33) onReceipt("Diagnostics copied")
                        }
                    }
                    member == null || sitesApi == null -> Unit
                    route == "notifications" -> com.epaflix.fytorium.notifications.NotificationRoute(sitesApi, care, sitesConnected, onBack = { pop() },
                        onReceipt = onReceipt, onUpdateRequired = { controller?.repository?.requireUpdate() },
                        answers = answers, live = { controller?.repository?.state?.value },
                        cached = { controller?.repository?.cachedJson(it, member.id) },
                        keep = { resource, json -> controller?.repository?.saveJson(resource, json, member.id) })
                    route == "planta" -> com.epaflix.fytorium.planta.PlantaRoute(sitesApi, care, sitesConnected, onBack = { pop() },
                        // A form left open in Plants asks first, from the review; Keep editing stays here (W6-P15).
                        onPlants = { val origin = tab; drafts["Plants"]?.firstOrNull()?.let { leaving = it; destination = "plants:$origin" } ?: showAllPlants(origin) },
                        onPlant = { push("plant/$it"); controller?.refresh() },
                        onSites = { push("sites") },
                        onReceipt = onReceipt, onUpdateRequired = { controller?.repository?.requireUpdate() }, answers = answers)
                    route == "members" -> com.epaflix.fytorium.household.MembersRoute(sitesApi, care, sitesConnected,
                        onBack = { pop() }, onFormDirty = { plantsDiscard = it }, onUpdateRequired = { controller?.repository?.requireUpdate() },
                        answers = answers, onReceipt = onReceipt, thumbnails = thumbnails,
                        cached = { controller?.repository?.cachedJson(it, member.id) }, keep = { resource, json -> controller?.repository?.saveJson(resource, json, member.id) })
                    route == "custom" -> com.epaflix.fytorium.household.CustomTasksRoute(sitesApi, care, sitesConnected,
                        onBack = { pop() }, onFormDirty = { plantsDiscard = it }, onUpdateRequired = { controller?.repository?.requireUpdate() },
                        answers = answers, onReceipt = onReceipt, thumbnails = thumbnails,
                        cached = { controller?.repository?.cachedJson(it, member.id) }, keep = { resource, json -> controller?.repository?.saveJson(resource, json, member.id) })
                    route == "unmatched" || route.startsWith("species/") -> com.epaflix.fytorium.species.SpeciesCareRoute(sitesApi, route.removePrefix("species/").toLongOrNull(), care, sitesConnected,
                        onBack = { pop() }, onChanged = { controller?.refresh() }, onSpecies = { push("species/$it") }, onUpdateRequired = { controller?.repository?.requireUpdate() },
                        onReceipt = onReceipt, answers = answers, onFormDirty = { plantsDiscard = it })
                }
            } }
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
