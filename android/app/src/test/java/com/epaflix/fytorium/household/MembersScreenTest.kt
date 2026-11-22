package com.epaflix.fytorium.household

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import com.epaflix.fytorium.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class MembersScreenTest {
    @get:Rule val compose = createComposeRule()
    @After fun forgetLinks() = InviteLinks.clear()
    private fun plant(id: String, name: String, site: String) = Plant(id, name, "Testoria lucida", "active", site, null, null, null, null, "{}")
    private val fern = plant("plant-one", "Synthetic fern", "Synthetic bathroom")
    private val twin = plant("plant-two", "Synthetic fern", "Synthetic office")
    private val owner = HouseholdMember("member-owner", "Synthetic owner", "owner", emptySet(), emptyList(), false)
    private val sitter = HouseholdMember("member-one", "Synthetic sitter", "sitter", setOf(fern.id), listOf("family", "sitter"), true)
    private val expires = "2030-06-08T10:00:00Z"
    private val care = CareState(member = Member("member-owner", Role.owner), plants = listOf(fern, twin))
    private val loaded = HouseholdState(loaded = true, members = listOf(owner, sitter), invites = listOf(HouseholdInvite("invite-one", "sitter", expires, setOf(fern.id))))
    private fun created(role: String) = CreatedInvite("invite-new", role, expires, "/invite/synthetic-link")

    // W5b-P9: people and invites read as sentences, with no Refresh button.
    @Test fun peopleAndInvitesReadAsSentences() {
        compose.setContent { FytoriumTheme { MembersScreen(loaded, care) } }
        compose.onNodeWithText("Synthetic owner (you)").assertIsDisplayed()
        compose.onNodeWithText("Owner").assertIsDisplayed()
        compose.onNodeWithText("Sitter · 1 plant").assertIsDisplayed()
        compose.onNodeWithText("Sitter invite").assertIsDisplayed()
        compose.onNodeWithText("Synthetic fern (Synthetic bathroom) · Expires ${expiryDate(expires)}").assertIsDisplayed()
        compose.onNodeWithText("Refresh members").assertDoesNotExist()
        // Only rows the server lets you change open a page.
        compose.onNode(hasText("Synthetic owner (you)") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Synthetic sitter") and hasClickAction()).assertExists()
        assertEquals("Spider Plant, Thyme and 2 more", shortList(listOf("Spider Plant", "Thyme", "Fern", "Aloe")))
    }

    // W5b-P10: no role is chosen at first; the picker names each plant's site, so twins stay apart.
    @Test fun anInviteStartsWithoutARoleAndThePickerNamesSites() {
        val receipts = mutableListOf<String>()
        var sent: Pair<String, Set<String>>? = null
        compose.setContent { FytoriumTheme { MembersScreen(loaded, care, onReceipt = { receipts += it }, onCreateInvite = { role, plants -> sent = role to plants; created(role) }) } }
        compose.onNodeWithText("Invite someone").performClick()
        compose.onNode(hasText("Family", substring = true) and isSelectable()).assertIsNotSelected()
        compose.onNode(hasText("Sitter", substring = true) and isSelectable()).assertIsNotSelected()
        // Sol W5b-sol-3: it looks off until a role is chosen.
        compose.onNodeWithText("Create invite link").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Choose Family or Sitter first."), receipts); assertNull(sent) }
        compose.onNode(hasText("Sitter", substring = true) and isSelectable()).performClick()
        compose.onNodeWithText("No plants chosen. You can share plants later from Members.").assertIsDisplayed()
        compose.onNode(hasText("Synthetic fern") and hasText("Synthetic office") and isToggleable()).performScrollTo().performClick().assertIsOn()
        compose.onNode(hasText("Synthetic fern") and hasText("Synthetic bathroom") and isToggleable()).assertIsOff()
        compose.onNodeWithText("Create invite link").performScrollTo().performClick()
        compose.waitUntil(5_000) { sent != null }
        assertEquals("sitter" to setOf("plant-two"), sent)
        compose.onNodeWithText("Send this link to the person you invite. It works once and expires on ${expiryDate(expires)}. You can't see it again after you leave this page.").assertIsDisplayed()
    }

    // W5b-P11: the link lives only in process memory; after process death the page says so and can revoke and create a new one.
    @Test fun theLinkLivesOnlyInMemoryAndAskBeforeLeavingUnsent() {
        val restoration = StateRestorationTester(compose)
        var shared: String? = null
        val revoked = mutableListOf<String>()
        var creates = 0
        restoration.setContent { FytoriumTheme { MembersScreen(loaded, care, onShare = { shared = it },
            onCreateInvite = { role, _ -> creates++; created(role).copy(id = "invite-$creates", link = "/invite/synthetic-link-$creates") },
            onRevoke = { revoked += it; true }) } }
        compose.onNodeWithText("Invite someone").performClick()
        compose.onNode(hasText("Family", substring = true) and isSelectable()).performClick()
        compose.onNodeWithText("Create invite link").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Share link").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Leave without sending the link?").assertIsDisplayed()
        compose.onNodeWithText("You can't see this link again. The invite still works until ${expiryDate(expires)} unless you revoke it under Pending invites.").assertIsDisplayed()
        compose.onNodeWithText("Stay").performClick()
        // Process death: the saved page comes back without its link.
        InviteLinks.clear()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("This link can't be shown again").assertIsDisplayed()
        compose.onNodeWithText("Revoke and create new").performClick()
        compose.waitUntil(5_000) { creates == 2 }
        compose.runOnIdle { assertEquals(listOf("invite-1"), revoked) }
        compose.onNodeWithText("Share link").performClick()
        compose.runOnIdle { assertEquals("/invite/synthetic-link-2", shared) }
        // Once sent, leaving asks nothing and forgets the link.
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Leave without sending the link?").assertDoesNotExist()
        compose.onNodeWithText("Invite someone").assertIsDisplayed()
        assertNull(InviteLinks.get("invite-2"))
    }

    // Sol W5b-S5: when the old invite was revoked but the new one failed, a retry only creates.
    @Test fun revokeAndCreateNewRetriesOnlyTheCreate() {
        val restoration = StateRestorationTester(compose)
        var revokes = 0
        var creates = 0
        restoration.setContent { FytoriumTheme { MembersScreen(loaded, care, onRevoke = { revokes++; true },
            onCreateInvite = { role, _ -> creates++; if (creates == 2) null else created(role).copy(id = "invite-$creates") }) } }
        compose.onNodeWithText("Invite someone").performClick()
        compose.onNode(hasText("Family", substring = true) and isSelectable()).performClick()
        compose.onNodeWithText("Create invite link").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        InviteLinks.clear()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Revoke and create new").performClick()
        compose.waitUntil(5_000) { creates == 2 }
        compose.onNodeWithText("Revoke and create new").performClick()
        compose.waitUntil(5_000) { creates == 3 }
        compose.runOnIdle { assertEquals(1, revokes) }
        compose.onNodeWithText("Share link").assertIsDisplayed()
    }
    // Sol W5b-S4: a tab tap away from Members forgets any link shown there.
    @Test fun aTabTapForgetsTheInviteLink() {
        compose.setContent { FytoriumTheme { AppShell(true, false, careState = care) } }
        InviteLinks.put("invite-shown", "/invite/synthetic-link")
        compose.onNode(isTab("Today")).performClick()
        assertNull(InviteLinks.get("invite-shown"))
    }

    // Astra W5b-astra-3: invites for two plants with one name say which site each is on, also when revoking.
    @Test fun twinPlantsInInvitesNameTheirSite() {
        val state = loaded.copy(invites = listOf(HouseholdInvite("invite-a", "sitter", expires, setOf(fern.id)), HouseholdInvite("invite-b", "sitter", expires, setOf(twin.id))))
        compose.setContent { FytoriumTheme { MembersScreen(state, care) } }
        compose.onNodeWithText("Synthetic fern (Synthetic bathroom) · Expires ${expiryDate(expires)}").assertExists()
        compose.onNodeWithText("Synthetic fern (Synthetic office) · Expires ${expiryDate(expires)}").assertExists()
        compose.onAllNodesWithText("Revoke")[1].performScrollTo().performClick()
        compose.onNodeWithText("The sitter invite for Synthetic fern (Synthetic office) stops working. It would have expired on ${expiryDate(expires)}.").assertIsDisplayed()
    }
    // Astra W5b-astra-4: a leave question open at process death does not come back without its link.
    @Test fun aLeaveQuestionDoesNotOutliveItsLink() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FytoriumTheme { MembersScreen(loaded, care, onCreateInvite = { role, _ -> created(role) }) } }
        compose.onNodeWithText("Invite someone").performClick()
        compose.onNode(hasText("Family", substring = true) and isSelectable()).performClick()
        compose.onNodeWithText("Create invite link").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Leave without sending the link?").assertIsDisplayed()
        InviteLinks.clear()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("This link can't be shown again").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    // Opus W5b-opus-2: ticking the first plant moves no row; Opus W5b-opus-7: the expiry date stays on one line.
    @Test fun tickingTheFirstPlantMovesNoRow() {
        compose.setContent { FytoriumTheme { MembersScreen(loaded, care) } }
        compose.onNodeWithText("Invite someone").performClick()
        compose.onNode(hasText("Sitter", substring = true) and isSelectable()).performClick()
        val twinRow = compose.onNode(hasText("Synthetic fern") and hasText("Synthetic office") and isToggleable())
        val before = twinRow.performScrollTo().fetchSemanticsNode().boundsInRoot
        compose.onNode(hasText("Synthetic fern") and hasText("Synthetic bathroom") and isToggleable()).performClick()
        assertEquals(before, twinRow.fetchSemanticsNode().boundsInRoot)
        assertFalse(expiryDate(expires).contains(' '))
    }
    // Opus W5b-opus-3: Back from a member page returns to the same place in a long list.
    @Test fun backFromAMemberKeepsTheListPlace() {
        val people = listOf(owner) + List(12) { sitter.copy(id = "member-$it", name = "Synthetic sitter %02d".format(it)) }
        compose.setContent { FytoriumTheme { MembersScreen(loaded.copy(members = people), care) } }
        val row = compose.onNodeWithText("Synthetic sitter 11").performScrollTo()
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(before, compose.onNodeWithText("Synthetic sitter 11").fetchSemanticsNode().boundsInRoot)
    }

    // W5b-P12 to P14: saves, removals and revokes name who or what changed in a receipt.
    @Test fun changesNameTheirTargetInAReceipt() {
        val receipts = mutableListOf<String>()
        var saved: Triple<String, String, Set<String>>? = null
        compose.setContent { FytoriumTheme { MembersScreen(loaded, care, onReceipt = { receipts += it },
            onMember = { m, r, p -> saved = Triple(m.id, r, p); true }, onRemove = { true }, onRevoke = { true }) } }
        compose.onNodeWithText("Synthetic sitter").performClick()
        compose.onNode(hasText("Synthetic fern") and hasText("Synthetic office") and isToggleable()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.assertDiscard("Your changes to Synthetic sitter are not saved yet.")
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNode(hasText("Family", substring = true) and isSelectable()).performClick()
        compose.onNodeWithText("Family members see every plant, so their shares are cleared.").assertIsDisplayed()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals(Triple("member-one", "family", emptySet<String>()), saved)
        compose.onNodeWithText("Synthetic sitter").performClick()
        compose.onNodeWithText("Remove from household").performScrollTo().performClick()
        compose.onNodeWithText("Remove member?").assertIsDisplayed()
        compose.onNodeWithText("Synthetic sitter is logged out on every device and loses access to this household. Their name stays in care history.").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { receipts.size == 2 }
        compose.onNodeWithText("Revoke").performScrollTo().performClick()
        compose.onNodeWithText("The sitter invite for Synthetic fern (Synthetic bathroom) stops working. It would have expired on ${expiryDate(expires)}.").assertIsDisplayed()
        compose.onNode(hasText("Revoke") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { receipts.size == 3 }
        assertEquals(listOf("Saved changes for Synthetic sitter", "Removed Synthetic sitter", "Revoked the sitter invite"), receipts)
    }

    // W5b-P27: the last members and invites read offline; writes explain.
    @Test fun offlineMembersReadTheLastCopyAndWritesExplain() {
        val cache = mutableMapOf<String, Pair<String, Long>>()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody(when (request.path) {
                    "/api/household/members" -> """[{"id":"member-owner","display_name":"Synthetic owner","role":"owner","plant_ids":[],"role_options":[],"can_remove":false}]"""
                    else -> "[]"
                })
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val online = mutableStateOf(true)
            val shown = mutableStateOf(true)
            val receipts = mutableListOf<String>()
            compose.setContent { FytoriumTheme { if (shown.value) MembersRoute(api, care, { online.value }, {}, onReceipt = { receipts += it },
                cached = { cache[it] }, keep = { key, json -> 1_000L.also { cache[key] = json to it } }) } }
            compose.waitUntil(5_000) { "members" in cache }
            compose.runOnIdle { shown.value = false; online.value = false }
            compose.runOnIdle { shown.value = true }
            // The offline copy is read off the main thread, so wait for it.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic owner (you)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Synthetic owner (you)").assertIsDisplayed()
            compose.onNode(hasText("Offline", substring = true) and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("Invite someone").assert(hasStateDescription(membersOffline)).assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals(listOf(membersOffline), receipts) }
        }
    }

    @Test fun lightScreens() = screens(false)
    @Test fun darkScreens() = screens(true)
    private fun screens(dark: Boolean) {
        compose.enableAccessibilityChecks()
        var share: String? = null
        compose.setContent { FytoriumTheme(dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MembersScreen(loaded, care, onCreateInvite = { role, _ -> created(role) }, onShare = { share = it }, onRemove = { true })
        } } }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(1000)
            compose.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            val file = "src/test/screenshots/a09-$name-${if (dark) "dark" else "light"}.png"
            if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.captureDialog(file) else compose.onRoot().captureRoboImage(file)
        }
        capture("members")
        compose.onNodeWithText("Invite someone").performClick()
        compose.onNode(hasText("Sitter", substring = true) and isSelectable()).performClick()
        capture("invite-create")
        compose.onNode(hasText("Synthetic fern") and hasText("Synthetic bathroom") and isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Create invite link").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        capture("invite-link")
        compose.onNodeWithText("Share link").performClick()
        compose.runOnIdle { assertEquals("/invite/synthetic-link", share) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Synthetic sitter").performClick()
        capture("shares")
        compose.onNodeWithText("Remove from household").performScrollTo().performClick()
        capture("remove")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Revoke").performScrollTo().performClick()
        capture("revoke")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun membersIgnoresCareBusyOfflineAndServerUnavailable() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody("[]")
            }
            server.start()
            val api = ApiClient(server.url("").toString().trimEnd('/'), MemoryCredentials().apply { tokens = Tokens("synthetic-access", "synthetic-refresh") }) {}
            val busy = CareState(member = Member("synthetic", Role.owner), busy = true, offline = true, serverUnavailable = true)
            compose.setContent { FytoriumTheme { MembersRoute(api, busy, { true }, {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No pending invites.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Invite someone").assertIsEnabled().performClick()
            compose.onNode(hasText("Family", substring = true) and isSelectable()).performClick()
            compose.onNodeWithText("Create invite link").assertIsEnabled()
        }
    }
}
