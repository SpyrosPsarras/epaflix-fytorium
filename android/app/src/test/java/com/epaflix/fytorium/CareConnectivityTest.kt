package com.epaflix.fytorium

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CareConnectivityTest {
    @Test fun anUnblockOrARefreshRereadsTheConnectionSoAMissedCallbackLeavesNoFalseOffline() {
        val server = MockWebServer().apply { start() }
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork!!
        fun validated(on: Boolean) = shadowOf(manager).setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance()
            .also { if (on) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) })
        validated(true)
        val db = Room.inMemoryDatabaseBuilder(context, CareDatabase::class.java).allowMainThreadQueries().build()
        val store = MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }
        val api = ApiClient(server.url("").toString().trimEnd('/'), store) {}
        val repository = Repository(api, CareCache(db), { true })
        val controller = CareController(context, store, repository, api)
        try {
            val callback = shadowOf(manager).networkCallbacks.single()
            fun blocked() { validated(false); callback.onCapabilitiesChanged(network, manager.getNetworkCapabilities(network)!!) }
            assertTrue(controller.online.value)
            blocked()
            assertFalse(controller.online.value)
            validated(true); callback.onBlockedStatusChanged(network, false)
            assertTrue("An unblock left a false Offline", controller.online.value)
            blocked()
            validated(true); controller.refresh()
            assertTrue("A refresh left a false Offline", controller.online.value)
            assertTrue("A refresh left Plants offline", repository.plantState.value.online)
        } finally { controller.close(); server.shutdown(); db.close() }
    }
}
