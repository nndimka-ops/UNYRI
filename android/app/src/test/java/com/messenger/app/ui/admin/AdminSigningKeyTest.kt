package com.messenger.app.ui.admin

import kotlin.test.*

class AdminSigningKeyTest {
    // Public test vector, never a real administrator credential.
    private val code = "0123456789ABCDEFGHJKMNPQRSTVWXZS"
    @Test fun `public pin survives uncertain enrollment and rotation without accepting unknown keys`() {
        assertTrue(AdminSigningKey.acceptsPin(null,null,"server",null))
        assertTrue(AdminSigningKey.acceptsPin(null,"server|new","server",null))
        assertTrue(AdminSigningKey.acceptsPin(null,"server|new","server","new"))
        assertFalse(AdminSigningKey.acceptsPin(null,"server|new","server","attacker"))
        assertTrue(AdminSigningKey.acceptsPin("server|old","server|new","server","old"))
        assertTrue(AdminSigningKey.acceptsPin("server|old","server|new","server","new"))
        assertFalse(AdminSigningKey.acceptsPin("server|old",null,"server",null))
        assertFalse(AdminSigningKey.acceptsPin("server|old",null,"other","old"))
    }
    @Test fun `paper code has checksum and interoperable Ed25519 result`() {
        assertTrue(AdminSigningKey.valid(code))
        assertTrue(AdminSigningKey.valid(code.chunked(8).joinToString("-").lowercase()))
        assertFalse(AdminSigningKey.valid(code.dropLast(1)+"0"))
        assertFalse(AdminSigningKey.valid(code+"A"))
        assertEquals("Szyj6KzonzQXpYgcyGK7U2ECJMcry7zql3_gC1muVPM",AdminSigningKey.publicKey(code))
        assertEquals("s5Xn31303SSBmTWdT7SJe_ILhCOcveaT1V5o93jjF9TLbo0w2RA380ktPVPks7KyEYAPh5So4RLotc04qgZ3AQ",AdminSigningKey.sign(code,"COCO test vector"))
    }
    @Test fun `generated codes validate and malformed context is rejected`() {
        val values=(1..32).map {AdminSigningKey.generate()}
        assertEquals(32,values.toSet().size)
        assertTrue(values.all {AdminSigningKey.valid(it) && it.split('-').map(String::length)==listOf(8,8,8,8)})
        assertFails {AdminSigningKey.publicKey("FFE3-43FD-FDS3-DSD2")}
        assertFails {AdminSigningKey.payload("not-a-server","","","","unlock","","")}
    }
}
