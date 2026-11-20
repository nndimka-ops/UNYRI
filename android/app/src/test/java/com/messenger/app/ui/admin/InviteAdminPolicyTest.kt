package com.messenger.app.ui.admin

import com.messenger.app.data.selfhost.ServerInviteLink
import java.net.URI
import kotlin.test.*

class InviteAdminPolicyTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val other = "00000000-0000-4000-8000-000000000002"
    private fun row(id: String) = InviteRow(id, "active", 0, 1, "2026-12-01T00:00:00Z")

    @Test fun `defaults and limits match the server without accepting partial or signed input`() {
        assertEquals(1 to 7, InviteAdminPolicy.limits(InviteAdminPolicy.defaultUses.toString(), InviteAdminPolicy.defaultDays.toString()))
        assertEquals(10000 to 365, InviteAdminPolicy.limits("10000", "365"))
        assertEquals(1 to 1, InviteAdminPolicy.limits("1", "1"))
        for ((uses, days) in listOf("" to "7", "1" to "", "0" to "7", "10001" to "7", "1" to "0",
            "1" to "366", "+1" to "7", "1 " to "7", "1.5" to "7", "١" to "7", "99999999999999" to "7"))
            assertNull(InviteAdminPolicy.limits(uses, days), "$uses / $days")
    }

    @Test fun `pagination retains prior rows deduplicates overlap and rejects repeating cursors`() {
        val first = InvitePage(listOf(row(id)), id)
        val merged = InviteAdminPolicy.append(first, InvitePage(listOf(row(id), row(other))), id)
        assertEquals(listOf(id, other), merged.items.map { it.id })
        assertNull(merged.nextBefore)
        assertFailsWith<IllegalArgumentException> { InviteAdminPolicy.append(first, InvitePage(emptyList(), id), id) }
    }

    @Test fun `created code shares through the existing signup link without placing the code in the query`() {
        val code = "$id.ABCDEFGHJKLMNPQR"
        val link = ServerInviteLink.create("https://fixture.test", code)
        val parsed = assertNotNull(ServerInviteLink.parse(link))
        assertEquals("https://fixture.test", parsed.origin)
        assertEquals(code, parsed.code)
        assertNull(URI(link).rawQuery)
        assertFalse(parsed.toString().contains(code))
    }
}
