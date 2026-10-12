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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = (application as FytoriumApplication).session
        setContent {
            val state by session.state.collectAsState()
            FytoriumTheme {
                AppShell(state.loggedIn, state.updateRequired, state.busy, state.message,
                    onLogin = { login() }, onLogout = session::logout,
                    onCopyDiagnostics = {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Fytorium diagnostics", session.copyDiagnostics()))
                    })
            }
        }
        if (savedInstanceState == null && intent.data != null) forwardRedirect(intent)
        session.start()
    }

    private fun login() {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(session.loginUrl()))
        } catch (_: Exception) { session.loginLaunchFailed() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); forwardRedirect(intent) }

    private fun forwardRedirect(intent: Intent) {
        val redirect = intent.dataString ?: return
        intent.data = null
        session.handleRedirect(redirect)
    }
}
