package com.epaflix.fytorium

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SessionState(
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val updateRequired: Boolean = false,
)

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
            val success = withContext(Dispatchers.IO) { api.completeLogin(redirect) }
            if (!success) {
                diagnostics.record(ErrorEvent.LOGIN_REJECTED)
                uiState.update { it.copy(message = "Login failed. Please try again.") }
            }
            checkVersionNow()
        }
    }

    fun logout() { work { withContext(Dispatchers.IO) { api.logout() } } }

    private suspend fun checkVersionNow() {
        val required = withContext(Dispatchers.IO) {
            api.request("/api/meta").use {
                if (it.code == 426) true
                else if (it.isSuccessful) requiresUpdate(it.code, it.body?.string().orEmpty(), BuildConfig.VERSION_NAME)
                else null
            }
        }
        if (required != null) uiState.update { it.copy(updateRequired = required) }
    }

    private fun work(action: suspend () -> Unit) {
        if (uiState.value.busy) return
        uiState.update { it.copy(busy = true, message = null) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                diagnostics.record(ErrorEvent.NETWORK_UNAVAILABLE)
                uiState.update { it.copy(message = "Can't reach Fytorium. Please try again.") }
            } finally {
                uiState.update { it.copy(busy = false) }
                while (scope.isActive && !uiState.value.busy && queuedRedirects.isNotEmpty()) {
                    handleRedirect(queuedRedirects.removeFirst())
                }
            }
        }
    }

    internal fun close() { scope.cancel() }
}
