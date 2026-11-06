use axum::extract::{Path, State};
use axum::Json;
use uuid::Uuid;

use crate::attachments::models::{
    AttachmentRow, CompleteAttachmentResponse, CreateAttachmentRequest, CreateAttachmentResponse,
    DownloadAttachmentResponse,
};
use crate::auth::middleware::AuthUser;
use crate::error::AppError;
use crate::state::AppState;

/// Generic cap for any attachment (photo/file/voice - the backend never
/// knows which, see Этап H п.2/8). Android additionally applies smaller
/// per-media-type limits before ever starting an upload.
// Permit a full 100 MiB plaintext file plus the streaming AEAD header/tags.
pub const MAX_ATTACHMENT_ENCRYPTED_SIZE: i64 = 100 * 1024 * 1024 + 4096;

/// `POST /attachments` - creates an upload intent and returns a presigned
/// PUT URL. Idempotent on `client_attachment_id` (scoped to the uploading
/// device): a retried request after a lost response returns the SAME
/// attachment/object_key/URL rather than creating an orphan object (Этап H
/// п.43) - same `ON CONFLICT DO NOTHING` + fallback SELECT pattern used
/// throughout this codebase.
pub async fn create_attachment(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<CreateAttachmentRequest>,
) -> Result<Json<CreateAttachmentResponse>, AppError> {
    if req.encrypted_size <= 0 {
        return Err(AppError::Validation("encrypted_size must be positive.".into()));
    }
    if req.encrypted_size > MAX_ATTACHMENT_ENCRYPTED_SIZE {
        return Err(AppError::Validation(format!(
            "Attachment exceeds the {MAX_ATTACHMENT_ENCRYPTED_SIZE}-byte limit."
        )));
    }

    crate::users::deletion::require_storage_ready(&state).await?;
    let mut tx = state.db.begin().await?;
    // Match backup media's user -> policy lock order; also close revocation
    // between middleware admission and a quota reservation.
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    // Serialize quota reservations, including uploads that have not completed yet.
    let (max_file, quota, budget): (i64,i64,i64) = sqlx::query_as("SELECT max_file_bytes,user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true FOR UPDATE")
        .fetch_one(&mut *tx).await?;
    let existing: Option<(Uuid,String,i64,bool)> = sqlx::query_as("SELECT id,object_key,encrypted_size,cleanup_pending FROM attachments WHERE uploader_device_id=$1 AND client_attachment_id=$2 FOR UPDATE")
        .bind(auth.device_id).bind(req.client_attachment_id).fetch_optional(&mut *tx).await?;
    let (attachment_id, object_key) = if let Some((id,key,size,cleaning)) = existing {
        if cleaning { return Err(AppError::Conflict("Abandoned upload is being removed. Start a new upload.".into())); }
        if size != req.encrypted_size { return Err(AppError::Validation("Attachment retry must keep the original encrypted size.".into())); }
        (id,key)
    } else {
        if req.encrypted_size > max_file { return Err(AppError::Validation("File exceeds this server's upload limit.".into())); }
        let used: i64 = sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1")
            .bind(auth.user_id).fetch_one(&mut *tx).await?;
        if quota > 0 && req.encrypted_size > quota.saturating_sub(used) { return Err(AppError::Validation("Your server storage quota is full.".into())); }
        if budget > 0 {
            let total: i64 = sqlx::query_scalar("SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations").fetch_one(&mut *tx).await?;
            if req.encrypted_size > budget.saturating_sub(total) { return Err(AppError::Validation("Server storage budget is full.".into())); }
        }
        let key=state.s3.generate_object_key();
        sqlx::query_as::<_,(Uuid,String)>("INSERT INTO attachments(client_attachment_id,uploader_user_id,uploader_device_id,object_key,encrypted_size) VALUES($1,$2,$3,$4,$5) RETURNING id,object_key")
            .bind(req.client_attachment_id).bind(auth.user_id).bind(auth.device_id).bind(key).bind(req.encrypted_size).fetch_one(&mut *tx).await?
    };
    sqlx::query("UPDATE attachments SET upload_lease_until=now()+make_interval(secs => $2) WHERE id=$1")
        .bind(attachment_id).bind(state.config.s3.presign_ttl_seconds.saturating_add(state.config.s3.max_upload_duration_seconds).saturating_add(60) as f64).execute(&mut *tx).await?;
    tx.commit().await?;

    let (upload_url, expires_at) = state.s3.presign_put_sized(&object_key, req.encrypted_size)?;
    Ok(Json(CreateAttachmentResponse { attachment_id, object_key, upload_url, expires_at }))
}

