package com.epaflix.fytorium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role as SemanticsRole

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun TaskScreen(state: CareState, view: String, onView: (String) -> Unit = {}, onRefresh: () -> Unit = {},
                           onAction: (CareTask, String, PotDetails?) -> Unit = { _, _, _ -> },
                           onHandleAll: (String) -> Unit = {}, onSettings: (TaskSettings) -> Unit = {},
                           onLoadPot: (String) -> Unit = {}, feedContent: @Composable (() -> Unit) -> Unit = {}) {
    val taskAnchor = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val list = if (view == "upcoming") state.upcoming else state.today
    var repot by remember { mutableStateOf<CareTask?>(null) }
    repot?.let { task ->
        BackHandler { repot = null }
        RepotForm(task, state, onDismiss = { repot = null }, onSave = { pot -> onAction(task, "repot", pot) })
        return
    }
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen && list != null) ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
        ViewSettings(list.settings, state.writable) { onSettings(it); settingsOpen = false }
    }
    val handlePlants = if (view == "today" && state.member?.role in listOf(com.epaflix.fytorium.Role.owner, com.epaflix.fytorium.Role.family))
        list?.sections.orEmpty().flatMap { it.tasks }.filter { task ->
            task.status == "due" && task.dueOn != null && task.dueOn <= list!!.today &&
                (state.handled[task.plantId + ":" + task.key] ?: task.completedEvent) == null
        }.map { it.plantId }.toSet() else emptySet()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Fytorium") }, actions = {
            if (state.member?.role != com.epaflix.fytorium.Role.sitter && list != null) IconButton(onClick = { settingsOpen = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Task view settings")
            }
        })
    }) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        PrimaryTabRow(selectedTabIndex = if (view == "upcoming") 1 else 0) {
            listOf("today" to "Today", "upcoming" to "Upcoming").forEach { (key, label) ->
                Tab(selected = view == key, onClick = { onView(key) }, text = { Text(label) }, modifier = Modifier.testTag("view-$key"))
            }
        }
        PullToRefreshBox(isRefreshing = state.busy, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            list?.let { Text(it.today) }
            if (view == "upcoming") Text("Next 14 calendar days, including today")
            OfflineNotice(state)
            if (view == "today") feedContent { scope.launch { taskAnchor.bringIntoView() } }
            if (list == null || list.sections.isEmpty()) Text(if (state.busy) "Loading tasks…" else "No tasks in this view.", modifier = Modifier.bringIntoViewRequester(taskAnchor))
            val shownPlants = mutableSetOf<String>()
            list?.sections?.forEach { section ->
                Text(section.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }
                    .then(if (section == list.sections.first()) Modifier.bringIntoViewRequester(taskAnchor) else Modifier))
                if (section.tasks.isEmpty()) Text("No tasks due today.")
                section.tasks.forEach { task ->
                    val event = state.handled[task.plantId + ":" + task.key] ?: task.completedEvent
                    Card(Modifier.fillMaxWidth().testTag("task:${task.plantId}:${task.key}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(task.plantName, style = MaterialTheme.typography.titleMedium)
                            Text(task.label + if (task.siteName.isNotBlank()) " · ${task.siteName}" else "")
                            Text(event?.label?.let { it + if (task.status == "due") " · Next ${task.actionLabel}: ${task.dueOn}" else "" } ?: if (task.lateDays > 0) "${task.lateDays}d late" else if (task.dueOn == list.today) "Due today" else "Due ${task.dueOn}")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.member?.let { member -> task.actions(list.today, member, event).forEach { action ->
                                    OutlinedButton(onClick = {
                                        if (action == "repot") { repot = task; onLoadPot(task.plantId) } else onAction(task, action, null)
                                    }, enabled = state.writable, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(when (action) { "complete" -> "Done"; "snooze" -> "Snooze 2 days"; "skip" -> "Skip"; "undo" -> "Undo"; else -> "Repot with new pot and soil" })
                                    }
                                } }
                            }
                            if (task.plantId in handlePlants && shownPlants.add(task.plantId)) OutlinedButton(onClick = { onHandleAll(task.plantId) }, enabled = state.writable,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Handle all tasks for ${task.plantName}") }
                            if (task.basedOn.isNotEmpty()) Provenance(task.basedOn)
                        }
                    }
                }
            }
        }
        }
      }
    }
}

