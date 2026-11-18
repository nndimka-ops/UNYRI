package com.messenger.app.data.call

import kotlin.math.max
import kotlin.math.sqrt

/** Numeric wire contract shared with iOS. Reject unknown or oversized events as a unit. */
object CallDiagnosticEvent {
    val allowedMetrics = setOf("duration_ms", "gap_ms", "budget_ms", "frames", "late_frames", "max_gap_ms",
        "memory_mb", "disk_free_mb", "thermal", "low_power", "network", "count", "bytes", "cache_hit",
        "status", "success", "cancelled", "queue_count", "visible_count", "items", "fps", "maximum_fps",
        "phase", "direction", "width", "height", "span", "operation", "trace", "skew_ms", "code", "attempt", "interface", "reason")

    fun create(operation: Int, phase: Int, values: Map<String, Number?> = emptyMap()): Map<String, Double> {
        val result = linkedMapOf("operation" to operation.toDouble(), "phase" to phase.toDouble())
        values.forEach { (key, value) -> value?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1e12 }?.let { result[key] = it } }
        return result.takeIf { it.size <= 16 && allowedMetrics.containsAll(it.keys) } ?: emptyMap()
    }
}

/** Scheduling is driven only by the existing full stats read, including when recording is off. */
class CallMediaDiagnosticsPolicy {
    private var lastSize: Pair<Long, Long>? = null
    var sizeSteps = 0; private set
    private var periodicFrom: Long? = null
    var periodicRecords = 0; private set
    private var cameraWasOn = false
    private var cameraOnAt: Long? = null
    private var cameraMarksDone = 0
    var cameraRecords = 0; private set
    private var audioPathTaken = false

    fun sizeStep(width: Long?, height: Long?): Boolean {
        if (width == null || height == null || lastSize == (width to height)) return false
        lastSize = width to height
        if (sizeSteps >= 20) return false
        sizeSteps++
        return true
    }
    fun noteCamera(cameraOn: Boolean, nowMs: Long, recording: Boolean) {
        if (cameraOn != cameraWasOn) {
            cameraWasOn = cameraOn; cameraOnAt = nowMs.takeIf { cameraOn }; cameraMarksDone = 0
        }
        if (!recording) cameraOnAt?.let { on ->
            val due = marks.indexOfLast { nowMs - on >= it }
            cameraMarksDone = max(cameraMarksDone, due + 1)
        }
    }
    fun audioPathDue(connected: Boolean): Boolean {
        if (!connected || audioPathTaken) return false
        audioPathTaken = true
        return true
    }
    fun playout(nowMs: Long, connected: Boolean, measured: Boolean): Int? {
        if (!connected) return null
        if (periodicFrom == null) periodicFrom = nowMs
        if (!measured) return null
        cameraOnAt?.let { on ->
            val due = marks.indexOfLast { nowMs - on >= it }
            if (cameraRecords < 10 && due >= cameraMarksDone) {
                cameraMarksDone = due + 1; cameraRecords++
                return due + 1
            }
        }
        if (nowMs - (periodicFrom ?: nowMs) >= 10_000 && periodicRecords < 30) {
            periodicFrom = nowMs; periodicRecords++
            return 0
        }
        return null
    }
    companion object {
        private val marks = listOf(3_000L, 8_000L)
        fun limitationCode(reason: String?): Int? = when (reason) { null -> null; "none" -> 0; "cpu" -> 1; "bandwidth" -> 2; else -> 3 }
        fun videoCodecCode(mimeType: String?, fmtp: String? = null): Double? {
            when (mimeType?.substringAfterLast('/')?.lowercase()) {
                null -> return null; "vp8" -> return 300.0; "vp9" -> return 400.0; "av1", "av1x" -> return 500.0; "h264" -> Unit; else -> return 0.0
            }
            val hex = fmtp.orEmpty().split(';').map { it.trim().lowercase() }.firstOrNull { it.startsWith("profile-level-id=") }?.substringAfter('=')
            val id = hex?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: return 600.0
            val profile = id shr 16; val flags = id shr 8 and 255
            val code = when {
                profile == 0x64 && flags == 0x0c -> 1
                profile == 0x42 && flags and 0x40 != 0 || profile == 0x4d && flags and 0x80 != 0 || profile == 0x58 && flags and 0xc0 == 0xc0 -> 2
                else -> 6
            }
            return (code * 100 + (id and 255).coerceAtMost(99)).toDouble()
        }
        /** The stats timestamp may be epoch or monotonic. Never combine unlike clocks. */
        fun sinceLastPacketMs(lastPacketMs: Double?, reportMs: Double?, uptimeMs: Double? = null): Double? {
            if (lastPacketMs == null) return null
            return listOfNotNull(reportMs, uptimeMs).map { it - lastPacketMs }.firstOrNull { it.isFinite() && it in 0.0..600_000.0 }
        }
    }
}

