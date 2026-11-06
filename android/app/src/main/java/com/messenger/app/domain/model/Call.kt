package com.messenger.app.domain.model

enum class CallMediaKind { AUDIO, VIDEO }

enum class CallDirection { OUTGOING, INCOMING }

/** Why a call ended - mirrors the backend's `end_reason` enum exactly (see
 *  server/migrations/0006_calls.sql), plus is also used locally for a
 *  client-only failure (e.g. permission denied before any signaling even
 *  started) that never reaches the backend. */
enum class CallEndReason {
    REJECTED, CANCELLED, BUSY, MISSED, HANGUP, FAILED, TIMEOUT,
    /** Client-local only - permission denied, no chat with callee, etc.
     *  Never sent to/received from the backend. */
    LOCAL_ERROR,
}

/** Immutable identity of a call for the duration of its lifetime -
 *  everything else (state, media) is layered on top by CallController. */
data class CallInfo(
    val callId: String,
    val otherUserId: String,
    val otherDisplayName: String,
    val mediaKind: CallMediaKind,
    val direction: CallDirection,
)
