package com.epaflix.fytorium.notifications

import com.epaflix.fytorium.ApiClient
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class NotificationSettings(val time: String = "09:00", val reminders: Boolean = false, val weather: Boolean = false, val completions: Boolean = false) {
    fun json() = JSONObject().put("reminder_time", time).put("reminders_enabled", reminders)
        .put("weather_alerts_enabled", weather).put("completion_notices_enabled", completions)
}
data class PushDevice(val id: String, val label: String, val transport: String, val enabled: Boolean, val current: Boolean = false, val lastSuccessAt: String? = null)
data class NotificationState(val settings: NotificationSettings = NotificationSettings(), val devices: List<PushDevice> = emptyList(),
    val timezone: String? = null, val loaded: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val blocked: Boolean = false, val updateRequired: Boolean = false, val error: String? = null, val message: String? = null,
    // How many devices the last test reached, and when the shown settings were read (for the offline line).
    val sent: Int? = null, val updatedAt: Long? = null)
private class NotificationFailure(val status: Int, val display: String?) : Exception()
private val validationMessages = setOf("Choose a reminder time HH:MM and on/off switches", "Choose an on/off completion notice switch", "Choose an on/off switch", "Device not found")
private fun settings(text: String) = JSONObject(text).let {
    NotificationSettings(it.getString("reminder_time"), it.getBoolean("reminders_enabled"), it.getBoolean("weather_alerts_enabled"), it.getBoolean("completion_notices_enabled"))
}
fun registerPushToken(api: ApiClient, token: String) {
    api.request("/api/notifications/subscriptions", method = "POST", body = JSONObject().put("transport", "fcm").put("token", token)).use {
        if (!it.isSuccessful) throw IOException("Notification registration unavailable")
    }
}

private fun devices(text: String): List<PushDevice> {
    val array = JSONArray(text)
    return (0 until array.length()).map { array.getJSONObject(it).let { row ->
        PushDevice(row.getString("id"), row.getString("label"), row.getString("transport"), row.getBoolean("enabled"), row.optBoolean("current_device"),
            if (row.isNull("last_success_at")) null else row.optString("last_success_at").takeIf { it.isNotBlank() })
    } }
}

// W5a-P32: the last settings, devices and time zone stay in the member's cache for offline reading.
class NotificationRepository(private val api: ApiClient, private val allowed: () -> Boolean, private val connected: () -> Boolean,
    private val cached: (String) -> Pair<String, Long>? = { null }, private val keep: (String, String) -> Long? = { _, _ -> null }) {
    private val mutable = MutableStateFlow(NotificationState())
    val state = mutable.asStateFlow()
    // What the server last confirmed, kept in the cache after every read and confirmed write, before any follow-up read.
    private fun persist() {
        if (!allowed() || !state.value.loaded) return
        val devices = JSONArray(state.value.devices.map { JSONObject().put("id", it.id).put("label", it.label).put("transport", it.transport)
            .put("enabled", it.enabled).put("current_device", it.current).put("last_success_at", it.lastSuccessAt ?: JSONObject.NULL) })
        val at = keep("notifications", JSONObject().put("settings", state.value.settings.json().toString()).put("subscriptions", devices.toString())
            .put("timezone", state.value.timezone ?: "").toString())
        if (allowed()) mutable.value = state.value.copy(updatedAt = at ?: state.value.updatedAt)
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String =
        api.request(if (path.startsWith("/")) path else "/api/notifications/$path", method = method, body = body).use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw NotificationFailure(it.code,
                runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { message -> message in validationMessages })
            text
        }
    @Synchronized fun refresh(): Boolean {
        if (!state.value.loaded && allowed()) cached("notifications")?.let { (json, at) -> runCatching {
            val j = JSONObject(json)
            mutable.value = state.value.copy(settings = settings(j.getString("settings")), devices = devices(j.getString("subscriptions")),
                timezone = j.optString("timezone").takeIf { it.isNotBlank() }, loaded = true, updatedAt = at)
        } }
        return run(false) {
            val preferences = request("settings")
            val rows = request("subscriptions")
            val timezone = JSONObject(request("/api/meta")).getString("timezone")
            if (allowed()) mutable.value = state.value.copy(settings = settings(preferences), devices = devices(rows), timezone = timezone, loaded = true, blocked = false,
                updatedAt = System.currentTimeMillis())
            persist()
        }
    }
    @Synchronized fun save(value: NotificationSettings) = run(true) {
        val text = request("settings", "PUT", value.json())
        if (allowed()) mutable.value = state.value.copy(settings = settings(text))
        persist()
    }
    // W5a-P30: each change applies to the settings as they are when it starts, so the last tap wins.
    @Synchronized fun update(change: (NotificationSettings) -> NotificationSettings) = save(change(state.value.settings))
    @Synchronized fun switch(device: PushDevice) = setDevice(device, !device.enabled)
    // The wanted value, not a flip of a row that may be stale, so two quick taps never send the same change twice.
    @Synchronized fun setDevice(device: PushDevice, enabled: Boolean) = run(true) {
        require(device.id.matches(Regex("[a-fA-F0-9-]{36}")))
        request("subscriptions/${device.id}", "PATCH", JSONObject().put("enabled", enabled))
        if (allowed()) mutable.value = state.value.copy(devices = state.value.devices.map { if (it.id == device.id) it.copy(enabled = enabled) else it })
        persist()
        reloadDevices()
    }
    @Synchronized fun remove(device: PushDevice) = run(true) {
        require(device.id.matches(Regex("[a-fA-F0-9-]{36}")))
        request("subscriptions/${device.id}", "DELETE")
        if (allowed()) mutable.value = state.value.copy(devices = state.value.devices.filter { it.id != device.id })
        persist()
        reloadDevices()
    }
    private fun reloadDevices() {
        val text = request("subscriptions")
        if (allowed()) mutable.value = state.value.copy(devices = devices(text))
        persist()
    }
    @Synchronized fun test() = run(true) {
        val sent = JSONObject(request("test", "POST")).getInt("sent")
        if (allowed()) mutable.value = state.value.copy(sent = sent)
    }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = NotificationState(); return false }
        if (write && (!state.value.loaded || state.value.blocked || state.value.updateRequired || state.value.saving)) return false
        mutable.value = state.value.copy(loading = !write, saving = write, error = null, message = null, sent = null)
        return try {
            if (!connected()) throw IOException()
            action(); allowed()
        } catch (error: Exception) {
            if (allowed()) mutable.value = state.value.copy(blocked = error !is NotificationFailure || error.status >= 500 || error.status in listOf(401, 403, 426),
                updateRequired = state.value.updateRequired || error is NotificationFailure && error.status == 426,
                error = (error as? NotificationFailure)?.display ?: "Notifications unavailable. Reconnect and refresh to retry.")
            false
        } finally { mutable.value = if (allowed()) state.value.copy(loading = false, saving = false) else NotificationState() }
    }
}
