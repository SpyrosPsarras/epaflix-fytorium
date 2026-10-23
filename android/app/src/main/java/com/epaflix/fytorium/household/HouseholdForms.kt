package com.epaflix.fytorium.household

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.Choice

@Composable private fun Toggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled); Text(label)
    }
}
@Composable private fun SharedPlants(plants: List<TaskTarget>, selected: Set<String>, enabled: Boolean, change: (Set<String>) -> Unit) {
    HouseholdHeading("Shared plants")
    for (plant in plants) Toggle(plant.name, plant.id in selected, enabled) { value -> change(if (value) selected + plant.id else selected - plant.id) }
}
@Composable internal fun InviteForm(plants: List<TaskTarget>, enabled: Boolean, save: (String, Set<String>) -> Unit) {
    var role by remember { mutableStateOf("family") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    Choice("Role", role, listOf("family" to "Family", "sitter" to "Sitter"), enabled) { role = it }
    if (role == "sitter") SharedPlants(plants, selected, enabled) { selected = it }
    Button(onClick = { save(role, if (role == "sitter") selected else emptySet()) }, enabled = enabled) { Text("Create invite link") }
}
@Composable internal fun MemberForm(member: HouseholdMember, plants: List<TaskTarget>, enabled: Boolean, save: (String, Set<String>) -> Unit) {
    var role by remember(member) { mutableStateOf(member.role) }
    var selected by remember(member) { mutableStateOf(member.plantIds) }
    HouseholdHeading(member.name)
    if (member.roleOptions.isNotEmpty()) Choice("Role", role, member.roleOptions.map { it to it.replaceFirstChar(Char::uppercase) }, enabled) { role = it }
    if (role == "sitter") SharedPlants(plants, selected, enabled) { selected = it }
    Button(onClick = { save(role, if (role == "sitter") selected else emptySet()) }, enabled = enabled) { Text("Save member") }
}
@Composable internal fun TaskForm(initial: CustomTask, errors: Map<String, String>, enabled: Boolean, save: (CustomTask) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var interval by remember(initial) { mutableStateOf(initial.interval) }
    OutlinedTextField(name, { name = it }, label = { Text("Task name") }, enabled = enabled, isError = errors.containsKey("name"),
        supportingText = errors["name"]?.let { { HouseholdError(it) } }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(interval, { interval = it }, label = { Text("Interval in days") }, enabled = enabled, isError = errors.containsKey("interval_days"),
        supportingText = errors["interval_days"]?.let { { HouseholdError(it) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Button(onClick = { save(initial.copy(name = name, interval = interval)) }, enabled = enabled) { Text("Save task") }
}
@Composable internal fun HouseholdSettingsForm(state: HouseholdState, owner: Boolean, enabled: Boolean,
    onLocation: (Map<String, String>) -> Unit, onFeed: (Map<String, Boolean>) -> Unit) {
    var location by remember(state.location) { mutableStateOf(state.location) }
    var feed by remember(state.feed) { mutableStateOf(state.feed) }
    HouseholdHeading("My home feed")
    Text("Choose the cards you want to see. Other household members keep their own choices.")
    for ((key, label) in feedLabels) Toggle(label, feed[key] == true, enabled) { feed = feed + (key to it) }
    Button(onClick = { onFeed(feed) }, enabled = enabled) { Text("Save my feed") }
    HouseholdHeading("Weather location")
    Text("One location for the household. Coordinates are sent to Open-Meteo to fetch local weather and modelled climate.")
    for ((key, label) in mapOf("latitude" to "Latitude", "longitude" to "Longitude", "label" to "Place label, optional")) {
        OutlinedTextField(location[key].orEmpty(), { location = location + (key to it) }, label = { Text(label) }, enabled = enabled && owner,
            keyboardOptions = KeyboardOptions(keyboardType = if (key == "label") KeyboardType.Text else KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
    if (owner) {
        Text("Leave both coordinates blank to remove the location.")
        Button(onClick = { onLocation(location) }, enabled = enabled) { Text("Save location") }
    } else Text("Only the owner can change it.")
}
