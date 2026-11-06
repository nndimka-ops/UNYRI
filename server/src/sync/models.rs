use serde::{Deserialize, Serialize};

use crate::messages::models::MessageDto;

#[derive(Debug, Deserialize)]
pub struct SyncQuery {
    pub after_sequence: Option<i64>,
    pub limit: Option<i64>,
}

/// `next_sequence` is always the cursor value the client should send back
/// as `after_sequence` on its next call - the last returned message's
/// `sequence`, or the request's own `after_sequence` unchanged when the
/// page was empty (no progress to report, never move backwards).
#[derive(Debug, Serialize)]
pub struct SyncResponse {
    pub messages: Vec<MessageDto>,
    pub history_cleared_through_sequence: Option<i64>,
    pub history_cleared_before_ms: Option<i64>,
    pub deleted_chat_ids: Vec<uuid::Uuid>,
    pub next_sequence: i64,
    pub has_more: bool,
}
