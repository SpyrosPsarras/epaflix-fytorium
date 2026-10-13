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

internal fun homeFeedVisible(tab: String, taskView: String, feedSettings: Boolean) =
    tab == "Today" && taskView == "today" && !feedSettings

@Composable fun AppShell(loggedIn: Boolean, updateRequired: Boolean, busy: Boolean = false, message: String? = null,
                         onLogin: () -> Unit = {}, onLogout: () -> Unit = {}, onCopyDiagnostics: () -> Unit = {},
                         careState: CareState? = null, controller: CareController? = null, thumbnails: Thumbnails? = null,
                         feed: FeedRepository? = null) {
    val loadedFeed = feed?.state?.collectAsState()?.value
    val feedState = loadedFeed?.let { if (it.member == careState?.member) it else FeedState(member = careState?.member) }
    val scope = rememberCoroutineScope()
    var feedSettings by rememberSaveable { mutableStateOf(false) }
    fun openFeedSettings() { feedSettings = true; scope.launch(Dispatchers.IO) { feed?.loadLocation() } }
    LaunchedEffect(loggedIn, careState?.member) { if (!loggedIn || careState?.member?.role == Role.sitter) feedSettings = false }
    BackHandler(enabled = feedSettings) { feedSettings = false }
    var feedPlant by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("Today") }
    var taskView by rememberSaveable { mutableStateOf("today") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feed, lifecycle, loggedIn, updateRequired, tab, taskView, feedSettings, careState?.member) {
        if (loggedIn && !updateRequired && careState != null && homeFeedVisible(tab, taskView, feedSettings)) feed?.poll(lifecycle)
    }
    var about by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = about) { about = false }
    Scaffold(bottomBar = {
        if (loggedIn && !updateRequired) NavigationBar {
            listOf("Today" to Icons.Default.Check, "Plants" to Icons.Default.Eco,
                "Journal" to Icons.Default.Book, "More" to Icons.Default.Menu).filter { careState?.member?.role != Role.sitter || it.first != "Journal" }.forEach { (title, icon) ->
                NavigationBarItem(selected = tab == title, onClick = { tab = title; about = false; feedSettings = false; feedPlant = null },
                    icon = { Icon(icon, contentDescription = null) }, label = { Text(title) })
            }
        }
    }) { padding ->
        if (loggedIn && !updateRequired && feedSettings && feedState != null && careState?.member?.role in listOf(Role.owner, Role.family)) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { feedSettings = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                FeedSettingsScreen(feedState,
                    onSaveFeed = { scope.launch(Dispatchers.IO) { feed?.saveSettings(it) } },
                    onSaveLocation = { scope.launch(Dispatchers.IO) { feed?.saveLocation(it) } })
                TextButton(onClick = { scope.launch(Dispatchers.IO) { feed?.refresh(careState?.member); feed?.loadLocation() } },
                    enabled = !feedState.refreshing && !feedState.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh settings") }
            }
            return@Scaffold
        }
        if (loggedIn && !updateRequired && careState != null && tab in listOf("Today", "Plants")) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text("Fytorium", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (tab == "Today") TaskScreen(careState, taskView, onView = { taskView = it },
                    onRefresh = { controller?.refresh() }, onAction = { task, action, pot -> controller?.act(task, action, pot) },
                    onHandleAll = { controller?.handleAll(it) }, onSettings = { controller?.settings(it) }, onLoadPot = { controller?.plant(it) },
                    feedContent = { seeTasks -> if (feedState != null) HomeFeed(feedState, onSettings = { openFeedSettings() },
                        onOpen = { href -> when {
                            href == "/#today" -> seeTasks()
                            href.startsWith("/plants/") -> { feedPlant = href.removePrefix("/plants/"); controller?.plant(feedPlant!!); tab = "Plants" }
                            else -> openFeedSettings()
                        } }, onWater = { scope.launch(Dispatchers.IO) { if (feed?.water() == true) controller?.refresh() } },
                        onUndo = { scope.launch(Dispatchers.IO) { if (feed?.undoRain() == true) controller?.refresh() } }) })
                else CachedPlants(careState, onPlant = { controller?.plant(it) }, thumbnails = thumbnails, initialPlant = feedPlant)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Fytorium", style = MaterialTheme.typography.headlineLarge)
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
                    Text("About", style = MaterialTheme.typography.headlineMedium)
                    Text("Version " + BuildConfig.VERSION_NAME)
                    Button(onClick = onCopyDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy diagnostics") }
                    Text("Diagnostics stay on this device until you copy them.")
                }
                tab == "More" -> {
                    Text("More", style = MaterialTheme.typography.headlineMedium)
                    if (feed != null && careState?.member?.role in listOf(Role.owner, Role.family)) Button(onClick = { openFeedSettings() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Feed and weather settings") }
                    Button(onClick = { about = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("About") }
                    Button(onClick = onLogout, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log out") }
                }
                else -> {
                    Text(tab, style = MaterialTheme.typography.headlineMedium)
                    Text(when (tab) { "Today" -> "No tasks yet."; "Plants" -> "No plants yet."; else -> "No journal entries yet." })
                }
            }
            message?.let { Text(it) }
            if (busy) Text("Please wait…")
        }
    }
}
