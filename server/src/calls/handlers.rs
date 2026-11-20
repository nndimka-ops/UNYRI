
use axum::extract::{Path, Query, State};
use axum::Json;
use chrono::{Duration, Utc};
use uuid::Uuid;

use crate::net::ClientIp;
use crate::auth::middleware::AuthUser;
use crate::auth::rate_limit;
use crate::calls::models::{CallDto, CreateCallRequest, CONNECTING_TIMEOUT_SECONDS, MEDIA_KINDS, RINGING_TIMEOUT_SECONDS};
use crate::calls::ringing;
use crate::calls::turn::{build_ice_servers, IceServersResponse};
use crate::error::AppError;
use crate::push::models::PushEvent;
use crate::state::AppState;
use crate::websocket;
use crate::websocket::protocol::ServerMessage;

const CREATE_CALL_LIMIT: u64 = 20;
const CREATE_CALL_WINDOW_SECONDS: u64 = 60;
/// Per caller-callee fixed window, on top of the per-user bucket: at most ten
/// creates per 600-second window (CALL_RINGING_SPEC 2.7).
const CREATE_CALL_PAIR_LIMIT: u64 = 10;
const CREATE_CALL_PAIR_WINDOW_SECONDS: u64 = 600;
/// One call's confirmation budget, shared by all callee devices. Its key is
/// admitted only after the user backstop and a locked, authorized ringing row.
const CONFIRM_RINGING_CALL_LIMIT: u64 = 10;
/// Per-user backstop, checked even for unknown/foreign/ended call ids.
const CONFIRM_RINGING_USER_LIMIT: u64 = 600;
const CONFIRM_RINGING_WINDOW_SECONDS: u64 = 60;
/// What one call can draw from each callee device: its confirmation and the
/// single retry (CALL_RINGING_SPEC 2.10). Repeats change nothing on the row.
const CONFIRM_REQUESTS_PER_CALL_PER_DEVICE: u64 = 2;
/// Devices the budget is sized for. A callee with more still confirms; only the
/// margin against another caller's burst of calls gets smaller.
const CONFIRM_BUDGET_SIZED_FOR_DEVICES: u64 = 10;
// The requests landing in one window belong to calls that rang within the
// window plus the ringing time, a span far inside one pair window span, so one
// caller has at most two pair windows of calls there. Even with every device
// confirming and retrying each of them, the budget must keep room for another
// caller's call, so one caller cannot cause a false "not online".
const _: () = assert!(
    CONFIRM_RINGING_WINDOW_SECONDS + 2 * RINGING_TIMEOUT_SECONDS as u64 <= CREATE_CALL_PAIR_WINDOW_SECONDS
        && (2 * CREATE_CALL_PAIR_LIMIT + 1) * CONFIRM_REQUESTS_PER_CALL_PER_DEVICE * CONFIRM_BUDGET_SIZED_FOR_DEVICES
            <= CONFIRM_RINGING_USER_LIMIT
);
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
        "SELECT u.id,COALESCE(i.public_username,'') AS username,i.public_username,i.unyri_number::text, \
         u.display_name,u.bio,u.avatar_url,u.profile_version,u.avatar_version,(u.avatar_key IS NOT NULL) AS has_avatar \
         FROM users u JOIN user_identities i ON i.user_id=u.id \
         WHERE u.id=ANY($1) AND u.deleted_at IS NULL AND NOT user_device_blocked($2,u.id)")
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

