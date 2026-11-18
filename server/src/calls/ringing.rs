//! Ringing confirmation (docs/CALL_RINGING_SPEC.md): the caller learns one bit,
//! "some callee device is ringing", and nothing else - no device, count,
//! platform or time.
//!
//! The kill switch is parsed outside `Config` (like `client_config`) so the many
//! struct-literal test configs stay valid; `Config::from_env` still parses it
//! once at boot, so a typo fails there instead of at the first call.
use std::sync::OnceLock;
use std::time::Duration;

use axum::http::HeaderMap;
use uuid::Uuid;

use crate::calls::models::{RINGING_SIGNAL_FLOOR_MS, RINGING_TIMEOUT_SECONDS};
use crate::error::AppError;
use crate::state::AppState;
use crate::websocket;
use crate::websocket::protocol::ServerMessage;

/// Sent by a client on the WebSocket upgrade request.
pub const CAPABILITIES_HEADER: &str = "x-unyri-capabilities";
/// The device confirms incoming calls with `POST /calls/{id}/ringing`.
pub const CALL_RING_ACK: &str = "call-ring-ack";
const CAPABILITIES_MAX_BYTES: usize = 256;

/// `CALL_RINGING_SIGNAL`: `on` (default) or `off`. Off is legacy mode for every
/// caller without a client release. Diagnostics name the key, never the value.
pub fn signal_from_env() -> anyhow::Result<bool> {
    parse_signal(std::env::var("CALL_RINGING_SIGNAL").ok().as_deref())
}

pub(crate) fn parse_signal(value: Option<&str>) -> anyhow::Result<bool> {
    match value.map(str::trim).filter(|value| !value.is_empty()) {
        None => Ok(true),
        Some(value) if value.eq_ignore_ascii_case("on") => Ok(true),
        Some(value) if value.eq_ignore_ascii_case("off") => Ok(false),
        Some(_) => anyhow::bail!("CALL_RINGING_SIGNAL must be on or off"),
    }
}

static SIGNAL: OnceLock<bool> = OnceLock::new();

/// Boot already validated the env. A harness that skips `Config::from_env` and
/// sets an invalid value fails closed (legacy mode), never open.
pub fn signal_enabled() -> bool {
    *SIGNAL.get_or_init(|| signal_from_env().unwrap_or(false))
}

/// The bit is never revealed earlier than this after `call.ringing` went out, by
/// event or by read, so "app open" and "woken by push" look the same. Measured
/// from `created_at`, which create_call refreshes after its locks and response reads.
pub fn floor_seconds() -> f64 {
    RINGING_SIGNAL_FLOOR_MS as f64 / 1000.0
}

fn confirmation_key(callee: Uuid, call: Uuid) -> String {
    format!("ratelimit:calls_ringing:call:{callee}:{call}:0.0.0.0")
}

fn closed_key(callee: Uuid, call: Uuid) -> String {
    format!("calls_ringing:closed:{callee}:{call}")
}

const REDIS_TIMEOUT_MS: u64 = 250;
const CONFIRMATION_TTL_FLOOR_MS: i64 = 100;

/// Include connection/reconnection and script loading in the same short budget.
/// Dropping a Redis future does not cancel a command already sent to the server.
pub(super) async fn redis_deadline<T>(
    operation: impl std::future::Future<Output = Result<T, AppError>>,
) -> Result<T, AppError> {
    tokio::time::timeout(Duration::from_millis(REDIS_TIMEOUT_MS), operation).await.unwrap_or_else(|_| {
        tracing::warn!("call confirmation Redis deadline exceeded");
        Err(AppError::Internal)
    })
}

/// Spend the aggregate backstop before any call lookup.
pub(super) async fn limit_confirmation_user(
    state: &AppState, callee: Uuid, limit: u64, window_seconds: u64,
) -> Result<(), AppError> {
    let count: u64 = redis_deadline(async {
        let mut conn = state.redis.clone();
        redis::Script::new(
            "local n=redis.call('INCR',KEYS[1]); \
             if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; \
             return n"
        ).key(format!("ratelimit:calls_ringing:user:{callee}:0.0.0.0"))
            .arg(window_seconds).invoke_async(&mut conn).await.map_err(|err| {
                tracing::error!(%err, "call confirmation user limiter failed");
                AppError::Internal
            })
    }).await?;
    if count > limit { return Err(AppError::RateLimited); }
    Ok(())
}

