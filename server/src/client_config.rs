//! Public client compatibility policy (`GET /client/config`). Unauthenticated
//! and secret-free, so self-hosted servers and logged-out clients can read it.
//!
//! The env values are parsed outside `Config` (like `ConferenceConfig`) so the
//! many struct-literal test configs stay valid; `Config::from_env` still parses
//! them once at boot, so a typo fails there instead of at the first request.
use std::cmp::Ordering;
use std::sync::OnceLock;

use axum::extract::State;
use axum::http::{header, HeaderValue};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::{json, Value};

use crate::auth::rate_limit;
use crate::error::AppError;
use crate::net::ClientIp;
use crate::state::AppState;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlatformPolicy {
    pub minimum: String,
    pub recommended: String,
    pub update_url: Option<String>,
}

impl Default for PlatformPolicy {
    fn default() -> Self {
        Self { minimum: "0.0.0".into(), recommended: "0.0.0".into(), update_url: None }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ClientPolicy {
    pub ios: PlatformPolicy,
    pub android: PlatformPolicy,
    pub desktop: PlatformPolicy,
}

impl ClientPolicy {
    pub fn from_env() -> anyhow::Result<Self> {
        Self::parse(|key| std::env::var(key).ok())
    }

    pub(crate) fn parse(get: impl Fn(&str) -> Option<String>) -> anyhow::Result<Self> {
        Ok(Self {
            ios: platform(&get, "IOS")?,
            android: platform(&get, "ANDROID")?,
            desktop: platform(&get, "DESKTOP")?,
        })
    }
}

/// Diagnostics name the key, never the value.
fn platform(get: &impl Fn(&str) -> Option<String>, name: &str) -> anyhow::Result<PlatformPolicy> {
    let read = |suffix: &str| {
        let key = format!("CLIENT_{name}_{suffix}");
        let value = get(&key).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
        (key, value)
    };
    let (key, minimum) = read("MINIMUM_VERSION");
    let minimum = match minimum {
        Some(value) if version_valid(&value) => value,
        Some(_) => anyhow::bail!("{key} is invalid"),
        None => "0.0.0".to_string(),
    };
    let (key, recommended) = read("RECOMMENDED_VERSION");
    let recommended = match recommended {
        Some(value) if version_valid(&value) => value,
        Some(_) => anyhow::bail!("{key} is invalid"),
        None => minimum.clone(),
    };
    // A recommendation below the minimum means nothing; raise it.
    let recommended = if compare_versions(&recommended, &minimum) == Ordering::Less { minimum.clone() } else { recommended };
    let (key, update_url) = read("UPDATE_URL");
    let update_url = match update_url {
        Some(value) if url_valid(&value) => Some(value),
        Some(_) => anyhow::bail!("{key} is invalid"),
        None => None,
    };
    Ok(PlatformPolicy { minimum, recommended, update_url })
}

fn url_valid(value: &str) -> bool {
    value.len() <= 512
        && reqwest::Url::parse(value).is_ok_and(|url| {
            url.scheme() == "https" && url.host_str().is_some_and(|host| !host.is_empty())
                && url.username().is_empty() && url.password().is_none()
        })
}

static POLICY: OnceLock<ClientPolicy> = OnceLock::new();

/// Boot already validated the env, so the default only covers a test harness
/// that builds the router without `Config::from_env`.
fn policy() -> &'static ClientPolicy {
    POLICY.get_or_init(|| ClientPolicy::from_env().unwrap_or_default())
}

/// `^\d{1,6}(\.\d{1,6}){0,3}([-+][0-9A-Za-z.\-]{1,32})?$`, at most 64 bytes.
pub fn version_valid(v: &str) -> bool {
    if v.len() > 64 {
        return false;
    }
    let (core, suffix) = match v.find(|c| c == '-' || c == '+') {
        Some(index) => (&v[..index], Some(&v[index + 1..])),
        None => (v, None),
    };
    let parts: Vec<&str> = core.split('.').collect();
    if parts.len() > 4 || !parts.iter().all(|part| (1..=6).contains(&part.len()) && part.bytes().all(|b| b.is_ascii_digit())) {
        return false;
    }
    suffix.map_or(true, |suffix| {
        (1..=32).contains(&suffix.len()) && suffix.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-')
    })
}

/// Numeric dotted prefix padded to four parts; the suffix never orders
/// ("0.2.1-alpha" equals "0.2.1").
pub fn parse_version(v: &str) -> Option<[u64; 4]> {
    if !version_valid(v) {
        return None;
    }
    let core = v.split(|c| c == '-' || c == '+').next().unwrap_or_default();
    let mut parts = [0u64; 4];
    for (slot, part) in parts.iter_mut().zip(core.split('.')) {
        *slot = part.parse().ok()?;
    }
    Some(parts)
}

/// An invalid value sorts as 0.0.0.0.
pub fn compare_versions(a: &str, b: &str) -> Ordering {
    parse_version(a).unwrap_or_default().cmp(&parse_version(b).unwrap_or_default())
}

fn max_version(a: &str, b: &str) -> String {
    if compare_versions(b, a) == Ordering::Greater { b.to_string() } else { a.to_string() }
}

fn effective_platform(env: &PlatformPolicy, console: &str) -> Value {
    let console = console.trim();
    // The owner console and the env both set a floor; the higher one wins.
    let minimum = if !console.is_empty() && version_valid(console) { max_version(&env.minimum, console) } else { env.minimum.clone() };
    let recommended = max_version(&env.recommended, &minimum);
    json!({"minimum_version": minimum, "recommended_version": recommended, "update_url": env.update_url})
}

/// `console` is `(ios, android, desktop)` from `server_policy`.
fn effective(policy: &ClientPolicy, console: Option<&(String, String, String)>) -> Value {
    let (ios, android, desktop) = console.map_or(("", "", ""), |(i, a, d)| (i.as_str(), a.as_str(), d.as_str()));
    json!({
        "schema_version": 1,
        "platforms": {
            "ios": effective_platform(&policy.ios, ios),
            "android": effective_platform(&policy.android, android),
            "desktop": effective_platform(&policy.desktop, desktop),
        }
    })
}

pub async fn get(State(state): State<AppState>, ClientIp(addr): ClientIp) -> Result<Response, AppError> {
    rate_limit::enforce(&state, "client-config:ip", addr, 120, 60).await?;
    // A database outage falls back to the env policy: an update gate must
    // never turn into a 500 that clients read as "no gate".
    let console: Option<(String, String, String)> = sqlx::query_as(
        "SELECT min_ios_version,min_android_version,min_desktop_version FROM server_policy WHERE id=true",
    )
    .fetch_optional(&state.db)
    .await
    .ok()
    .flatten();
    let mut response = Json(effective(policy(), console.as_ref())).into_response();
    // security_headers uses or_insert, so this value wins.
    response.headers_mut().insert(header::CACHE_CONTROL, HeaderValue::from_static("public, max-age=60"));
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let pairs: Vec<(String, String)> = pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        move |key| pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v.clone())
    }

    #[test]
    fn defaults_never_block() {
        assert_eq!(ClientPolicy::parse(|_| None).unwrap(), ClientPolicy::default());
        assert_eq!(ClientPolicy::parse(env(&[("CLIENT_IOS_MINIMUM_VERSION", "  ")])).unwrap(), ClientPolicy::default());
    }

    #[test]
    fn explicit_values_parse() {
        let policy = ClientPolicy::parse(env(&[
            ("CLIENT_IOS_MINIMUM_VERSION", "0.4.10"),
            ("CLIENT_IOS_RECOMMENDED_VERSION", "0.4.13"),
            ("CLIENT_IOS_UPDATE_URL", "https://testflight.apple.com/join/abc"),
            ("CLIENT_ANDROID_MINIMUM_VERSION", "1.2"),
        ]))
        .unwrap();
        assert_eq!(policy.ios, PlatformPolicy {
            minimum: "0.4.10".into(),
            recommended: "0.4.13".into(),
            update_url: Some("https://testflight.apple.com/join/abc".into()),
        });
        assert_eq!(policy.android.minimum, "1.2");
        assert_eq!(policy.android.recommended, "1.2", "recommended defaults to the minimum");
        assert_eq!(policy.desktop, PlatformPolicy::default());
    }

    #[test]
    fn recommended_below_minimum_is_raised() {
        let policy = ClientPolicy::parse(env(&[
            ("CLIENT_DESKTOP_MINIMUM_VERSION", "2.0.0"),
            ("CLIENT_DESKTOP_RECOMMENDED_VERSION", "1.9.9"),
        ]))
        .unwrap();
        assert_eq!(policy.desktop.recommended, "2.0.0");
    }

    #[test]
    fn invalid_versions_are_rejected_by_key() {
        let long_suffix = format!("1.0-{}", "a".repeat(33));
        for bad in ["1..2", "v1", "1.2.3.4.5", "1234567.0", long_suffix.as_str(), "1.0-", "1.0 beta"] {
            let error = ClientPolicy::parse(env(&[("CLIENT_IOS_MINIMUM_VERSION", bad)])).unwrap_err().to_string();
            assert_eq!(error, "CLIENT_IOS_MINIMUM_VERSION is invalid", "{bad}");
        }
        assert!(!version_valid(""));
        assert!(ClientPolicy::parse(env(&[("CLIENT_ANDROID_RECOMMENDED_VERSION", "x")])).is_err());
        assert!(version_valid("0.2.0-alpha.1"));
        assert!(version_valid("1.0+build-7"));
        assert!(version_valid(&format!("1.0-{}", "a".repeat(32))));    }

    #[test]
    fn invalid_urls_are_rejected() {
        let long = format!("https://example.com/{}", "a".repeat(500));
        for bad in ["http://apps.apple.com/app/id1", "https://user@example.com/x", "https://user:pw@example.com", "https://", "mailto:a@b.c", long.as_str()] {
            let error = ClientPolicy::parse(env(&[("CLIENT_IOS_UPDATE_URL", bad)])).unwrap_err().to_string();
            assert_eq!(error, "CLIENT_IOS_UPDATE_URL is invalid", "{bad}");
        }
        assert!(ClientPolicy::parse(env(&[("CLIENT_IOS_UPDATE_URL", "https://apps.apple.com/app/id1")])).is_ok());
    }

    #[test]
    fn versions_compare_numerically_and_ignore_suffixes() {
        assert_eq!(compare_versions("0.4.13", "0.4.9"), Ordering::Greater);
        assert_eq!(compare_versions("1.0", "1.0.0"), Ordering::Equal);
        assert_eq!(compare_versions("0.2.1-alpha", "0.2.1"), Ordering::Equal);
        assert_eq!(compare_versions("garbage", "0.0.0.0"), Ordering::Equal);
        assert_eq!(parse_version("1.2.3.4"), Some([1, 2, 3, 4]));
        assert_eq!(parse_version("7"), Some([7, 0, 0, 0]));
        assert_eq!(parse_version("1.2.3.4.5"), None);
    }

    #[test]
    fn effective_policy_takes_the_higher_floor() {
        let policy = ClientPolicy::parse(env(&[
            ("CLIENT_IOS_MINIMUM_VERSION", "0.4.0"),
            ("CLIENT_IOS_RECOMMENDED_VERSION", "0.4.5"),
            ("CLIENT_IOS_UPDATE_URL", "https://apps.apple.com/app/id1"),
        ]))
        .unwrap();
        let console = ("0.4.10".to_string(), "".to_string(), "abc".to_string());
        let value = effective(&policy, Some(&console));
        assert_eq!(value["schema_version"], 1);
        let ios = &value["platforms"]["ios"];
        assert_eq!(ios["minimum_version"], "0.4.10");
        assert_eq!(ios["recommended_version"], "0.4.10");
        assert_eq!(ios["update_url"], "https://apps.apple.com/app/id1");
        assert_eq!(value["platforms"]["android"]["minimum_version"], "0.0.0");
        assert_eq!(value["platforms"]["desktop"]["minimum_version"], "0.0.0", "an invalid console value is ignored");
        assert!(value["platforms"]["desktop"]["update_url"].is_null());

        let lower = ("0.3.0".to_string(), String::new(), String::new());
        assert_eq!(effective(&policy, Some(&lower))["platforms"]["ios"]["minimum_version"], "0.4.0");
        assert_eq!(effective(&policy, Some(&lower))["platforms"]["ios"]["recommended_version"], "0.4.5");
        assert_eq!(effective(&policy, None)["platforms"]["ios"]["minimum_version"], "0.4.0");

        let keys: Vec<&String> = value["platforms"]["ios"].as_object().unwrap().keys().collect();
        assert_eq!(keys.len(), 3);
        for key in ["minimum_version", "recommended_version", "update_url"] {
            assert!(value["platforms"]["ios"].get(key).is_some(), "{key}");
        }
    }
}
