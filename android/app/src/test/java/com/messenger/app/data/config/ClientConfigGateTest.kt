package com.messenger.app.data.config

import com.messenger.app.data.api.ClientPlatformPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppVersionTest {
    @Test fun numericPrefixAndInvalidVersionsMirrorIosContracts() {
        assertEquals(listOf(0, 4, 13, 0), AppVersion.components("0.4.13"))
        assertEquals(listOf(1, 2, 3, 4), AppVersion.components("1.2.3.4"))
        assertEquals(listOf(0, 2, 1, 0), AppVersion.components("0.2.1-alpha.1"))
        assertEquals(listOf(7, 0, 0, 0), AppVersion.components("7"))
        assertEquals(listOf(1, 0, 0, 0), AppVersion.components("1.0+build-7"))
        for (bad in listOf("", "1..2", "v1", "1.2.3.4.5", "1234567.0", "1.0-", "1.0 beta", "1.0-" + "a".repeat(33), "١.٠", "1.0+a+b")) {
            assertNull(AppVersion.components(bad), bad)
        }
        assertEquals(1, AppVersion.compare("0.4.13", "0.4.9"))
        assertEquals(0, AppVersion.compare("1.0", "1.0.0"))
        assertEquals(0, AppVersion.compare("0.2.1-alpha", "0.2.1"))
        assertNull(AppVersion.compare("garbage", "1.0"))
        assertNull(AppVersion.compare("1.0", "garbage"))
    }

    @Test fun updateLinksAreOnlyTrustedHttpsPlayStoreLinks() {
        val trusted = "https://play.google.com/store/apps/details?id=com.messenger.app"
        assertEquals(trusted, AppVersion.trustedUpdateUrl(trusted, "com.messenger.app", ""))
        for (raw in listOf(null, "http://play.google.com/store", "https://play.google.com.evil.example/x", "https://user@play.google.com/x",
            "https://play.google.com:443/x", "https://play.google.com:8443/x", "https://example.com/update", "https://apps.apple.com/app/id123",
            "javascript:alert(1)", "https://play.google.com/" + "x".repeat(512))) assertNull(AppVersion.trustedUpdateUrl(raw, "com.messenger.app", ""), raw)
    }

    @Test fun originKeysIgnorePathsAndRetainSchemeHostAndPort() {
        assertEquals("https://example.com:8443", AppVersion.originKey("HTTPS://EXAMPLE.com:8443/path?q=1"))
        assertNotEquals(AppVersion.originKey("http://example.com"), AppVersion.originKey("https://example.com"))
        assertFails { AppVersion.originKey("https://user@example.com") }
    }
}

