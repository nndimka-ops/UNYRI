package com.messenger.app.ui.admin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class SelfHostingAdminTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    @Test fun `invite actions use correct methods bounds paging and no credential query`() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>()
        val transport = AdminTransport({ path, body ->
            calls += path to body
            when {
                path.endsWith("/revoke") -> """{"ok":true}"""
                body != null -> """{"id":"$id","code":"fixture.secret","max_uses":2,"uses":0,"expires_at":"2026-12-01T00:00:00Z"}"""
                else -> """{"items":[{"id":"$id","status":"active","uses":1,"max_uses":2,"expires_at":"2026-12-01T00:00:00Z","creator":"Fixture"}],"next_before":"$id"}"""
            }
        }, { error("no blob") })
        val actions = SelfHostingAdminActions("moderator", transport)
        assertEquals("Fixture", actions.invites().items.single().creator)
        actions.invites(id)
        assertEquals("fixture.secret", actions.create(2, 7).code)
        val body = Json.parseToJsonElement(calls.last().second!!).jsonObject
        assertEquals(604800L, body["expires_in_seconds"]!!.jsonPrimitive.long)
        assertEquals(2, body["max_uses"]!!.jsonPrimitive.int)
        actions.revoke(id)
        assertEquals(listOf("invites", "invites?before=$id", "invites", "invites/$id/revoke"), calls.map { it.first })
        assertFailsWith<IllegalArgumentException> { actions.create(0, 7) }
        assertFailsWith<IllegalArgumentException> { actions.create(1, 366) }
        assertFailsWith<IllegalArgumentException> { actions.revoke("../registration") }
        Unit
    }
    @Test fun `moderator cannot read or write owner policy or self check`() = runBlocking {
        var calls = 0
        val transport = AdminTransport({ _, _ -> calls++; "{}" }, { error("no blob") })
        val actions = SelfHostingAdminActions("moderator", transport)
        assertFailsWith<IllegalStateException> { actions.registration() }
        assertFailsWith<IllegalStateException> { actions.setMode("open") }
        assertFailsWith<IllegalStateException> { actions.selfCheck() }
        assertEquals(0, calls)
        assertTrue(AdminNavigation.canOpen("moderator", AdminTarget.SelfHosting))
        assertFalse(AdminNavigation.canOpen(null, AdminTarget.SelfHosting))
    }
    @Test fun `owner can change registration and read self check without giving bearer-only access`() = runBlocking {
        val paths = mutableListOf<String>()
        val transport = AdminTransport({ path, body -> paths += path
            if (body != null) """{"ok":true}""" else if (path == "registration") """{"registration_mode":"invite_only","registration_open":false}""" else "{}"
        }, { error("no blob") })
        val actions = SelfHostingAdminActions("owner", transport)
        assertFalse(actions.registration().open)
        actions.setMode("open"); actions.selfCheck()
        assertEquals(listOf("registration", "registration", "self-check"), paths)
        assertFailsWith<IllegalArgumentException> { actions.setMode("closed") }
        assertFailsWith<IllegalStateException> { SelfHostingAdminActions("user", transport).invites() }
        Unit
    }
    @Test fun `self check covers every contracted field individually`() {
        assertEquals(27, selfCheckFields.size)
        assertEquals(selfCheckFields.size, selfCheckFields.map { it.group to it.field }.toSet().size)
        assertTrue(selfCheckFields.any { it.group == "public_url" && it.field == "tls" })
        assertTrue(selfCheckFields.any { it.group == "push" && it.field == "delivery" })
    }
}
