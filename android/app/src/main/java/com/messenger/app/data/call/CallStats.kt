package com.messenger.app.data.call

/** WebRTC `getStats()` snapshot from the call's existing 1 Hz read,
 *  never contains full SDP, IP addresses, TURN credentials, or Signal
 *  payloads. */
data class WebRtcCallStats(
    val selectedCandidatePair: WebRtcCandidatePairStats? = null,
    val audioOutbound: WebRtcAudioOutboundStats? = null,
    val audioInbound: WebRtcAudioInboundStats? = null,
    val videoOutbound: WebRtcVideoOutboundStats? = null,
    val videoInbound: WebRtcVideoInboundStats? = null,
    val timestampMs: Double? = null,
    val dtlsConnected: Boolean? = null,
    val audioRemoteInbound: WebRtcRemoteInboundStats? = null,
    val videoRemoteInbound: WebRtcRemoteInboundStats? = null,
    val camera: WebRtcCameraStats? = null,
    val metrics: CallMediaMetrics? = null,
)

data class WebRtcCandidatePairStats(
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
    val protocol: String? = null,
    val roundTripTimeSeconds: Double? = null,
    val id: String? = null,
    val bytesSent: Long? = null,
    val bytesReceived: Long? = null,
    val packetsDiscardedOnSend: Long? = null,
    val availableOutgoingBitrate: Double? = null,
    val totalRoundTripTime: Double? = null,
    val responsesReceived: Long? = null,
    val sinceLastPacketMs: Double? = null,
    val relayProtocol: String? = null,
)

data class WebRtcRemoteInboundStats(
    val fractionLost: Double? = null,
    val packetsLost: Long? = null,
    val roundTripTimeSeconds: Double? = null,
    val roundTripTimeMeasurements: Long? = null,
)
data class WebRtcCameraStats(val width: Long? = null, val height: Long? = null, val framesPerSecond: Double? = null)

data class WebRtcAudioOutboundStats(
    val packetsSent: Long? = null,
    val bytesSent: Long? = null,
    val audioLevel: Double? = null,
    val targetBitrate: Double? = null,
)

data class WebRtcAudioInboundStats(
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val packetsLost: Long? = null,
    val jitter: Double? = null,
    val audioLevel: Double? = null,
    val jitterBufferDelay: Double? = null,
    val jitterBufferTargetDelay: Double? = null,
    val jitterBufferMinimumDelay: Double? = null,
    val jitterBufferEmittedCount: Long? = null,
    val removedSamplesForAcceleration: Long? = null,
    val totalSamplesReceived: Long? = null,
    val concealedSamples: Long? = null,
    val silentConcealedSamples: Long? = null,
)

data class WebRtcVideoOutboundStats(
    val packetsSent: Long? = null,
    val bytesSent: Long? = null,
    val framesEncoded: Long? = null,
    val framesPerSecond: Double? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
    val codecName: String? = null,
    val codecCode: Double? = null,
    val qpSum: Long? = null,
    val totalEncodeTime: Double? = null,
    val keyFramesEncoded: Long? = null,
    val bandwidthLimitedSeconds: Double? = null,
    val qualityLimitationReason: String? = null,
    val qualityLimitationResolutionChanges: Long? = null,
    val keyFrameRequests: Long? = null,
    val totalPacketSendDelay: Double? = null,
    val retransmittedBytesSent: Long? = null,
    val targetBitrate: Double? = null,
)

data class WebRtcVideoInboundStats(
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val packetsLost: Long? = null,
    val framesDecoded: Long? = null,
    val framesPerSecond: Double? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
    val codecName: String? = null,
    val codecCode: Double? = null,
    val totalDecodeTime: Double? = null,
    val framesDropped: Long? = null,
    val keyFrameRequests: Long? = null,
    val totalInterFrameDelay: Double? = null,
    val totalSquaredInterFrameDelay: Double? = null,
    val jitterBufferDelay: Double? = null,
    val jitterBufferTargetDelay: Double? = null,
    val jitterBufferEmittedCount: Long? = null,
    val totalProcessingDelay: Double? = null,
    val totalFreezesDuration: Double? = null,
)

data class WebRtcStatsEntry(
    val id: String,
    val type: String,
    val members: Map<String, Any?>,
    val timestampUs: Double? = null,
)

