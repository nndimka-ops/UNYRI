package com.messenger.app.domain.call

import com.messenger.app.data.api.ApiJson
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.api.CallDto
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CallRingConfirmationContractsTest {
    @Test fun silentRingerDoesNotMakeAPresentedIncomingCallUnreachable() {
        assertFalse(alert(sound = false, vibration = false))
        assertTrue(CallRingConfirmationPolicy.presented(true, true, 4, true, true))
        assertFalse(CallRingConfirmationPolicy.presented(true, true, 4, true, false))
        assertFalse(CallRingConfirmationPolicy.presented(false, true, 4, true, true))
        assertFalse(CallRingConfirmationPolicy.presented(true, false, 4, true, true))
        assertFalse(CallRingConfirmationPolicy.presented(true, true, 2, true, true))
        assertFalse(CallRingConfirmationPolicy.presented(true, true, 4, false, true))
    }
    @Test fun retryClassificationMatchesTheCalleeContracts() {
        assertTrue(CallRingConfirmationPolicy.retry(IOException("offline")))
        assertTrue(CallRingConfirmationPolicy.retry(AuthApiException.Network("offline")))
        for (status in 500..599) {
            assertTrue(CallRingConfirmationPolicy.retry(AuthApiException.Api("server", "failed", status)))
            assertTrue(CallRingConfirmationPolicy.retry(AuthApiException.Network("failed", status)))
        }
        for (status in 200..499) {
            assertFalse("HTTP $status is final", CallRingConfirmationPolicy.retry(AuthApiException.Api("final", "final", status)))
            assertFalse(CallRingConfirmationPolicy.retry(AuthApiException.Network("final", status)))
        }
        assertFalse(CallRingConfirmationPolicy.retry(IllegalStateException("local failure")))
        assertFalse(CallRingConfirmationPolicy.retry(kotlinx.coroutines.CancellationException("cancelled")))
    }

    private fun alert(importance: Int = 4, app: Boolean = true, channel: Boolean = true,
                      sound: Boolean = true, vibration: Boolean = false, dnd: Boolean = true) =
        CallRingConfirmationPolicy.wouldAlert(app, channel, importance, sound, vibration, dnd)

    @Test fun quietOrDisabledChannelsNeverConfirmEvenWithSoundConfigured() {
        assertFalse("NONE", alert(importance = 0))
        assertFalse("MIN", alert(importance = 1))
        assertFalse("LOW", alert(importance = 2))
        assertFalse("channel or channel group disabled", alert(channel = false))
        assertFalse("app notifications disabled", alert(app = false))
    }

    @Test fun defaultAndHighNeedAnAllowedSoundOrVibration() {
        assertTrue("DEFAULT with sound", alert(importance = 3))
        assertTrue("HIGH with sound", alert(importance = 4))
        assertTrue("vibration-only alert", alert(sound = false, vibration = true))
        assertFalse("no alert medium", alert(sound = false, vibration = false))
        assertFalse("DND blocks calls", alert(dnd = false, vibration = true))
    }

    @Test fun dndBypassIsLimitedToPriorityMode() {
        assertTrue(CallRingConfirmationPolicy.dndAllowsCalls(1, false)) // ALL
        assertTrue(CallRingConfirmationPolicy.dndAllowsCalls(2, true)) // PRIORITY + bypass
        assertFalse(CallRingConfirmationPolicy.dndAllowsCalls(2, false))
        assertFalse(CallRingConfirmationPolicy.dndAllowsCalls(3, true)) // NONE
        assertFalse(CallRingConfirmationPolicy.dndAllowsCalls(4, true)) // ALARMS
        assertFalse(CallRingConfirmationPolicy.dndAllowsCalls(0, true)) // UNKNOWN
    }

    @Test fun oldAndNewCallRowsDecodeWithoutInventingAnExpectation() {
        val old = """{"id":"call","caller_user_id":"me","callee_user_id":"peer","media_kind":"audio","state":"ringing","created_at":"now"}"""
        val row = ApiJson.decodeFromString<CallDto>(old)
        assertNull(row.peerRingingExpected)
        assertNull(row.peerRinging)
        val new = ApiJson.decodeFromString<CallDto>(old.dropLast(1) + """, "peer_ringing_expected":true,"peer_ringing":false,"future":1}""")
        assertEquals(true, new.peerRingingExpected)
        assertEquals(false, new.peerRinging)
        assertEquals(true, ApiJson.decodeFromString<CallDto>(old.dropLast(1) + """, "peer_ringing":true}""").peerRinging)
    }
}
