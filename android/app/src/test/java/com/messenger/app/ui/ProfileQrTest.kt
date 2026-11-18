package com.messenger.app.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.*
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.domain.model.UserProfile
import kotlinx.coroutines.test.runTest
import androidx.lifecycle.SavedStateHandle
import com.messenger.app.security.PublicProfileLink

class ProfileQrTest {
    @Test fun generatedCodeDecodesToTheSamePublicProfileUriThatIsShared() {
        val link = assertNotNull(profilePublicLink("uiqa_boris_f6041a"))
        val matrix = profileQrMatrix(link)
        val scale = 8
        val width = (matrix.width + 8) * scale
        val pixels = IntArray(width * width) { -1 }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + 4) * scale + dy) * width + (x + 4) * scale + dx] = -16777216
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))).text
        assertEquals("unyri://user/uiqa_boris_f6041a", decoded)
        assertEquals(link, decoded)
        assertEquals("uiqa_boris_f6041a", profileLinkUsername(decoded))
    }

    @Test fun absentOrInvalidUsernameNeverBecomesAWorkingLookingQr() {
        for (name in listOf(null, "", " ", "ab", "@username", "name/path", "name?token=secret", "a".repeat(33))) assertNull(profilePublicLink(name))
        assertEquals("unyri://user/Valid.Name_1", profilePublicLink("Valid.Name_1"))
    }

    @Test fun onlyTheAdvertisedProfileUriShapeIsAccepted() {
        for (uri in listOf(null, "", "coco://conference/abc", "https://user/name", "unyri://user/ab",
            "unyri://user/name/extra", "unyri://user/name?token=secret", "unyri://user/name#fragment",
            "unyri://evil@user/name", "unyri://user:443/name", "unyri://user/%6Eame", "unyri://user/../name")) {
            assertNull(profileLinkUsername(uri), uri)
        }
        assertEquals("Valid.Name_1", profileLinkUsername("UNYRI://USER/Valid.Name_1"))
    }

    @Test fun coldLaunchWaitsForAuthenticationAndSavedInboxRestoresAcrossRecreation() {
        val saved = SavedStateHandle()
        val cold = ProfileLinkViewModel(saved).inbox
        cold.offer(link("boris"), "workspace-a", null)
        assertNull(cold.claim("workspace-a", null))
        val restored = ProfileLinkViewModel(saved).inbox
        val request = assertNotNull(restored.claim("workspace-a", "account-a"))
        assertEquals("boris", request.username)
        assertTrue(restored.isCurrent(request, "workspace-a", "account-a"))
        assertTrue(restored.consume(request))
        assertFalse(restored.consume(request))
        assertNull(ProfileLinkViewModel(saved).inbox.pending)
    }

    @Test fun warmDeliverySupersedesOldLookupAndBoundLinksCannotCrossAccounts() {
        val inbox = ProfileLinkInbox()
        inbox.offer(link("first"), "workspace", "a")
        val old = assertNotNull(inbox.claim("workspace", "a"))
        inbox.offer(link("second"), "workspace", "a")
        assertNull(PublicProfileLink.parse("android.intent.action.VIEW", "coco://conference/keep-other-route"))
        val next = assertNotNull(inbox.claim("workspace", "a"))
        assertEquals("second", next.username)
        assertFalse(inbox.isCurrent(old, "workspace", "a"))
        assertFalse(inbox.consume(old))
        inbox.fail(next); assertTrue(inbox.failed)
        inbox.retry(); assertFalse(inbox.failed)
        assertNotEquals(next.id, inbox.pending?.id)
        assertNull(inbox.claim("workspace", "b"))
        assertNull(inbox.pending)
    }

    private fun user(id: String, name: String) = PublicUser(id, name, name, null)
    private fun link(name: String) = assertNotNull(PublicProfileLink.parse("android.intent.action.VIEW", "unyri://user/$name"))
    private suspend fun resolveOtherProfileLink(username: String, owner: String, currentOwner: () -> String?,
        lookup: suspend (String, Long) -> Result<SearchResult>): Result<PublicUser> =
        resolveProfileLink(username, owner, currentOwner, selfId = "signed-in", getMe = {
            Result.success(UserProfile("signed-in", "signed_in", "Signed in", null, createdAt = "", updatedAt = ""))
        }, lookup = lookup)

    @Test fun authenticatedLookupReturnsOnlyAnExactCaseInsensitivePublicProfile() = runTest {
        val result = resolveOtherProfileLink("boris", "a", { "a" }, { name, _ ->
            assertEquals("boris", name)
            Result.success(SearchResult(listOf(user("partial", "boris_other"), user("exact", "BoRiS")), null))
        })
        assertEquals("exact", result.getOrThrow().id)
    }

    @Test fun missingAndAmbiguousSearchProfilesFail() = runTest {
        for (items in listOf(emptyList(), listOf(user("partial", "boris_other")), listOf(user("one", "boris"), user("two", "BORIS")))) {
            assertTrue(resolveOtherProfileLink("boris", "a", { "a" }, { _, _ -> Result.success(SearchResult(items, null)) }).isFailure)
        }
    }

    @Test fun lateLookupCannotPresentAProfileAfterAnAccountChange() = runTest {
        var owner = "a"
        val lateLookup = resolveOtherProfileLink("boris", "a", { owner }, { _, _ ->
            owner = "b"; Result.success(SearchResult(listOf(user("target", "boris")), null))
        })
        assertTrue(lateLookup.isFailure)
        val inbox = ProfileLinkInbox()
        inbox.offer(link("boris"), "workspace", "a")
        val bound = assertNotNull(inbox.claim("workspace", "a"))
        assertFalse(inbox.isCurrent(bound, "workspace", owner))
        assertFalse(inbox.present(bound, user("target", "boris"), "workspace", owner))
        assertNull(inbox.visibleCard("workspace", owner))
    }

    @Test fun failedLookupIsReportedWithoutAProfile() = runTest {
        assertTrue(resolveOtherProfileLink("boris", "a", { "a" }, { _, _ -> Result.failure(IllegalStateException("offline")) }).isFailure)
    }

    @Test fun exactProfileAfterFiftyAlphabeticallyEarlierSubstringMatchesSelectsTheRightCard() = runTest {
        val offsets = mutableListOf<Long>()
        val earlier = (0 until 50).map { user("partial-$it", "a${it.toString().padStart(2, '0')}boris") }
        val result = resolveOtherProfileLink("boris", "a", { "a" }, { name, offset ->
            assertEquals("boris", name); offsets += offset
            Result.success(if (offset == 0L) SearchResult(earlier, 50L)
                else SearchResult(listOf(user("exact", "BoRiS")), null))
        })
        assertEquals("exact", result.getOrThrow().id)
        assertEquals(listOf(0L, 50L), offsets)
    }

    @Test fun paginationStopsAtTheRequestBoundAndRejectsNonAdvancingCursors() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            findProfileLinkUser("boris", "a", { "a" }, { _, offset ->
                calls++; Result.success(SearchResult(listOf(user("partial", "boris_other")), offset + 50))
            }, maxPages = 2)
        }
        assertEquals(2, calls)
        for (stalled in listOf(0L, -1L)) {
            calls = 0
            assertTrue(resolveOtherProfileLink("boris", "a", { "a" }, { _, _ ->
                calls++; Result.success(SearchResult(emptyList(), stalled))
            }).isFailure)
            assertEquals(1, calls)
        }
    }

    @Test fun accountChangeOnALaterPagePreventsProfilePresentation() = runTest {
        var owner = "a"
        val offsets = mutableListOf<Long>()
        val result = resolveOtherProfileLink("boris", "a", { owner }, { _, offset ->
            offsets += offset
            if (offset == 0L) Result.success(SearchResult(emptyList(), 50L))
            else { owner = "b"; Result.success(SearchResult(listOf(user("exact", "boris")), null)) }
        })
        assertTrue(result.isFailure)
        assertEquals(listOf(0L, 50L), offsets)
    }

    @Test fun laterPageFailureOrCachedResultsCannotSupplyAProfileCard() = runTest {
        val failures = listOf(Result.failure<SearchResult>(IllegalStateException("offline")),
            Result.success(SearchResult(listOf(user("exact", "boris")), null, cached = true)))
        for (failure in failures) {
            val offsets = mutableListOf<Long>()
            val result = resolveOtherProfileLink("boris", "a", { "a" }, { _, offset ->
                offsets += offset
                if (offset == 0L) Result.success(SearchResult(emptyList(), 50L)) else failure
            })
            assertTrue(result.isFailure)
            assertEquals(listOf(0L, 50L), offsets)
        }
    }

    @Test fun profileCardIsConsumedOnceAndDismissalSurvivesRecreation() = runTest {
        val saved = SavedStateHandle()
        val inbox = ProfileLinkViewModel(saved).inbox
        inbox.offer(link("boris"), "workspace", "a")
        val request = assertNotNull(inbox.claim("workspace", "a"))
        val profile = resolveOtherProfileLink(request.username, "a", { "a" },
            { _, _ -> Result.success(SearchResult(listOf(user("target", "boris")), null)) }).getOrThrow()
        assertTrue(inbox.present(request, profile, "workspace", "a"))
        assertEquals(profile, inbox.visibleCard("workspace", "a"))
        assertNull(inbox.pending)
        assertFalse(inbox.present(request, profile, "workspace", "a"))
        inbox.dismissCard()
        val recreated = ProfileLinkViewModel(saved).inbox
        assertNull(recreated.pending)
        assertNull(recreated.visibleCard("workspace", "a"))
    }

    @Test fun locallyBoundAccountAndWorkspaceCannotBeReplacedBeforeTheFirstClaim() {
        val inbox = ProfileLinkInbox()
        inbox.offer(link("boris"), "workspace-a", "a")
        assertNull(inbox.claim("workspace-a", "b"))
        assertNull(inbox.pending)
        inbox.offer(link("boris"), "workspace-a", null)
        assertNull(inbox.claim("workspace-b", "b"))
        assertNull(inbox.pending)
    }

    @Test fun staleOrMismatchedProfilesCannotReplaceTheCurrentCard() {
        val inbox = ProfileLinkInbox()
        inbox.offer(link("first"), "workspace", "a")
        val old = assertNotNull(inbox.claim("workspace", "a"))
        inbox.offer(link("second"), "workspace", "a")
        val current = assertNotNull(inbox.claim("workspace", "a"))
        assertFalse(inbox.present(old, user("old", "first"), "workspace", "a"))
        assertFalse(inbox.present(current, user("wrong", "third"), "workspace", "a"))
        assertTrue(inbox.present(current, user("new", "second"), "workspace", "a"))
        assertNull(inbox.visibleCard("workspace", "b"))
        assertNull(inbox.claim("workspace", "b"))
        assertNull(inbox.visibleCard("workspace", "a"))
    }

    @Test fun externalProfileAllowlistRejectsChatAccountAndFrameworkPayloads() {
        for (uri in listOf("unyri://user/boris?chatId=chat&account=other", "unyri://user/boris#account=other",
            "unyri://user/boris/chat/123", "unyri://user/boris%3Faccount=other", "unyri://other@user/boris",
            "android-app://androidx.navigation/chat/123", "intent://user/boris#Intent;S.account=other;end")) {
            assertNull(PublicProfileLink.parse("android.intent.action.VIEW", uri), uri)
        }
        for (action in listOf(null, "android.intent.action.MAIN", "android.intent.action.SEND")) {
            assertNull(PublicProfileLink.parse(action, "unyri://user/boris"))
        }
    }

    @Test fun mainActivityRegistersProfileUrisSeparatelyFromConferenceLinks() {
        val file = listOf(java.io.File("src/main/AndroidManifest.xml"), java.io.File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val activities = doc.getElementsByTagName("activity")
        val ns = "http://schemas.android.com/apk/res/android"
        val main = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name") == ".MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        val profile = (0 until filters.length).map { filters.item(it) as org.w3c.dom.Element }.single { filter ->
            val data = filter.getElementsByTagName("data")
            (0 until data.length).any { (data.item(it) as org.w3c.dom.Element).let { it.getAttributeNS(ns, "scheme") == "unyri" && it.getAttributeNS(ns, "host") == "user" } }
        }
        val actions = profile.getElementsByTagName("action")
        assertTrue((0 until actions.length).any { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") == "android.intent.action.VIEW" })
        val categories = profile.getElementsByTagName("category")
        assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            (0 until categories.length).map { (categories.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }.toSet())
    }
}
