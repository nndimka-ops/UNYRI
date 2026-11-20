package org.unyri.admin.core

import kotlinx.serialization.json.*
import org.junit.Test
import java.time.Instant
import kotlin.test.*

class ConnectionDoctorTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private fun fixture() = wireJson.parseToJsonElement("""{
        "schema":1,"public_origin":"https://home.example.test","public_host":"home.example.test","public_port":443,
        "external_ip":{"address":"203.0.113.8","source":"installer_config","state":"unverified"},
        "lan":{"address":"192.168.0.10","port":443,"source":"default_route","observed_at":"2026-11-18T11:57:00Z","state":"detected"},
        "calls":{"turn_port":3478,"transports":["udp","tcp"],"relay_min":49152,"relay_max":49200,"relay_protocol":"udp"},
        "rooms":{"enabled":false},"ingress":{"mode":"direct"},"hosting":"home_pc"
    }""").jsonObject
    private fun present(a: ConnectionDoctorAddresses? = ConnectionDoctorAddresses.decode(fixture())) =
        ConnectionDoctorPresentation.make(a, "https://active.example.test:443", "Active server", now) { it.toString() }
    private fun keys(rows: List<ConnectionDoctorPresentation.Row>) = rows.map { it.key }
    private fun change(section: String, vararg fields: Pair<String, JsonElement>): ConnectionDoctorAddresses? {
        val f = fixture()
        return ConnectionDoctorAddresses.decode(JsonObject(f + (section to JsonObject(f[section]!!.jsonObject + fields))))
    }

    @Test fun absentAndMalformedObjectsDoNotBreakOlderSelfCheck() {
        assertEquals(ConnectionDoctorPresentation.unsupported, present(null).internet.single().key)
        assertNull(ConnectionDoctorAddresses.decode(JsonNull))
        for (a in listOf(JsonPrimitive("bad"), jsonBody("schema" to 2), jsonBody("schema" to "1"))) {
            assertEquals(ConnectionDoctorPresentation.unavailable, present(ConnectionDoctorAddresses.decode(a)).internet.single().key)
        }
    }
    @Test fun explicit443AndCanonicalHomeCopyKeepTlsIdentity() {
        val p = present()
        assertTrue(p.internet.any { it.key == "Port: %@" && it.arguments == listOf("443") })
        assertEquals("https://home.example.test:443", p.publicCopy)
        assertEquals("https://home.example.test\nhome.example.test:443\n192.168.0.10:443", p.homeCopy)
        assertFalse(p.homeCopy!!.contains("https://192.168"))
        assertTrue(p.internet.any { it.key == "Set during installation. Not checked now." })
        assertTrue(p.internet.any { it.key == "Personal call ports: %@ UDP and TCP; %@–%@ UDP." && it.arguments == listOf("3478", "49152", "49200") })
        assertTrue(p.home.any { it.key == "Detected during the check at %@." && it.arguments == listOf("2026-11-18T11:57:00Z") })
        assertTrue(p.connection.any { it.arguments == listOf("active.example.test", "443") })
        assertTrue(keys(p.connection).contains(ConnectionDoctorPresentation.pathUnknown))
    }
    @Test fun eachLanStateHasAnHonestSentence() {
        val expected = mapOf("unknown" to "Home address not found. Connect the PC to your router and check again.",
            "invalid" to "The home address may have changed. Update it in the PC app.",
            "not_applicable" to "This server is in a data centre. There is no home address for phones.",
            "future" to ConnectionDoctorPresentation.unavailable)
        expected.forEach { (state, sentence) ->
            val p = present(change("lan", "state" to JsonPrimitive(state)))
            assertEquals(listOf(sentence), keys(p.home)); assertNull(p.homeCopy)
        }
        assertTrue(keys(present().home).contains("The home address was found. Secure home access is not configured yet."))
        assertEquals(listOf(ConnectionDoctorPresentation.unavailable), keys(present(change("lan", "source" to JsonPrimitive("vpn"))).home))
    }
    @Test fun staleMissingAndFutureLanTimeAreWarningsWithoutClocks() {
        for (time in listOf(JsonNull, JsonPrimitive("wrong"), JsonPrimitive(now.minusSeconds(86401).toString()), JsonPrimitive(now.plusSeconds(301).toString()))) {
            assertTrue(keys(present(change("lan", "observed_at" to time)).home).contains("The home address may have changed. Update it in the PC app."))
        }
        assertFalse(keys(present().home).contains("The home address may have changed. Update it in the PC app."))
    }
    @Test fun eachExternalStateAndSourceIsBounded() {
        for (state in listOf("unknown", "invalid")) assertTrue(keys(present(change("external_ip", "state" to JsonPrimitive(state))).internet).contains("The external IP is not known yet."))
        for (state in listOf("observed", "future")) assertTrue(keys(present(change("external_ip", "state" to JsonPrimitive(state))).internet).contains(ConnectionDoctorPresentation.unavailable))
        assertTrue(keys(present(change("external_ip", "source" to JsonPrimitive("future"))).internet).contains(ConnectionDoctorPresentation.unavailable))
        for (ip in listOf("127.0.0.1", "192.168.0.10", "100.64.0.1", "fe80::1", "::ffff:203.0.113.8", "secret")) {
            assertNull(change("external_ip", "address" to JsonPrimitive(ip))!!.external.address)
        }
    }
    @Test fun vpsSuppressesPrivateCloudAddressAndHostingIsNotInferredFromOwnership() {
        val a = ConnectionDoctorAddresses.decode(fixture())!!
        val vps = present(a.copy(hosting = "vps"))
        assertEquals(listOf("This server is in a data centre. There is no home address for phones."), keys(vps.home))
        assertNull(vps.homeCopy)
        assertTrue(keys(vps.connection).contains("This is your server in a data centre."))
        assertTrue(keys(present(a.copy(hosting = null)).connection).contains("You manage this server. Its installation location is not yet known."))
        val offline = ConnectionDoctorPresentation.make(a, null, now = now) { it.toString() }
        assertFalse(keys(offline.connection).contains(ConnectionDoctorPresentation.pathUnknown))
    }
    @Test fun ipv6BracketsAndNondefaultPorts() {
        val f = fixture()
        val a = ConnectionDoctorAddresses.decode(JsonObject(f + mapOf(
            "public_origin" to JsonPrimitive("https://[2001:db8::8]:8443/"), "public_host" to JsonPrimitive("[2001:db8::8]"), "public_port" to JsonPrimitive(8443),
            "lan" to jsonBody("address" to "fd12:3456::1", "port" to 443, "source" to "default_route", "state" to "detected", "observed_at" to now.toString()))))
        assertEquals("https://[2001:db8::8]:8443", present(a).publicCopy)
        assertEquals("https://[2001:db8::8]:8443\n[2001:db8::8]:443\n[fd12:3456::1]:443", present(a).homeCopy)
    }
    @Test fun invalidValuesNeverBecomeCopyTargets() {
        for (raw in listOf("http://example.test", "https://localhost", "https://127.0.0.1", "https://172.30.219.10", "https://example.test:0",
            "https://example.test:65536", "https://user:secret@example.test", "https://example.test/a/..", "https://example.test/?secret=x", "https://bad_name.test")) {
            assertNull(DoctorValidation.origin(raw), raw)
        }
        for (ip in listOf("172.17.0.2", "172.30.219.10", "fe80::1", "203.0.113.8", "::ffff:192.168.0.1", "192.168.00.1")) assertNull(DoctorValidation.lanIP(ip), ip)
        assertNull(DoctorValidation.lanIP("10.20.0.5", "10.20.0.0/24"))
        assertEquals("fd12:3456::1", DoctorValidation.lanIP("FD12:3456::0001"))
        assertNull(present(ConnectionDoctorAddresses.decode(JsonObject(fixture() + ("public_port" to JsonPrimitive(444))))).publicCopy)
    }
    @Test fun unknownEnumsDoNotProveAConnectionRoute() {
        val a = ConnectionDoctorAddresses.decode(fixture())!!
        for (unknown in listOf(a.copy(ingress = "future"), a.copy(ingress = "tunnel", provider = "future"),
            a.copy(calls = a.calls.copy(relayProtocol = "future")), a.copy(calls = a.calls.copy(transports = setOf("future"))))) {
            assertTrue(keys(present(unknown).internet).contains(ConnectionDoctorPresentation.unavailable))
        }
        assertEquals(ConnectionDoctorAddresses.UNSUPPORTED, change("calls", "relay_protocol" to JsonPrimitive("future"))!!.calls.relayProtocol)
        assertEquals(ConnectionDoctorAddresses.UNSUPPORTED, change("lan", "state" to JsonPrimitive("future"))!!.lan.state)
        val tunnel = present(a.copy(ingress = "tunnel", provider = "cloudflare"))
        assertTrue(keys(tunnel.internet).contains("Messages work through this tunnel. Calls may fail to connect."))
        assertTrue(keys(tunnel.connection).contains(ConnectionDoctorPresentation.pathUnknown))
        assertTrue(keys(present(a.copy(hosting = "future")).connection).contains(ConnectionDoctorPresentation.unavailable))
    }
}
