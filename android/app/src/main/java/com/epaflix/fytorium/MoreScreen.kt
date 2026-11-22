package com.epaflix.fytorium

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.plants.Heading

private data class MoreRow(val key: String, val title: String, val description: String, val icon: ImageVector)

internal fun roleName(role: Role) = when (role) { Role.owner -> "Owner"; Role.family -> "Family"; Role.sitter -> "Sitter" }

// W5a-P1 to P5: one list of rows under a header that says who is logged in; Log out asks first.
@Composable internal fun MoreRoot(state: CareState, online: Boolean, busy: Boolean, open: (String) -> Unit, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    // Before the first refresh names the member, the list holds only About and Log out.
    val member = state.member
    var confirming by rememberSaveable { mutableStateOf(false) }
    val owner = state.names.owner ?: "the owner"
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (member != null) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
            state.names.member?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            Text(roleName(member.role), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (member.role == Role.sitter) {
                val count = state.plants.size
                Spacer(Modifier.height(8.dp))
                Text(if (count == 0) "No plants are shared with you yet." else "You look after $count ${if (count == 1) "plant" else "plants"} for $owner.")
                Text(if (count == 0) "Ask $owner to share some." else "Ask $owner to share more.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val groups = if (member == null) emptyList() else if (member.role == Role.sitter) listOf(null to listOf(MoreRow("notifications", "Notifications", "Reminders, alerts and devices", Icons.Outlined.Notifications)))
        else listOfNotNull(
            "You" to listOf(MoreRow("notifications", "Notifications", "Reminders, alerts and devices", Icons.Outlined.Notifications)),
            "Household" to listOfNotNull(
                MoreRow("weather", "Weather and feed", "Location and cards on Today", Icons.Outlined.WbSunny),
                MoreRow("sites", "Sites", "Where your plants live", Icons.Outlined.Place),
                MoreRow("members", "Members", "People and invites", Icons.Outlined.Group).takeIf { member.role == Role.owner },
                MoreRow("custom", "Custom tasks", "Your own repeating tasks", Icons.Outlined.Checklist)),
            "Tools" to listOfNotNull(
                MoreRow("planta", "Import from Planta", "Bring plants over from Planta", Icons.Outlined.Download).takeIf { member.role == Role.owner },
                MoreRow("unmatched", "Species without care data", "Species with no care data from sources", Icons.AutoMirrored.Outlined.HelpOutline)))
        groups.forEach { (heading, rows) ->
            heading?.let { Heading(it, MaterialTheme.typography.titleSmall, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            rows.forEach { row -> RowItem(row) { open(row.key) } }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        RowItem(MoreRow("about", "About", "Version, data sources and diagnostics", Icons.Outlined.Info)) { open("about") }
        TextButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) { Text("Log out") }
    }
    if (confirming) LogoutDialog(online, onDismiss = { confirming = false }) { confirming = false; onLogout() }
}

@Composable private fun RowItem(row: MoreRow, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(row.title) }, supportingContent = { Text(row.description) },
        leadingContent = { Icon(row.icon, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))
}

@Composable internal fun LogoutDialog(online: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { DialogTitle("Log out of Fytorium?") },
        text = { DialogBody { Text(if (online) "Plants and photos saved on this phone are removed, and this phone stops getting Fytorium notifications. You need a connection to log in again."
            else "This phone is offline, so you can't log in again until it reconnects. Plants and photos saved on this phone are removed.") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Log out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// W5a-P7 and P8: the line under Log in is reserved, so progress and errors never move the page.
@Composable internal fun LoginPage(busy: Boolean, message: String?, online: Boolean, onLogin: () -> Unit) {
    // A tap offline changes the line, so the tap is answered.
    var tappedOffline by remember(online) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Plant care for your household.")
        // Offline it stays a button TalkBack can reach; the line under it says why it does nothing.
        Button(onClick = { if (online) onLogin() else tappedOffline = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = "Logging in needs a connection." }) {
            // Both labels take the space, so the button keeps its size while the login finishes.
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Log in", Modifier.alpha(if (busy) 0f else 1f).then(if (busy) Modifier.clearAndSetSemantics {} else Modifier))
                Text("Logging in…", Modifier.alpha(if (busy) 1f else 0f).then(if (busy) Modifier else Modifier.clearAndSetSemantics {}))
            }
        }
        Text((if (!online) (if (tappedOffline) "Logging in needs a connection. Turn on Wi-Fi or mobile data, then tap Log in." else "You're logged out. Logging in needs a connection.")
            else message.takeIf { !busy }).orEmpty(),
            Modifier.heightIn(min = 48.dp), color = MaterialTheme.colorScheme.error, minLines = 2)
        Text("To join a household, open the invite link from its owner first, then log in here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val sources = listOf(
    Triple("GBIF", "Species and names · CC BY 4.0", "https://www.gbif.org/"),
    Triple("Wikidata", "Names · CC0", "https://www.wikidata.org/"),
    Triple("NorTaxa", "Norwegian names", "https://nortaxa.artsdatabanken.no/"),
    Triple("Open-Meteo", "Weather and climate", "https://open-meteo.com/"),
    Triple("Open Plantbook", "Care data", "https://open.plantbook.io/"),
    Triple("Perenual", "Care data", "https://perenual.com/"),
    Triple("Pl@ntNet", "Identify from photos", "https://plantnet.org/"))

// W5a-P10.
@Composable internal fun AboutPage(serverVersion: String?, onCopyDiagnostics: () -> Unit) {
    val links = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fytorium for Android ${BuildConfig.VERSION_NAME}")
        Text(serverVersion?.let { "Server $it" } ?: "Server version not loaded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCopyDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy diagnostics") }
        Text("Diagnostics hold the app version and recent error types with their times, nothing about your plants or account. They stay on this phone until you copy them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Heading("Data sources", MaterialTheme.typography.titleMedium)
        Column {
            sources.forEach { (name, what, url) ->
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text(what) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Open $name") { links.openUri(url) })
            }
        }
        TextButton(onClick = { links.openUri("https://github.com/SpyrosPsarras/epaflix-fytorium") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Source code") }
    }
}

// W5a-P11 and P12.
@Composable internal fun UpdateStop(minimum: String?, message: String?, busy: Boolean, online: Boolean, onObtainium: (() -> Unit)?, onCheckAgain: () -> Unit, onLogout: () -> Unit) {
    val links = LocalUriHandler.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Update Fytorium", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(minimum?.let { "This phone has Fytorium ${BuildConfig.VERSION_NAME}. Your household's server needs $it or newer." } ?: "Your household's server needs a newer Fytorium.")
        Button(onClick = { links.openUri("https://github.com/SpyrosPsarras/epaflix-fytorium/releases/latest") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Get the update") }
        onObtainium?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Obtainium") } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCheckAgain, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
            TextButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log out") }
        }
        Text(message.takeIf { !busy }.orEmpty(), Modifier.heightIn(min = 48.dp), minLines = 2)
    }
    if (confirming) LogoutDialog(online, onDismiss = { confirming = false }) { confirming = false; onLogout() }
}
