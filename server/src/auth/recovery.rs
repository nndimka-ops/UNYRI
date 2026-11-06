//! Optional account recovery. This credential never decrypts history or unlocks administration.
use crate::net::ClientIp;
use crate::websocket::{self, protocol::ServerMessage};
use crate::{
    auth::{password, rate_limit, refresh_token, AuthUser},
    error::AppError,
    state::AppState,
};
use axum::{
    extract::State,
    http::StatusCode,
    Json,
};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::net::{IpAddr, Ipv4Addr};
use uuid::Uuid;

pub fn routes() -> axum::Router<AppState> {
    use axum::routing::{get, post};
    axum::Router::new()
        .route("/status", get(status))
        .route("/code", post(issue))
        .route("/disable", post(disable))
        .route("/reset", post(reset))
        .layer(axum::extract::DefaultBodyLimit::max(4096))
        .layer(axum::middleware::map_response(
            |mut response: axum::response::Response| async move {
                response.headers_mut().insert(
                    axum::http::header::CACHE_CONTROL,
                    axum::http::HeaderValue::from_static("no-store"),
                );
                response
            },
        ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PasswordRequest {
    password: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ResetRequest {
    username: String,
    code: String,
    new_password: String,
}
#[derive(Serialize)]
pub struct RecoveryStatus {
    enabled: bool,
}
#[derive(Serialize)]
pub struct IssuedCode {
    code: String,
}

pub(crate) fn normalize(code: &str) -> Option<String> {
    if code.len() > 128 {
        return None;
    }
    let raw: String = code
        .chars()
        .filter(|c| !c.is_ascii_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();
    let secret = raw.strip_prefix("R1")?;
    (secret.len() == 40 && secret.bytes().all(|b| b.is_ascii_hexdigit())).then(|| secret.to_owned())
}
pub(crate) fn hash(user: Uuid, normalized: &str) -> Vec<u8> {
    let mut digest = Sha256::new();
    digest.update(b"COCO-ACCOUNT-RECOVERY-V1\0");
    digest.update(user.as_bytes());
    digest.update(normalized.as_bytes());
    digest.finalize().to_vec()
}
pub(crate) fn generate() -> Result<String, AppError> {
    let mut bytes = [0u8; 20];
    rand::rngs::OsRng
        .try_fill_bytes(&mut bytes)
        .map_err(|_| AppError::Internal)?;
    let raw = hex::encode_upper(bytes);
    Ok(format!(
        "R1-{}",
        raw.as_bytes()
            .chunks(5)
            .map(|c| String::from_utf8_lossy(c).into_owned())
            .collect::<Vec<_>>()
            .join("-")
    ))
}

pub async fn status(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<RecoveryStatus>, AppError> {
    let enabled =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM account_recovery WHERE user_id=$1)")
            .bind(auth.user_id)
            .fetch_one(&state.db)
            .await?;
    Ok(Json(RecoveryStatus { enabled }))
}

async fn confirm_password(
    state: &AppState,
    auth: &AuthUser,
    supplied: String,
    ip: IpAddr,
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
) -> Result<(), AppError> {
    if supplied.len() > 1024 || supplied.is_empty() {
        return Err(AppError::InvalidCredentials);
    }
    rate_limit::enforce(state, "recovery-configure", ip, 10, 300).await?;
    rate_limit::enforce(
        state,
        &format!("recovery-configure:{}", auth.user_id),
        Ipv4Addr::UNSPECIFIED.into(),
        5,
        300,
    )
    .await?;
    let stored: String = sqlx::query_scalar(
        "SELECT password_hash FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(auth.user_id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or(AppError::Unauthorized)?;
    let valid = password::run(move || Ok(password::verify_password(&supplied, &stored))).await?;
    if !valid {
        return Err(AppError::InvalidCredentials);
    }
    let active: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM sessions WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL AND expires_at>now())")
        .bind(auth.session_id).bind(auth.user_id).fetch_one(&mut **tx).await?;
    if !active {
        return Err(AppError::SessionRevoked);
    }
    Ok(())
}

pub async fn issue(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Json(req): Json<PasswordRequest>,
) -> Result<Json<IssuedCode>, AppError> {
    let mut tx = state.db.begin().await?;
    confirm_password(&state, &auth, req.password, ip, &mut tx).await?;
    let code = generate()?;
    let normalized = normalize(&code).ok_or(AppError::Internal)?;
    sqlx::query("INSERT INTO account_recovery(user_id,code_hash,expires_at,issued_by_admin) VALUES($1,$2,NULL,NULL) ON CONFLICT(user_id) DO UPDATE SET code_hash=EXCLUDED.code_hash,created_at=now(),expires_at=NULL,issued_by_admin=NULL")
        .bind(auth.user_id).bind(hash(auth.user_id, &normalized)).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(IssuedCode { code }))
}

pub async fn disable(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Json(req): Json<PasswordRequest>,
) -> Result<StatusCode, AppError> {
    let mut tx = state.db.begin().await?;
    confirm_password(&state, &auth, req.password, ip, &mut tx).await?;
    sqlx::query("DELETE FROM account_recovery WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn reset(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    Json(req): Json<ResetRequest>,
) -> Result<StatusCode, AppError> {
    rate_limit::enforce(&state, "recovery-reset", ip, 10, 300).await?;
    if req.username.len() > 128 || !(8..=256).contains(&req.new_password.chars().count()) {
        return Err(AppError::Validation(
            "Enter your handle and a new password of 8-256 characters.".into(),
        ));
    }
    let normalized_user: String = sqlx::query_scalar("SELECT lower($1::text)")
        .bind(&req.username)
        .fetch_one(&state.db)
        .await?;
    let bucket = format!(
        "recovery-reset:{:x}",
        Sha256::digest(normalized_user.as_bytes())
    );
    rate_limit::enforce(&state, &bucket, Ipv4Addr::UNSPECIFIED.into(), 10, 300).await?;
    let code = normalize(&req.code).ok_or(AppError::InvalidCredentials)?;
    let mut tx = state.db.begin().await?;
    // All credential/session creation locks users before sessions. A concurrent
    // old-password login or refresh cannot create a surviving session after reset.
    let user: Option<Uuid> = sqlx::query_scalar("SELECT id FROM users WHERE username_normalized=$1 AND deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=users.id AND (b.until_date IS NULL OR b.until_date>now())) FOR UPDATE")
        .bind(normalized_user).fetch_optional(&mut *tx).await?;
    let Some(user) = user else {
        return Err(AppError::InvalidCredentials);
    };
    let expected: Option<Vec<u8>> =
        sqlx::query_scalar("SELECT code_hash FROM account_recovery WHERE user_id=$1 AND (expires_at IS NULL OR expires_at>now()) FOR UPDATE")
            .bind(user)
            .fetch_optional(&mut *tx)
            .await?;
    if !refresh_token::constant_time_eq(
        expected.as_deref().unwrap_or(&[0u8; 32]),
        &hash(user, &code),
    ) {
        return Err(AppError::InvalidCredentials);
    }
    let password_hash = password::run(move || password::hash_password(&req.new_password)).await?;
    sqlx::query("UPDATE users SET password_hash=$2 WHERE id=$1")
        .bind(user)
        .bind(password_hash)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM account_recovery WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id IN (SELECT id FROM devices WHERE user_id=$1)").bind(user).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
        .bind(user)
        .execute(&mut *tx)
        .await?;
    // Close active calls in the SAME transaction; peers must not retain a live call.
    let calls: Vec<(Uuid,Uuid,Uuid)> = sqlx::query_as("UPDATE calls SET state='ended',end_reason='device_revoked',ended_at=now() WHERE (caller_user_id=$1 OR callee_user_id=$1) AND state<>'ended' RETURNING id,caller_user_id,callee_user_id")
        .bind(user).fetch_all(&mut *tx).await?;
    for (call, _, _) in &calls {
        sqlx::query("DELETE FROM call_participants_active WHERE call_id=$1")
            .bind(call)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    websocket::notify_user(
        &state,
        user,
        &ServerMessage::SessionRevoked {
            reason: "password_recovered".into(),
        },
    )
    .await;
    for (call, caller, callee) in calls {
        let event = ServerMessage::CallEnded {
            call_id: call,
            reason: "device_revoked".into(),
        };
        websocket::notify_user(&state, caller, &event).await;
        websocket::notify_user(&state, callee, &event).await;
        let push = crate::push::PushEvent::call_ended(call, "device_revoked");
        state
            .push
            .enqueue_for_users(&state.db, &[caller, callee], None, &push)
            .await;
    }
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn recovery_codes_are_random_scoped_and_strict() {
        let code = generate().unwrap();
        assert_ne!(code, generate().unwrap());
        let normalized = normalize(&code).unwrap();
        assert_eq!(normalized.len(), 40);
        assert_eq!(normalize(&code.to_lowercase()), Some(normalized.clone()));
        assert!(normalize("FFE3-43FD-FDS3-DSD2").is_none());
        assert!(normalize(&(code.clone() + "X")).is_none());
        assert_ne!(
            hash(Uuid::nil(), &normalized),
            hash(Uuid::new_v4(), &normalized)
        );
    }
}
