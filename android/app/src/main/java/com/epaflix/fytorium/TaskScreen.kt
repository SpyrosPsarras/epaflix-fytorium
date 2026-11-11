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
import androidx.compose.ui.layout.layout
import androidx.lifecycle.compose.LifecycleStartEffect
import com.epaflix.fytorium.feed.FeedState
import com.epaflix.fytorium.feed.keepingStripOf
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
@Composable fun TaskScreen(care: CareState, view: String, onView: (String) -> Unit = {}, onRefresh: () -> Unit = {},
                           onAction: (CareTask, String) -> Unit = { _, _ -> },
                           onHandleType: (String) -> Unit = {}, onHandlePlant: (String) -> Unit = {}, onSettings: suspend (TaskSettings) -> Boolean = { true }, refreshing: Boolean = false,
                           onUndoReceipt: (Receipt) -> Unit = {}, onReceiptDone: (Receipt) -> Unit = {},
                           thumbnails: Thumbnails? = null,
                           onSearch: () -> Unit = {}, onAddPlant: () -> Unit = {}, onImport: () -> Unit = {}, onRetry: () -> Unit = {},
                           receipts: SnackbarHostState? = null, onReceiptGone: () -> Unit = {},
                           deviceOnline: Boolean = true, onOpenTask: (CareTask) -> Unit = {}, scrollState: ScrollState = rememberScrollState(),
                           thumbnailLoader: suspend (String) -> Bitmap? = { id -> withContext(Dispatchers.IO) {
                               thumbnails?.load(id)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                           } }, feed: FeedState? = null, feedContent: @Composable (FeedState) -> Unit = {}) {
    val pullState = rememberPullToRefreshState()
    // Layout changes wait for a moment the user picked: the end of a pull, the app coming back to the foreground, a view switch or a return to Today.
    var moment by remember { mutableIntStateOf(0) }
    // Zero-duration device animations can leave the refresh indicator at its shown offset.
    LaunchedEffect(refreshing) { if (!refreshing) { pullState.snapTo(0f); moment++ } }
    LifecycleStartEffect(Unit) { moment++; onStopOrDispose {} }
    val live = if (view == "upcoming") care.upcoming else care.today
    val since = remember(view, moment, care.member) { care.transitions.size }
    val list = rememberHeld(live, view, moment, care.member) { shown, next -> next?.keepingLayoutOf(shown, care.transitions.drop(since).toMap()) }
    // Losing the last plant or share removes the toggle and every row, so it waits for the same moment.
    val noPlants = rememberHeld(care.plants.isEmpty().takeIf { care.today != null }, view, moment, care.member) { shown, next -> shown ?: next } == true
    // A row the server no longer lists stays in place until then, without controls that would act on it again.
    val listed = live?.sections.orEmpty().flatMapTo(mutableSetOf()) { section -> section.tasks.map { it.occurrence } }
    val state = if (view == "upcoming") care.copy(upcoming = list) else care.copy(today = list)
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) { snapshotFlow { scrollState.value != 0 }.first { it }; touched = true }
    val shownFeed = feed?.let { rememberHeld(it, view, moment) { shown, next -> if (!shown.loaded && !touched) next else next.keepingStripOf(shown) } }
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
    val scope = rememberCoroutineScope()
    val editor = state.member?.role in listOf(Role.owner, Role.family)
    val presentation = state.todayPresentation(noPlants)
    val sections = if (view == "today") presentation.sections else list?.sections.orEmpty().filter { it.tasks.isNotEmpty() }
    fun saveView(settings: TaskSettings) { scope.launch { onSettings(settings) } }
    var settingsOpen by remember { mutableStateOf(false) }
    fun needs(action: String, update: String, retry: String = "", verb: String = "needs") = when {
        !deviceOnline || state.offline -> "$action $verb a connection."
        state.serverUnavailable -> "$action $verb Fytorium to respond.$retry"
        !state.writable -> "Update Fytorium to $update."
        else -> null
    }
    val viewBlocked = needs("Task view", "change the task view")
    val blocked = needs("Adding a plant", "add plants", " Pull down to try again.")
    val rowBlocked = if (state.writable) null else needs("Changes", "make changes", verb = "need")
    val subtitleLine = subtitleLineHeight()
    val canAdd = editor && blocked == null
    // The sheet has no pull, so while it waits for Fytorium it asks again by itself.
    if (settingsOpen && deviceOnline && (state.offline || state.serverUnavailable)) LaunchedEffect(Unit) { while (true) { onRetry(); delay(5_000) } }
    if (settingsOpen && list != null) ModalBottomSheet(onDismissRequest = { settingsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ViewSettings(list.settings, viewBlocked, state.message, onSettings)
    }
    val own = remember { SnackbarHostState() }
    val snackbar = receipts ?: own
    fun explain(message: String) { scope.launch { snackbar.showSnackbar(message); onReceiptGone() } }
    fun adding(action: () -> Unit): () -> Unit = { if (canAdd) action() else blocked?.let(::explain) }
    val addingLook = Modifier.semantics { if (!canAdd) { disabled(); blocked?.let { stateDescription = it } } }
    val blockedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ReceiptSnackbar(state.receipt, snackbar, onUndoReceipt, onReceiptDone, onReceiptGone)
    Scaffold(snackbarHost = { if (receipts == null) SnackbarHost(own) }, topBar = {
        CenterAlignedTopAppBar(title = { Text(if (view == "upcoming") "Upcoming" else "Today") }, navigationIcon = {
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search plants") }
        }, actions = {
            if (editor && list != null && !noPlants) IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Tune, contentDescription = "Task view") }
            if (editor) IconButton(onClick = adding(onAddPlant), modifier = addingLook) {
                Icon(Icons.Default.Add, contentDescription = "Add plant", tint = if (canAdd) LocalContentColor.current else blockedColor)
            }
        })
    }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, state = pullState, onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), indicator = {
                if (refreshing || pullState.distanceFraction > 0f) PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter), state = pullState, isRefreshing = refreshing,
                    containerColor = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }) {
            Column(Modifier.fillMaxSize().testTag("task-list").steadyScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (view == "today" && shownFeed != null) feedContent(shownFeed)
                if (!noPlants) ViewToggle(view, onView)
                if (!noPlants) list?.settings?.let { settings -> ActiveView(settings, editor, state.writable) { saveView(TaskSettings()) } }
                TodayNotice(state)
                if (sections.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    val filter = list?.takeIf { !noPlants }?.hidingFilter()
                    val title = if (view == "upcoming" && !noPlants && list != null) "No upcoming " + filter?.let { "$it " }.orEmpty() + "tasks" else presentation.emptyTitle
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    presentation.emptyHint?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (view == "today" && !noPlants) presentation.nextTask?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    if (filter != null && editor) TextButton(onClick = { list?.let { saveView(it.settings.copy(filter = "all")) } }, enabled = state.writable,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all") }
                    if (noPlants && editor) {
                        Button(onClick = adding(onAddPlant), modifier = addingLook.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors().let { colors ->
                            if (canAdd) colors else ButtonDefaults.buttonColors(containerColor = colors.disabledContainerColor, contentColor = colors.disabledContentColor) }) { Text("Add plant") }
                        if (state.member?.role == Role.owner) TextButton(onClick = adding(onImport), modifier = addingLook.heightIn(min = 48.dp),
                            colors = if (canAdd) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = blockedColor)) { Text("Import from Planta") }
                    }
                }
                sections.forEachIndexed { index, section ->
                    val day = section.day
                    if (day != null && day != sections.getOrNull(index - 1)?.day) Text(dayLabel(day, list!!.today), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Card(Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        // Lists cached before Upcoming cards carried their day are one card per day, keyed by the date.
                        val dated = view == "upcoming" && day == null && section.key.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
                        Text(if (dated) dayLabel(section.key, list!!.today) else section.label,
                            style = if (day != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() })
                        val sort = list!!.settings.sort
                        // A card's heading names its plant, site or day, so its rows do not repeat it.
                        val by = if (dated) "day" else sort
                        section.tasks.forEach { task ->
                            key(task.plantId, task.key) { TaskRow(task, care, list.today, photos[task.coverPhotoId],
                                onAction = { action -> touched = true; onAction(task, action) }, onOpen = { onOpenTask(task) },
                                title = if (by == "plant") task.label else task.plantName,
                                details = listOfNotNull(task.label.takeIf { by == "day" || by == "site" }, task.siteName.takeIf { by != "site" }),
                                showPostponed = view == "upcoming" || sort != "task", listed = task.occurrence in listed, subtitleLine = subtitleLine,
                                blocked = if (task.occurrence in listed) rowBlocked else "This task changed elsewhere. Pull to refresh.", onBlocked = ::explain,
                                photoLoading = task.coverPhotoId != null && !photos.containsKey(task.coverPhotoId)) }
                        }
                        val due = section.tasks.any { task -> task.status == "due" && task.dueOn != null && task.dueOn <= list.today && task.completedEvent == null }
                        // A handled card keeps the button's place, so the rows below stay under the next tap.
                        if (view == "today" && editor && sort != "site" && (due || section.tasks.any { it.completedEvent != null })) {
                            val done = section.tasks.any { it.completedEvent?.kind == "completed" }
                            val handling = (if (sort == "plant") BusyKey.HandleAll(section.key) else BusyKey.Card(section.key)) in state.pending
                            FilledTonalButton(onClick = { touched = true; if (sort == "plant") onHandlePlant(section.key) else onHandleType(section.key) },
                                enabled = due && state.writable && !handling,
                                shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.CenterHorizontally)
                                    .padding(top = 12.dp, bottom = 16.dp).heightIn(min = 48.dp)
                                    .semantics { contentDescription = if (due) "Handle all ${section.label} tasks"
                                        else if (done) "All ${section.label} tasks done" else "No ${section.label} tasks due today" }) {
                                ButtonProgress(handling)
                                Text(if (due) "Handle all tasks" else if (done) "All done" else "Nothing due today")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun <T> rememberHeld(live: T, vararg keys: Any?, keep: (shown: T, live: T) -> T): T {
    val shown = remember(*keys) { mutableStateOf(live) }
    val next = keep(shown.value, live)
    SideEffect { shown.value = next }
    return next
}

// Clamping the scroll to a shorter list or a taller viewport would move every row, so the list ends in blank space instead.
private fun Modifier.steadyScroll(state: ScrollState) = verticalScroll(state).layout { measurable, constraints ->
    val content = measurable.measure(constraints)
    layout(content.width, maxOf(content.height, state.value + constraints.minHeight)) { content.place(0, 0) }
}

@Composable internal fun ReceiptSnackbar(receipt: Receipt?, host: SnackbarHostState, onUndo: (Receipt) -> Unit, onDone: (Receipt) -> Unit, onGone: () -> Unit = {}) {
    val undo by rememberUpdatedState(onUndo)
    val done by rememberUpdatedState(onDone)
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(receipt) {
        if (receipt == null) return@LaunchedEffect
        try {
            val result = host.showSnackbar(receipt.text, actionLabel = if (receipt.undo != null) "Undo" else null, withDismissAction = !receipt.short,
                duration = if (receipt.short) SnackbarDuration.Short else SnackbarDuration.Long)
            gone()
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

@Composable private fun ViewSettings(settings: TaskSettings, blocked: String?, failure: String?, save: suspend (TaskSettings) -> Boolean) {
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
    val active = blocked == null && !saving
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Task view", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Segments("Sort tasks", choice.sort, listOf("task" to "Task type", "plant" to "Plant", "site" to "Site"), active) { pick(choice.copy(sort = it)) }
        Segments("Filter", choice.filter, listOf("all" to "All", "indoor" to "Indoor", "outdoor" to "Outdoor"), active) { pick(choice.copy(filter = it)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = choice.showCompleted, enabled = active,
            role = SemanticsRole.Checkbox, onValueChange = { pick(choice.copy(showCompleted = it)) }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(choice.showCompleted, onCheckedChange = null, enabled = active)
            Spacer(Modifier.width(16.dp))
            Text("Show others' completed tasks", color = if (active) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            when {
                saving -> Text("Saving…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                blocked != null -> Text(blocked, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved == true -> Text("Saved", color = MaterialTheme.colorScheme.primary)
                saved == false -> Text(failure ?: "Could not save. Try again.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Segments(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, change: (String) -> Unit) {
    // Material dims only the selected segment when disabled, which reads as if that choice were the one turned off.
    val colors = SegmentedButtonDefaults.colors().let { SegmentedButtonDefaults.colors(disabledInactiveContentColor = it.disabledActiveContentColor,
        disabledInactiveBorderColor = it.disabledActiveBorderColor) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, (key, text) ->
                SegmentedButton(selected = key == value, onClick = { change(key) }, enabled = enabled, icon = {}, colors = colors,
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size), modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
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