async fn fetch_owned_attachment(state: &AppState, attachment_id: Uuid, user_id: Uuid) -> Result<AttachmentRow, AppError> {
    let row: Option<AttachmentRow> = sqlx::query_as(
        "SELECT id, uploader_user_id, object_key, encrypted_size, status::text AS status \
         FROM attachments WHERE id = $1 AND NOT cleanup_pending",
    )
    .bind(attachment_id)
    .fetch_optional(&state.db)
    .await?;
    let row = row.ok_or_else(|| AppError::NotFound("Attachment not found.".into()))?;
    // Never reveal that a differently-owned attachment id exists.
    if row.uploader_user_id != user_id {
        return Err(AppError::NotFound("Attachment not found.".into()));
    }
    Ok(row)
}

/// `POST /attachments/{id}/complete` - verifies the object actually landed
/// in storage with the declared size (a real HEAD request, never trusting
/// the client's word alone) before the attachment becomes eligible to be
/// referenced by a message. Idempotent: already-`attached`/`uploaded` is a
/// no-op success, not an error (safe to retry after a lost response).
pub async fn complete_attachment(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(attachment_id): Path<Uuid>,
) -> Result<Json<CompleteAttachmentResponse>, AppError> {
    let row = fetch_owned_attachment(&state, attachment_id, auth.user_id).await?;
    if row.status != "created" {
        return Ok(Json(CompleteAttachmentResponse { attachment_id: row.id, status: row.status }));
    }

    super::multipart::finish(&state, attachment_id).await?;

    let actual_size = state
        .s3
        .head_object(&row.object_key)
        .await
        .map_err(|_| AppError::Validation("Could not verify the upload in storage.".into()))?;
    let actual_size = actual_size.ok_or_else(|| AppError::Validation("Upload not found in storage yet.".into()))?;
    if actual_size as i64 != row.encrypted_size {
        return Err(AppError::Validation("Uploaded object size does not match the declared size.".into()));
    }

    sqlx::query("UPDATE attachments SET status = 'uploaded', uploaded_at = now() WHERE id = $1 AND status = 'created' AND NOT cleanup_pending")
        .bind(attachment_id)
        .execute(&state.db)
        .await?;

    Ok(Json(CompleteAttachmentResponse { attachment_id, status: "uploaded".to_string() }))
}

/// `GET /attachments/{id}/download` - state machine (Этап H п.4/38):
/// before `attached`, only the uploader may ever get a download URL; once
/// `attached` to a message, only current members of that message's chat can
/// (uploader included, since chat membership covers them too). Any other
/// case - including a third party who merely learned the UUID - is a plain
/// 404, never a 403 (no confirmation that the id even exists).
pub async fn download_attachment(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(attachment_id): Path<Uuid>,
) -> Result<Json<DownloadAttachmentResponse>, AppError> {
    let mut tx=state.db.begin().await?;
    let row: Option<(Uuid, String, String, Option<Uuid>)> = sqlx::query_as(
        "SELECT uploader_user_id, object_key, status::text, message_id FROM attachments WHERE id = $1 AND NOT cleanup_pending",
    )
    .bind(attachment_id)
    .fetch_optional(&mut *tx)
    .await?;
    let (uploader_user_id, object_key, status, message_id) =
        row.ok_or_else(|| AppError::NotFound("Attachment not found.".into()))?;

    let authorized = if status == "attached" {
        match message_id {
            Some(message_id) => {
                let member: Option<(bool,)> = sqlx::query_as(
                    "SELECT true FROM messages m JOIN chat_members cm ON cm.chat_id = m.chat_id \
                     WHERE m.id = $1 AND m.deleted_at IS NULL AND cm.user_id = $2 AND cm.left_at IS NULL AND NOT user_device_chat_blocked($3,m.chat_id) FOR SHARE OF m",
                )
                .bind(message_id)
                .bind(auth.user_id)
                .bind(auth.device_id)
                .fetch_optional(&mut *tx)
                .await?;
                member.is_some()
            }
            None => false,
        }
    } else {
        uploader_user_id == auth.user_id
    };

    if !authorized {
        return Err(AppError::NotFound("Attachment not found.".into()));
    }

    let (download_url, expires_at) = state.s3.presign_get(&object_key);
    tx.commit().await?;
    Ok(Json(DownloadAttachmentResponse { download_url, expires_at }))
}
