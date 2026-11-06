//! Server-authoritative administration. No registration path writes roles.
mod cursor;
mod signing;
use crate::net::ClientIp;
use crate::{
    auth::{middleware::AuthUser, password, rate_limit},
    error::AppError,
    state::AppState,
};
use aes_gcm::{
    aead::{Aead, Payload},
    Aes256Gcm, KeyInit, Nonce,
};
use axum::{
    extract::{FromRequestParts, Path, Query, State},
    http::{header, request::Parts},
    response::IntoResponse,
    Json,
};
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use chrono::{DateTime, Duration, Utc};
use rand::RngCore;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

/// UTC ISO-8601 with microseconds, always ending in `Z`.
///
/// Every list endpoint emits timestamps in exactly this shape and the same
/// string is the cursor sort key, so the value a client echoes back is
/// guaranteed to re-parse as a timestamp instead of reaching the database as
/// malformed text.
const ISO_UTC: &str = r#"YYYY-MM-DD"T"HH24:MI:SS.US"Z""#;

pub fn routes() -> axum::Router<AppState> {
    use axum::routing::{get, post};
    axum::Router::new()
        .route("/status", get(status))
        .route("/setup", post(setup))
        .route("/unlock", post(unlock))
        .route("/security", get(security_status).post(change_security))
        .route("/security/prepare", post(prepare_security))
        .route("/key-challenge", post(signing::challenge))
        .route("/key-rotate", post(signing::rotate))
        .route("/lock", post(lock))
        .route("/console-handoff", post(crate::console::issue_handoff))
        .route("/reports", get(reports))
        .route("/reports/:id", post(review))
        .route("/feedback", get(feedback_list))
        .route("/feedback/:id", get(feedback_detail))
        .route("/feedback/:id/review", post(feedback_review))
        .route("/feedback/:id/screenshots/:index", get(feedback_screenshot))
        .route("/audit", get(audit))
        .route("/summary", get(summary))
        .route("/operations", get(crate::admin_operations::overview))
        .route(
            "/operations/controls",
            post(crate::admin_operations::save_controls),
        )
        .route(
            "/operations/announcements",
            post(crate::admin_operations::publish_announcement),
        )
        .route(
            "/operations/announcements/:id/disable",
            post(crate::admin_operations::disable_announcement),
        )
        .route(
            "/operators/:id",
            post(crate::admin_operations::change_operator),
        )
        .route(
            "/operators/:id/reset-2fa",
            post(crate::admin_operations::reset_operator_2fa),
        )
        .route("/performance", get(crate::performance::live))
        .route("/users", get(users))
        .route("/users/:id", get(user_detail))
        .route("/users/:id/avatar", get(user_avatar))
        .route("/users/:id/delete", post(delete_user))
        .route(
            "/users/:id/recovery-code",
            post(crate::admin_operations::issue_recovery_code),
        )
        .route("/users/:id/notice", post(send_user_notice))
        .route("/users/:id/security-events", get(user_security_events))
        .route("/users/:id/diagnostics", post(export_user_diagnostics))
        .route("/users/:id/profile-moderation", post(moderate_profile))
        .route("/users/:id/read-only", post(set_read_only))
        .route("/users/:id/timeline", get(user_timeline))
        .route("/users/:id/notes", get(user_notes).post(add_note))
        .route("/users/:id/suspension", post(suspend))
        .route(
            "/installations/:installation_id/suspension",
            post(suspend_installation),
        )
        .route("/devices/:device_id/logout", post(logout_device))
        .route(
            "/server-policy",
            get(crate::server_management::admin_policy).post(crate::server_management::save_policy),
        )
        .route("/storage", get(crate::server_management::storage))
        .route("/storage/preview", post(crate::server_management::preview))
        .route("/storage/cleanup", post(crate::server_management::cleanup))
        .layer(axum::extract::DefaultBodyLimit::max(8192))
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

fn denied() -> AppError {
    AppError::NotFound("Not found.".into())
}

fn required_reason(value: &str) -> Result<&str, AppError> {
    let value = value.trim();
    if value.is_empty() || value.chars().count() > 2000 {
        return Err(AppError::Validation(
            "A reason of 1-2000 characters is required.".into(),
        ));
    }
    Ok(value)
}

async fn ensure_moderatable(state: &AppState, admin: &Admin, id: Uuid) -> Result<(), AppError> {
    if id == admin.user || role(&state.db, id).await?.is_some() {
        return Err(denied());
    }
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)")
            .bind(id)
            .fetch_one(&state.db)
            .await?;
    if !exists {
        return Err(denied());
    }
    Ok(())
}
pub fn hash(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}
fn random_token() -> String {
    let mut b = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}
fn key() -> Result<Vec<u8>, AppError> {
    let value = std::env::var("COCO_ADMIN_KEY")
        .map_err(|_| AppError::Conflict("Server administration is not configured.".into()))?;
    let key = hex::decode(value).map_err(|_| AppError::Internal)?;
    if key.len() != 32 {
        return Err(AppError::Internal);
    }
    Ok(key)
}
fn seal(secret: &[u8], user: Uuid) -> Result<Vec<u8>, AppError> {
    let cipher = Aes256Gcm::new_from_slice(&key()?).map_err(|_| AppError::Internal)?;
    let mut nonce = [0u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: secret,
                aad: user.as_bytes(),
            },
        )
        .map_err(|_| AppError::Internal)?;
    Ok([nonce.to_vec(), ciphertext].concat())
}
fn open(secret: &[u8], user: Uuid) -> Result<Vec<u8>, AppError> {
    if secret.len() < 28 {
        return Err(AppError::Internal);
    }
    Aes256Gcm::new_from_slice(&key()?)
        .map_err(|_| AppError::Internal)?
        .decrypt(
            Nonce::from_slice(&secret[..12]),
            Payload {
                msg: &secret[12..],
                aad: user.as_bytes(),
            },
        )
        .map_err(|_| AppError::Internal)
}
fn totp(secret: Vec<u8>, user: Uuid) -> Result<totp_rs::Totp, AppError> {
    totp_rs::Builder::new()
        .with_secret(secret)
        .with_skew(1)
        .with_account_name(user.to_string())
        .with_issuer(Some("UNYRI administrator"))
        .build()
        .map_err(|_| AppError::Internal)
}
pub async fn role(db: &sqlx::PgPool, user: Uuid) -> Result<Option<String>, AppError> {
    Ok(sqlx::query_scalar("SELECT a.role FROM server_administrators a JOIN users u ON u.id=a.user_id WHERE a.user_id=$1 AND u.deleted_at IS NULL").bind(user).fetch_optional(db).await?)
}
async fn privileged(state: &AppState, auth: &AuthUser) -> Result<(), AppError> {
    if role(&state.db, auth.user_id).await?.is_none() {
        return Err(denied());
    }
    Ok(())
}
async fn check_password(
    state: &AppState,
    auth: &AuthUser,
    supplied: String,
    ip: std::net::IpAddr,
) -> Result<(), AppError> {
    privileged(state, auth).await?;
    rate_limit::enforce(state, "admin_auth", ip, 15, 300).await?;
    rate_limit::enforce(
        state,
        &format!("admin_auth:{}", auth.user_id),
        std::net::Ipv4Addr::LOCALHOST.into(),
        10,
        300,
    )
    .await?;
    if supplied.len() > 1024 {
        return Err(AppError::InvalidCredentials);
    }
    let stored: String =
        sqlx::query_scalar("SELECT password_hash FROM users WHERE id=$1 AND deleted_at IS NULL")
            .bind(auth.user_id)
            .fetch_one(&state.db)
            .await?;
    let valid = password::run(move || Ok(password::verify_password(&supplied, &stored))).await?;
    if !valid {
        return Err(AppError::InvalidCredentials);
    }
    Ok(())
}
#[derive(Deserialize)]
pub struct Credentials {
    password: String,
    #[serde(default)]
    code: String,
    #[serde(default)]
    key_proof: Option<signing::Proof>,
    #[serde(default)]
    auth_policy: Option<String>,
    #[serde(default)]
    new_code: String,
    #[serde(default)]
    new_key_proof: Option<signing::Proof>,
}

const AUTH_POLICIES: [&str; 3] = ["authenticator", "paper_key", "both"];
fn needs_totp(policy: &str) -> bool {
    policy != "paper_key"
}
fn needs_paper(policy: &str) -> bool {
    policy != "authenticator"
}
fn policy(value: &str) -> Result<&str, AppError> {
    if AUTH_POLICIES.contains(&value) {
        Ok(value)
    } else {
        Err(AppError::Validation(
            "Choose authenticator, paper_key or both.".into(),
        ))
    }
}

#[derive(sqlx::FromRow)]
struct FactorState {
    secret: Option<Vec<u8>>,
    signing_public_key: Option<Vec<u8>>,
    auth_policy: String,
    auth_revision: i64,
    last_step: i64,
    pending_secret: Option<Vec<u8>>,
    pending_session: Option<Uuid>,
    pending_expires: Option<DateTime<Utc>>,
    pending_policy: Option<String>,
    pending_revision: Option<i64>,
}
impl FactorState {
    fn configured(&self) -> bool {
        (!needs_totp(&self.auth_policy) || self.secret.is_some())
            && (!needs_paper(&self.auth_policy) || self.signing_public_key.is_some())
    }
    fn prepared(&self, auth: &AuthUser, target: &str) -> bool {
        self.pending_session == Some(auth.session_id)
            && self.pending_policy.as_deref() == Some(target)
            && self.pending_revision == Some(self.auth_revision)
            && self.pending_expires.is_some_and(|date| date > Utc::now())
    }
}
const FACTORS_SQL: &str = "SELECT secret,signing_public_key,auth_policy,auth_revision,last_step,pending_secret,pending_session,pending_expires,pending_policy,pending_revision FROM server_administrators WHERE user_id=$1";

/// The request may have waited behind a factor change after HTTP extraction.
/// Re-admit it against committed account/device/session state at this point.
/// This deliberately adds no user/session row locks or reverse lock ordering.
async fn active_actor_after_wait(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
) -> Result<(), AppError> {
    let active: bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM sessions s JOIN users u ON u.id=s.user_id JOIN devices d ON d.id=s.device_id WHERE s.id=$1 AND s.user_id=$2 AND s.device_id=$3 AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp() AND u.deleted_at IS NULL AND d.revoked_at IS NULL AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=s.user_id AND (b.until_date IS NULL OR b.until_date>clock_timestamp())) AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=d.installation_id AND (b.until_date IS NULL OR b.until_date>clock_timestamp())) )")
        .bind(auth.session_id).bind(auth.user_id).bind(auth.device_id).fetch_one(&mut **tx).await?;
    if active {
        Ok(())
    } else {
        Err(AppError::SessionRevoked)
    }
}

async fn status_data(state: &AppState, user: Uuid) -> Result<Value, AppError> {
    let row: FactorState = sqlx::query_as(FACTORS_SQL)
        .bind(user)
        .fetch_one(&state.db)
        .await?;
    let server: Uuid =
        sqlx::query_scalar("SELECT server_id FROM admin_server_identity WHERE singleton")
            .fetch_one(&state.db)
            .await?;
    Ok(
        json!({"configured":row.configured(),"key_protocol":1,"auth_protocol":2,
        "server_id":server,"auth_policy":row.auth_policy,"auth_revision":row.auth_revision,
        "totp_configured":row.secret.is_some(),"paper_key_configured":row.signing_public_key.is_some(),
        "signing_public_key":row.signing_public_key.map(|v|URL_SAFE_NO_PAD.encode(v)),
        "available_policies":AUTH_POLICIES}),
    )
}

