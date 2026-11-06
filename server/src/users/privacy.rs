use axum::{extract::State, Json};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{auth::{middleware::{lock_active_session, AuthUser}, rate_limit}, error::AppError, state::AppState};

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq, sqlx::Type)]
#[serde(rename_all = "snake_case")]
#[sqlx(type_name = "text", rename_all = "snake_case")]
pub enum Visibility { #[default] Everyone, Nobody }

#[derive(Clone, Debug, Default, Deserialize, Serialize, sqlx::FromRow, PartialEq, Eq)]
pub struct PrivacySnapshot {
    pub online_visibility: Visibility,
    pub last_seen_visibility: Visibility,
    pub always_allow: Vec<Uuid>,
    pub never_allow: Vec<Uuid>,
    pub send_read_receipts: bool,
    pub revision: i64,
}

impl PrivacySnapshot {
    fn initial() -> Self { Self { send_read_receipts: true, ..Self::default() } }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PrivacyUpdate {
    pub online_visibility: Visibility,
    pub last_seen_visibility: Visibility,
    pub always_allow: Vec<Uuid>,
    pub never_allow: Vec<Uuid>,
    pub send_read_receipts: bool,
    pub expected_revision: i64,
}

const SELECT: &str = "SELECT online_visibility,last_seen_visibility,always_allow,never_allow,send_read_receipts,revision FROM user_privacy WHERE user_id=$1";

pub async fn get(State(state): State<AppState>, auth: AuthUser) -> Result<Json<PrivacySnapshot>, AppError> {
    rate_limit::enforce(&state, &format!("privacy:read:{}",auth.user_id), std::net::Ipv4Addr::UNSPECIFIED.into(), 120, 60).await?;
    let snapshot = sqlx::query_as(SELECT).bind(auth.user_id).fetch_optional(&state.db).await?;
    Ok(Json(snapshot.unwrap_or_else(PrivacySnapshot::initial)))
}

pub async fn put(State(state): State<AppState>, auth: AuthUser, Json(mut request): Json<PrivacyUpdate>) -> Result<Json<PrivacySnapshot>, AppError> {
    rate_limit::enforce(&state, &format!("privacy:write:{}",auth.user_id), std::net::Ipv4Addr::UNSPECIFIED.into(), 60, 60).await?;
    if request.expected_revision < 0 || request.always_allow.len() > 256 || request.never_allow.len() > 256 {
        return Err(AppError::Validation("Invalid privacy revision or too many exceptions.".into()));
    }
    for ids in [&mut request.always_allow, &mut request.never_allow] {
        if ids.iter().any(Uuid::is_nil) { return Err(AppError::Validation("Invalid privacy exception.".into())); }
        ids.retain(|id| *id != auth.user_id);
        ids.sort_unstable(); ids.dedup();
    }
    let mut tx = state.db.begin().await?;
    // Same account lock as explicit read mutations: preference and read exposure
    // have a definite commit order even when different devices race.
    // Privacy restrictions stay available to read-only accounts, like blocking
    // and session revocation. An active, unrevoked session is still mandatory.
    lock_active_session(&mut tx, &auth).await?;
    let current: PrivacySnapshot = sqlx::query_as(SELECT).bind(auth.user_id).fetch_optional(&mut *tx).await?
        .unwrap_or_else(PrivacySnapshot::initial);
    if current.revision != request.expected_revision {
        return Err(AppError::Conflict("Privacy settings changed. Reload and try again.".into()));
    }
    let mut next = PrivacySnapshot { online_visibility: request.online_visibility, last_seen_visibility: request.last_seen_visibility,
        always_allow: request.always_allow, never_allow: request.never_allow, send_read_receipts: request.send_read_receipts,
        revision: current.revision };
    if next != current {
        next.revision = next.revision.checked_add(1).ok_or(AppError::Internal)?;
        sqlx::query("INSERT INTO user_privacy(user_id,online_visibility,last_seen_visibility,always_allow,never_allow,send_read_receipts,revision) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(user_id) DO UPDATE SET online_visibility=$2,last_seen_visibility=$3,always_allow=$4,never_allow=$5,send_read_receipts=$6,revision=$7,updated_at=now()")
            .bind(auth.user_id).bind(next.online_visibility).bind(next.last_seen_visibility).bind(&next.always_allow).bind(&next.never_allow)
            .bind(next.send_read_receipts).bind(next.revision).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    // A hint to own other devices. All clients refresh on foreground/reconnect;
    // peer presence subscriptions reproject from the DB each one-second tick.
    let devices: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL")
        .bind(auth.user_id).fetch_all(&state.db).await.unwrap_or_default();
    let hint = serde_json::json!({"type":"privacy.changed","revision":next.revision}).to_string();
    for device in devices { state.ws_registry.send_to_device(device, &hint).await; }
    Ok(Json(next))
}
