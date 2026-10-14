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
data class PushDevice(val id: String, val label: String, val transport: String, val enabled: Boolean, val current: Boolean = false)
data class NotificationState(val settings: NotificationSettings = NotificationSettings(), val devices: List<PushDevice> = emptyList(),
    val timezone: String? = null, val loaded: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val blocked: Boolean = false, val updateRequired: Boolean = false, val error: String? = null, val message: String? = null)
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
        PushDevice(row.getString("id"), row.getString("label"), row.getString("transport"), row.getBoolean("enabled"), row.optBoolean("current_device"))
    } }
}

class NotificationRepository(private val api: ApiClient, private val allowed: () -> Boolean, private val connected: () -> Boolean) {
    private val mutable = MutableStateFlow(NotificationState())
    val state = mutable.asStateFlow()
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String =
        api.request(if (path.startsWith("/")) path else "/api/notifications/$path", method = method, body = body).use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw NotificationFailure(it.code,
                runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { message -> message in validationMessages })
            text
        }
    @Synchronized fun refresh() = run(false) {
        val preferences = settings(request("settings"))
        val rows = devices(request("subscriptions"))
        val timezone = JSONObject(request("/api/meta")).getString("timezone")
        if (allowed()) mutable.value = state.value.copy(settings = preferences, devices = rows, timezone = timezone, loaded = true, blocked = false)
    }
    @Synchronized fun save(value: NotificationSettings) = run(true) {
        val saved = settings(request("settings", "PUT", value.json()))
        if (allowed()) mutable.value = state.value.copy(settings = saved, message = "Notification settings saved.")
    }
    @Synchronized fun switch(device: PushDevice) = run(true) {
        require(device.id.matches(Regex("[a-fA-F0-9-]{36}")))
        request("subscriptions/${device.id}", "PATCH", JSONObject().put("enabled", !device.enabled))
        reloadDevices()
    }
    @Synchronized fun remove(device: PushDevice) = run(true) {
        require(device.id.matches(Regex("[a-fA-F0-9-]{36}")))
        request("subscriptions/${device.id}", "DELETE")
        reloadDevices()
        if (allowed()) mutable.value = state.value.copy(message = "Device removed.")
    }
    private fun reloadDevices() {
        val rows = devices(request("subscriptions"))
        if (allowed()) mutable.value = state.value.copy(devices = rows)
    }
    @Synchronized fun test() = run(true) {
        val sent = JSONObject(request("test", "POST")).getInt("sent")
        if (allowed()) mutable.value = state.value.copy(message = if (sent > 0) "Test notification sent." else "No device received the test. Enable notifications or try again later.")
    }
    private fun run(write: Boolean, action: () -> Unit): Boolean {
        if (!allowed()) { mutable.value = NotificationState(); return false }
        if (write && (!state.value.loaded || state.value.blocked || state.value.updateRequired || state.value.saving)) return false
        mutable.value = state.value.copy(loading = !write, saving = write, error = null, message = null)
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
