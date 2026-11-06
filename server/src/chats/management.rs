use axum::{extract::State, Json};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use crate::{auth::middleware::AuthUser, error::AppError, state::AppState};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PinRequest { pub chat_ids: Vec<Uuid>, pub pinned: bool }
#[derive(Serialize)]
pub struct Pins { pub chat_ids: Vec<Uuid> }

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ArchiveRequest {
    pub chat_ids: Vec<Uuid>,
    pub archived: bool,
}

#[derive(Serialize)]
pub struct Archives {
    pub chat_ids: Vec<Uuid>,
}

/// Only identifiers are returned. Clients intersect them with locally known
/// conversations; this preference endpoint never restores hidden chat history.
pub async fn archives(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<Archives>, AppError> {
    let ids = sqlx::query_scalar(
        "SELECT p.chat_id FROM chat_preferences p \
         JOIN chats c ON c.id=p.chat_id \
         JOIN chat_members m ON m.chat_id=p.chat_id AND m.user_id=p.user_id \
         WHERE p.user_id=$1 AND p.is_archived AND c.type='direct' \
         AND m.left_at IS NULL ORDER BY p.chat_id",
    )
    .bind(auth.user_id)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(Archives { chat_ids: ids }))
}

/// Archive is a user-owned presentation preference. It does not leave the
/// conversation, change notification settings, or remove any messages/calls.
/// Incoming messages deliberately do not auto-unarchive a conversation.
pub async fn set_archives(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<ArchiveRequest>,
) -> Result<Json<Archives>, AppError> {
    if req.chat_ids.is_empty() || req.chat_ids.len() > 100 {
        return Err(AppError::Validation("Select between 1 and 100 chats.".into()));
    }
    let mut ids = req.chat_ids;
    ids.sort();
    ids.dedup();
    let mut tx = state.db.begin().await?;
    // The same account-before-chat order as pins/deletion. Recheck the session
    // after waiting; validate every target before persisting any preference.
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let members: Vec<Uuid> = sqlx::query_scalar(
        "SELECT c.id FROM chats c JOIN chat_members m ON m.chat_id=c.id \
         WHERE c.id=ANY($1) AND c.type='direct' AND m.user_id=$2 \
         AND m.left_at IS NULL ORDER BY c.id FOR UPDATE OF c",
    )
    .bind(&ids)
    .bind(auth.user_id)
    .fetch_all(&mut *tx)
    .await?;
    if members != ids {
        return Err(AppError::ChatNotFound);
    }
    for id in ids {
        sqlx::query(
            "INSERT INTO chat_preferences(chat_id,user_id,is_archived) VALUES($1,$2,$3) \
             ON CONFLICT(chat_id,user_id) DO UPDATE SET is_archived=EXCLUDED.is_archived \
             WHERE chat_preferences.is_archived IS DISTINCT FROM EXCLUDED.is_archived",
        )
        .bind(id)
        .bind(auth.user_id)
        .bind(req.archived)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    crate::websocket::notify_user(
        &state,
        auth.user_id,
        &crate::websocket::protocol::ServerMessage::ChatManagementChanged,
    )
    .await;
    archives(State(state), auth).await
}

pub async fn pins(State(state): State<AppState>, auth: AuthUser) -> Result<Json<Pins>, AppError> {
    let ids = sqlx::query_scalar("SELECT p.chat_id FROM chat_preferences p JOIN chat_members m ON m.chat_id=p.chat_id AND m.user_id=p.user_id WHERE p.user_id=$1 AND p.is_pinned AND m.left_at IS NULL ORDER BY p.chat_id")
        .bind(auth.user_id).fetch_all(&state.db).await?;
    Ok(Json(Pins { chat_ids: ids }))
}

pub async fn set_pins(State(state): State<AppState>, auth: AuthUser, Json(req): Json<PinRequest>) -> Result<Json<Pins>, AppError> {
    let mut ids = req.chat_ids; ids.sort(); ids.dedup();
    if ids.is_empty() || ids.len() > 100 { return Err(AppError::Validation("Select between 1 and 100 chats.".into())); }
    let mut tx = state.db.begin().await?;
    // Lock the account and chats in a stable order; validate the whole batch before any change.
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let members: Vec<Uuid> = sqlx::query_scalar("SELECT c.id FROM chats c JOIN chat_members m ON m.chat_id=c.id WHERE c.id=ANY($1) AND m.user_id=$2 AND m.left_at IS NULL ORDER BY c.id FOR UPDATE OF c")
        .bind(&ids).bind(auth.user_id).fetch_all(&mut *tx).await?;
    if members != ids { return Err(AppError::NotFound("Chat not found.".into())); }
    for id in ids {
        sqlx::query("INSERT INTO chat_preferences(chat_id,user_id,is_pinned) VALUES($1,$2,$3) ON CONFLICT(chat_id,user_id) DO UPDATE SET is_pinned=EXCLUDED.is_pinned")
            .bind(id).bind(auth.user_id).bind(req.pinned).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    crate::websocket::notify_user(&state, auth.user_id, &crate::websocket::protocol::ServerMessage::ChatManagementChanged).await;
    pins(State(state), auth).await
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeleteRequest { pub for_everyone: bool, pub request_id: Option<Uuid> }

async fn lock_history_owners(tx: &mut sqlx::Transaction<'_,sqlx::Postgres>, auth: &AuthUser, chat_id: Uuid) -> Result<(),AppError> {
    let mut owners: Vec<Uuid> = sqlx::query_scalar("SELECT user_id FROM chat_members WHERE chat_id=$1 AND left_at IS NULL ORDER BY user_id")
        .bind(chat_id).fetch_all(&mut **tx).await?;
    if !owners.contains(&auth.user_id) { owners = vec![auth.user_id]; }
    // Stable order before the chat lock. NO KEY UPDATE allows a legacy peer's
    // FK writes to finish while we wait, preventing opposing-account deadlocks.
    sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR NO KEY UPDATE")
        .bind(owners).fetch_all(&mut **tx).await?;
    crate::auth::middleware::lock_active_user(tx, auth).await
}

pub async fn delete_chat(State(state): State<AppState>, auth: AuthUser,
    axum::extract::Path(chat_id): axum::extract::Path<Uuid>, Json(req): Json<DeleteRequest>) -> Result<Json<()>, AppError> {
    let mut tx = state.db.begin().await?;
    lock_history_owners(&mut tx, &auth, chat_id).await?;
    let kind: Option<String> = sqlx::query_scalar("SELECT type::text FROM chats WHERE id=$1 FOR UPDATE")
        .bind(chat_id).fetch_optional(&mut *tx).await?;
    if kind.is_none() {
        let removed: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM deleted_chats WHERE chat_id=$1 AND user_id=$2)")
            .bind(chat_id).bind(auth.user_id).fetch_one(&mut *tx).await?;
        return if removed { Ok(Json(())) } else { Err(AppError::NotFound("Chat not found.".into())) };
    }
    let members: Vec<Uuid> = sqlx::query_scalar("SELECT user_id FROM chat_members WHERE chat_id=$1 AND left_at IS NULL ORDER BY user_id")
        .bind(chat_id).fetch_all(&mut *tx).await?;
    if !members.contains(&auth.user_id) { return Err(AppError::NotFound("Chat not found.".into())); }
    if kind.as_deref() != Some("direct") { return Err(AppError::Validation("Only direct conversations can be deleted for both participants.".into())); }
    if !req.for_everyone {
        let request_id = req.request_id.ok_or_else(|| AppError::Validation("A deletion request ID is required.".into()))?;
        let inserted = sqlx::query("INSERT INTO personal_clear_requests(user_id,request_id,chat_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING")
            .bind(auth.user_id).bind(request_id).bind(chat_id).execute(&mut *tx).await?.rows_affected();
        if inserted == 0 {
            let previous: Uuid = sqlx::query_scalar("SELECT chat_id FROM personal_clear_requests WHERE user_id=$1 AND request_id=$2")
                .bind(auth.user_id).bind(request_id).fetch_one(&mut *tx).await?;
            if previous != chat_id { return Err(AppError::Validation("Request ID already used.".into())); }
            return Ok(Json(()));
        }
        let cutoff: i64 = sqlx::query_scalar("SELECT COALESCE(MAX(sequence),0) FROM messages WHERE chat_id=$1")
            .bind(chat_id).fetch_one(&mut *tx).await?;
        sqlx::query("INSERT INTO personal_chat_clears(user_id,chat_id,through_sequence,calls_before) VALUES($1,$2,$3,now()) ON CONFLICT(user_id,chat_id) DO UPDATE SET through_sequence=GREATEST(personal_chat_clears.through_sequence,EXCLUDED.through_sequence),calls_before=now()")
            .bind(auth.user_id).bind(chat_id).bind(cutoff).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM message_envelopes WHERE recipient_device_id IN (SELECT id FROM devices WHERE user_id=$1) AND message_id IN (SELECT id FROM messages WHERE chat_id=$2 AND sequence<=$3)")
            .bind(auth.user_id).bind(chat_id).bind(cutoff).execute(&mut *tx).await?;
        tx.commit().await?;
        crate::websocket::notify_user(&state, auth.user_id, &crate::websocket::protocol::ServerMessage::ChatManagementChanged).await;
        crate::websocket::notify_user(&state, auth.user_id, &crate::websocket::protocol::ServerMessage::CallHistoryChanged).await;
        return Ok(Json(()));
    }
    for user in &members {
        sqlx::query("INSERT INTO deleted_chats(user_id,chat_id) VALUES($1,$2) ON CONFLICT DO NOTHING")
            .bind(user).bind(chat_id).execute(&mut *tx).await?;
    }
    let grace = state.config.s3.presign_ttl_seconds.max(0).saturating_add(60).max(86400);
    sqlx::query("INSERT INTO account_object_deletions(object_key,multipart_upload_id,delete_after,reserved_bytes,owner_user_id) SELECT object_key,multipart_upload_id,now()+($2 * interval '1 second'),encrypted_size,uploader_user_id FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE chat_id=$1) ON CONFLICT(object_key) DO UPDATE SET delete_after=GREATEST(account_object_deletions.delete_after,EXCLUDED.delete_after),reserved_bytes=GREATEST(account_object_deletions.reserved_bytes,EXCLUDED.reserved_bytes),owner_user_id=COALESCE(account_object_deletions.owner_user_id,EXCLUDED.owner_user_id)")
        .bind(chat_id).bind(grace as f64).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE chat_id=$1)")
        .bind(chat_id).execute(&mut *tx).await?;
    sqlx::query("UPDATE messages SET target_message_id=NULL WHERE target_message_id IN (SELECT id FROM messages WHERE chat_id=$1)")
        .bind(chat_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM chats WHERE id=$1").bind(chat_id).execute(&mut *tx).await?;
    tx.commit().await?;
    for user in members {
        crate::websocket::notify_user(&state, user, &crate::websocket::protocol::ServerMessage::ChatDeleted { chat_id }).await;
    }
    Ok(Json(()))
}

#[derive(Serialize, sqlx::FromRow)]
pub struct PersonalClear {
    pub chat_id: Uuid,
    pub through_sequence: i64,
    pub calls_before: Option<chrono::DateTime<chrono::Utc>>,
}
pub async fn personal_clears(State(state): State<AppState>, auth: AuthUser) -> Result<Json<Vec<PersonalClear>>, AppError> {
    Ok(Json(sqlx::query_as("SELECT chat_id,through_sequence,calls_before FROM personal_chat_clears WHERE user_id=$1 ORDER BY chat_id")
        .bind(auth.user_id).fetch_all(&state.db).await?))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BoundedClearRequest {
    pub request_id: Uuid,
    pub for_everyone: bool,
    pub through_sequence: i64,
    pub calls_before: Option<chrono::DateTime<chrono::Utc>>,
}

/// Offline intent boundaries are immutable: later arrivals are never removed by a retry.
pub async fn clear_history(State(state): State<AppState>, auth: AuthUser,
    axum::extract::Path(chat_id): axum::extract::Path<Uuid>, Json(req): Json<BoundedClearRequest>) -> Result<Json<()>, AppError> {
    use chrono::Timelike;
    if req.through_sequence < 0 { return Err(AppError::Validation("Invalid history boundary.".into())); }
    let calls_before = req.calls_before.map(|date| date.with_nanosecond(date.nanosecond()/1000*1000).unwrap());
    let mut tx = state.db.begin().await?;
    // Same global feed lock order as single-call deletion, before account/FK locks.
    sqlx::query("SELECT pg_advisory_xact_lock(6840210040)").execute(&mut *tx).await?;
    lock_history_owners(&mut tx, &auth, chat_id).await?;
    let previous: Option<(Uuid,bool,i64,Option<chrono::DateTime<chrono::Utc>>)> = sqlx::query_as(
        "SELECT chat_id,for_everyone,through_sequence,calls_before FROM bounded_chat_clear_requests WHERE user_id=$1 AND request_id=$2")
        .bind(auth.user_id).bind(req.request_id).fetch_optional(&mut *tx).await?;
    if let Some(previous) = previous {
        if previous != (chat_id,req.for_everyone,req.through_sequence,calls_before) {
            return Err(AppError::Conflict("History command ID already used with different boundaries.".into()));
        }
        return Ok(Json(()));
    }
    if calls_before.is_some_and(|date| date > chrono::Utc::now() + chrono::Duration::seconds(1)) {
        return Err(AppError::Validation("Invalid call history boundary.".into()));
    }
    let chat: Option<(String,Option<Uuid>,Option<Uuid>,chrono::DateTime<chrono::Utc>)> = sqlx::query_as(
        "SELECT type::text,user_a_id,user_b_id,created_at FROM chats WHERE id=$1 FOR UPDATE")
        .bind(chat_id).fetch_optional(&mut *tx).await?;
    let (kind, a, b, chat_created_at) = chat.ok_or(AppError::ChatNotFound)?;
    let members: Vec<Uuid> = sqlx::query_scalar("SELECT user_id FROM chat_members WHERE chat_id=$1 AND left_at IS NULL ORDER BY user_id")
        .bind(chat_id).fetch_all(&mut *tx).await?;
    if !members.contains(&auth.user_id) { return Err(AppError::ChatNotFound); }
    if kind != "direct" { return Err(AppError::Validation("Only direct conversation history can be cleared.".into())); }
    let issued: i64 = sqlx::query_scalar("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM messages_sequence_seq")
        .fetch_one(&mut *tx).await?;
    if req.through_sequence > issued { return Err(AppError::Validation("History boundary exceeds the server sequence.".into())); }
    sqlx::query("INSERT INTO bounded_chat_clear_requests(user_id,request_id,chat_id,for_everyone,through_sequence,calls_before) VALUES($1,$2,$3,$4,$5,$6)")
        .bind(auth.user_id).bind(req.request_id).bind(chat_id).bind(req.for_everyone).bind(req.through_sequence).bind(calls_before)
        .execute(&mut *tx).await?;
    let owners = if req.for_everyone { members } else { vec![auth.user_id] };
    for owner in &owners {
        sqlx::query("INSERT INTO personal_chat_clears(user_id,chat_id,through_sequence,calls_before) VALUES($1,$2,$3,$4) ON CONFLICT(user_id,chat_id) DO UPDATE SET through_sequence=GREATEST(personal_chat_clears.through_sequence,EXCLUDED.through_sequence),calls_before=GREATEST(personal_chat_clears.calls_before,EXCLUDED.calls_before)")
            .bind(owner).bind(chat_id).bind(req.through_sequence).bind(calls_before).execute(&mut *tx).await?;
        sqlx::query("UPDATE chat_preferences SET is_pinned=false WHERE user_id=$1 AND chat_id=$2")
            .bind(owner).bind(chat_id).execute(&mut *tx).await?;
    }
    // Serialize deletion feed allocation/commit, as the individual call-delete endpoint does.
    if let Some(before) = calls_before {
        sqlx::query("SELECT pg_advisory_xact_lock(6840210040)").execute(&mut *tx).await?;
        for owner in &owners {
            sqlx::query("INSERT INTO call_history_deletions(user_id,call_id) SELECT $1,id FROM calls WHERE caller_user_id=ANY($2) AND callee_user_id=ANY($2) AND state='ended' AND created_at<=$3 AND created_at>=$4 ON CONFLICT(user_id,call_id) DO NOTHING")
                .bind(owner).bind(vec![a.ok_or(AppError::Internal)?,b.ok_or(AppError::Internal)?]).bind(before).bind(chat_created_at)
                .execute(&mut *tx).await?;
        }
    }
    if req.for_everyone {
        let grace = state.config.s3.presign_ttl_seconds.max(0).saturating_add(60).max(86400);
        sqlx::query("INSERT INTO account_object_deletions(object_key,multipart_upload_id,delete_after,reserved_bytes,owner_user_id) SELECT object_key,multipart_upload_id,now()+($3*interval '1 second'),encrypted_size,uploader_user_id FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE chat_id=$1 AND sequence<=$2) ON CONFLICT(object_key) DO UPDATE SET delete_after=GREATEST(account_object_deletions.delete_after,EXCLUDED.delete_after),reserved_bytes=GREATEST(account_object_deletions.reserved_bytes,EXCLUDED.reserved_bytes),owner_user_id=COALESCE(account_object_deletions.owner_user_id,EXCLUDED.owner_user_id)")
            .bind(chat_id).bind(req.through_sequence).bind(grace as f64).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE chat_id=$1 AND sequence<=$2)")
            .bind(chat_id).bind(req.through_sequence).execute(&mut *tx).await?;
        sqlx::query("UPDATE messages SET target_message_id=NULL WHERE target_message_id IN (SELECT id FROM messages WHERE chat_id=$1 AND sequence<=$2)")
            .bind(chat_id).bind(req.through_sequence).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM messages WHERE chat_id=$1 AND sequence<=$2")
            .bind(chat_id).bind(req.through_sequence).execute(&mut *tx).await?;
        sqlx::query("UPDATE chats SET last_message_at=(SELECT MAX(created_at) FROM messages WHERE chat_id=$1 AND kind='content') WHERE id=$1")
            .bind(chat_id).execute(&mut *tx).await?;
    } else {
        sqlx::query("DELETE FROM message_envelopes WHERE recipient_device_id IN (SELECT id FROM devices WHERE user_id=$1) AND message_id IN (SELECT id FROM messages WHERE chat_id=$2 AND sequence<=$3)")
            .bind(auth.user_id).bind(chat_id).bind(req.through_sequence).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    for owner in owners {
        crate::websocket::notify_user(&state, owner, &crate::websocket::protocol::ServerMessage::ChatManagementChanged).await;
        crate::websocket::notify_user(&state, owner, &crate::websocket::protocol::ServerMessage::CallHistoryChanged).await;
    }
    Ok(Json(()))
}
