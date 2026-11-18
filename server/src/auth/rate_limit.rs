use std::net::IpAddr;
use std::time::Duration;

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
    try_enforce_key(state, bucket, &client_key(ip), limit, window_seconds).await
}

/// [`enforce`] for unauthenticated authentication routes: IPv6 clients are
/// aggregated by /48 so rotating delegated /56 or /64 networks cannot reset it.
pub async fn enforce_auth(
    state: &AppState,
    bucket: &str,
    ip: IpAddr,
    limit: u64,
    window_seconds: u64,
) -> Result<(), AppError> {
    if try_enforce_key(state, bucket, &auth_client_key(ip), limit, window_seconds).await? {
        Ok(())
    } else {
        Err(AppError::RateLimited)
    }
}

/// Fixed-window counter for an explicit identity (`subject`), e.g. a global
/// budget that is not tied to one client address.
pub async fn try_enforce_key(
    state: &AppState,
    bucket: &str,
    subject: &str,
    limit: u64,
    window_seconds: u64,
) -> Result<bool, AppError> {
    if window_seconds == 0 { return Ok(false); }
    let count = increment(state, &format!("ratelimit:{bucket}:{subject}"), window_seconds).await?;
    Ok(count <= limit)
}

/// Increment a fixed-window counter and return the new count.
async fn increment(state: &AppState, key: &str, window_seconds: u64) -> Result<u64, AppError> {
    let mut conn = state.redis.clone();
    // The increment and TTL decision must be one Redis operation. Expiry
    // between separate SET NX and INCR commands recreated permanent buckets.
    // Repair legacy no-TTL counters without resetting their existing count;
    // normal hits never extend a live fixed window.
    let ttl = i64::try_from(window_seconds).map_err(|_| AppError::Internal)?;
    redis::Script::new(
        "local n=redis.call('INCR',KEYS[1]); if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n"
    ).key(key).arg(ttl).invoke_async(&mut conn).await.map_err(redis_error)
}

/// Failure-only reservation counter, scoped either to an account/network or
/// to the account across every network.
///
/// - Network scope: IPv4 /24, IPv6 /48. Global scope has no network suffix.
/// - Counts failures only. An attempt is reserved before the expensive check
///   (so a parallel burst is still bounded) and refunded when it succeeds or
///   when it never reached a credential decision.
/// - Repeated failures first slow that network down progressively; only the
///   hard limit refuses.
///
/// Callers skip the throttle entirely for a request that proves an
/// installation already bound to the account (`installations::bound_to_account`).
pub struct AccountThrottle {
    key: String,
}

impl AccountThrottle {
    /// Check an existing lockout without reserving a credential attempt. Used
    /// before reporting payload/password errors; admit still serializes races.
    pub async fn ensure_available(&self, state: &AppState, limit: u64, window_seconds: u64) -> Result<(), AppError> {
        if window_seconds == 0 { return Err(AppError::RateLimited); }
        let mut conn = state.redis.clone();
        let available: bool = redis::Script::new(
            "local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>=tonumber(ARGV[1]) then if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[2]); end; return 0 end; return 1"
        ).key(&self.key).arg(limit).arg(window_seconds).invoke_async(&mut conn).await.map_err(redis_error)?;
        if available { Ok(()) } else { Err(AppError::RateLimited) }
    }

    pub fn global(scope: &str, normalized_account: &str) -> Self {
        use sha2::{Digest, Sha256};
        let account = Sha256::digest(normalized_account.as_bytes());
        Self { key: format!("ratelimit:{scope}:{account:x}:global") }
    }

    pub fn new(scope: &str, normalized_account: &str, ip: IpAddr) -> Self {
        use sha2::{Digest, Sha256};
        // Hash the bounded Redis key rather than storing usernames.
        let account = Sha256::digest(normalized_account.as_bytes());
        Self { key: format!("ratelimit:{scope}:{account:x}:{}", account_network_key(ip)) }
    }

    /// Reserve one attempt. Refuses once `limit` attempts in this scope
    /// are on record and otherwise waits the progressive delay for the
    /// failures already recorded.
    pub async fn admit(&self, state: &AppState, limit: u64, window_seconds: u64) -> Result<(), AppError> {
        if window_seconds == 0 { return Err(AppError::RateLimited); }
        let mut conn = state.redis.clone();
        // A refused request never owns a reservation. Incrementing it would
        // turn concurrent refusals into failures that successful checks cannot
        // refund, leaving an account locked despite correct passwords.
        let attempts: u64 = redis::Script::new(
            "local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>=tonumber(ARGV[1]) then if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[2]); end; return 0 end; n=redis.call('INCR',KEYS[1]); if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[2]); end; return n"
        ).key(&self.key).arg(limit).arg(window_seconds).invoke_async(&mut conn).await.map_err(redis_error)?;
        if attempts == 0 { return Err(AppError::RateLimited); }
        let delay = progressive_delay(attempts - 1, limit);
        if !delay.is_zero() { tokio::time::sleep(delay).await; }
        Ok(())
    }

    /// Return the reserved attempt: the credential was correct, or the
    /// request failed before a credential decision was made.
    pub async fn refund(&self, state: &AppState) {
        let mut conn = state.redis.clone();
        let result: Result<i64, _> = redis::Script::new(
            "local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>0 then return redis.call('DECR',KEYS[1]); end; return 0"
        ).key(&self.key).invoke_async(&mut conn).await;
        if let Err(err) = result { tracing::warn!(error = %err, "account throttle refund failed"); }
    }
}

