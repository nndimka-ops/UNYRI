package com.messenger.app.data.call

/** Minimal WebRTC `getStats()` snapshot (Этап J.1-1/J.1-3) - debug-only,
 *  never contains full SDP, IP addresses, TURN credentials, or Signal
 *  payloads. */
data class WebRtcCallStats(
    val selectedCandidatePair: WebRtcCandidatePairStats? = null,
    val audioOutbound: WebRtcAudioOutboundStats? = null,
    val audioInbound: WebRtcAudioInboundStats? = null,
    val videoOutbound: WebRtcVideoOutboundStats? = null,
    val videoInbound: WebRtcVideoInboundStats? = null,
)

data class WebRtcCandidatePairStats(
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
    val protocol: String? = null,
    val roundTripTimeSeconds: Double? = null,
)

data class WebRtcAudioOutboundStats(
    val packetsSent: Long? = null,
    val bytesSent: Long? = null,
    val audioLevel: Double? = null,
)

data class WebRtcAudioInboundStats(
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val packetsLost: Long? = null,
    val jitter: Double? = null,
    val audioLevel: Double? = null,
)

data class WebRtcVideoOutboundStats(
    val packetsSent: Long? = null,
    val bytesSent: Long? = null,
    val framesEncoded: Long? = null,
    val framesPerSecond: Double? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
    val codecName: String? = null,
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
)

data class WebRtcStatsEntry(
    val id: String,
    val type: String,
    val members: Map<String, Any?>,
)

object WebRtcCallStatsParser {
    fun parse(entries: Collection<WebRtcStatsEntry>): WebRtcCallStats {
        val byId = entries.associateBy { it.id }
        val pair = entries.firstOrNull { it.type == "candidate-pair" && it.booleanMember("selected") == true }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.stringMember("state") == "succeeded" && it.booleanMember("nominated") == true }
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.booleanMember("nominated") == true }
        val localCandidate = pair?.stringMember("localCandidateId")?.let(byId::get)
        val remoteCandidate = pair?.stringMember("remoteCandidateId")?.let(byId::get)
        val audioOutbound = entries.firstOrNull { it.type == "outbound-rtp" && it.isAudioRtp() }
        val audioInbound = entries.firstOrNull { it.type == "inbound-rtp" && it.isAudioRtp() }
        val videoOutbound = entries.firstOrNull { it.type == "outbound-rtp" && it.isVideoRtp() }
        val videoInbound = entries.firstOrNull { it.type == "inbound-rtp" && it.isVideoRtp() }

        return WebRtcCallStats(
            selectedCandidatePair = pair?.let {
                WebRtcCandidatePairStats(
                    localCandidateType = localCandidate?.stringMember("candidateType"),
                    remoteCandidateType = remoteCandidate?.stringMember("candidateType"),
                    protocol = localCandidate?.stringMember("protocol") ?: remoteCandidate?.stringMember("protocol"),
                    roundTripTimeSeconds = it.doubleMember("currentRoundTripTime"),
                )
            },
            audioOutbound = audioOutbound?.let {
                WebRtcAudioOutboundStats(
                    packetsSent = it.longMember("packetsSent"),
                    bytesSent = it.longMember("bytesSent"),
                    audioLevel = it.doubleMember("audioLevel"),
                )
            },
            audioInbound = audioInbound?.let {
                WebRtcAudioInboundStats(
                    packetsReceived = it.longMember("packetsReceived"),
                    bytesReceived = it.longMember("bytesReceived"),
                    packetsLost = it.longMember("packetsLost"),
                    jitter = it.doubleMember("jitter"),
                    audioLevel = it.doubleMember("audioLevel"),
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
                )
            },
        )
    }

    private fun WebRtcStatsEntry.isAudioRtp(): Boolean = stringMember("kind") == "audio" || stringMember("mediaType") == "audio"
    private fun WebRtcStatsEntry.isVideoRtp(): Boolean = stringMember("kind") == "video" || stringMember("mediaType") == "video"
    private fun WebRtcStatsEntry.stringMember(key: String): String? = members[key] as? String
    private fun WebRtcStatsEntry.booleanMember(key: String): Boolean? = members[key] as? Boolean
    private fun WebRtcStatsEntry.longMember(key: String): Long? = (members[key] as? Number)?.toLong()
    private fun WebRtcStatsEntry.doubleMember(key: String): Double? = (members[key] as? Number)?.toDouble()
}