object WebRtcCallStatsParser {
    fun parse(entries: Collection<WebRtcStatsEntry>, uptimeMs: Double? = null): WebRtcCallStats {
        val byId = entries.associateBy { it.id }
        val transport = entries.firstOrNull { it.type == "transport" && it.stringMember("selectedCandidatePairId") != null }
        val pair = transport?.stringMember("selectedCandidatePairId")?.let(byId::get)
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.booleanMember("selected") == true }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.stringMember("state") == "succeeded" && it.booleanMember("nominated") == true }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.booleanMember("nominated") == true }
        val localCandidate = pair?.stringMember("localCandidateId")?.let(byId::get)
        val remoteCandidate = pair?.stringMember("remoteCandidateId")?.let(byId::get)
        val audioOutbound = entries.firstOrNull { it.type == "outbound-rtp" && it.isAudioRtp() }
        val audioInbound = entries.firstOrNull { it.type == "inbound-rtp" && it.isAudioRtp() }
        val videoOutbound = entries.firstOrNull { it.type == "outbound-rtp" && it.isVideoRtp() }
        val videoInbound = entries.firstOrNull { it.type == "inbound-rtp" && it.isVideoRtp() }
        val camera = entries.firstOrNull { it.type == "media-source" && it.isVideoRtp() }
        fun remote(outbound: WebRtcStatsEntry?, audio: Boolean): WebRtcRemoteInboundStats? {
            val entry = outbound?.stringMember("remoteId")?.let(byId::get)
                ?: entries.firstOrNull { it.type == "remote-inbound-rtp" && if (audio) it.isAudioRtp() else it.isVideoRtp() }
                ?: return null
            return WebRtcRemoteInboundStats(entry.doubleMember("fractionLost"), entry.longMember("packetsLost"),
                entry.doubleMember("roundTripTime"), entry.longMember("roundTripTimeMeasurements"))
        }
        fun codec(entry: WebRtcStatsEntry): WebRtcStatsEntry? = entry.stringMember("codecId")?.let(byId::get)

        return WebRtcCallStats(
            selectedCandidatePair = pair?.let {
                WebRtcCandidatePairStats(
                    localCandidateType = localCandidate?.stringMember("candidateType"),
                    remoteCandidateType = remoteCandidate?.stringMember("candidateType"),
                    protocol = localCandidate?.stringMember("protocol") ?: remoteCandidate?.stringMember("protocol"),
                    roundTripTimeSeconds = it.doubleMember("currentRoundTripTime"),
                    id = it.id,
                    bytesSent = it.longMember("bytesSent"),
                    bytesReceived = it.longMember("bytesReceived"),
                    packetsDiscardedOnSend = it.longMember("packetsDiscardedOnSend"),
                    availableOutgoingBitrate = it.doubleMember("availableOutgoingBitrate"),
                    totalRoundTripTime = it.doubleMember("totalRoundTripTime"),
                    responsesReceived = it.longMember("responsesReceived"),
                    sinceLastPacketMs = CallMediaDiagnosticsPolicy.sinceLastPacketMs(it.doubleMember("lastPacketReceivedTimestamp"), it.timestampUs?.div(1000), uptimeMs),
                    relayProtocol = localCandidate?.stringMember("relayProtocol"),
                )
            },
            audioOutbound = audioOutbound?.let {
                WebRtcAudioOutboundStats(
                    packetsSent = it.longMember("packetsSent"),
                    bytesSent = it.longMember("bytesSent"),
                    audioLevel = it.doubleMember("audioLevel"),
                    targetBitrate = it.doubleMember("targetBitrate"),
                )
            },
            audioInbound = audioInbound?.let {
                WebRtcAudioInboundStats(
                    packetsReceived = it.longMember("packetsReceived"),
                    bytesReceived = it.longMember("bytesReceived"),
                    packetsLost = it.longMember("packetsLost"),
                    jitter = it.doubleMember("jitter"),
                    audioLevel = it.doubleMember("audioLevel"),
                    jitterBufferDelay = it.doubleMember("jitterBufferDelay"),
                    jitterBufferTargetDelay = it.doubleMember("jitterBufferTargetDelay"),
                    jitterBufferMinimumDelay = it.doubleMember("jitterBufferMinimumDelay"),
                    jitterBufferEmittedCount = it.longMember("jitterBufferEmittedCount"),
                    removedSamplesForAcceleration = it.longMember("removedSamplesForAcceleration"),
                    totalSamplesReceived = it.longMember("totalSamplesReceived"),
                    concealedSamples = it.longMember("concealedSamples"),
                    silentConcealedSamples = it.longMember("silentConcealedSamples"),
                )
            },
            videoOutbound = videoOutbound?.let {
                WebRtcVideoOutboundStats(
                    packetsSent = it.longMember("packetsSent"),
                    bytesSent = it.longMember("bytesSent"),
                    framesEncoded = it.longMember("framesEncoded"),
                    framesPerSecond = it.doubleMember("framesPerSecond"),
                    frameWidth = it.longMember("frameWidth"),
                    frameHeight = it.longMember("frameHeight"),
                    codecName = it.stringMember("codecId")?.let(byId::get)?.stringMember("mimeType"),
                    codecCode = CallMediaDiagnosticsPolicy.videoCodecCode(codec(it)?.stringMember("mimeType"), codec(it)?.stringMember("sdpFmtpLine")),
                    qpSum = it.longMember("qpSum"),
                    totalEncodeTime = it.doubleMember("totalEncodeTime"),
                    keyFramesEncoded = it.longMember("keyFramesEncoded"),
                    bandwidthLimitedSeconds = (it.members["qualityLimitationDurations"] as? Map<*, *>)?.get("bandwidth")?.let { value -> (value as? Number)?.toDouble() },
                    qualityLimitationReason = it.stringMember("qualityLimitationReason"),
                    qualityLimitationResolutionChanges = it.longMember("qualityLimitationResolutionChanges"),
                    keyFrameRequests = it.keyFrameRequests(),
                    totalPacketSendDelay = it.doubleMember("totalPacketSendDelay"),
                    retransmittedBytesSent = it.longMember("retransmittedBytesSent"),
                    targetBitrate = it.doubleMember("targetBitrate"),
                )
            },
            videoInbound = videoInbound?.let {
                WebRtcVideoInboundStats(
                    packetsReceived = it.longMember("packetsReceived"),
                    bytesReceived = it.longMember("bytesReceived"),
                    packetsLost = it.longMember("packetsLost"),
                    framesDecoded = it.longMember("framesDecoded"),
                    framesPerSecond = it.doubleMember("framesPerSecond"),
                    frameWidth = it.longMember("frameWidth"),
                    frameHeight = it.longMember("frameHeight"),
                    codecName = it.stringMember("codecId")?.let(byId::get)?.stringMember("mimeType"),
                    codecCode = CallMediaDiagnosticsPolicy.videoCodecCode(codec(it)?.stringMember("mimeType"), codec(it)?.stringMember("sdpFmtpLine")),
                    totalDecodeTime = it.doubleMember("totalDecodeTime"),
                    framesDropped = it.longMember("framesDropped"),
                    keyFrameRequests = it.keyFrameRequests(),
                    totalInterFrameDelay = it.doubleMember("totalInterFrameDelay"),
                    totalSquaredInterFrameDelay = it.doubleMember("totalSquaredInterFrameDelay"),
                    jitterBufferDelay = it.doubleMember("jitterBufferDelay"),
                    jitterBufferTargetDelay = it.doubleMember("jitterBufferTargetDelay"),
                    jitterBufferEmittedCount = it.longMember("jitterBufferEmittedCount"),
                    totalProcessingDelay = it.doubleMember("totalProcessingDelay"),
                    totalFreezesDuration = it.doubleMember("totalFreezesDuration"),
                )
            },
            timestampMs = entries.mapNotNull { it.timestampUs?.div(1000) }.maxOrNull(),
            dtlsConnected = entries.firstOrNull { it.type == "transport" }?.stringMember("dtlsState")?.let { it == "connected" },
            audioRemoteInbound = remote(audioOutbound, true),
            videoRemoteInbound = remote(videoOutbound, false),
            camera = camera?.let { WebRtcCameraStats(it.longMember("width"), it.longMember("height"), it.doubleMember("framesPerSecond")) },
        )
    }

    private fun WebRtcStatsEntry.isAudioRtp(): Boolean = stringMember("kind") == "audio" || stringMember("mediaType") == "audio"
    private fun WebRtcStatsEntry.isVideoRtp(): Boolean = stringMember("kind") == "video" || stringMember("mediaType") == "video"
    private fun WebRtcStatsEntry.stringMember(key: String): String? = members[key] as? String
    private fun WebRtcStatsEntry.booleanMember(key: String): Boolean? = members[key] as? Boolean
    private fun WebRtcStatsEntry.longMember(key: String): Long? = (members[key] as? Number)?.toLong()
    private fun WebRtcStatsEntry.doubleMember(key: String): Double? = (members[key] as? Number)?.toDouble()
    private fun WebRtcStatsEntry.keyFrameRequests(): Long? = if (members["pliCount"] == null && members["firCount"] == null) null else (longMember("pliCount") ?: 0) + (longMember("firCount") ?: 0)
}
