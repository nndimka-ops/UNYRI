//! Opaque backup transport. No recovery key, Signal session or plaintext reaches
//! this module. Client import/restore is a separate capability, not implied here.
use crate::{
    auth::{
        middleware::{lock_active_session, lock_active_user, AuthUser},
        rate_limit,
    },
    error::AppError,
    state::AppState,
};
use axum::{extract::State, Json};
use base64::{engine::general_purpose::STANDARD, Engine};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;
pub mod media;

const MAX_BYTES: usize = 8 * 1024 * 1024;
#[derive(Serialize, sqlx::FromRow)]
pub struct BackupInfo {
    backup_id: Uuid,
    format_version: i32,
    sha256: String,
    bytes: i32,
    created_at: DateTime<Utc>,
    verified_at: Option<DateTime<Utc>>,
}
#[derive(Serialize)]
pub struct BackupState {
    pub server_id: Uuid,
    pub revision: i64,
    pub desired_enabled: bool,
    /// Transport support must not be mistaken for an implemented mobile restore.
    client_restore_required: bool,
    pub active: Option<BackupInfo>,
    pub staged: Option<BackupInfo>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Upload {
    expected_revision: i64,
    backup_id: Uuid,
    format_version: i32,
    ciphertext: String,
    sha256: String,
    #[serde(default)]
    media_ids: Vec<Uuid>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Confirm {
    expected_revision: i64,
    backup_id: Uuid,
    sha256: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Preference {
    expected_revision: i64,
    desired_enabled: bool,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Delete {
    expected_revision: i64,
}
#[derive(Serialize)]
pub struct Download {
    backup_id: Uuid,
    format_version: i32,
    sha256: String,
    ciphertext: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RestoreCheck {
    message_ids: Vec<Uuid>,
    #[serde(default)]
    call_ids: Vec<Uuid>,
}
#[derive(Serialize)]
pub struct RestoreTombstone { id: Uuid, sequence: i64 }
#[derive(Serialize)]
pub struct RestoreCheckResult { deleted: Vec<RestoreTombstone>, deleted_call_ids: Vec<Uuid> }

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SourceCheck {
    message_ids: Vec<Uuid>,
    #[serde(default)]
    call_ids: Vec<Uuid>,
}
#[derive(Serialize)]
pub struct SourceCheckResult { complete: bool }

/// Proves coverage of this session's authorized history window independently
/// of device envelopes. Password login deliberately hides earlier history;
/// requiring those unavailable rows would make its first backup impossible.
/// An empty /sync page still does not prove coverage of visible account rows.
pub async fn source_check(State(state): State<AppState>, auth: AuthUser, Json(request): Json<SourceCheck>)
    -> Result<(axum::http::HeaderMap, Json<SourceCheckResult>), AppError> {
    if request.message_ids.len() > 50000 || request.message_ids.iter().any(Uuid::is_nil)
        || request.message_ids.iter().collect::<std::collections::HashSet<_>>().len() != request.message_ids.len()
        || request.call_ids.len() > 50000 || request.call_ids.iter().any(Uuid::is_nil)
        || request.call_ids.iter().collect::<std::collections::HashSet<_>>().len() != request.call_ids.len() {
        return Err(AppError::Validation("Use at most 50000 unique message IDs and 50000 unique call IDs.".into()));
    }
    rate_limit::enforce(&state, &format!("history-source-check:{}",auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(), 120, 3600).await?;
    let visible_since = crate::auth::history::visible_since(&state.db, &auth).await?;
    let mut tx=state.db.begin().await?;
    lock_active_user(&mut tx,&auth).await?;
    // Unnest gives PostgreSQL a hashable anti-join rather than a per-message
    // linear scan of a potentially 50k-element client array.
    let missing:bool=sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM messages m
         JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$1 AND cm.left_at IS NULL
         LEFT JOIN personal_chat_clears pc ON pc.chat_id=m.chat_id AND pc.user_id=$1
         WHERE m.kind='content' AND m.deleted_at IS NULL
           AND m.created_at >= $3 AND NOT user_device_chat_blocked($4,m.chat_id)
           AND m.sequence>GREATEST(COALESCE(pc.through_sequence,0),
               COALESCE((SELECT through_sequence FROM history_clear_state WHERE singleton=true),0))
           AND NOT EXISTS(SELECT 1 FROM deleted_chats dc WHERE dc.user_id=$1 AND dc.chat_id=m.chat_id)
           AND NOT EXISTS(SELECT 1 FROM unnest($2::uuid[]) supplied(id) WHERE supplied.id=m.id))")
        .bind(auth.user_id).bind(&request.message_ids).bind(visible_since).bind(auth.device_id)
        .fetch_one(&mut *tx).await?;
    let missing_calls: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM calls c
           WHERE c.state='ended' AND (c.caller_user_id=$1 OR c.callee_user_id=$1)
             AND c.created_at >= $3 AND NOT user_device_call_blocked($4,c.id)
             AND NOT EXISTS(SELECT 1 FROM call_history_deletions d WHERE d.call_id=c.id AND d.user_id=$1)
             AND NOT EXISTS(SELECT 1 FROM unnest($2::uuid[]) supplied(id) WHERE supplied.id=c.id))",
    ).bind(auth.user_id).bind(&request.call_ids).bind(visible_since).bind(auth.device_id)
        .fetch_one(&mut *tx).await?;
    tx.commit().await?;
    let mut headers=axum::http::HeaderMap::new();
    headers.insert(axum::http::header::CACHE_CONTROL,axum::http::HeaderValue::from_static("no-store"));
    Ok((headers,Json(SourceCheckResult{complete:!missing && !missing_calls})))
}

/// Authenticated deletion projection for a newly admitted device which cannot
/// decrypt historical lifecycle envelopes. No message body or key is exposed.
pub async fn restore_check(State(state): State<AppState>, auth: AuthUser, Json(request): Json<RestoreCheck>)
    -> Result<(axum::http::HeaderMap, Json<RestoreCheckResult>), AppError> {
    if request.message_ids.len() > 500 || request.message_ids.iter().any(Uuid::is_nil)
        || request.message_ids.iter().collect::<std::collections::HashSet<_>>().len() != request.message_ids.len()
        || request.call_ids.len() > 500 || request.call_ids.iter().any(Uuid::is_nil)
        || request.call_ids.iter().collect::<std::collections::HashSet<_>>().len() != request.call_ids.len() {
        return Err(AppError::Validation("Use at most 500 unique message IDs and 500 unique call IDs.".into()));
    }
    rate_limit::enforce(&state, &format!("history-restore-check:{}",auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(), 1000, 3600).await?;
    let mut tx = state.db.begin().await?;
    lock_active_user(&mut tx, &auth).await?;
    // Retain membership after leaving: a later sender deletion must still
    // prevent resurrection of that user's old, legitimately held snapshot.
    let rows: Vec<(Uuid, Option<i64>)> = sqlx::query_as(
        "SELECT m.id,(SELECT MAX(d.sequence) FROM messages d WHERE d.target_message_id=m.id
         AND d.kind='delete' AND d.chat_id=m.chat_id AND d.sender_id=m.sender_id AND d.sequence>m.sequence)
         FROM messages m JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$1
         WHERE m.id=ANY($2) AND m.kind='content' AND m.deleted_at IS NOT NULL ORDER BY m.id")
        .bind(auth.user_id).bind(&request.message_ids).fetch_all(&mut *tx).await?;
    let mut deleted=Vec::with_capacity(rows.len());
    for (id,sequence) in rows {
        // Never silently revive a known deleted row if a damaged/custom server
        // has removed its deletion event. There is no honest sequence to invent.
        let sequence=sequence.ok_or_else(||AppError::Conflict("Deletion history is incomplete. Ask the server owner to repair it before restoring.".into()))?;
        deleted.push(RestoreTombstone{id,sequence});
    }
    // The authenticated archive already contains these IDs. Return only a
    // deletion projection for supplied IDs, never enumerate historical peers.
    let deleted_call_ids: Vec<Uuid> = sqlx::query_scalar(
        "SELECT supplied.id FROM unnest($2::uuid[]) supplied(id)
         WHERE NOT EXISTS(SELECT 1 FROM calls c
           WHERE c.id=supplied.id AND c.state='ended'
             AND (c.caller_user_id=$1 OR c.callee_user_id=$1)
             AND NOT EXISTS(SELECT 1 FROM call_history_deletions d WHERE d.call_id=c.id AND d.user_id=$1))
         ORDER BY supplied.id",
    ).bind(auth.user_id).bind(&request.call_ids).fetch_all(&mut *tx).await?;
    tx.commit().await?;
    let mut headers=axum::http::HeaderMap::new();
    headers.insert(axum::http::header::CACHE_CONTROL,axum::http::HeaderValue::from_static("no-store"));
    Ok((headers,Json(RestoreCheckResult{deleted,deleted_call_ids})))
}

async fn begin(
    state: &AppState,
    auth: &AuthUser,
) -> Result<sqlx::Transaction<'static, sqlx::Postgres>, AppError> {
    let mut tx = state.db.begin().await?;
    lock_active_user(&mut tx, auth).await?;
    initialize_account(&mut tx, auth).await?;
    Ok(tx)
}
async fn begin_read(
    state: &AppState, auth: &AuthUser,
) -> Result<sqlx::Transaction<'static, sqlx::Postgres>, AppError> {
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, auth).await?;
    initialize_account(&mut tx, auth).await?;
    Ok(tx)
}
async fn initialize_account(tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, auth: &AuthUser) -> Result<(), AppError> {
    sqlx::query("INSERT INTO history_backup_accounts(user_id) VALUES($1) ON CONFLICT DO NOTHING")
        .bind(auth.user_id)
        .execute(&mut **tx)
        .await?;
    Ok(())
}
async fn snapshot(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
) -> Result<BackupState, AppError> {
    let (revision, desired_enabled): (i64, bool) = sqlx::query_as(
        "SELECT revision,desired_enabled FROM history_backup_accounts WHERE user_id=$1",
    )
    .bind(user)
    .fetch_one(&mut **tx)
    .await?;
    let mut active = None;
    let mut staged = None;
    for state in ["active", "staged"] {
        let info = sqlx::query_as::<_, BackupInfo>("SELECT backup_id,format_version,sha256,octet_length(ciphertext) AS bytes,created_at,verified_at FROM history_backup_objects WHERE user_id=$1 AND state=$2")
            .bind(user).bind(state).fetch_optional(&mut **tx).await?;
        if state == "active" {
            active = info;
        } else {
            staged = info;
        }
    }
    let server_id: Uuid =
        sqlx::query_scalar("SELECT server_id FROM history_backup_server WHERE singleton=true")
            .fetch_one(&mut **tx)
            .await?;
    Ok(BackupState {
        server_id,
        revision,
        desired_enabled,
        client_restore_required: true,
        active,
        staged,
    })
}
fn revision(expected: i64, current: i64) -> Result<(), AppError> {
    if expected < 0 || expected != current {
        return Err(AppError::Conflict(
            "Backup changed on another device. Refresh before retrying.".into(),
        ));
    }
    Ok(())
}
async fn bump(tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, user: Uuid) -> Result<(), AppError> {
    sqlx::query("UPDATE history_backup_accounts SET revision=revision+1 WHERE user_id=$1")
        .bind(user)
        .execute(&mut **tx)
        .await?;
    Ok(())
}
async fn budget(
    state: &AppState,
    auth: &AuthUser,
    operation: &'static str,
) -> Result<(), AppError> {
    // Transfer retries must not consume the allowance needed to commit a
    // verified object. Keep every operation bounded independently per account.
    rate_limit::enforce(
        state,
        &format!("history-backup:{operation}:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        30,
        3600,
    )
    .await
}
fn decode_upload(request: &Upload) -> Result<Vec<u8>, AppError> {
    if !matches!(request.format_version, 1 | 2)
        || request.backup_id.is_nil()
        || request.media_ids.len() > 50000
        || request.media_ids.iter().any(Uuid::is_nil)
        || (request.format_version == 1 && !request.media_ids.is_empty())
        || request.media_ids.iter().collect::<std::collections::HashSet<_>>().len() != request.media_ids.len()
        || request.ciphertext.len() > ((MAX_BYTES + 2) / 3) * 4
    {
        return Err(AppError::Validation(
            "Unsupported or oversized encrypted backup.".into(),
        ));
    }
    let bytes = STANDARD
        .decode(&request.ciphertext)
        .map_err(|_| AppError::Validation("Invalid backup encoding.".into()))?;
    if !(29..=MAX_BYTES).contains(&bytes.len())
        || request.sha256 != hex::encode(Sha256::digest(&bytes))
    {
        return Err(AppError::Validation(
            "Backup size or checksum does not match.".into(),
        ));
    }
    Ok(bytes)
}
pub async fn status(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<BackupState>, AppError> {
    let mut tx = begin_read(&state, &auth).await?;
    let result = snapshot(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(Json(result))
}
pub async fn stage(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(request): Json<Upload>,
) -> Result<Json<BackupState>, AppError> {
    budget(&state, &auth, "stage").await?;
    let bytes = decode_upload(&request)?;
    let mut tx = begin(&state, &auth).await?;
    let current = snapshot(&mut tx, auth.user_id).await?;
    if !current.desired_enabled {
        return Err(AppError::Conflict("Backup uploads are disabled.".into()));
    }
    // Lost-response retry is idempotent only while exactly this object remains.
    let existing = current
        .staged
        .as_ref()
        .filter(|v| v.backup_id == request.backup_id)
        .or(current
            .active
            .as_ref()
            .filter(|v| v.backup_id == request.backup_id));
    if let Some(info) = existing.filter(|v| v.backup_id == request.backup_id) {
        let saved: Vec<Uuid> = sqlx::query_scalar("SELECT blob_id FROM history_backup_media_links WHERE user_id=$1 AND backup_id=$2 ORDER BY blob_id")
            .bind(auth.user_id).bind(request.backup_id).fetch_all(&mut *tx).await?;
        let mut supplied = request.media_ids.clone(); supplied.sort();
        if info.sha256 != request.sha256 || info.format_version != request.format_version || saved != supplied {
            return Err(AppError::Conflict(
                "Backup ID was already used for different content.".into(),
            ));
        }
        tx.commit().await?;
        return Ok(Json(current));
    }
    revision(request.expected_revision, current.revision)?;
    if request.format_version == 1 && current.active.iter().chain(current.staged.iter()).any(|v| v.format_version > 1) {
        return Err(AppError::Conflict("A newer client is required for this backup.".into()));
    }
    let sealed: i64 = sqlx::query_scalar("SELECT count(*) FROM history_backup_media WHERE user_id=$1 AND blob_id=ANY($2) AND sealed")
        .bind(auth.user_id).bind(&request.media_ids).fetch_one(&mut *tx).await?;
    if sealed != request.media_ids.len() as i64 { return Err(AppError::Validation("Backup media is not complete.".into())); }
    sqlx::query("DELETE FROM history_backup_objects WHERE user_id=$1 AND state='staged'")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO history_backup_objects(user_id,backup_id,format_version,ciphertext,sha256,state) VALUES($1,$2,$3,$4,$5,'staged')")
        .bind(auth.user_id).bind(request.backup_id).bind(request.format_version).bind(bytes).bind(request.sha256).execute(&mut *tx).await?;
    sqlx::query("INSERT INTO history_backup_media_links(user_id,backup_id,blob_id) SELECT $1,$2,unnest($3::uuid[])")
        .bind(auth.user_id).bind(request.backup_id).bind(&request.media_ids).execute(&mut *tx).await?;
    bump(&mut tx, auth.user_id).await?;
    let result = snapshot(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(Json(result))
}
pub async fn download(
    State(state): State<AppState>,
    auth: AuthUser,
    axum::extract::Path(id): axum::extract::Path<Uuid>,
) -> Result<(axum::http::HeaderMap, Json<Download>), AppError> {
    budget(&state, &auth, "download").await?;
    let mut tx = begin_read(&state, &auth).await?;
    let row: Option<(i32, String, Vec<u8>)> = sqlx::query_as("SELECT format_version,sha256,ciphertext FROM history_backup_objects WHERE user_id=$1 AND backup_id=$2")
        .bind(auth.user_id).bind(id).fetch_optional(&mut *tx).await?;
    let (format_version, sha256, bytes) =
        row.ok_or_else(|| AppError::NotFound("Backup not found.".into()))?;
    tx.commit().await?;
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(
        axum::http::header::CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    Ok((
        headers,
        Json(Download {
            backup_id: id,
            format_version,
            sha256,
            ciphertext: STANDARD.encode(bytes),
        }),
    ))
}
pub async fn confirm(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(request): Json<Confirm>,
) -> Result<Json<BackupState>, AppError> {
    budget(&state, &auth, "confirm").await?;
    let mut tx = begin(&state, &auth).await?;
    let current = snapshot(&mut tx, auth.user_id).await?;
    if current
        .active
        .as_ref()
        .is_some_and(|b| b.backup_id == request.backup_id && b.sha256 == request.sha256)
    {
        tx.commit().await?;
        return Ok(Json(current));
    }
    revision(request.expected_revision, current.revision)?;
    if !current.desired_enabled
        || !current
            .staged
            .as_ref()
            .is_some_and(|b| b.backup_id == request.backup_id && b.sha256 == request.sha256)
    {
        return Err(AppError::Conflict(
            "Staged backup no longer matches.".into(),
        ));
    }
    // The recipient verifies the exact archive named by the opaque key handoff.
    // Keep that archive (and its media links) until the recipient acknowledges,
    // either phone cancels, or the short transfer window expires. An automatic
    // backup may still stage its replacement and retry this commit afterwards.
    let transferring: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM history_transfers t
         JOIN history_backup_objects b ON b.user_id=t.owner_id AND b.backup_id=t.backup_id
         WHERE t.owner_id=$1 AND t.status='ready' AND t.expires_at>clock_timestamp() AND b.state='active')",
    ).bind(auth.user_id).fetch_one(&mut *tx).await?;
    if transferring {
        return Err(AppError::Conflict(
            "Finish or cancel the active history transfer before confirming a replacement backup.".into(),
        ));
    }
    sqlx::query("DELETE FROM history_backup_objects WHERE user_id=$1 AND state='active'")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE history_backup_objects SET state='active',verified_at=now() WHERE user_id=$1 AND backup_id=$2")
        .bind(auth.user_id).bind(request.backup_id).execute(&mut *tx).await?;
    bump(&mut tx, auth.user_id).await?;
    let result = snapshot(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(Json(result))
}
pub async fn preference(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(request): Json<Preference>,
) -> Result<Json<BackupState>, AppError> {
    let mut tx = begin(&state, &auth).await?;
    revision(
        request.expected_revision,
        snapshot(&mut tx, auth.user_id).await?.revision,
    )?;
    sqlx::query("UPDATE history_backup_accounts SET desired_enabled=$2 WHERE user_id=$1")
        .bind(auth.user_id)
        .bind(request.desired_enabled)
        .execute(&mut *tx)
        .await?;
    if !request.desired_enabled {
        sqlx::query("DELETE FROM history_backup_objects WHERE user_id=$1 AND state='staged'")
            .bind(auth.user_id)
            .execute(&mut *tx)
            .await?;
        media::remove_unreferenced(&mut tx, auth.user_id).await?;
    }
    bump(&mut tx, auth.user_id).await?;
    let result = snapshot(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(Json(result))
}
pub async fn delete(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(request): Json<Delete>,
) -> Result<Json<BackupState>, AppError> {
    let mut tx = begin(&state, &auth).await?;
    revision(
        request.expected_revision,
        snapshot(&mut tx, auth.user_id).await?.revision,
    )?;
    sqlx::query("DELETE FROM history_backup_objects WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    media::remove_unreferenced(&mut tx, auth.user_id).await?;
    // Explicit erasure also stops automatic uploads until the owner enables them again.
    sqlx::query("UPDATE history_backup_accounts SET desired_enabled=false WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    bump(&mut tx, auth.user_id).await?;
    let result = snapshot(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(Json(result))
}


#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RestoreComplete { pub backup_id: Uuid }

/// Compatibility acknowledgement after authenticated local import.
/// Historical calls now live inside the client-encrypted archive, not in an
/// unencrypted history endpoint unlocked by a device-wide restore marker.
pub async fn restore_complete(State(state): State<AppState>, auth: AuthUser, Json(request): Json<RestoreComplete>)
    -> Result<Json<serde_json::Value>, AppError> {
    let mut tx = state.db.begin().await?;
    lock_active_user(&mut tx, &auth).await?;
    let active: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM history_backup_objects WHERE user_id=$1 AND backup_id=$2 AND state='active' AND verified_at IS NOT NULL)")
        .bind(auth.user_id).bind(request.backup_id).fetch_one(&mut *tx).await?;
    if !active { return Err(AppError::Conflict("Restore source is no longer active; retry restore.".into())); }
    tx.commit().await?;
    Ok(Json(serde_json::json!({"ok":true})))
}
