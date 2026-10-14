package com.epaflix.fytorium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private lateinit var session: SessionController
    private val homeRequest = androidx.compose.runtime.mutableIntStateOf(0)
    private fun notificationIntent(intent: Intent) {
        if (intent.action == com.epaflix.fytorium.notifications.PushMessages.OPEN_HOME) { homeRequest.intValue++; intent.action = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FytoriumApplication
        session = app.session
        app.push.refresh()
        notificationIntent(intent)
        val care = app.care
        setContent {
            val state by session.state.collectAsState()
            val careState by app.repository.state.collectAsState()
            FytoriumTheme {
                AppShell(state.loggedIn, state.updateRequired || careState.updateRequired, state.busy, state.message,
                    onLogin = { login() }, onLogout = session::logout,
                    careState = careState, controller = care, thumbnails = app.thumbnails, feed = app.feed,
                    homeRequest = homeRequest.intValue, sitesApi = app.api, sitesConnected = { app.store.tokens != null && care.connected() },
                    onCopyDiagnostics = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Fytorium diagnostics", session.copyDiagnostics()))
                    })
            }
        }
        if (savedInstanceState == null && intent.data != null) forwardRedirect(intent)
        session.start()
    }

    override fun onStart() {
        super.onStart()
        (application as FytoriumApplication).care.refresh()
        (application as FytoriumApplication).push.refresh()
    }

    private fun login() {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(session.loginUrl()))
        } catch (_: Exception) { session.loginLaunchFailed() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); notificationIntent(intent); forwardRedirect(intent) }

    private fun forwardRedirect(intent: Intent) {
        val redirect = intent.dataString ?: return
        intent.data = null
        session.handleRedirect(redirect)
    }
}
