
use axum::extract::State;
use axum::http::StatusCode;
use axum::Json;
use chrono::{DateTime, Duration, Utc};
use uuid::Uuid;

use crate::net::ClientIp;
use crate::auth::middleware::AuthUser;
use crate::auth::models::{
    AuthResponse, DevicePlatform, LoginRequest, RefreshRequest, RefreshResponse, RegisterRequest, UserPublic,
};
use crate::auth::{jwt, password, rate_limit, refresh_token};
use crate::error::AppError;
use crate::state::AppState;
use crate::validation::{validate_display_name, validate_username};

const REGISTER_LIMIT: u64 = 5;
const LOGIN_LIMIT: u64 = 10;
const RATE_WINDOW_SECONDS: u64 = 60;

async fn login_protection(state: &AppState) -> Result<(bool, u64, u64), AppError> {
    let (enabled, ip, account): (bool, i64, i64) = sqlx::query_as(
        "SELECT automatic_login_protection,login_ip_limit,login_account_limit FROM server_policy WHERE id=true",
    ).fetch_one(&state.db).await?;
    Ok((enabled, ip.clamp(1, LOGIN_LIMIT as i64) as u64, account.clamp(1, 20) as u64))
}

/// Backfill presentation metadata for installations upgraded while signed in.
/// Follow refresh parents so token rotation never moves the history boundary.
pub async fn history_context(
    State(state): State<AppState>, auth: AuthUser,
) -> Result<Json<HistoryContext>, AppError> {
    let before_date = super::history::visible_since(&state.db, &auth).await?;
    let before_sequence: i64 = sqlx::query_scalar(
        "SELECT COALESCE(MAX(m.sequence), 0) FROM messages m \
         JOIN chat_members cm ON cm.chat_id = m.chat_id \
         WHERE cm.user_id = $1 AND cm.left_at IS NULL AND m.created_at < $2",
    ).bind(auth.user_id).bind(before_date).fetch_one(&state.db).await?;
    Ok(Json(HistoryContext { before_sequence, before_date }))
}

#[derive(serde::Serialize)]
pub struct HistoryContext {
    before_sequence: i64,
    before_date: DateTime<Utc>,
}

fn validate_password(password: &str) -> Result<(), AppError> {
    let len = password.chars().count();
    if !(8..=256).contains(&len) {
        return Err(AppError::Validation("Password must be 8-256 characters.".into()));
    }
    Ok(())
}

pub async fn register(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    Json(req): Json<RegisterRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    rate_limit::enforce(&state, "register", addr, REGISTER_LIMIT, RATE_WINDOW_SECONDS).await?;
    let registration_open: bool = sqlx::query_scalar("SELECT registration_open FROM server_policy WHERE id=true")
        .fetch_one(&state.db).await?;
    if !registration_open {
        return Err(AppError::Conflict("Registration is temporarily closed by the server operator.".into()));
    }

    validate_username(&req.username)?;
    validate_password(&req.password)?;
    let display_name = validate_display_name(&req.display_name)?.to_string();

    let supplied_password = req.password.clone();
    let password_hash = password::run(move || password::hash_password(&supplied_password)).await?;

    let mut tx = state.db.begin().await?;
    super::admission::lock(&mut tx, false).await?;
    super::admission::check_installation(&mut tx, req.installation_id).await?;
    let verified_installation = super::installations::verify(&mut tx, req.installation_credential.as_deref()).await?;

    let inserted: Option<(Uuid,)> = sqlx::query_as(
        "INSERT INTO users (username, display_name, password_hash) \
         VALUES ($1, $2, $3) \
         ON CONFLICT (username_normalized) DO NOTHING \
         RETURNING id",
    )
    .bind(&req.username)
    .bind(&display_name)
    .bind(&password_hash)
    .fetch_optional(&mut *tx)
    .await?;

    let Some((user_id,)) = inserted else {
        return Err(AppError::UsernameTaken);
    };

    let (device_id, _new_device_event) = upsert_device(
        &mut tx,
        user_id,
        req.installation_id,
        req.device_platform,
        req.device_name.as_deref(),
        req.os_version.as_deref(),
        req.app_version.as_deref(),
    )
    .await?;
    super::installations::bind_device(&mut tx, user_id, device_id, verified_installation).await?;
    let (access, refresh, _session_id) = create_session(&state, &mut tx, user_id, device_id).await?;

    tx.commit().await?;

    tracing::info!(%user_id, %device_id, installation_id = %req.installation_id, "user registered");

    Ok(Json(AuthResponse {
        history_before_sequence: 0,
        access_token: access.token,
        access_token_expires_at: access.expires_at,
        refresh_token: refresh.token,
        device_id,
        user: UserPublic { id: user_id, username: req.username, display_name },
    }))
}

