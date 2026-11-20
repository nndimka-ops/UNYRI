package org.unyri.admin.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Test
import java.time.Instant
import kotlin.test.*

class CoreTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()
    private val id = "00000000-0000-0000-0000-000000000000"
    private fun saved() = Session("https://test.invalid", "fake-access", PaperCrypto.b64(ByteArray(32)), id,
        "synthetic", id, "owner", id, Instant.now().plusSeconds(3600).toString(), Instant.now().toString(), true)

    @Test fun verifiesPublishedRustVectorAndRejectsTampering() {
        val public = "Szyj6KzonzQXpYgcyGK7U2ECJMcry7zql3_gC1muVPM"
        val signature = "s5Xn31303SSBmTWdT7SJe_ILhCOcveaT1V5o93jjF9TLbo0w2RA380ktPVPks7KyEYAPh5So4RLotc04qgZ3AQ"
        assertTrue(PaperCrypto.verify(public, "COCO test vector", signature))
        assertFalse(PaperCrypto.verify(public, "COCO test Vector", signature))
        assertFalse(PaperCrypto.verify(public, "COCO test vector", "bad"))
    }
    @Test fun signsExactlyLikeUnchangedCSharpAndRustPayload() {
        val v = wireJson.parseToJsonElement(fixture("crypto-vector")).jsonObject
        fun value(key: String) = v[key]!!.jsonPrimitive.content
        val paper = value("paper")
        assertEquals(paper, PaperCrypto.checked(paper.lowercase().chunked(4).joinToString(" - ")))
        assertEquals(value("seed_hex"), PaperCrypto.seed(paper).joinToString("") { "%02x".format(it) })
        assertEquals(value("public_key"), PaperCrypto.publicKey(paper))
        val c = Challenge(id,id,id,id,"unlock",value("public_key"),"synthetic-nonce")
        assertEquals(value("message"), PaperCrypto.payload(c))
        assertFalse(PaperCrypto.payload(c).endsWith("\n"))
        assertEquals(value("signature"), PaperCrypto.sign(paper, PaperCrypto.payload(c)))
        assertTrue(PaperCrypto.verify(c.public_key, PaperCrypto.payload(c), value("signature")))
        for (changed in listOf(c.copy(purpose="rotate"), c.copy(nonce="other"), c.copy(auth_session="00000000-0000-0000-0000-000000000001"))) {
            assertFalse(PaperCrypto.verify(c.public_key, PaperCrypto.payload(changed), value("signature")))
        }
        assertFailsWith<ProtocolFailure> { PaperCrypto.proof(paper,c,id,"00000000-0000-0000-0000-000000000001","unlock") }
        assertFailsWith<ProtocolFailure> { PaperCrypto.payload(c.copy(nonce="a\nb")) }
        assertFailsWith<ProtocolFailure> { PaperCrypto.uuid("0-0-0-0-0") }
        assertFailsWith<AdminFailure> { PaperCrypto.checked(paper.dropLast(1)+if(paper.last()=='0') "1" else "0") }
        assertFailsWith<AdminFailure> { PaperCrypto.checked("O".repeat(32)) }
    }
    @Test fun parsesHandwrittenServerFixturesWithoutInventingData() {
        val users = wireJson.decodeFromString<Page<UserRow>>(fixture("users")).checked()
        assertEquals("synthetic",users.items.single().reason)
        assertNull(users.items.single().description)
        val reports = wireJson.decodeFromString<Page<QueueRow>>(fixture("reports")).checked()
        assertEquals("spam",reports.items.single().reason)
        val feedback = wireJson.decodeFromString<FeedbackDetail>(fixture("feedback-detail"))
        assertEquals(0,feedback.screenshots.single().index)
        assertNull(feedback.performance)
        assertNull(wireJson.decodeFromString<Summary>(fixture("summary-moderator")).storage)
        val user = wireJson.decodeFromString<UserDetail>(fixture("user-detail"))
        assertEquals("Synthetic device",user.devices.single()["display_name"]!!.jsonPrimitive.content)
        assertFalse(user.moderation_allowed)
        assertEquals(24,wireJson.decodeFromString<Performance>(fixture("performance")).retention_hours)
        assertEquals("Test server",wireJson.decodeFromString<ServerPolicy>(fixture("server-info")).name)
        assertFalse(wireJson.decodeFromString<Health>(fixture("health-degraded")).database)
        assertEquals("note:add",wireJson.decodeFromString<Page<JsonObject>>(fixture("audit")).items.single()["action"]!!.jsonPrimitive.content)
    }
    @Test fun unknownPoliciesAndUnsupportedProtocolsFailClosed() {
        assertFailsWith<ProtocolFailure> { Policy.parse("password_only") }
        assertEquals(Policy.BOTH,AdminStatus(true,1,id).policy())
        assertFailsWith<ProtocolFailure> { AdminStatus(true,1,id,3,"both").policy() }
        assertFailsWith<ProtocolFailure> { AdminStatus(true,1,id,2,"unknown").policy() }
    }
    @Test fun rejectsRemoteHttpCredentialsAndRelativeEscapes() {
        for (bad in listOf("http://test.invalid", "https://user:pass@test.invalid", "https://test.invalid/path", "https://test.invalid?token=x", "https://test.invalid/#x", "file:///tmp/a", "https://test.invalid:0")) {
            assertFailsWith<AdminFailure>(bad) { Origin(bad) }
        }
        assertEquals("http://127.0.0.1:8080",Origin("http://127.0.0.1:8080/").value)
        assertEquals("http://[::1]:8080",Origin("http://[::1]:8080").value)
        for (bad in listOf("//evil.invalid/a", "/a/../b", "https://evil.invalid", "/a\\b")) assertFailsWith<ProtocolFailure> { Origin("https://test.invalid").resolve(bad) }
        assertTrue(AdminApi.query("/admin/users","query" to "a&b ?").contains("a%26b+%3F"))
    }
    @Test fun boundsBeforeParsing() {
        assertFailsWith<ProtocolFailure> { JsonBudget.check("[".repeat(33)+"]".repeat(33)) }
        assertFailsWith<ProtocolFailure> { JsonBudget.check("["+"0,".repeat(4098)+"0]") }
        JsonBudget.check("{\"quoted\":\"[\\\"}\",\"ok\":[]}")
        assertFailsWith<ProtocolFailure> { Page(listOf(1),null,true).checked() }
        assertFailsWith<ProtocolFailure> { Page<Int>(emptyList(),"x",true).checked() }
    }
    @Test fun cursorHistoryResetsAndRejectsLoops() {
        val pager = Pager<Int>(); pager.accept(Page(listOf(1),"a",true)); pager.next()
        assertEquals("a",pager.cursor); assertTrue(pager.canBack)
        assertFailsWith<ProtocolFailure> { pager.accept(Page(listOf(2),"a",true)) }
        pager.accept(Page(listOf(2))); pager.back(); assertNull(pager.cursor)
        pager.reset(); assertFalse(pager.canBack); assertTrue(pager.page.items.isEmpty())
        val epoch = SessionEpoch(); val first=epoch.value; epoch.clear(); assertFalse(epoch.accepts(first))
    }
    @Test fun optInExpiryAndRedaction() {
        val s=saved(); assertTrue(s.valid())
        assertFalse(s.copy(accessExpires=Instant.now().plusSeconds(29).toString()).valid())
        assertFalse(s.copy(unlockedAt=Instant.now().minusSeconds(43201).toString()).valid())
        assertFalse(s.copy(unlockedAt=Instant.now().plusSeconds(3600).toString()).valid())
        assertFalse(s.toString().contains(s.access))
        val unavailable = LinuxSessionStore("/nonexistent/synthetic-secret-tool")
        assertFailsWith<AdminFailure> { unavailable.save(s.copy(optedIn=false)) }
        assertFailsWith<AdminFailure> { unavailable.save(s) }
    }
    @Test fun sendsExactHeadersAndAttemptsBothRevocations() {
        val requests=mutableListOf<WireRequest>()
        val transport=Transport { r ->
            requests += r
            when(r.path) {
                "/admin/status" -> """{"configured":true,"key_protocol":1,"auth_protocol":2,"auth_policy":"authenticator","server_id":"$id"}"""
                "/admin/summary" -> fixture("summary-moderator")
                "/admin/lock" -> throw AdminFailure(FailureKind.NETWORK)
                else -> "{}"
            }
        }
        val api=AdminApi(Origin("https://test.invalid"),transport); val s=saved(); api.restore(s)
        assertEquals(s.access,requests[0].bearer); assertNull(requests[0].admin)
        assertEquals(s.admin,requests[1].admin)
        assertFailsWith<AdminFailure> { api.logout() }
        assertEquals(listOf("/admin/lock","/auth/logout"),requests.takeLast(2).map { it.path })
        assertNull(api.session)
        assertFailsWith<AdminFailure> { api.restore(s.copy(optedIn=false)) }
    }
}
