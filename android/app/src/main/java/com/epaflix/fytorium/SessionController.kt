package com.epaflix.fytorium

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SessionState(
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val updateRequired: Boolean = false,
    // The server's minimum from /api/meta, for the update stop.
    val minAppVersion: String? = null,
)

internal fun loginError(code: String) = when (code) {
    "not_member" -> "This account isn't in a Fytorium household. Open the invite link from the owner, then log in again."
    "access_denied" -> "Login was cancelled."
    "server_error" -> "Fytorium couldn't finish the login. Try again."
    else -> "Login didn't work. Try again."
}

// Application-owned work and redirects never capture an Activity.
class SessionController(private val store: CredentialStore, private val api: ApiClient, private val diagnostics: Diagnostics) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = combine(store.tokensState, uiState) { tokens, ui ->
        ui.copy(loggedIn = tokens != null)
    }.stateIn(scope, SharingStarted.Eagerly, SessionState(loggedIn = store.tokens != null))
    private val queuedRedirects = ArrayDeque<String>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        work { checkVersionNow() }
    }

    fun loginUrl(): String = api.loginUrl()

    fun loginLaunchFailed() {
        store.pending = null
        diagnostics.record(ErrorEvent.LOGIN_REJECTED)
        uiState.update { it.copy(message = "Couldn't open login. Install a browser and try again.") }
    }

    fun copyDiagnostics(): String {
        uiState.update { it.copy(message = "Diagnostics copied") }
        return diagnostics.copyText()
    }

    fun handleRedirect(redirect: String) {
        if (store.tokens != null) {
            diagnostics.debug(DebugEvent.REDIRECT_IGNORED)
            return
        }
        started = true
        if (uiState.value.busy) {
            queuedRedirects.addLast(redirect)
            return
        }
        work {
            val error = withContext(Dispatchers.IO) { api.completeLogin(redirect) }
            if (error != null) {
                diagnostics.record(ErrorEvent.LOGIN_REJECTED)
                uiState.update { it.copy(message = loginError(error)) }
            }
            checkVersionNow()
        }
    }

    // The session ends on this phone even when Fytorium can't be told, so a failed request is no error; the stop goes with the session.
    fun logout() {
        uiState.update { it.copy(updateRequired = false) }
        work { withContext(Dispatchers.IO) { runCatching { api.logout() } } }
    }

    // A 426 from any request reads the minimum again, so the stop names the version the server wants now.
    private var recheckWaiting = false
    fun recheck() { if (uiState.value.busy) recheckWaiting = true else work { checkVersionNow() } }

    fun checkAgain(checkedAt: () -> String) = work(offline = "Checking needs a connection.") {
        checkVersionNow()
        if (uiState.value.updateRequired) uiState.update { it.copy(message = "Still needs ${it.minAppVersion ?: "a newer Fytorium"} or newer. Checked ${checkedAt()}.") }
    }

    private suspend fun checkVersionNow() {
        val answer = withContext(Dispatchers.IO) {
            api.request("/api/meta").use {
                val body = it.body?.string().orEmpty()
                val minimum = runCatching { org.json.JSONObject(body).optString("minAppVersion").takeIf { value -> value.isNotBlank() } }.getOrNull()
                if (it.code == 426) true to minimum
                else if (it.isSuccessful) requiresUpdate(it.code, body, BuildConfig.VERSION_NAME) to minimum
                else null
            }
        }
        if (answer != null) uiState.update { it.copy(updateRequired = answer.first, minAppVersion = answer.second ?: it.minAppVersion) }
    }

    private fun work(offline: String = "Can't reach Fytorium. Check the connection and try again.", action: suspend () -> Unit) {
        if (uiState.value.busy) return
        uiState.update { it.copy(busy = true, message = null) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                diagnostics.record(ErrorEvent.NETWORK_UNAVAILABLE)
                uiState.update { it.copy(message = offline) }
            } finally {
                uiState.update { it.copy(busy = false) }
                if (recheckWaiting) { recheckWaiting = false; recheck() }
                while (scope.isActive && !uiState.value.busy && queuedRedirects.isNotEmpty()) {
                    handleRedirect(queuedRedirects.removeFirst())
                }
            }
        }
    }

    internal fun close() { scope.cancel() }
}