@Composable fun OfflineNotice(state: CareState) {
    if (state.offline || state.serverUnavailable) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.offline) state.offlineBanner() else state.serverBanner())
                Text(if (state.offline) "Actions are unavailable offline. Reconnect and refresh to make changes."
                    else "Actions are unavailable until Fytorium responds. Refresh to try again.")
            }
        }
    } else state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun ViewSettings(settings: TaskSettings, enabled: Boolean, save: (TaskSettings) -> Unit) {
    var sort by remember(settings) { mutableStateOf(settings.sort) }
    var filter by remember(settings) { mutableStateOf(settings.filter) }
    var completed by remember(settings) { mutableStateOf(settings.showCompleted) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Task view settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Choice("Sort tasks", sort, listOf("task" to "Task type", "plant" to "Plant", "site" to "Site"), enabled) { sort = it }
        Choice("Filter", filter, listOf("all" to "All", "indoor" to "Indoor", "outdoor" to "Outdoor"), enabled) { filter = it }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = completed, enabled = enabled,
            role = SemanticsRole.Checkbox, onValueChange = { completed = it }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(completed, onCheckedChange = null, enabled = enabled)
            Text("Show completed")
        }
        Button(onClick = { save(TaskSettings(sort, filter, completed)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save task view") }
    }
}

@Composable internal fun Choice(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("$label: " + (choices.firstOrNull { it.first == value }?.second ?: value))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { change(key); expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}
@Composable internal fun Provenance(bases: List<Basis>) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Based on") }
    if (expanded) bases.forEach { basis ->
        Text(basis.input, style = MaterialTheme.typography.titleSmall)
        Text((basis.value?.toString() ?: "not set") + (basis.factor?.let { " · ×$it" } ?: ""))
        Text(basis.adjustment)
        Text("Source: ${basis.source}" + (basis.ruleId?.let { " · $it" } ?: "") + (basis.fetchedAt?.let { " · Fetched $it" } ?: ""))
        basis.reason?.let { Text(it) }
    }
}
@Composable private fun RepotForm(task: CareTask, state: CareState, onDismiss: () -> Unit, onSave: (PotDetails) -> Unit) {
    val plant = state.detail?.plant?.takeIf { it.id == task.plantId }
    var pot by remember(plant) { mutableStateOf(plant?.potType ?: "plastic") }
    var soil by remember(plant) { mutableStateOf(plant?.soilType ?: "all_purpose") }
    var size by remember(plant) { mutableStateOf(plant?.potSizeCm?.toString() ?: "") }
    val valid = size.isBlank() || (size.toIntOrNull()?.let { it > 0 } == true)
    val event = state.handled[task.plantId + ":" + task.key]
    LaunchedEffect(event) { if (event?.previousPot == true) onDismiss() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Repot ${task.plantName}", style = MaterialTheme.typography.headlineMedium)
            Text("Save the new pot and soil and record repotting today. Undo restores the previous pot and schedule.")
            Choice("Pot type", pot, listOf("plastic", "terracotta", "ceramic", "glass", "metal", "wood", "fabric", "in_ground", "other").map { it to it.replace('_', ' ') }, state.writable) { pot = it }
            Choice("Soil type", soil, listOf("all_purpose", "cactus", "orchid", "aroid", "other").map { it to it.replace('_', ' ') }, state.writable) { soil = it }
            OutlinedTextField(size, { size = it }, label = { Text("Pot size in cm") }, isError = !valid, enabled = state.writable)
            if (!valid) Text("Use a positive whole number, or leave blank for unknown.")
            OfflineNotice(state)
            Button(onClick = { onSave(PotDetails(pot, size.toIntOrNull(), soil)) }, enabled = valid && state.writable && plant != null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Save repot") }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }
}
