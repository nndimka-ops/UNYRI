package com.messenger.app.conference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConferenceMicrophonePublicationTest {
    private class Source { var started = false; var disposed = 0 }
    @Test fun `muted initial entry and recovery validate encryption but never create or publish a source`() = runTest {
        var checks = 0; var creates = 0; var publications = 0
        repeat(2) {
            publishConferenceMicrophoneIfAllowed(validate = { checks++ }, blocked = { false }, requested = { false },
                createDisabled = { creates++; Source() }, publish = { publications++; true }, onPublished = { error("No microphone intent") },
                silence = { error("User mute is not an owner restriction") }, isOwned = { true }, dispose = { it.disposed++ })
        }
        assertEquals(2, checks); assertEquals(0, creates); assertEquals(0, publications)
    }
    @Test fun `only an explicit current microphone intent creates after muted recovery and host denial still wins`() = runTest {
        var requested = false; var blocked = false; var creates = 0; var publishes = 0; var starts = 0; var silences = 0
        suspend fun attempt() { publishConferenceMicrophoneIfAllowed(validate = {}, blocked = { blocked }, requested = { requested },
            createDisabled = { creates++; Source() }, publish = { publishes++; true }, onPublished = { it.started = true; starts++ },
            silence = { silences++ }, isOwned = { true }, dispose = { it.disposed++ }) }
        attempt(); requested = true; blocked = true; attempt()
        assertEquals(0, creates); assertEquals(0, publishes); assertEquals(1, silences)
        blocked = false; requested = false; attempt() // Permission restoration is not user consent.
        assertEquals(0, creates)
        requested = true; attempt()
        assertEquals(1, creates); assertEquals(1, publishes); assertEquals(1, starts)
    }
    @Test fun `allowed publication retains SDK ownership and applies explicit capture intent`() = runTest {
        val source = Source(); var checks = 0
        publishConferenceMicrophoneIfAllowed(validate = { checks++ }, blocked = { false }, createDisabled = { source },
            publish = { true }, onPublished = { it.started = true }, silence = { error("Not restricted") },
            isOwned = { true }, dispose = { it.disposed++ })
        assertTrue(source.started); assertEquals(0, source.disposed); assertEquals(2, checks)
    }
    @Test fun `restricted admission never creates or publishes a microphone source`() = runTest {
        var creates = 0; var publications = 0; var silences = 0; var checks = 0
        publishConferenceMicrophoneIfAllowed(validate = { checks++ }, blocked = { true },
            createDisabled = { creates++; Source() }, publish = { publications++; true }, onPublished = { it.started = true },
            silence = { silences++ }, isOwned = { false }, dispose = { it.disposed++ })
        assertEquals(0, creates); assertEquals(0, publications); assertEquals(1, silences); assertEquals(1, checks)
    }
    @Test fun `restriction arriving during publication is a muted success and unowned source is released`() = runTest {
        val gate = CompletableDeferred<Boolean>(); val source = Source()
        var blocked = false; var silences = 0; var checks = 0
        val pending = async {
            publishConferenceMicrophoneIfAllowed(validate = { checks++ }, blocked = { blocked },
                createDisabled = { source }, publish = { gate.await() }, onPublished = { it.started = true },
                silence = { silences++ }, isOwned = { false }, dispose = { it.disposed++ })
        }
        runCurrent(); blocked = true; gate.complete(false); pending.await()
        assertFalse(source.started); assertEquals(1, source.disposed); assertEquals(1, silences); assertEquals(2, checks)
        blocked = false // Allowing after the operation never starts an old source.
        assertFalse(source.started)
    }
    @Test fun `published source restricted while awaiting stays owned and disabled`() = runTest {
        val source = Source(); var blocked = false; var silenced = false
        publishConferenceMicrophoneIfAllowed(validate = {}, blocked = { blocked }, createDisabled = { source },
            publish = { blocked = true; true }, onPublished = { it.started = true }, silence = { silenced = true },
            isOwned = { true }, dispose = { it.disposed++ })
        assertTrue(silenced); assertFalse(source.started); assertEquals(0, source.disposed)
    }
    @Test fun `unrelated publication failure remains a failure and disposes unowned source`() = runTest {
        val source = Source()
        assertFailsWith<IllegalStateException> {
            publishConferenceMicrophoneIfAllowed(validate = {}, blocked = { false }, createDisabled = { source }, publish = { false },
                onPublished = { it.started = true }, silence = { error("Not a restriction") }, isOwned = { false }, dispose = { it.disposed++ })
        }
        assertEquals(1, source.disposed); assertFalse(source.started)
    }
    @Test fun `network exception is not disguised as an owner restriction`() = runTest {
        val source = Source(); var blocked = false
        assertFailsWith<java.io.IOException> {
            publishConferenceMicrophoneIfAllowed(validate = {}, blocked = { blocked }, createDisabled = { source },
                publish = { blocked = true; throw java.io.IOException() }, onPublished = { it.started = true },
                silence = { error("Network error must propagate") }, isOwned = { false }, dispose = { it.disposed++ })
        }
        assertFalse(source.started); assertEquals(1, source.disposed)
    }
    @Test fun `session cancellation after await wins over concurrent owner restriction`() = runTest {
        val source = Source(); var checks = 0; var blocked = false
        assertFailsWith<CancellationException> {
            publishConferenceMicrophoneIfAllowed(validate = { if (++checks > 1) throw CancellationException() }, blocked = { blocked },
                createDisabled = { source }, publish = { blocked = true; false }, onPublished = { it.started = true },
                silence = { error("Old session must not update new state") }, isOwned = { false }, dispose = { it.disposed++ })
        }
        assertEquals(1, source.disposed)
    }
    @Test fun `encryption failure after await cannot be swallowed as muted success`() = runTest {
        val source = Source(); var checks = 0; var blocked = false
        assertFailsWith<SecurityException> {
            publishConferenceMicrophoneIfAllowed(validate = { if (++checks > 1) throw SecurityException() }, blocked = { blocked },
                createDisabled = { source }, publish = { blocked = true; false }, onPublished = { it.started = true },
                silence = { error("Encryption must still fail closed") }, isOwned = { false }, dispose = { it.disposed++ })
        }
        assertFalse(source.started); assertEquals(1, source.disposed)
    }
}
