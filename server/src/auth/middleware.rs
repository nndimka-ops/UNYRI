use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use chrono::Utc;
use uuid::Uuid;

use crate::auth::jwt::decode_access_token;
use crate::error::AppError;
use crate::state::AppState;

/// Authenticated request identity, extracted from a validated access token
/// whose session is confirmed (via DB lookup) to still be active.
#[derive(Debug, Clone)]
pub struct AuthUser {
    pub user_id: Uuid,
    pub device_id: Uuid,
    pub session_id: Uuid,
}

/// Push mutation ordering matches login and administrative device retirement.
/// Hold the exact live session through the slot write, not just HTTP extraction.
pub async fn lock_push_session(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
) -> Result<(), AppError> {
    crate::auth::admission::lock(tx, false).await?;
    lock_active_session(tx, auth).await?;
    let session: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM sessions WHERE id=$1 AND user_id=$2 AND device_id=$3 \
         AND revoked_at IS NULL AND expires_at>now() FOR UPDATE",
    ).bind(auth.session_id).bind(auth.user_id).bind(auth.device_id)
        .fetch_optional(&mut **tx).await?;
    if session.is_none() { return Err(AppError::SessionRevoked); }
    Ok(())
}

pub async fn account_read_only(state: &AppState, user_id: Uuid) -> Result<bool, AppError> {
    Ok(sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM admin_user_controls WHERE user_id=$1 AND read_only_reason IS NOT NULL AND (read_only_until IS NULL OR read_only_until>now()))",
    )
    .bind(user_id)
    .fetch_one(&state.db)
    .await?)
}

/// Serialize sensitive mutations with recovery and recheck the admitted session
/// after waiting for the account lock. HTTP extraction alone is too early.
pub async fn lock_active_user(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
) -> Result<(), AppError> {
    lock_active_session(tx, auth).await?;
    let read_only: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM admin_user_controls WHERE user_id=$1 AND read_only_reason IS NOT NULL AND (read_only_until IS NULL OR read_only_until>now()))",
    ).bind(auth.user_id).fetch_one(&mut **tx).await?;
    if read_only {
        return Err(AppError::AccountReadOnly);
    }
    Ok(())
}

/// Security operations (revocation) remain available to read-only accounts.
pub async fn lock_active_session(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
) -> Result<(), AppError> {
    let user: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL FOR UPDATE")
            .bind(auth.user_id)
            .fetch_optional(&mut **tx)
            .await?;
    if user.is_none() {
        return Err(AppError::SessionRevoked);
    }
    let active: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM sessions s JOIN devices d ON d.id=s.device_id WHERE s.id=$1 AND s.user_id=$2 AND s.device_id=$3 AND s.revoked_at IS NULL AND s.expires_at>now() AND d.revoked_at IS NULL AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=d.installation_id AND (b.until_date IS NULL OR b.until_date>now())) AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=s.user_id AND (b.until_date IS NULL OR b.until_date>now())))",
    ).bind(auth.session_id).bind(auth.user_id).bind(auth.device_id).fetch_one(&mut **tx).await?;
    if !active {
        return Err(AppError::SessionRevoked);
    }
    Ok(())
}

#[derive(sqlx::FromRow)]
struct SessionStatusRow {
    revoked_at: Option<chrono::DateTime<Utc>>,
    expires_at: chrono::DateTime<Utc>,
    read_only: bool,
}

fn read_only_allows(parts: &Parts) -> bool {
    use axum::http::Method;
    if parts.method == Method::GET
        || parts.method == Method::HEAD
        || parts.method == Method::OPTIONS
    {
        return true;
    }
    let path = parts.uri.path();
    path == "/auth/logout"
        || (path.starts_with("/conferences/") && (path.ends_with("/leave") || path.ends_with("/end")))
        || path == "/auth/logout-pending"
        || path == "/devices/me/push-token"
        || path == "/devices/me/installation"
        || path == "/devices/revoke-others"
        || path == "/users/me/privacy"
        || path == "/users/me/auto-delete"
        || path == "/users/me/activity"
        || (path.starts_with("/devices/") && path.matches('/').count() == 2)
        || (path.starts_with("/chats/") && path.ends_with("/read"))
        || (path.starts_with("/users/") && path.ends_with("/block") && path.matches('/').count() == 3)
        || (path.starts_with("/chats/") && (path.ends_with("/block") || path.ends_with("/unblock")))
        || path == "/diagnostics"
        || path == "/performance"
        || path == "/feedback"
}

#[axum::async_trait]
impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let header = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .ok_or(AppError::Unauthorized)?;

        let token = header
            .strip_prefix("Bearer ")
            .ok_or(AppError::Unauthorized)?;
        let claims = decode_access_token(&state.config, token)?;

        let session = sqlx::query_as::<_, SessionStatusRow>(
            "SELECT s.revoked_at, s.expires_at, EXISTS(SELECT 1 FROM admin_user_controls c WHERE c.user_id=s.user_id AND (c.read_only_until IS NULL OR c.read_only_until>now()) AND c.read_only_reason IS NOT NULL) AS read_only FROM sessions s WHERE s.id = $1 AND NOT EXISTS(SELECT 1 FROM devices d JOIN installation_suspensions b ON b.installation_id=d.installation_id WHERE d.id=s.device_id AND (b.until_date IS NULL OR b.until_date>now())) AND NOT EXISTS (SELECT 1 FROM server_suspensions b WHERE b.user_id=s.user_id AND (b.until_date IS NULL OR b.until_date>now()))",
        )
        .bind(claims.session_id)
        .fetch_optional(&state.db)
        .await?
        // A valid server-signed token whose session no longer exists (including
        // account deletion) must clear the client's former account state.
        .ok_or(AppError::SessionRevoked)?;

        if session.revoked_at.is_some() {
            return Err(AppError::SessionRevoked);
        }
        if session.expires_at < Utc::now() {
            return Err(AppError::SessionRevoked);
        }
        if session.read_only && !read_only_allows(parts) {
            return Err(AppError::AccountReadOnly);
        }

        Ok(AuthUser {
            user_id: claims.sub,
            device_id: claims.device_id,
            session_id: claims.session_id,
        })
    }
}

/// A live socket follows refresh rotation, but never survives logout/revocation.
/// Replaced sessions are a linked chain; only its active, unexpired leaf authorizes I/O.
pub async fn socket_session_active(state: &AppState, auth: &AuthUser) -> Result<bool, AppError> {
    // One database round trip and one snapshot per check, including suspension.
    Ok(sqlx::query_scalar::<_, bool>(
        "WITH RECURSIVE family AS ( \
         SELECT id, replaced_by, revoked_at, expires_at FROM sessions \
         WHERE id = $1 AND user_id = $2 AND device_id = $3 \
         UNION \
         SELECT s.id, s.replaced_by, s.revoked_at, s.expires_at FROM sessions s \
         JOIN family f ON s.id = f.replaced_by \
         WHERE s.user_id = $2 AND s.device_id = $3) \
         SELECT EXISTS(SELECT 1 FROM family WHERE revoked_at IS NULL AND expires_at > now()) \
         AND NOT EXISTS(SELECT 1 FROM devices d JOIN installation_suspensions b ON b.installation_id=d.installation_id WHERE d.id=$3 AND (b.until_date IS NULL OR b.until_date>now())) AND NOT EXISTS(SELECT 1 FROM server_suspensions WHERE user_id=$2 AND (until_date IS NULL OR until_date>now()))"
    ).bind(auth.session_id).bind(auth.user_id).bind(auth.device_id)
        .fetch_one(&state.db).await?)
}
