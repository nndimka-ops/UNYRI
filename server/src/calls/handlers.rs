
use axum::extract::{Path, Query, State};
use axum::Json;
use chrono::{Duration, Utc};
use uuid::Uuid;

use crate::net::ClientIp;
use crate::auth::middleware::AuthUser;
use crate::auth::rate_limit;
use crate::calls::models::{CallDto, CreateCallRequest, CONNECTING_TIMEOUT_SECONDS, MEDIA_KINDS, RINGING_TIMEOUT_SECONDS};
use crate::calls::turn::{build_ice_servers, IceServersResponse};
use crate::error::AppError;
use crate::push::models::PushEvent;
use crate::state::AppState;
use crate::websocket;
use crate::websocket::protocol::ServerMessage;

const CREATE_CALL_LIMIT: u64 = 20;
const CREATE_CALL_WINDOW_SECONDS: u64 = 60;
const ICE_SERVERS_LIMIT: u64 = 30;
const ICE_SERVERS_WINDOW_SECONDS: u64 = 60;

/// Participant-scoped, stable history pagination. Includes calls missed while
/// the client was offline; call metadata is never injected into Signal messages.
pub async fn history(
    State(state): State<AppState>, auth: AuthUser,
    Query(query): Query<crate::calls::models::CallHistoryQuery>,
) -> Result<Json<crate::calls::models::CallHistoryResponse>, AppError> {
    let limit = query.limit.unwrap_or(50).clamp(1, 100);
    let visible_since = crate::auth::history::visible_since(&state.db, &auth).await?;
    let mut items: Vec<CallDto> = sqlx::query_as(
        "SELECT c.*, ch.id AS chat_id,
         (ch.id IS NOT NULL AND ch.created_at<=c.created_at AND (pc.calls_before IS NULL OR c.created_at>pc.calls_before)) AS chat_visible,
         (c.callee_user_id=$1 AND c.accepted_at IS NULL AND COALESCE(c.end_reason IN ('missed','cancelled','busy','failed','timeout'),false)
          AND (pc.calls_before IS NULL OR c.created_at>pc.calls_before)
          AND NOT EXISTS(SELECT 1 FROM call_reads r WHERE r.user_id=$1 AND r.call_id=c.id)) AS unread
         FROM calls c
         LEFT JOIN chats ch ON ch.type='direct' AND ch.user_a_id=LEAST(c.caller_user_id,c.callee_user_id) AND ch.user_b_id=GREATEST(c.caller_user_id,c.callee_user_id)
         LEFT JOIN personal_chat_clears pc ON pc.user_id=$1 AND pc.chat_id=ch.id
         WHERE (c.caller_user_id = $1 OR c.callee_user_id = $1)
         AND c.state = 'ended'
         AND c.created_at >= $5
         AND NOT user_device_call_blocked($6,c.id)
         AND NOT EXISTS (SELECT 1 FROM call_history_deletions d WHERE d.call_id=c.id AND d.user_id=$1)
         AND ($2::uuid IS NULL OR (c.caller_user_id = $2 OR c.callee_user_id = $2))
         AND ($3::uuid IS NULL OR (c.created_at, c.id) < (COALESCE(
             (SELECT p.created_at FROM calls p WHERE p.id=$3
              AND (p.caller_user_id=$1 OR p.callee_user_id=$1)),
             (SELECT d.call_created_at FROM call_history_deletions d
              WHERE d.user_id=$1 AND d.call_id=$3)), $3::uuid))
         ORDER BY c.created_at DESC, c.id DESC LIMIT $4")
        .bind(auth.user_id).bind(query.other_user_id).bind(query.before).bind(limit + 1).bind(visible_since).bind(auth.device_id)
        .fetch_all(&state.db).await?;
    let next_cursor = if items.len() > limit as usize {
        items.truncate(limit as usize);
        items.last().map(|call| call.id)
    } else { None };
    populate_history_peers(&state, auth.user_id, auth.device_id, &mut items).await?;
    // Restore imports authenticated ciphertext locally; it must never unlock
    // unencrypted historical call metadata on the server.
    Ok(Json(crate::calls::models::CallHistoryResponse { items, next_cursor, history_restored: false, visible_since }))
}

/// One query per page, including callers without a currently visible chat.
/// IDs come only from the participant-scoped, installation-filtered history.
async fn populate_history_peers(state: &AppState, owner: Uuid, device: Uuid, calls: &mut [CallDto]) -> Result<(), AppError> {
    let mut ids: Vec<Uuid> = calls.iter().filter_map(|call| {
        if call.caller_user_id == owner { Some(call.callee_user_id) }
        else if call.callee_user_id == owner { Some(call.caller_user_id) }
        else { None }
    }).collect();
    ids.sort(); ids.dedup();
    if ids.is_empty() { return Ok(()); }
    let profiles: Vec<crate::users::models::PublicUser> = sqlx::query_as(
        "SELECT id,username,display_name,bio,avatar_url,profile_version,avatar_version,(avatar_key IS NOT NULL) AS has_avatar FROM users WHERE id=ANY($1) AND deleted_at IS NULL AND NOT user_device_blocked($2,id)")
        .bind(ids).bind(device).fetch_all(&state.db).await?;
    let profiles: std::collections::HashMap<_,_> = profiles.into_iter().map(|profile| (profile.id,profile)).collect();
    for call in calls {
        let peer = if call.caller_user_id == owner { Some(call.callee_user_id) }
            else if call.callee_user_id == owner { Some(call.caller_user_id) } else { None };
        call.peer = peer.and_then(|id| profiles.get(&id).cloned());
    }
    Ok(())
}

#[derive(serde::Deserialize)]
pub struct DeleteHistoryRequest { pub everyone: bool }
#[derive(serde::Deserialize)]
pub struct DeletionQuery { pub after: Option<i64> }
#[derive(serde::Serialize, sqlx::FromRow)]
pub struct HistoryDeletion { pub sequence: i64, pub call_id: Uuid }
#[derive(serde::Serialize)]
pub struct DeletionPage { pub items: Vec<HistoryDeletion>, pub has_more: bool }

pub async fn history_deletions(
    State(state): State<AppState>, auth: AuthUser, Query(query): Query<DeletionQuery>,
) -> Result<Json<DeletionPage>, AppError> {
    // Recipient-owned opaque deletion IDs must also reach a new login which
    // restored an older encrypted archive before this deletion happened.
    // Metadata/history endpoints retain their separate login visibility floor.
    let mut items: Vec<HistoryDeletion> = sqlx::query_as(
        "SELECT d.sequence, d.call_id FROM call_history_deletions d
         WHERE d.user_id=$1 AND d.sequence>$2
         ORDER BY d.sequence LIMIT 201")
        .bind(auth.user_id).bind(query.after.unwrap_or(0).max(0)).fetch_all(&state.db).await?;
    let has_more = items.len() > 200;
    items.truncate(200);
    Ok(Json(DeletionPage { items, has_more }))
}

pub async fn delete_history(
    State(state): State<AppState>, auth: AuthUser, Path(call_id): Path<Uuid>,
    ClientIp(addr): ClientIp, Json(request): Json<DeleteHistoryRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    rate_limit::enforce(&state, "call_history_delete", addr, 300, 60).await?;
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if call.state != "ended" { return Err(AppError::Conflict("Only completed calls can be removed from history.".into())); }
    let mut tx = state.db.begin().await?;
    // Serialize allocation/commit of feed sequences: a later commit must never
    // advance a client's cursor past an earlier uncommitted deletion.
    sqlx::query("SELECT pg_advisory_xact_lock(6840210040)").execute(&mut *tx).await?;
    let owners = if request.everyone { vec![call.caller_user_id, call.callee_user_id] } else { vec![auth.user_id] };
    let mut changed_owners = Vec::new();
    for owner in owners {
        let changed = sqlx::query("INSERT INTO call_history_deletions(user_id,call_id) VALUES ($1,$2) ON CONFLICT(user_id,call_id) DO NOTHING")
            .bind(owner).bind(call_id).execute(&mut *tx).await?.rows_affected();
        if changed > 0 { changed_owners.push(owner); }
    }
    tx.commit().await?;
    for owner in changed_owners {
        websocket::notify_user(&state, owner, &ServerMessage::CallHistoryChanged).await;
    }
    Ok(Json(serde_json::json!({"ok": true})))
}

/// 404 (never 403) for "doesn't exist" AND "you're not a participant" - same
/// anti-probing shape used throughout the rest of the API (Этап I п.58).
fn not_found() -> AppError {
    AppError::NotFound("Call not found.".into())
}

async fn fetch_call(state: &AppState, call_id: Uuid, requester_id: Uuid) -> Result<CallDto, AppError> {
    let call: Option<CallDto> = sqlx::query_as(
        "SELECT c.*, ch.id AS chat_id,
         (ch.id IS NOT NULL AND ch.created_at<=c.created_at AND (pc.calls_before IS NULL OR c.created_at>pc.calls_before)) AS chat_visible,
         (c.callee_user_id=$2 AND c.state='ended' AND c.accepted_at IS NULL
          AND COALESCE(c.end_reason IN ('missed','cancelled','busy','failed','timeout'),false)
          AND (pc.calls_before IS NULL OR c.created_at>pc.calls_before)
          AND NOT EXISTS(SELECT 1 FROM call_reads r WHERE r.user_id=$2 AND r.call_id=c.id)) AS unread
         FROM calls c
         LEFT JOIN chats ch ON ch.type='direct' AND ch.user_a_id=LEAST(c.caller_user_id,c.callee_user_id) AND ch.user_b_id=GREATEST(c.caller_user_id,c.callee_user_id)
         LEFT JOIN personal_chat_clears pc ON pc.user_id=$2 AND pc.chat_id=ch.id
         WHERE c.id = $1",
    )
    .bind(call_id)
    .bind(requester_id)
    .fetch_optional(&state.db)
    .await?;
    let call = call.ok_or_else(not_found)?;
    if call.caller_user_id != requester_id && call.callee_user_id != requester_id {
        return Err(not_found());
    }
    Ok(call)
}

async fn fetch_device_call(state:&AppState,call_id:Uuid,auth:&AuthUser)->Result<CallDto,AppError> {
    let blocked:bool=sqlx::query_scalar("SELECT user_device_call_blocked($1,$2)")
        .bind(auth.device_id).bind(call_id).fetch_one(&state.db).await?;
    if blocked {return Err(not_found())}
    fetch_call(state,call_id,auth.user_id).await
}

/// `GET /calls/ice-servers` - short-lived TURN credentials, never a
/// permanent secret shipped in the APK (Этап J п.6).
pub async fn ice_servers(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
) -> Result<Json<IceServersResponse>, AppError> {
    rate_limit::enforce(&state, "ice_servers:ip", addr, 120, ICE_SERVERS_WINDOW_SECONDS).await?;
    rate_limit::enforce(
        &state,
        &format!("ice_servers:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        ICE_SERVERS_LIMIT,
        ICE_SERVERS_WINDOW_SECONDS,
    ).await?;
    Ok(Json(IceServersResponse { ice_servers: build_ice_servers(&state.config.turn, auth.user_id) }))
}

/// `POST /calls` - creates a call in `ringing` state and pushes
/// `call.ringing` to every active device of the callee (multi-device
/// incoming, Этап J п.18). Busy/simultaneous-call races are resolved purely
/// by the `call_participants_active` PK collision inside one transaction -
/// no advisory locks (see migration 0006 doc comment).
pub async fn create_call(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Json(body): Json<CreateCallRequest>,
) -> Result<Json<CallDto>, AppError> {
    rate_limit::enforce(&state, "calls_create:ip", addr, 60, CREATE_CALL_WINDOW_SECONDS).await?;
    rate_limit::enforce(
        &state,
        &format!("calls_create:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        CREATE_CALL_LIMIT,
        CREATE_CALL_WINDOW_SECONDS,
    ).await?;

    if !MEDIA_KINDS.contains(&body.media_kind.as_str()) {
        return Err(AppError::Validation("media_kind must be \"audio\" or \"video\".".into()));
    }
    if body.callee_user_id == auth.user_id {
        return Err(AppError::Validation("Cannot call yourself.".into()));
    }

    let callee_exists: Option<(bool,)> =
        sqlx::query_as("SELECT true FROM users WHERE id = $1 AND deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM server_suspensions b WHERE b.user_id=users.id AND (b.until_date IS NULL OR b.until_date>now()))")
            .bind(body.callee_user_id)
            .fetch_optional(&state.db)
            .await?;
    if callee_exists.is_none() {
        return Err(AppError::NotFound("User not found.".into()));
    }

    // Policy: calling requires an existing direct chat with the callee (the
    // call buttons only ever appear in a direct chat header - Этап J п.41).
    let (lo, hi) = if auth.user_id < body.callee_user_id {
        (auth.user_id, body.callee_user_id)
    } else {
        (body.callee_user_id, auth.user_id)
    };
    let chat_exists: Option<(Uuid,)> =
        sqlx::query_as("SELECT id FROM chats WHERE type = 'direct' AND user_a_id = $1 AND user_b_id = $2")
            .bind(lo)
            .bind(hi)
            .fetch_optional(&state.db)
            .await?;
    let (chat_id,) = chat_exists.ok_or_else(|| AppError::NotFound("No chat with this user.".into()))?;
    crate::chats::policy::require_device_contact_allowed(&state.db, chat_id, auth.device_id).await?;

    let call_id = Uuid::new_v4();
    let ringing_deadline = Utc::now() + Duration::seconds(RINGING_TIMEOUT_SECONDS);

    let mut tx = state.db.begin().await?;
    // Lock both accounts in stable order before FK writes: simultaneous A->B
    // and B->A calls must not each hold one account while waiting for the other.
    sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR UPDATE")
        .bind(vec![lo, hi]).fetch_all(&mut *tx).await?;
    crate::chats::policy::lock_contact_allowed(&mut tx, chat_id, auth.device_id).await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let recipient_restricted:bool=sqlx::query_scalar("SELECT \
        EXISTS(SELECT 1 FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND user_device_blocked(id,$2)) \
        AND NOT EXISTS(SELECT 1 FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND NOT user_device_blocked(id,$2))")
        .bind(body.callee_user_id).bind(auth.user_id).fetch_one(&mut *tx).await?;
    if recipient_restricted {return Err(AppError::Conflict("Communication is unavailable for this chat.".into()));}
    let inserted = sqlx::query(
        "INSERT INTO calls (id, caller_user_id, callee_user_id, media_kind, state, ringing_deadline) \
         VALUES ($1, $2, $3, $4, 'ringing', $5)",
    )
    .bind(call_id)
    .bind(auth.user_id)
    .bind(body.callee_user_id)
    .bind(&body.media_kind)
    .bind(ringing_deadline)
    .execute(&mut *tx)
    .await;
    if inserted.is_err() {
        tx.rollback().await.ok();
        return Err(AppError::Internal);
    }

    // Both participant rows in the SAME transaction: if either user is
    // already in another active call, this PK-collides and the whole call
    // creation rolls back atomically - "busy" for both the single-user and
    // simultaneous-pair-race cases (Этап J п.20-21) with zero extra locking.
    let participants = sqlx::query(
        "INSERT INTO call_participants_active (user_id, call_id) VALUES ($1, $3), ($2, $3)",
    )
    .bind(auth.user_id)
    .bind(body.callee_user_id)
    .bind(call_id)
    .execute(&mut *tx)
    .await;
    if participants.is_err() {
        tx.rollback().await.ok();
        return Err(AppError::Conflict("busy".into()));
    }

    tx.commit().await?;

    let call = fetch_device_call(&state, call_id, &auth).await?;
    websocket::notify_user(
        &state,
        body.callee_user_id,
        &ServerMessage::CallRinging { call_id, caller_user_id: auth.user_id, media_kind: body.media_kind.clone() },
    )
    .await;
    // K.4: wake every eligible callee device via FCM (never the caller).
    // Push is a hint only - the WS path above remains the realtime source.
    state
        .push
        .enqueue_for_users(&state.db, &[body.callee_user_id], None, &PushEvent::incoming_call(call_id, &body.media_kind))
        .await;
    Ok(Json(call))
}

/// `GET /calls/active` - authoritative call-state recovery after app
/// restart/WS reconnect/FCM wake (Этап J п.56) - never relies on the client
/// having received every WS event.
pub async fn get_active_call(State(state): State<AppState>, auth: AuthUser) -> Result<Json<Option<CallDto>>, AppError> {
    let call_id: Option<Uuid> = sqlx::query_scalar(
        "SELECT call_id FROM call_participants_active WHERE user_id = $1 AND NOT user_device_call_blocked($2,call_id)",
    )
    .bind(auth.user_id)
    .bind(auth.device_id)
    .fetch_optional(&state.db)
    .await?;
    // Recovery must expose the same canonical chat metadata as ringing/get;
    // defaulting chat_visible to false would suppress an eligible recovered call.
    let call = match call_id {
        Some(id) => Some(fetch_device_call(&state, id, &auth).await?),
        None => None,
    };
    Ok(Json(call))
}

pub async fn get_call(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if call.state == "ended" {
        if call.created_at < crate::auth::history::visible_since(&state.db, &auth).await? {
            return Err(AppError::NotFound("Call not found.".into()));
        }
    }
    Ok(Json(call))
}

/// Viewing a call acknowledges only metadata, never decrypts or mutates a message.
pub async fn mark_read(State(state): State<AppState>, auth: AuthUser, Path(call_id): Path<Uuid>) -> Result<Json<()>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if call.state != "ended" { return Err(AppError::Conflict("The call has not ended.".into())); }
    let since = crate::auth::history::visible_since(&state.db, &auth).await?;
    let visible: bool = sqlx::query_scalar("SELECT $1 >= $2 AND NOT EXISTS(SELECT 1 FROM call_history_deletions WHERE user_id=$3 AND call_id=$4)")
        .bind(call.created_at).bind(since).bind(auth.user_id).bind(call_id).fetch_one(&state.db).await?;
    if !visible { return Err(not_found()); }
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let changed = sqlx::query("INSERT INTO call_reads(user_id,call_id) VALUES($1,$2) ON CONFLICT DO NOTHING")
        .bind(auth.user_id).bind(call_id).execute(&mut *tx).await?.rows_affected();
    tx.commit().await?;
    if changed > 0 { websocket::notify_user(&state, auth.user_id, &ServerMessage::CallHistoryChanged).await; }
    Ok(Json(()))
}

/// The conditional transition and busy-slot release commit together. Rechecking
/// expiry in the UPDATE also protects against a refresh while waiting on its row lock.
async fn end_call(
    state: &AppState, call_id: Uuid, reason: &str,
    required_states: &[&str], expired_before: Option<chrono::DateTime<Utc>>,
) -> Result<bool, AppError> {
    let mut tx = state.db.begin().await?;
    let result = sqlx::query(
        "UPDATE calls SET state = 'ended', end_reason = $2, ended_at = now() \
         WHERE id = $1 AND state <> 'ended' AND (cardinality($3::text[]) = 0 OR state = ANY($3)) \
         AND ($4::timestamptz IS NULL \
              OR ($2 = 'missed' AND ringing_deadline < $4) \
              OR ($2 = 'timeout' AND connecting_deadline < $4) \
              OR ($2 = 'failed' AND last_activity_at < $4))",
    )
    .bind(call_id)
    .bind(reason)
    .bind(required_states)
    .bind(expired_before)
    .execute(&mut *tx)
    .await?;
    let transitioned = result.rows_affected() > 0;
    if transitioned {
        sqlx::query("DELETE FROM call_participants_active WHERE call_id = $1")
            .bind(call_id).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok(transitioned)
}

/// `POST /calls/{id}/accept` - only a callee device may accept, only from
/// `ringing`. First accepting device wins atomically via the conditional
/// UPDATE's `WHERE state = 'ringing'`; a losing concurrent accept sees 0
/// rows affected and gets 409 (Этап J п.18/54).
pub async fn accept_call(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if call.callee_user_id != auth.user_id {
        return Err(not_found());
    }
    // Deadline set immediately, not only once an answer is relayed - a
    // call that gets accepted but never proceeds to a real SDP exchange
    // (e.g. the caller crashes right after accept) must not stay
    // 'accepted' forever (found via live testing: the sweep only covered
    // 'ringing'/'connecting', leaving this state uncovered).
    let deadline = Utc::now() + Duration::seconds(CONNECTING_TIMEOUT_SECONDS);
    let result = sqlx::query(
        "UPDATE calls SET state = 'accepted', accepted_at = now(), accepted_device_id = $2, connecting_deadline = $3 \
         WHERE id = $1 AND state = 'ringing' AND NOT user_device_call_blocked($2,id)",
    )
    .bind(call_id)
    .bind(auth.device_id)
    .bind(deadline)
    .execute(&state.db)
    .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::Conflict("Call is no longer ringing.".into()));
    }

    websocket::notify_device(&state, auth.device_id, &ServerMessage::CallAccepted { call_id }).await;
    // Caller learns the call was answered.
    websocket::notify_user(&state, call.caller_user_id, &ServerMessage::CallAccepted { call_id }).await;
    // Every OTHER callee device stops ringing (Этап J п.18).
    notify_other_devices(&state, call.callee_user_id, auth.device_id, &ServerMessage::CallAnsweredElsewhere { call_id })
        .await;
    // K.9: process-dead losing devices must also have their notification
    // removed via FCM - answered_elsewhere is pushed to every other callee
    // device (excluding the one that just accepted).
    state
        .push
        .enqueue_for_users(&state.db, &[call.callee_user_id], Some(auth.device_id), &PushEvent::call_ended(call_id, "answered_elsewhere"))
        .await;

    fetch_device_call(&state, call_id, &auth).await.map(Json)
}

async fn notify_other_devices(state: &AppState, user_id: Uuid, except_device_id: Uuid, message: &ServerMessage) {
    let devices: Vec<(Uuid,)> =
        match sqlx::query_as("SELECT id FROM devices WHERE user_id = $1 AND revoked_at IS NULL AND id <> $2")
            .bind(user_id)
            .bind(except_device_id)
            .fetch_all(&state.db)
            .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::error!(%err, "failed to look up other devices");
                return;
            }
        };
    for (device_id,) in devices {
        websocket::notify_device(state, device_id, message).await;
    }
}

