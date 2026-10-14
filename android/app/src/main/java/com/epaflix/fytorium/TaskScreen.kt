package com.epaflix.fytorium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.WarningAmber
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role as SemanticsRole

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun TaskScreen(state: CareState, view: String, onView: (String) -> Unit = {}, onRefresh: () -> Unit = {},
                           onAction: (CareTask, String, PotDetails?) -> Unit = { _, _, _ -> },
                           onHandleAll: (String) -> Unit = {}, onSettings: (TaskSettings) -> Unit = {},
                           onLoadPot: (suspend (String) -> Plant)? = null, thumbnails: Thumbnails? = null,
                           onNotifications: () -> Unit = {}, onSearch: () -> Unit = {}, onAddPlant: () -> Unit = {}, onImport: () -> Unit = {},
                           deviceOnline: Boolean = true, now: java.time.Instant? = null, feedContent: @Composable (() -> Unit) -> Unit = {}) {
    val clock by produceState(now ?: java.time.Instant.now(), now) {
        if (now == null) while (true) { value = java.time.Instant.now(); delay(60_000) }
    }
    val photoIds = remember(state.today, state.upcoming) {
        listOfNotNull(state.today, state.upcoming).flatMap { it.sections }.flatMap { it.tasks }.mapNotNull { it.coverPhotoId }.distinct()
    }
    val photos by produceState<Map<String, Bitmap?>>(emptyMap(), thumbnails, state.lastUpdated, photoIds) {
        value = emptyMap()
        val loaded = mutableMapOf<String, Bitmap?>()
        val permits = Semaphore(4)
        coroutineScope {
            photoIds.forEach { id -> launch {
                val bitmap = permits.withPermit { withContext(Dispatchers.IO) {
                    thumbnails?.load(id)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                } }
                // A null entry is a finished, failed load; duplicate rows share this decode.
                loaded[id] = bitmap
                value = loaded.toMap()
            } }
        }
    }
    val pullState = rememberPullToRefreshState()
    // Zero-duration device animations can leave the refresh indicator at its shown offset.
    LaunchedEffect(state.busy) { if (!state.busy) pullState.snapTo(0f) }
    val taskAnchor = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val list = if (view == "upcoming") state.upcoming else state.today
    var repot by remember { mutableStateOf<CareTask?>(null) }
    repot?.let { task ->
        BackHandler { repot = null }
        RepotForm(task, state, onLoadPot, onDismiss = { repot = null }, onSave = { pot -> onAction(task, "repot", pot) })
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
    val presentation = state.todayPresentation(clock)
    val noPlants = state.today != null && state.plants.isEmpty()
    val sections = if (view == "today") presentation.sections else list?.sections.orEmpty().filter { it.tasks.isNotEmpty() }
    val showFab = state.member?.role in listOf(Role.owner, Role.family)
    val canAdd = state.member?.role in listOf(Role.owner, Role.family) && deviceOnline && !state.updateRequired
    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search plants") }
        }, actions = {
            IconButton(onClick = onNotifications) { Icon(Icons.Outlined.Notifications, contentDescription = "My notifications") }
        })
    }, floatingActionButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showFab && list != null && !noPlants) SmallFloatingActionButton(onClick = { settingsOpen = true },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "Task view settings")
            }
            if (state.member?.role in listOf(Role.owner, Role.family)) FloatingActionButton(onClick = { if (canAdd) onAddPlant() },
                modifier = Modifier.size(64.dp).semantics { if (!canAdd) disabled() }) {
                Icon(Icons.Default.Add, contentDescription = "Add plant")
            }
        }
    }) { padding ->
        PullToRefreshBox(isRefreshing = state.busy, state = pullState, onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), indicator = {
                if (state.busy || pullState.distanceFraction > 0f) PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter), state = pullState, isRefreshing = state.busy,
                    containerColor = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }) {
            Column(Modifier.fillMaxSize().testTag("task-list").verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = (if (showFab) 88.dp else 16.dp) + padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(presentation.greeting, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
                Text(presentation.tip, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.today != null && presentation.sections.isNotEmpty()) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(presentation.summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                if (view == "today") feedContent { scope.launch { taskAnchor.bringIntoView() } }
                if (!noPlants) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("today" to "Today", "upcoming" to "Upcoming").forEachIndexed { index, (key, label) ->
                        SegmentedButton(selected = view == key, onClick = { onView(key) }, shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.testTag("view-$key")) { Text(label) }
                    }
                }
                if (view == "upcoming" && !noPlants) Text("Next 14 calendar days, including today", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OfflineNotice(state)
                if (sections.isEmpty()) Column(Modifier.fillMaxWidth().bringIntoViewRequester(taskAnchor).padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    val title = if (view == "upcoming" && state.plants.isNotEmpty() && list != null) "No upcoming tasks" else presentation.emptyTitle
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    if (view == "today" && state.plants.isNotEmpty()) presentation.nextTask?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (state.today != null && state.plants.isEmpty() && state.member?.role in listOf(Role.owner, Role.family)) {
                        Button(onClick = onAddPlant, enabled = canAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add plant") }
                        if (state.member?.role == Role.owner) TextButton(onClick = onImport, enabled = canAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import from Planta") }
                    }
                }
                val shownPlants = mutableSetOf<String>()
                sections.forEachIndexed { index, section ->
                    Card(Modifier.fillMaxWidth().then(if (index == 0) Modifier.bringIntoViewRequester(taskAnchor) else Modifier),
                        shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(if (view == "upcoming" && section.key.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) taskDate(section.key) else section.label, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() })
                        section.tasks.forEachIndexed { rowIndex, task ->
                            if (rowIndex > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            key(task.plantId, task.key) { TaskRow(task, state, list!!.today, photos[task.coverPhotoId],
                                task.plantId in handlePlants && shownPlants.add(task.plantId),
                                onAction = { action -> if (action == "repot") repot = task else onAction(task, action, null) },
                                onHandleAll = { onHandleAll(task.plantId) }, showType = view == "upcoming",
                                photoLoading = task.coverPhotoId != null && !photos.containsKey(task.coverPhotoId)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun OfflineNotice(state: CareState) {
    if (state.offline || state.serverUnavailable) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = "Warning", tint = MaterialTheme.colorScheme.tertiary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.offline) state.offlineBanner() else state.serverBanner(), style = MaterialTheme.typography.titleSmall)
                    Text(if (state.offline) "Actions are unavailable offline. Reconnect and refresh to make changes."
                        else "Actions are unavailable until Fytorium responds. Refresh to try again.")
                }
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
@Composable internal fun RepotForm(task: CareTask, state: CareState, onLoadPot: (suspend (String) -> Plant)? = null, onDismiss: () -> Unit, onSave: (PotDetails) -> Unit) {
    val cached = state.detail?.plant?.takeIf { it.id == task.plantId }
    var pot by remember(task.plantId) { mutableStateOf(cached?.potType ?: "plastic") }
    var soil by remember(task.plantId) { mutableStateOf(cached?.soilType ?: "all_purpose") }
    var size by remember(task.plantId) { mutableStateOf(cached?.potSizeCm?.toString() ?: "") }
    var potEdited by remember(task.plantId) { mutableStateOf(false) }
    var soilEdited by remember(task.plantId) { mutableStateOf(false) }
    var sizeEdited by remember(task.plantId) { mutableStateOf(false) }
    var loading by remember(task.plantId) { mutableStateOf(onLoadPot != null) }
    var error by remember(task.plantId) { mutableStateOf<String?>(null) }
    LaunchedEffect(task.plantId) {
        if (onLoadPot != null) {
            try {
                val plant = onLoadPot(task.plantId)
                if (!potEdited) pot = plant.potType ?: "plastic"
                if (!soilEdited) soil = plant.soilType ?: "all_purpose"
                if (!sizeEdited) size = plant.potSizeCm?.toString().orEmpty()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not load the current pot. Try again." }
            finally { loading = false }
        }
    }
    val enabled = state.writable && !loading
    val valid = size.isBlank() || (size.toIntOrNull()?.let { it > 0 } == true)
    val event = state.handled[task.plantId + ":" + task.key]
    LaunchedEffect(event) { if (event?.previousPot == true) onDismiss() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Repot ${task.plantName}", style = MaterialTheme.typography.headlineMedium)
            Text("Save the new pot and soil and record repotting today. Undo restores the previous pot and schedule.")
            Choice("Pot type", pot, listOf("plastic", "terracotta", "ceramic", "glass", "metal", "wood", "fabric", "in_ground", "other").map { it to it.replace('_', ' ') }, enabled) { potEdited = true; pot = it }
            Choice("Soil type", soil, listOf("all_purpose", "cactus", "orchid", "aroid", "other").map { it to it.replace('_', ' ') }, enabled) { soilEdited = true; soil = it }
            OutlinedTextField(size, { sizeEdited = true; size = it }, label = { Text("Pot size in cm") }, isError = !valid, enabled = enabled)
            if (!valid) Text("Use a positive whole number, or leave blank for unknown.")
            if (loading) Text("Loading current pot…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OfflineNotice(state)
            Button(onClick = { onSave(PotDetails(pot, size.toIntOrNull(), soil)) }, enabled = valid && enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Save repot") }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }
}
