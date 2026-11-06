//! Account privacy preferences. These endpoints never create or restore a chat.
use axum::{
    extract::{Path, Query, State},
    Json,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::{
        middleware::{lock_active_session, AuthUser},
        rate_limit,
    },
    error::AppError,
    net::ClientIp,
    state::AppState,
    users::models::PublicUser,
};

#[derive(Deserialize)]
pub struct BlockedQuery {
    limit: Option<i64>,
    cursor: Option<Uuid>,
    #[serde(default)]
    q: String,
}

#[derive(Serialize)]
pub struct BlockedUser {
    user: PublicUser,
    blocked_at: DateTime<Utc>,
}

#[derive(Serialize)]
pub struct BlockedResponse {
    items: Vec<BlockedUser>,
    next_cursor: Option<Uuid>,
}

#[derive(sqlx::FromRow)]
struct BlockedRow {
    id: Uuid,
    username: String,
    display_name: String,
    bio: Option<String>,
    avatar_url: Option<String>,
    profile_version: i64,
    avatar_version: i64,
    has_avatar: bool,
    blocked_at: DateTime<Utc>,
}

pub async fn list(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Query(query): Query<BlockedQuery>,
) -> Result<Json<BlockedResponse>, AppError> {
    rate_limit::enforce(&state, "blocked:ip", ip, 120, 60).await?;
    rate_limit::enforce(
        &state,
        &format!("blocked:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        60,
        60,
    )
    .await?;
    let q = query.q.trim().trim_start_matches('@');
    if q.chars().count() > 256 {
        return Err(AppError::Validation("Search query is too long.".into()));
    }
    let pattern = format!(
        "%{}%",
        q.replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_")
    );
    let limit = query.limit.unwrap_or(50).clamp(1, 50);
    let rows: Vec<BlockedRow> = sqlx::query_as(
        "SELECT b.blocked_user_id AS id, COALESCE(u.username,'') AS username, \
         COALESCE(u.display_name,CASE WHEN EXISTS(SELECT 1 FROM users actual WHERE actual.id=b.blocked_user_id) \
             THEN 'Недоступный аккаунт' ELSE 'Удалённый аккаунт' END) AS display_name, u.bio, u.avatar_url, \
         COALESCE(u.profile_version,1) AS profile_version, COALESCE(u.avatar_version,0) AS avatar_version, (u.avatar_key IS NOT NULL) AS has_avatar, \
         b.created_at AS blocked_at FROM user_blocks b LEFT JOIN users u ON u.id=b.blocked_user_id AND u.deleted_at IS NULL \
         AND NOT EXISTS(SELECT 1 FROM user_blocks reverse_block WHERE reverse_block.user_id=u.id AND reverse_block.blocked_user_id=$1) \
         AND NOT EXISTS(SELECT 1 FROM user_block_installations restricted JOIN devices viewer ON viewer.verified_installation_id=restricted.installation_id \
             WHERE viewer.id=$5 AND restricted.user_id=u.id) \
         WHERE b.user_id=$1 AND ($2::uuid IS NULL OR b.blocked_user_id>$2) \
         AND (COALESCE(u.username_normalized,'') ILIKE $3 ESCAPE '\\' OR COALESCE(u.display_name,'Удалённый аккаунт') ILIKE $3 ESCAPE '\\') \
         ORDER BY b.blocked_user_id LIMIT $4",
    )
    .bind(auth.user_id)
    .bind(query.cursor)
    .bind(pattern)
    .bind(limit + 1)
    .bind(auth.device_id)
    .fetch_all(&state.db)
    .await?;
    let more = rows.len() > limit as usize;
    let items: Vec<_> = rows
        .into_iter()
        .take(limit as usize)
        .map(|row| BlockedUser {
            user: PublicUser {
                id: row.id,
                username: row.username,
                display_name: row.display_name,
                bio: row.bio,
                avatar_url: row.avatar_url,
                profile_version: row.profile_version,
                avatar_version: row.avatar_version,
                has_avatar: row.has_avatar,
            },
            blocked_at: row.blocked_at,
        })
        .collect();
    let next_cursor = if more {
        items.last().map(|row| row.user.id)
    } else {
        None
    };
    Ok(Json(BlockedResponse { items, next_cursor }))
}

pub async fn block(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(user_id): Path<Uuid>,
) -> Result<Json<()>, AppError> {
    set_block(&state, &auth, user_id, true, None).await?;
    Ok(Json(()))
}

pub async fn unblock(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(user_id): Path<Uuid>,
) -> Result<Json<()>, AppError> {
    set_block(&state, &auth, user_id, false, None).await?;
    Ok(Json(()))
}

/// Also used by legacy chat endpoints after their membership checks. The optional
/// chat id preserves group preference behavior; direct blocks apply account-wide.
pub async fn set_block(
    state: &AppState,
    auth: &AuthUser,
    peer: Uuid,
    blocked: bool,
    legacy_chat: Option<Uuid>,
) -> Result<(), AppError> {
    if peer == auth.user_id {
        return Err(AppError::Validation("Cannot block yourself.".into()));
    }
    rate_limit::enforce(
        state,
        &format!("block-mutation:{}:{}", auth.user_id, blocked),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        120,
        60,
    )
    .await?;
    let mut tx = state.db.begin().await?;
    // Stable order prevents simultaneous A->B and B->A mutations deadlocking on
    // the users foreign keys/account revocation locks.
    sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR UPDATE")
        .bind(vec![auth.user_id, peer])
        .execute(&mut *tx)
        .await?;
    lock_active_session(&mut tx, auth).await?;
    let mut ended_calls: Vec<(Uuid, Uuid, Uuid)> = Vec::new();
    let mut restriction_changed=false;
    if blocked {
        let exists: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)",
        )
        .bind(peer)
        .fetch_one(&mut *tx)
        .await?;
        if !exists {
            return Err(AppError::NotFound("User not found.".into()));
        }
        restriction_changed=sqlx::query("INSERT INTO user_blocks (user_id,blocked_user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING")
            .bind(auth.user_id).bind(peer).execute(&mut *tx).await?.rows_affected()>0;
        let installed=sqlx::query("INSERT INTO user_block_installations(user_id,blocked_user_id,installation_id) \
            SELECT $1,$2,installation_id FROM verified_installation_users WHERE user_id=$2 ON CONFLICT DO NOTHING")
            .bind(auth.user_id).bind(peer).execute(&mut *tx).await?.rows_affected()>0;
        restriction_changed|=installed;
        // Keep old chat summaries compatible, but never create a conversation.
        sqlx::query("INSERT INTO chat_preferences (chat_id,user_id,is_blocked,blocked_user_id) \
            SELECT c.id,$1,true,$2 FROM chats c JOIN chat_members m ON m.chat_id=c.id AND m.user_id=$1 AND m.left_at IS NULL \
            WHERE (c.type='direct' AND ((c.user_a_id=$1 AND c.user_b_id=$2) OR (c.user_a_id=$2 AND c.user_b_id=$1))) \
               OR c.id=$3 \
            ON CONFLICT (chat_id,user_id) DO UPDATE SET is_blocked=true,blocked_user_id=EXCLUDED.blocked_user_id")
            .bind(auth.user_id).bind(peer).bind(legacy_chat).execute(&mut *tx).await?;
        // End only this explicit account pair. An alias account's independent
        // clean-phone call must never be ended by a different source block.
        ended_calls=sqlx::query_as("UPDATE calls SET state='ended',end_reason='hangup',ended_at=now() \
            WHERE state<>'ended' AND ((caller_user_id=$1 AND callee_user_id=$2) OR (caller_user_id=$2 AND callee_user_id=$1)) \
            RETURNING id,caller_user_id,callee_user_id")
            .bind(auth.user_id).bind(peer).fetch_all(&mut *tx).await?;
        for (call, _, _) in &ended_calls {
            sqlx::query("DELETE FROM call_participants_active WHERE call_id=$1")
                .bind(call)
                .execute(&mut *tx)
                .await?;
        }
    } else {
        sqlx::query("UPDATE chat_preferences SET is_blocked=false,blocked_user_id=NULL WHERE user_id=$1 AND blocked_user_id=$2 AND is_blocked")
            .bind(auth.user_id).bind(peer).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM user_blocks WHERE user_id=$1 AND blocked_user_id=$2")
            .bind(auth.user_id)
            .bind(peer)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    if restriction_changed { crate::conferences::after_user_security_change(state,auth.user_id).await; }
    for (call, caller, callee) in ended_calls {
        let event = crate::websocket::protocol::ServerMessage::CallEnded {
            call_id: call,
            reason: "hangup".into(),
        };
        crate::websocket::notify_user(state, caller, &event).await;
        crate::websocket::notify_user(state, callee, &event).await;
        state
            .push
            .enqueue_for_users(
                &state.db,
                &[caller, callee],
                None,
                &crate::push::PushEvent::call_ended(call, "hangup"),
            )
            .await;
    }
    crate::websocket::notify_user(
        state,
        auth.user_id,
        &crate::websocket::protocol::ServerMessage::ChatManagementChanged,
    )
    .await;
    Ok(())
}

