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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.epaflix.fytorium.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun NotificationRoute(api: ApiClient, care: CareState, connected: () -> Boolean, onBack: () -> Unit,
    onReceipt: (String) -> Unit = {}, onUpdateRequired: () -> Unit = {}, answers: Int = 0, live: () -> CareState? = { null },
    cached: (String) -> Pair<String, Long>? = { null }, keep: (String, String) -> Long? = { _, _ -> null }) {
    val currentCare by rememberUpdatedState(care)
    val currentConnected by rememberUpdatedState(connected)
    val member = care.member
    // One repository per member for the process, so a reopened screen continues from a write still in flight (W5a-P30).
    // The app's live care state when the shell gives it, so work queued after this screen closes still checks the real member and update gate.
    val liveCare = { live() ?: currentCare }
    val allowed = { liveCare().let { it.member == member && member != null && !it.updateRequired } }
    // Bound to the member who started the read, so a late answer never lands in another member's cache.
    val readCache = { resource: String -> cached(resource).takeIf { liveCare().member == member } }
    val writeCache = { resource: String, json: String -> if (liveCare().member == member) keep(resource, json) else null }
    val repository = remember(api, member) { NotificationRepositories.of(member?.id) { NotificationRepository(api, allowed, { currentConnected() }, readCache, writeCache) }
        .also { it.bind(allowed, { currentConnected() }, readCache, writeCache) } }
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
    // Each 426 reaches the app-wide stop once, so a reopened screen never replays an old one.
    LaunchedEffect(state.updateRequired) { if (repository.takeUpdateRequired()) onUpdateRequired() }
    NotificationScreen(state, online = connected() && !care.updateRequired, sitter = member?.role == Role.sitter, care = care, onReceipt = onReceipt,
        permissionAsked = asked, notificationsEnabled = notificationsEnabled, onSystemSettings = { systemSettings() }, onEnable = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !asked) {
                // Persist before launching, so denial or process death never causes a repeated prompt.
                prefs.edit().putBoolean("asked", true).commit(); asked = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                systemSettings()
            }
        }, onBack = onBack, onRefresh = { scope.launch { withContext(Dispatchers.IO) { repository.refresh() } } },
        onUpdate = { change -> withContext(Dispatchers.IO) { repository.update(change) } },
        onSwitch = { device, enabled -> withContext(Dispatchers.IO) { repository.setDevice(device, enabled) } },
        onRemove = { withContext(Dispatchers.IO) { repository.remove(it) } },
        onTest = { withContext(Dispatchers.IO) { if (repository.test()) repository.state.value.sent else null } },
        failure = { repository.state.value.takeIf { !it.blocked }?.error })
    // W5a-P31: reload on open, on resume and on reconnect.
    val online = connected()
    LaunchedEffect(repository, online) { withContext(Dispatchers.IO) { repository.refresh() } }
    // W5a-P31: a read that failed loads again once Fytorium answers another request.
    LaunchedEffect(answers) { if (repository.state.value.blocked) withContext(Dispatchers.IO) { repository.refresh() } }
    var resumed by remember(repository) { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(repository) { if (resumed) scope.launch(Dispatchers.IO) { repository.refresh() }; resumed = true; onPauseOrDispose {} }
}

// W5a-P28: a name a person recognises for each device.
internal fun deviceName(device: PushDevice): String =
    if (device.transport == "fcm") device.label.takeIf { it != "Fytorium Android" } ?: "Android phone" else browserName(device.label)
