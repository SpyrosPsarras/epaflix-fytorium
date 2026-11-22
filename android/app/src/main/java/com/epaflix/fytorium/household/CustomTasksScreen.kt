package com.epaflix.fytorium.household

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.plants.DiscardDialog
import com.epaflix.fytorium.plants.Heading
import com.epaflix.fytorium.plants.PlantCover
import com.epaflix.fytorium.plants.PlantError
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val tasksOffline = "Changing tasks needs a connection."

data class HouseholdTask(val id: String, val name: String, val interval: Int, val plantId: String?, val siteId: String?,
    val targetName: String, val targetSite: String?, val targetStatus: String?, val targetPlants: Int?)
data class TaskSite(val id: String, val name: String, val plants: Int)
// A task's plant or site, as the form shows it; saved with the draft.
data class TaskFor(val kind: String, val id: String, val name: String, val site: String? = null) : java.io.Serializable
data class TasksState(val tasks: List<HouseholdTask> = emptyList(), val sites: List<TaskSite> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val blocked: Boolean = false, val updateRequired: Boolean = false, val errors: Map<String, String> = emptyMap(),
    val readError: String? = null, val updatedAt: Long? = null)
private class TaskFailure(val status: Int, val errors: Map<String, String>) : Exception()

// The household's custom tasks and sites; the last copy stays in the care cache so the list reads offline (W5b-P27).
class CustomTaskRepository(private val api: ApiClient, private val allowed: () -> Boolean, private val connected: () -> Boolean,
    private val cached: (String) -> Pair<String, Long>? = { null }, private val keep: (String, String) -> Long? = { _, _ -> null }) {
    private val mutable = MutableStateFlow(TasksState())
    val state = mutable.asStateFlow()
    fun clearErrors() { mutable.value = mutable.value.copy(errors = emptyMap()) }
    private fun id(id: String): String { require(id.matches(Regex("[A-Za-z0-9_-]+"))); return id }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String =
        api.request(path, method = method, body = body).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val errors = json?.optJSONObject("errors")?.let { fields -> fields.keys().asSequence().associateWith { fields.getString(it) } }
                    ?: mapOf("_form" to (json?.optString("message")?.takeIf { it.isNotBlank() } ?: "Could not reach Fytorium. Try again."))
                throw TaskFailure(response.code, errors)
            }
            text
        }
    private fun parse(json: JSONObject): Pair<List<HouseholdTask>, List<TaskSite>> {
        val tasks = JSONArray(json.getString("tasks")).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }.map {
            HouseholdTask(it.getString("id"), it.getString("name"), it.getInt("interval_days"), it.nullable("plant_id"), it.nullable("site_id"),
                it.optString("target_name"), it.nullable("target_site_name"), it.nullable("target_status"), if (it.isNull("target_plant_count")) null else it.optInt("target_plant_count"))
        }
        val sites = JSONArray(json.getString("sites")).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }.map {
            TaskSite(it.getString("id"), it.getString("name"), it.optInt("plant_count"))
        }
        return tasks to sites
    }
    @Synchronized fun refresh(): Boolean {
        if (!state.value.loaded && allowed()) cached(cacheKey)?.let { (json, at) ->
            runCatching { parse(JSONObject(json)) }.getOrNull()?.let { (tasks, sites) -> mutable.value = mutable.value.copy(tasks = tasks, sites = sites, loaded = true, updatedAt = at) } }
        return run(false) {
            val json = JSONObject().put("tasks", request("/api/custom-tasks")).put("sites", request("/api/sites"))
            val (tasks, sites) = parse(json)
            if (allowed()) { val at = keep(cacheKey, json.toString()) ?: System.currentTimeMillis()
                mutable.value = mutable.value.copy(tasks = tasks, sites = sites, loaded = true, blocked = false, readError = null, updatedAt = at) }
        }
    }
    @Synchronized fun save(taskId: String?, target: TaskFor, name: String, interval: Int): Boolean = run(true) {
        val body = JSONObject().put("name", name).put("interval_days", interval)
        if (taskId == null) body.put("${target.kind}_id", target.id)
        request(taskId?.let { "/api/custom-tasks/${id(it)}" } ?: "/api/custom-tasks", if (taskId == null) "POST" else "PUT", body)
    }.also { if (it) refresh() }
    @Synchronized fun delete(taskId: String): Boolean = run(true) { request("/api/custom-tasks/${id(taskId)}", "DELETE") }.also { if (it) refresh() }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = TasksState(); return false }
        if (write && (mutable.value.blocked || mutable.value.updateRequired)) return false
        mutable.value = mutable.value.copy(busy = true, errors = if (write) emptyMap() else mutable.value.errors)
        return try {
            if (!connected()) throw IOException()
            action()
            allowed()
        } catch (error: Exception) {
            val errors = if (error is TaskFailure) error.errors else mapOf("_form" to "Could not reach Fytorium. Try again.")
            // A failed read belongs to the page's line; only a write's failure goes into its form.
            if (allowed()) mutable.value = mutable.value.copy(errors = if (write) errors else mutable.value.errors,
                readError = if (write) mutable.value.readError else errors.values.firstOrNull(),
                blocked = error !is TaskFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = mutable.value.updateRequired || (error is TaskFailure && error.status == 426))
            false
        } finally { mutable.value = if (allowed()) mutable.value.copy(busy = false) else TasksState() }
    }
    private companion object { const val cacheKey = "custom-tasks" }
}

