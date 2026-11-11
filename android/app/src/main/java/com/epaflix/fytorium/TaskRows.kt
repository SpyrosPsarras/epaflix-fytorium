package com.epaflix.fytorium

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val ThumbnailLoading = SemanticsPropertyKey<Boolean>("ThumbnailLoading")
private var SemanticsPropertyReceiver.thumbnailLoading by ThumbnailLoading

fun lateLabel(days: Int): String = if (days < 60) "${days}d late" else "${days / 30}mo late"

@Composable internal fun TaskRow(task: CareTask, state: CareState, today: String, photo: Bitmap?,
    onAction: (String) -> Unit, showType: Boolean = false, photoLoading: Boolean = false, onOpen: () -> Unit = {},
    showPostponed: Boolean = showType, listed: Boolean = true) {
    val event = task.completedEvent
    val postponed = event?.takeIf { it.kind != "completed" }
    val actions = state.member?.let { task.actions(today, it, event) }.orEmpty()
    val busy = BusyKey.Row(task.occurrence) in state.pending
    val enabled = state.writable && !busy && listed
    val filled = IconButtonDefaults.filledIconButtonColors()
    Column(Modifier.fillMaxWidth().testTag("task:${task.plantId}:${task.key}")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = "Open ${task.plantName}", onClick = onOpen)
                .semantics { contentDescription = "Open ${task.plantName}" }, verticalAlignment = Alignment.CenterVertically) {
                TaskPhoto(task, photo, event == null, photoLoading)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(task.plantName, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SiteLine(task, postponed, showType, showPostponed && event == null)
                }
            }
            if ("snooze" in actions) IconButton(onClick = { onAction("snooze") }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.NotificationsPaused, contentDescription = "Snooze 2 days", tint = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            } else Spacer(Modifier.size(48.dp))
            if (postponed != null) {
                if ("undo" in actions) IconButton(onClick = { onAction("undo") }, enabled = enabled, modifier = Modifier.size(48.dp).semantics {
                    contentDescription = if (busy) "Saving ${task.label} for ${task.plantAndSite}" else "${undoLabel(postponed, emptyList())} for ${task.plantAndSite}"
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                } else Spacer(Modifier.size(48.dp))
            } else if (busy) {
                Box(Modifier.size(48.dp).semantics { contentDescription = "Saving ${task.label} for ${task.plantAndSite}" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (event != null) {
                val undoable = "undo" in actions
                val check = @Composable { Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = if (undoable && !enabled) filled.disabledContentColor else filled.contentColor) } }
                val watered = event.batchId == null && state.linkedWater(event)
                val done = "${task.label}${if (watered) " and water" else ""} done for ${task.plantAndSite}"
                if (undoable) {
                    val description = done + if (!enabled) "" else if (watered) ". Double tap to undo both" else if (event.batchId == null) ". Double tap to undo"
                        else ". Part of a batch" + state.batches[event.batchId]?.let { " of ${it.scope}" }.orEmpty() + ". Double tap to see the batch"
                    Surface(onClick = { onAction("undo") }, enabled = enabled, shape = CircleShape,
                        color = if (enabled) filled.containerColor else filled.disabledContainerColor,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = description }, content = check)
                } else Surface(shape = CircleShape, color = filled.containerColor,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = done }, content = check)
            } else if ("complete" in actions) {
                IconButton(onClick = { onAction("complete") }, enabled = enabled,
                    modifier = Modifier.size(48.dp).border(2.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = "Mark ${task.label} done for ${task.plantAndSite}" }) {
                    // An unfilled circle is the completion affordance, not a second checkbox.
                }
            } else Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable private fun SiteLine(task: CareTask, postponed: CareEvent?, showType: Boolean, showPostponed: Boolean) {
    val parts = listOfNotNull(task.label.takeIf { showType }, task.siteName).filter { it.isNotBlank() }
    val until = task.postponedUntil
    val status = postponed?.let { until?.let { "until " + shortDate(it) } ?: it.label } ?: task.postponedLabel?.takeIf { showPostponed }
    val shorter = until?.takeIf { postponed != null }?.let { "until " + dayMonth(it) }
    val spoken = postponed?.let { it.label + until?.let { " until " + longDate(it) }.orEmpty() }
    val glyph = postponed?.let { if (it.kind == "snoozed") Icons.Outlined.NotificationsPaused else Icons.Default.SkipNext }
    val glyphSize = with(LocalDensity.current) { 18.sp.toDp() }
    val glyphWidth = with(LocalDensity.current) { if (glyph == null) 0 else (glyphSize + 4.dp).roundToPx() }
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
        BoxWithConstraints {
            val measurer = rememberTextMeasurer()
            val style = LocalTextStyle.current
            fun width(text: String) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            fun fits(subtitle: String, label: String) = (if (subtitle.isEmpty()) 0 else width(subtitle) + width(" · ")) + glyphWidth + width(label) <= constraints.maxWidth
            // The site gives way first, then the weekday, so the date itself is never cut.
            val choices = if (status == null) listOf(parts.joinToString(" · ") to status)
                else (parts.size downTo 0).map { parts.take(it).joinToString(" · ") to status } + listOfNotNull(shorter?.let { "" to it })
            val (subtitle, shown) = choices.firstOrNull { (text, label) -> label == null || fits(text, label) } ?: choices.last()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // An empty line keeps its height, so a postponed label appearing or going never changes the row's.
                if (subtitle.isEmpty() && shown == null) Text("", Modifier.clearAndSetSemantics {})
                if (subtitle.isNotEmpty()) Text(subtitle, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty() && shown != null) Text(" · ", Modifier.clearAndSetSemantics { testTag = "site-separator" }, maxLines = 1)
                if (glyph != null) Icon(glyph, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp).size(glyphSize).testTag("${postponed.kind}-glyph"))
                if (shown != null) Text(shown, Modifier.semantics { spoken?.let { contentDescription = it } }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun ButtonProgress(visible: Boolean) {
    if (visible) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
}

@Composable private fun TaskPhoto(task: CareTask, bitmap: Bitmap?, pending: Boolean, loading: Boolean) {
    Box(Modifier.width(56.dp).height(62.dp).testTag("thumbnail:${task.coverPhotoId ?: task.plantId}")
        .semantics { thumbnailLoading = loading }, contentAlignment = Alignment.TopCenter) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Photo of ${task.plantName}", contentScale = ContentScale.Crop, modifier = Modifier.clip(CircleShape))
            else Box(contentAlignment = Alignment.Center) { Text(task.plantName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.semantics { contentDescription = "No cover photo for ${task.plantName}" }) }
        }
        if (pending && task.lateDays > 0) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.BottomCenter).requiredWidth(IntrinsicSize.Max)) {
            Text(lateLabel(task.lateDays), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError,
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}
