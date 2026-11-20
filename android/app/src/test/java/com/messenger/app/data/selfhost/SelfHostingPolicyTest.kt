package com.messenger.app.data.selfhost

import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.RegisterRequestDto
import com.messenger.app.data.api.parseApiException
import com.messenger.app.ui.selfhost.ServerJoinInbox
import com.messenger.app.ui.selfhost.inviteFieldVisible
import com.messenger.app.ui.selfhost.registrationFailureMessage
import com.messenger.app.ui.selfhost.registrationPolicyStale
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class SelfHostingPolicyTest {
    @Test fun `guide language selects only official GitHub pages`() {
        for ((language, filename) in listOf(
            "ru" to "SELF_HOSTING.md", "ru-RU" to "SELF_HOSTING.md",
            "uk" to "SELF_HOSTING_UK.md", "UK_ua" to "SELF_HOSTING_UK.md",
            "en" to "SELF_HOSTING_EN.md", "ro" to "SELF_HOSTING_EN.md",
            "" to "SELF_HOSTING_EN.md", "https://untrusted.test" to "SELF_HOSTING_EN.md",
        )) {
            val url = java.net.URI(SelfHostingGuidePolicy.guideUrl(language))
            assertEquals("https", url.scheme)
            assertEquals("github.com", url.host)
            assertEquals("/nndimka-ops/UNYRI/blob/main/docs/$filename", url.path)
            assertNull(url.userInfo)
            assertNull(url.query)
            assertNull(url.fragment)
        }
    }

    @Test fun `missing invalid and closed registration have distinct localized feedback and refresh policy`() {
        val messages = listOf("invite_required", "invite_invalid", "conflict").map { code ->
            assertTrue(registrationPolicyStale(code)); assertNotNull(registrationFailureMessage(code))
        }
        assertEquals(3, messages.toSet().size)
        assertFalse(registrationPolicyStale("invalid_credentials"))
        assertNull(registrationFailureMessage("invalid_credentials"))
        for (code in listOf("invite_required", "invite_invalid")) {
            val error = parseApiException(ApiJson, 403, """{"error":{"code":"$code","message":"fixture"}}""") as AuthApiException.Api
            assertEquals(403, error.httpStatus)
            assertNotNull(registrationFailureMessage(error.code))
        }
    }
    @Test fun `policy is cached by canonical origin and expires without a timer`() = runBlocking {
        var now = 0L; var calls = 0
        val cache = RegistrationPolicyCache({ now }, 100)
        suspend fun load(origin: String): RegistrationPolicy { calls++; return RegistrationPolicy(origin.contains("invite")) }
        assertFalse(cache.get("https://OPEN.test:443/", load = ::load).inviteRequired)
        cache.get("https://open.test", load = ::load)
        assertEquals(1, calls)
        assertTrue(cache.get("https://invite.test", load = ::load).inviteRequired)
        assertEquals(2, calls)
        now = 101
        cache.get("https://open.test", load = ::load)
        assertEquals(3, calls)
        cache.get("https://open.test", true, ::load)
        assertEquals(4, calls)
    }
    @Test fun `failed stale refresh propagates instead of inventing open registration`() = runBlocking {
        val cache = RegistrationPolicyCache()
        cache.get("https://invite.test") { RegistrationPolicy(true) }
        assertFailsWith<IllegalStateException> { cache.get("https://invite.test", true) { error("fixture") } }
        assertTrue(cache.get("https://invite.test") { error("should be cached") }.inviteRequired)
    }
    @Test fun `concurrent reads share the cached response`() = runBlocking {
        val cache = RegistrationPolicyCache(); var calls = 0
        coroutineScope { (1..8).map { async { cache.get("https://example.test") { calls++; yield(); RegistrationPolicy() } } }.awaitAll() }
        assertEquals(1, calls)
    }
    @Test fun `invite field is absent for old and open servers and present only for true`() {
        assertFalse(inviteFieldVisible(null))
        assertFalse(inviteFieldVisible(ApiJson.decodeFromString<RegistrationPolicy>("{}")))
        assertFalse(inviteFieldVisible(RegistrationPolicy(false, "invite_only")))
        assertTrue(inviteFieldVisible(RegistrationPolicy(true)))
        assertTrue(inviteFieldVisible(RegistrationPolicy(true, open = false)))
    }
    @Test fun `invite serialization omits a null credential and keeps closure independent`() {
        val request = RegisterRequestDto("fixture", "fixture", "Fixture", installationId = "fixture")
        assertFalse(ApiJson.encodeToString(request).contains("invite_code"))
        assertTrue(ApiJson.encodeToString(request.copy(inviteCode = "fixture-code")).contains("\"invite_code\":\"fixture-code\""))
        assertFalse(request.copy(inviteCode = "fixture-code").toString().contains("fixture-code"))
        assertFalse(ApiJson.decodeFromString<RegistrationPolicy>("""{"registration_open":false,"invite_required":true}""").open)
    }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun link(origin: String = "https://example.test", code: String = "fixture.secret") =
        "$origin/#server=${enc(origin)}&invite_code=${enc(code)}"
    @Test fun `share link round trip has no query or secret in string representation`() {
        val value = ServerInviteLink.create("https://example.test", "fixture.secret")
        assertNull(java.net.URI(value).query)
        val parsed = assertNotNull(ServerInviteLink.parse(value))
        assertEquals("https://example.test", parsed.origin)
        assertEquals("fixture.secret", parsed.code)
        assertFalse(parsed.toString().contains("fixture"))
    }
    @Test fun `reject query credentials foreign and duplicate keys and mismatched origins`() {
        for (value in listOf(link().replace("#", "?"), link() + "&foreign=1", link() + "&server=x",
            link().replace("invite_code", "password"), link().replace("#server=", "?x=1#server="),
            link().replace("https://example.test/#", "https://foreign.test/#"),
            link().replace("/#", "/other#"), link().replace("https://example.test/#", "https://user:pass@example.test/#")))
            assertNull(ServerInviteLink.parse(value))
    }
    @Test fun `reject HTTP except the existing explicit debug LAN allowance`() {
        assertNull(ServerInviteLink.parse(link("http://example.test"), true))
        assertNull(ServerInviteLink.parse(link("http://192.168.1.4:8080")))
        assertNotNull(ServerInviteLink.parse(link("http://192.168.1.4:8080"), true))
    }
    @Test fun `ingress is transient and discards confirmation after a session change`() {
        val inbox = ServerJoinInbox()
        assertTrue(inbox.offer(link(), 1, "https://old.test"))
        assertNull(inbox.signupInvite)
        assertNotNull(inbox.current(1, "https://old.test"))
        assertNull(inbox.current(2, "https://old.test"))
        assertNull(inbox.pending)
        assertTrue(inbox.offer(link(), 2, "https://old.test"))
        val invitation = assertNotNull(inbox.pending)
        assertTrue(inbox.begin(invitation, 2, "https://old.test"))
        assertFalse(inbox.offer(link(), 2, "https://old.test"))
        assertSame(invitation, inbox.current(3, "https://example.test"))
        inbox.import(invitation)
        assertSame(invitation, inbox.signupInvite)
        assertNull(inbox.pending)
        inbox.clearSignup(); assertNull(inbox.signupInvite)
        assertFalse(inbox.offer(link().replace('#', '?'), 2, "https://old.test"))
    }
    @Test fun `reject overlong empty malformed and control-bearing values`() {
        for (value in listOf(link(code = "x".repeat(257)), link(code = ""), link(code = "a\nb"), link(code = "a?b"),
            link() + "x".repeat(4096), "https://example.test/#server=%GG&invite_code=fixture",
            link().replace("https%3A%2F%2Fexample.test", enc("https://example.test/path"))))
            assertNull(ServerInviteLink.parse(value))
    }
    @Test fun `owner code accepts case separators and rejects foreign payloads`() {
        assertEquals("ABCDEFGHJKLMNPQR", OwnerClaimPolicy.normalize("abcd-efgh-jklm-npqr"))
        assertEquals("ABCDEFGHJKLMNPQR", OwnerClaimPolicy.normalize(" abcd efgh\tjklm\nnpqr "))
        assertNull(OwnerClaimPolicy.normalize("https://example.test"))
        assertNull(OwnerClaimPolicy.normalize("O000-1111-AAAA-BBBB"))
        assertNull(OwnerClaimPolicy.normalize("A".repeat(65)))
    }
    @Test fun `claim states cover unavailable invalid rate limit and successful role refresh`() = runBlocking {
        var available = false; var refusal: String? = null; var refreshed = false
        val controller = OwnerClaimController({ "fixture-account" }, { available }, {
            refusal?.let { throw AuthApiException.Api(it, "fixture") }
        }, { refreshed = true })
        controller.checkAvailability()
        assertEquals(ClaimState.Unavailable, controller.state.value)
        available = true; controller.checkAvailability(true)
        assertEquals(ClaimState.Available, controller.state.value)
        controller.claim("bad")
        assertEquals(ClaimState.Invalid, controller.state.value)
        refusal = "setup_invalid"; controller.claim("ABCDEFGHJKLMNPQR")
        assertEquals(ClaimState.Invalid, controller.state.value)
        refusal = "rate_limited"; controller.claim("ABCDEFGHJKLMNPQR")
        assertEquals(ClaimState.RateLimited, controller.state.value)
        assertFalse(OwnerClaimPolicy.canSubmit(controller.state.value))
        controller.checkAvailability(true); refusal = null
        controller.claim("ABCDEFGHJKLMNPQR")
        assertEquals(ClaimState.Success, controller.state.value)
        assertTrue(refreshed)
    }
    @Test fun `retired claim and account switches cannot grant a stale role`() = runBlocking {
        var owner: String? = "one"; var refreshed = false
        val controller = OwnerClaimController({ owner }, { true }, { owner = "two" }, { refreshed = true })
        controller.checkAvailability(); controller.claim("ABCDEFGHJKLMNPQR")
        assertFalse(refreshed)
        assertNotEquals(ClaimState.Success, controller.state.value)
        assertEquals(ClaimState.Unavailable, OwnerClaimPolicy.refusal("setup_not_available"))
        owner = null; controller.checkAvailability(); assertEquals(ClaimState.Unavailable, controller.state.value)
    }
    @Test fun `role refresh retry never reuses an already consumed claim code`() = runBlocking {
        var posts = 0; var failRefresh = true
        val controller = OwnerClaimController({ "fixture" }, { true }, { posts++ }, { if (failRefresh) error("fixture") })
        controller.checkAvailability(); controller.claim("ABCDEFGHJKLMNPQR")
        assertEquals(ClaimState.RoleRefreshFailed, controller.state.value)
        assertTrue(OwnerClaimPolicy.entryVisible(controller.state.value))
        failRefresh = false; controller.retryRoleRefresh()
        assertEquals(ClaimState.Success, controller.state.value)
        assertEquals(1, posts)
    }
    @Test fun `cancelled availability and claim operations can be retried by a new screen`() = runBlocking {
        var first = true
        val entered = CompletableDeferred<Unit>()
        val controller = OwnerClaimController({ "fixture" }, {
            if (first) { entered.complete(Unit); awaitCancellation() } else true
        }, { awaitCancellation() }, {})
        val availability = launch { controller.checkAvailability() }
        entered.await(); availability.cancelAndJoin()
        first = false; controller.checkAvailability()
        assertEquals(ClaimState.Available, controller.state.value)
        val claim = launch(start = CoroutineStart.UNDISPATCHED) { controller.claim("ABCDEFGHJKLMNPQR") }
        assertEquals(ClaimState.Submitting, controller.state.value)
        claim.cancelAndJoin()
        assertEquals(ClaimState.Failed, controller.state.value)
        controller.checkAvailability()
        assertEquals(ClaimState.Available, controller.state.value)
    }
}
