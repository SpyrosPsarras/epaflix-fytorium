package com.epaflix.fytorium.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.Role
import com.epaflix.fytorium.countLabel

// The task list below already shows late tasks with their late pills, and late tasks were the only cards sitters had.
internal fun FeedState.todayCards() = if (member?.role == Role.sitter) emptyList() else cards.filter { it.kind != "late_tasks" }

@Composable fun HomeFeed(state: FeedState, today: String? = null, connectionNotice: Boolean = true, onOpen: (String) -> Unit = {},
                         onWater: () -> Unit = {}, onUndo: () -> Unit = {}) {
    FeedNotice(state, connectionNotice)
    val cards = state.todayCards()
    val latestRain = cards.lastOrNull { it.rain }?.id
    val ordered = cards.sortedBy { when { it.id == latestRain -> 0; it.rain -> 1; it.kind == "weather_alerts" -> 2; it.kind == "current_weather" -> 3; else -> 4 } }
    var why by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // Every card takes the tallest card's height, so a short card leaves no empty band above the toggle.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(IntrinsicSize.Min).testTag("feed-strip"),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ordered.forEach { card ->
            val title = card.headline(today)
            when {
                card.kind == "plant_warnings" -> card.warnings.forEach { warning ->
                    StripCard("feed-${card.id}-${warning.kind}", warning.text, { onOpen(card.href) },
                        onWhy = { why = warning.text to listOf(warning.reason) + warning.inputs })
                }
                card.id == latestRain -> {
                    val weather = state.weather
                    val rain = weather?.rain
                    val undoable = weather?.lastBatchId != null
                    val waterable = rain != null && rain.batchId == null
                    StripCard("feed-${card.id}", title, { onOpen(card.href) }, detail = weather?.rainReceipt?.takeIf { undoable && waterable }) {
                        when {
                            waterable -> TextButton(onClick = onWater, enabled = state.writable, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) {
                                Text("Mark ${countLabel(rain.plantCount, "outdoor plant")} watered")
                            }
                            undoable -> Text(weather?.rainReceipt.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            weather != null -> Text("Rain watering is no longer available.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                        if (undoable) TextButton(onClick = onUndo, enabled = state.writable, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Undo rain watering" }) { Text("Undo") }
                    }
                }
                card.rain || card.why.isEmpty() -> StripCard("feed-${card.id}", title, { onOpen(card.href) })
                else -> StripCard("feed-${card.id}", title, { onOpen(card.href) }, onWhy = { why = title to card.why })
            }
        }
    }
    why?.let { (title, lines) ->
        AlertDialog(onDismissRequest = { why = null }, confirmButton = { TextButton(onClick = { why = null }) { Text("Close") } },
            title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { lines.forEach { Text(it) } } })
    }
}

@Composable private fun StripCard(tag: String, title: String, onOpen: () -> Unit, onWhy: (() -> Unit)? = null, detail: String? = null,
                                  action: @Composable RowScope.() -> Unit = {}) {
    Card(onClick = onOpen, modifier = Modifier.width(248.dp).fillMaxHeight().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxHeight().padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                action()
                if (onWhy != null) TextButton(onClick = onWhy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Why: $title" }) { Text("Why?") }
            }
        }
    }
}

@Composable internal fun FeedNotice(state: FeedState, connectionNotice: Boolean = true) {
    val message = when {
        state.offline -> if (!connectionNotice) null else if (state.loaded) "Feed is offline. Showing last loaded cards. Actions are unavailable offline." else "Actions are unavailable offline."
        state.unavailable -> if (connectionNotice) "Fytorium isn't responding. Showing last loaded cards. Refresh to try again." else null
        else -> state.message
    }
    message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}
