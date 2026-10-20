package com.epaflix.fytorium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.epaflix.fytorium.feed.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun homeFeedVisible(tab: String, taskView: String, feedSettings: Boolean) =
    tab == "Today" && taskView == "today" && !feedSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppShell(loggedIn: Boolean, updateRequired: Boolean, busy: Boolean = false, message: String? = null,
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
    var tab by rememberSaveable { mutableStateOf("Today") }
    var household by rememberSaveable { mutableStateOf<String?>(null) }
    var sites by rememberSaveable { mutableStateOf(false) }
    var journalPlantId by rememberSaveable { mutableStateOf<String?>(null) }
    var unmatched by rememberSaveable { mutableStateOf(false) }
    var plantaImport by rememberSaveable { mutableStateOf(false) }
    var careSpecies by rememberSaveable { mutableStateOf<Long?>(null) }
    var addPlant by rememberSaveable { mutableStateOf(false) }
    var taskView by rememberSaveable { mutableStateOf("today") }
    var notifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) {
            tab = "Today"; taskView = "today"; addPlant = false; notifications = false; sites = false; household = null
            plantaImport = false; unmatched = false; careSpecies = null; feedSettings = false; feedPlant = null; journalPlantId = null
        }
    }
    var about by rememberSaveable { mutableStateOf(false) }
    var previousMemberId by rememberSaveable { mutableStateOf(careState?.member?.id) }
    var previousRole by rememberSaveable { mutableStateOf(careState?.member?.role) }
    LaunchedEffect(loggedIn, careState?.member) {
        val member = careState?.member
        val memberChanged = previousMemberId != null && member != null && previousMemberId != member.id
        val becameSitter = previousRole != null && previousRole != Role.sitter && member?.role == Role.sitter
        if (!loggedIn || memberChanged) notifications = false
        if (!loggedIn || memberChanged || member?.role == Role.sitter) {
            addPlant = false
            if (!loggedIn || memberChanged || tab == "Journal") {
                tab = "Today"; taskView = "today"; about = false
            }
            sites = false; unmatched = false; careSpecies = null; feedSettings = false; household = null; journalPlantId = null
            if (!loggedIn || memberChanged || becameSitter) feedPlant = null
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
    Scaffold(topBar = {
        if (!loggedIn || updateRequired || (careState == null) || (tab == "More" && !sites && !notifications && !plantaImport && !unmatched && household == null && !feedSettings)) {
            TopAppBar(title = { Text(if (!loggedIn || updateRequired) "Fytorium" else if (about) "About" else tab, style = MaterialTheme.typography.titleMedium) })
        }
    }, bottomBar = {
        if (loggedIn && !updateRequired) NavigationBar {
            listOf("Today" to Icons.Default.Check, "Plants" to Icons.Default.Eco,
                "Journal" to Icons.Default.Book, "More" to Icons.Default.Menu).filter { careState?.member?.role != Role.sitter || it.first != "Journal" }.forEach { (title, icon) ->
                NavigationBarItem(selected = tab == title, onClick = { tab = title; addPlant = false; notifications = false; about = false; sites = false; plantaImport = false; household = null; unmatched = false; careSpecies = null; feedSettings = false; feedPlant = null; journalPlantId = null },
                    icon = { Icon(icon, contentDescription = null) }, label = { Text(title) })
            }
        }
    }) { padding ->
        if (loggedIn && !updateRequired && tab == "More" && notifications && careState?.member != null && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                key(careState.member) { com.epaflix.fytorium.notifications.NotificationRoute(sitesApi, careState, sitesConnected) { notifications = false } }
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
                        onChanged = { controller?.refresh() }, onSpecies = { careSpecies = it })
                }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && feedSettings && feedState != null && careState?.member?.role in listOf(Role.owner, Role.family)) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { feedSettings = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                FeedSettingsScreen(feedState,
                    onSaveFeed = { scope.launch(Dispatchers.IO) { feed?.saveSettings(it) } },
                    onSaveLocation = { scope.launch(Dispatchers.IO) { feed?.saveLocation(it) } })
                TextButton(onClick = { scope.launch(Dispatchers.IO) { feed?.refresh(careState?.member); feed?.loadLocation() } },
                    enabled = !feedState.refreshing && !feedState.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh settings") }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && tab == "More" && sites && careState?.member != null && careState.member.role != Role.sitter && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                com.epaflix.fytorium.sites.SitesRoute(sitesApi, careState, sitesConnected, onBack = { sites = false })
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && careState?.member != null && careState.member.role != Role.sitter && tab == "Journal" && sitesApi != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                key(careState.member) { com.epaflix.fytorium.photos.PhotosRoute(sitesApi, careState, sitesConnected, thumbnails,
                    onBack = { tab = "Today" }, onPlant = { journalPlantId = it; tab = "Plants" },
                    onChanged = { controller?.refresh() }) }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && careState != null && tab in listOf("Today", "Plants")) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (tab == "Today") TaskScreen(careState, taskView, onView = { taskView = it },
                    onRefresh = { controller?.refresh() }, onAction = { task, action, pot -> controller?.act(task, action, pot) },
                    onHandleType = { controller?.handleType(it) }, onSettings = { controller?.settings(it) }, thumbnails = thumbnails,
                    onNotifications = { tab = "More"; notifications = true },
                    onSearch = { addPlant = false; feedPlant = null; journalPlantId = null; tab = "Plants" },
                    onAddPlant = { addPlant = true; tab = "Plants" }, onImport = { tab = "More"; plantaImport = true }, deviceOnline = deviceOnline,
                    onLoadPot = controller?.let { care -> { id -> withContext(Dispatchers.IO) { care.repository.loadPot(id) } } },
                    feedContent = { seeTasks -> if (feedState != null && feedState.cards.isNotEmpty()) HomeFeed(feedState,
                        onOpen = { href -> when {
                            href == "/#today" -> seeTasks()
                            href.startsWith("/plants/") -> { feedPlant = href.removePrefix("/plants/"); tab = "Plants" }
                            else -> openFeedSettings()
                        } }, onWater = { scope.launch(Dispatchers.IO) { if (feed?.water() == true) controller?.refresh() } },
                        onUndo = { scope.launch(Dispatchers.IO) { if (feed?.undoRain() == true) controller?.refresh() } }) })
                else key(careState.member?.id, careState.member?.role == Role.sitter, journalPlantId, addPlant) { com.epaflix.fytorium.plants.PlantsScreen(careState, controller?.repository, thumbnails,
                    onBack = { tab = "Today"; feedPlant = null; journalPlantId = null; addPlant = false }, initialPlant = journalPlantId ?: feedPlant, initialAdd = addPlant,
                    photosApi = sitesApi, photosConnected = sitesConnected,
                    speciesApi = sitesApi, speciesConnected = sitesConnected, onCareChanged = { controller?.refresh() }) }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                updateRequired -> {
                    Text("Update Fytorium", style = MaterialTheme.typography.headlineMedium)
                    Text("This server needs a newer app. Install the latest Fytorium release.")
                }
                !loggedIn -> {
                    Text("Your plants, together.")
                    Button(onClick = onLogin, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log in") }
                }
                tab == "More" && about -> {
                    Text("Version " + BuildConfig.VERSION_NAME)
                    Button(onClick = onCopyDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy diagnostics") }
                    Text("Diagnostics stay on this device until you copy them.")
                }
                tab == "More" -> {
                    if (careState?.member != null && sitesApi != null) Button(onClick = { notifications = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("My notifications") }
                    if (feed != null && careState?.member?.role in listOf(Role.owner, Role.family)) Button(onClick = { openFeedSettings() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Feed and weather settings") }
                    if (careState?.member != null && careState.member.role != Role.sitter) {
                        Button(onClick = { sites = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sites") }
                        for (area in com.epaflix.fytorium.household.HouseholdArea.entries.filter { it != com.epaflix.fytorium.household.HouseholdArea.Members || careState.member.role == Role.owner }) {
                            Button(onClick = { household = area.name }, modifier = Modifier.heightIn(min = 48.dp)) { Text(area.title) }
                        }
                        Button(onClick = { unmatched = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unmatched species") }
                    }
                    if (careState?.member?.role == Role.owner) Button(onClick = { plantaImport = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import from Planta") }
                    Button(onClick = { about = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("About") }
                    Button(onClick = onLogout, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log out") }
                }
                else -> {
                    Text(when (tab) { "Today" -> "No tasks yet."; "Plants" -> "No plants yet."; else -> "No journal entries yet." })
                }
            }
            message?.let { Text(it) }
            if (busy) Text("Please wait…")
        }
    }
}
