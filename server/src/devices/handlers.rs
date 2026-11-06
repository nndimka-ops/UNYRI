use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::Json;
use serde::Deserialize;
use sqlx::{PgPool, Postgres, Transaction};
use uuid::Uuid;

use crate::auth::middleware::AuthUser;
use crate::auth::models::DevicePlatform;
use crate::devices::models::{
    DeviceDto, DeviceListResponse, RenameDeviceRequest, RevokeOthersResponse, SecurityEventDto, SecurityEventsResponse,
};
use crate::error::AppError;
use crate::push::models::PushEvent;
use crate::state::AppState;
use crate::validation::validate_device_name;
use crate::websocket::protocol::ServerMessage;
use crate::websocket;

pub const EVENT_NEW_DEVICE: &str = "new_device";
pub const EVENT_DEVICE_REVOKED: &str = "device_revoked";
pub const EVENT_ALL_OTHER_DEVICES_REVOKED: &str = "all_other_devices_revoked";
pub const EVENT_CONTACT_DEVICE_ADDED: &str = "contact_device_added";
pub const EVENT_CONTACT_IDENTITY_CHANGED: &str = "contact_identity_changed";

const LAST_ACTIVE_COALESCE_INTERVAL: &str = "5 minutes";
const SECURITY_EVENTS_DEFAULT_LIMIT: u32 = 50;
const SECURITY_EVENTS_MAX_LIMIT: u32 = 100;

fn default_device_name(platform: DevicePlatform) -> String {
    match platform {
        DevicePlatform::Android => "Android".to_string(),
        DevicePlatform::Ios => "iOS".to_string(),
        DevicePlatform::Desktop => "Desktop".to_string(),
        DevicePlatform::Web => "Web".to_string(),
    }
}

// ---------------------------------------------------------------------------
// Shared helpers (auth handlers also call record_security_event / enqueue_new_device_alert)
// ---------------------------------------------------------------------------

pub async fn record_security_event(
    tx: &mut Transaction<'_, Postgres>,
    user_id: Uuid,
    event_type: &str,
    actor_device_id: Option<Uuid>,
    subject_device_id: Option<Uuid>,
    metadata: serde_json::Value,
) -> Result<i64, AppError> {
    let (id,): (i64,) = sqlx::query_as(
        "INSERT INTO security_events (user_id, event_type, actor_device_id, subject_device_id, metadata) \
         VALUES ($1, $2, $3, $4, $5) RETURNING id",
    )
    .bind(user_id)
    .bind(event_type)
    .bind(actor_device_id)
    .bind(subject_device_id)
    .bind(metadata)
    .fetch_one(&mut **tx)
    .await?;
    Ok(id)
}

/// Result of persisting a conversation security event.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecurityEventInsertOutcome {
    /// A new durable row was written.
    Inserted(i64),
    /// The same transition for this recipient already exists - no new row.
    AlreadyExists,
}

/// Persists a CONVERSATION-scope security event (Checkpoint B): a contact's
/// cryptographic state change, intended to appear inside an existing chat.
///
/// Semantics:
/// - `recipient_user_id` = the authenticated user this event is FOR (row owner).
/// - `chat_id` = the existing direct chat this event belongs to.
/// - `subject_user_id` = the user whose cryptographic state changed.
/// - `subject_device_id` = the subject's affected server account-device.
/// - `event_key` = a server-derived idempotency key (Checkpoint C derives it
///   from trusted state; never from client input and never raw key material).
///
/// Idempotency is enforced by Postgres (partial UNIQUE index on
/// `(user_id, event_key)`), not by a SELECT-then-INSERT race: the same
/// transition for the same recipient inserts once and is a no-op afterwards,
/// while the same transition for a different recipient is a separate row.
/// Pure persistence - no push/WS/FCM/chat creation happens here.
pub async fn record_conversation_security_event(
    tx: &mut Transaction<'_, Postgres>,
    recipient_user_id: Uuid,
    chat_id: Uuid,
    subject_user_id: Uuid,
    subject_device_id: Uuid,
    event_type: &str,
    event_key: &str,
) -> Result<SecurityEventInsertOutcome, AppError> {
    if event_key.is_empty() {
        return Err(AppError::Validation("Conversation security event requires a non-empty event_key.".into()));
    }
    let inserted: Option<(i64,)> = sqlx::query_as(
        "INSERT INTO security_events \
            (user_id, event_type, actor_device_id, subject_device_id, subject_user_id, chat_id, scope, event_key, metadata) \
         VALUES ($1, $2, NULL, $3, $4, $5, 'conversation', $6, '{}'::jsonb) \
         ON CONFLICT DO NOTHING \
         RETURNING id",
    )
    .bind(recipient_user_id)
    .bind(event_type)
    .bind(subject_device_id)
    .bind(subject_user_id)
    .bind(chat_id)
    .bind(event_key)
    .fetch_optional(&mut **tx)
    .await?;
    Ok(match inserted {
        Some((id,)) => SecurityEventInsertOutcome::Inserted(id),
        None => SecurityEventInsertOutcome::AlreadyExists,
    })
}

