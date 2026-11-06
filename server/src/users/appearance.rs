use crate::{
    auth::middleware::{lock_active_user, AuthUser},
    error::AppError,
    state::AppState,
};
use axum::{extract::State, Json};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::sync::OnceLock;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AppearancePatch {
    pub version: i32,
    pub values: BTreeMap<String, String>,
    #[serde(default)]
    pub initialize_only: bool,
}
#[derive(Serialize, Deserialize, sqlx::FromRow)]
pub struct AppearanceSnapshot {
    pub revision: i64,
    pub values: Value,
}

fn validate(request: &AppearancePatch) -> Result<Value, AppError> {
    if request.version != 1 || request.values.is_empty() || request.values.len() > 66 {
        return Err(AppError::Validation(
            "Unsupported or empty appearance settings.".into(),
        ));
    }
    static SCHEMA: OnceLock<Option<Value>> = OnceLock::new();
    let schema = SCHEMA
        .get_or_init(|| {
            serde_json::from_str(include_str!("../../../shared/chat-appearance.json")).ok()
        })
        .as_ref()
        .ok_or(AppError::Internal)?;
    let fields = schema["fields"].as_array().ok_or(AppError::Internal)?;
    for (key, raw) in &request.values {
        let field = fields
            .iter()
            .find(|f| f["key"].as_str() == Some(key))
            .ok_or_else(|| AppError::Validation("Unknown appearance setting.".into()))?;
        let valid = raw.len() <= 64
            && match field["kind"].as_str() {
                Some("color") => {
                    raw.len() == 7
                        && raw.starts_with('#')
                        && raw.as_bytes()[1..].iter().all(u8::is_ascii_hexdigit)
                }
                Some("bool") => raw == "true" || raw == "false",
                Some("choice") => ["Фирменный", "Редкие символы", "Точки"].contains(&raw.as_str()),
                Some("number") => raw.parse::<f64>().ok().is_some_and(|n| {
                    n.is_finite()
                        && n >= field["min"].as_f64().unwrap_or(0.)
                        && n <= field["max"].as_f64().unwrap_or(1.)
                }),
                _ => false,
            };
        if !valid {
            return Err(AppError::Validation("Invalid appearance setting.".into()));
        }
    }
    serde_json::to_value(&request.values).map_err(|_| AppError::Internal)
}

pub async fn get(
    State(state): State<AppState>,
    crate::net::ClientIp(ip): crate::net::ClientIp,
    auth: AuthUser,
) -> Result<Json<AppearanceSnapshot>, AppError> {
    crate::auth::rate_limit::enforce(
        &state,
        &format!("appearance:read:{}", auth.user_id),
        ip,
        240,
        60,
    )
    .await?;
    let row = sqlx::query_as::<_, AppearanceSnapshot>(
        "SELECT revision, values FROM user_chat_appearance WHERE user_id=$1",
    )
    .bind(auth.user_id)
    .fetch_optional(&state.db)
    .await?;
    Ok(Json(row.unwrap_or(AppearanceSnapshot {
        revision: 0,
        values: json!({}),
    })))
}

/// Merge only supplied keys under the account lock. A replay of identical values is a no-op.
pub async fn patch(
    State(state): State<AppState>,
    crate::net::ClientIp(ip): crate::net::ClientIp,
    auth: AuthUser,
    Json(request): Json<AppearancePatch>,
) -> Result<Json<AppearanceSnapshot>, AppError> {
    crate::auth::rate_limit::enforce(
        &state,
        &format!("appearance:write:{}", auth.user_id),
        ip,
        120,
        60,
    )
    .await?;
    let values = validate(&request)?;
    let mut tx = state.db.begin().await?;
    lock_active_user(&mut tx, &auth).await?;
    let row = if request.initialize_only {
        sqlx::query("INSERT INTO user_chat_appearance(user_id, revision, values) VALUES($1,1,$2) ON CONFLICT(user_id) DO NOTHING")
            .bind(auth.user_id).bind(values).execute(&mut *tx).await?;
        sqlx::query_as::<_, AppearanceSnapshot>(
            "SELECT revision, values FROM user_chat_appearance WHERE user_id=$1",
        )
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?
    } else {
        sqlx::query_as::<_, AppearanceSnapshot>(
        "INSERT INTO user_chat_appearance(user_id, revision, values) VALUES($1,1,$2) ON CONFLICT(user_id) DO UPDATE SET values=user_chat_appearance.values || EXCLUDED.values, revision=user_chat_appearance.revision + CASE WHEN user_chat_appearance.values @> EXCLUDED.values THEN 0 ELSE 1 END, updated_at=now() RETURNING revision, values"
    ).bind(auth.user_id).bind(values).fetch_one(&mut *tx).await?
    };
    tx.commit().await?;
    // Event is a hint, not the durable state. Reconnect/foreground clients GET the snapshot.
    let devices: Vec<uuid::Uuid> =
        sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1 AND revoked_at IS NULL")
            .bind(auth.user_id)
            .fetch_all(&state.db)
            .await
            .unwrap_or_default();
    let hint = json!({"type":"appearance.changed","revision":row.revision}).to_string();
    for device in devices {
        state.ws_registry.send_to_device(device, &hint).await;
    }
    Ok(Json(row))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_schema_without_allowing_arbitrary_account_data() {
        for (key, value) in [
            ("tokens.out", "#123ABC"),
            ("common.fontSize", "14"),
            ("geometry.inline", "true"),
            ("pattern.kind", "Точки"),
        ] {
            assert!(validate(&AppearancePatch {
                version: 1,
                values: BTreeMap::from([(key.into(), value.into())]),
                initialize_only: false
            })
            .is_ok());
        }
        for (key, value) in [
            ("private.notes", "x"),
            ("tokens.out", "red"),
            ("common.fontSize", "999"),
            ("common.fontSize", "NaN"),
            ("geometry.inline", "yes"),
        ] {
            assert!(validate(&AppearancePatch {
                version: 1,
                values: BTreeMap::from([(key.into(), value.into())]),
                initialize_only: false
            })
            .is_err());
        }
    }
}