private class MemoryConfigStorage : ClientConfigStorage {
    var cache = emptyMap<String, ClientConfigEntry>()
    var hidden = emptyMap<String, String>()
    override fun entries() = cache
    override fun saveEntries(entries: Map<String, ClientConfigEntry>) { cache = entries }
    override fun dismissed() = hidden
    override fun saveDismissed(versions: Map<String, String>) { hidden = versions }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClientConfigGateTest {
    @Test fun offlineRetainsBlockingCacheAndColdStartStillFetches() = runTest {
        val storage = MemoryConfigStorage().apply { cache = mapOf("https://a.example" to ClientConfigEntry(0, ClientPlatformPolicy("2", "3"))) }
        var calls = 0
        val gate = ClientConfigGate("1", "https://a.example", storage, this, { calls++; error("offline") }, callInProgress = { true })
        assertNotNull(gate.decision.value.required, "cached requirement applies even during a cold-start call")
        assertEquals(ClientConfigGate.Outcome.UNREACHABLE, gate.refresh("https://a.example"))
        assertNotNull(gate.decision.value.required)
        assertEquals(1, calls)
        val uncached = ClientConfigGate("1", "https://b.example", storage, this, { error("offline") })
        uncached.refresh("https://b.example")
        assertNull(uncached.decision.value.required)
    }

    @Test fun sharedFlightThrottleForceAndOldServerClearDecision() = runTest {
        val storage = MemoryConfigStorage()
        var time = 1L
        var calls = 0
        var policy: ClientPlatformPolicy? = ClientPlatformPolicy("2", "3")
        val release = CompletableDeferred<Unit>()
        val gate = ClientConfigGate("1", "https://a.example", storage, this, { calls++; release.await(); policy }, now = { time })
        val first = async { gate.refresh("https://a.example") }
        val second = async { gate.refresh("https://a.example", force = true) }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(ClientConfigGate.Outcome.APPLIED, first.await())
        assertEquals(ClientConfigGate.Outcome.APPLIED, second.await())
        assertNotNull(gate.decision.value.required)
        assertEquals(ClientConfigGate.Outcome.THROTTLED, gate.refresh("https://a.example"))
        policy = null // old server endpoint
        gate.refresh("https://a.example", force = true)
        assertNull(gate.decision.value.required)
        time += ClientConfigGate.THROTTLE_MS
        gate.refresh("https://a.example")
        assertEquals(3, calls)
    }

    @Test fun firstColdFetchAppliesImmediatelyEvenWithARestoredCall() = runTest {
        val gate = ClientConfigGate("1", "https://a.example", MemoryConfigStorage(), this, { ClientPlatformPolicy("2", "2") }, { true })
        gate.refresh("https://a.example")
        assertNotNull(gate.decision.value.required)
    }
    @Test fun aNewRequirementWaitsForNextForegroundAfterCall() = runTest {
        var active = true
        var policy: ClientPlatformPolicy? = null
        val gate = ClientConfigGate("1", "https://a.example", MemoryConfigStorage(), this, { policy }, { active })
        gate.refresh("https://a.example")
        policy = ClientPlatformPolicy("2", "2")
        gate.refresh("https://a.example", force = true)
        assertNull(gate.decision.value.required)
        active = false
        assertNull(gate.decision.value.required, "call ending alone does not publish the gate")
        gate.refresh("https://a.example")
        assertNotNull(gate.decision.value.required)
    }

    @Test fun perOriginCacheDoesNotLeakAndIsBoundedToEight() = runTest {
        val storage = MemoryConfigStorage()
        var time = 0L
        val gate = ClientConfigGate("1", "https://a.example", storage, this,
            { if (it == "https://offline.example") error("offline") else ClientPlatformPolicy("2", "2") }, now = { ++time })
        gate.refresh("https://a.example")
        gate.refresh("https://offline.example")
        assertNull(gate.decision.value.required)
        for (i in 0..9) gate.refresh("https://server$i.example")
        assertEquals(8, storage.cache.size)
        assertTrue(storage.cache.containsKey("https://server9.example"))
        assertFalse(storage.cache.containsKey("https://a.example"))
    }

    @Test fun switchingOriginWhileRequestRunsNeverAppliesItsResponseToAnotherServer() = runTest {
        val release = CompletableDeferred<Unit>()
        val storage = MemoryConfigStorage()
        val gate = ClientConfigGate("1", "https://a.example", storage, this, {
            if (it == "https://a.example") { release.await(); ClientPlatformPolicy("2", "2") } else null
        })
        val first = async { gate.refresh("https://a.example") }
        runCurrent()
        gate.refresh("https://b.example")
        release.complete(Unit)
        first.await()
        assertNull(gate.decision.value.required)
        assertNotNull(storage.cache["https://a.example"])
    }

    @Test fun recommendationDismissalIsPersistedPerVersionPerOriginAndInvalidNeverBlocks() = runTest {
        val storage = MemoryConfigStorage()
        var policy = ClientPlatformPolicy("garbage", "2", "https://evil.example/update")
        val gate = ClientConfigGate("1", "https://a.example", storage, this, { policy })
        gate.refresh("https://a.example")
        assertNull(gate.decision.value.required)
        assertNotNull(gate.decision.value.recommended)
        assertNull(gate.decision.value.recommended?.updateUrl)
        gate.dismissRecommendation()
        gate.recheck()
        assertNull(gate.decision.value.recommended)
        policy = ClientPlatformPolicy("garbage", "3")
        gate.recheck()
        assertEquals("3", gate.decision.value.recommended?.version)
        gate.dismissRecommendation()
        gate.refresh("https://b.example")
        assertNotNull(gate.decision.value.recommended)
        val cold = ClientConfigGate("1", "https://a.example", storage, this, { policy })
        assertNull(cold.decision.value.recommended)
    }
}