/// Emits one CONVERSATION security event row per existing direct-chat partner
/// of `subject_user_id` (Checkpoint C). Eligibility = an existing
/// `type='direct'` chat where the subject is a member; the OTHER member is the
/// recipient (`security_events.user_id`). Saved/self chats and group chats are
/// never eligible, the subject never receives their own event, and no chat is
/// ever created. Runs inside the caller's transaction; idempotency is enforced
/// by [record_conversation_security_event].
pub async fn emit_conversation_security_event_to_chat_partners(
    tx: &mut Transaction<'_, Postgres>,
    subject_user_id: Uuid,
    subject_device_id: Uuid,
    event_type: &str,
    event_key: &str,
) -> Result<(), AppError> {
    let partners: Vec<(Uuid, Uuid)> = sqlx::query_as(
        "SELECT id, CASE WHEN user_a_id = $1 THEN user_b_id ELSE user_a_id END \
         FROM chats WHERE type = 'direct' AND (user_a_id = $1 OR user_b_id = $1)",
    )
    .bind(subject_user_id)
    .fetch_all(&mut **tx)
    .await?;

    for (chat_id, recipient_user_id) in partners {
        record_conversation_security_event(
            tx, recipient_user_id, chat_id, subject_user_id, subject_device_id, event_type, event_key,
        )
        .await?;
    }
    Ok(())
}

/// Coalesced last-activity write (Этап L.3): only touches the row when the
/// previous write is older than the interval, so login/refresh/WS-connect
/// never add a per-request write. Fire-and-forget by design.
pub async fn touch_last_active(db: &PgPool, device_id: Uuid) {
    let result = sqlx::query(
        "UPDATE devices SET last_active_at = now() \
         WHERE id = $1 AND (last_active_at IS NULL OR last_active_at < now() - $2::interval)",
    )
    .bind(device_id)
    .bind(LAST_ACTIVE_COALESCE_INTERVAL)
    .execute(db)
    .await;
    if let Err(err) = result {
        tracing::debug!(%err, device_id = %device_id, "touch_last_active failed");
    }
}

/// Privacy-safe new-device alert (Этап L.18): wakes every OTHER active device
/// of `user_id` (never the new device itself) with a `security_event` push.
/// A no-op when there are no other devices (e.g. first registration).
pub async fn enqueue_new_device_alert(
    state: &AppState,
    user_id: Uuid,
    new_device_id: Uuid,
    display_name: Option<&str>,
    platform: DevicePlatform,
    event_id: i64,
) {
    let name = display_name
        .filter(|s| !s.trim().is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| default_device_name(platform));
    let event = PushEvent::security_event(event_id, EVENT_NEW_DEVICE, new_device_id, &name);
    state.push.enqueue_for_users(&state.db, &[user_id], Some(new_device_id), &event).await;
}

