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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable fun SpeciesCareRoute(api: ApiClient, speciesKey: Long?, care: CareState, connected: () -> Boolean,
    onBack: () -> Unit, onChanged: () -> Unit = {}, onSpecies: (Long) -> Unit = {}, onUpdateRequired: () -> Unit = {}) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member, speciesKey) {
        SpeciesCareRepository(api, speciesKey,
            { member != null && member.role != Role.sitter && currentCare.member == member },
            { currentConnected() && !currentCare.updateRequired })
    }
    val state by repository.state.collectAsState()
    LaunchedEffect(state.updateRequired) { if (state.updateRequired) onUpdateRequired() }
    val scope = rememberCoroutineScope()
    val permitted = member != null && member.role != Role.sitter
    LaunchedEffect(repository, permitted) { if (permitted) withContext(Dispatchers.IO) { repository.refresh() } }
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
    suspend fun change(command: () -> Boolean): Boolean {
        val success = withContext(Dispatchers.IO) { command() }
        if (success) onChanged()
        return success
    }
    SpeciesCareScreen(state, connected() && !care.updateRequired, permitted, onBack,
        onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onRefreshCare = { scope.launch { change(repository::refreshCare) } },
        onOverride = { field, value -> change { repository.override(field, value) } },
        onReset = { field -> change { repository.reset(field) } },
        onRetry = { key -> scope.launch { change { repository.retryMatch(key) } } },
        onClear = repository::clearMessage, onSpecies = onSpecies, unmatched = speciesKey == null)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SpeciesCareScreen(state: SpeciesCareState, online: Boolean = true, canEdit: Boolean = true,
    onBack: () -> Unit = {}, onRefresh: () -> Unit = {}, onRefreshCare: () -> Unit = {},
    onOverride: suspend (String, Any) -> Boolean = { _, _ -> false }, onReset: suspend (String) -> Boolean = { false },
    onRetry: (Long) -> Unit = {}, onClear: () -> Unit = {}, onSpecies: (Long) -> Unit = {}, unmatched: Boolean = state.card == null) {
    var editing by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enabled = canEdit && online && state.writable && !submitting
    BackHandler(onBack = onBack)
    // An open dialog shows its own save's failure next to its fields.
    val failure = state.error?.takeIf { editing == null && resetting == null } ?: state.pageError
    val status = when {
        !canEdit || state.updateRequired -> null
        !online -> "Offline" to "This phone can't reach Fytorium. The values on this page stay readable. Reconnect and refresh to make changes."
        else -> (failure ?: state.message)?.let { it to it }
    }
    Scaffold(topBar = { Column {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { status?.first == failure && (state.blocked || state.pageError != null) }) {
            Text(if (unmatched) "Unmatched species" else "Care defaults for this species", maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }, expandedHeight = barHeight(), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                    for ((field, label) in careLabels) {
                        val item = card.profile.optJSONObject(field)
                        Card(Modifier.fillMaxWidth().testTag("care:$field")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                Text(displayCareValue(field, item?.opt("value")))
                                item?.known("source")?.takeIf { it != "unknown" }?.let { source ->
                                    Text("Source: ${source.replace('_', ' ')}" + item.known("ruleId")?.let { " \u00b7 $it" }.orEmpty() +
                                        item.known("fetchedAt")?.let { " \u00b7 fetched ${readableDate(it)}" }.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = { onClear(); editing = field }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Override ${lower(label)}") }
                                if (item?.optString("source") == "override") OutlinedButton(onClick = { onClear(); resetting = field }, enabled = enabled,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove override for ${lower(label)}") }
                            }
                        }
                    }
                    OutdoorMonths(card.outdoor)
                    // Where the values come from is for checking a source, so it waits behind its own heading.
                    var sources by rememberSaveable(card.key) { mutableStateOf(false) }
                    com.epaflix.fytorium.plants.Expander("Sources", sources) { sources = !sources }
                    if (sources) {
                        Text("GBIF key: ${card.key}")
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
                    Text("Open Plantbook unmatched species", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Rule estimates and other sources still apply. Open a species card to retry its match.")
                    if (state.loaded && state.unmatched.isEmpty()) Text("No unmatched species.")
                    for (species in state.unmatched) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${species.name}: ${species.status.replace('_', ' ')}")
                            TextButton(onClick = { onSpecies(species.key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Species care for ${species.name}") }
                            OutlinedButton(onClick = { onRetry(species.key) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry match") }
                        }
                    }
                    }
                }
            }
        }
    }
    if (canEdit) editing?.let { field ->
        val current = state.card?.profile?.optJSONObject(field)?.opt("value")
        var draft by remember(field) { mutableStateOf(draftOf(field, current)) }
        var low by remember(field) { mutableStateOf((current as? JSONObject)?.opt("min")?.toString().orEmpty()) }
        var high by remember(field) { mutableStateOf((current as? JSONObject)?.opt("max")?.toString().orEmpty()) }
        val months = remember(field) { mutableStateListOf<Int>().apply { (current as? JSONArray)?.takeIf { field == "pruningMonths" }?.let { a -> (0 until a.length()).forEach { add(a.getInt(it)) } } } }
        var invalid by remember(field) { mutableStateOf<String?>(null) }
        // The server refuses a minimum above the maximum, so the dialog says so as the values are typed.
        val order = if (field !in careRanges) null else number(low)?.let { min -> number(high)?.let { max -> "Minimum must be lower than maximum".takeIf { min.toDouble() > max.toDouble() } } }
        // Temperatures can be below zero, which number pads without a minus key cannot type.
        val numberKeys = KeyboardOptions(keyboardType = if (field in setOf("temperatureRangeC", "hardinessC")) KeyboardType.Text else KeyboardType.Decimal)
        AlertDialog(modifier = Modifier.width(320.dp), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { if (!submitting) { editing = null; onClear() } },
            title = { Text("Override ${lower(careLabels.getValue(field))}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                FilterChip(selected = month in months, onClick = { if (month in months) months.remove(month) else months.add(month); invalid = null },
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
                CareMessage(order ?: invalid ?: state.error)
                if (state.blocked) TextButton(onClick = onRefresh, enabled = online && !state.loading && !submitting) { Text("Retry loading species") }
            } },
            confirmButton = { TextButton(onClick = {
                val value = overrideValue(field, draft, low, high, months.sorted()) ?: run { invalid = "Enter a value for every field."; return@TextButton }
                scope.launch { submitting = true; try { if (onOverride(field, value)) editing = null } finally { submitting = false } }
            }, enabled = enabled && order == null) { Text("Save override") } },
            dismissButton = { TextButton(onClick = { editing = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
    if (canEdit) resetting?.let { field ->
        AlertDialog(onDismissRequest = { if (!submitting) { resetting = null; onClear() } }, title = { Text("Remove override for ${lower(careLabels.getValue(field))}?") },
            text = { Column { Text("Every plant of this species goes back to the value from its source data or Fytorium's rule."); CareMessage(state.error) } },
            confirmButton = { TextButton(onClick = { scope.launch { submitting = true; try { if (onReset(field)) resetting = null } finally { submitting = false } } }, enabled = enabled) { Text("Confirm reset") } },
            dismissButton = { TextButton(onClick = { resetting = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
}

@Composable private fun CareMessage(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
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
        val hardiness = months.getJSONObject("hardiness")
        Text("Hardiness source: ${hardiness.getString("source").replace('_', ' ')} · ${hardiness.get("value")} °C${hardiness.optString("ruleId").takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" }.orEmpty()}")
        if (hardiness.optString("ruleId") == "hardiness.usda_uncertain@1") Text("USDA zone conversion is uncertain. Perenual does not name its hardiness scale.")
        Text("Rules: ${months.getString("potRuleId")} · ${months.getString("ruleId")}")
        Text("Climate from Open-Meteo, CMCC_CM2_VHR4 model, 1991 to 2020 daily minimum averages. Fetched: ${months.known("fetchedAt")?.let(::readableDate) ?: "not fetched"}.")
        if (!months.isNull("unavailableSince")) Text("Climate unavailable since ${readableDate(months.getString("unavailableSince"))}. Last good values remain.")
    }
}

private fun lower(label: String) = label.replaceFirstChar(Char::lowercase)
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
