package com.epaflix.fytorium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.WarningAmber
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role as SemanticsRole

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun TaskScreen(state: CareState, view: String, onView: (String) -> Unit = {}, onRefresh: () -> Unit = {},
                           onAction: (CareTask, String) -> Unit = { _, _ -> },
                           onHandleType: (String) -> Unit = {}, onSettings: suspend (TaskSettings) -> Boolean = { true }, refreshing: Boolean = false,
                           onUndoReceipt: (Receipt) -> Unit = {}, onReceiptDone: (Receipt) -> Unit = {},
                           thumbnails: Thumbnails? = null,
                           onSearch: () -> Unit = {}, onAddPlant: () -> Unit = {}, onImport: () -> Unit = {},
                           deviceOnline: Boolean = true, onOpenTask: (CareTask) -> Unit = {}, scrollState: ScrollState = rememberScrollState(),
                           thumbnailLoader: suspend (String) -> Bitmap? = { id -> withContext(Dispatchers.IO) {
                               thumbnails?.load(id)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                           } }, feedContent: @Composable () -> Unit = {}) {
    val photoIds = remember(state.today, state.upcoming) {
        listOfNotNull(state.today, state.upcoming).flatMap { it.sections }.flatMap { it.tasks }.mapNotNull { it.coverPhotoId }.distinct()
    }
    val photoRequest by rememberUpdatedState(photoIds to state.lastUpdated)
    val loadPhoto by rememberUpdatedState(thumbnailLoader)
    val photos by produceState<Map<String, Bitmap?>>(emptyMap(), thumbnails, state.member) {
        val loaded = mutableMapOf<String, Bitmap?>()
        val inFlight = mutableSetOf<String>()
        val permits = Semaphore(4)
        // Pin publication to Main even when a Compose test continuation resumes on an IO thread.
        withContext(Dispatchers.Main.immediate) {
            // Refreshes keep decoded photos and join existing loads instead of cancelling and refetching.
            snapshotFlow { photoRequest }.collect { (ids, _) ->
                loaded.entries.removeAll { it.key !in ids || it.value == null }
                value = loaded.toMap()
                ids.filter { loaded[it] == null && it !in inFlight }.forEach { id ->
                    inFlight.add(id)
                    launch {
                        try {
                            val bitmap = permits.withPermit { loadPhoto(id) }
                            // A null entry is a finished, failed load; duplicate rows share this decode.
                            if (id in photoRequest.first) loaded[id] = bitmap
                            value = loaded.toMap()
                        } finally { inFlight.remove(id) }
                    }
                }
            }
        }
    }
    val pullState = rememberPullToRefreshState()
    // Zero-duration device animations can leave the refresh indicator at its shown offset.
    LaunchedEffect(refreshing) { if (!refreshing) pullState.snapTo(0f) }
    val scope = rememberCoroutineScope()
    val list = if (view == "upcoming") state.upcoming else state.today
    val editor = state.member?.role in listOf(Role.owner, Role.family)
    val presentation = state.todayPresentation()
    val noPlants = state.today != null && state.plants.isEmpty()
    val sections = if (view == "today") presentation.sections else list?.sections.orEmpty().filter { it.tasks.isNotEmpty() }
    val canAdd = editor && deviceOnline && state.writable
    fun saveView(settings: TaskSettings) { scope.launch { onSettings(settings) } }
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen && list != null) ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
        ViewSettings(list.settings, state.writable, state.message, onSettings)
    }
    val snackbar = remember { SnackbarHostState() }
    val blocked = when {
        !deviceOnline || state.offline -> "Adding a plant needs a connection."
        state.serverUnavailable -> "Adding a plant needs Fytorium to respond. Pull down to try again."
        else -> "Update Fytorium to add plants."
    }
    fun adding(action: () -> Unit): () -> Unit = { if (canAdd) action() else scope.launch { snackbar.showSnackbar(blocked) } }
    val addingLook = Modifier.semantics { if (!canAdd) { disabled(); stateDescription = blocked } }
    val blockedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    var receiptHeight by remember { mutableIntStateOf(0) }
    ReceiptSnackbar(state.receipt, snackbar, onUndoReceipt, onReceiptDone)
    Scaffold(snackbarHost = { SnackbarHost(snackbar, Modifier.onSizeChanged { receiptHeight = it.height }) }, topBar = {
        CenterAlignedTopAppBar(title = { Text("Today") }, navigationIcon = {
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search plants") }
        }, actions = {
            if (editor && list != null && !noPlants) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Tune, contentDescription = "Task view") }
            if (editor) IconButton(onClick = adding(onAddPlant), modifier = addingLook) {
                Icon(Icons.Default.Add, contentDescription = "Add plant", tint = if (canAdd) LocalContentColor.current else blockedColor)
            }
        })
    }) { padding ->
        // The viewport ends above the receipt while one shows, so no row scrolls under it.
        val receiptSpace = with(LocalDensity.current) { receiptHeight.toDp() }
        PullToRefreshBox(isRefreshing = refreshing, state = pullState, onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = receiptSpace), indicator = {
                if (refreshing || pullState.distanceFraction > 0f) PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter), state = pullState, isRefreshing = refreshing,
                    containerColor = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }) {
            Column(Modifier.fillMaxSize().testTag("task-list").verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (view == "today") feedContent()
                if (!noPlants) ViewToggle(view, onView)
                if (!noPlants) list?.settings?.let { settings -> ActiveView(settings, editor, state.writable) { saveView(TaskSettings()) } }
                TodayNotice(state)
                if (sections.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    val filter = list?.takeIf { state.plants.isNotEmpty() }?.hidingFilter()
                    val title = if (view == "upcoming" && state.plants.isNotEmpty() && list != null) "No upcoming " + filter?.let { "$it " }.orEmpty() + "tasks" else presentation.emptyTitle
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    presentation.emptyHint?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (view == "today" && state.plants.isNotEmpty()) presentation.nextTask?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (filter != null && editor) TextButton(onClick = { list?.let { saveView(it.settings.copy(filter = "all")) } }, enabled = state.writable,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all") }
                    if (state.today != null && state.plants.isEmpty() && editor) {
                        Button(onClick = adding(onAddPlant), modifier = addingLook.heightIn(min = 48.dp), colors = if (canAdd) ButtonDefaults.buttonColors()
                            else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), contentColor = blockedColor)) { Text("Add plant") }
                        if (state.member?.role == Role.owner) TextButton(onClick = adding(onImport), modifier = addingLook.heightIn(min = 48.dp),
                            colors = if (canAdd) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = blockedColor)) { Text("Import from Planta") }
                    }
                }
                sections.forEach { section ->
                    Card(Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(if (view == "upcoming" && section.key.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) dayLabel(section.key, list!!.today) else section.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() })
                        section.tasks.forEach { task ->
                            key(task.plantId, task.key) { TaskRow(task, state, list!!.today, photos[task.coverPhotoId],
                                onAction = { action -> onAction(task, action) },
                                onOpen = { onOpenTask(task) }, showType = view == "upcoming",
                                photoLoading = task.coverPhotoId != null && !photos.containsKey(task.coverPhotoId)) }
                        }
                        if (view == "today" && editor && section.tasks.any { task ->
                            task.status == "due" && task.dueOn != null && task.dueOn <= list!!.today && task.completedEvent == null
                        }) {
                            val handling = BusyKey.Card(section.key) in state.pending
                            FilledTonalButton(onClick = { onHandleType(section.key) }, enabled = state.writable && !handling,
                                shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.CenterHorizontally)
                                    .padding(top = 12.dp, bottom = 16.dp).heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Handle all ${section.label} tasks" }) {
                                ButtonProgress(handling)
                                Text("Handle all tasks")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun ReceiptSnackbar(receipt: Receipt?, host: SnackbarHostState, onUndo: (Receipt) -> Unit, onDone: (Receipt) -> Unit) {
    val undo by rememberUpdatedState(onUndo)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(receipt) {
        if (receipt == null) return@LaunchedEffect
        try {
            val result = host.showSnackbar(receipt.text, actionLabel = if (receipt.undo != null) "Undo" else null, withDismissAction = !receipt.short,
                duration = if (receipt.short) SnackbarDuration.Short else SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo(receipt) else done(receipt)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Leaving the screen ends the receipt, so returning later does not replay an old one.
            done(receipt)
            throw cancelled
        }
    }
}

@Composable fun OfflineNotice(state: CareState) {
    if (state.offline || state.serverUnavailable) WarningNotice {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.offline) state.offlineBanner() else state.serverBanner(), style = MaterialTheme.typography.titleSmall)
            Text(if (state.offline) "Actions are unavailable offline. Reconnect and refresh to make changes."
                else "Actions are unavailable until Fytorium responds. Refresh to try again.")
        }
    } else state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun TodayNotice(state: CareState) {
    val banner = state.todayBanner()
    if (banner != null) WarningNotice { Text(banner, style = MaterialTheme.typography.bodyMedium) }
    else state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun WarningNotice(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = "Warning", tint = MaterialTheme.colorScheme.tertiary)
            content()
        }
    }
}