/// Sends `session.revoked` to every live connection of `device_id` (Этап L.10).
/// The client is responsible for tearing down its session and returning to
/// the login screen; the next authenticated request from that device is
/// rejected with 401 by `AuthUser` regardless (Postgres, not the socket, is
/// the source of truth).
async fn send_session_revoked(state: &AppState, device_id: Uuid) {
    if let Ok(payload) = serde_json::to_string(&ServerMessage::SessionRevoked { reason: "device_revoked".to_string() }) {
        state.ws_registry.send_to_device(device_id, &payload).await;
    }
}

/// Ends a call whose accepted device was just revoked (Этап L.15), and wakes
/// both parties with a call_ended push + WS event.
pub(crate) async fn end_call_accepted_by_revoked_device(
    state: &AppState,
    call_id: Uuid,
    caller_user_id: Uuid,
    callee_user_id: Uuid,
) {
    let Ok(mut tx) = state.db.begin().await else {
        tracing::error!(%call_id, "failed to begin revoked-call cleanup");
        return;
    };
    let result = sqlx::query(
        "UPDATE calls SET state = 'ended', end_reason = 'device_revoked', ended_at = now() \
         WHERE id = $1 AND state <> 'ended'",
    )
    .bind(call_id)
    .execute(&mut *tx)
    .await;
    let Ok(result) = result else { return };
    if result.rows_affected() == 0 {
        return;
    }
    if sqlx::query("DELETE FROM call_participants_active WHERE call_id = $1").bind(call_id).execute(&mut *tx).await.is_err() {
        tracing::error!(%call_id, "failed to clear revoked-call participants; rolling back");
        return;
    }
    if tx.commit().await.is_err() {
        tracing::error!(%call_id, "failed to commit revoked-call cleanup");
        return;
    }
    let event = ServerMessage::CallEnded { call_id, reason: "device_revoked".to_string() };
    websocket::notify_user(state, caller_user_id, &event).await;
    websocket::notify_user(state, callee_user_id, &event).await;
    let push = PushEvent::call_ended(call_id, "device_revoked");
    state.push.enqueue_for_users(&state.db, &[caller_user_id], None, &push).await;
    state.push.enqueue_for_users(&state.db, &[callee_user_id], None, &push).await;
}

/// Core revoke transaction shared by single-device revoke and revoke-others
/// (Этап L.8): mark device revoked + revoke all its sessions + invalidate its
/// push token(s) + record a security event, in one transaction. Bundle
/// delivery and message-envelope targeting are already excluded by their
/// `revoked_at IS NULL` filters - no extra work needed there. Returns the
/// device display name if a live device was actually revoked.
#[allow(clippy::too_many_arguments)]
pub async fn revoke_device(
    state: &AppState,
    auth: &AuthUser,
    device_id: Uuid,
    event_type: &str,
) -> Result<Option<String>, AppError> {
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_session(&mut tx, auth).await?;
    let user_id = auth.user_id;
    let actor_device_id = auth.device_id;

    let subject: Option<(Option<String>,)> = sqlx::query_as(
        "SELECT display_name FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL",
    )
    .bind(device_id)
    .bind(user_id)
    .fetch_optional(&mut *tx)
    .await?;

    let Some((display_name,)) = subject else {
        tx.rollback().await?;
        return Ok(None);
    };

    sqlx::query("UPDATE devices SET revoked_at = now() WHERE id = $1")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE sessions SET revoked_at = now() WHERE device_id = $1 AND revoked_at IS NULL")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE push_tokens SET invalidated_at = now() WHERE device_id = $1")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;

    let name = display_name.unwrap_or_else(|| "Unknown device".to_string());
    record_security_event(
        &mut tx,
        user_id,
        event_type,
        Some(actor_device_id),
        Some(device_id),
        serde_json::json!({ "device_id": device_id, "display_name": name }),
    )
    .await?;

    let active_calls: Vec<(Uuid, Uuid, Uuid)> = sqlx::query_as(
        "SELECT id, caller_user_id, callee_user_id FROM calls \
         WHERE accepted_device_id = $1 AND state IN ('accepted','connecting','connected')",
    )
    .bind(device_id)
    .fetch_all(&mut *tx)
    .await?;

    tx.commit().await?;

    // Post-commit side effects (best-effort, never fail the revoke itself).
    send_session_revoked(state, device_id).await;
    crate::conferences::after_security_change(state).await;
    for (call_id, caller, callee) in active_calls {
        end_call_accepted_by_revoked_device(state, call_id, caller, callee).await;
    }

    Ok(Some(name))
}