internal fun browserName(agent: String): String {
    val browser = when { "Edg/" in agent -> "Edge"; "Firefox/" in agent || "FxiOS" in agent -> "Firefox"; "Chrome/" in agent || "CriOS" in agent -> "Chrome"; "Safari/" in agent -> "Safari"; else -> null }
    val system = when { "Android" in agent -> "Android"; "iPhone" in agent || "iPad" in agent -> "iOS"; "Windows" in agent -> "Windows"; "Macintosh" in agent || "Mac OS X" in agent -> "macOS"; "Linux" in agent -> "Linux"; else -> null }
    return when {
        browser != null && system != null -> "$browser on $system"
        browser != null || system != null -> browser ?: "Browser on $system"
        agent.length > 40 -> agent.take(40) + "\u2026"
        else -> agent
    }
}
internal fun lastNotified(at: String?): String = at?.let { runCatching {
    val day = java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    " \u00b7 Last notified " + if (day.year == java.time.LocalDate.now().year) dayMonth(day.toString()) else dayMonthYear(day.toString())
}.getOrNull() } ?: " \u00b7 Not notified yet"
internal fun clock(time: String, is24: Boolean): String {
    val parsed = java.time.LocalTime.parse(time)
    return parsed.format(java.time.format.DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", java.util.Locale.US))
}

// W5a-P22 to P29: a status card, what to send with switches that save on change, a test, and the devices.
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NotificationScreen(state: NotificationState, online: Boolean = true, sitter: Boolean = false, care: CareState? = null,
    permissionAsked: Boolean = false, notificationsEnabled: Boolean = true, onSystemSettings: () -> Unit = {}, onEnable: () -> Unit = {}, onBack: () -> Unit = {},
    onRefresh: () -> Unit = {}, onReceipt: (String) -> Unit = {},
    onUpdate: suspend ((NotificationSettings) -> NotificationSettings) -> Boolean = { false }, onSwitch: suspend (PushDevice, Boolean) -> Boolean = { _, _ -> false },
    onRemove: suspend (PushDevice) -> Boolean = { false }, onTest: suspend () -> Int? = { null }, failure: () -> String? = { null }) {
    val context = LocalContext.current
    val is24 = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val scope = rememberCoroutineScope()
    val pending = remember { Pending<Any>() }
    val enqueue = rememberWriteQueue()
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = state.settings.copy(time = pending["time"] as String? ?: state.settings.time, reminders = pending["reminders"] as Boolean? ?: state.settings.reminders,
        weather = pending["weather"] as Boolean? ?: state.settings.weather, completions = pending["completions"] as Boolean? ?: state.settings.completions)
    val canChange = online && state.loaded && !state.blocked && !state.updateRequired
    // W5a-P30: one queue in tap order; each control shows its own latest tap until that write ends.
    fun change(field: String, title: String, value: Any, edit: (NotificationSettings) -> NotificationSettings) {
        if (!canChange) { onReceipt("Changing settings needs a connection."); return }
        val ticket = pending.start(field, value)
        enqueue {
            val saved = onUpdate(edit)
            pending.end(field, ticket)
            failure()?.takeIf { !saved }?.let { onReceipt("$title wasn't saved. $it") }
        }
    }
    fun toggleDevice(device: PushDevice, enabled: Boolean) {
        if (!canChange) { onReceipt("Changing settings needs a connection."); return }
        val ticket = pending.start("device:" + device.id, enabled)
        enqueue {
            val changed = onSwitch(device, enabled)
            pending.end("device:" + device.id, ticket)
            // A refused change says so and reloads, so a device already gone elsewhere leaves the list.
            if (!changed) { onReceipt("${deviceName(device)} wasn't changed. ${(failure() ?: "Try again.").let { if (it.endsWith('.')) it else "$it." }}"); onRefresh() }
        }
    }
    val status = when {
        !online -> (care?.copy(lastUpdated = state.updatedAt)?.connectionLine(false) ?: "Offline") to "This phone can't reach Fytorium. You can still see the last settings. Changing settings needs a connection."
        state.blocked -> "Fytorium isn't responding" to (state.error ?: "Fytorium didn't answer. Try again when it responds.")
        else -> null
    }
    // Devices as the user last set them, so the card and the test follow a switch at once.
    fun enabled(device: PushDevice) = pending["device:" + device.id] as Boolean? ?: device.enabled
    val current = state.devices.firstOrNull { it.current }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { BarTitle(status?.first, status?.second.orEmpty(), retry = onRefresh.takeIf { status != null && online }) {
                Text("Notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) } },
            expandedHeight = barHeight(), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The card keeps one height in every state: its sentence has room for two lines.
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (sentence, button) = when {
                            !notificationsEnabled -> "Notifications are off for Fytorium on this phone." to "Turn on"
                            current != null && !enabled(current) -> "Fytorium isn't sending to this phone." to "Turn on"
                            else -> "Notifications are on for this phone." to "Android settings"
                        }
                        Text(sentence, minLines = 2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        OutlinedButton(onClick = {
                            when {
                                !notificationsEnabled -> if (permissionAsked) onSystemSettings() else onEnable()
                                current != null && !enabled(current) -> toggleDevice(current, true)
                                else -> onSystemSettings()
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(button) }
                    }
                }
                Text("Send me", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                SettingSwitch("Daily reminder", "What needs care today. Nothing on days with no tasks.", shown.reminders, canChange) {
                    change("reminders", "Daily reminder", it) { s -> s.copy(reminders = it) } }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = SemanticsRole.Button) {
                        if (canChange) pickingTime = true else onReceipt("Changing settings needs a connection.") }
                    .semantics { if (!canChange) stateDescription = "Changing settings needs a connection." },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Reminder time", Modifier.weight(1f))
                    Text(clock(shown.time, is24), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val phoneZone = java.time.ZoneId.systemDefault().id
                state.timezone?.takeIf { it != phoneZone }?.let { Text("Times use $it time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!sitter) SettingSwitch("Weather alerts", "Weather warnings for outdoor plants", shown.weather, canChange) {
                    change("weather", "Weather alerts", it) { s -> s.copy(weather = it) } }
                SettingSwitch("When someone else completes a task", "Care done by other members", shown.completions, canChange) {
                    change("completions", "When someone else completes a task", it) { s -> s.copy(completions = it) } }
                val anyOn = state.devices.any { enabled(it) }
                TextButton(onClick = {
                    when {
                        !anyOn -> onReceipt("Turn on a device below first.")
                        !canChange -> onReceipt("Changing settings needs a connection.")
                        else -> enqueue {
                            onTest()?.let { sent -> onReceipt(when {
                                sent == 0 -> "No device got the test. Try again later."
                                !notificationsEnabled -> "Test sent, but notifications are off for Fytorium on this phone"
                                else -> "Test sent to $sent ${if (sent == 1) "device" else "devices"}"
                            }) }
                        }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp).semantics { if (!anyOn) stateDescription = "Turn on a device below first." },
                    colors = if (anyOn) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))) {
                    Text("Send a test notification")
                }
                Text("Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                if (state.loaded && state.devices.isEmpty()) Text("No devices get notifications yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                (listOfNotNull(current) + state.devices.filter { !it.current }).forEach { device ->
                    val name = deviceName(device)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name)
                            Text((if (device.current) "This phone" else if (device.transport == "fcm") "Android app" else "Browser") + lastNotified(device.lastSuccessAt),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (device.transport != "fcm") IconButton(onClick = { if (canChange) removing = device.id else onReceipt("Changing settings needs a connection.") },
                            modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Delete, "Remove $name") }
                        Switch(enabled(device), onCheckedChange = { toggleDevice(device, it) },
                            modifier = Modifier.semantics { contentDescription = "Send notifications to $name"; if (!canChange) stateDescription = "Changing settings needs a connection." })
                    }
                }
            }
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (pickingTime) {
        val parts = shown.time.split(':')
        // One saved choice for the dial and the menus, so turning the phone keeps the time being picked.
        var hour by rememberSaveable { mutableIntStateOf(parts[0].toInt()) }
        var minute by rememberSaveable { mutableIntStateOf(parts[1].toInt()) }
        val time = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24)
        LaunchedEffect(time.hour, time.minute) { hour = time.hour; minute = time.minute }
        val short = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
        // A short window has room for neither the dial nor typed fields with their keyboard, so it offers two menus; minutes go in fives, plus the current one.
        val minutes = ((0..55 step 5).toList() + minute).distinct().sorted()
        AlertDialog(onDismissRequest = { pickingTime = false }, title = { DialogTitle("Reminder time") },
            text = { DialogBody { if (short) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeMenu("Hour", hour, (0..23).map { clock("%02d:00".format(java.util.Locale.ROOT, it), is24).let { label -> if (is24) label.substringBefore(':') else label.replace(":00", "") } },
                        Modifier.weight(1f)) { hour = it; time.hour = it }
                    TimeMenu("Minute", minutes.indexOf(minute), minutes.map { "%02d".format(java.util.Locale.ROOT, it) }, Modifier.weight(1f)) { minute = minutes[it]; time.minute = minutes[it] }
                } else TimePicker(time) } },
            confirmButton = { TextButton(onClick = {
                pickingTime = false
                val chosen = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
                change("time", "Reminder time", chosen) { s -> s.copy(time = chosen) }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set") } },
            dismissButton = { TextButton(onClick = { pickingTime = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
    state.devices.firstOrNull { it.id == removing }?.let { device ->
        val name = deviceName(device)
        AlertDialog(onDismissRequest = { removing = null }, title = { DialogTitle("Remove $name?") },
            text = { DialogBody { Text("It stops getting Fytorium notifications. To get them again, turn notifications on in that browser.") } },
            confirmButton = { TextButton(onClick = { removing = null; enqueue { if (onRemove(device)) onReceipt("Removed $name") } }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}
@Composable private fun TimeMenu(label: String, selected: Int, options: List<String>, modifier: Modifier, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The menu opens at the chosen value and marks it.
    val chosen = selected
    val scroll = rememberScrollState()
    val row = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(open) { if (open) scroll.scrollTo(selected * row) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$label: ${options[selected]}") }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 240.dp), scrollState = scroll) {
            options.forEachIndexed { index, option -> DropdownMenuItem(text = { Text(option) }, onClick = { choose(index); open = false },
                trailingIcon = if (index == selected) { { Icon(Icons.Default.Check, contentDescription = "Chosen") } } else null,
                modifier = Modifier.semantics { this.selected = index == chosen }) }
        }
    }
}
@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = SemanticsRole.Switch, onValueChange = change)
        .semantics { if (!enabled) stateDescription = "Changing settings needs a connection." },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { Text(title); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
