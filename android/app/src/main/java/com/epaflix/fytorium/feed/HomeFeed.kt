package com.epaflix.fytorium.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.Role
import com.epaflix.fytorium.countLabel

// The task list below already shows late tasks with their late pills, and late tasks were the only cards sitters had.
internal fun FeedState.todayCards() = if (member?.role == Role.sitter) emptyList() else cards.filter { it.kind != "late_tasks" }
// The strip appears or goes only at a moment the user picks, so a refresh never moves the rows below it.
internal fun FeedState.keepingStripOf(shown: FeedState) =
    if (shown.member == member && shown.todayCards().isEmpty() != todayCards().isEmpty()) copy(cards = shown.cards) else this
private data class Why(val title: String, val lines: List<String>, val technical: List<String>, val plants: List<Pair<String, String>> = emptyList())

@Composable fun HomeFeed(state: FeedState, today: String? = null, offline: Boolean = state.offline, connectionNotice: Boolean = true, sites: Map<String, String?> = emptyMap(),
                         onOpen: (String) -> Unit = {}, onWater: () -> Unit = {}, onUndo: () -> Unit = {}) {
    if (state.member == null || state.member.role == Role.sitter) return
    if (offline) Text("Weather and tips need a connection", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    else FeedNotice(state, connectionNotice)
    val cards = state.todayCards()
    if (cards.isEmpty()) return
    val latestRain = cards.lastOrNull { it.rain }?.id
    val ordered = cards.filter { it.kind != "plant_warnings" }
        .sortedBy { when { it.id == latestRain -> 0; it.rain -> 1; it.kind == "weather_alerts" -> 2; else -> 3 } }
    var why by remember { mutableStateOf<Why?>(null) }
    val type = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    // Top padding, two headline lines and a two-line button fit the rain card's fullest state, so a refresh that changes cards never moves the rows below.
    val height = with(LocalDensity.current) {
        28.dp + (measurer.measure("1\n2", type.titleSmall).size.height + measurer.measure("1\n2", type.labelLarge).size.height).toDp()
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(height).testTag("feed-strip"),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ordered.forEach { card ->
            val title = card.headline(today)
            when {
                card.id == latestRain -> {
                    val weather = state.weather
                    val rain = weather?.rain
                    val undoable = weather?.lastBatchId != null
                    val waterable = rain != null && rain.batchId == null
                    StripCard("feed-${card.id}", title, { onOpen(card.href) }, detail = weather?.rainReceipt?.takeIf { undoable && waterable }) {
                        when {
                            waterable -> TextButton(onClick = onWater, enabled = state.writable, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) {
                                Text("Mark ${countLabel(rain.plantCount, "outdoor plant")} watered", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            undoable -> Text(weather?.rainReceipt.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 12.dp))
                            weather != null -> Text("Rain watering is no longer available.", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp))
                        }
                        if (undoable) TextButton(onClick = onUndo, enabled = state.writable, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Undo rain watering" }) { Text("Undo") }
                    }
                }
                card.rain || card.why.isEmpty() -> StripCard("feed-${card.id}", title, { onOpen(card.href) })
                else -> StripCard("feed-${card.id}", title, { onOpen(card.href) }, onWhy = { why = Why(title, card.why, card.technical) })
            }
        }
        cards.warningCards(sites, owner = state.member.role == Role.owner).forEach { warning ->
            val explain = { why = Why(warning.title, warning.why, warning.technical, warning.plants) }
            StripCard(warning.tag, warning.title, warning.href?.let { href -> { onOpen(href) } } ?: explain, onWhy = explain)
        }
    }
    why?.let { shown ->
        AlertDialog(onDismissRequest = { why = null }, confirmButton = { TextButton(onClick = { why = null }) { Text("Close") } },
            title = { Text(shown.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.lines.forEach { Text(it) }
                shown.plants.forEach { (name, href) ->
                    TextButton(onClick = { why = null; onOpen(href) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(name) }
                }
                if (shown.technical.isNotEmpty()) Text("Technical details: " + shown.technical.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } })
    }
}

@Composable private fun StripCard(tag: String, title: String, onOpen: () -> Unit, onWhy: (() -> Unit)? = null, detail: String? = null,
                                  action: @Composable RowScope.() -> Unit = {}) {
    Card(onClick = onOpen, modifier = Modifier.width(248.dp).fillMaxHeight().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxHeight().padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (detail == null) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp))
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp)) }
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
        state.unavailable -> if (!connectionNotice) null
            else "Fytorium isn't responding. " + (if (state.todayCards().isEmpty()) "" else "Showing last loaded cards. ") + "Refresh to try again."
        else -> state.message
    }
    message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}
