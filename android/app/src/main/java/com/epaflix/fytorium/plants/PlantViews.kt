package com.epaflix.fytorium.plants

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.Role
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantsList(state: CareState, plants: List<Plant>, graveyard: Boolean, onPlant: (String) -> Unit,
    onAdd: () -> Unit, onGraveyard: () -> Unit, onRefresh: () -> Unit, thumbnails: Thumbnails? = null, enabled: Boolean = true,
    search: MutableState<String>? = null, focusSearch: Boolean = false, onFocused: () -> Unit = {}, onBack: () -> Unit = {},
    refreshing: Boolean = false, addBlocked: String? = null, onBlocked: (String) -> Unit = {}, request: PlantsRequestState = PlantsRequestState(), onRetry: () -> Unit = {}) {
    val query = search ?: rememberSaveable { mutableStateOf("") }
    val editor = state.member?.role != Role.sitter
    val canAdd = enabled && addBlocked == null
    fun add() { if (canAdd) onAdd() else addBlocked?.let(onBlocked) }
    val late = remember(state.today) {
        state.today?.sections.orEmpty().flatMap { it.tasks }.filter { it.completedEvent == null && it.lateDays > 0 }
            .groupBy { it.plantId }.mapValues { (_, tasks) -> tasks.maxOf { it.lateDays } }
    }
    val wanted = query.value.trim()
    val shown = remember(plants, wanted) {
        plants.filter { listOfNotNull(it.displayName, it.scientificName, it.siteName).any { name -> name.contains(wanted, ignoreCase = true) } }
    }
    val groups = remember(shown) {
        shown.groupBy { it.siteName }.entries.sortedWith(compareBy({ it.key == null }, { it.key?.lowercase() }))
            .map { (site, members) -> site to members.sortedBy { it.displayName.lowercase() } }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) { focus.requestFocus(); onFocused() } }
    val grid = rememberLazyGridState()
    // Results for a new query start at their first match, not at the old offset.
    LaunchedEffect(wanted) { grid.scrollToItem(0) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { PlantBarTitle(state, request, onRetry) { Heading(if (graveyard) "Graveyard" else "Plants") } }, expandedHeight = barHeight(), navigationIcon = {
            if (graveyard) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }, actions = {
            if (editor && !graveyard) {
                IconButton(onClick = ::add, modifier = Modifier.semantics { if (!canAdd) { disabled(); addBlocked?.let { stateDescription = it } } }) {
                    Icon(Icons.Default.Add, contentDescription = "Add plant", tint = if (canAdd) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Graveyard") }, onClick = { menu = false; onGraveyard() })
                    }
                }
            }
        })
        OutlinedTextField(query.value, { query.value = it }, label = { Text("Search plants") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.value.isNotEmpty()) IconButton(onClick = { query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus))
        if (graveyard) Text("History and photos stay here, without care tasks. Open a plant to restore it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (shown.isEmpty()) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(24.dp))
                Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                when {
                    plants.isNotEmpty() -> {
                        Text("No plant called \u201c$wanted\u201d", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        TextButton(onClick = { query.value = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear search") }
                    }
                    graveyard -> Text("No plants in the graveyard.", style = MaterialTheme.typography.titleMedium)
                    editor -> {
                        Text("No plants yet", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = ::add, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add your first plant") }
                    }
                    else -> Text("Nothing is shared with you yet. Ask the household owner to share plants.", textAlign = TextAlign.Center)
                }
            } else LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val headed = groups.size > 1 || groups.firstOrNull()?.first != null
                groups.forEach { (site, members) ->
                    if (headed) item(key = "site:$site", span = { GridItemSpan(maxLineSpan) }) {
                        Heading(site ?: "No site", MaterialTheme.typography.titleMedium, Modifier.padding(top = 8.dp))
                    }
                    items(members, key = { it.id }) { plant ->
                        val subtitle = if (graveyard) JSONObject(plant.attributesJson).nullable("graveyard_on")?.let { "Since " + dayMonthYear(it) } else plant.siteName
                        PlantTile(plant, late[plant.id] ?: 0, subtitle, thumbnails) { onPlant(plant.id) }
                    }
                }
            }
        }
    }
}

@Composable private fun PlantTile(plant: Plant, late: Int, subtitle: String?, thumbnails: Thumbnails?, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("plant:${plant.id}")
        .clickable(onClickLabel = "Open ${plant.displayName}", onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            PlantCover(plant, thumbnails, Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)), MaterialTheme.typography.displaySmall)
            if (late > 0) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                Text(lateLabel(late), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Text(plant.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable private fun photoBitmap(photoId: String?, thumbnails: Thumbnails?): Bitmap? = produceState<Bitmap?>(null, photoId, thumbnails) {
    value = withContext(Dispatchers.IO) { photoId?.let { thumbnails?.load(it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
}.value

@Composable internal fun PlantCover(plant: Plant, thumbnails: Thumbnails?, modifier: Modifier, initial: TextStyle) {
    val bitmap = photoBitmap(plant.coverPhotoId, thumbnails)
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Photo of ${plant.displayName}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().semantics { contentDescription = "No cover photo for ${plant.displayName}" }, contentAlignment = Alignment.Center) {
            Text(plant.displayName.take(1).uppercase(), style = initial, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

internal fun CareState.currentTask(occurrence: String?): CareTask? = rows.firstOrNull { it.occurrence == occurrence }

private val taskIcons = mapOf("water" to Icons.Outlined.WaterDrop, "fertilize" to Icons.Outlined.Science, "clean_leaves" to Icons.Outlined.CleaningServices,
    "remove_dead" to Icons.Outlined.LocalFlorist, "cut_back" to Icons.Outlined.ContentCut, "repot" to Icons.Outlined.Yard)

// One row per task in schedule order, so acting on one row never moves another.
internal fun careRows(state: CareState, detail: PlantDetail, opened: CareTask?, today: String?): List<CareTask> {
    val plant = detail.plant
    // A listed row this schedule and history show changed gives way to them, so an apply moment shows the plant's fresh state.
    val listed = state.rows.filter { it.plantId == plant.id && detail.current(it, state) }
    return (detail.tasks.keys + listed.map { it.key } + listOfNotNull(opened?.key)).distinct().mapNotNull { key ->
        val mine = listed.filter { it.key == key }
        val scheduled = detail.scheduled(key)
        // A location filter can hide the plant from the lists, so its history still says what this member did today.
        val own = detail.history.firstOrNull { it.key == key }?.takeIf { it.undoneAt == null && it.occurredOn == today && it.byMember == state.member?.id }
        opened?.takeIf { it.key == key && (it in mine || mine.isEmpty()) && detail.current(it, state) }
            ?: mine.firstOrNull { it.completedEvent != null }
            ?: own?.let { event -> (scheduled ?: mine.firstOrNull())?.copy(completedEvent = event, postponedUntil = event.postponedUntil) }
            ?: mine.firstOrNull() ?: scheduled
    }
}

// Lists older than this schedule and history can still show an occurrence or event another member acted on, which the server would refuse or act on differently.
// shortcut: sitters get no history, so their stale listed Undo still reaches the server's 409; upgrade when sitters get history.
private fun PlantDetail.current(task: CareTask, state: CareState) = refreshedAt <= (state.lastUpdated ?: 0) ||
    (task.completedEvent?.let { event -> history.none { it.id == event.id && (it.undoneAt != null || it.canUndo != event.canUndo) } }
        ?: tasks[task.key]?.let { it.status == task.status && it.dueOn == task.dueOn }) == true

private data class Moment(val key: String, val day: String, val at: Long, val event: CareEvent?, val text: String,
    val batchHint: Boolean = event?.run { batchId != null && undoneAt == null } == true)
private fun List<Moment>.keepingHistoryOf(shown: List<Moment>, state: CareState, turned: Map<String, Boolean>): List<Moment> {
    fun Moment.by(ids: Set<String>) = event?.run { id in ids || batchId in ids || linkedEventId in ids } == true
    // This device's graveyard move, restore or repot Undo turns only the Undos it changed off or on; later changes elsewhere wait.
    fun Moment.turnedHere() = event?.let { event -> turned[event.id]?.let { copy(event = event.copy(canUndo = it)) } }
    val live = associateBy { it.key }
    val keys = shown.mapTo(mutableSetOf()) { it.key }
    // An own Undo takes the batch hint's words now and its space at the next apply moment, so the entries below stay put.
    return (shown.map { held -> live[held.key]?.takeIf { it.by(state.undoneHere) }?.copy(batchHint = held.batchHint) ?: held.turnedHere() ?: held } +
        filter { it.key !in keys && it.by(state.createdHere) }).sortedByDescending { it.at }
}
private fun Plant.withFields(fields: JSONObject) = parsePlant(JSONObject(attributesJson).apply { fields.keys().forEach { put(it, fields.get(it)) } })
private fun timeline(detail: PlantDetail): List<Moment> {
    fun time(at: String?, day: String) = at?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val events = detail.history.map { Moment("history:${it.id}", it.occurredOn, time(it.occurredAt, it.occurredOn), it, historyText(it)) }
    val changes = JSONArray(detail.speciesChangesJson).mapObjects { it }.mapIndexed { index, change ->
        val day = change.getString("changed_on")
        Moment("change:${change.optString("id", index.toString())}", day, time(change.nullable("changed_at"), day), null,
            "Species changed from ${change.nullable("previous_display_name") ?: change.getString("previous_name")} to " +
                "${change.nullable("next_display_name") ?: change.getString("next_name")} \u00b7 ${change.getString("by_name")}")
    }
    return (events + changes).sortedByDescending { it.at }
}

private fun aboutLines(a: JSONObject): List<String> {
    fun value(key: String) = a.nullable(key)?.replace('_', ' ')
    val pot = listOfNotNull(value("pot_size_cm")?.let { "$it cm" }, value("pot_type")).joinToString(" ").takeIf { it.isNotEmpty() }
        ?.let { "$it pot" + if (a.optBoolean("drainage")) " with drainage holes" else "" }
    val size = listOfNotNull(value("plant_size"), value("growth_stage")).joinToString(", ").takeIf { it.isNotEmpty() }?.let { "$it plant" }
    val fertilizer = value("fertilizer_type")?.let { if (it == "sticks") "Fertilizer sticks" else "$it fertilizer" }
    return listOfNotNull(pot, value("soil_type")?.let { "$it soil" }, size, fertilizer,
        value("window_distance_cm")?.let { "$it cm from a window" },
        "Under a grow light".takeIf { a.optBoolean("grow_light") }, "Near a heater".takeIf { a.optBoolean("near_heater") },
        "Near air conditioning".takeIf { a.optBoolean("near_ac") },
        value("cultivar")?.let { "Cultivar $it" }, a.nullable("birthday")?.let { "Birthday " + dayMonthYear(it) },
        value("health")?.let { "Health: $it" }).map { it.replaceFirstChar(Char::uppercase) }
}

@Composable private fun SectionHeading(text: String) {
    Heading(text, MaterialTheme.typography.titleLarge, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantDetailScreen(state: CareState, detail: PlantDetail, onEdit: () -> Unit,
    onCommand: (String, JSONObject) -> Unit, onSpecies: () -> Unit, onUndo: (CareEvent) -> Unit, thumbnails: Thumbnails? = null,
    enabled: Boolean = true, options: PlantOptions? = null, onBack: () -> Unit = {}, onSpeciesCare: (() -> Unit)? = null,
    todayTask: CareTask? = null, onTask: (CareTask, String) -> Unit = { _, _ -> }, onBlocked: (String) -> Unit = {},
    refreshing: Boolean = false, onRefresh: () -> Unit = {}, writeBlocked: String? = null, request: PlantsRequestState = PlantsRequestState(), onRetry: () -> Unit = {},
    list: LazyListState = rememberLazyListState()) {
    val plant = detail.plant
    val owner = state.member?.role != Role.sitter
    val active = plant.status == "active"
    val today = state.today?.today ?: state.upcoming?.today
    val opened = todayTask?.takeIf { it.plantId == plant.id }?.let { state.currentTask(it.occurrence) ?: it }
    val live = TaskList(today.orEmpty(), "plant", TaskSettings(), emptyMap(), listOf(TaskSection(plant.id, plant.displayName, careRows(state, detail, opened, today))))
    var touched by remember(plant.id) { mutableStateOf(false) }
    // Only the user's own scroll counts, not the place a return restores or the page clamping that place.
    LaunchedEffect(list, plant.id) { snapshotFlow { list.isScrollInProgress }.collect { if (it) touched = true } }
    // Each entry, return and resume takes its first fresh read once, unless the user has scrolled or acted since (W2 A14).
    var entry by remember(plant.id) { mutableLongStateOf(detail.refreshedAt) }
    val refreshed by rememberUpdatedState(detail.refreshedAt)
    var firstRead by remember(plant.id) { mutableIntStateOf(0) }
    LaunchedEffect(plant.id, entry) { snapshotFlow { refreshed }.first { it > entry }; if (!touched) firstRead++ }
    val moment = rememberApplyMoment(refreshing, onStart = { entry = refreshed; touched = false }) + firstRead
    val held = rememberHeldList(live, state, plant.id, moment)!!.sections.single().tasks
    val since = remember(plant.id, moment, state.member) { state.ownPlantFields.size }
    val undoSince = remember(plant.id, moment, state.member) { state.ownUndoFlags.size }
    val own = state.ownPlantFields.drop(since).filter { it.first == plant.id }
    // A longer name or site, or a graveyard move from elsewhere, would otherwise push the held rows down or replace them.
    val header = rememberHeld(plant, plant.id, moment, state.member) { held, _ -> own.fold(held) { shown, (_, fields) -> shown.withFields(JSONObject(fields)) } }
    val buried = header.status != "active"
    // The rows' controls name the plant and site the headline shows, so this device's own rename reaches them at once.
    val rows = held.map { it.copy(plantName = header.displayName, siteName = header.siteName.orEmpty()) }
    val liveRows = live.sections.single().tasks
    val listed = if (active) liveRows.filter { detail.current(it, state) }.mapTo(mutableSetOf()) { it.occurrence } else emptySet()
    val applicable = rows.filter { it.completedEvent != null || it.status == "due" }
    val resting = rows.filter { it !in applicable }
    val attributes = remember(header.attributesJson) { JSONObject(header.attributesJson) }
    val fresh = remember(detail.history, detail.speciesChangesJson) { timeline(detail) }
    // Another member's history change waits for the same moment as the rows; this device's own writes and Undos show at once.
    val moments = rememberHeld(fresh, plant.id, moment, state.member) { shown, next -> next.keepingHistoryOf(shown, state, state.ownUndoFlags.drop(undoSince).toMap()) }
    val freshByKey = remember(fresh) { fresh.associateBy { it.key } }
    val heldEvents = moments.mapNotNull { it.event }
    fun List<CareTask>.dueNow() = filter { it.status == "due" && it.dueOn != null && today != null && it.dueOn <= today && it.completedEvent == null }
    val about = remember(attributes) { aboutLines(attributes) }
    var allHistory by rememberSaveable(plant.id) { mutableStateOf(false) }
    var details by rememberSaveable(plant.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember(plant.id) { mutableStateOf(false) }
    var burying by remember(plant.id) { mutableStateOf(false) }
    var confirmation by remember(plant.id) { mutableStateOf("") }
    val rowBlocked = if (state.writable) null else state.needs("Changes", "make changes", verb = "need")
    val acting: (() -> Unit) -> Unit = { touched = true; it() }
    // The name shows once: the bar takes it only after the headline under the cover has scrolled away.
    val titled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 && list.layoutInfo.visibleItemsInfo.none { it.key == "headline" } } }
    // Like the rows, a top bar control that is off says why on tap.
    fun gated(open: () -> Unit) { if (enabled) open() else writeBlocked?.let(onBlocked) }
    val gate = Modifier.semantics { if (!enabled) { disabled(); writeBlocked?.let { stateDescription = it } } }
    val gateTint = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { PlantBarTitle(state, request, onRetry) {
            if (titled) Text(header.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        } }, expandedHeight = barHeight(), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            if (owner) {
                IconButton(onClick = { gated(onEdit) }, enabled = enabled || writeBlocked != null, modifier = gate) { Icon(Icons.Default.Edit, contentDescription = "Edit plant", tint = gateTint) }
                Box {
                    IconButton(onClick = { gated { menu = true } }, enabled = enabled || writeBlocked != null, modifier = gate) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Plant settings", tint = gateTint)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Change species") }, onClick = { menu = false; onCommand("change-species", JSONObject()) }, enabled = enabled)
                        DropdownMenuItem(text = { Text("This plant's schedule") }, onClick = { menu = false; onCommand("care-form", JSONObject()) }, enabled = enabled && detail.careSettingsJson != null)
                        if (active) {
                            DropdownMenuItem(text = { Text("Repot") }, onClick = { menu = false; onCommand("repot-form", JSONObject()) }, enabled = enabled)
                            DropdownMenuItem(text = { Text("Move to graveyard") }, onClick = { menu = false; burying = true }, enabled = enabled)
                        } else DropdownMenuItem(text = { Text("Restore plant") }, onClick = { menu = false; onCommand("restore", JSONObject()) }, enabled = enabled)
                        DropdownMenuItem(text = { Text("Delete plant") }, onClick = { menu = false; deleting = true }, enabled = enabled)
                    }
                }
            }
        })
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item("cover") {
                    PlantCover(plant, thumbnails, Modifier.fillMaxWidth().aspectRatio(4f / 3f).then(
                        if (owner) Modifier.clickable(onClickLabel = "Open photo updates") { onCommand("photos", JSONObject()) } else Modifier), MaterialTheme.typography.displayLarge)
                }
                item("headline") { Heading(header.displayName, MaterialTheme.typography.headlineMedium, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                item("identity") {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val species = attributes.nullable("canonical_name") ?: header.scientificName
                        if (!species.equals(header.displayName, ignoreCase = true)) Text(species, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        header.siteName?.let { site ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(site, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (buried) Text(attributes.nullable("graveyard_on")?.let { "In the graveyard since " + dayMonthYear(it) } ?: "In the graveyard")
                    }
                }
                item("care-heading") { SectionHeading("Care") }
                if (buried) item("care-paused") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Care pauses in the graveyard. Restore ${header.displayName} to bring its tasks back.")
                        // A plant restored elsewhere keeps the held button in place, off, until the next pull.
                        val restoreBlocked = if (active) ChangedElsewhere else writeBlocked.takeIf { !enabled }
                        val canRestore = enabled && !active
                        if (owner) Button(onClick = { if (canRestore) acting { onCommand("restore", JSONObject()) } else restoreBlocked?.let(onBlocked) },
                            enabled = canRestore || restoreBlocked != null,
                            colors = ButtonDefaults.buttonColors().let { if (canRestore) it else ButtonDefaults.buttonColors(containerColor = it.disabledContainerColor, contentColor = it.disabledContentColor) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { if (!canRestore) { disabled(); restoreBlocked?.let { stateDescription = it } } }) {
                            Text("Restore ${header.displayName}")
                        }
                    }
                } else {
                    if (applicable.isEmpty()) item("care-none") { Text("No care tasks yet.", Modifier.padding(horizontal = 16.dp)) }
                    else item("care") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            applicable.forEach { task -> key(task.key) { CareRow(task, state, today, task.occurrence in listed, rowBlocked, { row, action -> acting { onTask(row, action) } },
                                { action, body -> acting { onCommand(action, body) } }, onBlocked) } }
                            val dueTasks = applicable.dueNow()
                            val due = dueTasks.isNotEmpty()
                            // A handled plant keeps the button's place, so nothing below moves under the next tap.
                            if (owner && (due || applicable.any { it.completedEvent != null })) {
                                // Done early for a later date is not done for today, so only a completion of something due by today reads All done.
                                val done = applicable.any { task -> task.completedEvent?.takeIf { it.kind == "completed" }?.let { it.dueOn ?: task.dueOn }?.let { today != null && it <= today } == true }
                                val handling = BusyKey.HandleAll(plant.id) in state.pending
                                // The server handles what its schedule says is due now, so a shown set that differs would name other tasks than it handles.
                                val serverDue = liveRows.mapNotNull { if (it.occurrence in listed) it else detail.scheduled(it.key)?.takeIf { active } }.dueNow()
                                val changed = dueTasks.mapTo(mutableSetOf()) { it.occurrence } != serverDue.mapTo(mutableSetOf()) { it.occurrence }
                                val canHandle = due && state.writable && enabled && !handling && !changed
                                val handleBlocked = if (!due || handling) null else if (changed) ChangedElsewhere else writeBlocked ?: rowBlocked
                                FilledTonalButton(onClick = { if (canHandle) acting { onCommand("handle-all", JSONObject()) } else handleBlocked?.let(onBlocked) }, enabled = canHandle || handleBlocked != null,
                                    colors = ButtonDefaults.filledTonalButtonColors().let { if (canHandle) it else ButtonDefaults.filledTonalButtonColors(containerColor = it.disabledContainerColor, contentColor = it.disabledContentColor) },
                                    shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 16.dp).heightIn(min = 48.dp)
                                        .semantics { contentDescription = if (due) "Handle all ${plant.displayName} tasks: " + dueTasks.joinToString(", ") { it.label }
                                            else if (done) "All ${plant.displayName} tasks done" else "No ${plant.displayName} tasks due today"
                                            if (!canHandle) { disabled(); handleBlocked?.let { stateDescription = it } } }) {
                                    ButtonProgress(handling)
                                    Text(if (due) "Handle all tasks" else if (done) "All done" else "Nothing due today")
                                }
                            }
                        }
                    }
                    if (resting.isNotEmpty()) item("care-resting") {
                        Text("Not needed now: " + resting.joinToString(", ") { it.label }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                    }
                }
                if (owner) {
                    item("history-heading") { SectionHeading("History") }
                    if (moments.isEmpty()) item("history-none") { Text("No care recorded yet.", Modifier.padding(horizontal = 16.dp)) }
                    val shown = if (allHistory) moments else moments.take(10)
                    shown.forEachIndexed { index, moment ->
                        if (index == 0 || moment.day != shown[index - 1].day) item("day:${moment.day}:$index") {
                            Text(historyDay(moment.day, today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                        }
                        item(moment.key) { HistoryEntry(moment, heldEvents, plant.displayName, state, enabled, freshByKey[moment.key] != moment, writeBlocked ?: rowBlocked, { acting { onUndo(it) } }, onBlocked) }
                    }
                    if (shown.size < moments.size) item("history-all") {
                        TextButton(onClick = { acting { allHistory = true } }, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) { Text("Show all history (${moments.size})") }
                    }
                    item("photos") {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { SectionHeading("Photos") }
                                TextButton(onClick = { onCommand("photos", JSONObject()) }, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) { Text("See all") }
                            }
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item("add") {
                                    Surface(onClick = { onCommand("photos", JSONObject()) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(96.dp)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
                                            Text("Add photo", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                itemsIndexed(detail.photoIds.take(10), key = { _, id -> id }) { index, id ->
                                    val bitmap = photoBitmap(id, thumbnails)
                                    Surface(onClick = { onCommand("photos", JSONObject()) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(96.dp).semantics { contentDescription = "Photo update ${index + 1} of ${detail.photoIds.size}" }) {
                                        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    }
                                }
                            }
                        }
                    }
                    item("about") {
                        Column {
                            SectionHeading("About this plant")
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                about.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                                val missing = listOf("pot_size_cm", "window_distance_cm", "cultivar", "birthday", "health").any { attributes.isNull(it) }
                                if (missing || about.isEmpty()) TextButton(onClick = onEdit, enabled = enabled, contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Add details") }
                            }
                        }
                    }
                }
                item("details") {
                    Column(Modifier.padding(top = 16.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (details) "Hide details" else "Show details") { acting { details = !details } }
                            .padding(horizontal = 16.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                            Text("Details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                        }
                        if (details) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Scientific name", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(header.scientificName)
                            attributes.nullable("gbif_key")?.let { Text("GBIF key $it \u00b7 Species data from GBIF, CC BY 4.0") }
                            Text("Care estimates are starting points, not a substitute for checking the plant. Month factors use the northern hemisphere and the anchor date.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            rows.forEach { task ->
                                Column(Modifier.testTag("basis:${task.key}")) {
                                    Heading(task.label, MaterialTheme.typography.titleSmall)
                                    if (task.basedOn.isNotEmpty()) Provenance(task.basedOn)
                                }
                            }
                            if (owner) {
                                onSpeciesCare?.let { open -> TextButton(onClick = open, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Care defaults for this species") } }
                                TextButton(onClick = onSpecies, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Species names") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (burying) AlertDialog(onDismissRequest = { burying = false }, title = { Text("Move ${plant.displayName} to the graveyard?") },
        text = { Text("It leaves Today and Upcoming. Its history and photos stay, and you can restore it later.") },
        confirmButton = { TextButton(onClick = { burying = false; onCommand("graveyard", JSONObject()) }, enabled = enabled) { Text("Move to graveyard") } },
        dismissButton = { TextButton(onClick = { burying = false }) { Text("Cancel") } })
    if (deleting) AlertDialog(modifier = Modifier.width(320.dp), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), onDismissRequest = { deleting = false },
        title = { Heading("Permanently delete ${plant.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This permanently deletes ${plant.displayName}, all care history and photos. It cannot be undone.")
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Type ${plant.displayName} to confirm") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        // The server checks the exact name, so a match that ignores case and outer spaces sends the name itself.
        confirmButton = { TextButton(onClick = { onCommand("delete", JSONObject().put("confirm_name", plant.displayName)); deleting = false },
            enabled = enabled && confirmation.trim().equals(plant.displayName.trim(), ignoreCase = true)) { Text("Permanently delete plant") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel deletion") } })
}

@Composable private fun CareRow(task: CareTask, state: CareState, today: String?, listed: Boolean, blocked: String?, onTask: (CareTask, String) -> Unit,
    onCommand: (String, JSONObject) -> Unit, onBlocked: (String) -> Unit) {
    val more = today?.let { day -> state.member?.let { task.actions(day, it) } }.orEmpty().filter { it == "skip" || it == "repot" }
    val busy = BusyKey.Row(task.occurrence) in state.pending
    val reason = if (listed) blocked else ChangedElsewhere
    val canSkip = !busy && state.writable && listed
    var open by remember { mutableStateOf(false) }
    Box {
        TaskRow(task, state, today, null, onAction = { onTask(task, it) }, title = task.label, details = listOfNotNull(today?.let { careStatus(task, it) }),
            onOpen = if (more.isEmpty()) null else ({ open = true }), openLabel = "More actions for ${task.label} on ${task.plantName}",
            icon = taskIcons[task.taskType] ?: Icons.Outlined.TaskAlt, listed = listed, blocked = reason, onBlocked = onBlocked)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if ("skip" in more) DropdownMenuItem(text = { Text("Skip") }, enabled = canSkip || (reason != null && !busy),
                colors = MenuDefaults.itemColors().let { if (canSkip) it else MenuDefaults.itemColors(textColor = it.disabledTextColor) },
                onClick = { open = false; if (canSkip) onTask(task, "skip") else reason?.let(onBlocked) },
                modifier = Modifier.semantics { contentDescription = "Skip ${task.label} for ${task.plantName}"; if (!canSkip) { disabled(); reason?.let { stateDescription = it } } })
            if ("repot" in more) DropdownMenuItem(text = { Text("Repot with new pot and soil") }, onClick = { open = false; onCommand("repot-form", JSONObject()) },
                modifier = Modifier.semantics { contentDescription = "Repot ${task.plantName} with new pot and soil" })
        }
    }
}

@Composable private fun HistoryEntry(moment: Moment, history: List<CareEvent>, plantName: String, state: CareState, enabled: Boolean, changed: Boolean,
    blocked: String?, onUndo: (CareEvent) -> Unit, onBlocked: (String) -> Unit) {
    val event = moment.event
    val undone = event?.undoneAt != null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(moment.text, style = MaterialTheme.typography.bodyLarge, color = if (undone) muted else LocalContentColor.current)
        if (moment.batchHint) Text("Part of a care batch. Undo reverses the whole batch.", if (undone) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
            style = MaterialTheme.typography.bodySmall, color = muted)
        // Undone takes the Undo button's height, so the entries below stay under the finger.
        if (undone) Text("Undone", Modifier.heightIn(min = 48.dp).wrapContentHeight(), style = MaterialTheme.typography.labelMedium, color = muted)
        else if (event?.canUndo == true) {
            val undoing = BusyKey.History(event.id) in state.pending
            val active = enabled && !undoing && !changed
            val reason = if (undoing) null else if (changed) ChangedElsewhere else blocked
            TextButton(onClick = { if (active) onUndo(event) else reason?.let(onBlocked) }, enabled = active || reason != null, contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors().let { if (active) it else ButtonDefaults.textButtonColors(contentColor = it.disabledContentColor) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("undo:${event.id}").semantics {
                    contentDescription = event.batchId?.let { batchReceipt(event, plantName, event.customTaskName.takeIf { event.customTaskId == event.batchTask }) }
                        ?.let { "Undo" + it.undone.removePrefix("Undid") + if (event.batchAction == "complete-all") "" else ", including $plantName" }
                        ?: "${undoLabel(event, history)} for $plantName"
                    if (!active) { disabled(); reason?.let { stateDescription = it } }
                }) {
                ButtonProgress(undoing)
                Text(undoLabel(event, history))
            }
        }
    }
}

@Composable internal fun RestoreForm(plant: Plant, sites: List<PlantSite>, writable: Boolean, onSave: (JSONObject) -> Unit, onCreateSite: () -> Unit = {}, error: String? = null,
    chosen: () -> String? = { null }, onChoose: (String) -> Unit = {}, onDirty: (() -> Boolean) -> Unit = {}) {
    val attributes = remember(plant.attributesJson) { JSONObject(plant.attributesJson) }
    val stored = attributes.nullable("site_id").orEmpty()
    val site = chosen() ?: stored
    val target = sites.firstOrNull { it.id == site }
    SideEffect { onDirty { (chosen() ?: stored) != stored } }
    PlantColumn {
        Text("${plant.displayName} returns to Today and Upcoming with its care tasks. Its history and photos stay.")
        Choice("Site", site, mapOf("" to "Choose a site") + sites.associate { it.id to it.name }, writable, onChange = onChoose)
        TextButton(onClick = onCreateSite, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Create a site") }
        Button(onClick = { onSave(JSONObject().put("site_id", site)) }, enabled = writable && target != null, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(target?.let { "Restore ${plant.displayName} to ${it.name}" } ?: "Restore ${plant.displayName}")
        }
        PlantError(error)
    }
}
@Composable internal fun PlantRepotForm(plant: Plant, options: PlantOptions, writable: Boolean, errors: Map<String, String> = emptyMap(),
    onSave: (JSONObject) -> Unit, onDirty: (() -> Boolean) -> Unit = {}) {
    val start = remember(plant.id) { listOf(plant.potType.orEmpty(), plant.soilType.orEmpty(), plant.potSizeCm?.toString().orEmpty()) }
    var pot by remember(plant.id) { mutableStateOf(start[0]) }
    var soil by remember(plant.id) { mutableStateOf(start[1]) }
    var size by remember(plant.id) { mutableStateOf(start[2]) }
    SideEffect { onDirty { listOf(pot, soil, size) != start } }
    PlantColumn {
        Heading("New pot and soil for ${plant.displayName}", style = MaterialTheme.typography.headlineSmall)
        Choice(options.labels.getValue("pot_type"), pot, options.choices.getValue("pot_type").associateWith(::choiceLabel), writable) { pot = it }
        Choice(options.labels.getValue("soil_type"), soil, options.choices.getValue("soil_type").associateWith(::choiceLabel), writable) { soil = it }
        OutlinedTextField(size, { size = it }, label = { Text(options.labels.getValue("pot_size_cm")) }, keyboardOptions = numberKeys,
            isError = errors.containsKey("pot_size_cm"), supportingText = { if (errors["pot_size_cm"] != null) PlantError(errors["pot_size_cm"]) else Text("Positive whole number, or blank for unknown") }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(JSONObject().put("pot_type", pot).put("soil_type", soil).put("pot_size_cm", size.toIntOrNull() ?: JSONObject.NULL)) },
            enabled = writable && (size.isBlank() || size.toIntOrNull()?.let { it > 0 } == true), modifier = Modifier.heightIn(min = 48.dp)) { Text("Repot ${plant.displayName}") }
        // The choices come from /api/choices, so a refusal of one is rare and waits under the button instead of keeping space under each choice.
        for (field in listOf("pot_type", "soil_type")) PlantError(errors[field]?.let { "${options.labels.getValue(field)}: $it" })
        PlantError(errors["_form"])
    }
}
