package com.epaflix.fytorium

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val ThumbnailLoading = SemanticsPropertyKey<Boolean>("ThumbnailLoading")
private var SemanticsPropertyReceiver.thumbnailLoading by ThumbnailLoading

fun lateLabel(days: Int): String = if (days < 60) "${days}d late" else "${days / 30}mo late"

@Composable internal fun TaskRow(task: CareTask, state: CareState, today: String, photo: Bitmap?,
    onAction: (String) -> Unit, showType: Boolean = false, photoLoading: Boolean = false, onOpen: () -> Unit = {}) {
    val event = task.completedEvent
    val actions = state.member?.let { task.actions(today, it, event) }.orEmpty()
    val busy = BusyKey.Row(task.occurrence) in state.pending
    val enabled = state.writable && !busy
    Column(Modifier.fillMaxWidth().testTag("task:${task.plantId}:${task.key}")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = "Open ${task.plantName}", onClick = onOpen)
                .semantics { contentDescription = "Open ${task.plantName}" }, verticalAlignment = Alignment.CenterVertically) {
                TaskPhoto(task, photo, event == null, photoLoading)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(task.plantName, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val subtitle = if (showType) listOf(task.label, task.siteName).filter { it.isNotBlank() }.joinToString(" · ") else task.siteName
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showType && event == null) task.postponedLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if ("snooze" in actions) IconButton(onClick = { onAction("snooze") }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.NotificationsPaused, contentDescription = "Snooze 2 days", tint = MaterialTheme.colorScheme.primary)
            }
            if (busy) {
                Box(Modifier.size(48.dp).semantics { contentDescription = "Saving ${task.label} for ${task.plantAndSite}" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (event != null) {
                val check = @Composable { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) } }
                val watered = event.batchId == null && state.linkedWater(event)
                val done = "${task.label}${if (watered) " and water" else ""} done for ${task.plantAndSite}"
                if ("undo" in actions) {
                    val description = done + if (watered) ". Double tap to undo both" else if (event.batchId == null) ". Double tap to undo"
                        else ". Part of a batch" + state.batches[event.batchId]?.let { " of ${it.scope}" }.orEmpty() + ". Double tap to see the batch"
                    Surface(onClick = { onAction("undo") }, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = description }, content = check)
                } else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = done }, content = check)
            } else if ("complete" in actions) {
                IconButton(onClick = { onAction("complete") }, enabled = enabled,
                    modifier = Modifier.size(48.dp).border(2.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = "Mark ${task.label} done for ${task.plantAndSite}" }) {
                    // An unfilled circle is the completion affordance, not a second checkbox.
                }
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