pub async fn reject_call(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if call.callee_user_id != auth.user_id {
        return Err(not_found());
    }
    if !end_call(&state, call_id, "rejected", &["ringing"], None).await? {
        return Err(AppError::Conflict("Call is no longer ringing.".into()));
    }
    websocket::notify_user(&state, call.caller_user_id, &ServerMessage::CallEnded { call_id, reason: "rejected".into() }).await;
    notify_other_devices(&state, call.callee_user_id, auth.device_id, &ServerMessage::CallEnded { call_id, reason: "rejected".into() }).await;
    state.push.enqueue_for_users(&state.db, &[call.caller_user_id], None, &PushEvent::call_ended(call_id, "rejected")).await;
    state.push.enqueue_for_users(&state.db, &[call.callee_user_id], Some(auth.device_id), &PushEvent::call_ended(call_id, "rejected")).await;
    fetch_device_call(&state, call_id, &auth).await.map(Json)
}

pub async fn cancel_call(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if call.caller_user_id != auth.user_id {
        return Err(not_found());
    }
    if !end_call(&state, call_id, "cancelled", &["ringing"], None).await? {
        return Err(AppError::Conflict("Call is no longer ringing.".into()));
    }
    websocket::notify_user(&state, call.callee_user_id, &ServerMessage::CallEnded { call_id, reason: "cancelled".into() }).await;
    // K.9: a dead-process callee with a ringing notification must have it
    // removed when the caller cancels.
    state.push.enqueue_for_users(&state.db, &[call.callee_user_id], None, &PushEvent::call_ended(call_id, "cancelled")).await;
    fetch_device_call(&state, call_id, &auth).await.map(Json)
}

