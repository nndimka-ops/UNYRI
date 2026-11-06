use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Deserialize)]
pub struct CreateCallRequest {
    pub callee_user_id: Uuid,
    /// "audio" or "video" - validated against the same CHECK constraint the
    /// DB enforces, so an invalid value fails fast with a clear 400 instead
    /// of a confusing DB constraint-violation 500.
    pub media_kind: String,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct CallDto {
    pub id: Uuid,
    pub caller_user_id: Uuid,
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
