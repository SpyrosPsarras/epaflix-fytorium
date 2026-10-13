package com.epaflix.fytorium

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchesAndShowsLogin() {
        compose.onNodeWithText("Fytorium").assertIsDisplayed()
        compose.onNodeWithText("Log in").assertIsDisplayed()
    }
    @Test fun recreationKeepsProcessClientAndStore() {
        val app = compose.activity.application as FytoriumApplication
        val client = app.api
        val store = app.store
        compose.activityRule.scenario.recreate()
        assertSame(client, (compose.activity.application as FytoriumApplication).api)
        assertSame(store, (compose.activity.application as FytoriumApplication).store)
    }
    @Test fun debugCleartextIsRestrictedToExactE2eHosts() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        assertTrue(policy.isCleartextTrafficPermitted("app"))
        assertTrue(policy.isCleartextTrafficPermitted("oidc"))
        for (host in listOf("app.evil", "sub.app", "sub.oidc", "example.com", "fytorium.epaflix.com", "127.0.0.1")) {
            assertFalse(host, policy.isCleartextTrafficPermitted(host))
        }
    }
    @Test fun emulatorReachesStackAndOidcProvider() {
        for (url in listOf("http://app:3000/healthz", "http://oidc:8080/default/.well-known/openid-configuration")) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
        }
    }
    @Test fun credentialsRoundTripWithoutPlaintextAndClearOnLogout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context)
        store.clear()
        store.tokens = Tokens("synthetic-e2e-access", "synthetic-e2e-refresh")
        store.pending = Pkce.create()
        assertEquals("synthetic-e2e-access", KeystoreCredentialStore(context).tokens!!.access)
        val raw = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("synthetic-e2e-access"))
        assertFalse(raw.contains(store.pending!!.verifier))
        store.clear()
        assertNull(store.tokens); assertNull(store.pending)
    }
    // Enable the real stack login with E2E_LOGIN=1 ./scripts/android e2e.
    @Test fun chosenOidcSubjectSeesEmptyHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("login") == "true")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(30_000) { compose.onNodeWithText("Log in").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText("Log in").performClick()
        assertTrue("Local OIDC page did not load in Chrome", device.wait(Until.hasObject(By.pkg("com.android.chrome").text("Mock OAuth2 Server Sign-in")), 30_000))
        device.waitForIdle()
        val subject = device.wait(Until.findObject(By.pkg("com.android.chrome").clazz("android.widget.EditText")), 30_000)
        assertNotNull("OIDC subject input did not appear in the Custom Tab", subject)
        subject.text = "synthetic-a03-owner"
        assertTrue("OIDC subject was not entered", device.wait(Until.hasObject(By.text("synthetic-a03-owner")), 10_000))
        val keyboard = By.pkg("com.google.android.inputmethod.latin")
        if (device.wait(Until.hasObject(keyboard), 2_000)) {
            device.pressBack()
            assertTrue("Keyboard did not close", device.wait(Until.gone(keyboard), 5_000))
        }
        // Swipe the page gutter, not the claims textarea, without pulling to refresh.
        var signIn = device.findObject(By.text("Sign-in"))
        for (attempt in 1..8) {
            if (signIn != null) break
            device.swipe(device.displayWidth * 95 / 100, device.displayHeight * 3 / 4,
                device.displayWidth * 95 / 100, device.displayHeight / 2, 80)
            signIn = device.wait(Until.findObject(By.text("Sign-in")), 2_000)
        }
        assertNotNull("OIDC Sign-in button missing", signIn)
        device.waitForIdle()
        signIn.click()
        device.wait(Until.findObject(By.textContains("Open")), 2_000)?.click()
        val returned = device.wait(Until.hasObject(By.pkg("com.epaflix.fytorium")), 30_000)
        assertTrue("OIDC redirect did not return to Fytorium", returned)
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("No tasks yet.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No tasks yet.").assertIsDisplayed()
        compose.onNodeWithText("Plants").assertIsDisplayed()
    }
}
