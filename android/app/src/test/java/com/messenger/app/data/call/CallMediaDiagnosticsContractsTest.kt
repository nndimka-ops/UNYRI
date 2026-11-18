package com.messenger.app.data.call

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CallMediaDiagnosticsContractsTest {
    @Test fun codecCodesMatchIosIncludingH264ProfileAndLevel() {
        val examples = listOf("640c1f" to 131.0, "42e01f" to 231.0, "640c34" to 152.0,
            "640C29" to 141.0, "4d801f" to 231.0, "58c01f" to 231.0, "64001f" to 631.0)
        examples.forEach { (profile, expected) -> assertEquals(expected, CallMediaDiagnosticsPolicy.videoCodecCode("video/H264", "packetization-mode=1; profile-level-id=$profile")) }
        assertEquals(600.0, CallMediaDiagnosticsPolicy.videoCodecCode("video/H264"))
        assertEquals(300.0, CallMediaDiagnosticsPolicy.videoCodecCode("video/VP8"))
        assertEquals(400.0, CallMediaDiagnosticsPolicy.videoCodecCode("video/VP9"))
        assertEquals(500.0, CallMediaDiagnosticsPolicy.videoCodecCode("video/AV1"))
        assertEquals(0.0, CallMediaDiagnosticsPolicy.videoCodecCode("video/rtx"))
        assertNull(CallMediaDiagnosticsPolicy.videoCodecCode(null))
    }
    @Test fun sizeStepTracksFirstChangeAndCapsAtTwenty() {
        val p = CallMediaDiagnosticsPolicy()
        assertFalse(p.sizeStep(null, null))
        assertTrue(p.sizeStep(1280, 720))
        assertFalse(p.sizeStep(1280, 720))
        assertFalse(p.sizeStep(null, null))
        assertFalse(p.sizeStep(1280, 720))
        repeat(40) { p.sizeStep(100L + it, 100) }
        assertEquals(20, p.sizeSteps)
    }
    @Test fun cameraMarksPeriodAndCapsMatchIos() {
        val p = CallMediaDiagnosticsPolicy()
        val records = mutableMapOf<Long, Int>()
        for (time in 0L..31) {
            p.noteCamera(time >= 5 && time != 21L, time * 1000, true)
            p.playout(time * 1000, true, true)?.let { records[time] = it }
        }
        assertEquals(mapOf(8L to 1, 10L to 0, 13L to 2, 20L to 0, 25L to 1, 30L to 2, 31L to 0), records)
        for (time in 32L..1000) p.playout(time * 1000, true, true)
        assertEquals(30, p.periodicRecords)
    }
    @Test fun cameraMarksDueWithDiagnosticsOffAreSkipped() {
        val p = CallMediaDiagnosticsPolicy()
        for (time in 0L..20) p.noteCamera(true, time * 1000, false)
        p.noteCamera(true, 21_000, true)
        assertNull(p.playout(21_000, true, true))
        assertTrue(p.audioPathDue(true)); assertFalse(p.audioPathDue(true))
    }
    @Test fun duePlayoutWaitsForMeasurement() {
        val p = CallMediaDiagnosticsPolicy()
        assertNull(p.playout(0, true, true))
        assertNull(p.playout(10_000, true, false))
        assertEquals(0, p.playout(11_000, true, true))
        assertFalse(CallMediaDiagnosticsPolicy().audioPathDue(false))
    }
    @Test fun audioNegotiationReadsFirstAudioSectionOnly() {
        val remote = CallAudioNegotiation.parse("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 63 111\r\na=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10; useinbandfec=1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 red/90000\r\n")
        assertEquals(CallAudioNegotiation(true, true, false, 1), remote)
        val local = CallAudioNegotiation.parse("m=audio 9 UDP/TLS/RTP/SAVPF 111 63\na=rtpmap:111 opus/48000/2\na=fmtp:111 useinbandfec=1;usedtx=1\na=rtpmap:63 red/48000/2\na=fmtp:63 111/111/111\n")
        assertEquals(CallAudioNegotiation(false, true, true, 2), local)
        assertEquals(mapOf("code" to 1, "status" to 1, "items" to 1, "direction" to 0, "count" to 13), CallAudioNegotiation.diagnostics(local, remote, true))
        assertFalse(CallAudioNegotiation.diagnostics(null, remote, false).containsKey("direction"))
        assertNull(CallAudioNegotiation.parse("m=video 9 UDP/TLS/RTP/SAVPF 96\na=rtpmap:96 red/90000\n"))
    }
    @Test fun eventsUseServerWhitelistAndSixteenKeyLimit() {
        assertTrue(CallDiagnosticEvent.create(2, 121, mapOf("invented" to 1)).isEmpty())
        assertTrue(CallDiagnosticEvent.create(2, 121, CallDiagnosticEvent.allowedMetrics.filter { it !in setOf("phase", "operation") }.take(15).associateWith { 1 }).isEmpty())
        assertEquals(mapOf("operation" to 2.0, "phase" to 119.0), CallDiagnosticEvent.create(2, 119, mapOf("count" to -1, "frames" to Double.NaN, "items" to null)))
    }
    @Test fun whitelistMatchesTheActualServerContract() {
        val server = listOf(File("../../server/src/performance.rs"), File("../server/src/performance.rs"), File("server/src/performance.rs")).firstOrNull { it.isFile }
        assertNotNull("Read the repository's actual server METRICS contract", server)
        val declaration = Regex("const METRICS:.*?;", RegexOption.DOT_MATCHES_ALL).find(server!!.readText())!!.value
        val keys = Regex("\"([^\"]+)\"").findAll(declaration).map { it.groupValues[1] }.toSet()
        assertEquals(keys, CallDiagnosticEvent.allowedMetrics)
    }
    @Test fun outgoingLossUsesMinimumOfLatestReportAndWindowCountAfterFortyPackets() {
        fun read(sent: Long, lost: Long?, fraction: Double?) = WebRtcCallStats(audioOutbound = WebRtcAudioOutboundStats(packetsSent = sent),
            audioRemoteInbound = WebRtcRemoteInboundStats(fractionLost = fraction, packetsLost = lost))
        fun loss(sent: Long, lost: Long?, fraction: Double?) = CallMediaMetrics.measure(listOf(0L to read(0, 0, .9), 4000L to read(sent, lost, fraction))).outgoingLoss
        assertNull(loss(39, 3, .1))
        assertEquals(.075, loss(40, 3, .1)!!, .000001)
        assertEquals(.02, loss(100, 10, .02)!!, .000001)
        assertEquals(0.0, loss(100, 0, .9)!!, .000001) // a repeated report does not claim loss across a later window
        assertEquals(.1, loss(100, null, .1)!!, .000001)
        assertEquals(.03, loss(100, 3, null)!!, .000001)
        assertNull(loss(100, null, null))
    }
    @Test fun timestampUsesFirstCompatibleClockAndRejectsStaleValues() {
        assertEquals(50.0, CallMediaDiagnosticsPolicy.sinceLastPacketMs(100.0, 150.0, 200.0))
        assertEquals(100.0, CallMediaDiagnosticsPolicy.sinceLastPacketMs(100.0, 1e12, 200.0))
        assertNull(CallMediaDiagnosticsPolicy.sinceLastPacketMs(100.0, 50.0, 1e12))
    }
    @Test fun firstPacketAndAppliedDescriptionsAreBounded() {
        val p = CallMediaDiagnostics(0)
        assertFalse(p.firstPacket("audio", 50)!!.containsKey("duration_ms"))
        p.iceState(PeerConnectionState.CONNECTED, 100)
        assertNull(p.firstPacket("audio", 150))
        assertEquals(100.0, p.firstPacket("video", 200)!!["duration_ms"])
        repeat(16) { assertNotNull(p.descriptionApplied(null, null, true)) }
        assertNull(p.descriptionApplied(null, null, true))
    }
    @Test fun recordingEnabledLaterDoesNotInventFirstAudioOrSendStaleVideo() {
        val p = CallMediaDiagnostics(0)
        val stats = WebRtcCallStats(audioInbound = WebRtcAudioInboundStats(jitterBufferEmittedCount = 10, jitterBufferDelay = .2),
            videoOutbound = WebRtcVideoOutboundStats(frameWidth = 1280, frameHeight = 720, codecCode = 131.0))
        assertTrue(p.sample(stats, 0, true, true, false).isEmpty())
        val events = p.sample(stats, 10_000, true, true, true, videoSending = false)
        assertFalse(events.any { it["phase"] == 145.0 || it["phase"] == 121.0 || it["phase"] == 117.0 })
    }
    @Test fun windowMeansAndAudioCountersMatchIos() {
        fun stats(mult: Long) = WebRtcCallStats(
            selectedCandidatePair = WebRtcCandidatePairStats(roundTripTimeSeconds = .02, totalRoundTripTime = mult * .02, responsesReceived = mult, availableOutgoingBitrate = 800_000.0),
            audioOutbound = WebRtcAudioOutboundStats(packetsSent = mult * 50),
            audioInbound = WebRtcAudioInboundStats(packetsReceived = mult * 50, packetsLost = 10 - mult,
                jitterBufferDelay = mult * .1, jitterBufferTargetDelay = mult * .08, jitterBufferMinimumDelay = mult * .06,
                jitterBufferEmittedCount = mult, removedSamplesForAcceleration = mult * 10, totalSamplesReceived = mult * 1000),
            videoOutbound = WebRtcVideoOutboundStats(framesEncoded = mult * 30, qpSum = mult * 900, totalEncodeTime = mult * .3,
                packetsSent = mult * 100, totalPacketSendDelay = mult * .1),
        )
        val m = CallMediaMetrics.measure(listOf(0L to stats(1), 4000L to stats(5)))
        assertEquals(20.0, m.stunRttMs!!, .0001)
        assertEquals(100.0, m.audioPlayoutDelayMs!!, .0001)
        assertEquals(20.0, m.avSyncDelayMs!!, .0001)
        assertEquals(50.0, m.audioPacketsOutPerSecond!!, .0001)
        assertEquals(50.0, m.audioPacketsInPerSecond!!, .0001)
        assertEquals(10.0, m.audioAcceleratedPerMille!!, .0001)
        assertEquals(30.0, m.videoOutQp!!, .0001)
        assertEquals(10.0, m.videoEncodeMs!!, .0001)
        assertEquals(1.0, m.videoPacerMs!!, .0001)
    }
    @Test fun senderFullEventHasExactlySixteenWhitelistedKeys() {
        val p = CallMediaDiagnostics(0)
        fun stats(n: Long) = WebRtcCallStats(videoOutbound = WebRtcVideoOutboundStats(frameWidth = 1280 + n, frameHeight = 720, framesPerSecond = 30.0,
            framesEncoded = n * 30, packetsSent = n * 100, qpSum = n * 900, totalEncodeTime = n * .1, keyFramesEncoded = n,
            bandwidthLimitedSeconds = n * .1, qualityLimitationReason = "none", qualityLimitationResolutionChanges = n,
            keyFrameRequests = n, totalPacketSendDelay = n * .1, targetBitrate = 800_000.0, codecCode = 131.0), camera = WebRtcCameraStats(framesPerSecond = 30.0))
        p.sample(stats(1), 0, true, true, true)
        val events = p.sample(stats(2), 1000, true, true, true)
        val sender = events.single { it["phase"] == 121.0 }
        assertEquals(16, sender.size)
        assertTrue(events.all { it.size <= 16 && CallDiagnosticEvent.allowedMetrics.containsAll(it.keys) })
    }
}