/// Called after an authorized ringing read, with no database connection held.
/// The database duration only sizes the key TTL, never decides call validity.
/// A Redis-only command deadline discards abandoned commands, and the closed
/// marker prevents recreation after a concurrent successful terminal cleanup.
pub(super) async fn limit_confirmation(
    state: &AppState, callee: Uuid, call: Uuid, limit: u64, remaining_ms: i64,
) -> Result<(), AppError> {
    let ttl_ms = remaining_ms.clamp(CONFIRMATION_TTL_FLOOR_MS, RINGING_TIMEOUT_SECONDS * 1000);
    let count: i64 = redis_deadline(async {
        let mut conn = state.redis.clone();
        // Keep this round trip inside the same 250 ms budget. Both the command
        // timestamp and its comparison use Redis; no database clock is involved.
        let (seconds, micros): (i64, i64) = redis::cmd("TIME").query_async(&mut conn).await.map_err(|err| {
            tracing::error!(%err, "call confirmation Redis clock lookup failed");
            AppError::Internal
        })?;
        let execute_before_ms = seconds * 1000 + micros / 1000 + REDIS_TIMEOUT_MS as i64;
        redis::Script::new(
            "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); \
             if now>=tonumber(ARGV[2]) then return -2; end; \
             if redis.call('EXISTS',KEYS[2])==1 then return -1; end; \
             local n=redis.call('INCR',KEYS[1]); local ttl=redis.call('PTTL',KEYS[1]); \
             if ttl<0 or ttl>tonumber(ARGV[1]) then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return n"
        ).key(confirmation_key(callee, call)).key(closed_key(callee, call))
            .arg(ttl_ms).arg(execute_before_ms).invoke_async(&mut conn)
            .await.map_err(|err| {
                tracing::error!(%err, "call confirmation limiter failed");
                AppError::Internal
            })
    }).await?;
    if count == -2 { return Err(AppError::Internal); }
    if count == -1 { return Err(AppError::Conflict("Call is no longer ringing.".into())); }
    if count as u64 > limit { Err(AppError::RateLimited) } else { Ok(()) }
}

/// Every transition out of ringing forgets the transient confirmation counter.
/// A count-free marker rejects in-flight confirmations for at most one ringing
/// window. The counter's bounded TTL is the fallback if cleanup times out.
pub async fn forget_confirmation(state: &AppState, callee: Uuid, call: Uuid) {
    let _ = redis_deadline(async {
        let mut conn = state.redis.clone();
        let result: redis::RedisResult<()> = redis::Script::new(
            "redis.call('SET',KEYS[2],'closed','EX',ARGV[1]); redis.call('DEL',KEYS[1]); return nil"
        ).key(confirmation_key(callee, call)).key(closed_key(callee, call))
            .arg(RINGING_TIMEOUT_SECONDS).invoke_async(&mut conn).await;
        result.map_err(|err| {
            tracing::warn!(%err, "call confirmation cleanup failed");
            AppError::Internal
        })
    }).await;
}

/// `X-Unyri-Capabilities: call-ring-ack[,other]`: comma-separated tokens,
/// visible ASCII, at most 256 bytes in total, compared case-sensitively.
/// Unknown tokens are ignored; a malformed or oversized header counts as absent.
/// Repeated header lines form one list, as HTTP defines.
pub fn advertises_ring_ack(headers: &HeaderMap) -> bool {
    let mut total = 0usize;
    let mut found = false;
    for value in headers.get_all(CAPABILITIES_HEADER) {
        let Ok(text) = value.to_str() else { return false };
        total += text.len();
        if total > CAPABILITIES_MAX_BYTES {
            return false;
        }
        found |= text.split(',').any(|token| token.trim() == CALL_RING_ACK);
    }
    found
}

/// Runs on every socket upgrade, before the upgrade response, so the flag is
/// current by the time the socket registers. An absent header clears it (an
/// older build was installed). Writes only on change; success logs nothing and
/// a failure logs no ids and never blocks the socket.
pub async fn record_capability(db: &sqlx::PgPool, device_id: Uuid, capable: bool) {
    let result = sqlx::query("UPDATE devices SET call_ring_ack = $2 WHERE id = $1 AND call_ring_ack IS DISTINCT FROM $2")
        .bind(device_id)
        .bind(capable)
        .execute(db)
        .await;
    if let Err(err) = result {
        tracing::debug!(%err, "call ring capability update failed");
    }
}

/// The expectation bit, computed once inside `create_call`'s transaction and
/// frozen on the row (spec §2.3). True only when the callee lets the caller see
/// them online and every non-revoked callee device that is not blocked for the
/// caller advertises the capability. This deliberately ignores live sockets,
/// push tokens and configured push providers: the create response must not
/// disclose connectivity before the signal floor. An empty set is true.
pub async fn expectation(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    callee: Uuid,
    caller: Uuid,
) -> Result<bool, AppError> {
    if !signal_enabled() {
        return Ok(false);
    }
    let visible: bool = sqlx::query_scalar("SELECT user_presence_visible($1,$2,'online')")
        .bind(callee)
        .bind(caller)
        .fetch_one(&mut **tx)
        .await?;
    if !visible {
        return Ok(false);
    }
    let expected: bool = sqlx::query_scalar(
        "SELECT NOT EXISTS(SELECT 1 FROM devices d \
         WHERE d.user_id = $1 AND d.revoked_at IS NULL AND NOT d.call_ring_ack \
           AND NOT user_device_blocked(d.id, $2))",
    )
    .bind(callee)
    .bind(caller)
    .fetch_one(&mut **tx)
    .await?;
    Ok(expected)
}