async fn fetch_call<'e, E>(source: E, call_id: Uuid, requester_id: Uuid) -> Result<CallDto, AppError>
where E: sqlx::Executor<'e, Database = sqlx::Postgres> {
    let call: Option<CallDto> = sqlx::query_as(
        "SELECT c.*, ch.id AS chat_id,
         (ch.id IS NOT NULL AND ch.created_at<=c.created_at AND (pc.calls_before IS NULL OR c.created_at>pc.calls_before)) AS chat_visible,
         (c.callee_user_id=$2 AND c.state='ended' AND c.accepted_at IS NULL
          AND COALESCE(c.end_reason IN ('missed','cancelled','busy','failed','timeout'),false)
          AND (pc.calls_before IS NULL OR c.created_at>pc.calls_before)
          AND NOT EXISTS(SELECT 1 FROM call_reads r WHERE r.user_id=$2 AND r.call_id=c.id)) AS unread,
         CASE WHEN c.caller_user_id=$2 AND c.state='ringing' THEN
              $3::boolean AND c.callee_ack_expected
              AND user_presence_visible(c.callee_user_id,c.caller_user_id,'online') END AS peer_ringing_expected,
         CASE WHEN c.caller_user_id=$2 AND c.state='ringing' THEN
              $3::boolean AND c.callee_ringing AND c.created_at <= now() - make_interval(secs => $4::float8)
              AND user_presence_visible(c.callee_user_id,c.caller_user_id,'online') END AS peer_ringing
         FROM calls c
         LEFT JOIN chats ch ON ch.type='direct' AND ch.user_a_id=LEAST(c.caller_user_id,c.callee_user_id) AND ch.user_b_id=GREATEST(c.caller_user_id,c.callee_user_id)
         LEFT JOIN personal_chat_clears pc ON pc.user_id=$2 AND pc.chat_id=ch.id
         WHERE c.id = $1",
    )
    .bind(call_id)
    .bind(requester_id)
    // The ringing fields (CALL_RINGING_SPEC 2.6) are NULL, so omitted, for the
    // callee and once the call has left `ringing`.
    .bind(ringing::signal_enabled())
    .bind(ringing::floor_seconds())
    .fetch_optional(source)
    .await?;
    let call = call.ok_or_else(not_found)?;
    if call.caller_user_id != requester_id && call.callee_user_id != requester_id {
        return Err(not_found());
    }
    Ok(call)
}

/// Once a callee device accepted, only that device owns the call's media lifecycle.
/// Another device of the same user (a slow CallKit answer that timed out and hangs
/// up) must not end or advance a call the user already answered elsewhere.
fn foreign_callee_device(call: &CallDto, auth: &AuthUser) -> bool {
    auth.user_id == call.callee_user_id && call.state != "ringing"
        && call.accepted_device_id.is_some_and(|device| device != auth.device_id)
}

async fn fetch_device_call(state:&AppState,call_id:Uuid,auth:&AuthUser)->Result<CallDto,AppError> {
    let blocked:bool=sqlx::query_scalar("SELECT user_device_call_blocked($1,$2)")
        .bind(auth.device_id).bind(call_id).fetch_one(&state.db).await?;
    if blocked {return Err(not_found())}
    fetch_call(&state.db,call_id,auth.user_id).await
}

