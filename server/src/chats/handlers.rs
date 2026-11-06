use axum::extract::{Path, Query, State};
use axum::Json;
use chrono::{DateTime, Utc};
use sqlx::PgPool;
use uuid::Uuid;

use crate::auth::middleware::AuthUser;
use crate::chats::models::{ChatListResponse, ChatSummary, ChatType, CreateDirectChatRequest, ListChatsQuery};
use crate::error::AppError;
use crate::state::AppState;
use crate::users::models::PublicUser;
use serde::Deserialize;

const LIST_LIMIT_DEFAULT: i64 = 20;
const LIST_LIMIT_MAX: i64 = 50;

#[derive(Deserialize)]
pub struct MuteChatRequest {
    #[serde(default)]
    pub muted_until: Option<DateTime<Utc>>,
}

#[derive(Deserialize)]
pub struct BlockChatRequest {
    pub blocked_user_id: Uuid,
}

#[derive(Deserialize)]
pub struct ReportChatRequest {
    pub reason: String,
    pub reported_user_id: Option<Uuid>,
    pub description: Option<String>,
}

#[derive(sqlx::FromRow)]
struct ChatSummaryRow {
    chat_id: Uuid,
    chat_type: ChatType,
    created_at: DateTime<Utc>,
    last_message_at: Option<DateTime<Utc>>,
    other_user_id: Option<Uuid>,
    other_username: Option<String>,
    other_display_name: Option<String>,
    other_avatar_url: Option<String>,
    other_avatar_key: Option<String>,
    other_profile_version: Option<i64>,
    other_avatar_version: Option<i64>,
    other_member_delivered_sequence: i64,
    other_member_read_sequence: i64,
    own_read_sequence: i64,
    is_muted: bool,
    muted_until: Option<DateTime<Utc>>,
    is_blocked: bool,
    blocked_user_id: Option<Uuid>,
}

impl From<ChatSummaryRow> for ChatSummary {
    fn from(row: ChatSummaryRow) -> Self {
        let other_user = match (row.other_user_id, row.other_username, row.other_display_name) {
            (Some(id), Some(username), Some(display_name)) => {
                Some(PublicUser {
                    id,
                    username,
                    display_name,
                    bio: None,
                    avatar_url: row.other_avatar_url,
                    profile_version: row.other_profile_version.unwrap_or(1),
                    avatar_version: row.other_avatar_version.unwrap_or(0),
                    has_avatar: row.other_avatar_key.is_some(),
                })
            }
            _ => None,
        };
        ChatSummary {
            chat_id: row.chat_id,
            chat_type: row.chat_type,
            other_user,
            created_at: row.created_at,
            last_message_at: row.last_message_at,
            other_member_delivered_sequence: row.other_member_delivered_sequence,
            other_member_read_sequence: row.other_member_read_sequence,
            own_read_sequence: row.own_read_sequence,
            is_muted: row.is_muted,
            muted_until: row.muted_until,
            is_blocked: row.is_blocked,
            blocked_user_id: row.blocked_user_id,
        }
    }
}

