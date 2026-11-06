use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::users::models::PublicUser;

/// Mirrors the `chat_type` Postgres enum from the Этап A migration.
#[derive(Debug, Clone, Copy, PartialEq, Eq, sqlx::Type, Serialize)]
#[sqlx(type_name = "chat_type", rename_all = "lowercase")]
#[serde(rename_all = "lowercase")]
pub enum ChatType {
    Direct,
    Group,
    Saved,
}

#[derive(Debug, Deserialize)]
pub struct CreateDirectChatRequest {
    pub other_user_id: Uuid,
}

/// A chat as seen in a chat list or `GET /chats/{id}`. `other_user` is only
/// populated for direct chats - never includes message content, only the
/// `last_message_at` timestamp the `messages_touch_chat` trigger maintains.
/// `other_member_delivered_sequence`/`other_member_read_sequence` are the
/// requester's own receipt-reconciliation view: how far the other member
/// has delivered/read *messages the requester sent* in this chat (0 if
/// nothing yet) - the HTTP recovery path for the realtime `receipt.updated`
/// WS event, in case it was ever missed.
#[derive(Debug, Serialize)]
pub struct ChatSummary {
    pub chat_id: Uuid,
    #[serde(rename = "type")]
    pub chat_type: ChatType,
    pub other_user: Option<PublicUser>,
    pub created_at: DateTime<Utc>,
    pub last_message_at: Option<DateTime<Utc>>,
    pub other_member_delivered_sequence: i64,
    pub other_member_read_sequence: i64,
    pub own_read_sequence: i64,
    pub is_muted: bool,
    pub muted_until: Option<DateTime<Utc>>,
    pub is_blocked: bool,
    pub blocked_user_id: Option<Uuid>,
}

#[derive(Debug, Deserialize)]
pub struct ListChatsQuery {
    pub limit: Option<i64>,
    pub offset: Option<i64>,
    pub chat_ids: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ChatListResponse {
    pub items: Vec<ChatSummary>,
    pub next_offset: Option<i64>,
}