async fn require_endpoint(state: &AppState, call_id: Uuid, auth: &AuthUser) -> Result<(), AppError> {
    let allowed: bool = sqlx::query_scalar("SELECT call_endpoint_allowed($1,$2,$3,$4)")
        .bind(call_id).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id)
        .fetch_one(&state.db).await?;
    if !allowed { return Err(not_found()); }
    Ok(())
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
    Ok(Json(IceServersResponse {
        ice_servers: build_ice_servers(&state.config.turn, auth.user_id),
        ttl_seconds: state.config.turn.credential_ttl_seconds,
    }))
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
) -> Result<Json<serde_json::Value>, AppError> {
    rate_limit::enforce(&state, "calls_create:ip", addr, 60, CREATE_CALL_WINDOW_SECONDS).await?;
    rate_limit::enforce(
        &state,
        &format!("calls_create:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        CREATE_CALL_LIMIT,
        CREATE_CALL_WINDOW_SECONDS,
    ).await?;
    rate_limit::enforce(
        &state,
        &format!("calls_create:pair:{}:{}", auth.user_id, body.callee_user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        CREATE_CALL_PAIR_LIMIT,
        CREATE_CALL_PAIR_WINDOW_SECONDS,
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
    let mut tx = state.db.begin().await?;
    // Lock both accounts in stable order before FK writes: simultaneous A->B
    // and B->A calls must not each hold one account while waiting for the other.
    sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR UPDATE")
        .bind(vec![lo, hi]).fetch_all(&mut *tx).await?;
    crate::chats::policy::lock_contact_allowed(&mut tx, chat_id, auth.device_id).await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    if let Some(request) = body.request_id {
        let existing: Option<(Uuid, String, serde_json::Value)> = sqlx::query_as(
            "SELECT callee_user_id,media_kind,create_response FROM calls WHERE caller_device_id=$1 AND request_id=$2",
        ).bind(auth.device_id).bind(request).fetch_optional(&mut *tx).await?;
        if let Some((callee, media, response)) = existing {
            if callee != body.callee_user_id || media != body.media_kind {
                return Err(AppError::Conflict("request_id was already used for a different call.".into()));
            }
            tx.commit().await?;
            return Ok(Json(response));
        }
    }
    let recipient_restricted:bool=sqlx::query_scalar("SELECT \
        EXISTS(SELECT 1 FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND user_device_blocked(id,$2)) \
        AND NOT EXISTS(SELECT 1 FROM devices WHERE user_id=$1 AND revoked_at IS NULL AND NOT user_device_blocked(id,$2))")
        .bind(body.callee_user_id).bind(auth.user_id).fetch_one(&mut *tx).await?;
    if recipient_restricted {return Err(AppError::Conflict("Communication is unavailable for this chat.".into()));}
    // Frozen on the row: whether the caller may expect a ringing confirmation.
    let peer_ringing_expected = ringing::expectation(&mut tx, body.callee_user_id, auth.user_id).await?;
    let inserted = sqlx::query(
        "INSERT INTO calls (id, caller_user_id, callee_user_id, media_kind, state, ringing_deadline, callee_ack_expected, created_at, caller_device_id, caller_session_family, request_id) \
         VALUES ($1, $2, $3, $4, 'ringing', clock_timestamp() + make_interval(secs => $5), $6, clock_timestamp(), $7, (SELECT family_id FROM sessions WHERE id=$8), $9)",
    )
    .bind(call_id)
    .bind(auth.user_id)
    .bind(body.callee_user_id)
    .bind(&body.media_kind)
    .bind(RINGING_TIMEOUT_SECONDS as f64)
    .bind(peer_ringing_expected)
    .bind(auth.device_id).bind(auth.session_id).bind(body.request_id)
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

    // Read the complete response before committing or notifying. A failed read
    // rolls back the call and both busy slots. The contact/device checks above
    // already ran under both account locks, as fetch_device_call would require.
    let mut call = fetch_call(&mut *tx, call_id, auth.user_id).await?;
    state.push.enqueue_incoming_call_transactional(&mut tx, body.callee_user_id,
        &PushEvent::incoming_call(call_id, &body.media_kind)).await?;
    // Anchor last, after every potentially slow response read and write. Neither
    // lock waits nor response reads may consume the disclosure floor or ring time.
    let template = serde_json::to_value(&call).map_err(|_| AppError::Internal)?;
    let (created_at, persisted): (chrono::DateTime<Utc>, Option<serde_json::Value>) = sqlx::query_as(
        "WITH stamp AS MATERIALIZED (SELECT clock_timestamp() AS at) \
         UPDATE calls c SET created_at=stamp.at, ringing_deadline=stamp.at+make_interval(secs => $2), \
         create_response=CASE WHEN c.request_id IS NULL THEN NULL \
           ELSE jsonb_set($3::jsonb,'{created_at}',to_jsonb(stamp.at)) END \
         FROM stamp WHERE c.id=$1 RETURNING c.created_at,c.create_response",
    ).bind(call_id).bind(RINGING_TIMEOUT_SECONDS as f64).bind(template)
        .fetch_one(&mut *tx).await?;
    call.created_at = created_at;
    let response = match persisted {
        Some(response) => response,
        None => serde_json::to_value(&call).map_err(|_| AppError::Internal)?,
    };
    tx.commit().await?;
    state.push.wake_worker();

    // No fallible response work follows notification. Fan-out/push failures are
    // best effort and do not turn this successful create into an HTTP error.
    websocket::notify_user(
        &state,
        body.callee_user_id,
        &ServerMessage::CallRinging { call_id, caller_user_id: auth.user_id, media_kind: body.media_kind.clone() },
    )
    .await;
    // K.4: wake every eligible callee device via FCM (never the caller).
    // Push is a hint only - the WS path above remains the realtime source.
    Ok(Json(response))
}

/// `GET /calls/active` - authoritative call-state recovery after app
/// restart/WS reconnect/FCM wake (Этап J п.56) - never relies on the client
/// having received every WS event.
pub async fn get_active_call(State(state): State<AppState>, auth: AuthUser) -> Result<Json<Option<CallDto>>, AppError> {
    let call_id: Option<Uuid> = sqlx::query_scalar(
        "SELECT call_id FROM call_participants_active WHERE user_id = $1 AND call_endpoint_allowed(call_id,$1,$2,$3)",
    )
    .bind(auth.user_id)
    .bind(auth.device_id)
    .bind(auth.session_id)
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
    end_call_inner(state, call_id, reason, required_states, expired_before, None).await
}

async fn end_call_as(state: &AppState, call_id: Uuid, reason: &str, states: &[&str], auth: &AuthUser) -> Result<bool, AppError> {
    end_call_inner(state, call_id, reason, states, None, Some(auth)).await
}

async fn end_call_inner(
    state: &AppState, call_id: Uuid, reason: &str, required_states: &[&str],
    expired_before: Option<chrono::DateTime<Utc>>, auth: Option<&AuthUser>,
) -> Result<bool, AppError> {
    let mut tx = state.db.begin().await?;
    // Evaluate endpoint ownership in a fresh statement after every call-row
    // wait; an accepting sibling may have changed the admitted endpoint.
    sqlx::query("SELECT id FROM calls WHERE id=$1 FOR UPDATE")
        .bind(call_id).execute(&mut *tx).await?;
    let callee: Option<Uuid> = sqlx::query_scalar(
        "UPDATE calls SET state = 'ended', end_reason = $2, ended_at = now() \
         WHERE id = $1 AND state <> 'ended' AND (cardinality($3::text[]) = 0 OR state = ANY($3)) \
         AND ($5::uuid IS NULL OR call_endpoint_allowed(id,$5,$6,$7)) \
         AND ($4::timestamptz IS NULL \
              OR ($2 = 'missed' AND ringing_deadline <= clock_timestamp()) \
              OR ($2 = 'timeout' AND connecting_deadline < $4) \
              OR ($2 = 'failed' AND last_activity_at < $4)) RETURNING callee_user_id",
    )
    .bind(call_id)
    .bind(reason)
    .bind(required_states)
    .bind(expired_before)
    .bind(auth.map(|a| a.user_id)).bind(auth.map(|a| a.device_id)).bind(auth.map(|a| a.session_id))
    .fetch_optional(&mut *tx)
    .await?;
    let transitioned = callee.is_some();
    if transitioned {
        sqlx::query("DELETE FROM call_participants_active WHERE call_id = $1")
            .bind(call_id).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    if let Some(callee) = callee {
        ringing::forget_confirmation(state, callee, call_id).await;
    }
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
    let mut tx = state.db.begin().await?;
    sqlx::query("SELECT id FROM users WHERE id=ANY($1) ORDER BY id FOR UPDATE")
        .bind(vec![call.caller_user_id, call.callee_user_id]).execute(&mut *tx).await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    // Clock predicates must run after an unchanged-row lock wait too.
    sqlx::query("SELECT id FROM calls WHERE id=$1 FOR UPDATE")
        .bind(call_id).execute(&mut *tx).await?;
    let result = sqlx::query(
        "UPDATE calls SET state = 'accepted', accepted_at = clock_timestamp(), accepted_device_id = $2, connecting_deadline = clock_timestamp()+make_interval(secs => $3), \
         accepted_session_family=(SELECT family_id FROM sessions WHERE id=$5) \
         WHERE id = $1 AND state = 'ringing' AND ringing_deadline > clock_timestamp() AND call_endpoint_allowed(id,$4,$2,$5)",
    )
    .bind(call_id)
    .bind(auth.device_id)
    .bind(CONNECTING_TIMEOUT_SECONDS as f64)
    .bind(auth.user_id).bind(auth.session_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    if result.rows_affected() == 0 {
        // Resolve expiry using the same conditional transition as the sweeper.
        if end_call(&state, call_id, "missed", &["ringing"], Some(Utc::now())).await? {
            let event = ServerMessage::CallEnded { call_id, reason: "missed".into() };
            websocket::notify_user(&state, call.caller_user_id, &event).await;
            websocket::notify_user(&state, call.callee_user_id, &event).await;
            state.push.enqueue_for_users(&state.db, &[call.caller_user_id, call.callee_user_id], None, &PushEvent::call_ended(call_id,"missed")).await;
        }
        // Preserve the legacy unsuccessful-accept contract. In particular,
        // Android starts media after any successful response; it must get 409.
        return Err(AppError::Conflict("Call is no longer ringing.".into()));
    }
    ringing::forget_confirmation(&state, call.callee_user_id, call_id).await;

    websocket::notify_device(&state, auth.device_id, &ServerMessage::CallAccepted { call_id }).await;
    // Caller learns the call was answered.
    if let (Some(device), Some(family)) = (call.caller_device_id, call.caller_session_family) {
        websocket::notify_family(&state, device, family, &ServerMessage::CallAccepted { call_id }).await;
    }
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

/// `POST /calls/{id}/ringing` - a callee device confirms that the call is
/// ringing on it (CALL_RINGING_SPEC 2.4). Only the callee user, only for a
/// ringing call, only from a device that may receive it; idempotent. The first
/// confirmation sets the call's one bit and schedules the caller's single
/// `call.peer_ringing`; later ones change nothing. No body; success logs
/// nothing. It never extends ringing.
pub async fn confirm_ringing(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(call_id): Path<Uuid>,
) -> Result<Json<()>, AppError> {
    // Bound work and key creation before looking up the supplied call id.
    // No shared-IP bucket: an abusive account must not suppress confirmations
    // for other callees behind the same carrier NAT or Wi-Fi connection.
    ringing::limit_confirmation_user(
        &state, auth.user_id, CONFIRM_RINGING_USER_LIMIT, CONFIRM_RINGING_WINDOW_SECONDS,
    ).await?;
    // Validate without retaining a connection or row lock during Redis work.
    // Recheck under the row lock afterwards: accept/end/revoke can win meanwhile.
    // PostgreSQL alone decides expiry. The duration is just a Redis TTL hint;
    // pool waits, preparation and clock skew cannot turn it into a false 409.
    let current: Option<(String, bool, i64)> = sqlx::query_as(
        "SELECT state, COALESCE(ringing_deadline > clock_timestamp(), false), \
           COALESCE(FLOOR(EXTRACT(EPOCH FROM (ringing_deadline - clock_timestamp())) * 1000), 0)::bigint \
         FROM calls WHERE id = $1 AND callee_user_id = $2 AND NOT user_device_call_blocked($3, id)",
    ).bind(call_id).bind(auth.user_id).bind(auth.device_id)
        .fetch_optional(&state.db).await?;
    let (current, unexpired, remaining_ms) = current.ok_or_else(not_found)?;
    if current != "ringing" || !unexpired {
        return Err(AppError::Conflict("Call is no longer ringing.".into()));
    }
    ringing::limit_confirmation(&state, auth.user_id, call_id, CONFIRM_RINGING_CALL_LIMIT, remaining_ms).await?;
    let mut tx = state.db.begin().await?;
    // MATERIALIZED evaluates the clock after lock waits. No Redis awaits occur
    // in this transaction; the closed marker protects terminal cleanup races.
    let current: Option<(String, bool, bool)> = sqlx::query_as(
        "WITH locked AS MATERIALIZED (\
           SELECT state, callee_ringing, created_at, ringing_deadline FROM calls \
           WHERE id = $1 AND callee_user_id = $2 AND NOT user_device_call_blocked($3, id) FOR UPDATE\
         ) SELECT state, callee_ringing, COALESCE(ringing_deadline > clock_timestamp(), false) FROM locked",
    )
    .bind(call_id)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .fetch_optional(&mut *tx)
    .await?;
    let (current, confirmed, unexpired) = current.ok_or_else(not_found)?;
    if current != "ringing" || !unexpired {
        return Err(AppError::Conflict("Call is no longer ringing.".into()));
    }
    let wait = if !confirmed {
        // Check expiry in the UPDATE itself: even preparation of this statement
        // can wait after the locked read. Only this atomic decision sets the bit.
        let wait: Option<f64> = sqlx::query_scalar(
            "UPDATE calls SET callee_ringing = true \
             WHERE id = $1 AND state = 'ringing' AND ringing_deadline > clock_timestamp() \
             RETURNING GREATEST(0, EXTRACT(EPOCH FROM (created_at + make_interval(secs => $2::float8) - clock_timestamp())))::float8",
        ).bind(call_id).bind(ringing::floor_seconds()).fetch_optional(&mut *tx).await?;
        Some(wait.ok_or_else(|| AppError::Conflict("Call is no longer ringing.".into()))?)
    } else { None };
    tx.commit().await?;
    if let Some(wait) = wait { ringing::announce_after_floor(&state, call_id, wait).await; }
    Ok(Json(()))
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
    require_endpoint(&state, call_id, &auth).await?;
    if call.state == "ended" { return Ok(Json(call)); }
    if !end_call_as(&state, call_id, "rejected", &["ringing"], &auth).await? {
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
    require_endpoint(&state, call_id, &auth).await?;
    if call.state == "ended" { return Ok(Json(call)); }
    if !end_call_as(&state, call_id, "cancelled", &["ringing"], &auth).await? {
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
    if foreign_callee_device(&call, &auth) { return Ok(Json(call)); }
    require_endpoint(&state, call_id, &auth).await?;
    // Choose the unanswered reason in a conditional transition, rather than
    // trusting the fetched state: an accept may win the row lock first.
    let caller_hangup = auth.user_id == call.caller_user_id;
    let ringing_reason = if caller_hangup { "cancelled" } else { "rejected" };
    if end_call_as(&state, call_id, ringing_reason, &["ringing"], &auth).await? {
        let other = if caller_hangup { call.callee_user_id } else { call.caller_user_id };
        let event = ServerMessage::CallEnded { call_id, reason: ringing_reason.into() };
        websocket::notify_user(&state, other, &event).await;
        // Match cancel_call/reject_call so process-dead devices dismiss their
        // incoming notification and a caller cancellation stays unread.
        state.push.enqueue_for_users(&state.db, &[other], None, &PushEvent::call_ended(call_id, ringing_reason)).await;
        if !caller_hangup {
            notify_other_devices(&state, auth.user_id, auth.device_id, &event).await;
            state.push.enqueue_for_users(&state.db, &[auth.user_id], Some(auth.device_id), &PushEvent::call_ended(call_id, ringing_reason)).await;
        }
        return fetch_device_call(&state, call_id, &auth).await.map(Json);
    }
    // A different callee device may have accepted while the ringing transition
    // waited. Re-read its ownership before applying the active-call hangup.
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if foreign_callee_device(&call, &auth) { return Ok(Json(call)); }
    if end_call_as(&state, call_id, "hangup", &["created", "accepted", "connecting", "connected"], &auth).await? {
        let other = if auth.user_id == call.caller_user_id { call.callee_user_id } else { call.caller_user_id };
        websocket::notify_user(&state, other, &ServerMessage::CallEnded { call_id, reason: "hangup".into() }).await;
        state.push.enqueue_for_users(&state.db, &[other], None, &PushEvent::call_ended(call_id, "hangup")).await;
        // Like reject: the actor's other devices may still be ringing (callee ended
        // while answering, or a ringing timeout). Stop them too.
        notify_other_devices(&state, auth.user_id, auth.device_id, &ServerMessage::CallEnded { call_id, reason: "hangup".into() }).await;
        state.push.enqueue_for_users(&state.db, &[auth.user_id], Some(auth.device_id), &PushEvent::call_ended(call_id, "hangup")).await;
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
    if foreign_callee_device(&call, &auth) { return Ok(Json(call)); }
    require_endpoint(&state, call_id, &auth).await?;
    if end_call_as(&state, call_id, "failed", &[], &auth).await? {
        let other = if auth.user_id == call.caller_user_id { call.callee_user_id } else { call.caller_user_id };
        websocket::notify_user(&state, other, &ServerMessage::CallEnded { call_id, reason: "failed".into() }).await;
        state.push.enqueue_for_users(&state.db, &[other], None, &PushEvent::call_ended(call_id, "failed")).await;
        // Like reject: the actor's other devices may still be ringing (callee ended
        // while answering, or a ringing timeout). Stop them too.
        notify_other_devices(&state, auth.user_id, auth.device_id, &ServerMessage::CallEnded { call_id, reason: "failed".into() }).await;
        state.push.enqueue_for_users(&state.db, &[auth.user_id], Some(auth.device_id), &PushEvent::call_ended(call_id, "failed")).await;
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
    let call = fetch_device_call(&state, call_id, &auth).await?;
    if foreign_callee_device(&call, &auth) { return Ok(Json(call)); }
    require_endpoint(&state, call_id, &auth).await?;
    let mut tx = state.db.begin().await?;
    sqlx::query("SELECT id FROM calls WHERE id=$1 FOR UPDATE")
        .bind(call_id).execute(&mut *tx).await?;
    sqlx::query("UPDATE calls SET state = 'connected', connected_at = now(), last_activity_at = now() WHERE id = $1 AND state IN ('connecting', 'accepted') AND call_endpoint_allowed(id,$2,$3,$4)")
        .bind(call_id)
        .bind(auth.user_id).bind(auth.device_id).bind(auth.session_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
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