/// Direct-chat-aware projection: for `type = 'direct'`, the "other side" is
/// computed straight from `user_a_id`/`user_b_id` on the chat row itself, so
/// no self-join against `chat_members` is needed to find them. Never selects
/// message content - only the `last_message_at` timestamp the
/// `messages_touch_chat` trigger maintains. The two `other_member_*_sequence`
/// scalar subqueries are the receipt-reconciliation HTTP recovery path (see
/// `ChatSummary` docs) - `mr.user_id = ou.id` naturally evaluates to no rows
/// (=> COALESCE 0) for group chats, where `ou.id` is NULL.
async fn fetch_chat_summary(db: &PgPool, chat_id: Uuid, requester_id: Uuid, requester_device: Option<Uuid>) -> Result<Option<ChatSummary>, AppError> {
    let row: Option<ChatSummaryRow> = sqlx::query_as(
        "SELECT c.id AS chat_id, c.type AS chat_type, c.created_at, c.last_message_at, \
                ou.id AS other_user_id, ou.username AS other_username, \
                ou.display_name AS other_display_name, ou.avatar_url AS other_avatar_url, \
                ou.avatar_key AS other_avatar_key, ou.profile_version AS other_profile_version, \
                ou.avatar_version AS other_avatar_version, \
                COALESCE(cp.is_muted AND (cp.muted_until IS NULL OR cp.muted_until > now()), false) AS is_muted, cp.muted_until, \
                CASE WHEN c.type='direct' THEN ub.blocked_user_id IS NOT NULL ELSE COALESCE(cp.is_blocked, false) END AS is_blocked, \
                CASE WHEN c.type='direct' THEN ub.blocked_user_id ELSE cp.blocked_user_id END AS blocked_user_id, \
                COALESCE((SELECT MAX(mr.sequence) FILTER (WHERE mr.delivered_at IS NOT NULL) \
                          FROM message_receipts mr JOIN messages m2 ON m2.id = mr.message_id \
                          WHERE m2.chat_id = c.id AND m2.sender_id = $2 AND mr.user_id = ou.id), 0) \
                    AS other_member_delivered_sequence, \
                COALESCE((SELECT MAX(mr.sequence) FILTER (WHERE mr.exposed_read_at IS NOT NULL) \
                          FROM message_receipts mr JOIN messages m2 ON m2.id = mr.message_id \
                          WHERE m2.chat_id = c.id AND m2.sender_id = $2 AND mr.user_id = ou.id), 0) \
                    AS other_member_read_sequence, \
                COALESCE((SELECT MAX(mr.sequence) FROM message_receipts mr \
                          JOIN messages m2 ON m2.id = mr.message_id \
                          WHERE m2.chat_id = c.id AND mr.user_id = cm.user_id \
                            AND mr.read_at IS NOT NULL), 0) AS own_read_sequence \
         FROM chat_members cm \
         JOIN chats c ON c.id = cm.chat_id \
         LEFT JOIN chat_preferences cp ON cp.chat_id = c.id AND cp.user_id = $2 \
         LEFT JOIN users ou ON ou.id = CASE WHEN c.type = 'direct' \
             THEN (CASE WHEN c.user_a_id = $2 THEN c.user_b_id ELSE c.user_a_id END) \
             ELSE NULL END AND NOT user_pair_blocked($2,ou.id) AND ($3::uuid IS NULL OR NOT user_device_blocked($3,ou.id)) \
         LEFT JOIN user_blocks ub ON ub.user_id=$2 AND ub.blocked_user_id=CASE WHEN c.type='direct' \
             THEN CASE WHEN c.user_a_id=$2 THEN c.user_b_id ELSE c.user_a_id END ELSE NULL END \
         WHERE cm.chat_id = $1 AND cm.user_id = $2 AND cm.left_at IS NULL",
    )
    .bind(chat_id)
    .bind(requester_id)
    .bind(requester_device)
    .fetch_optional(db)
    .await?;
    Ok(row.map(Into::into))
}