data class CallMediaMetrics(
    val rttMs: Double? = null, val stunRttMs: Double? = null, val rtcpRttMs: Double? = null,
    val availableOutgoingKbps: Double? = null, val outgoingLoss: Double? = null,
    val audioPlayoutDelayMs: Double? = null, val audioTargetDelayMs: Double? = null, val videoPlayoutDelayMs: Double? = null,
    val avSyncDelayMs: Double? = null, val audioPacketsOutPerSecond: Double? = null, val audioPacketsInPerSecond: Double? = null,
    val audioAcceleratedPerMille: Double? = null, val videoOutQp: Double? = null, val videoEncodeMs: Double? = null,
    val videoBandwidthLimitedMs: Double? = null, val videoResolutionChanges: Double? = null, val videoKeyFramesEncoded: Double? = null,
    val videoKeyFrameRequestsIn: Double? = null, val videoPacerMs: Double? = null, val videoRetransmittedBytes: Double? = null,
    val videoDecodeMs: Double? = null, val videoFramesDropped: Double? = null, val videoKeyFrameRequestsOut: Double? = null,
    val videoInterFrameStddevMs: Double? = null,
) {
    companion object {
        /** A five-read window, ratios of deltas rather than averages of one-second ratios. */
        fun measure(window: List<Pair<Long, WebRtcCallStats>>): CallMediaMetrics {
            val last = window.lastOrNull()?.second ?: return CallMediaMetrics()
            val first = window.first().second
            val span = (window.last().first - window.first().first) / 1000.0
            val a = first.audioInbound; val z = last.audioInbound
            val o = first.videoOutbound; val v = last.videoOutbound
            val i = first.videoInbound; val j = last.videoInbound
            val pair = last.selectedCandidatePair
            fun delta(before: Number?, after: Number?): Double? = if (before == null || after == null) null else (after.toDouble() - before.toDouble()).takeIf { it >= 0 && it.isFinite() }
            fun per(n: Double?, d: Double?): Double? = if (n != null && d != null && d > 0) n / d else null
            fun ms(n: Double?, d: Double?) = per(n, d)?.times(1000)
            fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            val stun = ms(delta(first.selectedCandidatePair?.totalRoundTripTime, pair?.totalRoundTripTime), delta(first.selectedCandidatePair?.responsesReceived, pair?.responsesReceived))
                ?: median(window.mapNotNull { it.second.selectedCandidatePair?.roundTripTimeSeconds })?.times(1000)
            val rtcp = (last.videoRemoteInbound?.roundTripTimeSeconds.takeIf { (delta(first.videoRemoteInbound?.roundTripTimeMeasurements, last.videoRemoteInbound?.roundTripTimeMeasurements) ?: 0.0) > 0 }
                ?: last.audioRemoteInbound?.roundTripTimeSeconds)?.times(1000)
            val sent = delta(first.audioOutbound?.packetsSent, last.audioOutbound?.packetsSent)
            val loss = if (sent != null && sent >= 40) listOfNotNull(last.audioRemoteInbound?.fractionLost?.coerceIn(0.0, 1.0), per(delta(first.audioRemoteInbound?.packetsLost, last.audioRemoteInbound?.packetsLost), sent)?.coerceAtMost(1.0)).minOrNull() else null
            val emitted = delta(a?.jitterBufferEmittedCount, z?.jitterBufferEmittedCount)
            val target = ms(delta(a?.jitterBufferTargetDelay, z?.jitterBufferTargetDelay), emitted)
            val minimum = ms(delta(a?.jitterBufferMinimumDelay, z?.jitterBufferMinimumDelay), emitted)
            val encoded = delta(o?.framesEncoded, v?.framesEncoded)
            val decoded = delta(i?.framesDecoded, j?.framesDecoded)
            val interMean = per(delta(i?.totalInterFrameDelay, j?.totalInterFrameDelay), decoded)
            val interSquares = per(delta(i?.totalSquaredInterFrameDelay, j?.totalSquaredInterFrameDelay), decoded)
            return CallMediaMetrics(
                rttMs = stun ?: rtcp, stunRttMs = stun, rtcpRttMs = rtcp,
                availableOutgoingKbps = pair?.availableOutgoingBitrate?.div(1000), outgoingLoss = loss,
                audioPlayoutDelayMs = ms(delta(a?.jitterBufferDelay, z?.jitterBufferDelay), emitted), audioTargetDelayMs = target,
                videoPlayoutDelayMs = ms(delta(i?.totalProcessingDelay, j?.totalProcessingDelay), decoded) ?: ms(delta(i?.jitterBufferDelay, j?.jitterBufferDelay), delta(i?.jitterBufferEmittedCount, j?.jitterBufferEmittedCount)),
                avSyncDelayMs = if (target != null && minimum != null) max(0.0, target - minimum) else null,
                audioPacketsOutPerSecond = per(sent, span),
                audioPacketsInPerSecond = per(delta(a?.packetsReceived, z?.packetsReceived)?.plus(delta(a?.packetsLost, z?.packetsLost) ?: 0.0), span),
                audioAcceleratedPerMille = per(delta(a?.removedSamplesForAcceleration, z?.removedSamplesForAcceleration), delta(a?.totalSamplesReceived, z?.totalSamplesReceived))?.times(1000),
                videoOutQp = per(delta(o?.qpSum, v?.qpSum), encoded), videoEncodeMs = ms(delta(o?.totalEncodeTime, v?.totalEncodeTime), encoded),
                videoBandwidthLimitedMs = delta(o?.bandwidthLimitedSeconds, v?.bandwidthLimitedSeconds)?.times(1000),
                videoResolutionChanges = delta(o?.qualityLimitationResolutionChanges, v?.qualityLimitationResolutionChanges),
                videoKeyFramesEncoded = delta(o?.keyFramesEncoded, v?.keyFramesEncoded), videoKeyFrameRequestsIn = delta(o?.keyFrameRequests, v?.keyFrameRequests),
                videoPacerMs = ms(delta(o?.totalPacketSendDelay, v?.totalPacketSendDelay), delta(o?.packetsSent, v?.packetsSent)),
                videoRetransmittedBytes = delta(o?.retransmittedBytesSent, v?.retransmittedBytesSent), videoDecodeMs = ms(delta(i?.totalDecodeTime, j?.totalDecodeTime), decoded),
                videoFramesDropped = delta(i?.framesDropped, j?.framesDropped), videoKeyFrameRequestsOut = delta(i?.keyFrameRequests, j?.keyFrameRequests),
                videoInterFrameStddevMs = if (interMean != null && interSquares != null) sqrt(max(0.0, interSquares - interMean * interMean)) * 1000 else null,
            )
        }
    }
}

