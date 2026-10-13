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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun AppShell(loggedIn: Boolean, updateRequired: Boolean, busy: Boolean = false, message: String? = null,
                         onLogin: () -> Unit = {}, onLogout: () -> Unit = {}, onCopyDiagnostics: () -> Unit = {},
                         careState: CareState? = null, controller: CareController? = null, thumbnails: Thumbnails? = null) {
    var tab by rememberSaveable { mutableStateOf("Today") }
    var about by rememberSaveable { mutableStateOf(false) }
    var taskView by rememberSaveable { mutableStateOf("today") }
    BackHandler(enabled = about) { about = false }
    Scaffold(bottomBar = {
        if (loggedIn && !updateRequired) NavigationBar {
            listOf("Today" to Icons.Default.Check, "Plants" to Icons.Default.Eco,
                "Journal" to Icons.Default.Book, "More" to Icons.Default.Menu).filter { careState?.member?.role != Role.sitter || it.first != "Journal" }.forEach { (title, icon) ->
                NavigationBarItem(selected = tab == title, onClick = { tab = title; about = false },
                    icon = { Icon(icon, contentDescription = null) }, label = { Text(title) })
            }
        }
    }) { padding ->
        if (loggedIn && !updateRequired && careState != null && tab in listOf("Today", "Plants")) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (tab == "Today") Text("Fytorium", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (tab == "Today") TaskScreen(careState, taskView, onView = { taskView = it },
                    onRefresh = { controller?.refresh() }, onAction = { task, action, pot -> controller?.act(task, action, pot) },
                    onHandleAll = { controller?.handleAll(it) }, onSettings = { controller?.settings(it) }, onLoadPot = controller?.let { care -> { id -> withContext(Dispatchers.IO) { care.repository.loadPot(id) } } })
                else key(careState.member) { com.epaflix.fytorium.plants.PlantsScreen(careState, controller?.repository, thumbnails, onBack = { tab = "Today" }) }
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
