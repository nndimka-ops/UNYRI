//! An expiring, bounded mailbox for pairwise Signal-encrypted P2P signaling.
//! There is deliberately no attachment, S3, TURN, or file-byte upload API here.
use crate::{
    auth::{middleware::AuthUser, rate_limit},
    error::AppError,
    net::ClientIp,
    state::AppState,
};
use axum::{
    extract::{DefaultBodyLimit, Path, Query, State},
    routing::{get, post, put},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{PgConnection, Postgres, Transaction};
use uuid::Uuid;

const MAX_CIPHER: usize = 65_536;
const MAX_SIGNALS: i64 = 256;
const MAX_ACTIVE: i64 = 8;
const GLOBAL_BYTES: i64 = 64 * 1024 * 1024;
const GLOBAL_TRANSFERS: i64 = 512;
const TTL_SECONDS: i64 = 7 * 24 * 60 * 60;
// All allocation paths take this lock AFTER ordered contact/session locks.
const ALLOCATION_LOCK: i64 = 7_120_031_103;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/direct-transfers/capabilities", get(capabilities))
        .route("/direct-transfers/:id", put(register).get(metadata))
        .route("/direct-transfers/:id/claim", post(claim))
        .route("/direct-transfers/:id/signals", post(send).get(inbox))
        .route("/direct-transfers/:id/close", post(close))
        .layer(DefaultBodyLimit::max(96 * 1024))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Register {
    chat_id: Uuid,
    offer_client_message_id: Uuid,
    recipient_user_id: Uuid,
    expires_at: i64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Empty {}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SignalInput {
    client_signal_id: Uuid,
    recipient_device_id: Uuid,
    message_type: i16,
    ciphertext: String,
}
impl SignalInput {
    fn decode(&self) -> Result<Vec<u8>, AppError> {
        if !matches!(self.message_type, 1 | 2) || self.ciphertext.len() > MAX_CIPHER.div_ceil(3) * 4
        {
            return Err(AppError::Validation("Invalid signaling envelope.".into()));
        }
        let bytes = STANDARD
            .decode(&self.ciphertext)
            .map_err(|_| AppError::Validation("Invalid signaling envelope.".into()))?;
        if bytes.is_empty() || bytes.len() > MAX_CIPHER {
            return Err(AppError::Validation("Invalid signaling envelope.".into()));
        }
        Ok(bytes)
    }
}
#[derive(sqlx::FromRow)]
struct Transfer {
    id: Uuid,
    chat_id: Uuid,
    offer_message_id: Uuid,
    offer_client_message_id: Uuid,
    sender_user_id: Uuid,
    sender_device_id: Uuid,
    recipient_user_id: Uuid,
    recipient_device_id: Option<Uuid>,
    expires_at: DateTime<Utc>,
    closed_at: Option<DateTime<Utc>>,
}
#[derive(Serialize)]
pub struct Metadata {
    id: Uuid,
    chat_id: Uuid,
    offer_client_message_id: Uuid,
    sender_user_id: Uuid,
    sender_device_id: Uuid,
    recipient_user_id: Uuid,
    recipient_device_id: Option<Uuid>,
    expires_at: i64,
    status: &'static str,
}
impl Transfer {
    fn metadata(&self) -> Metadata {
        Metadata {
            id: self.id,
            chat_id: self.chat_id,
            offer_client_message_id: self.offer_client_message_id,
            sender_user_id: self.sender_user_id,
            sender_device_id: self.sender_device_id,
            recipient_user_id: self.recipient_user_id,
            recipient_device_id: self.recipient_device_id,
            expires_at: self.expires_at.timestamp(),
            status: if self.closed_at.is_some() {
                "closed"
            } else {
                "active"
            },
        }
    }
    fn is_sender(&self, auth: &AuthUser) -> bool {
        self.sender_user_id == auth.user_id && self.sender_device_id == auth.device_id
    }
    fn is_receiver(&self, auth: &AuthUser) -> bool {
        self.recipient_user_id == auth.user_id && self.recipient_device_id == Some(auth.device_id)
    }
}
const TRANSFER_COLUMNS:&str="id,chat_id,offer_message_id,offer_client_message_id,sender_user_id,sender_device_id,recipient_user_id,recipient_device_id,expires_at,closed_at";

async fn limit(
    state: &AppState,
    auth: &AuthUser,
    ip: std::net::IpAddr,
    write: bool,
) -> Result<(), AppError> {
    rate_limit::enforce(
        state,
        if write {
            "direct-file-write"
        } else {
            "direct-file-read"
        },
        ip,
        if write { 240 } else { 1200 },
        60,
    )
    .await?;
    // The authenticated device budget must remain stable across network changes.
    rate_limit::enforce(
        state,
        &format!(
            "direct-file-{}:{}",
            if write { "write" } else { "read" },
            auth.device_id
        ),
        std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED),
        if write { 120 } else { 180 },
        60,
    )
    .await
}
#[derive(Serialize)]
struct Capabilities {
    version: u8,
    max_file_bytes: i64,
    direct_only: bool,
}
async fn capabilities(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
) -> Result<Json<Capabilities>, AppError> {
    limit(&state, &auth, ip, false).await?;
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_active_session(&mut tx, &auth).await?;
    tx.commit().await?;
    Ok(Json(Capabilities {
        version: 1,
        max_file_bytes: 68_719_476_736,
        direct_only: true,
    }))
}

/// Reject stale offers, deleted/left chats, blocked peers and revoked devices.
/// Recipient must actually possess a server envelope for the original offer.
async fn validate(tx: &mut PgConnection, row: &Transfer, auth: &AuthUser) -> Result<(), AppError> {
    if row.expires_at <= Utc::now() {
        return Err(AppError::Gone("This transfer has expired.".into()));
    }
    if !row.is_sender(auth) && row.recipient_user_id != auth.user_id {
        return Err(AppError::ChatNotFound);
    }
    let valid:bool=sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM messages m JOIN chats c ON c.id=m.chat_id \
         JOIN users su ON su.id=$2 JOIN users ru ON ru.id=$3 \
         JOIN devices sd ON sd.id=$4 AND sd.user_id=su.id \
         WHERE m.id=$1 AND m.chat_id=$5 AND m.sender_device_id=sd.id AND m.sender_id=su.id \
         AND m.client_message_id=$6 AND m.kind='content' AND m.deleted_at IS NULL AND c.type='direct' \
         AND su.deleted_at IS NULL AND ru.deleted_at IS NULL AND sd.revoked_at IS NULL \
         AND EXISTS(SELECT 1 FROM chat_members WHERE chat_id=c.id AND user_id=su.id AND left_at IS NULL) \
         AND EXISTS(SELECT 1 FROM chat_members WHERE chat_id=c.id AND user_id=ru.id AND left_at IS NULL) \
         AND NOT user_device_chat_blocked(sd.id,c.id) \
         AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id IN (su.id,ru.id) AND (b.until_date IS NULL OR b.until_date>now())) \
         AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=sd.installation_id AND (b.until_date IS NULL OR b.until_date>now())) \
         AND ($7::uuid IS NULL OR EXISTS(SELECT 1 FROM devices rd WHERE rd.id=$7 AND rd.user_id=ru.id AND rd.revoked_at IS NULL \
           AND NOT user_device_chat_blocked(rd.id,c.id) \
           AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=rd.installation_id AND (b.until_date IS NULL OR b.until_date>now())))) \
         AND ($8::uuid=sd.id OR EXISTS(SELECT 1 FROM message_envelopes WHERE message_id=m.id AND recipient_device_id=$8)))")
        .bind(row.offer_message_id).bind(row.sender_user_id).bind(row.recipient_user_id).bind(row.sender_device_id)
        .bind(row.chat_id).bind(row.offer_client_message_id).bind(row.recipient_device_id).bind(auth.device_id)
        .fetch_one(&mut *tx).await?;
    if !valid {
        return Err(AppError::ChatNotFound);
    }
    Ok(())
}

