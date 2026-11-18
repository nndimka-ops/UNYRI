package com.messenger.app.data.api

import com.messenger.buildlogic.ReleaseOriginPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReleaseOriginPolicyTest {
    @Test fun storeBuildOverridesRejectPrivateAndLocalHosts() {
        for (host in listOf("192.168.0.106", "10.0.2.2", "172.16.0.1", "172.31.255.255",
            "127.0.0.1", "127.2.3.4", "0.0.0.0", "169.254.1.1", "100.64.0.1", "198.18.0.1",
            "localhost", "LOCALHOST.", "app.localhost", "localhost.localdomain", "server.local", "server.lan", "server.internal",
            "server.home.arpa", "myserver", "[::]", "[::1]", "[fc00::1]", "[fd12::1]", "[fe80::1]",
            "[fec0::1]", "[ff02::1]", "[::ffff:192.168.0.106]", "[::ffff:127.0.0.1]",
            "2130706433", "127.1", "0177.0.0.1", "0x7f.0.0.1")) {
            assertFalse(host, ReleaseOriginPolicy.isAllowed("https://$host:8443", false))
        }
    }

    @Test fun alphaBuildOverridesKeepExplicitLocalOrigins() {
        for (origin in listOf("https://192.168.0.106:8443", "http://10.0.2.2:8080",
            "https://localhost", "https://[::1]:8443", "https://[fd12::1]", "https://server.local")) {
            assertTrue(origin, ReleaseOriginPolicy.isAllowed(origin, true))
        }
    }

    @Test fun publicHttpsDefaultsAndOverridesAreAllowed() {
        for (origin in listOf("https://unyri.example.com", "https://8.8.8.8:8443/", "https://[2606:4700:4700::1111]")) {
            assertTrue(origin, ReleaseOriginPolicy.isAllowed(origin, false))
        }
        val shared = File("../../shared/server-origin.json").readText()
        val origin = Regex("\"publicDefaultOrigin\"\\s*:\\s*\"([^\"]+)\"").find(shared)!!.groupValues[1]
        assertTrue(ReleaseOriginPolicy.isAllowed(origin, false))
    }

    @Test fun malformedOverridesAndStoreCleartextAreRejected() {
        for (origin in listOf("https://host.example:0", "https://host.example:65536", "https://user@host.example",
            "https://host.example/path", "https://host.example?query", "https://host.example#fragment", "not a uri")) {
            assertFalse(origin, ReleaseOriginPolicy.isAllowed(origin, false))
            assertFalse(origin, ReleaseOriginPolicy.isAllowed(origin, true))
        }
        assertFalse(ReleaseOriginPolicy.isAllowed("http://host.example", false))
    }

    @Test fun gradleUsesTheTestedPolicyBeforeGeneratingBuildConfig() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("require(ReleaseOriginPolicy.isAllowed(releaseApiBaseUrl, alphaLan))"))
        val source = File("src/main/java/com/messenger/app/data/api/ServerEndpoint.kt").readText()
        assertTrue(source.contains("BuildConfig.DEBUG || BuildConfig.LAN_TLS_MIGRATION"))
    }
}