// ---------------------------------------------------------------------------
// HTTP handlers
// ---------------------------------------------------------------------------

/// `GET /devices` - every active device of the authenticated user (Этап L.4),
/// oldest first, with `is_current` marking the requesting device.
pub async fn list_devices(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<DeviceListResponse>, AppError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        id: Uuid,
        display_name: Option<String>,
        platform: DevicePlatform,
        os_version: Option<String>,
        app_version: Option<String>,
        last_active_at: Option<chrono::DateTime<chrono::Utc>>,
        created_at: chrono::DateTime<chrono::Utc>,
    }

    let rows: Vec<Row> = sqlx::query_as(
        "SELECT id, display_name, platform, os_version, app_version, last_active_at, created_at \
         FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at ASC, id ASC",
    )
    .bind(auth.user_id)
    .fetch_all(&state.db)
    .await?;

    let devices = rows
        .into_iter()
        .map(|row| DeviceDto {
            display_name: row.display_name.unwrap_or_else(|| default_device_name(row.platform)),
            is_current: row.id == auth.device_id,
            id: row.id,
            platform: row.platform,
            os_version: row.os_version,
            app_version: row.app_version,
            last_active_at: row.last_active_at,
            created_at: row.created_at,
        })
        .collect();

    Ok(Json(DeviceListResponse { devices }))
}

/// `PATCH /devices/{device_id}` - rename one of the user's own active devices
/// (Этап L.5). Renaming the current device is allowed; renaming a revoked or
/// foreign device is a 404.
pub async fn rename_device(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(device_id): Path<Uuid>,
    Json(req): Json<RenameDeviceRequest>,
) -> Result<Json<DeviceDto>, AppError> {
    let name = validate_device_name(&req.display_name)?.to_string();
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;

    let result = sqlx::query(
        "UPDATE devices SET display_name = $1 WHERE id = $2 AND user_id = $3 AND revoked_at IS NULL",
    )
    .bind(&name)
    .bind(device_id)
    .bind(auth.user_id)
    .execute(&mut *tx)
    .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::NotFound("Device not found.".into()));
    }

    let row: (DevicePlatform, Option<String>, Option<String>, Option<chrono::DateTime<chrono::Utc>>, chrono::DateTime<chrono::Utc>) =
        sqlx::query_as(
            "SELECT platform, os_version, app_version, last_active_at, created_at FROM devices WHERE id = $1",
        )
        .bind(device_id)
        .fetch_one(&mut *tx)
        .await?;

    tx.commit().await?;
    Ok(Json(DeviceDto {
        id: device_id,
        display_name: name,
        platform: row.0,
        os_version: row.1,
        app_version: row.2,
        last_active_at: row.3,
        created_at: row.4,
        is_current: device_id == auth.device_id,
    }))
}

