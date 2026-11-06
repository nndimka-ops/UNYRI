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
    rate_limit::enforce(&state, "installation-create", ip, 20, 3600).await?;
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
    if credential.len() != 43 {
        return Err(invalid());
    }
    let secret = URL_SAFE_NO_PAD.decode(credential).map_err(|_| invalid())?;
    if secret.len() != 32 {
        return Err(invalid());
    }
    let hash = Sha256::digest(secret);
    let installation: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM verified_installations WHERE credential_hash=$1")
            .bind(hash.as_slice())
            .fetch_optional(&mut **tx)
            .await?;
    installation.map(Some).ok_or_else(invalid)
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
    rate_limit::enforce(&state, "installation-bind", ip, 60, 60).await?;
    let mut tx = state.db.begin().await?;
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
    sqlx::query("UPDATE devices SET verified_installation_id=$3 WHERE id=$1 AND user_id=$2")
        .bind(device)
        .bind(user)
        .bind(installation)
        .execute(&mut **tx)
        .await?;
    if let Some(id) = installation {
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
