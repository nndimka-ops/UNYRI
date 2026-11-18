package com.messenger.app.ui

import com.messenger.app.R
import com.messenger.app.data.identity.AuthStep
import com.messenger.app.data.identity.IdentityPolicy
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.security.PublicProfileLink
import com.messenger.app.ui.auth.login2Header
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class Login2Fix1Test {
    private val peer = PublicUser("peer", "alice", "Alice", null, publicNumber = "778936480", publicUsername = "alice")

    @Test fun numberGroupingAndDrawerCopyPreserveTheCanonicalDigits() {
        val grouped = "778\u202f936\u202f480"
        assertEquals(grouped, IdentityPolicy.formattedNumber("778936480"))
        assertEquals(PublicNumberPresentation(grouped, "778936480"), drawerPublicNumber("778936480"))
        for (raw in listOf("1", "12345", "012345678", "private_login", "１２３４５６７８９", "")) {
            assertEquals(raw, IdentityPolicy.formattedNumber(raw))
            assertNull(drawerPublicNumber(raw))
        }
        assertNull(drawerPublicNumber(null))
        assertEquals(grouped, publicHandleText("778936480", null, "private_login", false))
        assertEquals("@alice", publicHandleText("778936480", "alice", "private_login", false))
        assertEquals("", publicHandleText(null, null, "private_login", false))
        for (space in listOf(" ", "\u202f", "\u00a0"))
            assertEquals("778936480", IdentityPolicy.input("778${space}936${space}480")?.value)
        assertEquals(listOf("peer"), com.messenger.app.ui.search.ContactsPresentation.rows(
            listOf(com.messenger.app.data.profile.ContactEntry(peer)), grouped, emptySet(), legacyLogins = false).map { it.entry.id })
        assertTrue(com.messenger.app.ui.search.ContactsPresentation.rows(
            listOf(com.messenger.app.data.profile.ContactEntry(peer.copy(publicNumber = null))), "unrelated", emptySet(), legacyLogins = false).isEmpty())
        assertTrue(IdentityPolicy.link("778936480", "https://qa.example")!!.contains("/778936480?"))
    }

    @Test fun numberAndUsernameLinksResolveExactlyAndOfferBothActions() = runTest {
        for (path in listOf("n/778936480", "@alice")) {
            val raw = "unyri://u/$path?server=qa.example"
            val inbox = ProfileLinkInbox()
            inbox.offer(PublicProfileLink.parse("android.intent.action.VIEW", raw)!!, "scope", "owner")
            val request = inbox.claim("scope", "owner")!!
            val user = resolveProfileLink(raw, "owner", { "owner" }, "self", { error("No private profile read") },
                { query, offset ->
                    assertEquals(raw, query); assertEquals(0L, offset)
                    Result.success(SearchResult(listOf(peer), null))
                }, "https://qa.example").getOrThrow()
            assertTrue(inbox.present(request, user, "scope", "owner"))
            var messages = 0; var adds = 0
            val actions = ProfileLinkActions("owner", { "owner" }, { inbox.visibleCard("scope", "owner") != null },
                { id -> assertEquals("peer", id); messages++; Result.success("direct-chat") }, { adds++; true })
            assertEquals("direct-chat", actions.message(user.id))
            actions.addContact(false)
            assertEquals(1, messages); assertEquals(1, adds)
            actions.addContact(true) // Passive already-contact state cannot remove or add again.
            assertEquals(1, adds)
            inbox.dismissCard()
            assertNull(actions.message(user.id))
            actions.addContact(false)
            assertEquals(1, messages); assertEquals(1, adds)
        }
    }

    @Test fun foreignOriginAndMismatchedOrCachedPeopleNeverOpenALandingCard() = runTest {
        for (path in listOf("n/778936480", "@alice")) {
            val raw = "unyri://u/$path?server=foreign.example"
            assertTrue(resolveProfileLink(raw, "owner", { "owner" }, "self", { error("No self read") },
                { _, _ -> error("Foreign origin must not reach lookup") }, "https://qa.example").isFailure)
        }
        val raw = "unyri://u/@alice?server=qa.example"
        for (result in listOf(SearchResult(listOf(peer.copy(publicUsername = "bobby")), null),
            SearchResult(listOf(peer), null, cached = true), SearchResult(emptyList(), null))) {
            assertTrue(resolveProfileLink(raw, "owner", { "owner" }, "self", { error("No self read") },
                { _, _ -> Result.success(result) }, "https://qa.example").isFailure)
        }
        assertFalse(profileLinkMatches("unyri://u/n/123456789?server=qa.example", peer))
    }

    @Test fun landingActionsFenceLateResultsAndRepeatedTapsAndUseGenericFailureState() = runTest {
        var owner = "owner"; var calls = 0
        val pending = CompletableDeferred<Result<String>>()
        val actions = ProfileLinkActions("owner", { owner }, { true }, { calls++; pending.await() }, { error("Must not add") })
        val first = async { actions.message("peer") }
        testScheduler.runCurrent()
        assertTrue(actions.busy)
        assertNull(actions.message("peer"))
        assertEquals(1, calls)
        owner = "other"
        pending.complete(Result.success("old-chat"))
        assertNull(first.await())
        actions.addContact(false)
        val failed = ProfileLinkActions("owner", { "owner" }, { true },
            { Result.failure(IllegalStateException("untrusted server detail")) }, { false })
        assertNull(failed.message("peer")); assertTrue(failed.failed)
        failed.addContact(false); assertTrue(failed.failed); assertFalse(failed.busy)
    }

    @Test fun signUpAndDisplayNameUseRegistrationHeaderAndAllLocalesHaveNewCopy() {
        assertEquals(R.string.auth_flow_welcome, login2Header(AuthStep.SignIn))
        assertEquals(R.string.login2_signup_header, login2Header(AuthStep.SignUp))
        assertEquals(R.string.login2_signup_header, login2Header(AuthStep.DisplayName))
        val root = File("src/main/res")
        val dirs = root.listFiles()!!.filter { File(it, "login2.xml").exists() }
        assertEquals(20, dirs.size)
        fun strings(folder: File): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(folder, "login2_fix1.xml"))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        for (dir in dirs) {
            val copy = strings(dir)
            assertEquals(4, copy.size, dir.name)
            assertTrue(copy.values.all { it.isNotBlank() }, dir.name)
            if (dir.name != "values") assertNotEquals("System default", copy["language_system_default"], dir.name)
        }
        for ((locale, header, system) in listOf(Triple("values", "Join UNYRI", "System default"),
            Triple("values-ru", "Присоединиться к UNYRI", "Как в системе"),
            Triple("values-uk", "Приєднатися до UNYRI", "Як у системі"))) {
            val copy = strings(File(root, locale))
            assertEquals(header, copy["login2_signup_header"])
            assertEquals(system, copy["language_system_default"])
        }
        assertEquals("В контактах", strings(File(root, "values-ru"))["login2_in_contacts"])
        assertEquals("У контактах", strings(File(root, "values-uk"))["login2_in_contacts"])
        for ((locale, write, add) in listOf(Triple("values", "Message", "Add to contacts"),
            Triple("values-ru", "Написать", "Добавить в контакты"),
            Triple("values-uk", "Написати", "Додати до контактів"))) {
            fun label(file: String, key: String): String {
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(File(root, "$locale/$file.xml")).getElementsByTagName("string")
                return (0 until nodes.length).map { nodes.item(it) }
                    .single { it.attributes.getNamedItem("name").nodeValue == key }.textContent
            }
            assertEquals(write, label("drawer_contacts", "contacts_write"))
            assertEquals(add, label("login_contacts", "contacts_add"))
        }
    }
}