/// No delay for the first few failures (typos), then 250 ms doubling up to
/// 4 s per attempt until the hard limit refuses.
pub fn progressive_delay(prior_failures: u64, limit: u64) -> Duration {
    let free = (limit / 4).max(3);
    if prior_failures < free { return Duration::ZERO; }
    let step = (prior_failures - free).min(4) as u32;
    Duration::from_millis(250u64 << step)
}

fn redis_error(err: redis::RedisError) -> AppError {
    tracing::error!(error = %err, "rate limiter redis error");
    AppError::Internal
}

/// Rate-limit identity for an address: IPv4 as is, IPv6 by its /64 prefix
/// (a single subscriber normally controls a whole /64).
pub fn client_key(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() { return v4.to_string(); }
            let s = v6.segments();
            format!("{:x}:{:x}:{:x}:{:x}::/64", s[0], s[1], s[2], s[3])
        }
    }
}

/// Authentication-route identity: IPv4 as is, IPv6 by its /48 prefix.
pub fn auth_client_key(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(_) => client_key(ip),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => v4.to_string(),
            None => ipv6_slash_48(v6),
        },
    }
}

/// Network identity for per-account failure counters: IPv4 /24, IPv6 /48.
pub fn account_network_key(ip: IpAddr) -> String {
    let v4 = match ip {
        IpAddr::V4(v4) => v4,
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => v4,
            None => return ipv6_slash_48(v6),
        },
    };
    let o = v4.octets();
    format!("{}.{}.{}.0/24", o[0], o[1], o[2])
}

fn ipv6_slash_48(v6: std::net::Ipv6Addr) -> String {
    let s = v6.segments();
    format!("{:x}:{:x}:{:x}::/48", s[0], s[1], s[2])
}

#[cfg(test)]
mod client_key_tests {
    use super::client_key;
    use std::net::IpAddr;
    #[test]
    fn ipv6_is_keyed_by_slash_64_and_ipv4_is_unchanged() {
        let a: IpAddr = "2001:db8:1:2:aaaa::1".parse().unwrap();
        let b: IpAddr = "2001:db8:1:2:ffff:ffff:ffff:ffff".parse().unwrap();
        let c: IpAddr = "2001:db8:1:3::1".parse().unwrap();
        assert_eq!(client_key(a), client_key(b));
        assert_ne!(client_key(a), client_key(c));
        assert_eq!(client_key("203.0.113.7".parse().unwrap()), "203.0.113.7");
        assert_eq!(client_key("::ffff:203.0.113.7".parse().unwrap()), "203.0.113.7");
    }

    #[test]
    fn auth_routes_aggregate_ipv6_by_slash_48() {
        use super::auth_client_key;
        let a: IpAddr = "2001:db8:1:2ff:aaaa::1".parse().unwrap();
        let b: IpAddr = "2001:db8:1:200::1".parse().unwrap();
        let c: IpAddr = "2001:db8:2:300::1".parse().unwrap();
        assert_eq!(auth_client_key(a), "2001:db8:1::/48");
        assert_eq!(auth_client_key(a), auth_client_key(b));
        assert_ne!(auth_client_key(a), auth_client_key(c));
        assert_eq!(auth_client_key("203.0.113.7".parse().unwrap()), "203.0.113.7");
        assert_eq!(auth_client_key("::ffff:203.0.113.7".parse().unwrap()), "203.0.113.7");
    }

    #[test]
    fn account_throttle_network_is_ipv4_slash_24_and_ipv6_slash_48() {
        use super::account_network_key;
        assert_eq!(account_network_key("203.0.113.7".parse().unwrap()), "203.0.113.0/24");
        assert_eq!(account_network_key("203.0.113.250".parse().unwrap()), "203.0.113.0/24");
        assert_ne!(account_network_key("203.0.114.7".parse().unwrap()), "203.0.113.0/24");
        assert_eq!(account_network_key("::ffff:203.0.113.7".parse().unwrap()), "203.0.113.0/24");
        assert_eq!(account_network_key("2001:db8:1:2ff::1".parse().unwrap()), "2001:db8:1::/48");
    }

    #[test]
    fn progressive_delay_starts_after_free_attempts_and_is_capped() {
        use super::progressive_delay;
        use std::time::Duration;
        for prior in 0..5 { assert_eq!(progressive_delay(prior, 20), Duration::ZERO); }
        assert_eq!(progressive_delay(5, 20), Duration::from_millis(250));
        assert_eq!(progressive_delay(6, 20), Duration::from_millis(500));
        assert_eq!(progressive_delay(9, 20), Duration::from_secs(4));
        assert_eq!(progressive_delay(19, 20), Duration::from_secs(4));
        // Recovery uses limit 10 and keeps three free attempts.
        assert_eq!(progressive_delay(2, 10), Duration::ZERO);
        assert_eq!(progressive_delay(3, 10), Duration::from_millis(250));
        // A strict operator limit below the free allowance never delays.
        assert_eq!(progressive_delay(0, 1), Duration::ZERO);
    }
}
