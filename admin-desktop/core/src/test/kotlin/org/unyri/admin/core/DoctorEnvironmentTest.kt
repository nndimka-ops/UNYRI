package org.unyri.admin.core

import org.junit.Test
import java.io.StringReader
import kotlin.test.*

class DoctorEnvironmentTest {
    private val fixture = """
        UNYRI_DOMAIN=home.example.test
        TURN_EXTERNAL_IP=203.0.113.8
        UNYRI_LAN_ADDRESS=192.168.0.10
        UNYRI_LAN_PORT=443
        UNYRI_LAN_DETECTED_AT=2026-11-18T12:00:00Z
        UNYRI_HOSTING_KIND=home_pc
        UNYRI_INGRESS_MODE=direct
        COMPOSE_PROFILES=
    """.trimIndent()
    @Test fun lookupReadsExactlyNonsecretAllowlist() {
        val requested = mutableListOf<String>()
        val expected = setOf("UNYRI_LAN_ADDRESS", "UNYRI_LAN_PORT", "UNYRI_LAN_DETECTED_AT", "UNYRI_HOSTING_KIND",
            "UNYRI_INGRESS_MODE", "UNYRI_TUNNEL_PROVIDER", "PUBLIC_API_ORIGIN", "TURN_EXTERNAL_IP", "UNYRI_DOMAIN", "COMPOSE_PROFILES", "UNYRI_SUBNET")
        DoctorEnvironment.snapshot { requested += it; null }
        assertEquals(expected, requested.toSet()); assertEquals(expected.size, requested.size)
    }
    @Test fun credentialsAndUnrelatedLinesNeverReachTheModel() {
        val secret = "fixture-only-password-never-retained"
        val extra = "\nPOSTGRES_PASSWORD=$secret\nTURN_SHARED_SECRET=$secret\nLIVEKIT_API_SECRET=$secret\nADMIN_TOKEN=$secret\nUNYRI_SERVER_NAME=\"$secret\""
        val expected = DoctorEnvironment.read(StringReader(fixture))
        val actual = DoctorEnvironment.read(StringReader(fixture + extra))
        assertEquals(expected, actual)
        assertFalse(actual.toString().contains(secret))
        assertEquals("https://home.example.test", actual.publicAddress!!.origin)
        assertEquals("detected", actual.lan.state); assertEquals(false, actual.rooms)
        assertEquals(3478, actual.calls.turnPort)
    }
    @Test fun malformedAllowlistedValuesAreNotRetainedAsErrorsOrAddresses() {
        val bad = "sensitive-invalid-value"
        val a = DoctorEnvironment.read(StringReader("PUBLIC_API_ORIGIN=https://user:$bad@example.test\nTURN_EXTERNAL_IP=$bad\nUNYRI_LAN_ADDRESS=$bad\nUNYRI_LAN_PORT=+443\nUNYRI_LAN_DETECTED_AT=$bad\nUNYRI_HOSTING_KIND=$bad\nUNYRI_INGRESS_MODE=$bad\nUNYRI_TUNNEL_PROVIDER=$bad"))
        assertFalse(a.toString().contains(bad)); assertNull(a.publicAddress); assertTrue(a.publicInvalid)
        assertEquals("invalid", a.lan.state); assertEquals("invalid", a.external.state)
        assertNull(a.hosting); assertNull(a.ingress); assertNull(a.provider)
    }
    @Test fun metadataValidationMatchesServerAndVpsRemovesLan() {
        for (line in listOf("UNYRI_LAN_PORT=0", "UNYRI_LAN_PORT=65536", "UNYRI_LAN_DETECTED_AT=not-a-date", "UNYRI_LAN_ADDRESS=172.17.0.2", "UNYRI_LAN_ADDRESS=172.30.219.10")) {
            val key = line.substringBefore('=')
            val a = DoctorEnvironment.read(StringReader(fixture.lineSequence().filterNot { it.startsWith("$key=") }.joinToString("\n") + "\n$line"))
            assertEquals("invalid", a.lan.state, line)
        }
        val vps = DoctorEnvironment.read(StringReader(fixture.replace("home_pc", "vps")))
        assertEquals(ConnectionDoctorAddresses.LAN(state = "not_applicable"), vps.lan)
        val rooms = DoctorEnvironment.read(StringReader(fixture.replace("COMPOSE_PROFILES=", "COMPOSE_PROFILES=rooms")))
        assertEquals(true, rooms.rooms)
        val trial = DoctorEnvironment.read(StringReader(fixture), fixedManifest = false)
        assertNull(trial.calls.turnPort)
    }
    @Test fun duplicateAndOversizedFilesFailWithFixedMessages() {
        assertFailsWith<IllegalStateException> { DoctorEnvironment.read(StringReader(fixture + "\nTURN_EXTERNAL_IP=203.0.113.9")) }
        assertFailsWith<IllegalStateException> { DoctorEnvironment.read(StringReader("#".repeat(262145))) }
    }
}