/// Initial setup may select a policy only before any factor has been enrolled.
/// A reset authenticator must keep the existing paper identity and policy.
pub async fn setup(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Json(body): Json<Credentials>,
) -> Result<Json<Value>, AppError> {
    check_password(&state, &auth, body.password, ip).await?;
    let mut tx = state.db.begin().await?;
    let row: FactorState = sqlx::query_as(&format!("{FACTORS_SQL} FOR UPDATE"))
        .bind(auth.user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(denied)?;
    active_actor_after_wait(&mut tx, &auth).await?;
    if row.configured() {
        return Err(AppError::Conflict(
            "Administration is already configured. Use security settings.".into(),
        ));
    }
    let target = policy(body.auth_policy.as_deref().unwrap_or(&row.auth_policy))?;
    if (row.secret.is_some() || row.signing_public_key.is_some()) && target != row.auth_policy {
        return Err(AppError::Conflict(
            "Complete the existing authentication setup before changing its policy.".into(),
        ));
    }
    let (sealed, qr, secret) =
        new_totp_setup(needs_totp(target) && row.secret.is_none(), auth.user_id)?;
    sqlx::query("UPDATE server_administrators SET pending_secret=$2,pending_session=$3,pending_expires=now()+interval '10 minutes',pending_policy=$4,pending_revision=auth_revision WHERE user_id=$1")
        .bind(auth.user_id).bind(sealed).bind(auth.session_id).bind(target).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(
        json!({"auth_policy":target,"qr_png":qr,"secret":secret,"enrollment":true,
        "paper_key_configured":row.signing_public_key.is_some(),
        "signing_public_key":row.signing_public_key.map(|v|URL_SAFE_NO_PAD.encode(v))}),
    ))
}

fn new_totp_setup(
    needed: bool,
    user: Uuid,
) -> Result<(Option<Vec<u8>>, Option<String>, Option<String>), AppError> {
    if !needed {
        return Ok((None, None, None));
    }
    let mut secret = vec![0; 20];
    rand::rngs::OsRng.fill_bytes(&mut secret);
    let otp = totp(secret.clone(), user)?;
    Ok((
        Some(seal(&secret, user)?),
        Some(otp.to_qr_base64().map_err(|_| AppError::Internal)?),
        Some(otp.secret().to_base32()),
    ))
}

pub async fn status(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<Value>, AppError> {
    privileged(&state, &auth).await?;
    Ok(Json(status_data(&state, auth.user_id).await?))
}
pub async fn security_status(
    State(state): State<AppState>,
    admin: Admin,
) -> Result<Json<Value>, AppError> {
    Ok(Json(status_data(&state, admin.user).await?))
}

async fn verify_totp(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
    encrypted: &[u8],
    supplied: &str,
    last: i64,
    allow_recovery: bool,
) -> Result<i64, AppError> {
    let otp = totp(open(encrypted, user)?, user)?;
    if let Some(step) = otp
        .check_current(supplied)
        .map(|v| v as i64)
        .filter(|v| *v > last)
    {
        return Ok(step);
    }
    if allow_recovery {
        let used =
            sqlx::query("DELETE FROM admin_recovery_codes WHERE user_id=$1 AND code_hash=$2")
                .bind(user)
                .bind(hash(supplied))
                .execute(&mut **tx)
                .await?;
        if used.rows_affected() == 1 {
            return Ok(last);
        }
    }
    Err(AppError::InvalidCredentials)
}
async fn new_recovery(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
) -> Result<Vec<String>, AppError> {
    sqlx::query("DELETE FROM admin_recovery_codes WHERE user_id=$1")
        .bind(user)
        .execute(&mut **tx)
        .await?;
    let mut codes = Vec::new();
    for _ in 0..8 {
        let code = random_token();
        sqlx::query("INSERT INTO admin_recovery_codes(user_id,code_hash) VALUES($1,$2)")
            .bind(user)
            .bind(hash(&code))
            .execute(&mut **tx)
            .await?;
        codes.push(code);
    }
    Ok(codes)
}

pub async fn unlock(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Json(body): Json<Credentials>,
) -> Result<Json<Value>, AppError> {
    check_password(&state, &auth, body.password, ip).await?;
    let mut tx = state.db.begin().await?;
    let row: FactorState = sqlx::query_as(&format!("{FACTORS_SQL} FOR UPDATE"))
        .bind(auth.user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(denied)?;
    active_actor_after_wait(&mut tx, &auth).await?;
    let enrolling = !row.configured();
    let target = if enrolling {
        // Old clients omit auth_policy; the pending policy still defaults to both.
        let target = body
            .auth_policy
            .as_deref()
            .or(row.pending_policy.as_deref())
            .unwrap_or(&row.auth_policy);
        policy(target)?;
        if !row.prepared(&auth, target) {
            return Err(AppError::InvalidCredentials);
        }
        if (row.secret.is_some() || row.signing_public_key.is_some()) && target != row.auth_policy {
            return Err(AppError::InvalidCredentials);
        }
        target
    } else {
        if body
            .auth_policy
            .as_ref()
            .is_some_and(|v| v != &row.auth_policy)
        {
            return Err(AppError::InvalidCredentials);
        }
        &row.auth_policy
    };
    let signing_key = if needs_paper(target) {
        Some(
            signing::consume(
                &mut tx,
                &auth,
                "unlock",
                body.key_proof
                    .as_ref()
                    .ok_or(AppError::InvalidCredentials)?,
            )
            .await?,
        )
    } else {
        None
    };
    let mut step = row.last_step;
    let new_totp = needs_totp(target) && row.secret.is_none();
    let encrypted = if needs_totp(target) {
        let secret = row
            .secret
            .as_ref()
            .or(row.pending_secret.as_ref())
            .ok_or(AppError::InvalidCredentials)?;
        step = verify_totp(
            &mut tx,
            auth.user_id,
            secret,
            &body.code,
            row.last_step,
            !new_totp,
        )
        .await?;
        Some(secret.clone())
    } else {
        None
    };
    let recovery = if new_totp {
        new_recovery(&mut tx, auth.user_id).await?
    } else {
        Vec::new()
    };
    sqlx::query("UPDATE server_administrators SET secret=$2,signing_public_key=$3,auth_policy=$4,pending_secret=NULL,pending_session=NULL,pending_expires=NULL,pending_policy=NULL,pending_revision=NULL,last_step=$5 WHERE user_id=$1")
        .bind(auth.user_id).bind(encrypted).bind(&signing_key).bind(target).bind(step).execute(&mut *tx).await?;
    let token = random_token();
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1 AND (auth_session=$2 OR last_used<now()-interval '12 hours' OR created_at<now()-interval '12 hours')")
        .bind(auth.user_id).bind(auth.session_id).execute(&mut *tx).await?;
    sqlx::query("INSERT INTO admin_sessions(token_hash,user_id,auth_session,signing_public_key,auth_revision) VALUES($1,$2,$3,$4,$5)")
        .bind(hash(&token)).bind(auth.user_id).bind(auth.session_id).bind(signing_key).bind(row.auth_revision).execute(&mut *tx).await?;
    sqlx::query("INSERT INTO admin_audit(actor,action) VALUES($1,$2)")
        .bind(auth.user_id)
        .bind(if enrolling { "enroll" } else { "unlock" })
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Json(
        json!({"token":token,"recovery_codes":recovery,"auth_policy":target}),
    ))
}

async fn ensure_native_session(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
    admin: &Admin,
    revision: i64,
) -> Result<(), AppError> {
    if admin.channel != AdminChannel::Native
        || auth.user_id != admin.user
        || auth.session_id != admin.auth_session
    {
        return Err(denied());
    }
    active_actor_after_wait(tx, auth).await?;
    let valid: bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM admin_sessions s JOIN sessions au ON au.id=s.auth_session WHERE s.token_hash=$1 AND s.user_id=$2 AND s.auth_session=$3 AND s.auth_revision=$4 AND s.last_used>clock_timestamp()-interval '12 hours' AND s.created_at>clock_timestamp()-interval '12 hours' AND au.revoked_at IS NULL AND au.expires_at>clock_timestamp())")
        .bind(&admin.token_hash).bind(auth.user_id).bind(auth.session_id).bind(revision).fetch_one(&mut **tx).await?;
    if valid {
        Ok(())
    } else {
        Err(denied())
    }
}

/// Preparing a new factor cannot change the active policy or remove a factor.
/// Commit below requires fresh proofs of the OLD policy and every NEW factor.
pub async fn prepare_security(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    admin: Admin,
    Json(body): Json<Credentials>,
) -> Result<Json<Value>, AppError> {
    check_password(&state, &auth, body.password, ip).await?;
    let target = policy(
        body.auth_policy
            .as_deref()
            .ok_or_else(|| AppError::Validation("auth_policy is required.".into()))?,
    )?;
    let mut tx = state.db.begin().await?;
    let row: FactorState = sqlx::query_as(&format!("{FACTORS_SQL} FOR UPDATE"))
        .bind(auth.user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(denied)?;
    ensure_native_session(&mut tx, &auth, &admin, row.auth_revision).await?;
    if target == row.auth_policy {
        return Err(AppError::Conflict(
            "This authentication policy is already active.".into(),
        ));
    }
    let requires_new_totp = needs_totp(target) && row.secret.is_none();
    let requires_new_paper = needs_paper(target) && row.signing_public_key.is_none();
    let (sealed, qr, secret) = new_totp_setup(requires_new_totp, auth.user_id)?;
    sqlx::query("UPDATE server_administrators SET pending_secret=$2,pending_session=$3,pending_expires=now()+interval '10 minutes',pending_policy=$4,pending_revision=auth_revision WHERE user_id=$1")
        .bind(auth.user_id).bind(sealed).bind(auth.session_id).bind(target).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(
        json!({"auth_policy":target,"qr_png":qr,"secret":secret,
        "requires_new_totp":requires_new_totp,"requires_new_paper_key":requires_new_paper,"expires_in":600}),
    ))
}