/** Per-call callback and stats state. No identifiers, SDP or addresses leave this class. */
class CallMediaDiagnostics(private val initializedAtMs: Long) {
    private val schedule = CallMediaDiagnosticsPolicy()
    private val reads = ArrayDeque<Pair<Long, WebRtcCallStats>>()
    private var connectedAtMs: Long? = null
    private val firstKinds = mutableSetOf<String>()
    private var firstPlayed = false
    private var descriptions = 0
    var metrics = CallMediaMetrics(); private set
    var audioPathDue = false; private set

    @Synchronized fun iceState(state: PeerConnectionState, nowMs: Long): Map<String, Double> {
        if (state == PeerConnectionState.CONNECTED && connectedAtMs == null) connectedAtMs = nowMs
        val pair = reads.lastOrNull()
        val age = pair?.let { nowMs - it.first }?.coerceAtLeast(0)
        val gap = pair?.second?.selectedCandidatePair?.sinceLastPacketMs?.let { it + (age ?: 0) }
        return CallDiagnosticEvent.create(1, 100 + state.ordinal, mapOf("max_gap_ms" to gap))
    }
    @Synchronized fun firstPacket(kind: String, nowMs: Long): Map<String, Double>? {
        if (kind !in setOf("audio", "video") || !firstKinds.add(kind)) return null
        return CallDiagnosticEvent.create(5, 144, mapOf("status" to if (kind == "audio") 1 else 2,
            "duration_ms" to connectedAtMs?.let { nowMs - it }, "gap_ms" to nowMs - initializedAtMs))
    }
    @Synchronized fun descriptionApplied(local: String?, remote: String?, appliedLocal: Boolean): Map<String, Double>? {
        if (descriptions++ >= 16) return null
        return CallDiagnosticEvent.create(5, 148, CallAudioNegotiation.diagnostics(local?.let(CallAudioNegotiation::parse), remote?.let(CallAudioNegotiation::parse), appliedLocal))
    }
    @Synchronized fun sample(stats: WebRtcCallStats, nowMs: Long, connected: Boolean, cameraOn: Boolean, recording: Boolean, thermalState: Int = 0, videoSending: Boolean = cameraOn): List<Map<String, Double>> {
        if (reads.lastOrNull()?.let { nowMs - it.first > 3_000 } == true) reads.clear()
        reads.addLast(nowMs to stats); while (reads.size > 5) reads.removeFirst()
        metrics = CallMediaMetrics.measure(reads.toList())
        schedule.noteCamera(cameraOn, nowMs, recording)
        audioPathDue = recording && schedule.audioPathDue(connected)
        val playedNow = !firstPlayed && (stats.audioInbound?.jitterBufferEmittedCount ?: 0) > 0
        if (playedNow) firstPlayed = true
        if (!recording) return emptyList()
        val events = mutableListOf<Map<String, Double>>()
        val m = metrics; val v = stats.videoOutbound; val j = stats.videoInbound
        if (playedNow) {
            events += CallDiagnosticEvent.create(5, 145, mapOf("duration_ms" to connectedAtMs?.let { nowMs - it }, "gap_ms" to nowMs - initializedAtMs,
                "budget_ms" to stats.audioInbound?.jitterBufferDelay?.let { it * 1000 / (stats.audioInbound.jitterBufferEmittedCount ?: 1) }))
        }
        val size = videoSending && schedule.sizeStep(v?.frameWidth, v?.frameHeight)
        if (size) events += CallDiagnosticEvent.create(2, 117, mapOf("items" to m.availableOutgoingKbps, "budget_ms" to m.rttMs, "count" to m.outgoingLoss?.times(100),
            "reason" to CallMediaDiagnosticsPolicy.limitationCode(v?.qualityLimitationReason), "width" to v?.frameWidth, "height" to v?.frameHeight))
        val playout = schedule.playout(nowMs, connected, m.audioPlayoutDelayMs != null || m.videoPlayoutDelayMs != null)
        if (playout != null) events += CallDiagnosticEvent.create(2, 119, mapOf("status" to playout, "budget_ms" to m.audioPlayoutDelayMs, "max_gap_ms" to m.audioTargetDelayMs,
            "gap_ms" to m.videoPlayoutDelayMs, "skew_ms" to m.videoPlayoutDelayMs?.let { video -> m.audioPlayoutDelayMs?.let { video - it } }, "duration_ms" to m.rttMs,
            "items" to m.availableOutgoingKbps, "width" to v?.frameWidth, "height" to v?.frameHeight, "count" to m.avSyncDelayMs,
            "frames" to m.audioPacketsOutPerSecond, "late_frames" to m.audioPacketsInPerSecond, "code" to m.audioAcceleratedPerMille))
        if (size || playout != null) {
            if (videoSending && (v?.frameWidth != null || v?.targetBitrate != null || v?.codecCode != null)) events += CallDiagnosticEvent.create(2, 121, mapOf(
                "width" to v?.frameWidth, "height" to v?.frameHeight, "fps" to v?.framesPerSecond, "maximum_fps" to stats.camera?.framesPerSecond,
                "code" to m.videoOutQp, "budget_ms" to m.videoEncodeMs, "items" to v?.targetBitrate?.div(1000), "reason" to CallMediaDiagnosticsPolicy.limitationCode(v?.qualityLimitationReason),
                "duration_ms" to m.videoBandwidthLimitedMs, "count" to m.videoResolutionChanges, "frames" to m.videoKeyFramesEncoded, "late_frames" to m.videoKeyFrameRequestsIn,
                "gap_ms" to m.videoPacerMs, "status" to v?.codecCode))
            events += CallDiagnosticEvent.create(2, 122, mapOf("width" to j?.frameWidth, "height" to j?.frameHeight, "fps" to j?.framesPerSecond,
                "status" to j?.codecCode, "budget_ms" to m.videoDecodeMs, "frames" to m.videoFramesDropped, "late_frames" to m.videoKeyFrameRequestsOut,
                "gap_ms" to m.videoInterFrameStddevMs, "duration_ms" to m.stunRttMs, "max_gap_ms" to m.rtcpRttMs, "items" to m.availableOutgoingKbps,
                "bytes" to m.videoRetransmittedBytes, "thermal" to thermalState))
        }
        return events.filter { it.isNotEmpty() }
    }
}