/// Called by the one confirmation that set the bit, so at most one event per
/// call. `wait_seconds` is the rest of the floor as computed by Postgres, the
/// clock that wrote `created_at`; app-server clock skew cannot shorten it. A
/// restart inside the floor loses the event; the caller's reads recover it.
pub async fn announce_after_floor(state: &AppState, call_id: Uuid, wait_seconds: f64) {
    let floor = floor_seconds();
    let wait = if wait_seconds.is_finite() { wait_seconds.clamp(0.0, floor) } else { floor };
    if wait > 0.0 {
        let state = state.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs_f64(wait)).await;
            announce(&state, call_id).await;
        });
    } else {
        announce(state, call_id).await;
    }
}

/// Re-checked at emission: an accept or end inside the floor cleared the bit
/// through the trigger, and the callee may have hidden their online status.
async fn announce(state: &AppState, call_id: Uuid) {
    if !signal_enabled() {
        return;
    }
    let caller: Option<(Uuid, Uuid)> = match sqlx::query_as(
        "SELECT caller_device_id,caller_session_family FROM calls \
         WHERE id = $1 AND state = 'ringing' AND callee_ringing \
           AND caller_device_id IS NOT NULL AND caller_session_family IS NOT NULL \
           AND user_presence_visible(callee_user_id, caller_user_id, 'online')",
    )
    .bind(call_id)
    .fetch_optional(&state.db)
    .await
    {
        Ok(caller) => caller,
        Err(err) => {
            tracing::warn!(%err, "call ringing signal check failed");
            return;
        }
    };
    if let Some((device, family)) = caller {
        websocket::notify_family(state, device, family, &ServerMessage::CallPeerRinging { call_id }).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    #[test]
    fn kill_switch_defaults_on_and_rejects_typos() {
        assert!(parse_signal(None).unwrap());
        assert!(parse_signal(Some("")).unwrap());
        assert!(parse_signal(Some(" on ")).unwrap());
        assert!(parse_signal(Some("ON")).unwrap());
        assert!(!parse_signal(Some("off")).unwrap());
        assert!(!parse_signal(Some("Off")).unwrap());
        assert!(parse_signal(Some("false")).is_err());
        assert!(parse_signal(Some("0")).is_err());
        let error = parse_signal(Some("secret-value")).unwrap_err().to_string();
        assert!(!error.contains("secret-value"), "diagnostics never echo the value");
    }

    fn headers(values: &[&[u8]]) -> HeaderMap {
        let mut map = HeaderMap::new();
        for value in values {
            map.append(CAPABILITIES_HEADER, HeaderValue::from_bytes(value).unwrap());
        }
        map
    }

    #[test]
    fn capability_header_parsing() {
        assert!(!advertises_ring_ack(&HeaderMap::new()), "absent header = false");
        assert!(advertises_ring_ack(&headers(&[b"call-ring-ack"])));
        assert!(advertises_ring_ack(&headers(&[b"future-thing, call-ring-ack"])));
        assert!(advertises_ring_ack(&headers(&[b"future-thing", b"call-ring-ack"])), "repeated lines form one list");
        assert!(!advertises_ring_ack(&headers(&[b"Call-Ring-Ack"])), "tokens are case-sensitive");
        assert!(!advertises_ring_ack(&headers(&[b"call-ring-ack-v2"])), "no prefix matches");
        assert!(!advertises_ring_ack(&headers(&[b"other"])));
        assert!(!advertises_ring_ack(&headers(&[b"call-ring-ack, caf\xc3\xa9"])), "non-ASCII = malformed = absent");
        let long = format!("call-ring-ack,{}", "x".repeat(CAPABILITIES_MAX_BYTES));
        assert!(!advertises_ring_ack(&headers(&[long.as_bytes()])), "oversized = absent");
        let exact = format!("call-ring-ack,{}", "x".repeat(CAPABILITIES_MAX_BYTES - "call-ring-ack,".len()));
        assert!(advertises_ring_ack(&headers(&[exact.as_bytes()])), "256 bytes is still accepted");
    }

    #[test]
    fn floor_is_two_and_a_half_seconds() {
        assert_eq!(floor_seconds(), 2.5);
    }

    #[test]
    fn peer_ringing_wire_format_carries_only_the_call_id() {
        let call_id = Uuid::new_v4();
        let wire = serde_json::to_value(ServerMessage::CallPeerRinging { call_id }).unwrap();
        assert_eq!(wire, serde_json::json!({"type": "call.peer_ringing", "call_id": call_id}));
    }
}