pub async fn login(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    Json(req): Json<LoginRequest>,
) -> Result<Json<AuthResponse>, AppError> {
    rate_limit::enforce(&state, "login", addr, LOGIN_LIMIT, RATE_WINDOW_SECONDS).await?;
    let (automatic_protection, login_ip_limit, login_account_limit) = login_protection(&state).await?;
    if automatic_protection {
        rate_limit::enforce(&state, "login-policy", addr, login_ip_limit, RATE_WINDOW_SECONDS).await?;
    }
    // Use the database's normalization, identical to username lookup, including
    // alternate case. Hash the bounded Redis key rather than storing usernames.
    use sha2::{Digest, Sha256};
    let normalized: String = sqlx::query_scalar("SELECT lower($1::text)")
        .bind(&req.username).fetch_one(&state.db).await?;
    let bucket = format!("login-account:{:x}", Sha256::digest(normalized.as_bytes()));
    rate_limit::enforce(&state, &bucket, std::net::Ipv4Addr::UNSPECIFIED.into(),
        if automatic_protection { login_account_limit } else { 20 }, 300).await?;


    #[derive(sqlx::FromRow)]
    struct UserRow {
        id: Uuid,
        username: String,
        display_name: String,
        password_hash: String,
    }

    let user: Option<UserRow> = sqlx::query_as(
        "SELECT id, username, display_name, password_hash FROM users \
         WHERE username_normalized = lower($1) AND deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM server_suspensions b WHERE b.user_id=users.id AND (b.until_date IS NULL OR b.until_date>now()))",
    )
    .bind(&req.username)
    .fetch_optional(&state.db)
    .await?;

    let supplied_password = req.password.clone();
    let stored_hash = user.as_ref().map(|u| u.password_hash.clone());
    let verified = password::run(move || {
        let hash = stored_hash.as_deref().unwrap_or_else(|| password::dummy_hash());
        let matches = password::verify_password(&supplied_password, hash);
        Ok(stored_hash.is_some() && matches)
    }).await?;

    let Some(user) = user.filter(|_| verified) else {
        return Err(AppError::InvalidCredentials);
    };

    let mut tx = state.db.begin().await?;
    super::admission::lock(&mut tx, false).await?;
    super::admission::check_installation(&mut tx, req.installation_id).await?;
    let verified_installation = super::installations::verify(&mut tx, req.installation_credential.as_deref()).await?;
    let current_hash: Option<String> = sqlx::query_scalar(
        "SELECT password_hash FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE",
    ).bind(user.id).fetch_optional(&mut *tx).await?;
    if current_hash.as_deref() != Some(user.password_hash.as_str()) {
        return Err(AppError::InvalidCredentials);
    }
    let suspended:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM server_suspensions WHERE user_id=$1 AND (until_date IS NULL OR until_date>now()))")
        .bind(user.id).fetch_one(&mut *tx).await?;
    if suspended {return Err(AppError::InvalidCredentials)}
    let history_before_sequence: i64 = sqlx::query_scalar(
        "SELECT COALESCE(MAX(m.sequence), 0) FROM messages m \
         JOIN chat_members cm ON cm.chat_id = m.chat_id \
         WHERE cm.user_id = $1 AND cm.left_at IS NULL",
    ).bind(user.id).fetch_one(&mut *tx).await?;
    let (device_id, new_device_event) = upsert_device(
        &mut tx,
        user.id,
        req.installation_id,
        req.device_platform,
        req.device_name.as_deref(),
        req.os_version.as_deref(),
        req.app_version.as_deref(),
    )
    .await?;
    super::installations::bind_device(&mut tx, user.id, device_id, verified_installation).await?;
    let (access, refresh, _session_id) = create_session(&state, &mut tx, user.id, device_id).await?;
    tx.commit().await?;

    // Этап L.18: wake the user's other devices about this new device.
    if let Some(event_id) = new_device_event {
        crate::devices::handlers::enqueue_new_device_alert(
            &state,
            user.id,
            device_id,
            req.device_name.as_deref(),
            req.device_platform,
            event_id,
        )
        .await;
    }

    tracing::info!(user_id = %user.id, %device_id, installation_id = %req.installation_id, "user logged in");

    Ok(Json(AuthResponse {
        history_before_sequence,
        access_token: access.token,
        access_token_expires_at: access.expires_at,
        refresh_token: refresh.token,
        device_id,
        user: UserPublic { id: user.id, username: user.username, display_name: user.display_name },
    }))
}

