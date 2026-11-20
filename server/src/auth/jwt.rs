use chrono::{DateTime, Duration, Utc};
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::config::Config;
use crate::error::AppError;

/// Access token claims. Short-lived and stateless; `session_id` lets a
/// request handler correlate back to the revocable session row.
#[derive(Debug, Serialize, Deserialize)]
pub struct AccessTokenClaims {
    pub sub: Uuid,
    pub device_id: Uuid,
    pub session_id: Uuid,
    pub iss: String,
    pub aud: String,
    pub iat: i64,
    pub exp: i64,
    pub jti: Uuid,
}

pub struct IssuedAccessToken {
    pub token: String,
    pub expires_at: DateTime<Utc>,
}

pub fn issue_access_token(
    config: &Config,
    user_id: Uuid,
    device_id: Uuid,
    session_id: Uuid,
) -> Result<IssuedAccessToken, AppError> {
    issue_session_access_token(config, user_id, device_id, session_id, false)
}

pub fn issue_session_access_token(
    config: &Config, user_id: Uuid, device_id: Uuid, session_id: Uuid, bound: bool,
) -> Result<IssuedAccessToken, AppError> {
    let now = Utc::now();
    let ttl = if bound { config.access_token_ttl_seconds.min(super::device_proof::BOUND_ACCESS_TTL_SECONDS) }
        else { config.access_token_ttl_seconds };
    let expires_at = now + Duration::seconds(ttl);

    let claims = AccessTokenClaims {
        sub: user_id,
        device_id,
        session_id,
        iss: config.jwt_issuer.clone(),
        aud: config.jwt_audience.clone(),
        iat: now.timestamp(),
        exp: expires_at.timestamp(),
        jti: Uuid::new_v4(),
    };

    let token = encode(
        &Header::new(Algorithm::HS256),
        &claims,
        &EncodingKey::from_secret(config.jwt_secret.as_bytes()),
    )
    .map_err(|err| {
        tracing::error!(error = %err, "failed to encode access token");
        AppError::Internal
    })?;

    Ok(IssuedAccessToken { token, expires_at })
}

pub fn decode_access_token(config: &Config, token: &str) -> Result<AccessTokenClaims, AppError> {
    let mut validation = Validation::new(Algorithm::HS256);
    validation.leeway = 0;
    validation.set_issuer(&[config.jwt_issuer.clone()]);
    validation.set_audience(&[config.jwt_audience.clone()]);

    decode::<AccessTokenClaims>(token, &DecodingKey::from_secret(config.jwt_secret.as_bytes()), &validation)
        .and_then(|data| {
            if data.claims.exp <= Utc::now().timestamp() {
                Err(jsonwebtoken::errors::Error::from(jsonwebtoken::errors::ErrorKind::ExpiredSignature))
            } else { Ok(data.claims) }
        })
        .map_err(|_| AppError::Unauthorized)
}
