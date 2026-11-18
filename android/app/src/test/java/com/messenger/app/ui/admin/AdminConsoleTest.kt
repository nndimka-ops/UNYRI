package com.messenger.app.ui.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADMIN CONSOLE PACK 3 - native launcher contract.
//
// The device keeps the secure core and nothing else: it decides whether the
// entry exists, and it hands the browser a URL. Everything that could go wrong
// there is a security property, so each one is asserted.
class AdminConsoleTest {

    private val handoff = "A".repeat(AdminConsolePolicy.HandoffLength)

    @Test
    fun `the console entry exists only for a role the server granted`() {
        assertTrue(AdminConsolePolicy.entryVisible(AdminNavigation.OWNER))
        assertTrue(AdminConsolePolicy.entryVisible(AdminNavigation.MODERATOR))
        assertFalse(AdminConsolePolicy.entryVisible(null))
        assertFalse(AdminConsolePolicy.entryVisible(""))
        assertFalse(AdminConsolePolicy.entryVisible("user"))
        assertFalse(AdminConsolePolicy.entryVisible("admin"))
        // An unknown role must not be able to mint a handoff either.
        assertFalse(AdminConsolePolicy.canMintHandoff("superuser"))
        assertTrue(AdminConsolePolicy.canMintHandoff(AdminNavigation.OWNER))
    }

    @Test
    fun `the handoff travels in the fragment and never in the query`() {
        val url = AdminConsolePolicy.consoleUrl("https://coco.example", handoff)
        assertEquals("https://coco.example/administration#handoff=$handoff", url)
        // A fragment is never sent to the server, so it cannot be logged by a
        // reverse proxy and cannot leak through a Referer header.
        assertFalse(url!!.contains("?handoff"))
        assertFalse(url.contains("token"))
        assertFalse(url.contains("admin_token"))
    }

    @Test
    fun `a trailing slash or a padded base still produces one clean path`() {
        assertEquals(
            "https://coco.example/administration#handoff=$handoff",
            AdminConsolePolicy.consoleUrl("https://coco.example/", handoff),
        )
        assertEquals(
            "https://coco.example:8443/administration#handoff=$handoff",
            AdminConsolePolicy.consoleUrl("  https://coco.example:8443//  ", handoff),
        )
    }

    @Test
    fun `cleartext administration is never offered`() {
        assertNull(AdminConsolePolicy.consoleUrl("http://coco.example", handoff))
        assertNull(AdminConsolePolicy.consoleUrl("coco.example", handoff))
        assertNull(AdminConsolePolicy.consoleUrl("", handoff))
    }

    @Test
    fun `a malformed handoff is refused instead of being opened`() {
        // Too short, too long, empty and non-base64url values all fail closed, so
        // a truncated or tampered value shows a localized failure and a retry
        // rather than a browser page that cannot work.
        assertNull(AdminConsolePolicy.consoleUrl("https://coco.example", ""))
        assertNull(AdminConsolePolicy.consoleUrl("https://coco.example", "A".repeat(42)))
        assertNull(AdminConsolePolicy.consoleUrl("https://coco.example", "A".repeat(44)))
        assertNull(AdminConsolePolicy.consoleUrl("https://coco.example", "$handoff/../x"))
        assertNull(AdminConsolePolicy.consoleUrl("https://coco.example", "$handoff\n"))
        assertNull(AdminConsolePolicy.consoleUrl("https://coco.example", "A".repeat(42) + "="))
    }

    @Test
    fun `a failure is translated and never a raw status code`() {
        assertEquals(AdminPresentation.Failure.Network, AdminConsolePolicy.failure(null))
        assertEquals(AdminPresentation.Failure.Session, AdminConsolePolicy.failure(401))
        assertEquals(AdminPresentation.Failure.RateLimited, AdminConsolePolicy.failure(429))
        assertEquals(AdminPresentation.Failure.Server, AdminConsolePolicy.failure(503))
        assertEquals(AdminPresentation.Failure.Failed, AdminConsolePolicy.failure(404))
    }
}