data class CallAudioNegotiation(val redFirst: Boolean = false, val inbandFec: Boolean = false, val dtx: Boolean = false, val redDistance: Int? = null) {
    companion object {
        fun parse(sdp: String): CallAudioNegotiation? {
            val lines = sdp.lineSequence().map { it.trim() }.toList()
            val start = lines.indexOfFirst { it.startsWith("m=audio ") }.takeIf { it >= 0 } ?: return null
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("m=") }
            val payloads = lines[start].split(Regex("\\s+")).drop(3)
            fun fields(prefix: String) = section.filter { it.startsWith(prefix) }.mapNotNull { line -> line.removePrefix(prefix).split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val names = fields("a=rtpmap:").mapValues { it.value.substringBefore('/').lowercase() }
            val formats = fields("a=fmtp:")
            val opus = payloads.firstOrNull { names[it] == "opus" }
            val parameters = formats[opus].orEmpty().split(';').map { it.trim().lowercase() }
            val red = payloads.firstOrNull { names[it] == "red" }
            return CallAudioNegotiation(names[payloads.firstOrNull()] == "red", "useinbandfec=1" in parameters, "usedtx=1" in parameters,
                formats[red]?.split('/')?.size?.minus(1)?.coerceAtLeast(0))
        }
        fun diagnostics(local: CallAudioNegotiation?, remote: CallAudioNegotiation?, appliedLocal: Boolean): Map<String, Number?> = buildMap {
            put("code", if (appliedLocal) 1 else 2)
            remote?.let { put("status", if (it.redFirst) 1 else 0); put("items", it.redDistance) }
            local?.let { put("direction", if (it.redFirst) 1 else 0) }
            if (local != null || remote != null) put("count", (if (remote?.inbandFec == true) 1 else 0) + (if (remote?.dtx == true) 2 else 0) + (if (local?.inbandFec == true) 4 else 0) + (if (local?.dtx == true) 8 else 0))
        }
    }
}
