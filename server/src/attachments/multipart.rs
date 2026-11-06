use axum::{extract::{Path, State}, Json};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use crate::{auth::middleware::AuthUser, error::AppError, state::AppState};

pub const PART_SIZE: i64 = 5 * 1024 * 1024;
#[derive(Serialize)]
pub struct Part { number: i32, size: i64, upload_url: String, complete: bool }
#[derive(Serialize)]
pub struct Upload { part_size: i64, complete: bool, parts: Vec<Part> }
#[derive(Deserialize)]
pub struct Ack { etag: String }

pub async fn start(State(state): State<AppState>, auth: AuthUser, Path(id): Path<Uuid>) -> Result<Json<Upload>, AppError> {
    crate::users::deletion::require_storage_ready(&state).await?;
    let mut tx = state.db.begin().await?;
    let row: Option<(String, i64, String, Option<String>)> = sqlx::query_as(
        "SELECT object_key, encrypted_size, status::text, multipart_upload_id FROM attachments WHERE id=$1 AND uploader_user_id=$2 AND uploader_device_id=$3 AND NOT cleanup_pending FOR UPDATE")
        .bind(id).bind(auth.user_id).bind(auth.device_id).fetch_optional(&mut *tx).await?;
    let (key, size, status, upload) = row.ok_or_else(|| AppError::NotFound("Attachment not found.".into()))?;
    if status != "created" { return Ok(Json(Upload { part_size: PART_SIZE, complete: true, parts: vec![] })); }
    let upload = match upload {
        Some(value) => value,
        None => {
            let value = state.s3.begin_multipart(&key).await.map_err(|_| AppError::Internal)?;
            let result = sqlx::query("UPDATE attachments SET multipart_upload_id=$2 WHERE id=$1").bind(id).bind(&value).execute(&mut *tx).await;
            if let Err(error) = result { let _ = state.s3.abort_multipart(&key, &value).await; return Err(error.into()); }
            value
        }
    };
    let acked: Vec<i32> = sqlx::query_scalar("SELECT part_number FROM attachment_upload_parts WHERE attachment_id=$1")
        .bind(id).fetch_all(&mut *tx).await?;
    sqlx::query("UPDATE attachments SET upload_lease_until=now()+make_interval(secs => $2) WHERE id=$1").bind(id).bind(state.config.s3.presign_ttl_seconds.saturating_add(state.config.s3.max_upload_duration_seconds).saturating_add(60) as f64).execute(&mut *tx).await?;
    tx.commit().await?;
    let count = (size + PART_SIZE - 1) / PART_SIZE;
    let parts = (1..=count).map(|n| Ok(Part { number: n as i32, size: PART_SIZE.min(size - (n-1)*PART_SIZE),
        upload_url: state.s3.presign_part(&key, &upload, n as i32, PART_SIZE.min(size - (n-1)*PART_SIZE))?, complete: acked.contains(&(n as i32)) })).collect::<Result<Vec<_>,AppError>>()?;
    Ok(Json(Upload { part_size: PART_SIZE, complete: false, parts }))
}

pub async fn acknowledge(State(state): State<AppState>, auth: AuthUser, Path((id, part)): Path<(Uuid,i32)>, Json(ack): Json<Ack>) -> Result<Json<serde_json::Value>, AppError> {
    if ack.etag.is_empty() || ack.etag.len()>128 || !ack.etag.bytes().all(|b| b.is_ascii_hexdigit() || b == b'"' || b == b'-') {
        return Err(AppError::Validation("Invalid upload part receipt.".into()));
    }
    let mut tx = state.db.begin().await?;
    let size: Option<i64> = sqlx::query_scalar("SELECT encrypted_size FROM attachments WHERE id=$1 AND uploader_user_id=$2 AND uploader_device_id=$3 AND status='created' AND NOT cleanup_pending AND multipart_upload_id IS NOT NULL FOR UPDATE")
        .bind(id).bind(auth.user_id).bind(auth.device_id).fetch_optional(&mut *tx).await?;
    let size = size.ok_or_else(|| AppError::NotFound("Pending attachment not found.".into()))?;
    if part < 1 || part as i64 > (size + PART_SIZE - 1)/PART_SIZE { return Err(AppError::Validation("Invalid upload part number.".into())); }
    sqlx::query("INSERT INTO attachment_upload_parts (attachment_id, part_number, etag) VALUES ($1,$2,$3) ON CONFLICT (attachment_id, part_number) DO UPDATE SET etag=EXCLUDED.etag")
        .bind(id).bind(part).bind(ack.etag).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(serde_json::json!({"ok":true})))
}

pub async fn finish(state: &AppState, id: Uuid) -> Result<(), AppError> {
    let mut tx = state.db.begin().await?;
    let (key, size, upload): (String,i64,Option<String>) = sqlx::query_as("SELECT object_key, encrypted_size, multipart_upload_id FROM attachments WHERE id=$1 AND NOT cleanup_pending FOR UPDATE")
        .bind(id).fetch_one(&mut *tx).await?;
    if let Some(upload) = upload {
        // A lost completion response is recoverable: verify the final object
        // before attempting to finish an already-consumed multipart upload.
        if state.s3.head_object(&key).await.map_err(|_| AppError::Internal)? != Some(size as u64) {
            let parts: Vec<(i32,String)> = sqlx::query_as("SELECT part_number, etag FROM attachment_upload_parts WHERE attachment_id=$1 ORDER BY part_number")
                .bind(id).fetch_all(&mut *tx).await?;
            if parts.len() as i64 != (size+PART_SIZE-1)/PART_SIZE || parts.iter().enumerate().any(|(i,p)| p.0 != i as i32+1) {
                return Err(AppError::Validation("Upload has incomplete parts.".into()));
            }
            state.s3.finish_multipart(&key, &upload, parts).await.map_err(|_| AppError::Internal)?;
        }
    }
    tx.commit().await?;
    Ok(())
}
