package com.epaflix.fytorium.plants

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.ReferencePhoto
import com.epaflix.fytorium.Plant
import com.epaflix.fytorium.nullable
import com.epaflix.fytorium.taskLabel
import org.json.JSONObject

@Composable private fun savedFields(key: String?, initial: () -> Map<String, String>): androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> =
    rememberSaveable(key, saver = mapSaver(
        save = { fields: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> -> fields.toMap() },
        restore = { values -> values.map { it.key to it.value.toString() }.toMutableStateMap() })) { initial().toList().toMutableStateMap() }

@Composable internal fun Heading(text: String, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Text(text, style = style, modifier = Modifier.semantics { heading() })
}
@Composable internal fun PlantError(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Choice(label: String, value: String, options: Map<String, String>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(value = options[value] ?: value.replace('_', ' '), onValueChange = {}, readOnly = true,
            label = { Text(label) }, enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Birthday(value: String, enabled: Boolean, error: String?, onChange: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Birthday: " + value.ifBlank { "Not set" }) }
    PlantError(error)
    if (choosing) {
        val initial = remember(value) { runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { choosing = false },
            confirmButton = { TextButton(onClick = { onChange(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()); choosing = false }) { Text("Set birthday") } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
    if (value.isNotBlank()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text("Clear birthday") }
}
@Composable internal fun Toggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}
@Composable internal fun PlantColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable fun PlantForm(plant: Plant?, options: PlantOptions, sites: List<PlantSite>, writable: Boolean, errors: Map<String, String> = emptyMap(), onSave: (JSONObject) -> Unit) {
    val original = remember(plant?.id) { JSONObject(plant?.attributesJson ?: "{}") }
    val texts = savedFields(plant?.id) { mutableMapOf<String, String>().apply {
        (listOf("nickname", "cultivar", "birthday") + options.numbers).forEach { key -> put(key, if (original.isNull(key)) "" else original.optString(key)) }
    } }
    val choices = savedFields(plant?.id) { mutableMapOf<String, String>().apply {
        options.choices.forEach { (key, values) -> put(key, original.nullable(key) ?: values.first()) }
        put("health", if (original.isNull("health")) "" else original.optString("health"))
        put("site_id", if (original.isNull("site_id")) "" else original.optString("site_id"))
    } }
    val flags = savedFields(plant?.id) { options.booleans.associateWith { original.optBoolean(it).toString() } }
    val validNumbers = options.numbers.all { texts[it].isNullOrBlank() || (texts[it]?.toIntOrNull()?.let { n -> n > 0 } == true) }
    PlantColumn {
        Heading(if (plant == null) "Add plant details" else "Edit plant", style = MaterialTheme.typography.headlineSmall)
        listOf("nickname" to "Nickname", "cultivar" to "Cultivar").forEach { (key, label) ->
            OutlinedTextField(texts[key].orEmpty(), { texts[key] = it }, label = { Text(label) }, enabled = writable, singleLine = true,
                isError = errors.containsKey(key), supportingText = errors[key]?.let { { PlantError(it) } }, modifier = Modifier.fillMaxWidth())
        }
        Birthday(texts["birthday"].orEmpty(), writable, errors["birthday"]) { texts["birthday"] = it }
        Choice("Health", choices["health"].orEmpty(), mapOf("" to "Not set") + options.health.associateWith { it.replace('_', ' ') }, writable) { choices["health"] = it }
        PlantError(errors["health"])
        Choice("Site", choices["site_id"].orEmpty(), mapOf("" to if (plant?.status == "graveyard") "No site" else "Choose a site") + sites.associate { it.id to it.name }, writable) { choices["site_id"] = it }
        PlantError(errors["site_id"])
        PlantError(errors["gbif_key"])
        if (sites.isEmpty()) Text("Create a site under More before adding or restoring a plant.")
        Heading("Pot and plant attributes")
        options.choices.forEach { (key, values) ->
            Choice(options.labels.getValue(key), choices[key].orEmpty(), values.associateWith { it.replace('_', ' ') }, writable) { choices[key] = it }
            PlantError(errors[key])
        }
        options.numbers.forEach { key ->
            OutlinedTextField(texts[key].orEmpty(), { texts[key] = it }, label = { Text(options.labels.getValue(key)) }, enabled = writable,
                isError = errors.containsKey(key) || (!texts[key].isNullOrBlank() && texts[key]?.toIntOrNull()?.let { it > 0 } != true),
                supportingText = { if (errors[key] != null) PlantError(errors[key]) else Text("Positive whole number, or blank for unknown") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        options.booleans.forEach { key ->
            Toggle(options.labels.getValue(key), flags[key].toBoolean(), writable) { flags[key] = it.toString() }
            PlantError(errors[key])
        }
        Button(onClick = {
            val body = JSONObject()
            choices.forEach { (key, value) -> body.put(key, value.ifBlank { null } ?: JSONObject.NULL) }
            texts.forEach { (key, value) -> body.put(key, if (key in options.numbers) value.toIntOrNull() ?: JSONObject.NULL else value.ifBlank { null } ?: JSONObject.NULL) }
            flags.forEach { (key, value) -> body.put(key, value.toBoolean()) }
            onSave(body)
        }, enabled = writable && validNumbers && (plant?.status == "graveyard" || !choices["site_id"].isNullOrBlank()), modifier = Modifier.heightIn(min = 48.dp)) { Text("Save plant") }
    }
}
@Composable fun SpeciesPicker(writable: Boolean, result: SpeciesSearch?, onSearch: (String) -> Unit, onSelect: (SpeciesCandidate, String, Boolean) -> Unit, imageApi: ApiClient? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember(result) { mutableStateOf<SpeciesCandidate?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("Species search", style = MaterialTheme.typography.headlineSmall) }
        item { OutlinedTextField(query, { query = it }, label = { Text("Species name") }, supportingText = { Text("Common or scientific name, English, Norwegian or Greek") }, singleLine = true, enabled = writable, modifier = Modifier.fillMaxWidth()) }
        item { Button(onClick = { selected = null; onSearch(query.trim()) }, enabled = writable && query.trim().length in 1..200, modifier = Modifier.heightIn(min = 48.dp)) { Text("Search species") } }
        if (result != null) {
            if (selected == null) {
                if (result.candidates.isEmpty()) item { Text("No species found.") }
                items(result.candidates, key = { it.key }) { candidate ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReferencePhoto(imageApi, candidate.key, candidate.canonicalName, showName = true)
                        Column(Modifier.weight(1f)) {
                            Heading(candidate.scientificName, style = MaterialTheme.typography.titleMedium)
                            candidate.matchedName?.let { Text("Matched " + it + " · " + candidate.matchedLanguage.orEmpty()) }
                            Text((candidate.matchType ?: "Stored species") + " · " + candidate.confidence?.let { "$it% confidence · " }.orEmpty() + candidate.rank)
                            OutlinedButton(onClick = {
                                if (candidate.requiresConfirmation) selected = candidate else onSelect(candidate, result.id, false)
                            }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose " + candidate.canonicalName) }
                        }
                    }
                }
            } else selected?.let { candidate -> item {
                Heading("Confirm species")
                ReferencePhoto(imageApi, candidate.key, candidate.canonicalName)
                Text("This match needs your confirmation. Is " + candidate.scientificName + " the species you meant?")
                Button(onClick = { onSelect(candidate, result.id, true) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirm " + candidate.canonicalName) }
                TextButton(onClick = { selected = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to candidates") }
            } }
        }
        item { Text("Names from GBIF, CC BY 4.0") }
    }
}
@Composable fun PlantCareSettings(json: String, writable: Boolean, onSave: (JSONObject) -> Unit) {
    val settings = remember(json) { JSONObject(json) }
    val types = listOf("water", "fertilize", "clean_leaves", "remove_dead", "cut_back", "repot")
    val intervals = remember(json) { mutableStateMapOf<String, String>().apply {
        val current = settings.getJSONObject("customIntervals")
        types.forEach { put(it, if (current.isNull(it)) "" else current.optString(it)) }
    } }
    PlantColumn {
        Heading("Care settings", style = MaterialTheme.typography.headlineSmall)
        Heading("Custom intervals", style = MaterialTheme.typography.titleLarge)
        Text("Whole days from 1 to 365. Clear to use the computed interval. Season and applicability rules still apply.")
        types.forEach { key ->
            OutlinedTextField(intervals[key].orEmpty(), { intervals[key] = it }, label = { Text("${taskLabel(key)} interval in days") }, enabled = writable,
                isError = !intervals[key].isNullOrBlank() && intervals[key]?.toIntOrNull() !in 1..365, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { intervals[key] = "" }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear ${taskLabel(key)} interval") }
        }
        Button(onClick = {
            val custom = JSONObject(); intervals.forEach { (key, value) -> value.toIntOrNull()?.let { custom.put(key, it) } }
            onSave(JSONObject().put("customIntervals", custom))
        }, enabled = writable && intervals.values.all { it.isBlank() || it.toIntOrNull() in 1..365 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save care settings") }
    }
}
@Composable fun SpeciesNames(species: Species, options: PlantOptions, writable: Boolean, onCommand: (String, JSONObject) -> Unit) {
    var name by remember(species.key) { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading(species.scientificName, style = MaterialTheme.typography.headlineSmall); Text("GBIF key: " + species.key) }
        options.languages.forEach { (code, label) ->
            item { Heading(label) }
            val names = species.names.filter { it.language == code }
            if (names.isEmpty()) item { Text("No names stored yet.") }
            items(names, key = { it.id }) { entry ->
                Text(entry.name + " · Source: " + entry.source)
                if (entry.source == "owner") TextButton(onClick = { onCommand(entry.id, JSONObject()) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove " + entry.name) }
            }
        }
        item {
            Heading("Add an owner name")
            OutlinedTextField(name, { name = it }, label = { Text("Owner name") }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
            Choice("Language", language, options.languages, writable) { language = it }
            Button(onClick = { onCommand("add", JSONObject().put("name", name).put("language", language)); name = "" }, enabled = writable && name.trim().length in 1..200, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add name") }
            Text("Owner names survive external-name refreshes.")
            OutlinedButton(onClick = { onCommand("refresh", JSONObject()) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh names") }
            if (species.fetching) Text("Fetching names in the background. Failed sources retry with backoff, up to eight attempts.")
            if (species.failed) Text("A name source stopped after eight failures. Refresh names to try again. Stored names remain available.")
            Text("Species and names from GBIF, CC BY 4.0. Wikidata names are CC0.")
        }
    }
}
