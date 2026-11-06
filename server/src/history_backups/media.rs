//! Durable ciphertext objects. Public upload keys never become restore keys.
use super::{begin, revision, snapshot};
use crate::{
    auth::{middleware::AuthUser, rate_limit},
    error::AppError,
    state::AppState,
};
use axum::{
    extract::{Path, State},
    Json,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Start {
    expected_revision: i64,
    blob_id: Uuid,
    bytes: i64,
    sha256: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Seal {
    expected_revision: i64,
}
#[derive(Serialize)]
pub struct UploadUrl {
    blob_id: Uuid,
    bytes: i64,
    sha256: String,
    upload_url: String,
    expires_at: DateTime<Utc>,
}
#[derive(Serialize)]
pub struct DownloadUrl {
    blob_id: Uuid,
    bytes: i64,
    sha256: String,
    download_url: String,
    expires_at: DateTime<Utc>,
}
#[derive(sqlx::FromRow)]
struct Media {
    blob_id: Uuid,
    bytes: i64,
    sha256: String,
    upload_key: String,
    sealed_key: String,
    sealed: bool,
}

async fn budget(state: &AppState, auth: &AuthUser, operation: &str) -> Result<(), AppError> {
    rate_limit::enforce(
        state,
        &format!("history-media:{operation}:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        10000,
        3600,
    )
    .await
}
async fn owned(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
    id: Uuid,
) -> Result<Media, AppError> {
    sqlx::query_as("SELECT blob_id,bytes,sha256,upload_key,sealed_key,sealed FROM history_backup_media WHERE user_id=$1 AND blob_id=$2")
        .bind(user).bind(id).fetch_optional(&mut **tx).await?.ok_or_else(||AppError::NotFound("Backup media not found.".into()))
}
fn download_url(state: &AppState, media: Media) -> DownloadUrl {
    let (download_url, expires_at) = state.s3.presign_get(&media.sealed_key);
    DownloadUrl {
        blob_id: media.blob_id,
        bytes: media.bytes,
        sha256: media.sha256,
        download_url,
        expires_at,
    }
}
pub async fn start(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<Start>,
) -> Result<Json<UploadUrl>, AppError> {
    if req.blob_id.is_nil()
        || !(1..=crate::attachments::handlers::MAX_ATTACHMENT_ENCRYPTED_SIZE).contains(&req.bytes)
        || req.sha256.len() != 64
        || !req
            .sha256
            .bytes()
            .all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v))
    {
        return Err(AppError::Validation(
            "Invalid backup media size or checksum.".into(),
        ));
    }
    budget(&state, &auth, "start").await?;
    crate::users::deletion::require_storage_ready(&state).await?;
    let mut tx = begin(&state, &auth).await?;
    let current = snapshot(&mut tx, auth.user_id).await?;
    revision(req.expected_revision, current.revision)?;
    if !current.desired_enabled {
        return Err(AppError::Conflict("Backup uploads are disabled.".into()));
    }
    let existing:Option<Media>=sqlx::query_as("SELECT blob_id,bytes,sha256,upload_key,sealed_key,sealed FROM history_backup_media WHERE user_id=$1 AND blob_id=$2")
        .bind(auth.user_id).bind(req.blob_id).fetch_optional(&mut *tx).await?;
    let key = if let Some(media) = existing {
        if media.sealed || media.bytes != req.bytes || media.sha256 != req.sha256 {
            return Err(AppError::Conflict("Media ID is sealed or has different content. Download the sealed object or use a new ID.".into()));
        }
        media.upload_key
    } else {
        let count: i64 =
            sqlx::query_scalar("SELECT count(*) FROM history_backup_media WHERE user_id=$1")
                .bind(auth.user_id)
                .fetch_one(&mut *tx)
                .await?;
        if count >= 100000 {
            return Err(AppError::Validation("Backup media limit reached.".into()));
        }
        let (max_file,quota,budget):(i64,i64,i64)=sqlx::query_as("SELECT max_file_bytes,user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true FOR UPDATE")
            .fetch_one(&mut *tx).await?;
        if req.bytes > max_file {
            return Err(AppError::Validation(
                "File exceeds this server's upload limit.".into(),
            ));
        }
        let used:i64=sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1")
            .bind(auth.user_id).fetch_one(&mut *tx).await?;
        let total:i64=sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&mut *tx).await?;
        let required = req.bytes * 2;
        if (quota > 0 && required > quota.saturating_sub(used))
            || (budget > 0 && required > budget.saturating_sub(total))
        {
            return Err(AppError::Validation(
                "Storage quota is full. Backup sealing requires temporary copy space.".into(),
            ));
        }
        let source = format!("history-media/{}", Uuid::new_v4());
        // Reserved but never written. First sealing attempt atomically renames
        // this reservation to its unique destination before storage I/O.
        let destination = format!("history-media/pending/{}", Uuid::new_v4());
        sqlx::query("INSERT INTO history_backup_storage(object_key,owner_id,bytes) VALUES($1,$3,$4),($2,$3,$4)")
            .bind(&source).bind(&destination).bind(auth.user_id).bind(req.bytes).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO history_backup_media(user_id,blob_id,bytes,sha256,upload_key,sealed_key,upload_lease_until) VALUES($1,$2,$3,$4,$5,$6,now())")
            .bind(auth.user_id).bind(req.blob_id).bind(req.bytes).bind(&req.sha256).bind(&source).bind(&destination).execute(&mut *tx).await?;
        source
    };
    let (upload_url, expires_at) = state.s3.presign_put_sized(&key, req.bytes)?;
    // Keep the durable reservation beyond URL expiry for uploads already in
    // flight when the account/copy is erased (same 24h grace as account purge).
    sqlx::query("UPDATE history_backup_media SET upload_lease_until=$3+interval '24 hours' WHERE user_id=$1 AND blob_id=$2")
        .bind(auth.user_id).bind(req.blob_id).bind(expires_at).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(UploadUrl {
        blob_id: req.blob_id,
        bytes: req.bytes,
        sha256: req.sha256,
        upload_url,
        expires_at,
    }))
}

