//! Server-issued installation proof; independent of account identity/history.
//! Credentials are opaque capabilities and must never enter URLs or logs.
use crate::{
    auth::{
        middleware::{lock_active_session, AuthUser},
        rate_limit,
    },
    error::AppError,
    net::ClientIp,
    state::AppState,
};
use axum::http::{header, HeaderMap};
use axum::{extract::State, Json};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

#[derive(Serialize)]
pub struct InstallationCredential {
    installation_credential: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BindInstallation {
    installation_credential: String,
}

pub async fn create(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
) -> Result<(HeaderMap, Json<InstallationCredential>), AppError> {
    rate_limit::enforce_auth(&state, "installation-create", ip, 20, 3600).await?;
    let mut secret = [0u8; 32];
    OsRng.fill_bytes(&mut secret);
    let credential = URL_SAFE_NO_PAD.encode(secret);
    let hash = Sha256::digest(secret);
    sqlx::query("INSERT INTO verified_installations(credential_hash) VALUES($1)")
        .bind(hash.as_slice())
        .execute(&state.db)
        .await?;
    let mut headers = HeaderMap::new();
    headers.insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    Ok((
        headers,
        Json(InstallationCredential {
            installation_credential: credential,
        }),
    ))
}

pub async fn verify(
    tx: &mut Transaction<'_, Postgres>,
    credential: Option<&str>,
) -> Result<Option<Uuid>, AppError> {
    let Some(credential) = credential else {
        return Ok(None);
    };
    let hash = credential_hash(credential).ok_or_else(invalid)?;
    let installation: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM verified_installations WHERE credential_hash=$1")
            .bind(hash.as_slice())
            .fetch_optional(&mut **tx)
            .await?;
    installation.map(Some).ok_or_else(invalid)
}

fn credential_hash(credential: &str) -> Option<[u8; 32]> {
    if credential.len() != 43 {
        return None;
    }
    let secret = URL_SAFE_NO_PAD.decode(credential).ok()?;
    if secret.len() != 32 {
        return None;
    }
    Some(Sha256::digest(secret).into())
}

/// Whether `credential` proves an installation already bound to the active
/// account `username_normalized` through a device that was never revoked
/// afterwards (device removal and password recovery revoke it). Such a
/// request is exempt from the per-account sign-in throttle, so guessing
/// against a handle cannot keep its owner's own installations out.
///
/// The lookup runs for every supplied credential, whether or not the
/// account exists, and a malformed credential is simply not a proof here
/// (the sign-in transaction still validates it).
pub async fn bound_to_account(
    db: &sqlx::PgPool,
    credential: Option<&str>,
    username_normalized: &str,
) -> Result<bool, AppError> {
    let Some(credential) = credential else {
        return Ok(false);
    };
    let hash = credential_hash(credential).unwrap_or([0u8; 32]);
    let bound: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM verified_installations vi \
         JOIN verified_installation_users viu ON viu.installation_id=vi.id \
         JOIN users u ON u.id=viu.user_id \
         JOIN devices d ON d.user_id=u.id AND d.verified_installation_id=vi.id AND d.revoked_at IS NULL \
         WHERE vi.credential_hash=$1 AND u.username_normalized=$2 AND u.deleted_at IS NULL)",
    )
    .bind(hash.as_slice())
    .bind(username_normalized)
    .fetch_one(db)
    .await?;
    Ok(bound)
}

fn invalid() -> AppError {
    AppError::Validation("Invalid installation credential.".into())
}

pub async fn bind(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Json(request): Json<BindInstallation>,
) -> Result<Json<()>, AppError> {
    rate_limit::enforce_auth(&state, "installation-bind", ip, 60, 60).await?;
    let mut tx = state.db.begin().await?;
    super::admission::lock(&mut tx, false).await?;
    lock_active_session(&mut tx, &auth).await?;
    let id = verify(&mut tx, Some(&request.installation_credential)).await?;
    bind_device(&mut tx, auth.user_id, auth.device_id, id).await?;
    tx.commit().await?;
    Ok(Json(()))
}

pub async fn bind_device(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    device: Uuid,
    installation: Option<Uuid>,
) -> Result<(), AppError> {
    // A missing proof is a legacy login, not permission to erase an existing
    // binding. A different proof must never replace the device's identity.
    let installation = match installation {
        Some(id) => Some(id),
        None => sqlx::query_scalar("SELECT verified_installation_id FROM devices WHERE id=$1 AND user_id=$2")
            .bind(device).bind(user).fetch_one(&mut **tx).await?,
    };
    let Some(installation) = installation else { return Ok(()); };
    let client_id: Uuid = sqlx::query_scalar("SELECT installation_id FROM devices WHERE id=$1 AND user_id=$2")
        .bind(device).bind(user).fetch_one(&mut **tx).await?;
    let canonical: Option<Uuid> = sqlx::query_scalar(
        "SELECT client_installation_id FROM verified_installations WHERE id=$1 FOR UPDATE",
    ).bind(installation).fetch_one(&mut **tx).await?;
    // Check the proof's durable aliases before comparing the public UUID. This
    // also covers inconsistent historical bindings retained by migration 0088.
    let restricted: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM verified_installation_aliases a JOIN installation_suspensions b ON b.installation_id=a.client_installation_id \
         WHERE a.installation_id=$1 AND (b.until_date IS NULL OR b.until_date>now()))",
    ).bind(installation).fetch_one(&mut **tx).await?;
    if restricted { return Err(AppError::InstallationRestricted); }
    if canonical.is_some_and(|id| id != client_id) { return Err(invalid()); }
    let updated = sqlx::query("UPDATE devices SET verified_installation_id=$3 WHERE id=$1 AND user_id=$2 AND (verified_installation_id IS NULL OR verified_installation_id=$3)")
        .bind(device)
        .bind(user)
        .bind(installation)
        .execute(&mut **tx)
        .await?;
    if updated.rows_affected() != 1 { return Err(invalid()); }
    sqlx::query("UPDATE verified_installations SET client_installation_id=$2 WHERE id=$1 AND client_installation_id IS NULL")
        .bind(installation).bind(client_id).execute(&mut **tx).await?;
    sqlx::query("INSERT INTO verified_installation_aliases(installation_id,client_installation_id) VALUES($1,$2) ON CONFLICT DO NOTHING")
        .bind(installation).bind(client_id).execute(&mut **tx).await?;
    {
        let id = installation;
        sqlx::query("INSERT INTO verified_installation_users(installation_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING")
            .bind(id).bind(user).execute(&mut **tx).await?;
        // Existing blocks also cover an already signed-in account upgraded to
        // proof support. No unrelated account becomes globally blocked.
        sqlx::query("INSERT INTO user_block_installations(user_id,blocked_user_id,installation_id) \
            SELECT user_id,blocked_user_id,$2 FROM user_blocks WHERE blocked_user_id=$1 ON CONFLICT DO NOTHING")
            .bind(user).bind(id).execute(&mut **tx).await?;
    }
    Ok(())
}
