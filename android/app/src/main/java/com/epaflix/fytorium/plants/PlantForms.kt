package com.epaflix.fytorium.plants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import com.epaflix.fytorium.DialogBody
import com.epaflix.fytorium.DialogTitle
import com.epaflix.fytorium.ApiClient
import com.epaflix.fytorium.barHeight
import com.epaflix.fytorium.CareTask
import com.epaflix.fytorium.Plant
import com.epaflix.fytorium.countLabel
import com.epaflix.fytorium.dayMonthYear
import com.epaflix.fytorium.nullable
import com.epaflix.fytorium.taskLabel
import com.epaflix.fytorium.taskNames
import org.json.JSONObject

@Composable internal fun savedFields(key: String?, initial: () -> Map<String, String>): androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> =
    rememberSaveable(key, saver = mapSaver(
        save = { fields: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> -> fields.toMap() },
        restore = { values -> values.map { it.key to it.value.toString() }.toMutableStateMap() })) { initial().toList().toMutableStateMap() }

internal val numberKeys = KeyboardOptions(keyboardType = KeyboardType.Number)
internal fun choiceLabel(value: String) = value.replace('_', ' ').replaceFirstChar(Char::uppercase)

@Composable internal fun Heading(text: String, style: TextStyle = MaterialTheme.typography.titleLarge, modifier: Modifier = Modifier) {
    Text(text, style = style, modifier = modifier.semantics { heading() })
}
// An invisible copy of the longest message keeps its space, so one appearing there moves nothing below it.
@Composable internal fun PlantError(message: String?, keep: String? = null) {
    if (message != null || keep != null) Box {
        keep?.let { Text(it, Modifier.alpha(0f).clearAndSetSemantics {}) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Choice(label: String, value: String, options: Map<String, String>, enabled: Boolean = true, supporting: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(value = options[value] ?: choiceLabel(value), onValueChange = {}, readOnly = true,
            label = { Text(label) }, enabled = enabled, supportingText = supporting,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}
// The server refuses a birthday after its UTC date, so the picker offers no later day.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Birthday(value: String, enabled: Boolean, error: String?, today: LocalDate, onChange: (String) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val shown = value.takeIf { it.isNotBlank() }?.let { runCatching { dayMonthYear(it) }.getOrNull() ?: it } ?: "Not set"
    OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Birthday: $shown") }
    PlantError(error)
    if (choosing) {
        val initial = remember(value) { runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val latest = maxOf(today.year, runCatching { LocalDate.parse(value).year }.getOrDefault(today.year))
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial, yearRange = 1900..latest, selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            override fun isSelectableYear(year: Int) = year <= today.year
        })
        DatePickerDialog(onDismissRequest = { choosing = false },
            confirmButton = { TextButton(onClick = { onChange(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()); choosing = false }) { Text("Set birthday") } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } }) { DialogBody { DatePicker(picker) } }
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
@Composable internal fun Expander(label: String, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Hide $label" else "Show $label", onClick = onToggle)
        .semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
}
// Material floats the label only over text, so the decoration gets the current name while the field's own value stays empty.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NameField(value: String, onChange: (String) -> Unit, current: String?, enabled: Boolean, errorText: String?, hint: String) {
    val source = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val focused by source.collectIsFocusedAsState()
    val standIn = current?.takeIf { value.isEmpty() }
    BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .padding(top = with(LocalDensity.current) { (MaterialTheme.typography.bodySmall.lineHeight / 2).toDp() })
        .semantics { standIn?.let { stateDescription = "Empty, shows $it" }; errorText?.let { error(it) } }
        .defaultMinSize(OutlinedTextFieldDefaults.MinWidth, OutlinedTextFieldDefaults.MinHeight), enabled = enabled, singleLine = true, interactionSource = source,
        textStyle = LocalTextStyle.current.copy(color = when { !enabled -> colors.disabledTextColor; errorText != null -> colors.errorTextColor
            focused -> colors.focusedTextColor; else -> colors.unfocusedTextColor }),
        cursorBrush = SolidColor(if (errorText != null) colors.errorCursorColor else colors.cursorColor)) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(value = standIn ?: value, innerTextField = { Box {
            standIn?.let { Text(it, color = if (enabled) colors.unfocusedPlaceholderColor else colors.disabledPlaceholderColor, modifier = Modifier.clearAndSetSemantics {}) }
            inner()
        } }, enabled = enabled, singleLine = true, visualTransformation = VisualTransformation.None, interactionSource = source, isError = errorText != null,
            label = { Text("Name") }, supportingText = { if (errorText == null) Text(hint) else PlantError(errorText, keep = hint) }, colors = colors)
    }
}
private val leadingFields = setOf("nickname", "site_id", "gbif_key", "_form")
// Name, site and Save lead; everything else waits under More details, below Save, which opens by itself when one of its fields has an error.
@Composable fun PlantForm(plant: Plant?, options: PlantOptions, sites: List<PlantSite>, writable: Boolean, errors: Map<String, String> = emptyMap(),
    onSave: (JSONObject) -> Unit, today: LocalDate = LocalDate.now(ZoneOffset.UTC), onDirty: (() -> Boolean) -> Unit = {}) {
    val original = remember(plant?.id) { JSONObject(plant?.attributesJson ?: "{}") }
    val startTexts = remember(plant?.id) { (listOf("nickname", "cultivar", "birthday") + options.numbers).associateWith { key -> if (original.isNull(key)) "" else original.optString(key) } }
    val startChoices = remember(plant?.id) { options.choices.mapValues { (key, values) -> original.nullable(key) ?: values.first() } +
        mapOf("health" to (if (original.isNull("health")) "" else original.optString("health")), "site_id" to (if (original.isNull("site_id")) "" else original.optString("site_id"))) }
    val startFlags = remember(plant?.id) { options.booleans.associateWith { original.optBoolean(it).toString() } }
    val texts = savedFields(plant?.id) { startTexts }
    val choices = savedFields(plant?.id) { startChoices }
    val flags = savedFields(plant?.id) { startFlags }
    SideEffect { onDirty { texts.toMap() != startTexts || choices.toMap() != startChoices || flags.toMap() != startFlags } }
    var more by rememberSaveable(plant?.id) { mutableStateOf(false) }
    LaunchedEffect(errors) { if (errors.keys.any { it !in leadingFields }) more = true }
    val validNumbers = options.numbers.all { texts[it].isNullOrBlank() || (texts[it]?.toIntOrNull()?.let { n -> n > 0 } == true) }
    PlantColumn {
        val shownName = plant?.displayName?.takeIf { original.isNull("nickname") }
        NameField(texts["nickname"].orEmpty(), { texts["nickname"] = it }, shownName, writable, errors["nickname"],
            if (shownName != null) "Leave empty to keep using the species name" else "Leave empty to use the species name")
        // A site removed elsewhere before Save is named in the line kept under Site, so Save stays where it was.
        // shortcut: copies readPlant's "Choose a site in your household." wording to keep its space; upgrade when /api/plants sends its refusal texts or the server wording changes.
        Choice("Site", choices["site_id"].orEmpty(), mapOf("" to if (plant?.status == "graveyard") "No site" else "Choose a site") + sites.associate { it.id to it.name }, writable,
            supporting = { PlantError(errors["site_id"], keep = "Choose a site in your household.") }) { choices["site_id"] = it }
        if (sites.isEmpty()) Text("Create a site under More before adding or restoring a plant.")
        Button(onClick = {
            val body = JSONObject()
            choices.forEach { (key, value) -> body.put(key, value.ifBlank { null } ?: JSONObject.NULL) }
            texts.forEach { (key, value) -> body.put(key, if (key in options.numbers) value.toIntOrNull() ?: JSONObject.NULL else value.ifBlank { null } ?: JSONObject.NULL) }
            flags.forEach { (key, value) -> body.put(key, value.toBoolean()) }
            onSave(body)
        }, enabled = writable && validNumbers && (plant?.status == "graveyard" || !choices["site_id"].isNullOrBlank()), modifier = Modifier.heightIn(min = 48.dp)) { Text("Save plant") }
        // A failed save's message and every field error below this point go under the button, so the button stays where the finger is for another try.
        PlantError(errors["_form"])
        PlantError(errors["gbif_key"])
        Expander("More details", more) { more = !more }
        if (more) {
            OutlinedTextField(texts["cultivar"].orEmpty(), { texts["cultivar"] = it }, label = { Text("Cultivar") }, enabled = writable, singleLine = true,
                isError = errors.containsKey("cultivar"), supportingText = errors["cultivar"]?.let { { PlantError(it) } }, modifier = Modifier.fillMaxWidth())
            Birthday(texts["birthday"].orEmpty(), writable, errors["birthday"], today) { texts["birthday"] = it }
            Choice("Health", choices["health"].orEmpty(), mapOf("" to "Not set") + options.health.associateWith(::choiceLabel), writable) { choices["health"] = it }
            PlantError(errors["health"])
            options.choices.forEach { (key, values) ->
                Choice(options.labels.getValue(key), choices[key].orEmpty(), values.associateWith(::choiceLabel), writable) { choices[key] = it }
                PlantError(errors[key])
            }
            options.numbers.forEach { key ->
                OutlinedTextField(texts[key].orEmpty(), { texts[key] = it }, label = { Text(options.labels.getValue(key)) }, enabled = writable, keyboardOptions = numberKeys,
                    isError = errors.containsKey(key) || (!texts[key].isNullOrBlank() && texts[key]?.toIntOrNull()?.let { it > 0 } != true),
                    supportingText = { if (errors[key] != null) PlantError(errors[key]) else Text("Positive whole number, or blank for unknown") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            options.booleans.forEach { key ->
                Toggle(options.labels.getValue(key), flags[key].toBoolean(), writable) { flags[key] = it.toString() }
                PlantError(errors[key])
            }
        }
    }
}
// The plant's own intervals. Each task says how often it is due now and where that comes from, so a blank field means something.
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlantCareSettings(json: String, writable: Boolean, onSave: (JSONObject) -> Unit, tasks: Map<String, CareTask> = emptyMap(),
    plantName: String = "", onSpeciesCare: (() -> Unit)? = null, onBack: (() -> Unit)? = null,
    barTitle: @Composable (@Composable () -> Unit) -> Unit = { it() }, error: String? = null, onDirty: (() -> Boolean) -> Unit = {}) {
    val opened = rememberSaveable { json }
    val custom = remember(opened) { JSONObject(opened).getJSONObject("customIntervals") }
    val types = taskNames.keys.toList()
    val stored = remember(opened) { types.associateWith { if (custom.isNull(it)) "" else custom.optString(it) } }
    val intervals = savedFields(opened) { stored }
    SideEffect { onDirty { intervals.toMap() != stored } }
    val valid = intervals.values.all { it.isBlank() || it.toIntOrNull() in 1..365 }
    fun current(type: String): String? {
        val task = tasks[type] ?: return null
        if (task.status != "due") return "Not scheduled for this plant now"
        val days = task.intervalDays ?: return null
        val source = when {
            !custom.isNull(type) -> "your setting for this plant"
            task.basedOn.firstOrNull()?.source == "rule" -> "from Fytorium's estimate"
            else -> "from species care"
        }
        return "Every ${countLabel(days, "day")}, $source"
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { barTitle { Heading("This plant's schedule") } }, expandedHeight = barHeight(), navigationIcon = {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
        }, actions = {
            TextButton(onClick = {
                val saved = JSONObject(); intervals.forEach { (key, value) -> value.toIntOrNull()?.let { saved.put(key, it) } }
                onSave(JSONObject().put("customIntervals", saved))
            }, enabled = writable && valid, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PlantError(error)
            Text("Changes here apply only to ${plantName.ifBlank { "this plant" }}. Care defaults for this species change every plant of its species.")
            onSpeciesCare?.let { TextButton(onClick = it, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Care defaults for this species") } }
            types.forEach { key ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Heading(taskLabel(key), MaterialTheme.typography.titleMedium)
                    current(key)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val value = intervals[key].orEmpty()
                    val invalid = value.isNotBlank() && value.toIntOrNull() !in 1..365
                    OutlinedTextField(value, { intervals[key] = it }, label = { Text("${taskLabel(key)} interval in days") }, enabled = writable, keyboardOptions = numberKeys,
                        isError = invalid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(if (invalid) "Whole days from 1 to 365" else "Blank uses the computed interval") },
                        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { intervals[key] = "" }, enabled = writable) {
                            Icon(Icons.Default.Close, contentDescription = "Clear ${taskLabel(key)} interval for ${plantName.ifBlank { "this plant" }}") } })
                }
            }
            Text("Season and applicability rules still apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private val nameSources = mapOf("owner" to "Added by your household", "wikidata" to "Wikidata", "gbif" to "GBIF", "nortaxa" to "NorTaxa")
@Composable fun SpeciesNames(species: Species, options: PlantOptions, writable: Boolean, onCommand: (String, JSONObject, onSuccess: () -> Unit) -> Unit,
    list: LazyListState = rememberLazyListState(), error: String? = null, target: String? = null, onDirty: (() -> Boolean) -> Unit = {}, draft: MutableState<String>? = null) {
    // A read landing while the user types, or a failed add, must not drop the draft; only a successful add clears it.
    val nameState: MutableState<String> = draft ?: rememberSaveable(species.key) { mutableStateOf("") }
    var name by nameState
    SideEffect { onDirty { name.isNotBlank() } }
    var language by rememberSaveable(species.key) { mutableStateOf("en") }
    var others by rememberSaveable(species.key) { mutableStateOf(false) }
    var removing by rememberSaveable(species.key) { mutableStateOf<SpeciesName?>(null) }
    val shown = species.commonName
    val household = species.names.filter { it.source == "owner" }
    val sourced = species.names.filter { it.source != "owner" && it.language in options.languages }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("title") {
            Column {
                Heading(shown ?: species.canonicalName, style = MaterialTheme.typography.headlineSmall)
                if (shown != null) Text(species.canonicalName, color = muted)
            }
        }
        item("in-use") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (shown != null) "Plants of this species without a name of their own show \u201c$shown\u201d."
                    else "Plants of this species without a name of their own show its scientific name, ${endSentence(species.canonicalName)}")
                Text(when {
                    shown == null -> "No source has a common name for it yet."
                    household.any { sameName(it.name, shown) } -> "Your household added this name."
                    else -> "Fytorium chose it from the names sources give."
                }, color = muted)
            }
        }
        item("household") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Heading("Your household's name")
                Text("A name added here shows on every plant of this species without a name of its own, and wins over names from sources. To rename one plant, edit its name instead.")
                OutlinedTextField(name, { name = it }, label = { Text("Common name") }, enabled = writable, singleLine = true, modifier = Modifier.fillMaxWidth())
                Choice("Language", language, options.languages, writable) { language = it }
                Button(onClick = { onCommand("add", JSONObject().put("name", name.trim()).put("language", language)) { name = "" } }, enabled = writable && name.trim().length in 1..200,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(target?.let { "Add name to $it" } ?: "Add name") }
                PlantError(error)
            }
        }
        items(household, key = { it.id }) { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name + " \u00b7 " + (options.languages[entry.language] ?: entry.language), Modifier.weight(1f))
                TextButton(onClick = { removing = entry }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove " + entry.name) }
            }
        }
        item("others") { Expander("Other names from sources (${sourced.size})", others) { others = !others } }
        if (others) options.languages.forEach { (code, label) ->
            val names = sourced.filter { it.language == code }
            if (names.isNotEmpty()) {
                item("language:$code") { Heading(label, MaterialTheme.typography.titleSmall) }
                items(names, key = { it.id }) { entry -> Text(entry.name + " \u00b7 " + (nameSources[entry.source] ?: entry.source)) }
            }
        }
        item("refresh") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onCommand("refresh", JSONObject()) {} }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh names") }
                if (species.fetching) Text("Some names are still loading.")
                Text("Species and names from GBIF, CC BY 4.0. Wikidata names are CC0.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
    removing?.let { entry ->
        val renames = entry.renamedPlants
        val next = entry.renamedTo?.let { "\u201c$it\u201d" } ?: "another name for this species"
        AlertDialog(onDismissRequest = { removing = null }, title = { DialogTitle("Remove this name?") },
            text = { DialogBody { Text("Remove \u201c${entry.name}\u201d from this species?"); Text(when (renames) {
                0 -> "No plant shows this name now, so removing it renames none."
                1 -> "1 plant shows this name now and will show $next instead."
                else -> "$renames plants show this name now and will show $next instead."
            }) } },
            confirmButton = { TextButton(onClick = { removing = null; onCommand(entry.id, JSONObject()) {} }, enabled = writable) { Text("Remove name") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}