pub async fn seal(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<Seal>,
) -> Result<Json<DownloadUrl>, AppError> {
    budget(&state, &auth, "seal").await?;
    let mut tx = begin(&state, &auth).await?;
    let current = snapshot(&mut tx, auth.user_id).await?;
    revision(req.expected_revision, current.revision)?;
    if !current.desired_enabled {
        return Err(AppError::Conflict("Backup uploads are disabled.".into()));
    }
    let mut media = owned(&mut tx, auth.user_id, id).await?;
    if media.sealed {
        let result = download_url(&state, media);
        tx.commit().await?;
        return Ok(Json(result));
    }
    let destination = format!("history-media/{}", Uuid::new_v4());
    if media.sealed_key.starts_with("history-media/pending/") {
        sqlx::query("UPDATE history_backup_storage SET object_key=$2 WHERE object_key=$1 AND delete_after IS NULL")
            .bind(&media.sealed_key).bind(&destination).execute(&mut *tx).await?;
    } else {
        // A timed-out COPY may still finish in S3. Never overwrite its key:
        // retain its reservation until GC and charge the new attempt too.
        let (quota,budget):(i64,i64)=sqlx::query_as("SELECT user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true FOR UPDATE")
            .fetch_one(&mut *tx).await?;
        let used:i64=sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1")
            .bind(auth.user_id).fetch_one(&mut *tx).await?;
        let total:i64=sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&mut *tx).await?;
        if (quota > 0 && media.bytes > quota.saturating_sub(used))
            || (budget > 0 && media.bytes > budget.saturating_sub(total))
        {
            return Err(AppError::Validation(
                "Storage quota is full while an interrupted copy awaits cleanup.".into(),
            ));
        }
        sqlx::query(
            "INSERT INTO history_backup_storage(object_key,owner_id,bytes) VALUES($1,$2,$3)",
        )
        .bind(&destination)
        .bind(auth.user_id)
        .bind(media.bytes)
        .execute(&mut *tx)
        .await?;
        sqlx::query("UPDATE history_backup_storage SET delete_after=now()+interval '24 hours' WHERE object_key=$1")
            .bind(&media.sealed_key).execute(&mut *tx).await?;
    }
    sqlx::query("UPDATE history_backup_media SET sealed_key=$3,upload_lease_until=GREATEST(upload_lease_until,now()+interval '24 hours') WHERE user_id=$1 AND blob_id=$2")
        .bind(auth.user_id).bind(id).bind(&destination).execute(&mut *tx).await?;
    media.sealed_key = destination;
    // Persist the attempt before I/O, so crash/cancellation cannot orphan it.
    tx.commit().await?;
    let size = state
        .s3
        .head_object(&media.upload_key)
        .await
        .map_err(|_| AppError::Internal)?;
    if size != Some(media.bytes as u64) {
        return Err(AppError::Validation(
            "Upload is missing or incomplete.".into(),
        ));
    }
    state
        .s3
        .copy_object(&media.upload_key, &media.sealed_key)
        .await
        .map_err(|_| AppError::Internal)?;
    state
        .s3
        .verify_object_sha256(&media.sealed_key, media.bytes, &media.sha256)
        .await
        .map_err(|_| {
            AppError::Validation("Backup media verification failed. Retry the upload.".into())
        })?;
    // Deletion, pause, revocation and another sealing attempt can all occur
    // during storage I/O. None is allowed to be undone by this late response.
    let mut tx = begin(&state, &auth).await?;
    let current = snapshot(&mut tx, auth.user_id).await?;
    revision(req.expected_revision, current.revision)?;
    if !current.desired_enabled {
        return Err(AppError::Conflict("Backup uploads are disabled.".into()));
    }
    let latest = owned(&mut tx, auth.user_id, id).await?;
    if latest.sealed_key != media.sealed_key || latest.sealed {
        return Err(AppError::Conflict(
            "Backup media sealing was superseded. Refresh before retrying.".into(),
        ));
    }
    sqlx::query("UPDATE history_backup_media SET sealed=true WHERE user_id=$1 AND blob_id=$2")
        .bind(auth.user_id)
        .bind(id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE history_backup_storage SET delete_after=GREATEST(now()+interval '24 hours',(SELECT upload_lease_until FROM history_backup_media WHERE user_id=$1 AND blob_id=$2)) WHERE object_key=$3")
        .bind(auth.user_id).bind(id).bind(&media.upload_key).execute(&mut *tx).await?;
    media.sealed = true;
    let result = download_url(&state, media);
    tx.commit().await?;
    Ok(Json(result))
}
pub async fn download(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<(axum::http::HeaderMap, Json<DownloadUrl>), AppError> {
    budget(&state, &auth, "download").await?;
    let mut tx = super::begin_read(&state, &auth).await?;
    let media = owned(&mut tx, auth.user_id, id).await?;
    if !media.sealed {
        return Err(AppError::NotFound("Backup media is not sealed.".into()));
    }
    let result = download_url(&state, media);
    tx.commit().await?;
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(
        axum::http::header::CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    Ok((headers, Json(result)))
}

pub(super) async fn remove_unreferenced(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
) -> Result<(), AppError> {
    sqlx::query("DELETE FROM history_backup_media m WHERE user_id=$1 AND NOT EXISTS(SELECT 1 FROM history_backup_media_links l WHERE l.user_id=m.user_id AND l.blob_id=m.blob_id)")
        .bind(user).execute(&mut **tx).await?;
    Ok(())
}

/// Only expired, unlinked media is abandoned. Active/staged links are never GC'd.
/// Every object deletion is retryable; account cascades retain the storage ledger.
pub async fn cleanup_once(state: &AppState) -> Result<(), AppError> {
    let users:Vec<Uuid>=sqlx::query_scalar("SELECT DISTINCT user_id FROM history_backup_media m WHERE created_at<now()-interval '24 hours' AND upload_lease_until<now() AND NOT EXISTS(SELECT 1 FROM history_backup_media_links l WHERE l.user_id=m.user_id AND l.blob_id=m.blob_id) LIMIT 100")
        .fetch_all(&state.db).await?;
    for user in users {
        let mut tx = state.db.begin().await?;
        sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
            .bind(user)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM history_backup_media m WHERE user_id=$1 AND created_at<now()-interval '24 hours' AND upload_lease_until<now() AND NOT EXISTS(SELECT 1 FROM history_backup_media_links l WHERE l.user_id=m.user_id AND l.blob_id=m.blob_id)")
            .bind(user).execute(&mut *tx).await?;
        tx.commit().await?;
    }
    let keys:Vec<String>=sqlx::query_scalar("SELECT object_key FROM history_backup_storage WHERE delete_after<now() ORDER BY delete_after LIMIT 100")
        .fetch_all(&state.db).await?;
    for key in keys {
        // Once queued, no API can revive this key or issue another PUT for it.
        if state.s3.delete_object(&key).await.is_ok() {
            sqlx::query(
                "DELETE FROM history_backup_storage WHERE object_key=$1 AND delete_after<now()",
            )
            .bind(key)
            .execute(&state.db)
            .await?;
        }
    }
    Ok(())
}
pub fn spawn_cleanup(state: AppState) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(300));
        loop {
            interval.tick().await;
            if cleanup_once(&state).await.is_err() {
                tracing::warn!("history media cleanup will retry");
            }
        }
    });
}