private fun every(days: Int) = if (days == 1) "Every day" else "Every $days days"
private fun validName(name: String) = name.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= 60 }
private fun validInterval(text: String) = text.trim().toIntOrNull()?.takeIf { it in 1..365 }

@Composable fun CustomTasksRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit, onFormDirty: (() -> String?) -> Unit = {},
    onUpdateRequired: () -> Unit = {}, answers: Int = 0, onReceipt: (String) -> Unit = {}, thumbnails: Thumbnails? = null, fixed: TaskFor? = null,
    cached: (String) -> Pair<String, Long>? = { null }, keep: (String, String) -> Long? = { _, _ -> null }) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    // Keyed by the member id, never the member object: its hash changes per process (W5b-P29).
    val repository = remember(api, member?.id) { CustomTaskRepository(api,
        { currentCare.member?.id == member?.id && member != null && currentCare.member?.role != com.epaflix.fytorium.Role.sitter },
        { currentConnected() && !currentCare.updateRequired }, cached, keep) }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } }
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
    ReloadOnAnswer(answers, state.busy, { repository.state.value.run { readError != null || blocked } }) { withContext(Dispatchers.IO) { repository.refresh() } }
    val online = connected() && !care.updateRequired
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) { if (online && !wasOnline) reload(); wasOnline = online }
    ResumeReload(::reload)
    if (member == null || member.role == com.epaflix.fytorium.Role.sitter) return
    CustomTasksScreen(state, care, online, onBack = onBack, onFormDirty = onFormDirty, onReceipt = onReceipt, thumbnails = thumbnails, fixed = fixed,
        onRefresh = ::reload, onPull = { withContext(Dispatchers.IO) { repository.refresh() } }, onClear = repository::clearErrors,
        onSave = { id, target, name, interval -> withContext(Dispatchers.IO) { repository.save(id, target, name, interval) } },
        onDelete = { withContext(Dispatchers.IO) { repository.delete(it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CustomTasksScreen(state: TasksState, care: CareState, online: Boolean = true, onBack: () -> Unit = {}, onFormDirty: (() -> String?) -> Unit = {},
    onReceipt: (String) -> Unit = {}, thumbnails: Thumbnails? = null, fixed: TaskFor? = null, onRefresh: () -> Unit = {}, onPull: suspend () -> Unit = { onRefresh() },
    onClear: () -> Unit = {}, onSave: suspend (String?, TaskFor, String, Int) -> Boolean = { _, _, _, _ -> false }, onDelete: suspend (String) -> Boolean = { false }) {
    // "list", "pick" or "form"; a plant page's Add a task opens straight on the form for its plant (W5b-P18).
    var page by rememberSaveable { mutableStateOf(if (fixed != null) "form" else "list") }
    var target by rememberSaveable { mutableStateOf(fixed) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = (state.loaded || fixed != null) && !state.busy && !state.blocked && !state.updateRequired && !submitting
    fun write(action: () -> Unit) { if (!online) onReceipt(tasksOffline) else if (ready) action() }
    val task = state.tasks.firstOrNull { it.id == editing }
    val changed = page == "form" && (if (task == null) name.isNotBlank() || interval.isNotBlank() else name != task.name || interval != task.interval.toString())
    val about = target?.let { if (it.kind == "site") "every plant in ${it.name}" else it.name }
    val discardBody = when { !changed -> null; task == null -> "Your new task for $about is not saved yet."; else -> "Your changes to ${task.name} are not saved yet." }
    fun close() {
        onClear(); name = ""; interval = ""; checked = false; editing = null; query = ""
        if (fixed != null) onBack() else { target = null; page = "list" }
    }
    fun back() = when {
        page == "form" && changed -> discarding = true
        page == "form" && editing == null && fixed == null -> { onClear(); name = ""; interval = ""; checked = false; target = null; page = "pick" }
        page == "form" -> close()
        // Back from Change returns to the task being typed; only the first picker returns to the list.
        page == "pick" -> { query = ""; page = if (target != null) "form" else "list" }
        else -> onBack()
    }
    val currentDirty by rememberUpdatedState(onFormDirty)
    SideEffect { onFormDirty { discardBody } }
    DisposableEffect(Unit) { onDispose { currentDirty { null } } }
    BackHandler { back() }
    val status = when {
        !online -> (care.copy(lastUpdated = state.updatedAt).connectionLine(false) ?: "Offline") to "This phone can't reach Fytorium. You can still see the saved tasks. $tasksOffline"
        state.readError != null -> "Custom tasks didn't load" to state.readError
        state.blocked -> "Custom tasks can't be changed right now" to listOfNotNull(state.errors["_form"], "Try again to reload the tasks.").joinToString(" ")
        else -> null
    }
    val title = when { page == "pick" -> "For which plant or site?"; page == "form" && task != null -> "Edit task"; page == "form" -> "New task"; else -> "Custom tasks" }
    Scaffold(topBar = {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { online && (state.readError != null || state.blocked) }) { Text(title) } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when (page) {
            "pick" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search plants and sites") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, modifier = Modifier.fillMaxWidth())
                val wanted = query.trim()
                val sites = state.sites.filter { it.name.contains(wanted, ignoreCase = true) }.sortedBy { it.name.lowercase() }
                // Graveyard plants get no reminders, so they are not offered (W5b-P16).
                val plants = care.plants.filter { it.status == "active" && listOfNotNull(it.displayName, it.siteName).any { n -> n.contains(wanted, ignoreCase = true) } }
                    .sortedBy { it.displayName.lowercase() }
                if (sites.isNotEmpty()) Heading("Sites", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
                for (site in sites) ListItem(headlineContent = { Text(site.name) }, supportingContent = { Text(if (site.plants == 1) "1 plant" else "${site.plants} plants") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Add a task for ${site.name}", role = androidx.compose.ui.semantics.Role.Button) {
                        target = TaskFor("site", site.id, site.name); page = "form" })
                if (plants.isNotEmpty()) Heading("Plants", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
                for (plant in plants) ListItem(headlineContent = { Text(plant.displayName) }, supportingContent = plant.siteName?.let { { Text(it) } },
                    leadingContent = { PlantCover(plant, thumbnails, Modifier.size(40.dp).clip(CircleShape).clearAndSetSemantics {}, MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Add a task for ${plant.displayName}", role = androidx.compose.ui.semantics.Role.Button) {
                        target = TaskFor("plant", plant.id, plant.displayName, plant.siteName); page = "form" })
                if (sites.isEmpty() && plants.isEmpty()) Text("Nothing called \u201c$wanted\u201d", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "form" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val shownTarget = target ?: task?.let { TaskFor(if (it.siteId != null) "site" else "plant", it.siteId ?: it.plantId!!, it.targetName, it.targetSite) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shownTarget?.let { if (it.kind == "site") "For every plant in ${it.name}, also plants added later" else "For ${it.name}" + it.site?.let { s -> " · $s" }.orEmpty() }.orEmpty(),
                        Modifier.weight(1f))
                    // A saved task's target cannot change (server rule); a plant page's task is for that plant.
                    if (task == null && fixed == null) TextButton(onClick = { page = "pick" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
                }
                val nameError = (if (checked && !validName(name)) "Enter a name from 1 to 60 characters." else null) ?: state.errors["name"]
                OutlinedTextField(name, { name = it; onClear() }, label = { Text("Task name") }, singleLine = true, isError = nameError != null,
                    supportingText = { Text(nameError ?: "Up to 60 characters") }, modifier = Modifier.fillMaxWidth())
                val days = validInterval(interval)
                val intervalError = (if (checked && days == null) "Enter whole days from 1 to 365." else null) ?: state.errors["interval_days"]
                OutlinedTextField(interval, { interval = it; onClear() }, label = { Text("Repeat every") }, singleLine = true, suffix = { Text("days") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = intervalError != null,
                    supportingText = { Text(intervalError ?: "1 to 365 days") }, modifier = Modifier.fillMaxWidth())
                val today = care.today?.today
                if (task == null && days != null && today != null) Text("First due " + shortDate(java.time.LocalDate.parse(today).plusDays(days.toLong()).toString()) +
                    if (shownTarget?.kind == "site") " for the plants there now." else "")
                Button(onClick = {
                    checked = true
                    val ok = shownTarget != null && validName(name) && days != null
                    if (ok) write { scope.launch {
                        submitting = true
                        try {
                            if (onSave(editing, shownTarget!!, name.trim(), days!!)) {
                                onReceipt(when { task != null -> "Saved ${name.trim()}"; shownTarget.kind == "site" -> "Added ${name.trim()} for every plant in ${shownTarget.name}"
                                    else -> "Added ${name.trim()} for ${shownTarget.name}" })
                                close()
                            }
                        } finally { submitting = false }
                    } }
                }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!online) stateDescription = tasksOffline }) { Text("Save task") }
                TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                PlantError(state.errors["_form"].takeIf { !deleting })
                if (task != null) TextButton(onClick = { write { onClear(); deleting = true } }, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { if (!online) stateDescription = tasksOffline }) { Text("Delete task") }
            }
            else -> PullToRefreshBox(isRefreshing = pulling, onRefresh = { pulling = true; scope.launch { try { onPull() } finally { pulling = false } } }, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { write { onClear(); page = "pick" } }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)
                        .semantics { if (!online) stateDescription = tasksOffline }) { Text("Add task") }
                    if (state.loaded && state.tasks.isEmpty()) Text("No custom tasks yet. Add one for a plant or for every plant on a site.", Modifier.padding(16.dp))
                    // Sites first, then active plants, then graveyard plants, each by name (W5b-P15).
                    val groups = state.tasks.groupBy { (it.siteId ?: it.plantId)!! }.values.sortedWith(compareBy(
                        { it.first().siteId == null }, { it.first().targetStatus == "graveyard" }, { it.first().targetName.lowercase() }))
                    for (group in groups) {
                        val first = group.first()
                        Heading(first.targetName, MaterialTheme.typography.titleMedium, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                        Text(when {
                            first.siteId != null -> "Site · every plant here, now ${first.targetPlants ?: 0}"
                            first.targetStatus == "graveyard" -> "In the graveyard. Its tasks pause until it is restored."
                            else -> "Plant" + first.targetSite?.let { " in $it" }.orEmpty()
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        for (row in group.sortedBy { it.name.lowercase() }) ListItem(headlineContent = { Text(row.name) }, supportingContent = { Text(every(row.interval)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClickLabel = "Edit ${row.name}", role = androidx.compose.ui.semantics.Role.Button) {
                                onClear(); editing = row.id; target = null; name = row.name; interval = row.interval.toString(); checked = false; page = "form" })
                    }
                }
            }
        }
        if (state.busy || submitting) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    } }
    if (discarding) DiscardDialog("Discard changes?", discardBody.orEmpty(), onKeep = { discarding = false }) { discarding = false; close() }
    if (deleting) task?.let { doomed ->
        AlertDialog(onDismissRequest = { if (!submitting) { deleting = false; onClear() } },
            // A generic title, with the task named in the body (W3 rule).
            title = { DialogTitle("Delete task?") },
            text = { DialogBody {
                Text(if (doomed.siteId != null) "Every plant in ${doomed.targetName} stops getting ${doomed.name}. Past care stays in history."
                    else "${doomed.targetName} stops getting ${doomed.name}. Its past care stays in history.")
                PlantError(state.errors["_form"])
            } },
            confirmButton = { TextButton(onClick = { write { scope.launch {
                submitting = true
                try { if (onDelete(doomed.id)) { deleting = false; onReceipt("Deleted ${doomed.name}"); close() } } finally { submitting = false }
            } } }, enabled = !submitting, modifier = Modifier.semantics { if (!online) stateDescription = tasksOffline }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
}