/// `POST /chats/direct` - the only way to create a 1:1 chat. `other_user_id`
/// is the sole client input; the requester's own identity always comes from
/// the validated access token (`AuthUser`), never from the request body.
///
/// Race safety: two concurrent requests for the same pair rely entirely on
/// the partial unique index `chats_direct_pair_key` (Этап A migration) via
/// `INSERT ... ON CONFLICT ... DO NOTHING`. Postgres's conflict handling
/// blocks a losing transaction until the winner commits, then re-checks -
/// so the follow-up SELECT below is guaranteed to see the winner's row and
/// its members. No application-level locking is needed.
pub async fn create_direct(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<CreateDirectChatRequest>,
) -> Result<Json<ChatSummary>, AppError> {
    if req.other_user_id == auth.user_id {
        return Err(AppError::Validation("Cannot create a chat with yourself.".into()));
    }
    crate::users::blocks::require_visible(&state, &auth, req.other_user_id).await?;

    let other_exists: Option<(Uuid,)> = sqlx::query_as("SELECT id FROM users WHERE id = $1 AND deleted_at IS NULL")
        .bind(req.other_user_id)
        .fetch_optional(&state.db)
        .await?;
    if other_exists.is_none() {
        return Err(AppError::NotFound("User not found.".into()));
    }

    // Canonical ordering matches the CHECK constraint on `chats`
    // (`user_a_id < user_b_id`), so A→B and B→A always target the same row.
    let (user_a, user_b) =
        if auth.user_id < req.other_user_id { (auth.user_id, req.other_user_id) } else { (req.other_user_id, auth.user_id) };

    let mut tx = state.db.begin().await?;

    let inserted: Option<(Uuid,)> = sqlx::query_as(
        "INSERT INTO chats (type, user_a_id, user_b_id) VALUES ('direct', $1, $2) \
         ON CONFLICT (user_a_id, user_b_id) WHERE type = 'direct' DO NOTHING \
         RETURNING id",
    )
    .bind(user_a)
    .bind(user_b)
    .fetch_optional(&mut *tx)
    .await?;

    let chat_id = match inserted {
        Some((id,)) => {
            sqlx::query("INSERT INTO chat_members (chat_id, user_id) VALUES ($1, $2), ($1, $3)")
                .bind(id)
                .bind(user_a)
                .bind(user_b)
                .execute(&mut *tx)
                .await?;
            id
        }
        None => {
            let (id,): (Uuid,) =
                sqlx::query_as("SELECT id FROM chats WHERE user_a_id = $1 AND user_b_id = $2 AND type = 'direct'")
                    .bind(user_a)
                    .bind(user_b)
                    .fetch_one(&mut *tx)
                    .await?;
            id
        }
    };

    tx.commit().await?;

    let summary = fetch_chat_summary(&state.db, chat_id, auth.user_id, Some(auth.device_id)).await?.ok_or(AppError::Internal)?;
    Ok(Json(summary))
}

