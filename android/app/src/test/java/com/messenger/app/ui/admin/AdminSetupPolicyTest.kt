package com.messenger.app.ui.admin

import kotlin.test.*
class AdminSetupPolicyTest {
    @Test fun `factor policy is strict with legacy both fallback`() {
        assertEquals(AdminFactorPolicy.Both, AdminFactorPolicy.fromStatus(1, null))
        assertEquals(AdminFactorPolicy.Both, AdminFactorPolicy.fromStatus(0, "authenticator"))
        assertEquals(AdminFactorPolicy.Authenticator, AdminFactorPolicy.fromStatus(2, "authenticator"))
        assertEquals(AdminFactorPolicy.PaperKey, AdminFactorPolicy.fromStatus(2, "paper_key"))
        assertEquals(AdminFactorPolicy.Both, AdminFactorPolicy.fromStatus(2, "both"))
        listOf(null, "", "password", "none", "BOTH").forEach { assertNull(AdminFactorPolicy.fromStatus(2, it)) }
        assertTrue(AdminFactorPolicy.Authenticator.authenticator)
        assertFalse(AdminFactorPolicy.Authenticator.paper)
        assertTrue(AdminFactorPolicy.PaperKey.paper)
        assertFalse(AdminFactorPolicy.PaperKey.authenticator)
    }
    @Test fun `authenticator-only still pins server identity`() {
        assertTrue(AdminFactorPolicy.acceptsServerPin(null, null, "server"))
        assertTrue(AdminFactorPolicy.acceptsServerPin("server|old-paper", null, "server"))
        assertTrue(AdminFactorPolicy.acceptsServerPin(null, "server|pending-paper", "server"))
        assertFalse(AdminFactorPolicy.acceptsServerPin("other|old-paper", null, "server"))
        assertFalse(AdminFactorPolicy.acceptsServerPin(null, "other|pending-paper", "server"))
    }
    @Test fun `only OTP or explicit recovery format can reach unlock`() {
        assertTrue(AdminSetupPolicy.validCode("001234", false))
        assertFalse(AdminSetupPolicy.validCode("12345", false))
        assertFalse(AdminSetupPolicy.validCode("１２３４５６", false))
        assertFalse(AdminSetupPolicy.validCode("A".repeat(32), false))
        assertFalse(AdminSetupPolicy.validCode("A".repeat(43), false))
        assertTrue(AdminSetupPolicy.validCode("A".repeat(42) + "_", true))
        assertFalse(AdminSetupPolicy.validCode("123456", true))
        assertFalse(AdminSetupPolicy.validCode("A".repeat(42) + "=", true))
    }
    @Test fun `authenticator switch retains draft only within bounded monotonic window`() {
        assertFalse(AdminSetupPolicy.expired(null,900000))
        assertFalse(AdminSetupPolicy.expired(1000,180999))
        assertTrue(AdminSetupPolicy.expired(1000,181000))
        assertTrue(AdminSetupPolicy.expired(1000,999))
    }
}
