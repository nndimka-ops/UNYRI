package com.messenger.app.data.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebRtcCallStatsParserTest {
    @Test
    fun `transport selection and additional diagnostic counters use one full report`() {
        val parsed = WebRtcCallStatsParser.parse(listOf(
            entry("transport", "transport", "selectedCandidatePairId" to "new-pair", "dtlsState" to "connected"),
            entry("old-pair", "candidate-pair", "selected" to true),
            WebRtcStatsEntry("new-pair", "candidate-pair", mapOf("bytesSent" to 1000L, "packetsDiscardedOnSend" to 12L,
                "availableOutgoingBitrate" to 650000.0, "lastPacketReceivedTimestamp" to 4900.0), timestampUs = 5_000_000.0),
            entry("codec", "codec", "mimeType" to "video/H264", "sdpFmtpLine" to "profile-level-id=640c1f"),
            entry("video", "outbound-rtp", "kind" to "video", "codecId" to "codec", "qpSum" to 900L,
                "qualityLimitationDurations" to mapOf("bandwidth" to 1.5), "firCount" to 2L, "remoteId" to "video-report"),
            entry("video-report", "remote-inbound-rtp", "kind" to "video", "roundTripTime" to .03, "roundTripTimeMeasurements" to 3L),
            entry("audio", "inbound-rtp", "kind" to "audio", "jitterBufferMinimumDelay" to .02,
                "removedSamplesForAcceleration" to 40L, "jitterBufferEmittedCount" to 100L),
            entry("camera", "media-source", "kind" to "video", "width" to 1280L, "height" to 720L, "framesPerSecond" to 30.0),
        ))
        assertEquals("new-pair", parsed.selectedCandidatePair?.id)
        assertEquals(true, parsed.dtlsConnected)
        assertEquals(100.0, parsed.selectedCandidatePair?.sinceLastPacketMs)
        assertEquals(12L, parsed.selectedCandidatePair?.packetsDiscardedOnSend)
        assertEquals(131.0, parsed.videoOutbound?.codecCode)
        assertEquals(1.5, parsed.videoOutbound?.bandwidthLimitedSeconds)
        assertEquals(2L, parsed.videoOutbound?.keyFrameRequests)
        assertEquals(.03, parsed.videoRemoteInbound?.roundTripTimeSeconds)
        assertEquals(.02, parsed.audioInbound?.jitterBufferMinimumDelay)
        assertEquals(30.0, parsed.camera?.framesPerSecond)
    }

    @Test
    fun `selected candidate pair resolves linked candidate types and protocol`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("local-1", "local-candidate", "candidateType" to "host", "protocol" to "udp"),
                entry("remote-1", "remote-candidate", "candidateType" to "srflx", "protocol" to "udp"),
                entry(
                    "pair-1",
                    "candidate-pair",
                    "selected" to true,
                    "localCandidateId" to "local-1",
                    "remoteCandidateId" to "remote-1",
                    "currentRoundTripTime" to 0.031,
                ),
            ),
        )

        assertEquals("host", stats.selectedCandidatePair?.localCandidateType)
        assertEquals("srflx", stats.selectedCandidatePair?.remoteCandidateType)
        assertEquals("udp", stats.selectedCandidatePair?.protocol)
        assertEquals(0.031, stats.selectedCandidatePair?.roundTripTimeSeconds)
    }

    @Test
    fun `relay candidate type is preserved`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("local-1", "local-candidate", "candidateType" to "relay", "protocol" to "tcp"),
                entry("remote-1", "remote-candidate", "candidateType" to "relay"),
                entry("pair-1", "candidate-pair", "selected" to true, "localCandidateId" to "local-1", "remoteCandidateId" to "remote-1"),
            ),
        )

        assertEquals("relay", stats.selectedCandidatePair?.localCandidateType)
        assertEquals("relay", stats.selectedCandidatePair?.remoteCandidateType)
        assertEquals("tcp", stats.selectedCandidatePair?.protocol)
    }

    @Test
    fun `outbound audio ignores video rtp`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("video-out", "outbound-rtp", "kind" to "video", "packetsSent" to 999L, "bytesSent" to 999L),
                entry("audio-out", "outbound-rtp", "kind" to "audio", "packetsSent" to 7L, "bytesSent" to 800L, "audioLevel" to 0.42),
            ),
        )

        assertEquals(7L, stats.audioOutbound?.packetsSent)
        assertEquals(800L, stats.audioOutbound?.bytesSent)
        assertEquals(0.42, stats.audioOutbound?.audioLevel)
    }

    @Test
    fun `inbound audio ignores video rtp`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("video-in", "inbound-rtp", "mediaType" to "video", "packetsReceived" to 999L, "bytesReceived" to 999L),
                entry("audio-in", "inbound-rtp", "mediaType" to "audio", "packetsReceived" to 11L, "bytesReceived" to 1200L, "packetsLost" to 1L, "jitter" to 0.003),
            ),
        )

        assertEquals(11L, stats.audioInbound?.packetsReceived)
        assertEquals(1200L, stats.audioInbound?.bytesReceived)
        assertEquals(1L, stats.audioInbound?.packetsLost)
        assertEquals(0.003, stats.audioInbound?.jitter)
    }

    @Test
    fun `missing optional fields are safe`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("local-1", "local-candidate"),
                entry("remote-1", "remote-candidate"),
                entry("pair-1", "candidate-pair", "nominated" to true, "state" to "succeeded", "localCandidateId" to "local-1", "remoteCandidateId" to "remote-1"),
                entry("audio-out", "outbound-rtp", "kind" to "audio"),
                entry("audio-in", "inbound-rtp", "kind" to "audio"),
            ),
        )

        assertNull(stats.selectedCandidatePair?.localCandidateType)
        assertNull(stats.selectedCandidatePair?.remoteCandidateType)
        assertNull(stats.selectedCandidatePair?.protocol)
        assertNull(stats.selectedCandidatePair?.roundTripTimeSeconds)
        assertNull(stats.audioOutbound?.packetsSent)
        assertNull(stats.audioInbound?.packetsReceived)
    }

    @Test
    fun `empty report is safe`() {
        val stats = WebRtcCallStatsParser.parse(emptyList())

        assertNull(stats.selectedCandidatePair)
        assertNull(stats.audioOutbound)
        assertNull(stats.audioInbound)
        assertNull(stats.videoOutbound)
        assertNull(stats.videoInbound)
    }

    @Test
    fun `video stats parser ignores audio rtp`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("audio-out", "outbound-rtp", "kind" to "audio", "packetsSent" to 7L, "framesEncoded" to 999L),
                entry("audio-in", "inbound-rtp", "kind" to "audio", "packetsReceived" to 11L, "framesDecoded" to 999L),
                entry("video-out", "outbound-rtp", "kind" to "video", "packetsSent" to 100L, "framesEncoded" to 60L),
                entry("video-in", "inbound-rtp", "kind" to "video", "packetsReceived" to 200L, "framesDecoded" to 120L),
            ),
        )

        assertEquals(100L, stats.videoOutbound?.packetsSent)
        assertEquals(60L, stats.videoOutbound?.framesEncoded)
        assertEquals(200L, stats.videoInbound?.packetsReceived)
        assertEquals(120L, stats.videoInbound?.framesDecoded)
    }

    @Test
    fun `video inbound and outbound are parsed correctly with codec`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("codec-1", "codec", "mimeType" to "video/VP8"),
                entry(
                    "video-out",
                    "outbound-rtp",
                    "kind" to "video",
                    "codecId" to "codec-1",
                    "packetsSent" to 1234L,
                    "bytesSent" to 999_999L,
                    "framesEncoded" to 300L,
                    "framesPerSecond" to 30.0,
                    "frameWidth" to 1280L,
                    "frameHeight" to 720L,
                ),
                entry(
                    "video-in",
                    "inbound-rtp",
                    "kind" to "video",
                    "codecId" to "codec-1",
                    "packetsReceived" to 1234L,
                    "bytesReceived" to 888_888L,
                    "packetsLost" to 3L,
                    "framesDecoded" to 300L,
                    "framesPerSecond" to 30.0,
                    "frameWidth" to 1280L,
                    "frameHeight" to 720L,
                ),
            ),
        )

        val out = stats.videoOutbound!!
        assertEquals(1234L, out.packetsSent)
        assertEquals(999_999L, out.bytesSent)
        assertEquals(300L, out.framesEncoded)
        assertEquals(30.0, out.framesPerSecond)
        assertEquals(1280L, out.frameWidth)
        assertEquals(720L, out.frameHeight)
        assertEquals("video/VP8", out.codecName)

        val inbound = stats.videoInbound!!
        assertEquals(1234L, inbound.packetsReceived)
        assertEquals(888_888L, inbound.bytesReceived)
        assertEquals(3L, inbound.packetsLost)
        assertEquals(300L, inbound.framesDecoded)
        assertEquals(30.0, inbound.framesPerSecond)
        assertEquals("video/VP8", inbound.codecName)
    }

    @Test
    fun `missing video fields are safe`() {
        val stats = WebRtcCallStatsParser.parse(
            listOf(
                entry("video-out", "outbound-rtp", "kind" to "video"),
                entry("video-in", "inbound-rtp", "kind" to "video"),
            ),
        )

        assertNull(stats.videoOutbound?.packetsSent)
        assertNull(stats.videoOutbound?.framesEncoded)
        assertNull(stats.videoOutbound?.codecName)
        assertNull(stats.videoInbound?.packetsReceived)
        assertNull(stats.videoInbound?.framesDecoded)
        assertNull(stats.videoInbound?.codecName)
    }

    private fun entry(id: String, type: String, vararg members: Pair<String, Any?>): WebRtcStatsEntry =
        WebRtcStatsEntry(id, type, mapOf(*members))
}