/// `GET /chats/saved` - idempotent get-or-create of the requester's private
/// Saved Messages chat (M.35). Race safety is the same pattern as
/// `create_direct`: the partial unique index `chats_saved_owner_key`
/// (`UNIQUE(owner_user_id) WHERE type='saved'`) via `INSERT ... ON CONFLICT
/// DO NOTHING`, then a fallback SELECT that is guaranteed to see the winner's
/// row. Concurrent callers (20 in a burst) can never create a second row.
/// Only ever creates a chat owned by the authenticated user - never accepts
/// a target user id from the request.
pub async fn get_or_create_saved(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<ChatSummary>, AppError> {
    // Fast path: an already-existing saved chat needs no transaction.
    if let Some(summary) = fetch_saved_summary(&state.db, auth.user_id).await? {
        return Ok(Json(summary));
    }

    let mut tx = state.db.begin().await?;

    let inserted: Option<(Uuid,)> = sqlx::query_as(
        "INSERT INTO chats (type, owner_user_id) VALUES ('saved', $1) \
         ON CONFLICT (owner_user_id) WHERE type = 'saved' DO NOTHING \
         RETURNING id",
    )
    .bind(auth.user_id)
    .fetch_optional(&mut *tx)
    .await?;

    let chat_id = match inserted {
        Some((id,)) => {
            // Exactly one member: the owner. No other membership paths exist
            // for saved chats (M.36: no add/remove member).
            sqlx::query("INSERT INTO chat_members (chat_id, user_id) VALUES ($1, $2)")
                .bind(id)
                .bind(auth.user_id)
                .execute(&mut *tx)
                .await?;
            id
        }
        None => {
            let (id,): (Uuid,) =
                sqlx::query_as("SELECT id FROM chats WHERE owner_user_id = $1 AND type = 'saved'")
                    .bind(auth.user_id)
                    .fetch_one(&mut *tx)
                    .await?;
            id
        }
    };

    tx.commit().await?;

    let summary = fetch_chat_summary(&state.db, chat_id, auth.user_id, Some(auth.device_id)).await?.ok_or(AppError::Internal)?;
    Ok(Json(summary))
}

async fn fetch_saved_summary(db: &PgPool, owner_id: Uuid) -> Result<Option<ChatSummary>, AppError> {
    let chat_id: Option<(Uuid,)> =
        sqlx::query_as("SELECT id FROM chats WHERE owner_user_id = $1 AND type = 'saved'")
            .bind(owner_id)
            .fetch_optional(db)
            .await?;
    match chat_id {
        Some((id,)) => fetch_chat_summary(db, id, owner_id, None).await,
        None => Ok(None),
    }
}

/// `GET /chats` - only chats the requester is currently a member of.
/// Ordered by `last_message_at DESC` (chats with no messages yet sort last),
/// `chat_id DESC` as a stable tie-breaker.
///
/// Pagination is offset-based rather than cursor-based: `last_message_at`
/// changes every time a chat receives a new message, which would force a
/// keyset cursor to encode a NULL-able, frequently-reordered column - doable,
/// but real complexity for no real benefit at this scale (a user's chat list
/// is at most a few hundred rows). Offset pagination's usual downside (a row
/// shifting between pages as data changes) is a minor cosmetic issue here,
/// not a correctness problem like it would be for message history sync -
/// that will use the monotonic `messages.sequence` column instead.
pub async fn list_chats(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<ListChatsQuery>,
) -> Result<Json<ChatListResponse>, AppError> {
    let limit = query.limit.unwrap_or(LIST_LIMIT_DEFAULT).clamp(1, LIST_LIMIT_MAX);
    let offset = query.offset.unwrap_or(0).max(0);
    let chat_ids = query.chat_ids.as_deref().map(parse_known_chat_ids).transpose()?;
    let visible_since = crate::auth::history::visible_since(&state.db, &auth).await?;

    let rows: Vec<ChatSummaryRow> = sqlx::query_as(
        "SELECT c.id AS chat_id, c.type AS chat_type, c.created_at, c.last_message_at, \
                ou.id AS other_user_id, ou.username AS other_username, \
                ou.display_name AS other_display_name, ou.avatar_url AS other_avatar_url, \
                ou.avatar_key AS other_avatar_key, ou.profile_version AS other_profile_version, \
                ou.avatar_version AS other_avatar_version, \
                COALESCE(cp.is_muted AND (cp.muted_until IS NULL OR cp.muted_until > now()), false) AS is_muted, cp.muted_until, \
                CASE WHEN c.type='direct' THEN ub.blocked_user_id IS NOT NULL ELSE COALESCE(cp.is_blocked, false) END AS is_blocked, \
                CASE WHEN c.type='direct' THEN ub.blocked_user_id ELSE cp.blocked_user_id END AS blocked_user_id, \
                COALESCE((SELECT MAX(mr.sequence) FILTER (WHERE mr.delivered_at IS NOT NULL) \
                          FROM message_receipts mr JOIN messages m2 ON m2.id = mr.message_id \
                          WHERE m2.chat_id = c.id AND m2.sender_id = $1 AND mr.user_id = ou.id), 0) \
                    AS other_member_delivered_sequence, \
                COALESCE((SELECT MAX(mr.sequence) FILTER (WHERE mr.exposed_read_at IS NOT NULL) \
                          FROM message_receipts mr JOIN messages m2 ON m2.id = mr.message_id \
                          WHERE m2.chat_id = c.id AND m2.sender_id = $1 AND mr.user_id = ou.id), 0) \
                    AS other_member_read_sequence, \
                COALESCE((SELECT MAX(mr.sequence) FROM message_receipts mr \
                          JOIN messages m2 ON m2.id = mr.message_id \
                          WHERE m2.chat_id = c.id AND mr.user_id = cm.user_id \
                            AND mr.read_at IS NOT NULL), 0) AS own_read_sequence \
        FROM chat_members cm \
         JOIN chats c ON c.id = cm.chat_id \
         LEFT JOIN chat_preferences cp ON cp.chat_id = c.id AND cp.user_id = $1 \
         LEFT JOIN users ou ON ou.id = CASE WHEN c.type = 'direct' \
             THEN (CASE WHEN c.user_a_id = $1 THEN c.user_b_id ELSE c.user_a_id END) \
             ELSE NULL END AND NOT user_device_blocked($5,ou.id) \
         LEFT JOIN user_blocks ub ON ub.user_id=$1 AND ub.blocked_user_id=CASE WHEN c.type='direct' \
             THEN CASE WHEN c.user_a_id=$1 THEN c.user_b_id ELSE c.user_a_id END ELSE NULL END \
         WHERE cm.user_id = $1 AND cm.left_at IS NULL \
           AND ($4::uuid[] IS NULL OR c.id = ANY($4)) \
           AND ($4::uuid[] IS NOT NULL
                OR c.created_at >= $6
                OR EXISTS (SELECT 1 FROM messages visible_message
                    JOIN message_envelopes visible_envelope ON visible_envelope.message_id = visible_message.id
                    WHERE visible_message.chat_id = c.id AND visible_message.kind = 'content'
                      AND visible_message.deleted_at IS NULL AND visible_envelope.recipient_device_id = $5
                      AND visible_message.created_at >= $6)) \
         ORDER BY c.last_message_at DESC NULLS LAST, c.id DESC \
         LIMIT $2 OFFSET $3",
    )
    .bind(auth.user_id)
    .bind(limit + 1)
    .bind(offset)
    .bind(chat_ids)
    .bind(auth.device_id)
    .bind(visible_since)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() as i64 > limit;
    let items: Vec<ChatSummary> = rows.into_iter().take(limit as usize).map(Into::into).collect();
    let next_offset = if has_more { Some(offset + limit) } else { None };

    Ok(Json(ChatListResponse { items, next_offset }))
}

