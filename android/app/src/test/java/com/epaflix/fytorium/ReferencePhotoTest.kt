package com.epaflix.fytorium

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReferencePhotoTest {
    @get:Rule val compose = createComposeRule()
    @Test fun referenceLoadsThroughAuthenticatedApiAndShowsCredit() {
        MockWebServer().use { server ->
            server.start()
            val image = java.io.ByteArrayOutputStream().apply {
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, this)
            }.toByteArray()
            server.enqueue(MockResponse().setBody("""{"photo":{"creator":"Synthetic photographer","license":"CC BY 4.0","source_url":"https://example.org/observation"},"common_name":"Synthetic lantern"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(image)))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { ReferencePhoto(api, 910701, "Testoria lucida", showName = true) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Reference photo of Testoria lucida").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("CC BY 4.0 · Photo: Synthetic photographer").assertIsDisplayed()
            compose.onNodeWithText("Synthetic lantern").assertIsDisplayed()
            compose.onNodeWithText("Testoria lucida").assertIsDisplayed()
            for (path in listOf("/api/species/910701/photo/credit", "/api/species/910701/photo")) {
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            }
        }
    }
    @Test @Config(qualifiers = "w360dp-h800dp-mdpi") fun longCreatorKeepsLicenseAndEntireCreditVisibleAtLargerFontScale() {
        MockWebServer().use { server ->
            server.start()
            val creator = "Synthetic photographer ".repeat(14).take(300)
            server.enqueue(MockResponse().setBody("""{"photo":{"creator":"$creator","license":"CC BY 4.0","source_url":"https://example.org/observation"},"common_name":null}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                    FytoriumTheme { ReferencePhoto(api, 910701, "Testoria lucida") }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("CC BY 4.0", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val credit = compose.onNodeWithText("CC BY 4.0", substring = true)
            credit.assertIsDisplayed().assertTextEquals("CC BY 4.0 · Photo: $creator")
            val bounds = credit.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            val layouts = mutableListOf<TextLayoutResult>()
            credit.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertFalse(layouts.single().hasVisualOverflow)
        }
    }
    @Test fun failedCoverShowsPlaceholder() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404))
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            compose.setContent { FytoriumTheme { AuthenticatedThumbnail(api, "/api/import/planta/synthetic/cover", "Planta photo of synthetic fern", "No Planta photo") } }
            compose.waitUntil(5_000) { server.requestCount == 1 }
            compose.onNodeWithText("No Planta photo").assertIsDisplayed()
            compose.onNodeWithContentDescription("Planta photo of synthetic fern").assertDoesNotExist()
        }
    }
}
