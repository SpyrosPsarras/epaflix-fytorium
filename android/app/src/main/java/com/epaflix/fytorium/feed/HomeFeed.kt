package com.epaflix.fytorium.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.Role

@Composable fun HomeFeed(state: FeedState, onSettings: () -> Unit = {}, onOpen: (String) -> Unit = {},
                         onWater: () -> Unit = {}, onUndo: () -> Unit = {}, compact: Boolean = false) {
    if (!compact) Text("Home feed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    if (!compact && state.member?.role in listOf(Role.owner, Role.family)) TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Customize my feed") }
    FeedNotice(state)
    if (!compact && !state.loaded) Text(if (state.offline) "Feed unavailable offline" else if (state.refreshing) "Loading feed…" else "Feed unavailable. Refresh to try again.")
    val cards = state.cards.filter { state.member?.role != Role.sitter || it.kind == "late_tasks" }
    val latestRain = cards.lastOrNull { it.rain }?.id
    // Match HomeFeed.svelte's three groups; retain server order within each group.
    val ordered = cards.filter { it.kind in listOf("late_tasks", "current_weather") } +
        cards.filter { it.kind == "weather_alerts" } + cards.filter { it.kind == "plant_warnings" }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ordered.forEach { card ->
            Card(Modifier.width(if (compact) 280.dp else 300.dp).testTag("feed-${card.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onOpen(card.href) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(card.title, style = MaterialTheme.typography.titleMedium)
                    }
                    card.lines.take(if (compact) 2 else card.lines.size).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    card.plants.take(if (compact) 1 else card.plants.size).forEach { plant -> TextButton(onClick = { onOpen("/plants/${plant.id}") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(plant.name) } }
                    if (!compact && card.href == "/#today") TextButton(onClick = { onOpen(card.href) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("See all in Today") }
                    if (card.id == latestRain && state.weather != null) {
                        state.weather.rain?.let { rain ->
                            Text("It rained: ${rainAmount(rain.precipitationMm)} mm today or yesterday.")
                            if (rain.batchId != null) Text("Marked watered.")
                            else OutlinedButton(onClick = onWater, enabled = state.writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Mark ${rain.plantCount} outdoor plants watered") }
                        }
                        if (state.weather.lastBatchId != null) OutlinedButton(onClick = onUndo, enabled = state.writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo rain watering") }
                        if (state.weather.rain == null && state.weather.lastBatchId == null) Text("Rain watering is no longer available for this report.")
                    }
                    card.warnings.take(if (compact) 1 else card.warnings.size).forEach { warning ->
                        Text(warning.title, style = MaterialTheme.typography.titleSmall)
                        Text(warning.reason)
                        var expanded by remember(card.id, warning.title) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Warning inputs") }
                        if (expanded) warning.inputs.forEach { Text(it) }
                    }
                }
            }
        }
    }
}

@Composable internal fun FeedNotice(state: FeedState) {
    val message = when {
        state.offline -> if (state.loaded) "Feed is offline. Showing last loaded cards. Actions are unavailable offline." else "Actions are unavailable offline."
        state.unavailable -> "Fytorium isn't responding. Showing last loaded cards. Refresh to try again."
        else -> state.message
    }
    message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}
