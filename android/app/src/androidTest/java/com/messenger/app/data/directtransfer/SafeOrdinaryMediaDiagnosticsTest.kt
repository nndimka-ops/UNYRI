package com.messenger.app.data.directtransfer

import org.junit.Assert.*
import org.junit.Test
import livekit.org.webrtc.SessionDescription

class SafeOrdinaryMediaDiagnosticsTest {
    @Test fun sessionInheritancePortZeroAndInactiveArePreservedWithoutPrivateLines() {
        val raw = """v=0
            |o=- 123 456 IN IP4 198.51.100.7
            |a=recvonly
            |a=ice-ufrag:PRIVATE_UFRAG
            |a=ice-pwd:PRIVATE_PASSWORD
            |a=fingerprint:sha-256 PRIVATE_FINGERPRINT
            |m=audio 9 UDP/TLS/RTP/SAVPF 111
            |a=mid:PRIVATE_AUDIO_MID
            |m=video 9 UDP/TLS/RTP/SAVPF 96
            |a=inactive
            |a=msid:PRIVATE_STREAM PRIVATE_TRACK
            |a=candidate:PRIVATE_CANDIDATE 1 udp 1 192.0.2.5 5000 typ host
            |m=video 0 UDP/TLS/RTP/SAVPF 96
            |a=sendonly
        """.trimMargin()
        val summary = requireNotNull(SafeOrdinaryMediaDiagnostics.sdpSummary(SessionDescription(SessionDescription.Type.OFFER, raw)))
        assertEquals("recvonly", summary.getString("session_direction"))
        val sections = summary.getJSONArray("sections")
        assertEquals(3, sections.length())
        assertEquals("recvonly", sections.getJSONObject(0).getString("effective_direction"))
        assertEquals("inactive", sections.getJSONObject(1).getString("effective_direction"))
        assertEquals(9, sections.getJSONObject(1).getInt("port"))
        assertEquals("sendonly", sections.getJSONObject(2).getString("effective_direction"))
        assertEquals(0, sections.getJSONObject(2).getInt("port"))
        val encoded = summary.toString()
        assertFalse(encoded.contains("PRIVATE"));assertFalse(encoded.contains("198.51.100.7"));assertFalse(encoded.contains("192.0.2.5"))
        assertFalse(encoded.contains("ice-pwd"));assertFalse(encoded.contains("fingerprint"));assertFalse(encoded.contains("msid"))
    }

    @Test fun absentDirectionDefaultsSendrecvAndNullDescriptionIsAbsent() {
        assertNull(SafeOrdinaryMediaDiagnostics.sdpSummary(null))
        val summary = requireNotNull(SafeOrdinaryMediaDiagnostics.sdpSummary(SessionDescription(SessionDescription.Type.ANSWER,
            "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")))
        assertEquals("sendrecv", summary.getString("session_direction"))
        assertEquals("sendrecv", summary.getJSONArray("sections").getJSONObject(0).getString("effective_direction"))
        assertEquals("ANSWER", summary.getString("type"))
    }
}
