package com.epaflix.fytorium.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun NotificationRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    val repository = remember(api, member) { NotificationRepository(api,
        { currentCare.member == member && member != null && !currentCare.updateRequired }, { currentConnected() }) }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE) }
    var asked by remember { mutableStateOf(prefs.getBoolean("asked", false)) }
    var notificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun systemSettings() = context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true; notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    BackHandler { onBack() }
    NotificationScreen(state, online = connected() && !care.updateRequired, sitter = member?.role == Role.sitter,
        permissionAsked = asked, notificationsEnabled = notificationsEnabled, onSystemSettings = { systemSettings() }, onEnable = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !asked) {
                // Persist before launching, so denial or process death never causes a repeated prompt.
                prefs.edit().putBoolean("asked", true).commit(); asked = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                systemSettings()
            }
        }, onBack = onBack, onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onSave = { withContext(Dispatchers.IO) { repository.save(it) } },
        onSwitch = { withContext(Dispatchers.IO) { repository.switch(it) } },
        onRemove = { withContext(Dispatchers.IO) { repository.remove(it) } },
        onTest = { withContext(Dispatchers.IO) { repository.test() } })
    LaunchedEffect(repository) { withContext(Dispatchers.IO) { repository.refresh() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NotificationScreen(state: NotificationState, online: Boolean = true, sitter: Boolean = false,
    permissionAsked: Boolean = false, notificationsEnabled: Boolean = true, onSystemSettings: () -> Unit = {}, onEnable: () -> Unit = {}, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onSave: suspend (NotificationSettings) -> Unit = {}, onSwitch: suspend (PushDevice) -> Unit = {},
    onRemove: suspend (PushDevice) -> Unit = {}, onTest: suspend () -> Unit = {}) {
    var input by remember(state.settings) { mutableStateOf(state.settings) }
    var submitting by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writable = online && state.loaded && !state.saving && !state.blocked && !state.updateRequired && !submitting
    fun act(action: suspend () -> Unit) {
        if (submitting) return
        submitting = true
        scope.launch { try { action() } finally { submitting = false } }
    }
    if (pickingTime) {
        val time = rememberTimePickerState(initialHour = input.time.substringBefore(':').toInt(), initialMinute = input.time.substringAfter(':').toInt(), is24Hour = true)
        AlertDialog(onDismissRequest = { pickingTime = false }, title = { Text("Reminder time") },
            text = { TimePicker(time) },
            confirmButton = { TextButton(onClick = {
                input = input.copy(time = "%02d:%02d".format(java.util.Locale.ROOT, time.hour, time.minute)); pickingTime = false
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set time") } },
            dismissButton = { TextButton(onClick = { pickingTime = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        Text("My notifications", style = MaterialTheme.typography.headlineMedium)
        Text("Daily reminders, weather alerts and completion notices use separate Android notification channels.")
        Text("Allow notifications to receive reminders. You can mute each category in Android settings.")
        Button(onClick = onEnable, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (permissionAsked) "Android notification settings" else "Enable notifications") }
        if (!notificationsEnabled) {
            Text("Notifications are off on this phone.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onSystemSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Android notification settings") }
        }
        if (!online) Text("Actions are unavailable offline. Reconnect and refresh to make changes.")
        if (state.updateRequired) Text("Update Fytorium. This server needs a newer app.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(if (!notificationsEnabled && it == "Test notification sent.") "Test notification sent, but notifications are off on this phone." else it) }
        if (state.loading || state.saving || submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.saving && !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh notifications") }
        NotificationSwitch("Daily reminders", input.reminders, writable) { input = input.copy(reminders = it) }
        OutlinedButton(onClick = { pickingTime = true }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reminder time: ${input.time}") }
        state.timezone?.let { Text("Time zone: $it") }
        Text("Empty days send nothing. Today's reminder replaces the previous one.")
        if (!sitter) NotificationSwitch("Weather alerts", input.weather, writable) { input = input.copy(weather = it) }
        NotificationSwitch("Task completed by someone else", input.completions, writable) { input = input.copy(completions = it) }
        Button(onClick = { act { onSave(input) } }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save notification settings") }
        Button(onClick = { act { onTest() } }, enabled = writable && state.devices.any { it.enabled }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Send test notification") }
        Text("Devices", style = MaterialTheme.typography.titleLarge)
        if (state.loaded && state.devices.isEmpty()) Text("No devices enabled.")
        for (device in state.devices) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.label)
                    if (device.current) Text("This device")
                    Text(if (device.transport == "fcm") "Android app" else "Browser")
                    NotificationSwitch("Enable ${device.label}", device.enabled, writable) { act { onSwitch(device) } }
                    OutlinedButton(onClick = { act { onRemove(device) } }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove ${device.label}") }
                }
            }
        }
    }
}
@Composable private fun NotificationSwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = SemanticsRole.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}
