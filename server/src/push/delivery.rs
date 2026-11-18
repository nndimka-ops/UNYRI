//! Delivery receipts from the iOS notification service extension.
//!
//! The extension runs for every message alert, even when the app was closed, but
//! it holds no account credentials. Each alert therefore carries a short-lived
//! token bound to the recipient device and the message, signed with a key derived
//! from the server secret. `POST /push/delivered` accepts only that exact pair, so
//! the token cannot mark anything else delivered and leaks nothing about content.

use std::sync::OnceLock;

use axum::{extract::State, Json};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use hmac::{Hmac, Mac};
use serde::Deserialize;
use sha2::Sha256;
use uuid::Uuid;

use crate::{auth::rate_limit, error::AppError, net::ClientIp, state::AppState};

static KEY: OnceLock<Vec<u8>> = OnceLock::new();

/// Lifetime of a delivery token; the extension reports within seconds.
pub const TOKEN_TTL_SECONDS: i64 = 3 * 24 * 60 * 60;

static ORIGIN: OnceLock<Option<String>> = OnceLock::new();

/// Public HTTPS origin the extension posts to (env PUBLIC_API_ORIGIN, e.g.
/// https://chat.example.com). Users can sign in to any server, so the extension
/// cannot rely on the origin bundled in the app.
pub fn public_origin() -> Option<&'static str> {
    ORIGIN.get_or_init(|| {
        std::env::var("PUBLIC_API_ORIGIN").ok()
            .map(|value| value.trim().trim_end_matches('/').to_string())
            .filter(|value| value.starts_with("https://") && value.len() <= 200 && !value.contains(char::is_whitespace))
    }).as_deref()
}

pub fn init(secret: &str) {
    let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(secret.as_bytes()) else { return };
    mac.update(b"unyri-delivery-receipt-v1");
    let _ = KEY.set(mac.finalize().into_bytes().to_vec());
}

fn message(device: Uuid, message: Uuid, expires_at: i64) -> String {
    format!("dlv:{device}:{message}:{expires_at}")
}

pub fn token(device: Uuid, message_id: Uuid, expires_at: i64) -> Option<String> {
    let key = KEY.get()?;
    let mut mac = Hmac::<Sha256>::new_from_slice(key).ok()?;
    mac.update(message(device, message_id, expires_at).as_bytes());
    Some(URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes()))
}

pub fn verify(device: Uuid, message_id: Uuid, expires_at: i64, token: &str, now: i64) -> bool {
    if expires_at < now || expires_at > now + TOKEN_TTL_SECONDS + 60 { return false; }
    let (Some(key), Ok(raw)) = (KEY.get(), URL_SAFE_NO_PAD.decode(token)) else { return false };
    let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(key) else { return false };
    mac.update(message(device, message_id, expires_at).as_bytes());
    mac.verify_slice(&raw).is_ok()
}

#[derive(Debug, Deserialize)]
pub struct DeliveredByPush {
    pub device_id: Uuid,
    pub message_id: Uuid,
    pub expires_at: i64,
    pub token: String,
}

/// `POST /push/delivered` - unauthenticated; the token is the authorization.
pub async fn delivered(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    Json(req): Json<DeliveredByPush>,
) -> Result<Json<serde_json::Value>, AppError> {
    rate_limit::enforce(&state, "push_delivered:ip", addr, 600, 60).await?;
    let now = chrono::Utc::now().timestamp();
    if req.token.len() > 128 || !verify(req.device_id, req.message_id, req.expires_at, &req.token, now) {
        return Err(AppError::NotFound("Not found.".into()));
    }
    // The device must be live and must actually have received this message.
    let target: Option<(Uuid, Uuid, i64)> = sqlx::query_as(
        "SELECT d.user_id, m.chat_id, m.sequence FROM devices d \
         JOIN message_envelopes me ON me.recipient_device_id = d.id AND me.message_id = $2 \
         JOIN messages m ON m.id = me.message_id \
         WHERE d.id = $1 AND d.revoked_at IS NULL AND m.deleted_at IS NULL",
    )
    .bind(req.device_id)
    .bind(req.message_id)
    .fetch_optional(&state.db)
    .await?;
    let Some((user_id, chat_id, sequence)) = target else { return Err(AppError::NotFound("Not found.".into())) };
    crate::messages::handlers::mark_delivered_through(&state, user_id, chat_id, sequence).await?;
    Ok(Json(serde_json::json!({"ok": true})))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tokens_bind_device_message_and_expiry() {
        init("test-secret-for-delivery-tokens");
        let (device, msg) = (Uuid::new_v4(), Uuid::new_v4());
        let now = 1_800_000_000;
        let exp = now + 3600;
        let token = token(device, msg, exp).unwrap();
        assert!(verify(device, msg, exp, &token, now));
        assert!(!verify(Uuid::new_v4(), msg, exp, &token, now));
        assert!(!verify(device, Uuid::new_v4(), exp, &token, now));
        assert!(!verify(device, msg, exp + 1, &token, now));
        assert!(!verify(device, msg, exp, &token, exp + 1));
        assert!(!verify(device, msg, exp, "AAAA", now));
    }
}