/// `POST /calls/{id}/hangup` - either party, any non-terminal state,
/// idempotent (duplicate hangup after another party already ended it is a
/// harmless no-op, never an error - Этап J п.53).
pub async fn hangup_call(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if end_call(&state, call_id, "hangup", &[], None).await? {
        let other = if auth.user_id == call.caller_user_id { call.callee_user_id } else { call.caller_user_id };
        websocket::notify_user(&state, other, &ServerMessage::CallEnded { call_id, reason: "hangup".into() }).await;
        state.push.enqueue_for_users(&state.db, &[other], None, &PushEvent::call_ended(call_id, "hangup")).await;
    }
    fetch_device_call(&state, call_id, &auth).await.map(Json)
}

/// `POST /calls/{id}/failed` - either party self-reports an unrecoverable
/// media failure (e.g. ICE restart attempts exhausted) - never left hanging
/// forever waiting for a backend-side media observation the backend
/// structurally cannot make (it is not a media proxy).
pub async fn fail_call(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if end_call(&state, call_id, "failed", &[], None).await? {
        let other = if auth.user_id == call.caller_user_id { call.callee_user_id } else { call.caller_user_id };
        websocket::notify_user(&state, other, &ServerMessage::CallEnded { call_id, reason: "failed".into() }).await;
        state.push.enqueue_for_users(&state.db, &[other], None, &PushEvent::call_ended(call_id, "failed")).await;
    }
    fetch_device_call(&state, call_id, &auth).await.map(Json)
}

