package com.epaflix.fytorium.species

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import com.epaflix.fytorium.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable fun SpeciesCareRoute(api: ApiClient, speciesKey: Long?, care: CareState, connected: () -> Boolean,
    onBack: () -> Unit, onChanged: () -> Unit = {}, onSpecies: (Long) -> Unit = {}) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member, speciesKey) {
        SpeciesCareRepository(api, speciesKey,
            { member != null && member.role != Role.sitter && currentCare.member == member },
            { currentConnected() && !currentCare.updateRequired })
    }
    val state by repository.state.collectAsState()
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

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (unmatched) "Unmatched species" else "Species care") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canEdit) {
                if (!online) Text("Actions are unavailable offline. Reconnect and refresh to make changes.")
                if (state.updateRequired) Text("Update Fytorium. This server needs a newer app.")
                if (state.loading || state.saving || submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = onRefresh, enabled = online && !state.loading && !state.saving && !submitting,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh species") }
                if (editing == null && resetting == null) CareMessage(state.error)
                CareMessage(state.message, error = false)
                state.card?.let { card ->
                    Text(card.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("GBIF key: ${card.key}")
                    Text("Starting estimates. Check the plant and soil before following a watering interval.")
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
                    OutdoorMonths(card.outdoor)
                    for ((field, label) in careLabels) {
                        val item = card.profile.optJSONObject(field)
                        Card(Modifier.fillMaxWidth().testTag("care:$field")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                Text(displayCareValue(field, item?.opt("value")))
                                Text("Source: ${item?.optString("source") ?: "unknown"}${item?.optString("ruleId")?.takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" }.orEmpty()}")
                                Text("Fetch time: ${item?.optString("fetchedAt")?.takeIf { it.isNotBlank() && it != "null" } ?: "not fetched"}")
                                OutlinedButton(onClick = { onClear(); editing = field }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Override ${label.lowercase()}") }
                                if (item?.optString("source") == "override") OutlinedButton(onClick = { onClear(); resetting = field }, enabled = enabled,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove override for ${label.lowercase()}") }
                            }
                        }
                    }
                } ?: run {
                    if (unmatched) {
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
        var draft by remember(field) { mutableStateOf("") }
        var invalid by remember(field) { mutableStateOf<String?>(null) }
        AlertDialog(modifier = Modifier.width(320.dp), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { if (!submitting) { editing = null; onClear() } },
            title = { Text("Override ${careLabels.getValue(field).lowercase()}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = "Override value for ${careLabels.getValue(field).lowercase()}"
                careChoices[field]?.let { options ->
                    Choice(label, draft, listOf("" to "Choose a value") + options.map { it to it.replace('_', ' ') }, enabled) { draft = it; invalid = null }
                } ?: run {
                    Text(label)
                    OutlinedTextField(draft, { draft = it; invalid = null }, label = { Text("Value") }, enabled = enabled, maxLines = 5,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field in listOf("wateringIntervalDays", "fertilizingIntervalDays")) KeyboardType.Decimal else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth())
                }
                careExamples[field]?.let { Text("JSON format: $it") }
                CareMessage(invalid ?: state.error)
                if (state.blocked) TextButton(onClick = onRefresh, enabled = online && !state.loading && !submitting) { Text("Retry loading species") }
            } },
            confirmButton = { TextButton(onClick = {
                val value = runCatching {
                    if (field in listOf("light", "humidityNeed", "cycle")) draft.also { require(it.isNotBlank()) }
                    else JSONArray("[$draft]").also { require(it.length() == 1) }.get(0)
                }.getOrElse { invalid = "Enter a valid value. Ranges and lists use the JSON format shown."; return@TextButton }
                scope.launch { submitting = true; try { if (onOverride(field, value)) editing = null } finally { submitting = false } }
            }, enabled = enabled) { Text("Save override") } },
            dismissButton = { TextButton(onClick = { editing = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
    if (canEdit) resetting?.let { field ->
        AlertDialog(onDismissRequest = { if (!submitting) { resetting = null; onClear() } }, title = { Text("Remove override?") },
            text = { Column { Text("Remove override for ${careLabels.getValue(field).lowercase()}? The value will use its source data or Fytorium rule."); CareMessage(state.error) } },
            confirmButton = { TextButton(onClick = { scope.launch { submitting = true; try { if (onReset(field)) resetting = null } finally { submitting = false } } }, enabled = enabled) { Text("Confirm reset") } },
            dismissButton = { TextButton(onClick = { resetting = null; onClear() }, enabled = !submitting) { Text("Cancel") } })
    }
}

@Composable private fun CareMessage(message: String?, error: Boolean = true) {
    message?.let { Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
        Text("Climate from Open-Meteo, CMCC_CM2_VHR4 model, 1991 to 2020 daily minimum averages. Fetched: ${months.optString("fetchedAt").takeIf { it.isNotBlank() && it != "null" } ?: "not fetched"}.")
        if (!months.isNull("unavailableSince")) Text("Climate unavailable since ${months.getString("unavailableSince")}. Last good values remain.")
    }
}

// Input examples mirror the web card. Validation belongs to the server.
private val careExamples = mapOf("careText" to "\"Synthetic care advice\"", "soilMoistureRangePercent" to "{\"min\":20,\"max\":60}",
    "hardinessC" to "-5", "temperatureRangeC" to "{\"min\":15,\"max\":28}", "hardiness" to "{\"min\":9,\"max\":11}",
    "dimensions" to "{\"min\":30,\"max\":100,\"unit\":\"cm\"}", "pruningMonths" to "[3,4]", "pests" to "[\"Scale insects\"]")
