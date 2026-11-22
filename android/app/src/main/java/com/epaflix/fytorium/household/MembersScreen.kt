package com.epaflix.fytorium.household

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.LeaveDialog
import com.epaflix.fytorium.plants.Heading
import com.epaflix.fytorium.plants.PlantCover
import com.epaflix.fytorium.plants.PlantError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal const val membersOffline = "Changing members needs a connection."
internal const val inviteDraftBody = "No invite is created until you tap Create invite link."

// Invite links live only in this process (W5b-P11): never in saved state, Room, diagnostics or logs. Cleared at log out.
internal object InviteLinks {
    private val links = mutableMapOf<String, String>()
    @Synchronized fun put(id: String, link: String) { links[id] = link }
    @Synchronized fun get(id: String): String? = links[id]
    @Synchronized fun drop(id: String) { links.remove(id) }
    @Synchronized fun clear() = links.clear()
}

// What the link page keeps across process death: never the link itself.
data class InviteDraft(val id: String, val role: String, val expiresAt: String, val plantIds: List<String>) : java.io.Serializable

internal fun expiryDate(expiresAt: String): String {
    val day = Instant.parse(expiresAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return shortDate(day.toString()) + if (day.year != java.time.LocalDate.now().year) " ${day.year}" else ""
}
// "Spider Plant, Thyme" or, past three, "Spider Plant, Thyme and 2 more".
internal fun shortList(names: List<String>) = if (names.size <= 3) names.joinToString(", ") else names.take(2).joinToString(", ") + " and ${names.size - 2} more"
private fun sentenceList(names: List<String>) = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

// The shell's question before a tab or notification leaves one of these pages: title, keep button, leave button.
internal fun leaveQuestion(body: String): Triple<String, String, String> = when {
    body == "The location you typed is not saved." -> Triple("Discard these coordinates?", "Keep editing", "Discard")
    body == inviteDraftBody -> Triple("Discard this invite?", "Keep editing", "Discard")
    body.startsWith("You can't see this link again.") -> Triple("Leave without sending the link?", "Stay", "Leave")
    else -> Triple("Discard changes?", "Keep editing", "Discard")
}

private val roleCards = listOf(
    Triple("family", "Family", "Can see and change everything except members, the weather location and Planta import."),
    Triple("sitter", "Sitter", "Sees only the plants you share, and can mark their care done, snooze or skip it. No photos, history or other plants."))

@Composable fun MembersRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit, onFormDirty: (() -> String?) -> Unit = {},
    onUpdateRequired: () -> Unit = {}, answers: Int = 0, onReceipt: (String) -> Unit = {}, thumbnails: Thumbnails? = null,
    cached: (String) -> Pair<String, Long>? = { null }, keep: (String, String) -> Long? = { _, _ -> null }) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    // Keyed by the member id, never the member object: its hash changes per process (W5b-P29).
    val repository = remember(api, member?.id) { HouseholdRepository(api, HouseholdArea.Members,
        { currentCare.member?.takeIf { it.id == member?.id }?.role }, { currentConnected() && !currentCare.updateRequired }, cached, keep) }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun reload() { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } }
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    ReloadOnAnswer(answers, state.loading || state.saving, { repository.state.value.run { readError != null || blocked } }) { withContext(Dispatchers.IO) { repository.refresh() } }
    val online = connected() && !care.updateRequired
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) { if (online && !wasOnline) reload(); wasOnline = online }
    ResumeReload(::reload)
    if (member?.role != com.epaflix.fytorium.Role.owner) return
    MembersScreen(state, care, online, onBack = onBack, onFormDirty = onFormDirty, onReceipt = onReceipt, thumbnails = thumbnails,
        onRefresh = ::reload, onPull = { withContext(Dispatchers.IO) { repository.refresh() } }, onClear = repository::clearErrors,
        onCreateInvite = { role, plants -> withContext(Dispatchers.IO) { repository.createInvite(role, plants) } },
        onShare = { link ->
            val url = java.net.URI(BuildConfig.SERVER_URL).resolve(link).toString()
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, "Share invite link"))
        },
        onCopy = { link ->
            val url = java.net.URI(BuildConfig.SERVER_URL).resolve(link).toString()
            // Marked sensitive, so the system confirmation hides it on Android 13 and later; older versions get a receipt instead.
            val clip = ClipData.newPlainText("Invite link", url).apply {
                if (Build.VERSION.SDK_INT >= 33) description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) } }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < 33) onReceipt("Link copied")
        },
        onMember = { m, r, p -> withContext(Dispatchers.IO) { repository.saveMember(m, r, p) } },
        onRemove = { withContext(Dispatchers.IO) { repository.removeMember(it) } },
        onRevoke = { withContext(Dispatchers.IO) { repository.revokeInvite(it) } })
}