/// Refresh only conversations already known to this device. A distinct route
/// prevents older servers from silently ignoring a filter and returning history.
pub async fn known_chats(
    state: State<AppState>, auth: AuthUser, Query(query): Query<ListChatsQuery>,
) -> Result<Json<ChatListResponse>, AppError> {
    if query.chat_ids.is_none() {
        return Err(AppError::Validation("chat_ids is required.".into()));
    }
    list_chats(state, auth, Query(query)).await
}

fn parse_known_chat_ids(value: &str) -> Result<Vec<Uuid>, AppError> {
    if value.is_empty() || value.len() > 50 * 37 {
        return Err(AppError::Validation("Provide between 1 and 50 chat IDs.".into()));
    }
    let ids: Vec<Uuid> = value.split(',').map(|id| Uuid::parse_str(id)
        .map_err(|_| AppError::Validation("Invalid chat ID.".into())))
        .collect::<Result<_, _>>()?;
    if ids.len() > 50 {
        return Err(AppError::Validation("Provide between 1 and 50 chat IDs.".into()));
    }
    Ok(ids)
}

/// Read-only lookup for a known caller; never creates a conversation.
pub async fn peer_chat(
    State(state): State<AppState>, auth: AuthUser, Path(peer): Path<Uuid>,
) -> Result<Json<Option<ChatSummary>>, AppError> {
    let id: Option<Uuid> = sqlx::query_scalar(
        "SELECT c.id FROM chats c JOIN chat_members cm ON cm.chat_id=c.id \
         WHERE cm.user_id=$1 AND cm.left_at IS NULL AND c.type='direct' \
           AND ((c.user_a_id=$1 AND c.user_b_id=$2) OR (c.user_a_id=$2 AND c.user_b_id=$1)) LIMIT 1",
    ).bind(auth.user_id).bind(peer).fetch_optional(&state.db).await?;
    let summary = match id {
        Some(id) => fetch_chat_summary(&state.db,id,auth.user_id,Some(auth.device_id)).await?,
        None => None,
    };
    Ok(Json(summary))
}

