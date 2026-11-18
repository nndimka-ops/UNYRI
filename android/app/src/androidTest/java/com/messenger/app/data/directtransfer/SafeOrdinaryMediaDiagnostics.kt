package com.messenger.app.data.directtransfer

import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SessionDescription

/** QA only: whitelist metadata, never serialize an SDP, address, fingerprint, mid or track ID. */
internal object SafeOrdinaryMediaDiagnostics {
    private val directions = setOf("sendrecv", "sendonly", "recvonly", "inactive")
    private val counterKeys = setOf("bytesSent", "bytesReceived", "packetsSent", "packetsReceived",
        "framesEncoded", "framesDecoded", "framesSent", "framesReceived", "framesDropped",
        "frameWidth", "frameHeight", "framesPerSecond", "totalEncodeTime", "totalDecodeTime")

    fun sdpSummary(description: SessionDescription?): JSONObject? {
        if (description == null) return null
        var sessionDirection = "sendrecv"
        var current: JSONObject? = null
        val sections = mutableListOf<JSONObject>()
        description.description.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.startsWith("m=")) {
                val parts = line.drop(2).split(' ')
                val kind = parts.getOrNull(0)?.takeIf { it in setOf("audio", "video", "application") } ?: "unknown"
                val port = parts.getOrNull(1)?.substringBefore('/')?.toIntOrNull()?.takeIf { it in 0..65535 }
                current = JSONObject().put("index", sections.size).put("media", kind)
                    .put("port", port).put("explicit_direction", JSONObject.NULL)
                sections += requireNotNull(current)
            } else if (line.startsWith("a=") && line.drop(2) in directions) {
                if (current == null) sessionDirection = line.drop(2)
                else current!!.put("explicit_direction", line.drop(2))
            }
        }
        sections.forEach { section -> section.put("effective_direction",
            if (section.isNull("explicit_direction")) sessionDirection else section.getString("explicit_direction")) }
        return JSONObject().put("type", description.type.name).put("session_direction", sessionDirection)
            .put("sections", JSONArray(sections))
    }

    suspend fun collect(engine: Any, runSalt: String): JSONObject {
        require(runSalt.matches(Regex("[a-f0-9-]{36}")))
        val pc = engine.javaClass.getDeclaredField("peerConnection").apply { isAccessible = true }.get(engine) as? PeerConnection
            ?: return JSONObject().put("peer_present", false)
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest((runSalt + ":" + value).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
        val summary = JSONObject().put("peer_present", true)
            .put("local", sdpSummary(pc.localDescription)).put("remote", sdpSummary(pc.remoteDescription))
        val transceivers = JSONArray()
        pc.transceivers.forEachIndexed { index, transceiver ->
            val tx = JSONObject().put("index", index).put("media", transceiver.mediaType.name)
                .put("direction", transceiver.direction.name).put("current_direction", transceiver.currentDirection?.name)
            transceiver.sender.track()?.let { tx.put("sender_kind", it.kind()).put("sender_enabled", it.enabled()).put("sender_track_hash", digest(it.id())) }
            transceiver.receiver.track()?.let { tx.put("receiver_kind", it.kind()).put("receiver_enabled", it.enabled()).put("receiver_track_hash", digest(it.id())) }
            transceivers.put(tx)
        }
        summary.put("transceivers", transceivers)
        val mediaStats = suspendCancellableCoroutine<JSONObject> { continuation ->
            pc.getStats { report ->
                fun entriesFor(kind: String): JSONArray {
                    val entries = JSONArray()
                    report.statsMap.values.filter { stat ->
                        stat.type in setOf("inbound-rtp", "outbound-rtp", "remote-inbound-rtp", "remote-outbound-rtp") &&
                            (stat.members["kind"] == kind || stat.members["mediaType"] == kind)
                    }.sortedBy { it.id }.forEach { stat ->
                        val item = JSONObject().put("entry_hash", digest(stat.id)).put("type", stat.type)
                        counterKeys.sorted().forEach { key -> (stat.members[key] as? Number)?.let { item.put(key, it) } }
                        entries.put(item)
                    }
                    return entries
                }
                if (continuation.isActive) continuation.resume(JSONObject().put("video", entriesFor("video")).put("audio", entriesFor("audio")))
            }
        }
        return summary.put("video_rtp_all_entries", mediaStats.getJSONArray("video"))
            .put("audio_rtp_all_entries", mediaStats.getJSONArray("audio"))
    }
}
