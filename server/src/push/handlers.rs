use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::Json;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use super::apns::{Environment, Kind, Preferences};
use crate::auth::{
    middleware::{lock_push_session, AuthUser},
    rate_limit,
};
use crate::error::AppError;
use crate::net::ClientIp;
use crate::state::AppState;

const REGISTER_PUSH_WINDOW_SECONDS: u64 = 60;
const MAX_TOKEN_LENGTH: usize = 4096;

#[derive(Deserialize)]
pub struct RegisterPushTokenRequest {
    pub provider: String,
    pub token: String,
    /// Informational only; actual platform and ownership are authenticated DB state.
    #[serde(default)]
    pub platform: Option<String>,
    pub environment: Option<Environment>,
    pub topic: Option<String>,
    pub registration_id: Option<Uuid>,
    pub revision: Option<i64>,
    pub preferences: Option<Preferences>,
}

#[derive(Serialize)]
pub struct RegisterPushTokenResponse {
    pub device_id: Uuid,
    pub provider: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub registration_id: Option<Uuid>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<i64>,
}

#[derive(sqlx::FromRow)]
struct Stored {
    registration_id: Uuid,
    revision: i64,
    token: String,
    environment: Option<String>,
    topic: Option<String>,
    message_alert_enabled: bool,
    message_sound_enabled: bool,
    permission_allowed: bool,
    retired: bool,
}

pub async fn register_push_token(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Json(req): Json<RegisterPushTokenRequest>,
) -> Result<Json<RegisterPushTokenResponse>, AppError> {
    rate_limit::enforce(
        &state,
        "push_token:ip",
        addr,
        120,
        REGISTER_PUSH_WINDOW_SECONDS,
    )
    .await?;
    rate_limit::enforce(
        &state,
        &format!("push_token:device:{}", auth.device_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        30,
        REGISTER_PUSH_WINDOW_SECONDS,
    )
    .await?;
    if req.token.trim().is_empty() || req.token.len() > MAX_TOKEN_LENGTH {
        return Err(AppError::Validation("Invalid push token.".into()));
    }
    let kind = match req.provider.as_str() {
        "fcm" => None,
        "apns" => Some(Kind::Alert),
        "apns_voip" => Some(Kind::Voip),
        _ => return Err(AppError::Validation("Unknown push provider.".into())),
    };
    let mut tx = state.db.begin().await?;
    lock_push_session(&mut tx, &auth).await?;
    let Some(kind) = kind else {
        sqlx::query("INSERT INTO push_tokens(device_id,provider,token) VALUES($1,'fcm',$2) \
            ON CONFLICT(device_id,provider) DO UPDATE SET token=$2,updated_at=now(), \
            invalidated_at=NULL,last_error_code=NULL,registration_id=gen_random_uuid(),token_registered_at=now()")
            .bind(auth.device_id).bind(&req.token).execute(&mut *tx).await?;
        tx.commit().await?;
        return Ok(Json(RegisterPushTokenResponse {
            device_id: auth.device_id,
            provider: req.provider,
            registration_id: None,
            revision: None,
        }));
    };
    let platform: String = sqlx::query_scalar(
        "SELECT platform::text FROM devices WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL",
    )
    .bind(auth.device_id)
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    if platform != "ios" {
        return Err(AppError::Validation("APNs requires an iOS device.".into()));
    }
    let environment = req
        .environment
        .ok_or_else(|| AppError::Validation("APNs environment is required.".into()))?;
    let topic = req
        .topic
        .as_deref()
        .ok_or_else(|| AppError::Validation("APNs topic is required.".into()))?;
    let id = req
        .registration_id
        .filter(|id| !id.is_nil())
        .ok_or_else(|| AppError::Validation("Registration ID is required.".into()))?;
    let revision = req.revision.filter(|value| *value > 0).ok_or_else(|| {
        AppError::Validation("Positive registration revision is required.".into())
    })?;
    let token = super::apns::normalize_token(&req.token)
        .map_err(|_| AppError::Validation("Invalid APNs token encoding.".into()))?;
    let preferences = if kind == Kind::Alert {
        req.preferences
            .ok_or_else(|| AppError::Validation("Alert preferences are required.".into()))?
    } else {
        Preferences {
            message_alert_enabled: false,
            message_sound_enabled: false,
            permission_allowed: false,
        }
    };
    if !state.push.admits_apns(kind, environment, topic) {
        return Err(AppError::Unavailable(
            "APNs environment/topic is not configured.".into(),
        ));
    }
    let current: Option<Stored> = sqlx::query_as("SELECT registration_id,revision,token,environment,topic, \
        message_alert_enabled,message_sound_enabled,permission_allowed,invalidated_at IS NOT NULL AS retired \
        FROM push_tokens WHERE device_id=$1 AND provider::text=$2 FOR UPDATE")
        .bind(auth.device_id).bind(&req.provider).fetch_optional(&mut *tx).await?;
    if let Some(current) = current {
        if revision < current.revision {
            return Err(AppError::PushRegistrationStale(current.revision));
        }
        if revision == current.revision {
            if id == current.registration_id && current.retired {
                return Err(AppError::PushRegistrationRetired(current.revision));
            }
            let same = id == current.registration_id
                && token == current.token
                && current.environment.as_deref() == Some(environment.as_str())
                && current.topic.as_deref() == Some(topic)
                && preferences.message_alert_enabled == current.message_alert_enabled
                && preferences.message_sound_enabled == current.message_sound_enabled
                && preferences.permission_allowed == current.permission_allowed;
            if !same {
                return Err(AppError::PushRegistrationStale(current.revision));
            }
            tx.commit().await?;
            return Ok(Json(RegisterPushTokenResponse {
                device_id: auth.device_id,
                provider: req.provider,
                registration_id: Some(id),
                revision: Some(revision),
            }));
        }
    }
    sqlx::query("INSERT INTO push_tokens(device_id,provider,token,environment,topic,registration_id,revision, \
        message_alert_enabled,message_sound_enabled,permission_allowed) \
        VALUES($1,$2::push_provider,$3,$4,$5,$6,$7,$8,$9,$10) \
        ON CONFLICT(device_id,provider) DO UPDATE SET token=EXCLUDED.token,environment=EXCLUDED.environment, \
        topic=EXCLUDED.topic,registration_id=EXCLUDED.registration_id,revision=EXCLUDED.revision, \
        message_alert_enabled=EXCLUDED.message_alert_enabled,message_sound_enabled=EXCLUDED.message_sound_enabled, \
        permission_allowed=EXCLUDED.permission_allowed,token_registered_at=now(),invalidated_at=NULL, \
        last_error_code=NULL,last_success_at=NULL")
        .bind(auth.device_id).bind(&req.provider).bind(&token).bind(environment.as_str()).bind(topic).bind(id).bind(revision)
        .bind(preferences.message_alert_enabled).bind(preferences.message_sound_enabled).bind(preferences.permission_allowed)
        .execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(RegisterPushTokenResponse {
        device_id: auth.device_id,
        provider: req.provider,
        registration_id: Some(id),
        revision: Some(revision),
    }))
}

