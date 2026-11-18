package com.messenger.app.data.call

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription

/** The production native local-description boundary, shared by offers and answers.
 * Capability advertisement belongs only to signaling, after these bytes are applied. */
internal object CallLocalSdp {
    suspend fun createAndApply(
        peer: PeerConnection,
        type: SessionDescription.Type,
        requestsDtx: Boolean,
        isCurrent: () -> Boolean,
        onApplied: (SessionDescription) -> Unit = {},
    ): String {
        check(isCurrent()) { "Description superseded" }
        val created = suspendCoroutine<SessionDescription> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                override fun onCreateFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }
            when (type) {
                SessionDescription.Type.OFFER -> peer.createOffer(observer, MediaConstraints())
                SessionDescription.Type.ANSWER -> peer.createAnswer(observer, MediaConstraints())
                else -> error("Not an offer or answer")
            }
        }
        check(isCurrent()) { "Description superseded" }
        val local = if (requestsDtx) SessionDescription(created.type,
            CallSdpMunger.requestingOpusDtx(created.description)) else created
        suspendCoroutine<Unit> { continuation ->
            peer.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) = continuation.resumeWithException(IllegalStateException("Local SDP failed"))
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String?) = Unit
            }, local)
        }
        check(isCurrent()) { "Description superseded" }
        onApplied(local)
        return local.description
    }
}