@Composable private fun ViewToggle(view: String, onView: (String) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            listOf("today" to "Today", "upcoming" to "Upcoming").forEach { (key, label) ->
                val selected = view == key
                Surface(selected = selected, onClick = { onView(key) }, shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("view-$key").semantics { role = SemanticsRole.Tab }) {
                    Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

private fun activeViewLabels(settings: TaskSettings) = listOfNotNull(
    settings.filterLabel(),
    mapOf("plant" to "Sorted by plant", "site" to "Sorted by site")[settings.sort],
    "Showing others' completed".takeIf { settings.showCompleted })

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ActiveView(settings: TaskSettings, canReset: Boolean, enabled: Boolean, reset: () -> Unit) {
    val labels = activeViewLabels(settings)
    if (labels.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (canReset) TextButton(onClick = reset, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.CenterVertically)) { Text("Reset") }
    }
}

@Composable private fun ViewSettings(settings: TaskSettings, enabled: Boolean, failure: String?, save: suspend (TaskSettings) -> Boolean) {
    var choice by remember(settings) { mutableStateOf(settings) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    fun pick(next: TaskSettings) {
        if (next == choice) return
        val previous = choice
        choice = next; saving = true; saved = null
        scope.launch {
            val ok = save(next)
            saving = false; saved = ok
            if (!ok) choice = previous
        }
    }
    val active = enabled && !saving
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Task view", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Choice("Sort tasks", choice.sort, listOf("task" to "Task type", "plant" to "Plant", "site" to "Site"), active) { pick(choice.copy(sort = it)) }
        Choice("Filter", choice.filter, listOf("all" to "All", "indoor" to "Indoor", "outdoor" to "Outdoor"), active) { pick(choice.copy(filter = it)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = choice.showCompleted, enabled = active,
            role = SemanticsRole.Checkbox, onValueChange = { pick(choice.copy(showCompleted = it)) }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(choice.showCompleted, onCheckedChange = null, enabled = active)
            Text("Show others' completed tasks")
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            when {
                saving -> Text("Saving…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved == true -> Text("Saved", color = MaterialTheme.colorScheme.primary)
                saved == false -> Text(failure ?: "Could not save. Try again.", color = MaterialTheme.colorScheme.error)
            }
        }
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