/// `POST /calls/{id}/connected` - client-reported milestone (the backend is
/// not a media proxy and cannot observe `PeerConnection` state itself,
/// Этап J п.52). Either party may report it; only meaningful from
/// `connecting`.
pub async fn mark_connected(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<CallDto>, AppError> {
    let _call = fetch_device_call(&state, call_id, &auth).await?;
    sqlx::query("UPDATE calls SET state = 'connected', connected_at = now(), last_activity_at = now() WHERE id = $1 AND state IN ('connecting', 'accepted')")
        .bind(call_id)
        .execute(&state.db)
        .await?;
    fetch_device_call(&state, call_id, &auth).await.map(Json)
}

/// Background sweep for server-authoritative timeouts (Этап J п.22/23):
/// ringing calls past their deadline end as `missed`; connecting calls past
/// theirs end as `timeout`. Never relies solely on an Android-side timer.
/// Also sweeps connected calls abandoned by both sides (N.2R).
pub async fn sweep_expired_calls(state: &AppState) {
    let missed: Vec<(Uuid, Uuid, Uuid)> = match sqlx::query_as(
        "SELECT id, caller_user_id, callee_user_id FROM calls WHERE state = 'ringing' AND ringing_deadline < now()",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "call sweep: failed to query ringing calls");
            return;
        }
    };
    for (call_id, caller_id, callee_id) in missed {
        if matches!(end_call(state, call_id, "missed", &["ringing"], Some(Utc::now())).await, Ok(true)) {
            websocket::notify_user(state, caller_id, &ServerMessage::CallEnded { call_id, reason: "missed".into() }).await;
            websocket::notify_user(state, callee_id, &ServerMessage::CallEnded { call_id, reason: "missed".into() }).await;
            state.push.enqueue_for_users(&state.db, &[caller_id, callee_id], None, &PushEvent::call_ended(call_id, "missed")).await;
        }
    }

    let timed_out: Vec<(Uuid, Uuid, Uuid)> = match sqlx::query_as(
        "SELECT id, caller_user_id, callee_user_id FROM calls WHERE state IN ('accepted', 'connecting') AND connecting_deadline < now()",
    )
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "call sweep: failed to query connecting calls");
            return;
        }
    };
    for (call_id, caller_id, callee_id) in timed_out {
        if matches!(end_call(state, call_id, "timeout", &["accepted", "connecting"], Some(Utc::now())).await, Ok(true)) {
            websocket::notify_user(state, caller_id, &ServerMessage::CallEnded { call_id, reason: "timeout".into() }).await;
            websocket::notify_user(state, callee_id, &ServerMessage::CallEnded { call_id, reason: "timeout".into() }).await;
            state.push.enqueue_for_users(&state.db, &[caller_id, callee_id], None, &PushEvent::call_ended(call_id, "timeout")).await;
        }
    }

    // N.2R: connected calls abandoned by EVERY device. The backend is not a
    // media proxy and cannot observe P2P media, and "the app's socket is
    // connected" is NOT liveness (a restarted app reconnects its socket even
    // though it is no longer in the call). Instead each device that is
    // actually in the call sends `call.keep_alive` every ~10s, refreshing
    // last_activity_at. A connected call whose liveness went stale is
    // unrecoverable by either party - without this sweep a double force-stop
    // mid-call leaves the row `connected` forever and both users permanently
    // "busy" (their call_participants_active rows are never released).
    const CONNECTED_LIVENESS_GRACE_SECONDS: i64 = 40;
    let stale_cutoff = Utc::now() - Duration::seconds(CONNECTED_LIVENESS_GRACE_SECONDS);
    let stale: Vec<(Uuid, Uuid, Uuid)> = match sqlx::query_as(
        "SELECT id, caller_user_id, callee_user_id FROM calls WHERE state = 'connected' AND last_activity_at < $1",
    )
    .bind(stale_cutoff)
    .fetch_all(&state.db)
    .await
    {
        Ok(rows) => rows,
        Err(err) => {
            tracing::error!(%err, "call sweep: failed to query stale connected calls");
            return;
        }
    };
    for (call_id, caller_id, callee_id) in stale {
        if matches!(end_call(state, call_id, "failed", &["connected"], Some(stale_cutoff)).await, Ok(true)) {
            websocket::notify_user(state, caller_id, &ServerMessage::CallEnded { call_id, reason: "failed".into() }).await;
            websocket::notify_user(state, callee_id, &ServerMessage::CallEnded { call_id, reason: "failed".into() }).await;
            state.push.enqueue_for_users(&state.db, &[caller_id, callee_id], None, &PushEvent::call_ended(call_id, "failed")).await;
        }
    }
}

/// Sets `connecting_deadline` when the first SDP answer moves a call from
/// `accepted` to `connecting` - called from the WS signaling relay (see
/// `calls::signaling::relay_answer`), never from an HTTP handler.
pub async fn mark_connecting(state: &AppState, call_id: Uuid) {
    let deadline = Utc::now() + Duration::seconds(CONNECTING_TIMEOUT_SECONDS);
    let _ = sqlx::query("UPDATE calls SET state = 'connecting', connecting_deadline = $2 WHERE id = $1 AND state = 'accepted'")
        .bind(call_id)
        .bind(deadline)
        .execute(&state.db)
        .await;
}

/// No new scheduler dependency, same `tokio::spawn` + `interval` pattern as
/// `attachments::cleanup::spawn_periodic_cleanup` - a short interval (5s) is
/// appropriate here since ringing/connecting timeouts are user-facing
/// (30-40s), unlike the hourly attachment orphan sweep.
pub fn spawn_periodic_sweep(state: AppState) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(5));
        loop {
            interval.tick().await;
            sweep_expired_calls(&state).await;
        }
    });
}
