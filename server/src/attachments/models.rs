use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Client declares only the safe minimum: how many encrypted bytes it's
/// about to upload. Never a filename, mime type, or media type - those are
/// only ever visible inside the Signal-encrypted message content.
#[derive(Debug, Deserialize)]
pub struct CreateAttachmentRequest {
    pub client_attachment_id: Uuid,
    pub encrypted_size: i64,
}

#[derive(Debug, Serialize)]
pub struct CreateAttachmentResponse {
    pub attachment_id: Uuid,
    pub object_key: String,
    pub upload_url: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
pub struct CompleteAttachmentResponse {
    pub attachment_id: Uuid,
    pub status: String,
}

#[derive(Debug, Serialize)]
pub struct DownloadAttachmentResponse {
    pub download_url: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, sqlx::FromRow)]
pub struct AttachmentRow {
    pub id: Uuid,
    pub uploader_user_id: Uuid,
    pub object_key: String,
    pub encrypted_size: i64,
    pub status: String,
}
