//! Ten-minute authenticated relay for an opaque, recipient-encrypted recovery
//! key. Clients authenticate the handoff using the comparison code separately.
use crate::{
    auth::{
        middleware::{lock_active_user, AuthUser},
        rate_limit,
    },
    error::AppError,
    state::AppState,
};
use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, HeaderValue},
    routing::{get, post},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/history-transfer", post(create))
        .route("/history-transfer/:id", get(read))
        .route("/history-transfer/:id/publish", post(publish))
        .route("/history-transfer/:id/ack", post(ack))
        .route("/history-transfer/:id/cancel", post(cancel))
        .layer(axum::extract::DefaultBodyLimit::max(2048))
        .layer(axum::middleware::map_response(no_store))
}
async fn no_store(mut response: axum::response::Response) -> axum::response::Response {
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Create {
    recipient_public_key: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Publish {
    expected_revision: i64,
    backup_id: Uuid,
    sender_public_key: String,
    ciphertext: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Ack {
    backup_id: Uuid,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Cancel {}
#[derive(sqlx::FromRow)]
struct Row {
    transfer_id: Uuid,
    owner_id: Uuid,
    recipient_device_id: Uuid,
    recipient_public_key: String,
    expires_at: DateTime<Utc>,
    status: String,
    sender_device_id: Option<Uuid>,
    sender_public_key: Option<String>,
    backup_id: Option<Uuid>,
    ciphertext: Option<String>,
    publication_hash: Option<String>,
}
#[derive(Serialize)]
pub struct Record {
    transfer_id: Uuid,
    server_id: Uuid,
    owner_id: Uuid,
    recipient_device_id: Uuid,
    recipient_public_key: String,
    expires_at_ms: i64,
    status: String,
    sender_public_key: Option<String>,
    backup_id: Option<Uuid>,
    ciphertext: Option<String>,
}
type Reply = (HeaderMap, Json<Record>);
fn base64_value(value: &str, size: usize) -> Result<Vec<u8>, AppError> {
    if value.len() != ((size + 2) / 3) * 4 {
        return Err(AppError::Validation("Invalid transfer encoding.".into()));
    }
    let bytes = STANDARD
        .decode(value)
        .map_err(|_| AppError::Validation("Invalid transfer encoding.".into()))?;
    if bytes.len() != size || STANDARD.encode(&bytes) != value {
        return Err(AppError::Validation("Invalid transfer encoding.".into()));
    }
    Ok(bytes)
}
fn public_key(value: &str) -> Result<(), AppError> {
    if base64_value(value, 32)?.iter().all(|v| *v == 0) {
        return Err(AppError::Validation("Invalid transfer public key.".into()));
    }
    Ok(())
}
async fn budget(
    state: &AppState,
    auth: &AuthUser,
    operation: &str,
    limit: u64,
) -> Result<(), AppError> {
    rate_limit::enforce(
        state,
        &format!("history-transfer:{operation}:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        limit,
        3600,
    )
    .await
}
async fn begin(
    state: &AppState,
    auth: &AuthUser,
) -> Result<sqlx::Transaction<'static, sqlx::Postgres>, AppError> {
    let mut tx = state.db.begin().await?;
    lock_active_user(&mut tx, auth).await?;
    Ok(tx)
}
async fn owned(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
    id: Uuid,
) -> Result<Row, AppError> {
    sqlx::query_as("SELECT transfer_id,owner_id,recipient_device_id,recipient_public_key,expires_at,status,sender_device_id,sender_public_key,backup_id,ciphertext,publication_hash FROM history_transfers WHERE transfer_id=$1 AND owner_id=$2 FOR UPDATE")
        .bind(id).bind(auth.user_id).fetch_optional(&mut **tx).await?.ok_or_else(||AppError::NotFound("Transfer not found.".into()))
}
fn unexpired(row: &Row) -> Result<(), AppError> {
    if row.expires_at <= Utc::now() {
        return Err(AppError::Gone(
            "Transfer expired. Create a new QR code.".into(),
        ));
    }
    Ok(())
}
async fn recipient_active(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    row: &Row,
) -> Result<(), AppError> {
    let active:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM devices d WHERE d.id=$1 AND d.user_id=$2 AND d.revoked_at IS NULL
        AND EXISTS(SELECT 1 FROM sessions s WHERE s.device_id=d.id AND s.user_id=d.user_id AND s.revoked_at IS NULL AND s.expires_at>now())
        AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=d.installation_id AND (b.until_date IS NULL OR b.until_date>now())))")
        .bind(row.recipient_device_id).bind(row.owner_id).fetch_one(&mut **tx).await?;
    if !active {
        return Err(AppError::Conflict(
            "Receiving device is no longer active.".into(),
        ));
    }
    Ok(())
}
async fn reply(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    row: Row,
    auth: &AuthUser,
) -> Result<Reply, AppError> {
    let server_id: Uuid =
        sqlx::query_scalar("SELECT server_id FROM history_backup_server WHERE singleton=true")
            .fetch_one(&mut **tx)
            .await?;
    let envelope =
        auth.device_id == row.recipient_device_id || row.sender_device_id == Some(auth.device_id);
    let mut headers = HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok((
        headers,
        Json(Record {
            transfer_id: row.transfer_id,
            server_id,
            owner_id: row.owner_id,
            recipient_device_id: row.recipient_device_id,
            recipient_public_key: row.recipient_public_key,
            expires_at_ms: row.expires_at.timestamp_millis(),
            status: row.status,
            sender_public_key: if envelope {
                row.sender_public_key
            } else {
                None
            },
            backup_id: if envelope { row.backup_id } else { None },
            ciphertext: if envelope { row.ciphertext } else { None },
        }),
    ))
}
pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<Create>,
) -> Result<Reply, AppError> {
    public_key(&req.recipient_public_key)?;
    budget(&state, &auth, "create", 12).await?;
    let mut tx = begin(&state, &auth).await?;
    let pending:i64=sqlx::query_scalar("SELECT count(*) FROM history_transfers WHERE owner_id=$1 AND status IN ('waiting','ready') AND expires_at>now()")
        .bind(auth.user_id).fetch_one(&mut *tx).await?;
    if pending >= 4 {
        return Err(AppError::Conflict(
            "Close an existing transfer before creating another.".into(),
        ));
    }
    let id = Uuid::new_v4();
    let expiry = DateTime::<Utc>::from_timestamp_millis(Utc::now().timestamp_millis() + 600000)
        .ok_or(AppError::Internal)?;
    sqlx::query("INSERT INTO history_transfers(transfer_id,owner_id,recipient_device_id,recipient_public_key,expires_at) VALUES($1,$2,$3,$4,$5)")
        .bind(id).bind(auth.user_id).bind(auth.device_id).bind(req.recipient_public_key).bind(expiry).execute(&mut *tx).await?;
    let row = owned(&mut tx, &auth, id).await?;
    let result = reply(&mut tx, row, &auth).await?;
    tx.commit().await?;
    Ok(result)
}
pub async fn read(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<Reply, AppError> {
    budget(&state, &auth, "read", 3600).await?;
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_session(&mut tx, &auth).await?;
    let row = owned(&mut tx, &auth, id).await?;
    unexpired(&row)?;
    let result = reply(&mut tx, row, &auth).await?;
    tx.commit().await?;
    Ok(result)
}
pub async fn publish(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<Publish>,
) -> Result<Reply, AppError> {
    public_key(&req.sender_public_key)?;
    base64_value(&req.ciphertext, 60)?;
    if req.expected_revision < 0 || req.backup_id.is_nil() {
        return Err(AppError::Validation("Invalid backup reference.".into()));
    }
    budget(&state, &auth, "publish", 120).await?;
    let mut tx = begin(&state, &auth).await?;
    let row = owned(&mut tx, &auth, id).await?;
    unexpired(&row)?;
    if auth.device_id == row.recipient_device_id {
        return Err(AppError::Conflict(
            "Use the other phone to approve this transfer.".into(),
        ));
    }
    recipient_active(&mut tx, &row).await?;
    let digest = hex::encode(Sha256::digest(
        format!(
            "{}\n{}\n{}\n{}",
            req.expected_revision, req.backup_id, req.sender_public_key, req.ciphertext
        )
        .as_bytes(),
    ));
    if row.status == "cancelled" {
        return Err(AppError::Conflict("Transfer was cancelled.".into()));
    }
    if row.status == "ready" || row.status == "consumed" {
        if row.sender_device_id != Some(auth.device_id)
            || row.publication_hash.as_deref() != Some(&digest)
        {
            return Err(AppError::Conflict(
                "Transfer was already published with different content.".into(),
            ));
        }
        let result = reply(&mut tx, row, &auth).await?;
        tx.commit().await?;
        return Ok(result);
    }
    let valid:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM history_backup_accounts a JOIN history_backup_objects b ON b.user_id=a.user_id WHERE a.user_id=$1 AND a.desired_enabled AND a.revision=$2 AND b.backup_id=$3 AND b.state='active' AND b.verified_at IS NOT NULL)")
        .bind(auth.user_id).bind(req.expected_revision).bind(req.backup_id).fetch_one(&mut *tx).await?;
    if !valid {
        return Err(AppError::Conflict(
            "Refresh the confirmed backup before publishing the transfer.".into(),
        ));
    }
    sqlx::query("UPDATE history_transfers SET status='ready',sender_device_id=$2,sender_public_key=$3,backup_id=$4,ciphertext=$5,publication_hash=$6 WHERE transfer_id=$1")
        .bind(id).bind(auth.device_id).bind(req.sender_public_key).bind(req.backup_id).bind(req.ciphertext).bind(digest).execute(&mut *tx).await?;
    let row = owned(&mut tx, &auth, id).await?;
    let result = reply(&mut tx, row, &auth).await?;
    tx.commit().await?;
    Ok(result)
}
pub async fn ack(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(req): Json<Ack>,
) -> Result<Reply, AppError> {
    budget(&state, &auth, "ack", 300).await?;
    let mut tx = begin(&state, &auth).await?;
    let row = owned(&mut tx, &auth, id).await?;
    unexpired(&row)?;
    if row.recipient_device_id != auth.device_id {
        return Err(AppError::NotFound("Transfer not found.".into()));
    }
    if row.backup_id != Some(req.backup_id) || !matches!(row.status.as_str(), "ready" | "consumed")
    {
        return Err(AppError::Conflict(
            "Transfer is not ready for this acknowledgement.".into(),
        ));
    }
    sqlx::query("UPDATE history_transfers SET status='consumed',ciphertext=NULL,sender_public_key=NULL WHERE transfer_id=$1").bind(id).execute(&mut *tx).await?;
    let row = owned(&mut tx, &auth, id).await?;
    let result = reply(&mut tx, row, &auth).await?;
    tx.commit().await?;
    Ok(result)
}
pub async fn cancel(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(_): Json<Cancel>,
) -> Result<Reply, AppError> {
    budget(&state, &auth, "cancel", 300).await?;
    let mut tx = begin(&state, &auth).await?;
    let row = owned(&mut tx, &auth, id).await?;
    if row.recipient_device_id != auth.device_id && row.sender_device_id != Some(auth.device_id) {
        return Err(AppError::NotFound("Transfer not found.".into()));
    }
    // Consumed remains consumed; cancellation can never undo an acknowledgement.
    sqlx::query("UPDATE history_transfers SET status=CASE WHEN status='consumed' THEN status ELSE 'cancelled' END,ciphertext=NULL,sender_public_key=NULL WHERE transfer_id=$1")
        .bind(id).execute(&mut *tx).await?;
    let row = owned(&mut tx, &auth, id).await?;
    let result = reply(&mut tx, row, &auth).await?;
    tx.commit().await?;
    Ok(result)
}
pub async fn cleanup_once(state: &AppState) -> Result<(), AppError> {
    // Account lock is unnecessary: publication checks expiry while holding row
    // lock, and these updates cannot make a terminal row writable again.
    sqlx::query("UPDATE history_transfers SET status=CASE WHEN status='consumed' THEN status ELSE 'cancelled' END,ciphertext=NULL,sender_public_key=NULL WHERE expires_at<=now() AND (ciphertext IS NOT NULL OR status='waiting')").execute(&state.db).await?;
    sqlx::query("DELETE FROM history_transfers WHERE expires_at<now()-interval '24 hours'")
        .execute(&state.db)
        .await?;
    Ok(())
}
pub fn spawn_cleanup(state: AppState) {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(60));
        loop {
            tick.tick().await;
            if cleanup_once(&state).await.is_err() {
                tracing::warn!("history transfer cleanup will retry");
            }
        }
    });
}
