package com.messenger.app.ui

import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SelfDiscoveryTest {
    private val ownId = "67a2d975-1ea4-4d1b-8140-63062fc58ac1"
    private val own = PublicUser(ownId, "private_login", "Me", null,
        publicNumber = "604743993", publicUsername = "my_public_name")

    @Test fun selfDetectionUsesTheAuthenticatedImmutableIdForNumberAndUsernameResults() {
        for (result in listOf(own, own.copy(publicUsername = null), own.copy(username = "renamed_login"))) {
            assertTrue(isOwnPublicProfile(result.id, ownId))
            assertTrue(isOwnPublicProfile(result.id.uppercase(), ownId))
            assertFalse(isOwnPublicProfile(result.copy(id = "peer").id, ownId))
        }
        assertFalse(isOwnPublicProfile(ownId, null))
        assertFalse(isOwnPublicProfile(ownId, ""))
        assertFalse(isOwnPublicProfile(ownId, own.publicNumber))
        assertFalse(isOwnPublicProfile(ownId, own.publicUsername))
    }

    @Test fun ownNumberAndUsernameLinksResolveToSelfAndCannotInvokePeerActions() = runTest {
        for (path in listOf("n/604743993", "@my_public_name")) {
            val raw = "unyri://u/$path?server=qa.example"
            val result = resolveProfileLink(raw, "owner", { "owner" }, ownId,
                { error("Public lookup must not read the private login") },
                { _, _ -> Result.success(SearchResult(listOf(own), null)) },
                "https://qa.example").getOrThrow()
            assertTrue(isOwnPublicProfile(result.id, ownId))
            val actions = ProfileLinkActions("owner", { "owner" }, { true },
                { error("Self must not open a direct chat") }, { error("Self must not be added as a contact") },
                { !isOwnPublicProfile(result.id, ownId) })
            assertNull(actions.message(result.id))
            actions.addContact(false)
            assertFalse(actions.busy)
            assertFalse(actions.failed)
        }
    }

    @Test fun peerActionsStillWorkAndRecheckSelfEligibilityAtEachTap() = runTest {
        var selfId: String? = ownId
        var messages = 0
        var adds = 0
        val user = own.copy(id = "peer")
        val actions = ProfileLinkActions("owner", { "owner" }, { true },
            { messages++; Result.success("chat") }, { adds++; true },
            { !isOwnPublicProfile(user.id, selfId) })
        assertEquals("chat", actions.message(user.id))
        actions.addContact(false)
        assertEquals(1, messages)
        assertEquals(1, adds)
        selfId = "PEER"
        assertNull(actions.message(user.id))
        actions.addContact(false)
        assertEquals(1, messages)
        assertEquals(1, adds)
    }

    @Test fun selfLabelIsLocalizedInEveryExistingIdentityLocale() {
        val locales = File("src/main/res").listFiles()!!.filter { File(it, "login2.xml").exists() }
        assertEquals(20, locales.size)
        val labels = locales.associate { folder ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(folder, "battery_fix.xml"))
            val nodes = document.getElementsByTagName("string")
            val label = (0 until nodes.length).map { nodes.item(it) }
                .single { it.attributes.getNamedItem("name").nodeValue == "contacts_self" }.textContent
            assertTrue(label.isNotBlank(), folder.name)
            folder.name to label
        }
        assertEquals("This is you", labels["values"])
        assertEquals("Это вы", labels["values-ru"])
        assertEquals("Це ви", labels["values-uk"])
    }
}