pub async fn refresh(
    State(state): State<AppState>,
    Json(req): Json<RefreshRequest>,
) -> Result<Json<RefreshResponse>, AppError> {
    let session_id = refresh_token::parse_session_id(&req.refresh_token).ok_or(AppError::InvalidRefreshToken)?;

    #[derive(sqlx::FromRow)]
    struct SessionRow {
        id: Uuid,
        user_id: Uuid,
        device_id: Uuid,
        refresh_token_hash: String,
        expires_at: DateTime<Utc>,
        revoked_at: Option<DateTime<Utc>>,
        replaced_by: Option<Uuid>,
        refresh_request_hash: Option<String>,
    }

    let mut tx = state.db.begin().await?;
    super::admission::lock(&mut tx, false).await?;

    // Account lock precedes session locks, shared with password recovery/login.
    let owner: Option<Uuid> = sqlx::query_scalar("SELECT user_id FROM sessions WHERE id=$1")
        .bind(session_id).fetch_optional(&mut *tx).await?;
    let owner = owner.ok_or(AppError::InvalidRefreshToken)?;
    let active_owner: Option<Uuid> = sqlx::query_scalar("SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE")
        .bind(owner).fetch_optional(&mut *tx).await?;
    if active_owner.is_none() { return Err(AppError::InvalidRefreshToken); }
    // Row lock: two concurrent refreshes of the same token must be
    // serialized, otherwise both could "succeed" and desynchronize rotation.
    let session: Option<SessionRow> = sqlx::query_as(
        "SELECT id, user_id, device_id, refresh_token_hash, expires_at, revoked_at, replaced_by, refresh_request_hash \
         FROM sessions WHERE id = $1 FOR UPDATE",
    )
    .bind(session_id)
    .fetch_optional(&mut *tx)
    .await?;

    let Some(session) = session else {
        return Err(AppError::InvalidRefreshToken);
    };

    let presented_hash = refresh_token::hash_token(&req.refresh_token);
    if !refresh_token::constant_time_eq(session.refresh_token_hash.as_bytes(), presented_hash.as_bytes()) {
        return Err(AppError::InvalidRefreshToken);
    }

    let allowed:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM devices d WHERE d.id=$1 AND d.revoked_at IS NULL AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=d.installation_id AND (b.until_date IS NULL OR b.until_date>now())) AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=$2 AND (b.until_date IS NULL OR b.until_date>now())))")
        .bind(session.device_id).bind(session.user_id).fetch_one(&mut *tx).await?;
    if !allowed {return Err(AppError::InvalidRefreshToken)}
    if session.revoked_at.is_some() {
        if let (Some(request_id), Some(expected), Some(child_id)) =
            (req.request_id, session.refresh_request_hash.as_ref(), session.replaced_by)
        {
            let supplied = refresh_token::hash_token(&request_id.to_string());
            if refresh_token::constant_time_eq(expected.as_bytes(), supplied.as_bytes()) {
                if session.expires_at < Utc::now() { return Err(AppError::InvalidRefreshToken); }
                let child: Option<(String,)> = sqlx::query_as(
                    "SELECT refresh_token_hash FROM sessions WHERE id = $1 AND user_id = $2 AND device_id = $3 \
                     AND revoked_at IS NULL AND expires_at > now() FOR UPDATE",
                ).bind(child_id).bind(session.user_id).bind(session.device_id).fetch_optional(&mut *tx).await?;
                if let Some((hash,)) = child {
                    let refresh = refresh_token::for_retry(child_id, &req.refresh_token, request_id, &state.config.jwt_secret);
                    if refresh_token::constant_time_eq(hash.as_bytes(), refresh.hash.as_bytes()) {
                        let access = jwt::issue_access_token(&state.config, session.user_id, session.device_id, child_id)?;
                        tx.commit().await?;
                        return Ok(Json(RefreshResponse { access_token: access.token,
                            access_token_expires_at: access.expires_at, refresh_token: refresh.token }));
                    }
                }
                // A consumed/revoked replacement cannot be revived by replaying its parent.
                return Err(AppError::InvalidRefreshToken);
            }
        }
        // Reuse of a token that was already rotated away: assume the token
        // was stolen and kill the whole active session family for this
        // device, forcing re-authentication.
        sqlx::query(
            "UPDATE sessions SET revoked_at = now() \
             WHERE user_id = $1 AND device_id = $2 AND revoked_at IS NULL",
        )
        .bind(session.user_id)
        .bind(session.device_id)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        tracing::warn!(
            session_id = %session.id,
            user_id = %session.user_id,
            "refresh token reuse detected; session family revoked"
        );
        return Err(AppError::InvalidRefreshToken);
    }

    if session.expires_at < Utc::now() {
        return Err(AppError::InvalidRefreshToken);
    }

    let new_session_id = Uuid::new_v4();
    let new_refresh = match req.request_id {
        Some(request_id) => refresh_token::for_retry(new_session_id, &req.refresh_token, request_id, &state.config.jwt_secret),
        None => refresh_token::generate(new_session_id),
    };
    let new_expires_at = Utc::now() + Duration::seconds(state.config.refresh_token_ttl_seconds);

    sqlx::query(
        "INSERT INTO sessions (id, user_id, device_id, refresh_token_hash, expires_at, last_used_at) \
         VALUES ($1, $2, $3, $4, $5, now())",
    )
    .bind(new_session_id)
    .bind(session.user_id)
    .bind(session.device_id)
    .bind(&new_refresh.hash)
    .bind(new_expires_at)
    .execute(&mut *tx)
    .await?;

    sqlx::query("UPDATE sessions SET revoked_at = now(), replaced_by = $2, refresh_request_hash = $3 WHERE id = $1")
        .bind(session.id)
        .bind(new_session_id)
        .bind(req.request_id.map(|id| refresh_token::hash_token(&id.to_string())))
        .execute(&mut *tx)
        .await?;

    let access = jwt::issue_access_token(&state.config, session.user_id, session.device_id, new_session_id)?;

    tx.commit().await?;

    // Этап L.3: token refresh is activity - coalesced to avoid per-refresh writes.
    crate::devices::handlers::touch_last_active(&state.db, session.device_id).await;

    Ok(Json(RefreshResponse {
        access_token: access.token,
        access_token_expires_at: access.expires_at,
        refresh_token: new_refresh.token,
    }))
}

