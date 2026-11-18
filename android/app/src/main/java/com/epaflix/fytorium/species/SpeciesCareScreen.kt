package com.epaflix.fytorium.species

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.Role
import com.epaflix.fytorium.plants.endSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable fun SpeciesCareRoute(api: ApiClient, speciesKey: Long?, care: CareState, connected: () -> Boolean,
    onBack: () -> Unit, onChanged: () -> Unit = {}, onSpecies: (Long) -> Unit = {}, onUpdateRequired: () -> Unit = {}, onReceipt: (String) -> Unit = {},
    answers: Int = 0, onFormDirty: (() -> String?) -> Unit = {}) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    var target by rememberSaveable { mutableStateOf(speciesKey) }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(speciesKey, editing) { if (!editing) target = speciesKey }
    val repository = remember(api, member, target) {
        SpeciesCareRepository(api, target,
            { member != null && member.role != Role.sitter && currentCare.member == member },
            { currentConnected() && !currentCare.updateRequired })
    }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    val permitted = member != null && member.role != Role.sitter
    LaunchedEffect(repository, permitted) { if (permitted) withContext(Dispatchers.IO) { repository.refresh() } }
    // Once Fytorium answers another request, a read it left unanswered here loads again without a tap, also while changes are off, and pending fetches poll again.
    LaunchedEffect(answers) {
        val failed = repository.state.value.run { readFailed && !loading }
        if (permitted && failed) withContext(Dispatchers.IO) { repository.refresh() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // At most 60 reloads per route, including lifecycle restarts.
    var polls by remember(repository) { mutableStateOf(0) }
    LaunchedEffect(repository, permitted, lifecycle, state.pendingFetch) {
        if (permitted && state.pendingFetch) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (polls < 60 && repository.state.value.pendingFetch) {
                delay(2_000)
                val current = repository.state.value
                if (!current.pendingFetch) break
                if (!current.loading && !current.saving) {
                    polls++
                    withContext(Dispatchers.IO) { repository.refresh() }
                }
            }
        }
    }
    // Care commands invalidate task provenance, but task refresh state never gates this route.
    suspend fun change(receipt: String? = null, command: () -> Boolean): Boolean {
        val species = state.card?.let { it.commonName ?: it.canonicalName }
        val success = withContext(Dispatchers.IO) { command() }
        if (success) { onChanged(); receipt?.let { onReceipt("$it for $species") } }
        return success
    }
    val pinned = state.card?.takeIf { editing && target != speciesKey }?.let { card ->
        val now = care.detail?.plant?.attributesJson?.let { JSONObject(it).let { a -> a.nullable("common_name") ?: a.nullable("canonical_name") } } ?: "another species"
        "This plant is now ${endSentence(now)} You're editing care for ${endSentence(card.commonName ?: card.canonicalName)}" to
            "Save or discard this override to see care for ${endSentence(now)}"
    }
    SpeciesCareScreen(state, connected() && !care.updateRequired, permitted, onBack,
        onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onRefreshCare = { scope.launch { change { repository.refreshCare() } } },
        onOverride = { field, value -> change("Saved " + shortLabel(field)) { repository.override(field, value) } },
        onReset = { field -> change("Removed your " + shortLabel(field)) { repository.reset(field) } },
        onRetry = { key -> scope.launch { change { repository.retryMatch(key) } } },
        onClear = repository::clearMessage, onSpecies = onSpecies, unmatched = target == null, onFormDirty = onFormDirty,
        onEditing = { editing = it }, note = pinned)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SpeciesCareScreen(state: SpeciesCareState, online: Boolean = true, canEdit: Boolean = true,
    onBack: () -> Unit = {}, onRefresh: () -> Unit = {}, onRefreshCare: () -> Unit = {},
    onOverride: suspend (String, Any) -> Boolean = { _, _ -> false }, onReset: suspend (String) -> Boolean = { false },
    onRetry: (Long) -> Unit = {}, onClear: () -> Unit = {}, onSpecies: (Long) -> Unit = {}, unmatched: Boolean = state.card == null,
    onFormDirty: (() -> String?) -> Unit = {}, onEditing: (Boolean) -> Unit = {}, note: Pair<String, String>? = null) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editingKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var leaveOverride by remember { mutableStateOf<(() -> Unit)?>(null) }
    var resetting by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    SideEffect { onEditing(editing != null || resetting != null) }
    val scope = rememberCoroutineScope()
    val enabled = canEdit && online && state.writable && !submitting
    fun back() { if (editing != null) leaveOverride?.invoke() else onBack() }
    BackHandler { back() }
    // An open dialog shows its own save's failure next to its fields.
    val failure = state.error?.takeIf { editing == null && resetting == null } ?: state.pageError
    val status = (when {
        !canEdit || state.updateRequired -> null
        !online -> "Offline" to "This phone can't reach Fytorium. The values on this page stay readable. Reconnect and refresh to make changes."
        else -> (failure ?: state.message)?.let { it to it }
    }).withNote(note)
    Scaffold(topBar = { Column {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { status?.first == failure && (state.blocked || state.pageError != null) }) {
            Text(if (unmatched) "Unmatched species" else "Care defaults for this species", maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }, expandedHeight = barHeight(), navigationIcon = {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
        // Progress has its own fixed slot under the bar, so it never moves or covers the page.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (canEdit && (state.loading || state.saving || submitting)) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canEdit) {
                state.card?.let { card ->
                    val title = card.commonName ?: card.canonicalName
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (card.commonName != null) Text(card.canonicalName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("These values set the starting care for every plant of $title in your household. To change one plant only, use its schedule on the plant page.")
                    Text("Starting estimates. Check the plant and soil before following a watering interval.")
                    // The values Fytorium knows lead; a field without one waits in its own fold, still open to a value of your own.
                    val (known, unknown) = careLabels.entries.partition { (field) -> card.profile.optJSONObject(field)?.isNull("value") == false }
                    val edit = { field: String -> onClear(); editingKey = card.key; editingName = title; editing = field }
                    val reset = { field: String -> onClear(); resetting = field }
                    known.forEach { (field, label) -> CareField(card.profile.optJSONObject(field), field, label, enabled, edit, reset) }
                    OutdoorMonths(card.outdoor)
                    var missing by rememberSaveable(card.key) { mutableStateOf(false) }
                    if (unknown.isNotEmpty()) com.epaflix.fytorium.plants.Expander("No value yet (${unknown.size})", missing) { missing = !missing }
                    if (missing) unknown.forEach { (field, label) -> CareField(null, field, label, enabled, edit, reset) }
                    // Where the values come from is for checking a source, so it waits behind its own heading.
                    var sources by rememberSaveable(card.key) { mutableStateOf(false) }
                    com.epaflix.fytorium.plants.Expander("Sources", sources) { sources = !sources }
                    if (sources) {
                        Text("GBIF key: ${card.key}")
                        known.forEach { (field, label) ->
                            val item = card.profile.getJSONObject(field)
                            val source = item.known("source")?.takeIf { it != "unknown" }
                            if (source == "override") Text("$label: your value")
                            else source?.let { Text("$label: ${it.replace('_', ' ')}" + item.known("ruleId")?.let { rule -> " \u00b7 $rule" }.orEmpty() +
                                item.known("fetchedAt")?.let { at -> " \u00b7 fetched ${readableDate(at)}" }.orEmpty()) }
                        }
                        OutdoorSources(card.outdoor)
                        for (fetch in card.fetches) {
                            if (fetch.has("matchStatus")) {
                                val status = fetch.getString("matchStatus")
                                Text("Open Plantbook: ${if (status == "matched") "matched ${fetch.optString("pid")}" else status.replace('_', ' ')}")
                                OutlinedButton(onClick = { onRetry(card.key) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry match") }
                            }
                            val source = fetch.getString("source").split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                            Text(if (fetch.getString("status") == "disabled") "$source not configured" else "$source fetch: ${fetch.getString("status").replace('_', ' ')}")
                        }
                        OutlinedButton(onClick = onRefreshCare, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh care data") }
                        OutlinedButton(onClick = onRefresh, enabled = online && !state.loading && !state.saving && !submitting,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh species") }
                    }
                } ?: run {
                    if (unmatched) {
                    OutlinedButton(onClick = onRefresh, enabled = online && !state.loading && !state.saving && !submitting,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh species") }
                    Text("These species of yours have no care data from sources yet. Fytorium's own estimates still apply.")
                    if (state.loaded && state.unmatched.isEmpty()) Text("Every species of yours has care data.")
                    for (species in state.unmatched) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val title = species.commonName ?: species.name
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            if (species.commonName != null) Text(species.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (species.status == "ambiguous") "No clear care data match yet" else "No care data found yet")
                            TextButton(onClick = { onSpecies(species.key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Species care for $title") }
                            OutlinedButton(onClick = { onRetry(species.key) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Look for care data again") }
                        }
                    }
                    }
                }
            }
        }
    }
    if (canEdit) editing?.let { field -> key(editingKey, field) {
        val card = state.card
        val current = card?.profile?.optJSONObject(field)?.opt("value")
        var draft by rememberSaveable { mutableStateOf(draftOf(field, current)) }
        var low by rememberSaveable { mutableStateOf((current as? JSONObject)?.opt("min")?.toString().orEmpty()) }
        var high by rememberSaveable { mutableStateOf((current as? JSONObject)?.opt("max")?.toString().orEmpty()) }
        var months by rememberSaveable { mutableStateOf((current as? JSONArray)?.takeIf { field == "pruningMonths" }?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()) }
        var invalid by remember(field) { mutableStateOf<String?>(null) }
        val opened = rememberSaveable { listOf(draft, low, high, months.toList()) }
        // Read at the moment something leaves the dialog, so Back right after a keystroke still asks.
        fun changed() = opened != listOf(draft, low, high, months.toList())
        var discarding by rememberSaveable { mutableStateOf(false) }
        val question = "Discard your ${shortLabel(field)} for $editingName?"
        // The shell asks the same question before a notification leaves the dialog with a typed value.
        SideEffect { onFormDirty { question.takeIf { changed() } } }
        DisposableEffect(field) { onDispose { leaveOverride = null; onFormDirty { null } } }
        fun close() { discarding = false; editing = null; onClear() }
        fun leave() { if (changed()) discarding = true else close() }
        SideEffect { leaveOverride = ::leave }
        // The server refuses a minimum above the maximum, so the dialog says so as the values are typed.
        val order = if (field !in careRanges) null else number(low)?.let { min -> number(high)?.let { max -> "Minimum must be lower than maximum".takeIf { min.toDouble() > max.toDouble() } } }
        // Temperatures can be below zero, which number pads without a minus key cannot type.
        val numberKeys = KeyboardOptions(keyboardType = if (field in setOf("temperatureRangeC", "hardinessC")) KeyboardType.Text else KeyboardType.Decimal)
        if (card != null) AlertDialog(modifier = Modifier.width(320.dp), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { if (!submitting) leave() },
            title = { Text("Override ${lower(careLabels.getValue(field))}") },
            text = { DialogBody {
                val label = "Override value for ${lower(careLabels.getValue(field))}"
                // Each kind of value gets its own control, so nobody types JSON.
                when {
                    field in careChoices -> Choice(label, draft, listOf("" to "Choose a value") + careChoices.getValue(field).map { it to choiceText(it) }, enabled) { draft = it; invalid = null }
                    field in careRanges -> {
                        Text(label)
                        OutlinedTextField(low, { low = it; invalid = null; onClear() }, label = { Text("Minimum") }, enabled = enabled, singleLine = true, keyboardOptions = numberKeys, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(high, { high = it; invalid = null; onClear() }, label = { Text("Maximum") }, enabled = enabled, singleLine = true, keyboardOptions = numberKeys, modifier = Modifier.fillMaxWidth())
                    }
                    field == "pruningMonths" -> {
                        Text(label)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            monthNames.forEachIndexed { index, name ->
                                val month = index + 1
                                FilterChip(selected = month in months, onClick = { months = if (month in months) months - month else months + month; invalid = null },
                                    label = { Text(name.take(3)) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = name })
                            }
                        }
                    }
                    else -> {
                        Text(label)
                        OutlinedTextField(draft, { draft = it; invalid = null }, label = { Text(when (field) { "careText" -> "Care text"; "pests" -> "Pests"; "hardinessC" -> "Temperature in \u00b0C"; else -> "Days" }) },
                            supportingText = if (field == "pests") ({ Text("Separate pests with commas") }) else null, enabled = enabled, maxLines = 5,
                            keyboardOptions = if (field in careNumbers) numberKeys else KeyboardOptions.Default, modifier = Modifier.fillMaxWidth())
                    }
                }
                CareMessage(order ?: invalid ?: state.error, retry = if (!state.blocked) null else ({
                    TextButton(onClick = onRefresh, enabled = online && !state.loading && !submitting) { Text("Retry loading species") } }))
            } },
            confirmButton = { TextButton(onClick = {
                val value = overrideValue(field, draft, low, high, months.sorted()) ?: run { invalid = "Enter a value for every field."; return@TextButton }
                scope.launch { submitting = true; try { if (onOverride(field, value)) editing = null } finally { submitting = false } }
            }, enabled = enabled && order == null) { Text("Save override") } },
            dismissButton = { TextButton(onClick = { leave() }, enabled = !submitting) { Text("Cancel") } })
        if (discarding) com.epaflix.fytorium.plants.DiscardDialog(question, onKeep = { discarding = false }) { close() }
    } }
    if (canEdit) resetting?.let { field ->
        AlertDialog(onDismissRequest = { if (!submitting) { resetting = null; onClear() } }, title = { Text("Remove override for ${lower(careLabels.getValue(field))}?") },
            text = { DialogBody { Text("Every plant of this species goes back to the value from its source data or Fytorium's rule."); CareMessage(state.error) } },
            confirmButton = { TextButton(onClick = { scope.launch { submitting = true; try { if (onReset(field)) resetting = null } finally { submitting = false } } }, enabled = enabled) { Text("Confirm reset") } },
            dismissButton = { TextButton(onClick = { resetting = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
}

@Composable private fun CareField(item: JSONObject?, field: String, label: String, enabled: Boolean, onEdit: (String) -> Unit, onReset: (String) -> Unit) {
    val own = item?.optString("source") == "override"
    Card(Modifier.fillMaxWidth().testTag("care:$field")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (item?.isNull("value") == false) Text(displayCareValue(field, item.opt("value")))
            if (own) Text("Your value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onEdit(field) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Override ${lower(label)}") }
            if (own) OutlinedButton(onClick = { onReset(field) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove override for ${lower(label)}") }
        }
    }
}

// The message keeps two lines from the start; a longer one or Retry scrolls into view inside the dialog body.
@Composable private fun CareMessage(message: String?, retry: (@Composable () -> Unit)? = null) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(message, retry != null) { if (message != null) requester.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(requester)) {
        Text(message.orEmpty(), color = MaterialTheme.colorScheme.error, minLines = 2,
            modifier = if (message == null) Modifier.clearAndSetSemantics {} else Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        retry?.invoke()
    }
}

// A dialog body keeps the height it opened with and scrolls inside it, so a failed save's message never moves the dialog's buttons.
@Composable private fun DialogBody(content: @Composable ColumnScope.() -> Unit) {
    val opened = remember { IntArray(1) { -1 } }
    Column(Modifier.layout { measurable, constraints ->
        val held = opened[0].takeIf { it >= 0 }?.coerceAtMost(constraints.maxHeight)
        val body = measurable.measure(held?.let { constraints.copy(minHeight = it, maxHeight = it) } ?: constraints)
        // Recorded when placed, as intrinsic measurements of the dialog also run this block.
        layout(body.width, body.height) { if (opened[0] < 0) opened[0] = body.height; body.place(0, 0) }
    }.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable private fun OutdoorMonths(months: JSONObject) {
    Text("Outdoor months", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (!months.optBoolean("locationSet")) Text("Set your location to see outdoor months")
    else {
        fun show(field: String): String = if (months.isNull(field)) "Climate refresh pending" else months.getJSONArray(field).let {
            if (it.length() == 0) "No suitable months" else displayCareValue("pruningMonths", it)
        }
        Text("Pot: ${show("pot")}"); Text("Ground: ${show("ground")}")
        Text("Ground hardiness: ${months.get("groundThresholdC")} °C. Potted hardiness: ${months.get("potThresholdC")} °C.")
        Text("Monthly mean minimum must be at least hardiness plus ${months.get("marginC")} °C. This estimate does not rule out frost.")
        if (!months.isNull("unavailableSince")) Text("Climate unavailable since ${readableDate(months.getString("unavailableSince"))}. Last good values remain.")
    }
}

@Composable private fun OutdoorSources(months: JSONObject) {
    if (!months.optBoolean("locationSet")) return
    val hardiness = months.getJSONObject("hardiness")
    Text("Hardiness source: ${hardiness.getString("source").replace('_', ' ')} · ${hardiness.get("value")} °C${hardiness.optString("ruleId").takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" }.orEmpty()}")
    if (hardiness.optString("ruleId") == "hardiness.usda_uncertain@1") Text("USDA zone conversion is uncertain. Perenual does not name its hardiness scale.")
    Text("Rules: ${months.getString("potRuleId")} · ${months.getString("ruleId")}")
    Text("Climate from Open-Meteo, CMCC_CM2_VHR4 model, 1991 to 2020 daily minimum averages. Fetched: ${months.known("fetchedAt")?.let(::readableDate) ?: "not fetched"}.")
}

private fun lower(label: String) = label.replaceFirstChar(Char::lowercase)
// A receipt names the field without its unit: "Saved watering interval for Spider Plant".
private fun shortLabel(field: String) = lower(careLabels.getValue(field).substringBefore(" in "))
private fun JSONObject.known(name: String) = optString(name).takeIf { it.isNotBlank() && it != "null" }
private fun readableDate(value: String) = runCatching { dayMonthYear(java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) }
    .recoverCatching { dayMonthYear(value.take(10)) }.getOrDefault(value)
private fun choiceText(value: String) = when (value) { "true" -> "Yes"; "false" -> "No"; else -> value.replace('_', ' ').replaceFirstChar(Char::uppercase) }
private val careRanges = setOf("temperatureRangeC", "soilMoistureRangePercent", "hardiness", "dimensions")
private val careNumbers = setOf("wateringIntervalDays", "fertilizingIntervalDays", "hardinessC")
private fun draftOf(field: String, value: Any?): String = when {
    value == null || value == JSONObject.NULL || value is JSONObject -> ""
    value is JSONArray -> if (field == "pests") (0 until value.length()).joinToString(", ") { value.getString(it) } else ""
    else -> value.toString()
}
private fun number(text: String): Number? = text.trim().replace(',', '.').toDoubleOrNull()?.let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < Int.MAX_VALUE) it.toInt() else it }
private fun overrideValue(field: String, draft: String, low: String, high: String, months: List<Int>): Any? = when {
    field in setOf("indoor", "poisonousToPets", "poisonousToHumans") -> draft.toBooleanStrictOrNull()
    field in careChoices -> draft.takeIf { it.isNotBlank() }
    field in careRanges -> number(low)?.let { min -> number(high)?.let { max ->
        JSONObject().put("min", min).put("max", max).apply { if (field == "dimensions") put("unit", "cm") } } }
    field == "pruningMonths" -> months.takeIf { it.isNotEmpty() }?.let { JSONArray(it) }
    field == "pests" -> JSONArray(draft.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    field in careNumbers -> number(draft)
    else -> draft.trim().takeIf { it.isNotEmpty() }
}
