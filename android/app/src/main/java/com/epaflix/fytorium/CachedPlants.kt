package com.epaflix.fytorium

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Read-only cached views; plant editing belongs to A05.
@Composable fun CachedPlants(state: CareState, onPlant: (String) -> Unit, thumbnails: Thumbnails?, initialPlant: String? = null) {
    var selected by remember(initialPlant) { mutableStateOf(initialPlant) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Plants", style = MaterialTheme.typography.headlineMedium)
        OfflineNotice(state)
        val detail = state.detail?.takeIf { it.plant.id == selected }
        if (selected != null) {
            TextButton(onClick = { selected = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to plants") }
            if (detail == null) Text("No cached details yet. Connect to load this plant.") else {
                Text(detail.plant.displayName, style = MaterialTheme.typography.titleLarge)
                Text(detail.plant.scientificName)
                Thumbnail(detail.plant, thumbnails)
                Text("Care history", style = MaterialTheme.typography.titleLarge)
                if (detail.history.isEmpty()) Text("No care events yet.")
                detail.history.forEach { event ->
                    Text("${event.occurredOn} · ${taskLabel(event.taskType, event.customTaskName)}")
                    Text("${event.label} by ${event.byName}" + if (event.undoneAt != null) " · Undone" else "")
                }
                detail.tasks.forEach { (key, task) ->
                    Text(taskLabel(task.taskType ?: key, task.customTaskName))
                    Text(if (task.status == "due") "Due ${task.dueOn}" else "Not applicable")
                    if (task.basedOn.isNotEmpty()) Provenance(task.basedOn)
                }
            }
        } else {
            val plants = if (state.member?.role == Role.sitter) (state.today?.sections.orEmpty() + state.upcoming?.sections.orEmpty())
                .flatMap { it.tasks }.distinctBy { it.plantId }.map { Plant(it.plantId, it.plantName, "", "active", null, null, null, null, null) }
                else state.plants
            if (plants.isEmpty()) Text("No plants yet.")
            plants.forEach { plant ->
                Thumbnail(plant, thumbnails)
                OutlinedButton(onClick = { selected = plant.id; onPlant(plant.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(plant.displayName) }
            }
        }
    }
}
@Composable internal fun Thumbnail(plant: Plant, thumbnails: Thumbnails?) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, plant.coverPhotoId, thumbnails) {
        value = withContext(Dispatchers.IO) { plant.coverPhotoId?.let { thumbnails?.load(it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Photo of ${plant.displayName}", modifier = Modifier.size(96.dp)) }
}