/// `GET /chats/{chat_id}` - 404s (not 403) for both a nonexistent chat and a
/// chat the requester isn't a member of, so membership can't be probed.
pub async fn get_chat(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
) -> Result<Json<ChatSummary>, AppError> {
    let summary = fetch_chat_summary(&state.db, chat_id, auth.user_id, Some(auth.device_id))
        .await?
        .ok_or_else(|| AppError::NotFound("Chat not found.".into()))?;
    Ok(Json(summary))
}

async fn require_membership(db: &PgPool, chat_id: Uuid, user_id: Uuid) -> Result<(), AppError> {
    let member: Option<(Uuid,)> = sqlx::query_as(
        "SELECT user_id FROM chat_members WHERE chat_id = $1 AND user_id = $2 AND left_at IS NULL",
    )
    .bind(chat_id)
    .bind(user_id)
    .fetch_optional(db)
    .await?;
    if member.is_none() {
        return Err(AppError::NotFound("Chat not found.".into()));
    }
    Ok(())
}

pub async fn set_mute(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Json(req): Json<MuteChatRequest>,
) -> Result<Json<()>, AppError> {
    require_membership(&state.db, chat_id, auth.user_id).await?;
    if req.muted_until.is_some_and(|until| until <= Utc::now()) {
        return Err(AppError::Validation("Mute expiry must be in the future.".into()));
    }
    sqlx::query(
        "INSERT INTO chat_preferences (chat_id, user_id, is_muted, muted_until) \
         VALUES ($1, $2, true, $3) \
         ON CONFLICT (chat_id, user_id) DO UPDATE SET is_muted = true, muted_until = EXCLUDED.muted_until",
    )
    .bind(chat_id)
    .bind(auth.user_id)
    .bind(req.muted_until)
    .execute(&state.db)
    .await?;
    Ok(Json(()))
}

pub async fn clear_mute(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
) -> Result<Json<()>, AppError> {
    require_membership(&state.db, chat_id, auth.user_id).await?;
    sqlx::query("INSERT INTO chat_preferences (chat_id, user_id, is_muted, muted_until) VALUES ($1, $2, false, NULL) \
                 ON CONFLICT (chat_id, user_id) DO UPDATE SET is_muted = false, muted_until = NULL")
        .bind(chat_id)
        .bind(auth.user_id)
        .execute(&state.db)
        .await?;
    Ok(Json(()))
}

pub async fn block_chat(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Json(req): Json<BlockChatRequest>,
) -> Result<Json<()>, AppError> {
    if req.blocked_user_id == auth.user_id {
        return Err(AppError::Validation("Cannot block yourself.".into()));
    }
    require_membership(&state.db, chat_id, auth.user_id).await?;
    let is_member: Option<(bool,)> = sqlx::query_as(
        "SELECT true FROM chat_members WHERE chat_id = $1 AND user_id = $2 AND left_at IS NULL",
    )
    .bind(chat_id)
    .bind(req.blocked_user_id)
    .fetch_optional(&state.db)
    .await?;
    if is_member.is_none() {
        return Err(AppError::Validation("Blocked user is not in this chat.".into()));
    }
    crate::users::blocks::set_block(&state, &auth, req.blocked_user_id, true, Some(chat_id)).await?;
    Ok(Json(()))
}