pub async fn change_security(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    admin: Admin,
    Json(body): Json<Credentials>,
) -> Result<Json<Value>, AppError> {
    check_password(&state, &auth, body.password, ip).await?;
    let target = policy(
        body.auth_policy
            .as_deref()
            .ok_or_else(|| AppError::Validation("auth_policy is required.".into()))?,
    )?;
    let mut tx = state.db.begin().await?;
    let row: FactorState = sqlx::query_as(&format!("{FACTORS_SQL} FOR UPDATE"))
        .bind(auth.user_id)
        .fetch_optional(&mut *tx)
        .await?
        .ok_or_else(denied)?;
    ensure_native_session(&mut tx, &auth, &admin, row.auth_revision).await?;
    if !row.configured() || !row.prepared(&auth, target) || target == row.auth_policy {
        return Err(AppError::InvalidCredentials);
    }
    // Even a stolen, still-live admin token cannot remove its owner's factors.
    if needs_paper(&row.auth_policy) {
        signing::consume(
            &mut tx,
            &auth,
            "authorize",
            body.key_proof
                .as_ref()
                .ok_or(AppError::InvalidCredentials)?,
        )
        .await?;
    }
    let mut step = row.last_step;
    if needs_totp(&row.auth_policy) {
        step = verify_totp(
            &mut tx,
            auth.user_id,
            row.secret.as_ref().ok_or(AppError::InvalidCredentials)?,
            &body.code,
            row.last_step,
            true,
        )
        .await?;
    }
    let signing_key = if needs_paper(target) {
        if let Some(existing) = row.signing_public_key.as_ref() {
            Some(existing.clone())
        } else {
            Some(
                signing::consume(
                    &mut tx,
                    &auth,
                    "enroll",
                    body.new_key_proof
                        .as_ref()
                        .ok_or(AppError::InvalidCredentials)?,
                )
                .await?,
            )
        }
    } else {
        None
    };
    let new_totp = needs_totp(target) && row.secret.is_none();
    let encrypted = if needs_totp(target) {
        if let Some(existing) = row.secret.as_ref() {
            Some(existing.clone())
        } else {
            let secret = row
                .pending_secret
                .as_ref()
                .ok_or(AppError::InvalidCredentials)?;
            step = verify_totp(&mut tx, auth.user_id, secret, &body.new_code, -1, false).await?;
            Some(secret.clone())
        }
    } else {
        step = -1;
        None
    };
    let recovery = if new_totp {
        new_recovery(&mut tx, auth.user_id).await?
    } else {
        Vec::new()
    };
    if !needs_totp(target) {
        sqlx::query("DELETE FROM admin_recovery_codes WHERE user_id=$1")
            .bind(auth.user_id)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query("UPDATE server_administrators SET auth_policy=$2,auth_revision=auth_revision+1,secret=$3,signing_public_key=$4,last_step=$5,pending_secret=NULL,pending_session=NULL,pending_expires=NULL,pending_policy=NULL,pending_revision=NULL WHERE user_id=$1")
        .bind(auth.user_id).bind(target).bind(encrypted).bind(signing_key).bind(step).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    audit_event(
        &mut tx,
        auth.user_id,
        "operator:authentication-policy",
        Some(auth.user_id),
        "Administrator changed own authentication methods",
        json!({"previous_policy":row.auth_policy,"auth_policy":target}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(
        json!({"ok":true,"reauthenticate":true,"auth_policy":target,"recovery_codes":recovery}),
    ))
}

/// Which channel authorized a request.
///
/// The two channels are deliberately not interchangeable: only [`AdminChannel::Native`]
/// may mint a browser session, so a stolen browser cookie cannot be turned into a
/// durable foothold, and the native session records the active authentication-policy revision.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AdminChannel {
    /// An unlocked native client, authenticated with `x-coco-admin`.
    Native,
    /// The server-hosted console, authenticated with its own cookie session.
    Console,
}

pub struct Admin {
    pub user: Uuid,
    pub role: String,
    /// Hash of the **native** administration session that authorized this
    /// request, whichever channel it came in on. `POST /admin/lock` deletes
    /// exactly this row, and the console session derives from it by foreign key.
    pub token_hash: String,
    pub auth_session: Uuid,
    pub channel: AdminChannel,
}
#[axum::async_trait]
impl FromRequestParts<AppState> for Admin {
    type Rejection = AppError;
    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, AppError> {
        // Channel 1: the native client. Unchanged from Pack 2 - the admin token
        // still requires a live bearer session, the active policy revision, and
        // the factors that were required when it was unlocked.
        //
        // The header value is copied out first: `AuthUser` needs a mutable borrow
        // of the request parts, which cannot coexist with a borrow of its headers.
        let native = parts
            .headers
            .get("x-coco-admin")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        if let Some(token) = native {
            if token.len() != 43 {
                return Err(denied());
            }
            let auth = AuthUser::from_request_parts(parts, state).await?;
            let token_hash = hash(&token);
            let role:Option<String>=sqlx::query_scalar("UPDATE admin_sessions s SET last_used=now() FROM server_administrators a WHERE s.token_hash=$1 AND s.user_id=$2 AND s.auth_session=$3 AND a.user_id=s.user_id AND s.auth_revision=a.auth_revision AND (a.auth_policy='paper_key' OR a.secret IS NOT NULL) AND (a.auth_policy='authenticator' OR (a.signing_public_key IS NOT NULL AND s.signing_public_key=a.signing_public_key)) AND s.last_used>now()-interval '12 hours' AND s.created_at>now()-interval '12 hours' RETURNING a.role")
                .bind(&token_hash).bind(auth.user_id).bind(auth.session_id).fetch_optional(&state.db).await?;
            return Ok(Self {
                user: auth.user_id,
                role: role.ok_or_else(denied)?,
                token_hash,
                auth_session: auth.session_id,
                channel: AdminChannel::Native,
            });
        }
        // Channel 2: the console. There is no bearer session in a browser, so
        // identity comes from the console session row, which is itself bound to
        // the native admin session by foreign key.
        //
        // An anonymous caller is answered exactly as before this pack. With no
        // administration credential at all there is nothing to hide, and the
        // bearer extractor's own 401 is what tells a client to sign in again -
        // which is the contract `/admin/*` has always had. Once a credential
        // *is* presented, a failure is indistinguishable from "not an
        // administrator" (404), so the route stays hidden.
        let token =
            match crate::console::read_cookie(&parts.headers, crate::console::SESSION_COOKIE) {
                Some(token) => token,
                None => {
                    AuthUser::from_request_parts(parts, state).await?;
                    return Err(denied());
                }
            };
        let safe =
            parts.method == axum::http::Method::GET || parts.method == axum::http::Method::HEAD;
        // Origin/Referer must prove same-origin before any state change. The
        // cookie is SameSite=Strict and the CSRF header is required below, so
        // this is the third independent barrier rather than the only one.
        if !safe && !crate::console::same_origin(&parts.headers) {
            return Err(denied());
        }
        let session = crate::console::validate(state, &token).await?;
        if !safe {
            let presented = parts
                .headers
                .get(crate::console::CSRF_HEADER)
                .and_then(|value| value.to_str().ok())
                .filter(|value| value.len() == 43)
                .ok_or_else(denied)?;
            if hash(presented) != session.csrf_hash {
                return Err(denied());
            }
        }
        Ok(Self {
            user: session.user,
            role: session.role,
            token_hash: session.admin_session,
            auth_session: session.auth_session,
            channel: AdminChannel::Console,
        })
    }
}
pub async fn lock(State(state): State<AppState>, admin: Admin) -> Result<Json<Value>, AppError> {
    sqlx::query("DELETE FROM admin_sessions WHERE token_hash=$1")
        .bind(admin.token_hash)
        .execute(&state.db)
        .await?;
    Ok(Json(json!({"ok":true})))
}
/// Fixed, whitelisted filter vocabularies. Clients cannot choose a sort column
/// or a filter field outside these sets.
const REPORT_STATUSES: [&str; 4] = ["open", "in_progress", "resolved", "dismissed"];
const REPORT_REASONS: [&str; 7] = [
    "spam",
    "harassment",
    "pornography",
    "scam",
    "self_harm",
    "copyright",
    "other",
];
const FEEDBACK_PLATFORMS: [&str; 2] = ["ios", "android"];
const RESOLUTION_STATUSES: [&str; 3] = ["open", "resolved", "dismissed"];
const ACCOUNT_STATUSES: [&str; 4] = ["active", "suspended", "read_only", "restricted"];

/// Appends an audit row. `detail` carries machine-readable, non-secret context
/// (affected installation, report id, note id) so the free-text reason stays
/// human-readable and clients can render technical detail as expandable
/// context rather than raw JSON.
pub(crate) async fn audit_event(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    actor: Uuid,
    action: &str,
    target: Option<Uuid>,
    reason: &str,
    detail: Value,
) -> Result<(), AppError> {
    sqlx::query(
        "INSERT INTO admin_audit(actor,action,target,reason,detail) VALUES($1,$2,$3,$4,$5)",
    )
    .bind(actor)
    .bind(action)
    .bind(target)
    .bind(reason)
    .bind(detail)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

#[derive(Deserialize, Default)]
pub struct ReportsQuery {
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    reported_user_id: Option<String>,
    #[serde(default)]
    from: Option<String>,
    #[serde(default)]
    to: Option<String>,
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    limit: Option<i64>,
}

/// User reports, newest first, with cursor pagination and server-side filters.
pub async fn reports(
    State(state): State<AppState>,
    _admin: Admin,
    Query(query): Query<ReportsQuery>,
) -> Result<Json<Value>, AppError> {
    let status = cursor::choice(query.status.as_deref(), &REPORT_STATUSES, "status")?;
    let reason = cursor::choice(query.reason.as_deref(), &REPORT_REASONS, "reason")?;
    let reported = cursor::uuid(query.reported_user_id.as_deref())?;
    let from = cursor::instant(query.from.as_deref())?;
    let to = cursor::instant(query.to.as_deref())?;
    let limit = cursor::limit(query.limit)?;
    let print = cursor::fingerprint(&[
        "reports",
        status.unwrap_or_default(),
        reason.unwrap_or_default(),
        &reported.map(|v| v.to_string()).unwrap_or_default(),
        &from.map(|v| v.to_rfc3339()).unwrap_or_default(),
        &to.map(|v| v.to_rfc3339()).unwrap_or_default(),
    ]);
    let after = cursor::decode_timestamp_uuid(query.cursor.as_deref(), &print)?;
    let sql = format!(
        "SELECT jsonb_build_object('id',r.id,'user_id',r.reported_user_id,
            'reporter_user_id',r.reporter_user_id,'reason',r.reason,'description',r.description,
            'reporter_username',(SELECT u.username FROM users u WHERE u.id=r.reporter_user_id),
            'reporter_display_name',(SELECT u.display_name FROM users u WHERE u.id=r.reporter_user_id),
            'reported_username',(SELECT u.username FROM users u WHERE u.id=r.reported_user_id),
            'reported_display_name',(SELECT u.display_name FROM users u WHERE u.id=r.reported_user_id),
            'status',r.review_status,'review_note',r.review_note,'decision_reason',r.decision_reason,
            'reviewed_by',r.reviewed_by,
            'reviewed_at',to_char(r.reviewed_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'created_at',to_char(r.created_at AT TIME ZONE 'UTC','{ISO_UTC}'))
         FROM chat_reports r
         WHERE ($1::text IS NULL OR r.review_status=$1)
           AND ($2::text IS NULL OR r.reason::text=$2)
           AND ($3::uuid IS NULL OR r.reported_user_id=$3)
           AND ($4::timestamptz IS NULL OR r.created_at>=$4)
           AND ($5::timestamptz IS NULL OR r.created_at<=$5)
           AND ($6::timestamptz IS NULL OR (r.created_at,r.id)<($6::timestamptz,$7::uuid))
         ORDER BY r.created_at DESC,r.id DESC LIMIT $8"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(status)
        .bind(reason)
        .bind(reported)
        .bind(from)
        .bind(to)
        .bind(after.map(|(stamp, _)| stamp))
        .bind(after.map(|(_, id)| id))
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(
        cursor::finish(rows, limit, &print, |row| {
            Some((
                row["created_at"].as_str()?.to_string(),
                row["id"].as_str()?.to_string(),
            ))
        })
        .into_json(),
    ))
}
#[derive(Deserialize)]
pub struct Review {
    status: String,
    note: String,
    #[serde(default)]
    decision_reason: String,
}
pub async fn review(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<Review>,
) -> Result<Json<Value>, AppError> {
    if !["open", "in_progress", "resolved", "dismissed"].contains(&body.status.as_str())
        || body.note.len() > 4000
        || body.decision_reason.len() > 4000
        || (["resolved", "dismissed"].contains(&body.status.as_str())
            && body.decision_reason.trim().is_empty())
    {
        return Err(AppError::Validation("Invalid review.".into()));
    }
    let mut tx = state.db.begin().await?;
    if sqlx::query("UPDATE chat_reports SET review_status=$2,review_note=$3,decision_reason=$4,reviewed_by=$5,reviewed_at=now() WHERE id=$1")
        .bind(id)
        .bind(&body.status)
        .bind(&body.note)
        .bind(body.decision_reason.trim())
        .bind(admin.user)
        .execute(&mut *tx)
        .await?
        .rows_affected()
        != 1
    {
        return Err(denied());
    }
    audit_event(
        &mut tx,
        admin.user,
        &format!("report:{}", body.status),
        Some(id),
        &format!("{}\n{}", body.note, body.decision_reason),
        json!({"report_id": id, "status": body.status}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

#[derive(Deserialize)]
pub struct FeedbackQuery {
    #[serde(default)]
    platform: Option<String>,
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    from: Option<String>,
    #[serde(default)]
    to: Option<String>,
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    limit: Option<i64>,
}

/// Technical feedback metadata, newest first.
///
/// Screenshots are never inlined here: the list and the detail view carry only
/// descriptors, and the bytes come from `/admin/feedback/:id/screenshots/:index`
/// on demand. `bug_reports.payload` has no category field, so no category filter
/// is offered rather than inventing one.
pub async fn feedback_list(
    State(state): State<AppState>,
    _admin: Admin,
    Query(query): Query<FeedbackQuery>,
) -> Result<Json<Value>, AppError> {
    let platform = cursor::choice(query.platform.as_deref(), &FEEDBACK_PLATFORMS, "platform")?;
    let status = cursor::choice(query.status.as_deref(), &RESOLUTION_STATUSES, "status")?;
    let from = cursor::instant(query.from.as_deref())?;
    let to = cursor::instant(query.to.as_deref())?;
    let limit = cursor::limit(query.limit)?;
    let print = cursor::fingerprint(&[
        "feedback",
        platform.unwrap_or_default(),
        status.unwrap_or_default(),
        &from.map(|v| v.to_rfc3339()).unwrap_or_default(),
        &to.map(|v| v.to_rfc3339()).unwrap_or_default(),
    ]);
    let after = cursor::decode_timestamp_uuid(query.cursor.as_deref(), &print)?;
    let sql = format!(
        "SELECT jsonb_build_object('id',b.id,'user_id',b.user_id,
            'description',b.payload->>'description','platform',b.payload->>'platform',
            'app_version',b.payload->>'app_version','os_version',b.payload->>'os_version',
            'screenshot_count',COALESCE(jsonb_array_length(b.payload->'screenshots'),0),
            'status',b.review_status,'review_note',b.review_note,'decision_reason',b.decision_reason,
            'reviewed_by',b.reviewed_by,
            'reviewed_at',to_char(b.reviewed_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'created_at',to_char(b.created_at AT TIME ZONE 'UTC','{ISO_UTC}'))
         FROM bug_reports b
         WHERE ($1::text IS NULL OR b.payload->>'platform'=$1)
           AND ($2::text IS NULL OR b.review_status=$2)
           AND ($3::timestamptz IS NULL OR b.created_at>=$3)
           AND ($4::timestamptz IS NULL OR b.created_at<=$4)
           AND ($5::timestamptz IS NULL OR (b.created_at,b.id)<($5::timestamptz,$6::uuid))
         ORDER BY b.created_at DESC,b.id DESC LIMIT $7"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(platform)
        .bind(status)
        .bind(from)
        .bind(to)
        .bind(after.map(|(stamp, _)| stamp))
        .bind(after.map(|(_, id)| id))
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(
        cursor::finish(rows, limit, &print, |row| {
            Some((
                row["created_at"].as_str()?.to_string(),
                row["id"].as_str()?.to_string(),
            ))
        })
        .into_json(),
    ))
}
#[derive(Deserialize)]
pub struct UsersQuery {
    #[serde(default)]
    query: Option<String>,
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    limit: Option<i64>,
}

/// Account search by username or display name, ordered by username.
///
/// Display-name matching is an administrator-only capability: the ordinary
/// `/users/search` surface remains username-only, so this does not widen what a
/// normal user can look up. Ordinariness of ordering relies on
/// `username_normalized` being UNIQUE (migration 0001), which makes the cursor
/// a total order without a tiebreaker column.
pub async fn users(
    State(state): State<AppState>,
    _admin: Admin,
    Query(query): Query<UsersQuery>,
) -> Result<Json<Value>, AppError> {
    let search = cursor::text(query.query.as_deref(), 100, "Search")?;
    let status = cursor::choice(query.status.as_deref(), &ACCOUNT_STATUSES, "status")?;
    let limit = cursor::limit(query.limit)?;
    let print = cursor::fingerprint(&[
        "users",
        search.unwrap_or_default(),
        status.unwrap_or_default(),
    ]);
    let after = cursor::decode_text_key_uuid(query.cursor.as_deref(), &print)?;
    let sql = format!(
        "SELECT jsonb_build_object('id',u.id,'user_id',u.id,
            'description',u.display_name,'reason',u.username,'sort_key',u.username_normalized,
            'installation_blocks',(SELECT COUNT(DISTINCT ib.installation_id)
                FROM installation_suspensions ib JOIN devices di ON di.installation_id=ib.installation_id
                WHERE di.user_id=u.id AND (ib.until_date IS NULL OR ib.until_date>now())),
            'status',CASE WHEN b.user_id IS NOT NULL AND (b.until_date IS NULL OR b.until_date>now())
                THEN 'suspended' WHEN EXISTS(SELECT 1 FROM admin_user_controls c WHERE c.user_id=u.id AND c.read_only_reason IS NOT NULL AND (c.read_only_until IS NULL OR c.read_only_until>now())) THEN 'read_only' ELSE 'active' END,
            'suspended_until',to_char(b.until_date AT TIME ZONE 'UTC','{ISO_UTC}'),
            'created_at',to_char(u.created_at AT TIME ZONE 'UTC','{ISO_UTC}'))
         FROM users u LEFT JOIN server_suspensions b ON b.user_id=u.id
         WHERE u.deleted_at IS NULL
           AND ($1::text IS NULL OR position(lower($1) in u.username_normalized)>0
                OR position(lower($1) in lower(u.display_name))>0)
           AND ($2::text IS NULL OR (CASE WHEN b.user_id IS NOT NULL AND (b.until_date IS NULL OR b.until_date>now())
                THEN 'suspended' WHEN EXISTS(SELECT 1 FROM admin_user_controls c WHERE c.user_id=u.id AND c.read_only_reason IS NOT NULL AND (c.read_only_until IS NULL OR c.read_only_until>now())) THEN 'read_only' ELSE 'active' END)=ANY(CASE WHEN $2='restricted' THEN ARRAY['suspended','read_only'] ELSE ARRAY[$2] END))
           AND ($3::text IS NULL OR u.username_normalized>$3)
         ORDER BY u.username_normalized LIMIT $4"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(search)
        .bind(status)
        .bind(after.map(|(key, _)| key))
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(
        cursor::finish(rows, limit, &print, |row| {
            Some((
                row["sort_key"].as_str()?.to_string(),
                row["user_id"].as_str()?.to_string(),
            ))
        })
        .into_json(),
    ))
}
/// Only the two image types the submission endpoint accepts may ever be served.
fn screenshot_magic_ok(mime: &str, bytes: &[u8]) -> bool {
    match mime {
        "image/jpeg" => bytes.starts_with(&[0xff, 0xd8, 0xff]),
        "image/png" => bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
        _ => false,
    }
}

/// Feedback detail: text and metadata plus screenshot *descriptors*.
///
/// The screenshot bytes deliberately stay in the database here. Sizes are
/// computed with `octet_length(decode(...))`, so even the base64 never leaves
/// Postgres for this endpoint.
pub async fn feedback_detail(
    State(state): State<AppState>,
    _admin: Admin,
    Path(id): Path<Uuid>,
) -> Result<Json<Value>, AppError> {
    let sql = format!(
        "SELECT jsonb_build_object('id',b.id,'user_id',b.user_id,
            'description',b.payload->>'description','platform',b.payload->>'platform',
            'app_version',b.payload->>'app_version','os_version',b.payload->>'os_version',
            'status',b.review_status,'review_note',b.review_note,'decision_reason',b.decision_reason,
            'reviewed_by',b.reviewed_by,
            'reviewed_at',to_char(b.reviewed_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'created_at',to_char(b.created_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'performance',b.payload->'performance',
            'screenshots',COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'index',(s.ordinality-1),
                    'mime_type',s.item->>'mime_type',
                    'byte_size',octet_length(decode(s.item->>'data_base64','base64'))))
                FROM jsonb_array_elements(COALESCE(b.payload->'screenshots','[]'::jsonb))
                    WITH ORDINALITY AS s(item,ordinality)),'[]'::jsonb))
         FROM bug_reports b WHERE b.id=$1"
    );
    let row: Option<Value> = sqlx::query_scalar(&sql)
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    Ok(Json(row.ok_or_else(denied)?))
}

/// Serves exactly one stored screenshot, addressed by its position in the
/// submission. There is no path, key or object identifier in the request, so
/// traversal and arbitrary object access are structurally impossible.
pub async fn feedback_screenshot(
    State(state): State<AppState>,
    _admin: Admin,
    Path((id, index)): Path<(Uuid, i64)>,
) -> Result<impl IntoResponse, AppError> {
    // A submission carries at most three screenshots (see feedback::Report).
    if !(0..3).contains(&index) {
        return Err(denied());
    }
    let payload: Option<Value> = sqlx::query_scalar("SELECT payload FROM bug_reports WHERE id=$1")
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    let payload = payload.ok_or_else(denied)?;
    let screenshot = payload
        .get("screenshots")
        .and_then(Value::as_array)
        .and_then(|items| items.get(index as usize))
        .ok_or_else(denied)?;
    let mime = screenshot
        .get("mime_type")
        .and_then(Value::as_str)
        .filter(|value| matches!(*value, "image/jpeg" | "image/png"))
        .ok_or_else(denied)?;
    let encoded = screenshot
        .get("data_base64")
        .and_then(Value::as_str)
        .ok_or_else(denied)?;
    // Re-validate on read: stored rows are trusted, but a bounded, magic-checked
    // response is what the client is allowed to render.
    let bytes = STANDARD.decode(encoded).map_err(|_| denied())?;
    if bytes.is_empty() || bytes.len() > 1024 * 1024 || !screenshot_magic_ok(mime, &bytes) {
        return Err(denied());
    }
    Ok((
        [
            (header::CONTENT_TYPE, mime.to_string()),
            (header::CACHE_CONTROL, "no-store".to_string()),
        ],
        bytes,
    ))
}

#[derive(Deserialize)]
pub struct FeedbackReview {
    status: String,
    #[serde(default)]
    note: String,
    #[serde(default)]
    decision_reason: String,
}

/// Feedback has no decision to appeal and no account to restrict, so a closed
/// status always requires a reason; `open` re-opens the item.
pub async fn feedback_review(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<FeedbackReview>,
) -> Result<Json<Value>, AppError> {
    if !RESOLUTION_STATUSES.contains(&body.status.as_str())
        || body.note.chars().count() > 2000
        || body.decision_reason.chars().count() > 2000
        || (body.status != "open" && body.decision_reason.trim().is_empty())
    {
        return Err(AppError::Validation("Invalid review.".into()));
    }
    let mut tx = state.db.begin().await?;
    if sqlx::query("UPDATE bug_reports SET review_status=$2,review_note=$3,decision_reason=$4,reviewed_by=$5,reviewed_at=now() WHERE id=$1")
        .bind(id)
        .bind(&body.status)
        .bind(&body.note)
        .bind(body.decision_reason.trim())
        .bind(admin.user)
        .execute(&mut *tx)
        .await?
        .rows_affected()
        != 1
    {
        return Err(denied());
    }
    audit_event(
        &mut tx,
        admin.user,
        &format!("feedback:{}", body.status),
        Some(id),
        &format!("{}\n{}", body.note, body.decision_reason),
        json!({"feedback_id": id, "status": body.status}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}
#[derive(Deserialize)]
pub struct AuditQuery {
    #[serde(default)]
    action: Option<String>,
    #[serde(default)]
    actor: Option<String>,
    #[serde(default)]
    target: Option<String>,
    #[serde(default)]
    from: Option<String>,
    #[serde(default)]
    to: Option<String>,
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    limit: Option<i64>,
}

/// Owner-only action journal, newest first.
pub async fn audit(
    State(state): State<AppState>,
    admin: Admin,
    Query(query): Query<AuditQuery>,
) -> Result<Json<Value>, AppError> {
    if admin.role != "owner" {
        return Err(denied());
    }
    let action = cursor::text(query.action.as_deref(), 100, "Action")?;
    let actor = cursor::uuid(query.actor.as_deref())?;
    let target = cursor::uuid(query.target.as_deref())?;
    let from = cursor::instant(query.from.as_deref())?;
    let to = cursor::instant(query.to.as_deref())?;
    let limit = cursor::limit(query.limit)?;
    let print = cursor::fingerprint(&[
        "audit",
        action.unwrap_or_default(),
        &actor.map(|v| v.to_string()).unwrap_or_default(),
        &target.map(|v| v.to_string()).unwrap_or_default(),
        &from.map(|v| v.to_rfc3339()).unwrap_or_default(),
        &to.map(|v| v.to_rfc3339()).unwrap_or_default(),
    ]);
    let after = cursor::decode_timestamp_i64(query.cursor.as_deref(), &print)?;
    let sql = format!(
        "SELECT jsonb_build_object('id',a.id::text,'actor',a.actor,'action',a.action,
            'target',a.target,'reason',a.reason,'detail',a.detail,
            'created_at',to_char(a.created_at AT TIME ZONE 'UTC','{ISO_UTC}'))
         FROM admin_audit a
         WHERE ($1::text IS NULL OR a.action=$1)
           AND ($2::uuid IS NULL OR a.actor=$2)
           AND ($3::uuid IS NULL OR a.target=$3)
           AND ($4::timestamptz IS NULL OR a.created_at>=$4)
           AND ($5::timestamptz IS NULL OR a.created_at<=$5)
           AND ($6::timestamptz IS NULL OR (a.created_at,a.id)<($6::timestamptz,$7::bigint))
         ORDER BY a.created_at DESC,a.id DESC LIMIT $8"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(action)
        .bind(actor)
        .bind(target)
        .bind(from)
        .bind(to)
        .bind(after.map(|(stamp, _)| stamp))
        .bind(after.map(|(_, id)| id))
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(
        cursor::finish(rows, limit, &print, |row| {
            Some((
                row["created_at"].as_str()?.to_string(),
                row["id"].as_str()?.to_string(),
            ))
        })
        .into_json(),
    ))
}

/// One lightweight request behind the dashboard.
///
/// Counters are computed by the database, never derived from a client's
/// first page of results. Only moderation totals and (for owners) aggregate
/// storage figures are exposed - never message counts, chat content, message
/// plaintext, ciphertext analytics or secret material.
pub async fn summary(State(state): State<AppState>, admin: Admin) -> Result<Json<Value>, AppError> {
    let (
        open_reports,
        in_progress_reports,
        unresolved_feedback,
        active_account_suspensions,
        active_installation_suspensions,
        reports_last_24h,
    ): (i64, i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT
           (SELECT count(*) FROM chat_reports WHERE review_status='open'),
           (SELECT count(*) FROM chat_reports WHERE review_status='in_progress'),
           (SELECT count(*) FROM bug_reports WHERE review_status='open'),
           (SELECT count(*) FROM server_suspensions WHERE until_date IS NULL OR until_date>now()),
           (SELECT count(*) FROM installation_suspensions WHERE until_date IS NULL OR until_date>now()),
           (SELECT count(*) FROM chat_reports WHERE created_at>now()-interval '24 hours')",
    )
    .fetch_one(&state.db)
    .await?;
    // Moderators see the same moderation counters; aggregate storage is owner-only
    // and is reported as an explicit null rather than a fabricated zero.
    let storage = match admin.role.as_str() {
        "owner" => {
            let (used, reserved, budget) = crate::server_management::figures(&state).await?;
            json!({"used_bytes":used,"reserved_bytes":reserved,"budget_bytes":budget})
        }
        _ => Value::Null,
    };
    Ok(Json(json!({
        "open_reports": open_reports,
        "in_progress_reports": in_progress_reports,
        "unresolved_feedback": unresolved_feedback,
        "active_account_suspensions": active_account_suspensions,
        "active_installation_suspensions": active_installation_suspensions,
        "reports_last_24h": reports_last_24h,
        "storage": storage,
    })))
}

/// The User Moderation Center payload: everything an administrator needs about
/// one account, and nothing about its private conversations.
///
/// Primary fields are human-readable (username, display name, device label).
/// Raw identifiers are still returned because a client must be able to address
/// a device for moderation, but they are documented as technical detail and the
/// clients present them collapsed.
pub async fn user_detail(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
) -> Result<Json<Value>, AppError> {
    let sql = format!(
        "SELECT jsonb_build_object(
            'user_id',u.id,'username',u.username,'display_name',u.display_name,
            'bio',u.bio,
            'has_avatar',u.avatar_key IS NOT NULL,
            'created_at',to_char(u.created_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'suspended',b.user_id IS NOT NULL AND (b.until_date IS NULL OR b.until_date>now()),
            'suspended_until',to_char(b.until_date AT TIME ZONE 'UTC','{ISO_UTC}'),
            'suspension_reason',b.reason,
            'read_only',c.user_id IS NOT NULL AND c.read_only_reason IS NOT NULL
                AND (c.read_only_until IS NULL OR c.read_only_until>now()),
            'read_only_until',to_char(c.read_only_until AT TIME ZONE 'UTC','{ISO_UTC}'),
            'read_only_reason',c.read_only_reason,
            'reports_total',(SELECT count(*) FROM chat_reports r WHERE r.reported_user_id=u.id),
            'reports_open',(SELECT count(*) FROM chat_reports r WHERE r.reported_user_id=u.id
                AND r.review_status IN ('open','in_progress')),
            'recent_security_events',(SELECT count(*) FROM security_events e WHERE e.user_id=u.id
                AND e.scope='account' AND e.created_at>now()-interval '24 hours'),
            'notes',(SELECT count(*) FROM admin_notes n WHERE n.subject_user_id=u.id),
            'is_administrator',EXISTS(SELECT 1 FROM server_administrators a WHERE a.user_id=u.id))
         FROM users u
         LEFT JOIN server_suspensions b ON b.user_id=u.id
         LEFT JOIN admin_user_controls c ON c.user_id=u.id
         WHERE u.id=$1 AND u.deleted_at IS NULL"
    );
    let row: Option<Value> = sqlx::query_scalar(&sql)
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    let row = row.ok_or_else(denied)?;

    let devices_sql = format!(
        "SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'id',d.id,'installation_id',d.installation_id,'platform',d.platform,
            'display_name',d.display_name,'app_version',d.app_version,'os_version',d.os_version,
            'signal_device_id',d.signal_device_id,
            'created_at',to_char(d.created_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'last_active_at',to_char(d.last_active_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'revoked',d.revoked_at IS NOT NULL,
            'restriction_active',COALESCE(r.restricted,false),
            'restriction_until',to_char(r.until_date AT TIME ZONE 'UTC','{ISO_UTC}'),
            'restriction_reason',r.reason,
            'is_administrator_device',EXISTS(SELECT 1 FROM server_administrators a WHERE a.user_id=d.user_id)
         ) ORDER BY d.last_active_at DESC NULLS LAST,d.created_at DESC),'[]'::jsonb)
         FROM devices d
         LEFT JOIN LATERAL (
            SELECT true AS restricted,ib.until_date,ib.reason
              FROM installation_suspensions ib
             WHERE ib.installation_id=d.installation_id
               AND (ib.until_date IS NULL OR ib.until_date>now())
             ORDER BY ib.created_at DESC LIMIT 1
         ) r ON TRUE
         WHERE d.user_id=$1"
    );
    let devices: Value = sqlx::query_scalar(&devices_sql)
        .bind(id)
        .fetch_one(&state.db)
        .await?;

    let is_administrator = row["is_administrator"].as_bool().unwrap_or(false);
    let suspended = row["suspended"].as_bool().unwrap_or(false);
    let read_only = row["read_only"].as_bool().unwrap_or(false);
    let open_reports = row["reports_open"].as_i64().unwrap_or(0);
    let recent_security_events = row["recent_security_events"].as_i64().unwrap_or(0);
    let device_rows = devices.as_array().cloned().unwrap_or_default();
    let active_devices = device_rows
        .iter()
        .filter(|device| !device["revoked"].as_bool().unwrap_or(false))
        .count() as i64;
    let restricted_devices = device_rows
        .iter()
        .filter(|device| device["restriction_active"].as_bool().unwrap_or(false))
        .count() as i64;
    let mut risk_score = 0i64;
    let mut risk_reasons: Vec<String> = Vec::new();
    if suspended {
        risk_score += 50;
        risk_reasons.push("Аккаунт ограничен".into());
    }
    if read_only {
        risk_score += 10;
        risk_reasons.push("Включён режим только чтение".into());
    }
    if open_reports > 0 {
        risk_score += (open_reports * 15).min(45);
        risk_reasons.push(format!("Открытых жалоб: {open_reports}"));
    }
    if restricted_devices > 0 {
        risk_score += 20;
        risk_reasons.push(format!("Ограничено установок: {restricted_devices}"));
    }
    if active_devices >= 4 {
        risk_score += 10;
        risk_reasons.push(format!("Много активных устройств: {active_devices}"));
    }
    if recent_security_events >= 3 {
        risk_score += 15;
        risk_reasons.push(format!(
            "Событий безопасности за 24 ч: {recent_security_events}"
        ));
    }
    risk_score = risk_score.min(100);
    let risk_level = if risk_score >= 60 {
        "high"
    } else if risk_score >= 20 {
        "watch"
    } else {
        "normal"
    };
    Ok(Json(json!({
        "profile": {
            "user_id": row["user_id"],
            "username": row["username"],
            "display_name": row["display_name"],
            "bio": row["bio"],
            "has_avatar": row["has_avatar"],
            "created_at": row["created_at"],
        },
        "moderation": {
            "status": if suspended { "suspended" } else { "active" },
            "until": row["suspended_until"],
            "reason": row["suspension_reason"],
            "reports_total": row["reports_total"],
            "reports_open": row["reports_open"],
            "notes": row["notes"],
            "read_only": read_only,
            "read_only_until": row["read_only_until"],
            "read_only_reason": row["read_only_reason"],
        },
        "devices": devices,
        "security": {
            "risk_score": risk_score,
            "risk_level": risk_level,
            "reasons": risk_reasons,
            "active_devices": active_devices,
            "restricted_devices": restricted_devices,
            "recent_security_events": recent_security_events,
        },
        "is_administrator": is_administrator,
        // Mirrors the server-side rule in `suspend` so the client can explain
        // the restriction instead of discovering it as a failure.
        "suspend_allowed": id != admin.user && !is_administrator,
        "moderation_allowed": id != admin.user && !is_administrator,
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UserNoticeRequest {
    title: String,
    body: String,
    severity: String,
    reason: String,
}

pub async fn send_user_notice(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<UserNoticeRequest>,
) -> Result<Json<Value>, AppError> {
    let reason = required_reason(&body.reason)?;
    let title = body.title.trim();
    let message = body.body.trim();
    let severity = body.severity.as_str();
    if title.is_empty()
        || title.chars().count() > 120
        || message.is_empty()
        || message.chars().count() > 2000
        || !["info", "warning", "critical"].contains(&severity)
    {
        return Err(AppError::Validation("Invalid user notification.".into()));
    }
    let mut tx = state.db.begin().await?;
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)")
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
    if !exists {
        return Err(denied());
    }
    let notice_id = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO admin_user_notices(id,user_id,title,body,severity,created_by) VALUES($1,$2,$3,$4,$5,$6)",
    ).bind(notice_id).bind(id).bind(title).bind(message).bind(severity)
        .bind(admin.user).execute(&mut *tx).await?;
    crate::devices::handlers::record_security_event(
        &mut tx,
        id,
        "admin_notice",
        None,
        None,
        json!({"notice_id":notice_id,"title":title,"severity":severity}),
    )
    .await?;
    audit_event(
        &mut tx,
        admin.user,
        "notice:send",
        Some(id),
        reason,
        json!({"notice_id":notice_id,"title":title,"severity":severity}),
    )
    .await?;
    let push_queued = state
        .push
        .enqueue_transactional(
            &mut tx,
            id,
            &crate::push::PushEvent::admin_notice(notice_id, title, message, severity),
        )
        .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true,"notice_id":notice_id,
        "push_enabled":state.push.enabled(),"push_queued":push_queued})))
}

