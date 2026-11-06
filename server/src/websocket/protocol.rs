use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::messages::models::MessageDto;

/// Minimal versioned client -> server messages. Will grow as more message
/// types (TYPING, SYNC, ...) are introduced in later stages.
#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
pub enum ClientMessage {
    #[serde(rename = "presence.heartbeat")]
    PresenceHeartbeat { active: bool },
    #[serde(rename = "presence.subscribe")]
    PresenceSubscribe { user_ids: Vec<Uuid> },
    #[serde(rename = "chat.draft")]
    ChatDraft { chat_id: Uuid, stream: Uuid, revision: u64, payload: String, envelopes: Vec<DraftEnvelope> },
    #[serde(rename = "chat.activity")]
    ChatActivity { chat_id: Uuid, activity: String },
    #[serde(rename = "PING")]
    Ping {
        #[serde(default)]
        request_id: Option<String>,
    },
    /// Call signaling: opaque SDP/ICE relay only - the backend never trusts
    /// any sender identity in the payload, always the authenticated
    /// connection's own user/device (Этап J п.14/16).
    #[serde(rename = "call.offer")]
    CallOffer { call_id: Uuid, sdp: String },
    #[serde(rename = "call.answer")]
    CallAnswer { call_id: Uuid, sdp: String },
    #[serde(rename = "call.ice")]
    CallIce {
        call_id: Uuid,
        candidate: String,
        #[serde(default)]
        sdp_mid: Option<String>,
        #[serde(default)]
        sdp_m_line_index: Option<i32>,
    },
    /// Audio -> video upgrade (N.1A): transient negotiation frames. No DB
    /// storage - the upgrade is ephemeral; losing it mid-flight just means
    /// the requestor re-sends. Same WebSocket, same call.
    #[serde(rename = "call.video_upgrade_request")]
    CallVideoUpgradeRequest { call_id: Uuid },
    #[serde(rename = "call.video_upgrade_accept")]
    CallVideoUpgradeAccept { call_id: Uuid },
    #[serde(rename = "call.video_upgrade_reject")]
    CallVideoUpgradeReject { call_id: Uuid },
    #[serde(rename = "call.video_upgrade_cancel")]
    CallVideoUpgradeCancel { call_id: Uuid },
    /// Remote camera on/off notification ("Camera is off" state, N.1A §13).
    #[serde(rename = "call.video_state")]
    CallVideoState { call_id: Uuid, enabled: bool },
    /// N.2R call-liveness heartbeat: sent periodically by every device while
    /// it is actually in a call (Connecting/Connected/Reconnecting). Refreshes
    /// the call's last_activity_at; a connected call whose liveness goes stale
    /// is swept as failed. Not a reply frame.
    #[serde(rename = "call.keep_alive")]
    CallKeepAlive { call_id: Uuid },
}

/// Minimal versioned server -> client messages.
#[derive(Debug, Serialize)]
#[serde(tag = "type")]
pub enum ServerMessage {
    #[serde(rename = "call.history_changed")]
    CallHistoryChanged,
    #[serde(rename = "presence.snapshot")]
    PresenceSnapshot {
        items: Vec<crate::presence::PresenceItem>,
        observed_at: chrono::DateTime<chrono::Utc>,
        valid_for_ms: u64,
    },
    #[serde(rename = "chat.management_changed")]
    ChatManagementChanged,
    #[serde(rename = "chat.deleted")]
    ChatDeleted { chat_id: Uuid },
    #[serde(rename = "chat.draft")]
    ChatDraft { chat_id: Uuid, user_id: Uuid, device_id: Uuid, stream: Uuid, revision: u64, payload: String, envelope: DraftEnvelope },
    #[serde(rename = "chat.activity")]
    ChatActivity { chat_id: Uuid, user_id: Uuid, activity: String },
    #[serde(rename = "PONG")]
    Pong {
        #[serde(skip_serializing_if = "Option::is_none")]
        request_id: Option<String>,
    },
    #[serde(rename = "ERROR")]
    Error { message: String },
    /// Fired to every live connection of a device whose session was revoked
    /// (Этап L.10). The client must tear down its socket, drop its tokens and
    /// return to the login screen. `reason` is informational only - the
    /// server-side revocation is already authoritative regardless of whether
    /// this frame is ever received.
    #[serde(rename = "session.revoked")]
    SessionRevoked { reason: String },
    /// Pushed to every other member of a chat when a new message is stored.
    #[serde(rename = "message.new")]
    MessageNew { message: MessageDto },
    /// Pushed to the sender's own other connections (multi-device echo) once
    /// their message is durably stored.
    #[serde(rename = "message.sent")]
    MessageSent { message: MessageDto },
    /// Realtime fast-path notification that `user_id`'s delivered/read
    /// watermark for `chat_id` advanced. Fan-out is per-*user* (every active
    /// device of the other chat member(s)), not per-device, since receipts
    /// carry no ciphertext and are a user-level aggregate (Этап A's
    /// `message_receipts` is already `UNIQUE(message_id, user_id)`). This is
    /// the fast path only - if this event is ever missed (dropped
    /// connection, etc), `GET /sync`/`GET /chats/{id}` reconciliation always
    /// recovers the same state, so no delivery guarantee is needed here.
    #[serde(rename = "receipt.updated")]
    ReceiptUpdated {
        chat_id: Uuid,
        user_id: Uuid,
        delivered_sequence: i64,
        read_sequence: i64,
    },
    /// Pushed to every active device of the callee when a call is created.
    #[serde(rename = "call.ringing")]
    CallRinging { call_id: Uuid, caller_user_id: Uuid, media_kind: String },
    /// Relayed SDP offer (caller -> callee device(s)) / answer (accepted
    /// callee device -> caller) - opaque to the backend.
    #[serde(rename = "call.offer")]
    CallOffer { call_id: Uuid, sdp: String },
    #[serde(rename = "call.answer")]
    CallAnswer { call_id: Uuid, sdp: String },
    #[serde(rename = "call.ice")]
    CallIce {
        call_id: Uuid,
        candidate: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        sdp_mid: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        sdp_m_line_index: Option<i32>,
    },
    /// Fired to the caller (and to the winning callee device, informational)
    /// once the accept race resolves.
    #[serde(rename = "call.accepted")]
    CallAccepted { call_id: Uuid },
    /// Fired to every OTHER callee device once one of them wins the accept
    /// race (Этап J п.18).
    #[serde(rename = "call.answered_elsewhere")]
    CallAnsweredElsewhere { call_id: Uuid },
    /// Terminal notification to whichever party didn't trigger the end
    /// themselves (reject/cancel/hangup/busy/missed/timeout/failed).
    #[serde(rename = "call.ended")]
    CallEnded { call_id: Uuid, reason: String },
    /// Relayed video-upgrade negotiation frames (N.1A).
    #[serde(rename = "call.video_upgrade_request")]
    CallVideoUpgradeRequest { call_id: Uuid },
    #[serde(rename = "call.video_upgrade_accept")]
    CallVideoUpgradeAccept { call_id: Uuid },
    #[serde(rename = "call.video_upgrade_reject")]
    CallVideoUpgradeReject { call_id: Uuid },
    #[serde(rename = "call.video_upgrade_cancel")]
    CallVideoUpgradeCancel { call_id: Uuid },
    #[serde(rename = "call.video_state")]
    CallVideoState { call_id: Uuid, enabled: bool },
}


/// Signal-wrapped, random per-stream AES key. Never logged or persisted.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DraftEnvelope {
    pub recipient_device_id: Uuid,
    pub message_type: i32,
    pub ciphertext: String,
}