async fn locked<'a>(
    state: &'a AppState,
    auth: &AuthUser,
    id: Uuid,
    write: bool,
) -> Result<(Transaction<'a, Postgres>, Transfer), AppError> {
    // Admit only the original sender or a recipient device holding the offer.
    // Do this BEFORE contact policy to keep unrelated UUID probes uniformly 404.
    // The transaction below repeats all checks after ordered account locks.
    let chat: Option<Uuid> =
        sqlx::query_scalar("SELECT chat_id FROM direct_file_transfers t WHERE id=$1 \
            AND ((sender_user_id=$2 AND sender_device_id=$3) OR \
            (recipient_user_id=$2 AND EXISTS(SELECT 1 FROM message_envelopes e WHERE e.message_id=t.offer_message_id AND e.recipient_device_id=$3)))")
            .bind(id).bind(auth.user_id).bind(auth.device_id)
            .fetch_optional(&state.db)
            .await?;
    let chat = chat.ok_or(AppError::ChatNotFound)?;
    let mut tx = state.db.begin().await?;
    crate::chats::policy::lock_contact_allowed(&mut tx, chat, auth.device_id).await?;
    if write {
        crate::auth::middleware::lock_active_user(&mut tx, auth).await?
    } else {
        crate::auth::middleware::lock_active_session(&mut tx, auth).await?
    }
    let row: Transfer = sqlx::query_as(&format!(
        "SELECT {TRANSFER_COLUMNS} FROM direct_file_transfers WHERE id=$1 FOR UPDATE"
    ))
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::ChatNotFound)?;
    validate(&mut tx, &row, auth).await?;
    Ok((tx, row))
}

