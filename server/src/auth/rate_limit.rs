use std::net::IpAddr;

use crate::error::AppError;
use crate::state::AppState;

/// Fixed-window rate limiter backed by Redis: `limit` requests per
/// `window_seconds`, keyed by route bucket + client IP.
pub async fn enforce(
    state: &AppState,
    bucket: &str,
    ip: IpAddr,
    limit: u64,
    window_seconds: u64,
) -> Result<(), AppError> {
    if try_enforce(state, bucket, ip, limit, window_seconds).await? {
        Ok(())
    } else {
        Err(AppError::RateLimited)
    }
}

/// Like [`enforce`], but returns `false` instead of an error when the window
/// is exhausted. Used where a shared budget must degrade a response instead
/// of creating a global switch that one caller can use to deny everyone else
/// (the target's one-time-prekey budget is the concrete case).
pub async fn try_enforce(
    state: &AppState,
    bucket: &str,
    ip: IpAddr,
    limit: u64,
    window_seconds: u64,
) -> Result<bool, AppError> {
    let key = format!("ratelimit:{bucket}:{ip}");
    let mut conn = state.redis.clone();

    // The increment and TTL decision must be one Redis operation. Expiry
    // between separate SET NX and INCR commands recreated permanent buckets.
    // Repair legacy no-TTL counters without resetting their existing count;
    // normal hits never extend a live fixed window.
    if window_seconds == 0 { return Ok(false); }
    let ttl = i64::try_from(window_seconds).map_err(|_| AppError::Internal)?;
    let count: u64 = redis::Script::new(
        "local n=redis.call('INCR',KEYS[1]); if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n"
    ).key(&key).arg(ttl).invoke_async(&mut conn).await.map_err(redis_error)?;

    Ok(count <= limit)
}

fn redis_error(err: redis::RedisError) -> AppError {
    tracing::error!(error = %err, "rate limiter redis error");
    AppError::Internal
}
