package com.epaflix.fytorium.plants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import org.json.JSONArray
import org.json.JSONObject

@Composable fun PlantsList(state: CareState, plants: List<Plant>, graveyard: Boolean, onPlant: (String) -> Unit,
    onAdd: () -> Unit, onGraveyard: () -> Unit, onRefresh: () -> Unit, thumbnails: Thumbnails? = null, enabled: Boolean = true) {
    var query by remember { mutableStateOf("") }
    val dates = remember(plants) { plants.associate { it.id to JSONObject(it.attributesJson).nullable("graveyard_on") } }
    val filtered = remember(plants, query) { plants.filter { (it.displayName + " " + it.scientificName).contains(query.trim(), ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Heading(if (graveyard) "Graveyard" else "Plants", style = MaterialTheme.typography.headlineSmall)
            if (graveyard) Text("History and photos stay here, without care tasks. Open a plant to restore it.")
        }
        item { OutlinedTextField(query, { query = it }, label = { Text("Search plants") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (state.member?.role != Role.sitter && !graveyard) item {
            Button(onClick = onAdd, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add plant") }
            TextButton(onClick = onGraveyard, modifier = Modifier.heightIn(min = 48.dp)) { Text("Graveyard") }
        }
        item { TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh plants") } }
        if (filtered.isEmpty()) item { Text("No plants found.") }
        items(filtered, key = { it.id }) { plant ->
            ElevatedCard(onClick = { onPlant(plant.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plant:${plant.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Thumbnail(plant, thumbnails)
                    Heading(plant.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(plant.scientificName)
                    plant.siteName?.let { Text(it) }
                    if (plant.status == "graveyard") Text(dates[plant.id]?.let { "In the graveyard since $it" } ?: "In the graveyard")
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantDetailScreen(state: CareState, detail: PlantDetail, onEdit: () -> Unit,
    onCommand: (String, JSONObject) -> Unit, onSpecies: () -> Unit, onUndo: (CareEvent) -> Unit, thumbnails: Thumbnails? = null,
    enabled: Boolean = true, options: PlantOptions? = null, onBack: () -> Unit = {}) {
    val plant = detail.plant
    val attributes = remember(plant.attributesJson) { JSONObject(plant.attributesJson) }
    val changes = remember(detail.speciesChangesJson) { JSONArray(detail.speciesChangesJson).mapObjects { change ->
        "${change.getString("changed_on")} · Species changed from ${change.getString("previous_name")} to ${change.getString("next_name")} by ${change.getString("by_name")}" }
    }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember(plant.id) { mutableStateOf(false) }
    var confirmation by remember(plant.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Heading(plant.displayName) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            if (state.member?.role != Role.sitter) {
                TextButton(onClick = onEdit, enabled = enabled) { Text("Edit plant") }
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, contentDescription = "Plant settings") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Care settings") }, onClick = { menu = false; onCommand("care-form", JSONObject()) }, enabled = enabled && detail.careSettingsJson != null)
                        DropdownMenuItem(text = { Text("Species names") }, onClick = { menu = false; onSpecies() }, enabled = enabled)
                        DropdownMenuItem(text = { Text("Change species") }, onClick = { menu = false; onCommand("change-species", JSONObject()) }, enabled = enabled)
                        if (plant.status == "active") DropdownMenuItem(text = { Text("Move to graveyard") }, onClick = { menu = false; onCommand("graveyard", JSONObject()) }, enabled = enabled)
                        else DropdownMenuItem(text = { Text("Restore plant") }, onClick = { menu = false; onCommand("restore", JSONObject()) }, enabled = enabled)
                        DropdownMenuItem(text = { Text("Delete plant") }, onClick = { menu = false; deleting = true }, enabled = enabled)
                    }
                }
            }
        })
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(plant.scientificName) }
        if (state.member?.role == Role.sitter) item {
            Text(attributes.nullable("cultivar").orEmpty())
            Text("Care tasks are on Today and Upcoming.")
        } else {
            item {
                Thumbnail(plant, thumbnails)
                if (plant.status == "graveyard") {
                    Text(attributes.nullable("graveyard_on")?.let { "In the graveyard since $it" } ?: "In the graveyard")
                    Text("History and photos stay here, without care tasks. Open a plant to restore it.")
                }
            }
            item {
                Heading("Photo updates")
                OutlinedButton(onClick = { onCommand("photos", JSONObject()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View all updates") }
            }
            item {
                Heading("Species and site")
                Text("${attributes.nullable("canonical_name") ?: plant.scientificName} · ${attributes.nullable("rank")?.lowercase() ?: "Unknown rank"} · GBIF key: ${attributes.nullable("gbif_key") ?: "Not set"}")
                Text("Species data from GBIF, CC BY 4.0")
                Text("Site: ${plant.siteName ?: "Site deleted"}")
                listOf("nickname", "cultivar", "birthday", "health").forEach { key -> Text("${key.replaceFirstChar { it.uppercase() }}: ${attributes.nullable(key)?.replace('_', ' ') ?: "Not set"}") }
            }
            if (plant.status == "active") item {
                Button(onClick = { onCommand("handle-all", JSONObject()) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Handle all tasks") }
                Text("Completes only tasks due or late today. Undo reverses the whole batch.")
                OutlinedButton(onClick = { onCommand("repot-form", JSONObject()) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Repot with new pot and soil") }
            }
            item { Heading("Pot and plant attributes") }
            items(options?.labels?.keys?.toList().orEmpty(), key = { "attribute:$it" }) { key ->
                val value = if (key in options!!.booleans) { if (attributes.optBoolean(key)) "Yes" else "No" }
                    else attributes.nullable(key)?.replace('_', ' ') ?: "Not set"
                Text("${options.labels.getValue(key)}: $value")
            }
            if (plant.status == "active") {
                items(detail.tasks.toList(), key = { "task:${it.first}" }) { (key, task) ->
                    Heading(taskLabel(task.taskType ?: key, task.customTaskName))
                    Text(if (task.status == "due") "Next: ${task.dueOn} · Every ${task.intervalDays} days · ${task.lateDays}d late" else "Not applicable")
                }
                item {
                    Heading("Based on")
                    Text("Starting estimates, not a substitute for checking the plant. Month factors use the northern hemisphere and the anchor date.")
                }
                items(detail.tasks.toList(), key = { "basis:${it.first}" }) { (key, task) ->
                    Heading(taskLabel(task.taskType ?: key, task.customTaskName), MaterialTheme.typography.titleMedium)
                    if (task.basedOn.isNotEmpty()) Provenance(task.basedOn)
                }
            }
            item { Heading("Care history") }
            items(changes) { Text(it) }
            if (detail.history.isEmpty()) item { Text("No care events yet.") }
            items(detail.history, key = { "history:${it.id}" }) { event ->
                Text("${event.occurredOn} · ${taskLabel(event.taskType, event.customTaskName)}")
                Text("${event.label} by ${event.byName}" + if (event.undoneAt != null) " · Undone" else "")
                if (event.batchId != null) Text("Part of a care batch. Undo reverses the whole batch.")
                if (event.canUndo) OutlinedButton(onClick = { onUndo(event) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("undo:${event.id}")) { Text("Undo") }
            }
        }
    }
    }
    if (deleting) AlertDialog(modifier = Modifier.width(320.dp), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), onDismissRequest = { deleting = false }, title = { Heading("Permanently delete plant?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This permanently deletes ${plant.displayName}, all care history and photos. It cannot be undone.")
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Type the plant name to confirm") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onCommand("delete", JSONObject().put("confirm_name", confirmation)); deleting = false }, enabled = enabled && confirmation == plant.displayName) { Text("Permanently delete plant") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel deletion") } })
}
@Composable internal fun RestoreForm(plant: Plant, sites: List<PlantSite>, writable: Boolean, onSave: (JSONObject) -> Unit) {
    val attributes = remember(plant.attributesJson) { JSONObject(plant.attributesJson) }
    var site by remember(plant.id) { mutableStateOf(attributes.nullable("site_id").orEmpty()) }
    PlantColumn {
        Heading("Restore plant", style = MaterialTheme.typography.headlineSmall)
        Text("Restore this plant to active lists and care tasks. Its history and photos stay.")
        Choice("Restore to site", site, mapOf("" to "Choose a site") + sites.associate { it.id to it.name }, writable) { site = it }
        Button(onClick = { onSave(JSONObject().put("site_id", site)) }, enabled = writable && sites.any { it.id == site }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore to active plants") }
    }
}
@Composable internal fun PlantRepotForm(plant: Plant, options: PlantOptions, writable: Boolean, errors: Map<String, String> = emptyMap(), onSave: (JSONObject) -> Unit) {
    var pot by remember(plant.id) { mutableStateOf(plant.potType.orEmpty()) }
    var soil by remember(plant.id) { mutableStateOf(plant.soilType.orEmpty()) }
    var size by remember(plant.id) { mutableStateOf(plant.potSizeCm?.toString().orEmpty()) }
    PlantColumn {
        Heading("Repot with new pot and soil", style = MaterialTheme.typography.headlineSmall)
        Choice(options.labels.getValue("pot_type"), pot, options.choices.getValue("pot_type").associateWith { it.replace('_', ' ') }, writable) { pot = it }
        PlantError(errors["pot_type"])
        Choice(options.labels.getValue("soil_type"), soil, options.choices.getValue("soil_type").associateWith { it.replace('_', ' ') }, writable) { soil = it }
        PlantError(errors["soil_type"])
        OutlinedTextField(size, { size = it }, label = { Text(options.labels.getValue("pot_size_cm")) },
            isError = errors.containsKey("pot_size_cm"), supportingText = { if (errors["pot_size_cm"] != null) PlantError(errors["pot_size_cm"]) else Text("Positive whole number, or blank for unknown") }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(JSONObject().put("pot_type", pot).put("soil_type", soil).put("pot_size_cm", size.toIntOrNull() ?: JSONObject.NULL)) },
            enabled = writable && (size.isBlank() || size.toIntOrNull()?.let { it > 0 } == true), modifier = Modifier.heightIn(min = 48.dp)) { Text("Save repot") }
    }
}