#[derive(Deserialize)]
pub struct AdminSecurityEventsQuery {
    cursor: Option<i64>,
    limit: Option<i64>,
}

pub async fn user_security_events(
    State(state): State<AppState>,
    _admin: Admin,
    Path(id): Path<Uuid>,
    Query(query): Query<AdminSecurityEventsQuery>,
) -> Result<Json<Value>, AppError> {
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL)")
            .bind(id)
            .fetch_one(&state.db)
            .await?;
    if !exists {
        return Err(denied());
    }
    let limit = cursor::limit(query.limit)?;
    let mut rows: Vec<Value> = sqlx::query_scalar(&format!(
        "SELECT jsonb_build_object('id',e.id::text,'event_type',e.event_type,
            'actor_device_id',e.actor_device_id,'subject_device_id',e.subject_device_id,
            'metadata',e.metadata,'created_at',to_char(e.created_at AT TIME ZONE 'UTC','{ISO_UTC}'))
         FROM security_events e WHERE e.user_id=$1 AND e.scope='account'
           AND ($2::bigint IS NULL OR e.id<$2)
         ORDER BY e.id DESC LIMIT $3"
    ))
    .bind(id)
    .bind(query.cursor)
    .bind(limit + 1)
    .fetch_all(&state.db)
    .await?;
    let has_more = rows.len() as i64 > limit;
    if has_more {
        rows.truncate(limit as usize);
    }
    let next_cursor = if has_more {
        rows.last()
            .and_then(|row| row["id"].as_str())
            .and_then(|value| value.parse::<i64>().ok())
    } else {
        None
    };
    Ok(Json(json!({"items":rows,"next_cursor":next_cursor})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DiagnosticsExportRequest {
    reason: String,
}

pub async fn export_user_diagnostics(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<DiagnosticsExportRequest>,
) -> Result<Json<Value>, AppError> {
    let reason = required_reason(&body.reason)?;
    let mut tx = state.db.begin().await?;
    let profile: Option<Value> = sqlx::query_scalar(
        "SELECT jsonb_build_object('user_id',u.id,'username',u.username,'display_name',u.display_name,
            'bio',u.bio,'has_avatar',u.avatar_key IS NOT NULL,'profile_version',u.profile_version,
            'avatar_version',u.avatar_version,'created_at',u.created_at,'updated_at',u.updated_at)
         FROM users u WHERE u.id=$1 AND u.deleted_at IS NULL",
    ).bind(id).fetch_optional(&mut *tx).await?;
    let profile = profile.ok_or_else(denied)?;
    let devices: Vec<Value> = sqlx::query_scalar(
        "SELECT jsonb_build_object('device_id',d.id,'platform',d.platform,'display_name',d.display_name,
            'app_version',d.app_version,'os_version',d.os_version,'created_at',d.created_at,
            'last_active_at',d.last_active_at,'revoked',d.revoked_at IS NOT NULL,
            'restricted',EXISTS(SELECT 1 FROM installation_suspensions s WHERE s.installation_id=d.installation_id
                AND (s.until_date IS NULL OR s.until_date>now())))
         FROM devices d WHERE d.user_id=$1 ORDER BY d.last_active_at DESC NULLS LAST,d.created_at DESC LIMIT 100",
    ).bind(id).fetch_all(&mut *tx).await?;
    let sessions: Value = sqlx::query_scalar(
        "SELECT jsonb_build_object('total',count(*),'active',count(*) FILTER(WHERE revoked_at IS NULL AND expires_at>now()),
            'revoked',count(*) FILTER(WHERE revoked_at IS NOT NULL),'last_used_at',max(last_used_at))
         FROM sessions WHERE user_id=$1",
    ).bind(id).fetch_one(&mut *tx).await?;
    let push: Value = sqlx::query_scalar(
        "SELECT jsonb_build_object('registered',count(pt.device_id),'active',count(pt.device_id) FILTER(WHERE pt.invalidated_at IS NULL),
            'last_success_at',max(pt.last_success_at),'last_error_codes',COALESCE(jsonb_agg(DISTINCT pt.last_error_code)
                FILTER(WHERE pt.last_error_code IS NOT NULL),'[]'::jsonb))
         FROM devices d LEFT JOIN push_tokens pt ON pt.device_id=d.id WHERE d.user_id=$1",
    ).bind(id).fetch_one(&mut *tx).await?;
    let moderation: Value = sqlx::query_scalar(
        "SELECT jsonb_build_object(
            'suspended',EXISTS(SELECT 1 FROM server_suspensions s WHERE s.user_id=$1 AND (s.until_date IS NULL OR s.until_date>now())),
            'read_only',EXISTS(SELECT 1 FROM admin_user_controls c WHERE c.user_id=$1 AND c.read_only_reason IS NOT NULL AND (c.read_only_until IS NULL OR c.read_only_until>now())),
            'reports_total',(SELECT count(*) FROM chat_reports r WHERE r.reported_user_id=$1),
            'reports_open',(SELECT count(*) FROM chat_reports r WHERE r.reported_user_id=$1 AND r.review_status IN ('open','in_progress')),
            'notes',(SELECT count(*) FROM admin_notes n WHERE n.subject_user_id=$1))",
    ).bind(id).fetch_one(&mut *tx).await?;
    let security_events: Vec<Value> = sqlx::query_scalar(
        "SELECT jsonb_build_object('id',id::text,'event_type',event_type,'actor_device_id',actor_device_id,
            'subject_device_id',subject_device_id,'metadata',metadata,'created_at',created_at)
         FROM security_events WHERE user_id=$1 AND scope='account' ORDER BY id DESC LIMIT 50",
    ).bind(id).fetch_all(&mut *tx).await?;
    let audit: Vec<Value> = sqlx::query_scalar(
        "SELECT jsonb_build_object('id',id::text,'action',action,'actor',actor,'reason',reason,
            'detail',detail,'created_at',created_at) FROM admin_audit WHERE target=$1 ORDER BY id DESC LIMIT 50",
    ).bind(id).fetch_all(&mut *tx).await?;
    audit_event(
        &mut tx,
        admin.user,
        "diagnostics:export",
        Some(id),
        reason,
        json!({"format":"json","security_events":security_events.len(),"audit_events":audit.len()}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({
        "schema":"coco-admin-user-diagnostics-v1","exported_at":Utc::now(),
        "privacy":"No message content, credentials, keys, tokens or recovery secrets are included.",
        "profile":profile,"moderation":moderation,"sessions":sessions,"push":push,
        "devices":devices,"security_events":security_events,"audit":audit
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProfileModerationRequest {
    #[serde(default)]
    remove_avatar: bool,
    #[serde(default)]
    clear_display_name: bool,
    #[serde(default)]
    clear_bio: bool,
    reason: String,
}

pub async fn moderate_profile(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<ProfileModerationRequest>,
) -> Result<Json<Value>, AppError> {
    let reason = required_reason(&body.reason)?;
    if !body.remove_avatar && !body.clear_display_name && !body.clear_bio {
        return Err(AppError::Validation(
            "Select at least one profile field.".into(),
        ));
    }
    ensure_moderatable(&state, &admin, id).await?;
    let mut tx = state.db.begin().await?;
    let current: (String, String, Option<String>, Option<String>) = sqlx::query_as(
        "SELECT username,display_name,bio,avatar_key FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE",
    ).bind(id).fetch_optional(&mut *tx).await?.ok_or_else(denied)?;
    let name_changed = body.clear_display_name && current.1 != current.0;
    let bio_changed = body.clear_bio && current.2.is_some();
    let avatar_changed = body.remove_avatar && current.3.is_some();
    if !name_changed && !bio_changed && !avatar_changed {
        return Err(AppError::Conflict(
            "Selected profile fields are already clear.".into(),
        ));
    }
    let display_name = if body.clear_display_name {
        current.0.clone()
    } else {
        current.1.clone()
    };
    let bio = if body.clear_bio {
        None
    } else {
        current.2.clone()
    };
    let avatar_key = if body.remove_avatar {
        None
    } else {
        current.3.clone()
    };
    sqlx::query(
        "UPDATE users SET display_name=$2,bio=$3,avatar_key=$4,
            avatar_url=CASE WHEN $5 THEN NULL ELSE avatar_url END,
            profile_version=profile_version+1,
            avatar_version=avatar_version+CASE WHEN $6 THEN 1 ELSE 0 END
         WHERE id=$1",
    )
    .bind(id)
    .bind(&display_name)
    .bind(&bio)
    .bind(&avatar_key)
    .bind(body.remove_avatar)
    .bind(avatar_changed)
    .execute(&mut *tx)
    .await?;
    audit_event(&mut tx, admin.user, "profile:moderate", Some(id), reason,
        json!({"avatar_removed":avatar_changed,"display_name_cleared":name_changed,"bio_cleared":bio_changed})).await?;
    tx.commit().await?;
    // A removed profile is unavailable immediately. Its object reservation is
    // released only by lease-aware cleanup after every PUT capability expires.
    Ok(Json(json!({"ok":true,"avatar_removed":avatar_changed,
        "display_name_cleared":name_changed,"bio_cleared":bio_changed})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReadOnlyRequest {
    enabled: bool,
    until: Option<DateTime<Utc>>,
    reason: String,
}

pub async fn set_read_only(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<ReadOnlyRequest>,
) -> Result<Json<Value>, AppError> {
    let reason = required_reason(&body.reason)?;
    ensure_moderatable(&state, &admin, id).await?;
    if body.enabled
        && body
            .until
            .is_some_and(|value| value <= Utc::now() || value > Utc::now() + Duration::days(365))
    {
        return Err(AppError::Validation(
            "Read-only expiry must be in the future and within one year.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    // Serialize activation with admitted content writes, not just HTTP extraction.
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(id)
        .execute(&mut *tx)
        .await?;

    if body.enabled {
        sqlx::query(
            "INSERT INTO admin_user_controls(user_id,read_only_until,read_only_reason,updated_by)
             VALUES($1,$2,$3,$4) ON CONFLICT(user_id) DO UPDATE SET
             read_only_until=excluded.read_only_until,read_only_reason=excluded.read_only_reason,
             updated_by=excluded.updated_by,updated_at=now()",
        )
        .bind(id)
        .bind(body.until)
        .bind(reason)
        .bind(admin.user)
        .execute(&mut *tx)
        .await?;
    } else {
        sqlx::query("DELETE FROM admin_user_controls WHERE user_id=$1")
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    audit_event(
        &mut tx,
        admin.user,
        if body.enabled {
            "read_only:enable"
        } else {
            "read_only:disable"
        },
        Some(id),
        reason,
        json!({"until":body.until}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(
        json!({"ok":true,"enabled":body.enabled,"until":body.until}),
    ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AdminDeviceLogoutRequest {
    reason: String,
}

/// Immediately signs out one selected app installation without placing a
/// persistent restriction on future logins from that installation.
pub async fn logout_device(
    State(state): State<AppState>,
    admin: Admin,
    Path(device_id): Path<Uuid>,
    Json(body): Json<AdminDeviceLogoutRequest>,
) -> Result<Json<Value>, AppError> {
    if body.reason.trim().is_empty() || body.reason.len() > 2000 {
        return Err(AppError::Validation(
            "A reason is required to sign out a device.".into(),
        ));
    }

    let mut tx = state.db.begin().await?;
    crate::auth::admission::lock(&mut tx, true).await?;
    let device: Option<(Uuid, Option<String>)> = sqlx::query_as(
        "SELECT user_id,display_name FROM devices WHERE id=$1 AND revoked_at IS NULL FOR UPDATE",
    )
    .bind(device_id)
    .fetch_optional(&mut *tx)
    .await?;
    let (user_id, display_name) = device.ok_or_else(denied)?;
    if user_id == admin.user {
        return Err(AppError::Conflict(
            "You cannot sign out the device used for this admin session.".into(),
        ));
    }
    let protected: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM server_administrators WHERE user_id=$1)")
            .bind(user_id)
            .fetch_one(&mut *tx)
            .await?;
    if protected {
        return Err(denied());
    }

    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE device_id=$1 AND revoked_at IS NULL")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id=$1")
        .bind(device_id)
        .execute(&mut *tx)
        .await?;
    audit_event(
        &mut tx,
        admin.user,
        "device_logout",
        Some(user_id),
        body.reason.trim(),
        json!({"device_id": device_id, "display_name": display_name}),
    )
    .await?;

    let active_calls: Vec<(Uuid, Uuid, Uuid)> = sqlx::query_as(
        "SELECT id,caller_user_id,callee_user_id FROM calls \
         WHERE accepted_device_id=$1 AND state IN ('accepted','connecting','connected')",
    )
    .bind(device_id)
    .fetch_all(&mut *tx)
    .await?;
    tx.commit().await?;

    if let Ok(payload) =
        serde_json::to_string(&crate::websocket::protocol::ServerMessage::SessionRevoked {
            reason: "device_revoked".into(),
        })
    {
        state.ws_registry.send_to_device(device_id, &payload).await;
    }
    for (call_id, caller, callee) in active_calls {
        crate::devices::handlers::end_call_accepted_by_revoked_device(
            &state, call_id, caller, callee,
        )
        .await;
    }
    Ok(Json(json!({"ok":true})))
}

#[derive(Deserialize, Default)]
pub struct AdminAvatarQuery {
    size: Option<u32>,
}

/// Streams a profile picture through the authenticated admin API so desktop
/// clients never need direct network access to private object storage.
pub async fn user_avatar(
    State(state): State<AppState>,
    _admin: Admin,
    Path(user_id): Path<Uuid>,
    Query(query): Query<AdminAvatarQuery>,
) -> Result<impl IntoResponse, AppError> {
    let key: Option<String> =
        sqlx::query_scalar("SELECT avatar_key FROM users WHERE id=$1 AND deleted_at IS NULL")
            .bind(user_id)
            .fetch_optional(&state.db)
            .await?
            .flatten();
    let key = key.ok_or_else(|| AppError::NotFound("No avatar set.".into()))?;
    let key = crate::users::avatar_sizes::rendition(&state, &key, user_id, query.size).await?;
    let bytes = state
        .s3
        .read_avatar(&key)
        .await
        .map_err(|_| AppError::Internal)?;
    Ok((
        axum::http::StatusCode::OK,
        [(header::CONTENT_TYPE, "application/octet-stream")],
        bytes,
    ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeleteUserRequest {
    confirm_username: String,
    reason: String,
}

pub async fn delete_user(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<DeleteUserRequest>,
) -> Result<axum::http::StatusCode, AppError> {
    if admin.role != "owner" || id == admin.user {
        return Err(denied());
    }
    if body.confirm_username.len() > 32 || body.reason.trim().is_empty() || body.reason.len() > 2000
    {
        return Err(AppError::Validation(
            "An exact username confirmation and deletion reason are required.".into(),
        ));
    }
    // Owner-only destructive action. The server owner explicitly requested
    // unrestricted bulk moderation, so there is no per-admin deletion quota.

    let mut tx = state.db.begin().await?;
    // Account deletion writes durable peer call tombstones. Match the global
    // history feed -> users lock order used by manual/automatic removal.
    sqlx::query("SELECT pg_advisory_xact_lock(6840210040)").execute(&mut *tx).await?;
    let username: String = sqlx::query_scalar(
        "SELECT username FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(denied)?;
    if body.confirm_username != username {
        return Err(AppError::Validation(
            "The confirmation username does not match.".into(),
        ));
    }
    let target_is_admin: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM server_administrators WHERE user_id=$1)")
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
    if target_is_admin {
        return Err(denied());
    }
    sqlx::query(
        "INSERT INTO admin_audit(actor,action,target,reason,detail) \
         VALUES($1,'account_delete',$2,$3,jsonb_build_object('username',$4))",
    )
    .bind(admin.user)
    .bind(id)
    .bind(body.reason.trim())
    .bind(&username)
    .execute(&mut *tx)
    .await?;
    let effects = crate::users::deletion::delete_account_data(&state, &mut tx, id).await?;
    tx.commit().await?;
    crate::users::deletion::notify_account_deleted(&state, effects).await;
    Ok(axum::http::StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct PageQuery {
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    limit: Option<i64>,
}

/// User-scoped moderation timeline.
///
/// No new event table is introduced: `admin_audit` already records every
/// account/device/note action with the moderated account as `target`, and the
/// report lifecycle lives in `chat_reports` joined to its `report:*` audit rows.
/// The database maps raw actions to stable semantic `kind` values so both
/// clients render the very same events.
pub async fn user_timeline(
    State(state): State<AppState>,
    _admin: Admin,
    Path(id): Path<Uuid>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Value>, AppError> {
    let limit = cursor::limit(query.limit)?;
    let print = cursor::fingerprint(&["timeline", &id.to_string()]);
    let after = cursor::decode_timestamp_key(query.cursor.as_deref(), &print)?;
    let sql = format!(
        "WITH scoped AS (
            SELECT a.created_at,a.actor,a.reason,a.action,a.id,
                   (a.detail || jsonb_build_object('report_id',r.id)) AS detail
              FROM admin_audit a JOIN chat_reports r ON r.id=a.target
             WHERE r.reported_user_id=$1 AND a.action LIKE 'report:%'
            UNION ALL
            SELECT a.created_at,a.actor,a.reason,a.action,a.id,a.detail
              FROM admin_audit a WHERE a.target=$1
         ), events AS (
            SELECT s.created_at,s.actor,s.reason,s.detail,
                   CASE s.action
                       WHEN 'suspend' THEN 'account_suspended'
                       WHEN 'suspend_with_installations' THEN 'account_suspended'
                       WHEN 'unsuspend' THEN 'account_restored'
                       WHEN 'unsuspend_with_installations' THEN 'account_restored'
                       WHEN 'device_suspend' THEN 'device_suspended'
                       WHEN 'device_unsuspend' THEN 'device_restored'
                       WHEN 'device_logout' THEN 'device_session_ended'
                       WHEN 'account_delete' THEN 'account_deleted'
                       WHEN 'note:add' THEN 'note_added'
                       WHEN 'notice:send' THEN 'notice_sent'
                       WHEN 'diagnostics:export' THEN 'diagnostics_exported'
                       WHEN 'profile:moderate' THEN 'profile_moderated'
                       WHEN 'read_only:enable' THEN 'read_only_enabled'
                       WHEN 'read_only:disable' THEN 'read_only_disabled'
                       WHEN 'report:open' THEN 'report_open'
                       WHEN 'report:in_progress' THEN 'report_started'
                       WHEN 'report:resolved' THEN 'report_resolved'
                       WHEN 'report:dismissed' THEN 'report_dismissed'
                       ELSE 'other' END AS kind,
                   'audit:' || s.id::text AS ref
              FROM scoped s
            UNION ALL
            SELECT r.created_at,NULL::uuid,r.reason::text,jsonb_build_object('report_id',r.id),
                   'report_received','report:' || r.id::text
              FROM chat_reports r WHERE r.reported_user_id=$1
         )
         SELECT jsonb_build_object('kind',e.kind,
            'occurred_at',to_char(e.created_at AT TIME ZONE 'UTC','{ISO_UTC}'),
            'actor',e.actor,'reason',e.reason,'detail',e.detail,'ref',e.ref)
           FROM events e
          WHERE ($2::timestamptz IS NULL OR (e.created_at,e.ref)<($2::timestamptz,$3::text))
          ORDER BY e.created_at DESC,e.ref DESC LIMIT $4"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(id)
        .bind(after.as_ref().map(|(stamp, _)| *stamp))
        .bind(after.map(|(_, reference)| reference))
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(
        cursor::finish(rows, limit, &print, |row| {
            Some((
                row["occurred_at"].as_str()?.to_string(),
                row["ref"].as_str()?.to_string(),
            ))
        })
        .into_json(),
    ))
}

/// Internal notes are visible only through this admin-only route and are never
/// part of any user-facing response.
pub async fn user_notes(
    State(state): State<AppState>,
    _admin: Admin,
    Path(id): Path<Uuid>,
    Query(query): Query<PageQuery>,
) -> Result<Json<Value>, AppError> {
    let limit = cursor::limit(query.limit)?;
    let print = cursor::fingerprint(&["notes", &id.to_string()]);
    let after = cursor::decode_timestamp_i64(query.cursor.as_deref(), &print)?;
    let sql = format!(
        "SELECT jsonb_build_object('id',n.id::text,'author_user_id',n.author_user_id,
            'body',n.body,
            'created_at',to_char(n.created_at AT TIME ZONE 'UTC','{ISO_UTC}'))
         FROM admin_notes n
         WHERE n.subject_user_id=$1
           AND ($2::timestamptz IS NULL OR (n.created_at,n.id)<($2::timestamptz,$3::bigint))
         ORDER BY n.created_at DESC,n.id DESC LIMIT $4"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(id)
        .bind(after.map(|(stamp, _)| stamp))
        .bind(after.map(|(_, note)| note))
        .bind(limit + 1)
        .fetch_all(&state.db)
        .await?;
    Ok(Json(
        cursor::finish(rows, limit, &print, |row| {
            Some((
                row["created_at"].as_str()?.to_string(),
                row["id"].as_str()?.to_string(),
            ))
        })
        .into_json(),
    ))
}

#[derive(Deserialize)]
pub struct NewNote {
    body: String,
}

/// Notes are append-only. There is deliberately no edit or delete path: a
/// moderation record that can be rewritten after the fact is worth little, and
/// the audit requirement would only grow. Every addition is audited.
pub async fn add_note(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<NewNote>,
) -> Result<Json<Value>, AppError> {
    let text = body.body.trim();
    let length = text.chars().count();
    if !(1..=2000).contains(&length) {
        return Err(AppError::Validation(
            "A note between 1 and 2000 characters is required.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    let exists: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
    if exists.is_none() {
        return Err(denied());
    }
    let note: i64 = sqlx::query_scalar(
        "INSERT INTO admin_notes(subject_user_id,author_user_id,body) VALUES($1,$2,$3) RETURNING id",
    )
    .bind(id)
    .bind(admin.user)
    .bind(text)
    .fetch_one(&mut *tx)
    .await?;
    audit_event(
        &mut tx,
        admin.user,
        "note:add",
        Some(id),
        "Internal administrator note added",
        json!({"note_id": note}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"id": note.to_string()})))
}

#[derive(Deserialize)]
pub struct InstallationSuspension {
    blocked: bool,
    reason: String,
    until: Option<DateTime<Utc>>,
}

/// Restricts or restores a single installation (one physical app install).
///
/// This is narrower than the account-level `suspend`: the account keeps working
/// on its other devices. It reuses the existing `installation_suspensions`
/// table, which `auth::admission::check_installation` already enforces on every
/// login/refresh, so revocation here is server-authoritative.
///
/// Scope note: only this installation's sessions, push tokens and admin
/// sessions are torn down. Calls are intentionally not ended, because
/// `call_participants_active` is keyed by account rather than by device and the
/// account itself is not being suspended.
pub async fn suspend_installation(
    State(state): State<AppState>,
    admin: Admin,
    Path(installation_id): Path<Uuid>,
    Json(body): Json<InstallationSuspension>,
) -> Result<Json<Value>, AppError> {
    if body.reason.trim().is_empty()
        || body.reason.len() > 2000
        || body.until.is_some_and(|value| value <= Utc::now())
    {
        return Err(AppError::Validation(
            "A reason and valid expiry are required.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    crate::auth::admission::lock(&mut tx, true).await?;

    // An installation id can be reused by different accounts over time, so the
    // owning set is not necessarily a single user. Restrictions previously
    // created for this installation are also resolved so a restore can find an
    // account even after the device row is gone.
    let owners: Vec<Uuid> = sqlx::query_scalar(
        "SELECT DISTINCT user_id FROM devices WHERE installation_id=$1 ORDER BY user_id",
    )
    .bind(installation_id)
    .fetch_all(&mut *tx)
    .await?;
    let prior: Vec<Uuid> = sqlx::query_scalar(
        "SELECT DISTINCT source_user_id FROM installation_suspensions WHERE installation_id=$1 ORDER BY source_user_id",
    )
    .bind(installation_id)
    .fetch_all(&mut *tx)
    .await?;
    let target = owners
        .first()
        .copied()
        .or_else(|| prior.first().copied())
        .ok_or_else(denied)?;

    let mut affected_devices: Vec<Uuid> = vec![];
    if body.blocked {
        if owners.is_empty() {
            return Err(denied());
        }
        // A device that belongs to an administrator is protected, exactly as in
        // the account-level path.
        let protected: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM devices d JOIN server_administrators a ON a.user_id=d.user_id WHERE d.installation_id=$1)",
        )
        .bind(installation_id)
        .fetch_one(&mut *tx)
        .await?;
        if protected {
            return Err(AppError::Conflict(
                "This installation belongs to an administrator. Restrict the account instead."
                    .into(),
            ));
        }
        for owner in &owners {
            sqlx::query("INSERT INTO installation_suspensions(installation_id,source_user_id,until_date,reason,actor) VALUES($1,$2,$3,$4,$5) ON CONFLICT(installation_id,source_user_id) DO UPDATE SET until_date=excluded.until_date,reason=excluded.reason,actor=excluded.actor,created_at=now()")
                .bind(installation_id)
                .bind(owner)
                .bind(body.until)
                .bind(body.reason.trim())
                .bind(admin.user)
                .execute(&mut *tx)
                .await?;
        }
        affected_devices = sqlx::query_scalar("SELECT id FROM devices WHERE installation_id=$1")
            .bind(installation_id)
            .fetch_all(&mut *tx)
            .await?;
        // Row-lock the affected accounts so a concurrent account-level
        // suspension cannot interleave with this device change.
        let _locked: Vec<Uuid> = sqlx::query_scalar(
            "SELECT id FROM users WHERE id IN (SELECT user_id FROM devices WHERE id=ANY($1)) ORDER BY id FOR UPDATE",
        )
        .bind(&affected_devices)
        .fetch_all(&mut *tx)
        .await?;
        sqlx::query(
            "UPDATE sessions SET revoked_at=now() WHERE revoked_at IS NULL AND device_id=ANY($1)",
        )
        .bind(&affected_devices)
        .execute(&mut *tx)
        .await?;
        sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id=ANY($1)")
            .bind(&affected_devices)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM admin_sessions WHERE auth_session IN (SELECT id FROM sessions WHERE device_id=ANY($1))")
            .bind(&affected_devices)
            .execute(&mut *tx)
            .await?;
    } else {
        // A device-level restore lifts the installation restriction as a whole.
        // Any account-level suspension stays untouched.
        sqlx::query("DELETE FROM installation_suspensions WHERE installation_id=$1")
            .bind(installation_id)
            .execute(&mut *tx)
            .await?;
    }
    audit_event(
        &mut tx,
        admin.user,
        if body.blocked {
            "device_suspend"
        } else {
            "device_unsuspend"
        },
        Some(target),
        body.reason.trim(),
        json!({"installation_id": installation_id, "installations": owners.len()}),
    )
    .await?;
    tx.commit().await?;

    if body.blocked {
        if let Ok(payload) =
            serde_json::to_string(&crate::websocket::protocol::ServerMessage::SessionRevoked {
                reason: "device_revoked".into(),
            })
        {
            for device in affected_devices {
                state.ws_registry.send_to_device(device, &payload).await;
            }
        }
    }
    Ok(Json(json!({"ok":true})))
}
#[derive(Deserialize)]
pub struct Suspension {
    blocked: bool,
    #[serde(default)]
    include_installations: bool,
    reason: String,
    until: Option<DateTime<Utc>>,
}
pub async fn suspend(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
    Json(body): Json<Suspension>,
) -> Result<Json<Value>, AppError> {
    if id == admin.user || role(&state.db, id).await?.is_some() {
        return Err(denied());
    }
    if body.reason.trim().is_empty()
        || body.reason.len() > 2000
        || body.until.is_some_and(|v| v <= Utc::now())
    {
        return Err(AppError::Validation(
            "A reason and valid expiry are required.".into(),
        ));
    }
    let mut tx = state.db.begin().await?;
    crate::auth::admission::lock(&mut tx, true).await?;
    let exists: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
    if exists.is_none() && body.blocked {
        return Err(denied());
    }
    let installations: Vec<Uuid> = if body.include_installations && body.blocked {
        sqlx::query_scalar("SELECT DISTINCT installation_id FROM devices WHERE user_id=$1")
            .bind(id)
            .fetch_all(&mut *tx)
            .await?
    } else {
        vec![]
    };
    if !installations.is_empty() {
        let protected:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM devices d JOIN server_administrators a ON a.user_id=d.user_id WHERE d.installation_id=ANY($1))")
            .bind(&installations).fetch_one(&mut *tx).await?;
        if protected {
            return Err(AppError::Conflict(
                "A known installation is shared with an administrator. Restrict only the account."
                    .into(),
            ));
        }
    }
    let affected_devices: Vec<Uuid> = if body.blocked {
        sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 OR installation_id=ANY($2)")
            .bind(id)
            .bind(&installations)
            .fetch_all(&mut *tx)
            .await?
    } else {
        vec![]
    };
    let affected_users:Vec<Uuid> = sqlx::query_scalar("SELECT id FROM users WHERE id=$1 OR id IN (SELECT user_id FROM devices WHERE id=ANY($2)) ORDER BY id FOR UPDATE")
        .bind(id).bind(&affected_devices).fetch_all(&mut *tx).await?;
    if body.blocked && !affected_users.contains(&id) {
        return Err(denied());
    }
    let mut ended_calls: Vec<(Uuid, Uuid, Uuid)> = vec![];
    if body.blocked {
        sqlx::query("INSERT INTO server_suspensions(user_id,until_date,reason) VALUES($1,$2,$3) ON CONFLICT(user_id) DO UPDATE SET until_date=excluded.until_date,reason=excluded.reason")
            .bind(id).bind(body.until).bind(&body.reason).execute(&mut *tx).await?;
        for installation in &installations {
            sqlx::query("INSERT INTO installation_suspensions(installation_id,source_user_id,until_date,reason,actor) VALUES($1,$2,$3,$4,$5) ON CONFLICT(installation_id,source_user_id) DO UPDATE SET until_date=excluded.until_date,reason=excluded.reason,actor=excluded.actor,created_at=now()")
                .bind(installation).bind(id).bind(body.until).bind(&body.reason).bind(admin.user).execute(&mut *tx).await?;
        }
        sqlx::query("UPDATE sessions SET revoked_at=now() WHERE revoked_at IS NULL AND (user_id=$1 OR device_id IN (SELECT id FROM devices WHERE installation_id=ANY($2)))")
            .bind(id).bind(&installations).execute(&mut *tx).await?;
        sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id IN (SELECT id FROM devices WHERE user_id=$1 OR installation_id=ANY($2))")
            .bind(id).bind(&installations).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1 OR auth_session IN (SELECT id FROM sessions WHERE revoked_at IS NOT NULL)")
            .bind(id).execute(&mut *tx).await?;
        ended_calls=sqlx::query_as("UPDATE calls SET state='ended',end_reason='device_revoked',ended_at=now() WHERE (caller_user_id=ANY($1) OR callee_user_id=ANY($1)) AND state<>'ended' RETURNING id,caller_user_id,callee_user_id")
            .bind(&affected_users).fetch_all(&mut *tx).await?;
        for (call, _, _) in &ended_calls {
            sqlx::query("DELETE FROM call_participants_active WHERE call_id=$1")
                .bind(call)
                .execute(&mut *tx)
                .await?;
        }
    } else {
        sqlx::query("DELETE FROM server_suspensions WHERE user_id=$1")
            .bind(id)
            .execute(&mut *tx)
            .await?;
        if body.include_installations {
            // Only lift restrictions originating from this account. A shared
            // installation may still have an independent restriction.
            sqlx::query("DELETE FROM installation_suspensions WHERE source_user_id=$1")
                .bind(id)
                .execute(&mut *tx)
                .await?;
        }
    }
    sqlx::query("INSERT INTO admin_audit(actor,action,target,reason) VALUES($1,$2,$3,$4)")
        .bind(admin.user)
        .bind(match (body.blocked, body.include_installations) {
            (true, true) => "suspend_with_installations",
            (false, true) => "unsuspend_with_installations",
            (true, false) => "suspend",
            (false, false) => "unsuspend",
        })
        .bind(id)
        .bind(body.reason)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    if let Ok(payload) =
        serde_json::to_string(&crate::websocket::protocol::ServerMessage::SessionRevoked {
            reason: "device_revoked".into(),
        })
    {
        for device in affected_devices {
            state.ws_registry.send_to_device(device, &payload).await;
        }
    }
    for (call, caller, callee) in ended_calls {
        let event = crate::websocket::protocol::ServerMessage::CallEnded {
            call_id: call,
            reason: "device_revoked".into(),
        };
        crate::websocket::notify_user(&state, caller, &event).await;
        crate::websocket::notify_user(&state, callee, &event).await;
        state
            .push
            .enqueue_for_users(
                &state.db,
                &[caller, callee],
                None,
                &crate::push::PushEvent::call_ended(call, "device_revoked"),
            )
            .await;
    }
    Ok(Json(json!({"ok":true})))
}
