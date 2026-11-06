use axum::{extract::State, http::StatusCode, Json};
use serde::{Deserialize, Serialize};
use crate::net::ClientIp;
use crate::{auth::{middleware::AuthUser, rate_limit}, error::AppError, state::AppState};

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Platform { Android, Ios }
#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Operation { Messages, Receipts, Chats, Sync, Calls, Attachments, Keys }

/// Deliberately no arbitrary text, URLs, IDs, device names, or stack traces.
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Event {
    platform: Platform,
    operation: Operation,
    status: u16,
}
impl Event {
    fn valid(&self) -> bool { self.status == 0 || (400..=599).contains(&self.status) }
}

pub async fn ingest(State(state): State<AppState>, ClientIp(addr): ClientIp,
    auth: AuthUser, Json(event): Json<Event>) -> Result<StatusCode, AppError> {
    if !event.valid() { return Err(AppError::Validation("Invalid diagnostic status".into())); }
    rate_limit::enforce(&state, "diagnostics:ip", addr, 120, 60).await?;
    rate_limit::enforce(
        &state,
        &format!("diagnostics:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        30,
        60,
    ).await?;
    let now = chrono::Utc::now();
    let key = format!("diagnostics:v1:{}", now.format("%Y-%m-%d"));
    let payload = serde_json::json!({"received_at": now.to_rfc3339(), "event": event}).to_string();
    let mut redis = state.redis.clone();
    // Bounded daily ring; expire each day's data independently of later traffic.
    let _: () = redis::pipe().atomic().cmd("LPUSH").arg(&key).arg(payload).ignore()
        .cmd("LTRIM").arg(&key).arg(0).arg(999).ignore()
        .cmd("EXPIRE").arg(&key).arg(604800).ignore()
        .query_async(&mut redis).await.map_err(|_| AppError::Internal)?;
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn strict_schema_rejects_sensitive_fields() {
        assert!(serde_json::from_str::<Event>(r#"{"platform":"ios","operation":"messages","status":500,"text":"secret"}"#).is_err());
        assert!(serde_json::from_str::<Event>(r#"{"platform":"ios","operation":"secret","status":500}"#).is_err());
    }
    #[test] fn only_error_statuses_are_allowed() {
        for status in [0, 400, 429, 503, 599] { assert!(Event { platform: Platform::Ios, operation: Operation::Messages, status }.valid()); }
        for status in [1, 200, 399, 600] { assert!(!Event { platform: Platform::Android, operation: Operation::Calls, status }.valid()); }
    }
}