/// An offline logout carries only its original refresh proof. Revoke descendants
/// of that session, never a later independent login on the same device.
pub async fn logout_pending(
    State(state): State<AppState>, ClientIp(addr): ClientIp,
    Json(req): Json<RefreshRequest>,
) -> Result<StatusCode, AppError> {
    rate_limit::enforce(&state, "logout-pending", addr, 60, RATE_WINDOW_SECONDS).await?;
    if req.refresh_token.len() > 256 { return Err(AppError::InvalidRefreshToken); }
    let id = refresh_token::parse_session_id(&req.refresh_token).ok_or(AppError::InvalidRefreshToken)?;
    let mut tx = state.db.begin().await?;
    let owner: Option<Uuid> = sqlx::query_scalar("SELECT user_id FROM sessions WHERE id=$1")
        .bind(id).fetch_optional(&mut *tx).await?;
    let Some(owner) = owner else { return Ok(StatusCode::NO_CONTENT); };
    // Same lock order as refresh and login; prevents a rotation escaping revocation.
    let _: Option<Uuid> = sqlx::query_scalar("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(owner).fetch_optional(&mut *tx).await?;
    let row: Option<(String, Uuid)> = sqlx::query_as("SELECT refresh_token_hash, device_id FROM sessions WHERE id=$1 FOR UPDATE")
        .bind(id).fetch_optional(&mut *tx).await?;
    let Some((hash, device)) = row else { return Ok(StatusCode::NO_CONTENT); };
    if !refresh_token::constant_time_eq(hash.as_bytes(), refresh_token::hash_token(&req.refresh_token).as_bytes()) {
        return Err(AppError::InvalidRefreshToken);
    }
    sqlx::query("WITH RECURSIVE family AS (
        SELECT id,replaced_by FROM sessions WHERE id=$1
        UNION SELECT s.id,s.replaced_by FROM sessions s JOIN family f ON s.id=f.replaced_by
        WHERE s.user_id=$2 AND s.device_id=$3)
        UPDATE sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE id IN (SELECT id FROM family)")
        .bind(id).bind(owner).bind(device).execute(&mut *tx).await?;
    // A newer independent login owns its push registration; do not invalidate it.
    sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id=$1
        AND NOT EXISTS(SELECT 1 FROM sessions WHERE device_id=$1 AND revoked_at IS NULL AND expires_at>now())")
        .bind(device).execute(&mut *tx).await?;
    tx.commit().await?;
    crate::conferences::after_security_change(&state).await;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn logout(State(state): State<AppState>, auth: AuthUser) -> Result<StatusCode, AppError> {
    let mut tx = state.db.begin().await?;
    crate::auth::middleware::lock_push_session(&mut tx, &auth).await?;
    sqlx::query("UPDATE sessions SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL")
        .bind(auth.session_id)
        .execute(&mut *tx)
        .await?;

    // K.2: logout must sever the push ownership mapping for this device so the
    // old account can never receive private call/message notifications again.
    sqlx::query("UPDATE push_tokens SET invalidated_at = now() WHERE device_id = $1 \
        AND NOT EXISTS(SELECT 1 FROM sessions WHERE device_id=$1 AND revoked_at IS NULL AND expires_at>now())")
        .bind(auth.device_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    tracing::info!(session_id = %auth.session_id, user_id = %auth.user_id, "user logged out");

    crate::conferences::after_security_change(&state).await;

    Ok(StatusCode::NO_CONTENT)
}

async fn upsert_device(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    installation_id: Uuid,
    platform: DevicePlatform,
    display_name: Option<&str>,
    os_version: Option<&str>,
    app_version: Option<&str>,
) -> Result<(Uuid, Option<i64>), AppError> {
    // Account-scoped device identity: the same physical installation may be
    // used by different accounts over time, so devices.id is a server-generated
    // account-scoped id and the client's installation UUID lives in a separate
    // column. Lookup/reuse is keyed on (user_id, installation_id).
    // Serializes concurrent first-device-registration for the same user,
    // so the `next signal_device_id` computed below can't race (two
    // brand-new devices for one user, registered at the exact same
    // moment, must never get assigned the same small integer id).
    sqlx::query("SELECT pg_advisory_xact_lock(hashtext($1::text))")
        .bind(user_id)
        .execute(&mut **tx)
        .await?;

    let existing: Option<(Uuid,)> = sqlx::query_as(
        "SELECT id FROM devices WHERE user_id = $1 AND installation_id = $2",
    )
    .bind(user_id)
    .bind(installation_id)
    .fetch_optional(&mut **tx)
    .await?;

    if let Some((device_id,)) = existing {
        sqlx::query(
            "UPDATE devices SET last_active_at = now(), revoked_at = NULL, platform = $2, \
             display_name = COALESCE($3, display_name), \
             os_version = COALESCE($4, os_version), app_version = COALESCE($5, app_version) \
             WHERE id = $1",
        )
        .bind(device_id)
        .bind(platform)
        .bind(display_name)
        .bind(os_version)
        .bind(app_version)
        .execute(&mut **tx)
        .await?;
        return Ok((device_id, None));
    }

    let (next_signal_device_id,): (i16,) = sqlx::query_as(
        "SELECT (COALESCE(MAX(signal_device_id), 0) + 1)::smallint FROM devices WHERE user_id = $1",
    )
    .bind(user_id)
    .fetch_one(&mut **tx)
    .await?;

    let (device_id,): (Uuid,) = sqlx::query_as(
        "INSERT INTO devices (user_id, platform, display_name, last_active_at, signal_device_id, os_version, app_version, installation_id) \
         VALUES ($1, $2, $3, now(), $4, $5, $6, $7) \
         RETURNING id",
    )
    .bind(user_id)
    .bind(platform)
    .bind(display_name)
    .bind(next_signal_device_id)
    .bind(os_version)
    .bind(app_version)
    .bind(installation_id)
    .fetch_one(&mut **tx)
    .await?;

    // Этап L.16: log the new-device event in the same transaction as the
    // device insert (the two can never be observed independently).
    let metadata = serde_json::json!({
        "device_id": device_id,
        "installation_id": installation_id,
        "platform": platform,
        "display_name": display_name,
        "os_version": os_version,
        "app_version": app_version,
    });
    let event_id = crate::devices::handlers::record_security_event(
        tx,
        user_id,
        crate::devices::handlers::EVENT_NEW_DEVICE,
        Some(device_id),
        Some(device_id),
        metadata,
    )
    .await?;
    Ok((device_id, Some(event_id)))
}

async fn create_session(
    state: &AppState,
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: Uuid,
    device_id: Uuid,
) -> Result<(jwt::IssuedAccessToken, refresh_token::IssuedRefreshToken, Uuid), AppError> {
    let session_id = Uuid::new_v4();
    let refresh = refresh_token::generate(session_id);
    let expires_at = Utc::now() + Duration::seconds(state.config.refresh_token_ttl_seconds);

    sqlx::query(
        "INSERT INTO sessions (id, user_id, device_id, refresh_token_hash, expires_at, last_used_at) \
         VALUES ($1, $2, $3, $4, $5, now())",
    )
    .bind(session_id)
    .bind(user_id)
    .bind(device_id)
    .bind(&refresh.hash)
    .bind(expires_at)
    .execute(&mut **tx)
    .await?;

    let access = jwt::issue_access_token(&state.config, user_id, device_id, session_id)?;

    Ok((access, refresh, session_id))
}