/// `DELETE /devices/{device_id}` - revoke one of the user's other active
/// devices (Этап L.8). The current device cannot revoke itself (409).
pub async fn revoke_device_handler(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(device_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    if device_id == auth.device_id {
        return Err(AppError::Conflict(
            "Use logout to leave from this device; you cannot revoke the device you are currently using.".into(),
        ));
    }
    match revoke_device(&state, &auth, device_id, EVENT_DEVICE_REVOKED).await? {
        Some(_) => Ok(StatusCode::NO_CONTENT),
        None => Err(AppError::NotFound("Device not found.".into())),
    }
}

/// `POST /devices/revoke-others` - revoke every active device except the
/// current one (Этап L.17), logged as one aggregate security event.
pub async fn revoke_other_devices(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<RevokeOthersResponse>, AppError> {
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_session(&mut tx, &auth).await?;

    let revoked: Vec<(Uuid, Option<String>)> = sqlx::query_as(
        "UPDATE devices SET revoked_at = now() \
         WHERE user_id = $1 AND id <> $2 AND revoked_at IS NULL \
         RETURNING id, display_name",
    )
    .bind(auth.user_id)
    .bind(auth.device_id)
    .fetch_all(&mut *tx)
    .await?;

    if revoked.is_empty() {
        tx.rollback().await?;
        return Ok(Json(RevokeOthersResponse { revoked_count: 0 }));
    }

    sqlx::query("UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND device_id <> $2 AND revoked_at IS NULL")
        .bind(auth.user_id)
        .bind(auth.device_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "UPDATE push_tokens SET invalidated_at = now() \
         WHERE device_id IN (SELECT id FROM devices WHERE user_id = $1 AND id <> $2)",
    )
    .bind(auth.user_id)
    .bind(auth.device_id)
    .execute(&mut *tx)
    .await?;

    let ids: Vec<Uuid> = revoked.iter().map(|(id, _)| *id).collect();
    let active_calls: Vec<(Uuid, Uuid, Uuid)> = sqlx::query_as(
        "SELECT id, caller_user_id, callee_user_id FROM calls \
         WHERE accepted_device_id = ANY($1) AND state IN ('accepted','connecting','connected')",
    )
    .bind(&ids)
    .fetch_all(&mut *tx)
    .await?;

    record_security_event(
        &mut tx,
        auth.user_id,
        EVENT_ALL_OTHER_DEVICES_REVOKED,
        Some(auth.device_id),
        None,
        serde_json::json!({ "count": revoked.len(), "device_ids": ids }),
    )
    .await?;

    tx.commit().await?;

    for (device_id, _) in &revoked {
        send_session_revoked(&state, *device_id).await;
    }
    crate::conferences::after_security_change(&state).await;
    for (call_id, caller, callee) in active_calls {
        end_call_accepted_by_revoked_device(&state, call_id, caller, callee).await;
    }

    Ok(Json(RevokeOthersResponse { revoked_count: revoked.len() as i64 }))
}

#[derive(Debug, Deserialize)]
pub struct SecurityEventsQuery {
    pub limit: Option<u32>,
    /// Keyset pagination cursor: the id of the last event already seen.
    pub cursor: Option<i64>,
}

/// `GET /security/events` - recent OWNER/ACCOUNT security events for the
/// authenticated user, newest first, keyset-paginated (Этап L.16). Metadata is
/// non-secret. Conversation-scope events (contact_device_added /
/// contact_identity_changed) are deliberately EXCLUDED: they belong to the
/// per-chat security-event surface, never to the owner DevicesScreen feed.
pub async fn list_security_events(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<SecurityEventsQuery>,
) -> Result<Json<SecurityEventsResponse>, AppError> {
    let limit = query.limit.unwrap_or(SECURITY_EVENTS_DEFAULT_LIMIT).min(SECURITY_EVENTS_MAX_LIMIT) as i64;

    // Fetch one extra row to know whether another page exists.
    let rows: Vec<(i64, String, Option<Uuid>, Option<Uuid>, serde_json::Value, chrono::DateTime<chrono::Utc>)> =
        sqlx::query_as(
            "SELECT id, event_type, actor_device_id, subject_device_id, metadata, created_at \
             FROM security_events \
             WHERE user_id = $1 AND scope = 'account' AND ($2::bigint IS NULL OR id < $2) \
             ORDER BY id DESC LIMIT $3",
        )
        .bind(auth.user_id)
        .bind(query.cursor)
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;

    let has_more = rows.len() as i64 > limit;
    let events: Vec<SecurityEventDto> = rows
        .into_iter()
        .take(limit as usize)
        .map(|(id, event_type, actor_device_id, subject_device_id, metadata, created_at)| SecurityEventDto {
            id,
            event_type,
            actor_device_id,
            subject_device_id,
            metadata,
            created_at,
        })
        .collect();

    let next_cursor = if has_more { events.last().map(|e| e.id) } else { None };

    Ok(Json(SecurityEventsResponse { events, next_cursor }))
}