pub async fn clear_block(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
) -> Result<Json<()>, AppError> {
    require_membership(&state.db, chat_id, auth.user_id).await?;
    let peer: Option<Uuid> = sqlx::query_scalar(
        "SELECT CASE WHEN c.type='direct' THEN CASE WHEN c.user_a_id=$2 THEN c.user_b_id ELSE c.user_a_id END \
         ELSE p.blocked_user_id END FROM chats c LEFT JOIN chat_preferences p ON p.chat_id=c.id AND p.user_id=$2 \
         WHERE c.id=$1",
    ).bind(chat_id).bind(auth.user_id).fetch_one(&state.db).await?;
    if let Some(peer) = peer {
        crate::users::blocks::set_block(&state, &auth, peer, false, None).await?;
    }
    Ok(Json(()))
}

pub async fn report_chat(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(chat_id): Path<Uuid>,
    Json(req): Json<ReportChatRequest>,
) -> Result<Json<()>, AppError> {
    require_membership(&state.db, chat_id, auth.user_id).await?;
    let allowed = ["spam", "harassment", "pornography", "scam", "self_harm", "copyright", "other"];
    let reason = req.reason.trim().to_lowercase();
    if !allowed.contains(&reason.as_str()) {
        return Err(AppError::Validation("Invalid report reason.".into()));
    }

    if let Some(reported_user_id) = req.reported_user_id {
        if reported_user_id == auth.user_id {
            return Err(AppError::Validation("Cannot report yourself.".into()));
        }
        require_membership(&state.db, chat_id, reported_user_id).await?;
    }
    let description = req.description.as_deref().map(str::trim).filter(|value| !value.is_empty());
    if description.is_some_and(|value| value.chars().count() > 4000) {
        return Err(AppError::Validation("Report description is too long.".into()));
    }

    // Serialize submissions by this account so concurrent requests cannot
    // bypass the limit. No IP address or plaintext messages are recorded.
    let mut tx = state.db.begin().await?;
    sqlx::query("SELECT id FROM users WHERE id = $1 FOR UPDATE")
        .bind(auth.user_id).execute(&mut *tx).await?;
    let recent: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM chat_reports WHERE reporter_user_id = $1 AND created_at > now() - interval '1 hour'",
    ).bind(auth.user_id).fetch_one(&mut *tx).await?;
    if recent >= 10 { return Err(AppError::RateLimited); }

    sqlx::query(
        "INSERT INTO chat_reports (chat_id, reporter_user_id, reported_user_id, reason, description) \
         VALUES ($1, $2, $3, $4::chat_report_reason, $5)",
    )
    .bind(chat_id)
    .bind(auth.user_id)
    .bind(req.reported_user_id)
    .bind(reason)
    .bind(description)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(Json(()))
}

#[derive(serde::Serialize)]
pub struct PeerPresence {
    online: bool,
    last_seen_at: Option<chrono::DateTime<chrono::Utc>>,
    observed_at: chrono::DateTime<chrono::Utc>,
    valid_for_ms: u64,
}

pub async fn peer_presence(State(state): State<AppState>, auth: AuthUser, Path(chat_id): Path<Uuid>) -> Result<Json<PeerPresence>, AppError> {
    require_membership(&state.db, chat_id, auth.user_id).await?;
    crate::chats::policy::require_device_contact_allowed(&state.db, chat_id, auth.device_id).await?;
    let peers: Vec<Uuid> = sqlx::query_scalar(
        "SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id!=$2 AND left_at IS NULL")
        .bind(chat_id).bind(auth.user_id).fetch_all(&state.db).await?;
    let items = crate::presence::snapshot_for_device(&state, &auth, &peers).await?;
    if items.is_empty() { return Err(AppError::NotFound("Presence is unavailable.".into())); }
    Ok(Json(PeerPresence { online: items.iter().any(|p| p.online), last_seen_at: items.iter().filter_map(|p| p.last_seen_at).max(),
        observed_at: chrono::Utc::now(), valid_for_ms: crate::presence::VALID_FOR_MS }))
}
