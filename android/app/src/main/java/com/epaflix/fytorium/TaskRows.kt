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
    val event = state.handled[task.plantId + ":" + task.key] ?: task.completedEvent
    val actions = state.member?.let { task.actions(today, it, event) }.orEmpty()
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
                }
            }
            if ("snooze" in actions) IconButton(onClick = { onAction("snooze") }, enabled = state.writable, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.NotificationsPaused, contentDescription = "Snooze 2 days", tint = MaterialTheme.colorScheme.primary)
            }
            if (event != null) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, contentDescription = "${event.label} for ${task.plantName}", tint = MaterialTheme.colorScheme.onPrimary) }
                }
            } else if ("complete" in actions) {
                IconButton(onClick = { onAction("complete") }, enabled = state.writable,
                    modifier = Modifier.size(48.dp).border(2.dp, if (state.writable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = "Mark ${task.label} for ${task.plantName} done" }) {
                    // An unfilled circle is the completion affordance, not a second checkbox.
                }
            }
        }
        if ("undo" in actions) TextButton(onClick = { onAction("undo") }, enabled = state.writable,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).padding(end = 12.dp)) { Text("Undo") }
    }
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