/// Public profile access must not bypass the directory/presence privacy filter.
pub async fn require_visible(
    state: &AppState,
    viewer: &AuthUser,
    peer: Uuid,
) -> Result<(), AppError> {
    let blocked: bool = sqlx::query_scalar("SELECT user_device_blocked($1,$2)")
        .bind(viewer.device_id)
        .bind(peer)
        .fetch_one(&state.db)
        .await?;
    if blocked {
        return Err(AppError::NotFound("User not found.".into()));
    }
    Ok(())
}

/// Apply privacy at fan-out as well as admission. This also protects an alias
/// account on a restricted installation while its independent phone still works.
pub async fn allows_event(
    db: &sqlx::PgPool,
    device: Uuid,
    event: &crate::websocket::protocol::ServerMessage,
) -> bool {
    use crate::websocket::protocol::ServerMessage::*;
    let chat = match event {
        MessageNew { message } | MessageSent { message } => Some(message.chat_id),
        ChatActivity { chat_id, .. }
        | ChatDraft { chat_id, .. }
        | ReceiptUpdated { chat_id, .. } => Some(*chat_id),
        _ => None,
    };
    if let Some(chat) = chat {
        return sqlx::query_scalar::<_, bool>("SELECT NOT user_device_chat_blocked($1,$2)")
            .bind(device)
            .bind(chat)
            .fetch_one(db)
            .await
            .unwrap_or(false);
    }
    let call = match event {
        CallRinging { call_id, .. }
        | CallOffer { call_id, .. }
        | CallAnswer { call_id, .. }
        | CallIce { call_id, .. }
        | CallAccepted { call_id }
        | CallAnsweredElsewhere { call_id }
        | CallVideoUpgradeRequest { call_id }
        | CallVideoUpgradeAccept { call_id }
        | CallVideoUpgradeReject { call_id }
        | CallVideoUpgradeCancel { call_id }
        | CallVideoState { call_id, .. } => Some(*call_id),
        _ => None,
    };
    if let Some(call) = call {
        return sqlx::query_scalar::<_, bool>("SELECT NOT user_device_call_blocked($1,$2)")
            .bind(device)
            .bind(call)
            .fetch_one(db)
            .await
            .unwrap_or(false);
    }
    // Terminal call cleanup contains no identity/SDP and must still dismiss an
    // earlier ringing UI if the participant was blocked during that call.
    true
}

pub async fn allows_push(db: &sqlx::PgPool, device: Uuid, event: &crate::push::PushEvent) -> bool {
    let (field, sql) = match event.event.as_str() {
        "new_message" => ("chat_id", "SELECT NOT user_device_chat_blocked($1,$2)"),
        "incoming_call" => ("call_id", "SELECT NOT user_device_call_blocked($1,$2)"),
        _ => return true,
    };
    let Some(id) = event
        .data
        .get(field)
        .and_then(|id| Uuid::parse_str(id).ok())
    else {
        return false;
    };
    sqlx::query_scalar::<_, bool>(sql)
        .bind(device)
        .bind(id)
        .fetch_one(db)
        .await
        .unwrap_or(false)
}
