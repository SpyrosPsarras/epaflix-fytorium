package com.epaflix.fytorium.household

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun HouseholdRoute(api: ApiClient, area: HouseholdArea, care: CareState, connected: () -> Boolean, onBack: () -> Unit) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, area, member) { HouseholdRepository(api, area,
        { currentCare.member?.takeIf { it == member }?.role }, { currentConnected() && !currentCare.updateRequired }) }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    val role = member?.role ?: return
    if (role == Role.sitter || (area == HouseholdArea.Members && role != Role.owner)) return
    HouseholdScreen(area, state, role, connected() && !care.updateRequired, onBack, repository::clearErrors,
        onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onCreateInvite = { roleName, plants -> withContext(Dispatchers.IO) { repository.createInvite(roleName, plants) } },
        onShare = { link ->
            // The only use of this single-display token is the platform share sheet. Never save or log it.
            val url = java.net.URI(BuildConfig.SERVER_URL).resolve(link).toString()
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, "Share invite link"))
        },
        onMember = { m, r, p -> withContext(Dispatchers.IO) { repository.saveMember(m, r, p) } },
        onRemove = { withContext(Dispatchers.IO) { repository.removeMember(it) } },
        onRevoke = { withContext(Dispatchers.IO) { repository.revokeInvite(it) } },
        onMisting = { withContext(Dispatchers.IO) { repository.saveMisting(it) } },
        onLocation = { withContext(Dispatchers.IO) { repository.saveLocation(it) } },
        onFeed = { withContext(Dispatchers.IO) { repository.saveFeed(it) } },
        onTarget = { scope.launch { withContext(Dispatchers.IO) { repository.selectTarget(it) } } },
        onTask = { withContext(Dispatchers.IO) { repository.saveTask(it) } },
        onDeleteTask = { withContext(Dispatchers.IO) { repository.deleteTask(it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HouseholdScreen(area: HouseholdArea, state: HouseholdState, role: Role, online: Boolean = true,
    onBack: () -> Unit = {}, onClear: () -> Unit = {}, onRefresh: () -> Unit = {},
    onCreateInvite: suspend (String, Set<String>) -> String? = { _, _ -> null }, onShare: (String) -> Unit = {},
    onMember: suspend (HouseholdMember, String, Set<String>) -> Boolean = { _, _, _ -> false },
    onRemove: suspend (String) -> Boolean = { false }, onRevoke: suspend (String) -> Boolean = { false },
    onMisting: suspend (Boolean) -> Boolean = { false }, onLocation: suspend (Map<String, String>) -> Boolean = { false },
    onFeed: suspend (Map<String, Boolean>) -> Boolean = { false }, onTarget: (TaskTarget) -> Unit = {},
    onTask: suspend (CustomTask) -> Boolean = { false }, onDeleteTask: suspend (CustomTask) -> Boolean = { false }) {
    if (role == Role.sitter || (area == HouseholdArea.Members && role != Role.owner)) return
    var inviteEditor by remember(area) { mutableStateOf(false) }
    var memberEditor by remember(area) { mutableStateOf<HouseholdMember?>(null) }
    var taskEditor by remember(area, state.target) { mutableStateOf<CustomTask?>(null) }
    var confirmation by remember(area) { mutableStateOf<Pair<String, String>?>(null) }
    var link by remember(area) { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writable = online && state.loaded && !state.loading && !state.saving && !state.blocked && !state.updateRequired && !submitting
    fun submit(action: suspend () -> Unit) { scope.launch { submitting = true; try { action() } finally { submitting = false } } }
    fun back() {
        if (submitting || state.saving) return
        if (inviteEditor || memberEditor != null || taskEditor != null) {
            inviteEditor = false; memberEditor = null; taskEditor = null; link = null; onClear()
        } else onBack()
    }
    BackHandler { back() }
    Scaffold(topBar = { TopAppBar(title = { Text(when {
        inviteEditor -> "Invite a member"; memberEditor != null -> "Edit member"; taskEditor != null -> if (taskEditor!!.id == null) "Add custom task" else "Edit custom task"; else -> area.title
    }) }, navigationIcon = { IconButton(onClick = ::back, enabled = !submitting && !state.saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!online) Text("Actions are unavailable offline. Reconnect and refresh to make changes.")
            if (state.updateRequired) Text("Update Fytorium. This server needs a newer app.")
            if (state.loading || state.saving || submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onRefresh, enabled = online && !state.loading && !state.saving && !submitting) { Text("Refresh ${area.title.lowercase()}") }
            if (confirmation == null) HouseholdError(state.errors["_form"])
            when {
                inviteEditor -> {
                    if (link == null) InviteForm(state.plants, writable) { r, p -> submit { link = onCreateInvite(r, p) } }
                    else {
                        Text("Copy this link now. It appears only here, once, and expires after seven days.")
                        Button(onClick = { link?.let(onShare) }, enabled = online) { Text("Share invite link") }
                        TextButton(onClick = { link = null; inviteEditor = false }) { Text("Hide link") }
                    }
                }
                memberEditor != null -> MemberForm(memberEditor!!, state.plants, writable) { r, p -> submit { if (onMember(memberEditor!!, r, p)) memberEditor = null } }
                taskEditor != null -> TaskForm(taskEditor!!, state.errors, writable) { task -> submit { if (onTask(task)) taskEditor = null } }
                area == HouseholdArea.Settings -> HouseholdSettingsForm(state, role == Role.owner, writable,
                    onMisting = { submit { onMisting(it) } }, onLocation = { submit { onLocation(it) } }, onFeed = { submit { onFeed(it) } })
                area == HouseholdArea.Members -> {
                    Button(onClick = { onClear(); inviteEditor = true }, enabled = writable) { Text("Create invite") }
                    HouseholdHeading("Household members")
                    for (member in state.members) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HouseholdHeading(member.name)
                            Text("Role: ${member.role}")
                            if (member.roleOptions.isNotEmpty() || member.role == "sitter") OutlinedButton(onClick = { onClear(); memberEditor = member }, enabled = writable) { Text("Edit member") }
                            if (member.canRemove) OutlinedButton(onClick = { onClear(); confirmation = "member" to member.id }, enabled = writable) { Text("Remove member") }
                        }
                    }
                    HouseholdHeading("Pending invites")
                    if (state.loaded && state.invites.isEmpty()) Text("No pending invites.")
                    for (invite in state.invites) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${invite.role} · Expires ${invite.expires}")
                            for (plant in state.plants.filter { it.id in invite.plantIds }) Text(plant.name)
                            OutlinedButton(onClick = { onClear(); confirmation = "invite" to invite.id }, enabled = writable) { Text("Revoke invite") }
                        }
                    }
                }
                area == HouseholdArea.Tasks -> {
                    com.epaflix.fytorium.Choice("Target", state.target?.let { "${it.kind}: ${it.name}" }.orEmpty(),
                        (state.plants + state.sites).map { "${it.kind}:${it.id}" to "${it.kind}: ${it.name}" }, writable) { key ->
                        (state.plants + state.sites).firstOrNull { "${it.kind}:${it.id}" == key }?.let(onTarget)
                    }
                    if (state.target != null) {
                        Text(if (state.target.kind == "site") "Each active plant on this site gets its own task and care history, including plants added later." else "These tasks belong to this plant.")
                        Text("First due date is the later of plant addition or task creation, plus the interval.")
                        Button(onClick = { onClear(); taskEditor = CustomTask() }, enabled = writable) { Text("Add custom task") }
                        for (task in state.tasks) Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HouseholdHeading(task.name); Text("Interval in days: ${task.interval}")
                                OutlinedButton(onClick = { onClear(); taskEditor = task }, enabled = writable) { Text("Edit task") }
                                OutlinedButton(onClick = { onClear(); confirmation = "task" to task.id!! }, enabled = writable) { Text("Delete task") }
                            }
                        }
                        Text("Deleting a task removes its reminders everywhere. Its name and care events stay in history.")
                    }
                }
            }
        }
    }
    confirmation?.let { (kind, id) ->
        val title = when (kind) { "member" -> "Remove member?"; "invite" -> "Revoke invite?"; else -> "Delete task?" }
        AlertDialog(onDismissRequest = { if (!submitting) { confirmation = null; onClear() } }, title = { Text(title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (kind) { "member" -> "Remove this member and all their devices?"; "invite" -> "Revoke this invite?"; else -> "Deleting a task removes its reminders everywhere. Its name and care events stay in history." })
                HouseholdError(state.errors["_form"])
            } },
            confirmButton = { TextButton(onClick = { submit {
                val success = when (kind) { "member" -> onRemove(id); "invite" -> onRevoke(id); else -> state.tasks.firstOrNull { it.id == id }?.let { onDeleteTask(it) } ?: false }
                if (success) confirmation = null
            } }, enabled = writable) { Text(when (kind) { "member" -> "Confirm remove"; "invite" -> "Confirm revoke"; else -> "Confirm delete" }) } },
            dismissButton = { TextButton(onClick = { confirmation = null; onClear() }, enabled = !submitting && !state.saving) { Text("Cancel") } })
    }
}

@Composable internal fun HouseholdError(message: String?) { message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
@Composable internal fun HouseholdHeading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
