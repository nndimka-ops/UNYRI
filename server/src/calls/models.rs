use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Deserialize)]
pub struct CreateCallRequest {
    pub callee_user_id: Uuid,
    /// Optional device-scoped idempotency key. Legacy callers omit it.
    pub request_id: Option<Uuid>,
    /// "audio" or "video" - validated against the same CHECK constraint the
    /// DB enforces, so an invalid value fails fast with a clear 400 instead
    /// of a confusing DB constraint-violation 500.
    pub media_kind: String,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct CallDto {
    pub id: Uuid,
    pub caller_user_id: Uuid,
    #[serde(skip)]
    pub caller_device_id: Option<Uuid>,
    #[serde(skip)]
    pub caller_session_family: Option<Uuid>,
    pub callee_user_id: Uuid,
    pub media_kind: String,
    pub state: String,
    pub end_reason: Option<String>,
    pub accepted_device_id: Option<Uuid>,
    pub created_at: DateTime<Utc>,
    pub accepted_at: Option<DateTime<Utc>>,
    pub connected_at: Option<DateTime<Utc>>,
    pub ended_at: Option<DateTime<Utc>>,
    #[sqlx(default)]
    pub chat_id: Option<Uuid>,
    #[sqlx(default)]
    pub chat_visible: bool,
    #[sqlx(default)]
    pub unread: bool,
    /// Caller-only, while ringing: a ringing confirmation can be expected
    /// (docs/CALL_RINGING_SPEC.md §2.3). Omitted for the callee and after ringing.
    #[sqlx(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_ringing_expected: Option<bool>,
    /// Caller-only, while ringing: some callee device confirmed it is ringing,
    /// revealed only after the timing floor (§2.5). Omitted like the field above.
    #[sqlx(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_ringing: Option<bool>,
    /// Participant-scoped public identity, independent of chat visibility.
    #[sqlx(skip)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer: Option<crate::users::models::PublicUser>,
}

pub const MEDIA_KINDS: [&str; 2] = ["audio", "video"];

#[derive(Debug, Deserialize)]
pub struct CallHistoryQuery {
    pub other_user_id: Option<Uuid>,
    pub before: Option<Uuid>,
    pub limit: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct CallHistoryResponse {
    pub history_restored: bool,
    pub visible_since: DateTime<Utc>,
    pub items: Vec<CallDto>,
    pub next_cursor: Option<Uuid>,
}

pub const RINGING_TIMEOUT_SECONDS: i64 = 40;
pub const CONNECTING_TIMEOUT_SECONDS: i64 = 30;
/// The ringing bit never reaches the caller earlier than this after the callee
/// was sent `call.ringing` (docs/CALL_RINGING_SPEC.md §2.5).
pub const RINGING_SIGNAL_FLOOR_MS: i64 = 2500;