async fn register(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    Path(id): Path<Uuid>,
    Json(input): Json<Register>,
) -> Result<Json<Metadata>, AppError> {
    limit(&state, &auth, ip, true).await?;
    if input.recipient_user_id == auth.user_id {
        return Err(AppError::Validation(
            "Choose another person for this transfer.".into(),
        ));
    }
    let expires = DateTime::<Utc>::from_timestamp(input.expires_at, 0)
        .ok_or_else(|| AppError::Validation("Invalid transfer expiry.".into()))?;
    let now = Utc::now();
    if expires <= now || expires > now + chrono::Duration::seconds(TTL_SECONDS + 300) {
        return Err(AppError::Validation(
            "Transfer expiry must be within seven days.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    crate::chats::policy::lock_contact_allowed(&mut tx, input.chat_id, auth.device_id).await?;
    crate::auth::middleware::lock_active_user(&mut tx, &auth).await?;
    let message:Option<(Uuid,DateTime<Utc>)>=sqlx::query_as("SELECT id,created_at FROM messages WHERE chat_id=$1 AND sender_id=$2 AND sender_device_id=$3 AND client_message_id=$4 AND kind='content' AND deleted_at IS NULL")
        .bind(input.chat_id).bind(auth.user_id).bind(auth.device_id).bind(input.offer_client_message_id).fetch_optional(&mut *tx).await?;
    let (message_id, created_at) = message.ok_or(AppError::ChatNotFound)?;
    if expires > created_at + chrono::Duration::seconds(TTL_SECONDS + 300) {
        return Err(AppError::Validation("Invalid transfer expiry.".into()));
    }
    let proposed = Transfer {
        id,
        chat_id: input.chat_id,
        offer_message_id: message_id,
        offer_client_message_id: input.offer_client_message_id,
        sender_user_id: auth.user_id,
        sender_device_id: auth.device_id,
        recipient_user_id: input.recipient_user_id,
        recipient_device_id: None,
        expires_at: expires,
        closed_at: None,
    };
    validate(&mut tx, &proposed, &auth).await?;
    sqlx::query("SELECT pg_advisory_xact_lock($1)")
        .bind(ALLOCATION_LOCK)
        .execute(&mut *tx)
        .await?;
    let existing: Option<Transfer> = sqlx::query_as(&format!(
        "SELECT {TRANSFER_COLUMNS} FROM direct_file_transfers WHERE id=$1"
    ))
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    let row = if let Some(old) = existing {
        if old.chat_id != proposed.chat_id
            || old.offer_client_message_id != proposed.offer_client_message_id
            || old.sender_device_id != auth.device_id
            || old.recipient_user_id != input.recipient_user_id
            || old.expires_at != expires
        {
            return Err(AppError::Conflict(
                "Transfer identity already exists.".into(),
            ));
        }
        old
    } else {
        let active:i64=sqlx::query_scalar("SELECT count(*) FROM direct_file_transfers WHERE sender_user_id=$1 AND closed_at IS NULL AND expires_at>now()")
            .bind(auth.user_id).fetch_one(&mut *tx).await?;
        let total: i64 = sqlx::query_scalar("SELECT count(*) FROM direct_file_transfers")
            .fetch_one(&mut *tx)
            .await?;
        if active >= MAX_ACTIVE || total >= GLOBAL_TRANSFERS {
            return Err(AppError::RateLimited);
        }
        let alias:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM direct_file_transfers WHERE sender_device_id=$1 AND offer_client_message_id=$2)")
            .bind(auth.device_id).bind(input.offer_client_message_id).fetch_one(&mut *tx).await?;
        if alias {
            return Err(AppError::Conflict(
                "The offer already belongs to another transfer.".into(),
            ));
        }
        sqlx::query("INSERT INTO direct_file_transfers(id,chat_id,offer_message_id,offer_client_message_id,sender_user_id,sender_device_id,recipient_user_id,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8)")
            .bind(id).bind(input.chat_id).bind(message_id).bind(input.offer_client_message_id).bind(auth.user_id).bind(auth.device_id).bind(input.recipient_user_id).bind(expires).execute(&mut *tx).await?;
        proposed
    };
    tx.commit().await?;
    Ok(Json(row.metadata()))
}
async fn metadata(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    Path(id): Path<Uuid>,
) -> Result<Json<Metadata>, AppError> {
    limit(&state, &auth, ip, false).await?;
    let (tx, row) = locked(&state, &auth, id, false).await?;
    tx.commit().await?;
    Ok(Json(row.metadata()))
}
async fn claim(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    Path(id): Path<Uuid>,
    Json(_): Json<Empty>,
) -> Result<Json<Metadata>, AppError> {
    limit(&state, &auth, ip, true).await?;
    let (mut tx, mut row) = locked(&state, &auth, id, true).await?;
    if auth.user_id != row.recipient_user_id {
        return Err(AppError::ChatNotFound);
    }
    if row.closed_at.is_some() {
        return Err(AppError::Gone("This transfer is closed.".into()));
    }
    if let Some(device) = row.recipient_device_id {
        if device != auth.device_id {
            return Err(AppError::Conflict(
                "The transfer was accepted on another device.".into(),
            ));
        }
    } else {
        sqlx::query("UPDATE direct_file_transfers SET recipient_device_id=$2 WHERE id=$1")
            .bind(id)
            .bind(auth.device_id)
            .execute(&mut *tx)
            .await?;
        row.recipient_device_id = Some(auth.device_id)
    }
    validate(&mut tx, &row, &auth).await?;
    tx.commit().await?;
    Ok(Json(row.metadata()))
}
#[derive(Serialize)]
struct SignalReceipt {
    sequence: i64,
    client_signal_id: Uuid,
}
async fn send(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    Path(id): Path<Uuid>,
    Json(input): Json<SignalInput>,
) -> Result<Json<SignalReceipt>, AppError> {
    let bytes = input.decode()?;
    limit(&state, &auth, ip, true).await?;
    let (mut tx, row) = locked(&state, &auth, id, true).await?;
    let recipient = if row.is_sender(&auth) {
        row.recipient_device_id
            .ok_or_else(|| AppError::Conflict("Waiting for the recipient to accept.".into()))?
    } else if row.is_receiver(&auth) {
        row.sender_device_id
    } else {
        return Err(AppError::ChatNotFound);
    };
    if input.recipient_device_id != recipient {
        return Err(AppError::ChatNotFound);
    }
    sqlx::query("SELECT pg_advisory_xact_lock($1)")
        .bind(ALLOCATION_LOCK)
        .execute(&mut *tx)
        .await?;
    let existing:Option<(i64,Uuid,Uuid,i16,Vec<u8>)>=sqlx::query_as("SELECT sequence,transfer_id,recipient_device_id,message_type,ciphertext FROM direct_file_signals WHERE sender_device_id=$1 AND client_signal_id=$2")
        .bind(auth.device_id).bind(input.client_signal_id).fetch_optional(&mut *tx).await?;
    let sequence = if let Some((sequence, transfer, to, kind, cipher)) = existing {
        if transfer != id || to != recipient || kind != input.message_type || cipher != bytes {
            return Err(AppError::Conflict(
                "Signaling identity already exists.".into(),
            ));
        }
        sequence
    } else {
        if row.closed_at.is_some() {
            return Err(AppError::Gone("This transfer is closed.".into()));
        }
        let count: i64 =
            sqlx::query_scalar("SELECT count(*) FROM direct_file_signals WHERE transfer_id=$1")
                .bind(id)
                .fetch_one(&mut *tx)
                .await?;
        let used: i64 = sqlx::query_scalar(
            "SELECT COALESCE(sum(octet_length(ciphertext)),0)::bigint FROM direct_file_signals",
        )
        .fetch_one(&mut *tx)
        .await?;
        if count >= MAX_SIGNALS || used + bytes.len() as i64 > GLOBAL_BYTES {
            return Err(AppError::RateLimited);
        }
        sqlx::query_scalar("INSERT INTO direct_file_signals(transfer_id,client_signal_id,sender_user_id,sender_device_id,recipient_device_id,message_type,ciphertext) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING sequence")
            .bind(id).bind(input.client_signal_id).bind(auth.user_id).bind(auth.device_id).bind(recipient).bind(input.message_type).bind(bytes).fetch_one(&mut *tx).await?
    };
    tx.commit().await?;
    Ok(Json(SignalReceipt {
        sequence,
        client_signal_id: input.client_signal_id,
    }))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct InboxQuery {
    #[serde(default)]
    after: i64,
    #[serde(default = "page_size")]
    limit: i64,
}
fn page_size() -> i64 {
    32
}
#[derive(Serialize)]
struct SignalDto {
    sequence: i64,
    client_signal_id: Uuid,
    sender_user_id: Uuid,
    sender_device_id: Uuid,
    recipient_device_id: Uuid,
    message_type: i16,
    ciphertext: String,
}
#[derive(Serialize)]
struct Inbox {
    transfer: Metadata,
    signals: Vec<SignalDto>,
    has_more: bool,
}
async fn inbox(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    Path(id): Path<Uuid>,
    Query(query): Query<InboxQuery>,
) -> Result<Json<Inbox>, AppError> {
    if query.after < 0 || !(1..=32).contains(&query.limit) {
        return Err(AppError::Validation("Invalid signaling page.".into()));
    }
    limit(&state, &auth, ip, false).await?;
    let (mut tx, row) = locked(&state, &auth, id, false).await?;
    if !row.is_sender(&auth) && !row.is_receiver(&auth) {
        return Err(AppError::Conflict(
            "Accept the transfer on this device first.".into(),
        ));
    }
    type Stored = (i64, Uuid, Uuid, Uuid, Uuid, i16, Vec<u8>);
    let mut stored:Vec<Stored>=sqlx::query_as("SELECT sequence,client_signal_id,sender_user_id,sender_device_id,recipient_device_id,message_type,ciphertext FROM direct_file_signals WHERE transfer_id=$1 AND recipient_device_id=$2 AND sequence>$3 ORDER BY sequence LIMIT $4")
        .bind(id).bind(auth.device_id).bind(query.after).bind(query.limit+1).fetch_all(&mut *tx).await?;
    let has_more = stored.len() > query.limit as usize;
    stored.truncate(query.limit as usize);
    let signals = stored
        .into_iter()
        .map(
            |(
                sequence,
                client_signal_id,
                sender_user_id,
                sender_device_id,
                recipient_device_id,
                message_type,
                ciphertext,
            )| SignalDto {
                sequence,
                client_signal_id,
                sender_user_id,
                sender_device_id,
                recipient_device_id,
                message_type,
                ciphertext: STANDARD.encode(ciphertext),
            },
        )
        .collect();
    tx.commit().await?;
    Ok(Json(Inbox {
        transfer: row.metadata(),
        signals,
        has_more,
    }))
}
async fn close(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    Path(id): Path<Uuid>,
    Json(_): Json<Empty>,
) -> Result<Json<Metadata>, AppError> {
    limit(&state, &auth, ip, true).await?;
    let (mut tx, mut row) = locked(&state, &auth, id, true).await?;
    if !row.is_sender(&auth) && !row.is_receiver(&auth) {
        return Err(AppError::ChatNotFound);
    }
    row.closed_at=Some(sqlx::query_scalar("UPDATE direct_file_transfers SET closed_at=COALESCE(closed_at,now()) WHERE id=$1 RETURNING closed_at").bind(id).fetch_one(&mut *tx).await?);
    tx.commit().await?;
    Ok(Json(row.metadata()))
}
pub fn spawn_cleanup(state: AppState) {
    tokio::spawn(async move {
        loop {
            if sqlx::query("DELETE FROM direct_file_transfers WHERE id IN (SELECT id FROM direct_file_transfers WHERE expires_at<=now() ORDER BY expires_at LIMIT 256)").execute(&state.db).await.is_err(){tracing::warn!("direct-file signaling cleanup will retry")}
            tokio::time::sleep(std::time::Duration::from_secs(600)).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    fn envelope(bytes: Vec<u8>) -> SignalInput {
        SignalInput {
            client_signal_id: Uuid::new_v4(),
            recipient_device_id: Uuid::new_v4(),
            message_type: 2,
            ciphertext: STANDARD.encode(bytes),
        }
    }
    #[test]
    fn envelope_is_bounded_and_never_accepts_empty_cipher() {
        assert!(envelope(vec![]).decode().is_err());
        assert!(envelope(vec![1; MAX_CIPHER]).decode().is_ok());
        assert!(envelope(vec![1; MAX_CIPHER + 1]).decode().is_err());
        let mut bad = envelope(vec![1]);
        bad.message_type = 3;
        assert!(bad.decode().is_err());
        bad.message_type = 2;
        bad.ciphertext = "%%%".into();
        assert!(bad.decode().is_err());
    }
    #[test]
    fn plaintext_or_file_fields_are_not_a_signaling_api() {
        let input = serde_json::json!({"client_signal_id":Uuid::new_v4(),"recipient_device_id":Uuid::new_v4(),"message_type":2,"ciphertext":"AQ==","filename":"private"});
        assert!(serde_json::from_value::<SignalInput>(input).is_err());
    }
}