// Once Fytorium answers again, a page with a failed read or paused writes reloads; an answer that arrives mid-operation waits for it to end.
@Composable internal fun ReloadOnAnswer(answers: Int, busy: Boolean, failing: () -> Boolean, reload: suspend () -> Unit) {
    var seen by remember { mutableIntStateOf(answers) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(answers) { if (answers != seen) { seen = answers; pending = true } }
    LaunchedEffect(pending, busy) { if (pending && !busy) { pending = false; if (failing()) reload() } }
}

@Composable internal fun ResumeReload(reload: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(reload)
    DisposableEffect(lifecycle) {
        // Only a return to the app reloads; the first resume comes with the open, which already loads.
        var paused = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) paused = true
            else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && paused) latest()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MembersScreen(state: HouseholdState, care: CareState, online: Boolean = true, onBack: () -> Unit = {}, onFormDirty: (() -> String?) -> Unit = {},
    onReceipt: (String) -> Unit = {}, thumbnails: Thumbnails? = null, onRefresh: () -> Unit = {}, onPull: suspend () -> Unit = { onRefresh() }, onClear: () -> Unit = {},
    onCreateInvite: suspend (String, Set<String>) -> CreatedInvite? = { _, _ -> null }, onShare: (String) -> Unit = {}, onCopy: (String) -> Unit = {},
    onMember: suspend (HouseholdMember, String, Set<String>) -> Boolean = { _, _, _ -> false },
    onRemove: suspend (String) -> Boolean = { false }, onRevoke: suspend (String) -> Boolean = { false }) {
    // "list", "invite", "link" or "member:<id>".
    var page by rememberSaveable { mutableStateOf("list") }
    var inviteRole by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteTicks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var draft by rememberSaveable { mutableStateOf<InviteDraft?>(null) }
    // Revoke and create new: once the old invite is revoked, a retry only creates.
    var revokedOld by rememberSaveable { mutableStateOf<String?>(null) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var memberRole by rememberSaveable { mutableStateOf<String?>(null) }
    var memberTicks by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var revoking by rememberSaveable { mutableStateOf<String?>(null) }
    // The page a pending leave question goes to once confirmed.
    var leaving by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = state.loaded && !state.loading && !state.saving && !state.blocked && !state.updateRequired && !submitting
    fun write(action: () -> Unit) { if (!online) onReceipt(membersOffline) else if (ready) action() }
    fun submit(action: suspend () -> Unit) { scope.launch { submitting = true; try { action() } finally { submitting = false } } }
    val plants = care.plants.filter { it.status == "active" }.sortedWith(compareBy({ it.siteName?.lowercase() }, { it.displayName.lowercase() }))
    fun plantNames(ids: Collection<String>) = ids.mapNotNull { id -> care.plants.firstOrNull { it.id == id }?.displayName ?: state.plants.firstOrNull { it.id == id }?.name }
    val shown = state.members.firstOrNull { "member:${it.id}" == page }
    val link = draft?.let { InviteLinks.get(it.id) }
    val inviteChanged = page == "invite" && inviteRole != null
    val memberChanged = shown != null && ((memberRole ?: shown.role) != shown.role || (memberTicks?.toSet() ?: shown.plantIds) != shown.plantIds)
    val linkLeaveBody = draft?.takeIf { page == "link" && link != null && !sent }?.let {
        "You can't see this link again. The invite still works until ${expiryDate(it.expiresAt)} unless you revoke it under Pending invites." }
    val leaveBody = when {
        inviteChanged -> inviteDraftBody
        memberChanged -> "Your changes to ${shown!!.name} are not saved yet."
        else -> linkLeaveBody
    }
    fun go(target: String) {
        if (page == "link") draft?.let { InviteLinks.drop(it.id) }
        if (target == "list") { inviteRole = null; inviteTicks = emptyList(); draft = null; sent = false; memberRole = null; memberTicks = null }
        onClear(); page = target
    }
    fun leave(target: String) { if (leaveBody != null) leaving = target else go(target) }
    fun back() { if (page == "list") onBack() else leave("list") }
    val currentDirty by rememberUpdatedState(onFormDirty)
    SideEffect { onFormDirty { leaveBody } }
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    BackHandler { back() }
    val status = when {
        !online -> (care.copy(lastUpdated = state.updatedAt).connectionLine(false) ?: "Offline") to
            "This phone can't reach Fytorium. You can still see the saved members and invites. $membersOffline"
        state.readError != null -> "Members didn't load" to state.readError
        state.blocked -> "Members can't be changed right now" to listOfNotNull(state.errors["_form"], "Try again to reload the members.").joinToString(" ")
        else -> null
    }
    val title = when { page == "invite" -> "Invite someone"; page == "link" -> "Invite link"; shown != null -> shown.name; else -> "Members" }
    Scaffold(topBar = {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { online && (state.readError != null || state.blocked) }) { Text(title) } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            page == "invite" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoleCards(inviteRole, true) { inviteRole = it }
                if (inviteRole == "sitter") PlantPicker(plants, inviteTicks.toSet(), thumbnails, true, "No plants chosen. You can share plants later from Members.") { inviteTicks = it.toList() }
                val chosen = inviteRole
                Button(onClick = {
                    when { chosen == null -> onReceipt("Choose Family or Sitter first."); else -> write { submit {
                        val ticks = if (chosen == "sitter") inviteTicks.toSet() else emptySet()
                        onCreateInvite(chosen, ticks)?.let { created ->
                            InviteLinks.put(created.id, created.link)
                            draft = InviteDraft(created.id, created.role, created.expiresAt, ticks.toList()); sent = false
                            inviteRole = null; inviteTicks = emptyList(); page = "link"
                        }
                    } } }
                }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp).semantics {
                    if (chosen == null) stateDescription = "Choose Family or Sitter first." else if (!online) stateDescription = membersOffline
                }) { Text("Create invite link") }
                PlantError(state.errors["_form"])
            }
            page == "link" && draft != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val invite = draft!!
                if (link != null) {
                    Text("Send this link to the person you invite. It works once and expires on ${expiryDate(invite.expiresAt)}. You can't see it again after you leave this page.")
                    Button(onClick = { sent = true; onShare(link) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share link") }
                    OutlinedButton(onClick = { sent = true; onCopy(link) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy link") }
                    TextButton(onClick = { leave("list") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                } else {
                    // Fytorium was closed with the page open: the link is gone by design (W5b-A1).
                    Heading("This link can't be shown again", MaterialTheme.typography.titleMedium)
                    Text("Fytorium was closed, so the link is gone. If you didn't send it, revoke the invite and create a new one.")
                    Button(onClick = { write { submit {
                        if (revokedOld == invite.id || onRevoke(invite.id).also { if (it) revokedOld = invite.id }) onCreateInvite(invite.role, invite.plantIds.toSet())?.let { created ->
                            InviteLinks.put(created.id, created.link)
                            draft = InviteDraft(created.id, created.role, created.expiresAt, invite.plantIds); sent = false
                        }
                    } } }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = membersOffline }) { Text("Revoke and create new") }
                    TextButton(onClick = { go("list") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep the invite") }
                    PlantError(state.errors["_form"])
                }
            }
            shown != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val role = memberRole ?: shown.role
                val ticks = memberTicks?.toSet() ?: shown.plantIds
                if (shown.roleOptions.isNotEmpty()) RoleCards(role, true,
                    familyNote = "Family members see every plant, so their shares are cleared.".takeIf { shown.role == "sitter" }) { memberRole = it }
                if (role == "sitter") PlantPicker(plants, ticks, thumbnails, true, null) { memberTicks = it.toList() }
                Button(onClick = { write { submit {
                    if (onMember(shown, role, if (role == "sitter") ticks else emptySet())) { onReceipt("Saved changes for ${shown.name}"); go("list") }
                } } }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = membersOffline }) { Text("Save") }
                PlantError(state.errors["_form"].takeIf { removing == null })
                if (shown.canRemove) TextButton(onClick = { write { onClear(); removing = shown.id } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove from household") }
            }
            else -> PullToRefreshBox(isRefreshing = pulling, onRefresh = { pulling = true; scope.launch { try { onPull() } finally { pulling = false } } }, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { write { onClear(); page = "invite" } }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)
                        .semantics { if (!online) stateDescription = membersOffline }) { Text("Invite someone") }
                    Heading("People", MaterialTheme.typography.titleMedium, Modifier.padding(start = 16.dp, top = 8.dp))
                    for (person in state.members) {
                        val opens = person.roleOptions.isNotEmpty() || person.canRemove
                        val line = when (person.role) {
                            "owner" -> "Owner"; "family" -> "Family"
                            else -> "Sitter · " + when (person.plantIds.size) { 0 -> "No plants"; 1 -> "1 plant"; else -> "${person.plantIds.size} plants" }
                        }
                        ListItem(headlineContent = { Text(person.name + if (person.id == care.member?.id) " (you)" else "") }, supportingContent = { Text(line) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).then(if (opens) Modifier.clickable(onClickLabel = "Open ${person.name}",
                                role = androidx.compose.ui.semantics.Role.Button) { onClear(); page = "member:${person.id}" } else Modifier))
                    }
                    Heading("Pending invites", MaterialTheme.typography.titleMedium, Modifier.padding(start = 16.dp, top = 8.dp))
                    if (state.loaded && state.invites.isEmpty()) Text("No pending invites.", Modifier.padding(horizontal = 16.dp))
                    for (invite in state.invites) {
                        val names = plantNames(invite.plantIds)
                        ListItem(headlineContent = { Text(if (invite.role == "sitter") "Sitter invite" else "Family invite") },
                            supportingContent = { Text((listOfNotNull(shortList(names).takeIf { invite.role == "sitter" && names.isNotEmpty() }) + "Expires ${expiryDate(invite.expires)}").joinToString(" · ")) },
                            trailingContent = { TextButton(onClick = { write { onClear(); revoking = invite.id } }, modifier = Modifier.heightIn(min = 48.dp)
                                .semantics { if (!online) stateDescription = membersOffline }) { Text("Revoke") } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))
                    }
                }
            }
        }
        if (state.loading || state.saving || submitting) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    } }
    leaving?.let { target ->
        val (question, keepLabel, leaveLabel) = leaveQuestion(leaveBody.orEmpty())
        LeaveDialog(question, leaveBody.orEmpty(), keepLabel, leaveLabel, onKeep = { leaving = null }) { leaving = null; go(target) }
    }
    removing?.let { id ->
        val person = state.members.firstOrNull { it.id == id }
        val name = person?.name ?: "This member"
        AlertDialog(onDismissRequest = { if (!submitting) { removing = null; onClear() } },
            title = { DialogTitle("Remove member?") },
            text = { DialogBody {
                Text("$name is logged out on every device and loses access to this household. Their name stays in care history.")
                PlantError(state.errors["_form"])
            } },
            confirmButton = { TextButton(onClick = { write { submit { if (onRemove(id)) { removing = null; onReceipt("Removed $name"); go("list") } } } }, enabled = !submitting,
                modifier = Modifier.semantics { if (!online) stateDescription = membersOffline }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
    revoking?.let { id ->
        val invite = state.invites.firstOrNull { it.id == id }
        val names = invite?.let { plantNames(it.plantIds) }.orEmpty()
        val kind = if (invite?.role == "sitter") "sitter" else "family"
        AlertDialog(onDismissRequest = { if (!submitting) { revoking = null; onClear() } },
            title = { DialogTitle("Revoke this invite?") },
            text = { DialogBody {
                Text("The $kind invite" + (if (kind == "sitter" && names.isNotEmpty()) " for ${sentenceList(names)}" else "") + " stops working." +
                    (invite?.let { " It would have expired on ${expiryDate(it.expires)}." } ?: ""))
                PlantError(state.errors["_form"])
            } },
            confirmButton = { TextButton(onClick = { write { submit { if (onRevoke(id)) { revoking = null; onReceipt("Revoked the $kind invite") } } } }, enabled = !submitting,
                modifier = Modifier.semantics { if (!online) stateDescription = membersOffline }) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { revoking = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
}

// W5b-P10: one selectable card per role, none chosen at first, each read as its title, sentence and state.
@Composable private fun RoleCards(role: String?, enabled: Boolean, familyNote: String? = null, change: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, title, sentence) in roleCards) {
            val chosen = role == key
            OutlinedCard(Modifier.fillMaxWidth().selectable(chosen, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton) { change(key) },
                border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(chosen, onClick = null, enabled = enabled)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(sentence, style = MaterialTheme.typography.bodyMedium)
                        if (key == "family") familyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

// W5b-P10: one toggleable row per active plant with its cover, name and site, so twin names stay apart.
@Composable private fun PlantPicker(plants: List<Plant>, selected: Set<String>, thumbnails: Thumbnails?, enabled: Boolean, none: String?, change: (Set<String>) -> Unit) {
    Heading("Plants to share", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
    if (selected.isEmpty()) none?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    for (plant in plants) {
        val on = plant.id in selected
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox) {
            change(if (it) selected + plant.id else selected - plant.id) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The row reads the name; the cover is decoration.
            PlantCover(plant, thumbnails, Modifier.size(40.dp).clip(CircleShape).clearAndSetSemantics {}, MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f)) {
                Text(plant.displayName)
                plant.siteName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Checkbox(on, onCheckedChange = null, enabled = enabled)
        }
    }
}
