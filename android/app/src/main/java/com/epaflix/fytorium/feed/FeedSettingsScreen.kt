package com.epaflix.fytorium.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.Role

@Composable fun FeedSettingsScreen(state: FeedState, onSaveFeed: (Map<String, Boolean>) -> Unit = {},
                                  onSaveLocation: (WeatherLocation?) -> Unit = {}) {
    if (state.member?.role !in listOf(Role.owner, Role.family)) return
    Text("Feed settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    FeedNotice(state)
    var settings by remember(state.settings) { mutableStateOf(state.settings ?: emptyMap()) }
    feedLabels.forEach { (key, label) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = settings[key] == true, enabled = state.writable,
            role = SemanticsRole.Checkbox, onValueChange = { settings = settings + (key to it) }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(settings[key] == true, onCheckedChange = null, enabled = state.writable)
            Text(label)
        }
    }
    Button(onClick = { onSaveFeed(settings) }, enabled = state.writable && settings.size == feedLabels.size, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save my feed") }
    Text("Weather location", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
    Text("One location for the household. Coordinates are sent to Open-Meteo to fetch local weather and modelled climate.")
    if (!state.locationLoaded) { Text("Location unavailable. Reconnect and refresh to try again."); return }
    if (state.member?.role != Role.owner) {
        Text(state.location?.label ?: if (state.location != null) "Location set." else "No location set.")
        Text("Only the owner can change it.")
        return
    }
    var latitude by remember(state.location) { mutableStateOf(state.location?.latitude?.toString() ?: "") }
    var longitude by remember(state.location) { mutableStateOf(state.location?.longitude?.toString() ?: "") }
    var label by remember(state.location) { mutableStateOf(state.location?.label ?: "") }
    val empty = latitude.isBlank() && longitude.isBlank()
    val lat = latitude.toDoubleOrNull()?.takeIf { it.isFinite() }
    val lon = longitude.toDoubleOrNull()?.takeIf { it.isFinite() }
    val valid = empty || lat != null && lon != null
    OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, enabled = state.writable, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, enabled = state.writable, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(label, { label = it }, label = { Text("Place label, optional") }, enabled = state.writable, modifier = Modifier.fillMaxWidth())
    Text("Leave both coordinates blank to remove the location.")
    if (!valid) Text("Enter both coordinates as numbers, or leave both blank.")
    Button(onClick = { onSaveLocation(if (empty) null else WeatherLocation(lat!!, lon!!, label)) }, enabled = state.writable && valid, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save location") }
}