#[derive(Default, Deserialize)]
pub struct DeletePushTokenQuery {
    pub provider: Option<String>,
    pub registration_id: Option<Uuid>,
    pub revision: Option<i64>,
}

pub async fn delete_push_token(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<DeletePushTokenQuery>,
) -> Result<StatusCode, AppError> {
    let mut tx = state.db.begin().await?;
    lock_push_session(&mut tx, &auth).await?;
    match (query.provider, query.registration_id, query.revision) {
        (None, None, None) => {
            sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id=$1")
                .bind(auth.device_id)
                .execute(&mut *tx)
                .await?;
        }
        (Some(provider), Some(id), Some(revision))
            if matches!(provider.as_str(), "apns" | "apns_voip")
                && revision > 0
                && !id.is_nil() =>
        {
            let platform: String = sqlx::query_scalar("SELECT platform::text FROM devices WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL")
                .bind(auth.device_id).bind(auth.user_id).fetch_one(&mut *tx).await?;
            if platform != "ios" {
                return Err(AppError::Validation("APNs requires an iOS device.".into()));
            }
            // Retain OFF intent even if its ambiguously timed-out PUT has not arrived.
            // A newer or same-revision different intent must survive an old cleanup.
            sqlx::query("INSERT INTO push_tokens(device_id,provider,token,registration_id,revision,invalidated_at) \
                VALUES($1,$2::push_provider,'',$3,$4,now()) ON CONFLICT(device_id,provider) DO UPDATE \
                SET token='',environment=NULL,topic=NULL,registration_id=EXCLUDED.registration_id,revision=EXCLUDED.revision,invalidated_at=now() \
                WHERE push_tokens.revision<EXCLUDED.revision OR \
                (push_tokens.revision=EXCLUDED.revision AND push_tokens.registration_id=EXCLUDED.registration_id)")
                .bind(auth.device_id).bind(provider).bind(id).bind(revision).execute(&mut *tx).await?;
        }
        (Some(provider), Some(id), Some(revision)) if provider == "fcm" && revision >= 0 => {
            sqlx::query("UPDATE push_tokens SET invalidated_at=now() WHERE device_id=$1 AND provider='fcm' AND registration_id=$2 AND revision=$3")
                .bind(auth.device_id).bind(id).bind(revision).execute(&mut *tx).await?;
        }
        _ => {
            return Err(AppError::Validation(
                "Provider, registration ID and revision are required together.".into(),
            ))
        }
    }
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
