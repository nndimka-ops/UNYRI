package com.messenger.app.ui.call

import com.messenger.app.R
import com.messenger.app.data.call.WebRtcCallStats
import kotlin.math.*

data class CallQualityUiState(val quality: CallQualityPresentation.Result = CallQualityPresentation.Result(),
    val relay: Boolean? = null, val transport: String? = null)

/** Same window, thresholds and hysteresis as iOS CallQualityPolicy.swift. */
class CallQualityPresentation {
    data class Result(val bars: Int = 0, val caption: Int? = null)
    private val window = ArrayDeque<Pair<Long, WebRtcCallStats>>()
    private var grade: Int? = null
    private var gradeAt = 0L
    private var worse = 0
    private var better = 0
    private var captionAt: Long? = null
    private var lastVideoBytesAt: Long? = null
    private var lastDecodedAt: Long? = null
    private var silenceAt: Long? = null
    private var bandwidthAt: Long? = null
    fun reset(keepGrade: Boolean = false) {
        window.clear(); worse = 0; better = 0
        lastVideoBytesAt = null; lastDecodedAt = null; silenceAt = null; bandwidthAt = null
        if (!keepGrade) { grade = null; captionAt = null }
    }
    fun update(now: Long, stats: WebRtcCallStats, receivingVideo: Boolean, sendingVideo: Boolean,
        localMuted: Boolean = false): Result {
        val previous = window.lastOrNull()
        if (previous != null && now - previous.first > 3_000) reset()
        if (window.lastOrNull()?.first == now) return result(now)
        window.addLast(now to stats)
        while (window.size > 5) window.removeFirst()
        if (previous != null) {
            if ((delta(previous.second.audioInbound?.bytesReceived, stats.audioInbound?.bytesReceived) ?: 0.0) > 0) silenceAt = null
            else if ((previous.second.audioInbound?.bytesReceived ?: 0) > 0 && silenceAt == null) silenceAt = now
            if ((delta(previous.second.videoInbound?.bytesReceived, stats.videoInbound?.bytesReceived) ?: 0.0) > 0) lastVideoBytesAt = now
            if ((delta(previous.second.videoInbound?.framesDecoded, stats.videoInbound?.framesDecoded) ?: 0.0) > 0) lastDecodedAt = now
        }
        if (sendingVideo && stats.videoOutbound?.qualityLimitationReason == "bandwidth") {
            if (bandwidthAt == null) bandwidthAt = now
        } else bandwidthAt = null
        if (window.size < 2) return result(now)
        val first = window.first().second
        val seconds = (now - window.first().first) / 1000.0
        val responses = delta(first.selectedCandidatePair?.responsesReceived, stats.selectedCandidatePair?.responsesReceived)
        val total = delta(first.selectedCandidatePair?.totalRoundTripTime, stats.selectedCandidatePair?.totalRoundTripTime)
        val rtt = if (first.selectedCandidatePair?.id == stats.selectedCandidatePair?.id && responses != null && responses > 0 && total != null) total / responses * 1000 else {
            val values = window.mapNotNull { it.second.selectedCandidatePair?.roundTripTimeSeconds ?: it.second.audioRemoteInbound?.roundTripTimeSeconds }.sorted()
            if (values.isEmpty()) null else values[values.size / 2] * 1000
        }
        val lost = delta(first.audioInbound?.packetsLost, stats.audioInbound?.packetsLost)
        val received = delta(first.audioInbound?.packetsReceived, stats.audioInbound?.packetsReceived)
        val incoming = if (lost != null && received != null && lost + received >= 40) lost / (lost + received) else null
        val sent = delta(first.audioOutbound?.packetsSent, stats.audioOutbound?.packetsSent)
        val outgoing = if (sent != null && sent >= 40) listOfNotNull(stats.audioRemoteInbound?.fractionLost?.coerceIn(0.0, 1.0),
            delta(first.audioRemoteInbound?.packetsLost, stats.audioRemoteInbound?.packetsLost)?.div(sent)?.coerceIn(0.0, 1.0)).minOrNull() else null
        val concealed = delta(first.audioInbound?.concealedSamples, stats.audioInbound?.concealedSamples)
        val samples = delta(first.audioInbound?.totalSamplesReceived, stats.audioInbound?.totalSamplesReceived)
        val silent = delta(first.audioInbound?.silentConcealedSamples, stats.audioInbound?.silentConcealedSamples) ?: 0.0
        val concealment = if (concealed != null && samples != null && samples > 0) max(0.0, concealed - silent) / samples else null
        val jitter = window.mapNotNull { it.second.audioInbound?.jitter }.takeIf { it.isNotEmpty() }?.average()?.times(1000)
        val videoArriving = receivingVideo && lastVideoBytesAt?.let { now - it < 2_000 } == true
        val fps = if (videoArriving && seconds >= 2) delta(first.videoInbound?.framesDecoded, stats.videoInbound?.framesDecoded)?.div(seconds) else null
        val freeze = if (videoArriving && seconds >= 2) delta(first.videoInbound?.totalFreezesDuration, stats.videoInbound?.totalFreezesDuration)?.div(seconds) else null
        val upload = if (bandwidthAt?.let { now - it >= 3_000 } == true) stats.selectedCandidatePair?.availableOutgoingBitrate?.div(1000) else null
        if (listOf(rtt, jitter, incoming, outgoing, concealment, fps, upload).all { it == null }) return result(now)
        var raw = maxOf(level(rtt, 250.0, 400.0, 700.0), level(jitter, 40.0, 80.0, 150.0),
            level((concealment ?: incoming)?.times(100), if (concealment != null) 2.0 else 3.0, if (concealment != null) 5.0 else 6.0, 12.0),
            level(outgoing?.times(100), 3.0, 6.0, 12.0), level(freeze?.times(100), 10.0, 10.0, 30.0),
            level(fps?.let { -it }, -10.0, -5.0, Double.POSITIVE_INFINITY),
            level(upload?.let { -it }, Double.NEGATIVE_INFINITY, -400.0, -150.0))
        if (silenceAt?.let { now - it > 2_000 } == true || (videoArriving && now - (lastDecodedAt ?: window.first().first) >= 2_000)) raw = 3
        val current = grade
        if (current == null) { if (window.size == 5) show(raw, now) }
        else if (raw > current) { worse++; better = 0; if (worse >= 2 && now - gradeAt >= 3_000) show(raw, now) }
        else if (raw < current) { better++; worse = 0; if (better >= 5 && now - gradeAt >= 3_000) show(raw, now) }
        else { worse = 0; better = 0 }
        return result(now)
    }
    private fun show(raw: Int, now: Long) { grade = raw; gradeAt = now; worse = 0; better = 0; captionAt = if (raw >= 2) now else null }
    fun result(now: Long): Result {
        val elapsed = captionAt?.let { now - it } ?: 0
        val caption = when { elapsed < 4_000 -> null; grade == 3 -> R.string.call_quality_poor
            grade == 2 && elapsed < 9_000 -> R.string.call_quality_weak; else -> null }
        return Result(grade?.let { 4 - it } ?: 0, caption)
    }
    private fun level(value: Double?, a: Double, b: Double, c: Double): Int {
        if (value == null || !value.isFinite()) return 0
        fun rate(v: Double) = if (v < a) 0 else if (v < b) 1 else if (v < c) 2 else 3
        val raw = rate(value)
        return if (grade != null && raw < grade!!) min(grade!!, rate(if (value < 0) value * .85 else value / .85)) else raw
    }
    private fun delta(a: Number?, b: Number?): Double? = if (a == null || b == null) null else (b.toDouble() - a.toDouble()).takeIf { it.isFinite() && it >= 0 }
}

