use std::env;

use crate::net::IpNet;

/// Server configuration loaded from environment variables (see `.env.example`).
#[derive(Debug, Clone)]
pub struct Config {
    pub host: String,
    pub port: u16,
    pub database_url: String,
    pub database_max_connections: u32,
    pub redis_url: String,
    pub jwt_secret: String,
    /// Direct peers allowed to supply X-Forwarded-For. Empty means no proxy
    /// is trusted and every direct peer is treated as the client.
    pub trusted_proxies: Vec<IpNet>,
    pub jwt_issuer: String,
    pub jwt_audience: String,
    pub access_token_ttl_seconds: i64,
    pub refresh_token_ttl_seconds: i64,
    pub s3: S3Config,
    pub turn: TurnConfig,
    pub fcm: FcmConfig,
}

/// FCM push configuration. Entirely optional: when `project_id` is absent (or
/// no service-account credential file is resolvable) the server still boots
/// and serves the whole product - it just never sends FCM pushes. This keeps
/// local dev and CI free of a Firebase dependency.
#[derive(Debug, Clone, Default)]
pub struct FcmConfig {
    pub project_id: Option<String>,
    pub service_account_file: Option<String>,
}

impl FcmConfig {
    /// FCM is only wired up when both a project id and a credential file are
    /// configured. Never invent keys - absence simply disables the provider.
    pub fn enabled(&self) -> bool {
        self.project_id.is_some() && self.service_account_file.is_some()
    }
}

/// TURN/STUN configuration for `GET /calls/ice-servers`. The server never
/// hands out a permanent TURN credential - `turn_shared_secret` is only ever
/// used server-side to HMAC-sign short-lived (`turn_credential_ttl_seconds`)
/// per-request username/credential pairs (the standard "TURN REST API"
/// time-limited credential mechanism coturn implements via
/// `use-auth-secret`/`static-auth-secret`).
#[derive(Debug, Clone)]
pub struct TurnConfig {
    pub stun_urls: Vec<String>,
    pub turn_urls: Vec<String>,
    pub shared_secret: String,
    pub credential_ttl_seconds: i64,
}

/// S3-compatible object storage settings for encrypted attachment blobs.
/// Generic on purpose (`endpoint_url` + path-style bucket) so production can
/// point at any real S3-compatible provider, not just local MinIO.
#[derive(Debug, Clone)]
pub struct S3Config {
    /// Absolute request-body deadline enforced at the only public S3 ingress.
    /// Zero disables delegated uploads instead of assuming an in-flight bound.
    pub max_upload_duration_seconds: i64,
    /// A write-only principal for delegated client PUTs. Its IAM policy must
    /// explicitly deny GetObject/GetObjectVersion on every source bucket.
    pub upload_access_key_id: String,
    pub upload_secret_access_key: String,
    pub endpoint_url: String,
    pub public_endpoint_url: String,
    pub region: String,
    pub bucket: String,
    pub access_key_id: String,
    pub secret_access_key: String,
    pub presign_ttl_seconds: i64,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let mode = env::var("COCO_ENV").unwrap_or_else(|_| {
            if cfg!(debug_assertions) { "development" } else { "production" }.to_string()
        });
        validate_environment(&mode, |key| env::var(key).ok())?;
        crate::conferences::config::ConferenceConfig::from_env()?;
        Ok(Self {
            host: env::var("SERVER_HOST").unwrap_or_else(|_| "0.0.0.0".to_string()),
            port: env::var("SERVER_PORT")
                .unwrap_or_else(|_| "8080".to_string())
                .parse()?,
            database_url: env::var("DATABASE_URL")?,
            database_max_connections: env::var("DATABASE_MAX_CONNECTIONS")
                .unwrap_or_else(|_| "10".to_string())
                .parse()?,
            redis_url: env::var("REDIS_URL")?,
            // Never hardcoded: the server refuses to start without a real secret.
            jwt_secret: env::var("JWT_SECRET")?,
            trusted_proxies: parse_trusted_proxies(env::var("TRUSTED_PROXIES").ok())?,
            jwt_issuer: env::var("JWT_ISSUER").unwrap_or_else(|_| "messenger-server".to_string()),
            jwt_audience: env::var("JWT_AUDIENCE").unwrap_or_else(|_| "messenger-clients".to_string()),
            access_token_ttl_seconds: env::var("ACCESS_TOKEN_TTL_SECONDS")
                .unwrap_or_else(|_| "900".to_string())
                .parse()?,
            refresh_token_ttl_seconds: env::var("REFRESH_TOKEN_TTL_SECONDS")
                .unwrap_or_else(|_| "2592000".to_string())
                .parse()?,
            s3: S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS").ok().and_then(|v|v.parse().ok()).unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(), upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),

                // Server-side calls (presign signing + the /complete HEAD
                // check) use the in-network endpoint; the URL handed back
                // to Android must use the host-reachable one - these differ
                // in local dev (container name vs 10.0.2.2) but are the same
                // real endpoint in production.
                endpoint_url: env::var("S3_ENDPOINT_URL").unwrap_or_else(|_| "http://localhost:9000".to_string()),
                public_endpoint_url: env::var("S3_PUBLIC_ENDPOINT_URL")
                    .unwrap_or_else(|_| "http://10.0.2.2:9000".to_string()),
                region: env::var("S3_REGION").unwrap_or_else(|_| "us-east-1".to_string()),
                bucket: env::var("S3_BUCKET").unwrap_or_else(|_| "messenger-attachments".to_string()),
                access_key_id: env::var("S3_ACCESS_KEY_ID").unwrap_or_else(|_| "messenger_minio".to_string()),
                secret_access_key: env::var("S3_SECRET_ACCESS_KEY")
                    .unwrap_or_else(|_| "messenger_minio_secret".to_string()),
                presign_ttl_seconds: env::var("S3_PRESIGN_TTL_SECONDS")
                    .unwrap_or_else(|_| "600".to_string())
                    .parse()?,
            },
            turn: TurnConfig {
                stun_urls: env::var("TURN_STUN_URLS")
                    .unwrap_or_else(|_| "stun:10.0.2.2:3478".to_string())
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect(),
                turn_urls: env::var("TURN_TURN_URLS")
                    .unwrap_or_else(|_| "turn:10.0.2.2:3478?transport=udp,turn:10.0.2.2:3478?transport=tcp".to_string())
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect(),
                // Never a real production secret by default - must be overridden
                // via env to match the coturn `static-auth-secret` in use.
                shared_secret: env::var("TURN_SHARED_SECRET")
                    .unwrap_or_else(|_| "dev_turn_shared_secret_change_me".to_string()),
                credential_ttl_seconds: env::var("TURN_CREDENTIAL_TTL_SECONDS")
                    .unwrap_or_else(|_| "3600".to_string())
                    .parse()?,
            },
            fcm: FcmConfig {
                project_id: env::var("FCM_PROJECT_ID").ok(),
                // Prefer an explicit file; fall back to the standard ADC env var
                // (GOOGLE_APPLICATION_CREDENTIALS) which is also how ADC is
                // supplied outside GCP. The credential file must never be
                // committed or logged (see .gitignore protection).
                service_account_file: env::var("FCM_SERVICE_ACCOUNT_FILE")
                    .ok()
                    .or_else(|| env::var("GOOGLE_APPLICATION_CREDENTIALS").ok()),
            },
        })
    }
}

fn parse_trusted_proxies(value: Option<String>) -> anyhow::Result<Vec<IpNet>> {
    let Some(value) = value else { return Ok(Vec::new()); };
    if value.trim().is_empty() { return Ok(Vec::new()); }
    value
        .split(',')
        .map(|entry| {
            let entry = entry.trim();
            IpNet::parse(entry).ok_or_else(|| anyhow::anyhow!("TRUSTED_PROXIES contains an invalid CIDR: {entry}"))
        })
        .collect()
}

// Validate before opening databases or running migrations. Never include values
// in diagnostics: configuration may contain credentials.
fn validate_environment(mode: &str, get: impl Fn(&str) -> Option<String>) -> anyhow::Result<()> {
    anyhow::ensure!(matches!(mode, "development" | "production"), "COCO_ENV must be development or production");
    if mode == "development" { return Ok(()); }
    for key in ["JWT_SECRET", "S3_ACCESS_KEY_ID", "S3_SECRET_ACCESS_KEY", "S3_UPLOAD_ACCESS_KEY_ID", "S3_UPLOAD_SECRET_ACCESS_KEY", "S3_ENDPOINT_URL", "S3_PUBLIC_ENDPOINT_URL", "TURN_SHARED_SECRET", "TURN_TURN_URLS"] {
        let value = get(key).unwrap_or_default();
        anyhow::ensure!(!value.trim().is_empty(), "{key} is required in production");
        anyhow::ensure!(!matches!(value.as_str(), "messenger_minio" | "messenger_minio_secret" | "dev_turn_shared_secret_change_me" | "change-me-to-a-long-random-value-in-production"), "{key} must not use a development default in production");
        if matches!(key, "JWT_SECRET" | "TURN_SHARED_SECRET") {
            anyhow::ensure!(value.trim().len() >= 32, "{key} must contain at least 32 bytes in production");
        }
        if key == "S3_PUBLIC_ENDPOINT_URL" {
            let url = reqwest::Url::parse(&value).map_err(|_| anyhow::anyhow!("S3_PUBLIC_ENDPOINT_URL is invalid"))?;
            anyhow::ensure!(url.scheme() == "https" && url.host_str().is_some() && url.username().is_empty() && url.password().is_none() && url.query().is_none() && url.fragment().is_none(), "S3_PUBLIC_ENDPOINT_URL must be an HTTPS endpoint without credentials, query or fragment");
        }
    }
    anyhow::ensure!(get("S3_UPLOAD_ACCESS_KEY_ID") != get("S3_ACCESS_KEY_ID"), "S3 client uploads require a separate write-only principal");
    anyhow::ensure!(get("S3_MAX_UPLOAD_DURATION_SECONDS").and_then(|s|s.parse::<i64>().ok()).is_some_and(|n|(1..=7200).contains(&n)), "S3_MAX_UPLOAD_DURATION_SECONDS must match an enforced ingress deadline between 1 and 7200 seconds");
    Ok(())
}

#[cfg(test)]
mod environment_tests {
    use super::*;
    fn valid(key: &str) -> Option<String> {
        Some(match key {
            "S3_MAX_UPLOAD_DURATION_SECONDS" => "1800",
            "S3_UPLOAD_ACCESS_KEY_ID" => "fixture-write-only-access-key",
            "S3_PUBLIC_ENDPOINT_URL" => "https://files.example.com",
            "S3_ENDPOINT_URL" => "http://minio:9000",
            "TURN_TURN_URLS" => "turn:relay.example.com:3478",
            _ => "a-random-operator-supplied-secret-of-sufficient-length",
        }.to_string())
    }
    #[test]
    fn production_requires_explicit_settings() {
        assert!(validate_environment("production", valid).is_ok());
        for missing in ["JWT_SECRET", "S3_ACCESS_KEY_ID", "S3_SECRET_ACCESS_KEY", "S3_UPLOAD_ACCESS_KEY_ID", "S3_UPLOAD_SECRET_ACCESS_KEY", "S3_ENDPOINT_URL", "S3_PUBLIC_ENDPOINT_URL", "TURN_SHARED_SECRET", "TURN_TURN_URLS"] {
            assert!(validate_environment("production", |k| if k == missing { None } else { valid(k) }).is_err());
        }
    }
    #[test]
    fn rejects_defaults_short_secrets_and_cleartext_public_storage() {
        for (key, value) in [("JWT_SECRET", "change-me-to-a-long-random-value-in-production"), ("JWT_SECRET", "short"), ("TURN_SHARED_SECRET", "dev_turn_shared_secret_change_me"), ("S3_ACCESS_KEY_ID", "messenger_minio"), ("S3_SECRET_ACCESS_KEY", "messenger_minio_secret"), ("S3_PUBLIC_ENDPOINT_URL", "http://files.example.com")] {
            assert!(validate_environment("production", |k| if k == key { Some(value.into()) } else { valid(k) }).is_err());
        }
    }
    #[test]
    fn development_stays_compatible_and_typos_fail() {
        assert!(validate_environment("development", |_| None).is_ok());
        assert!(validate_environment("prodution", valid).is_err());
    }
    #[test]
    fn client_upload_policy_explicitly_denies_all_copy_sources() {
        let policy:serde_json::Value=serde_json::from_str(include_str!("../storage-client-uploads-policy.template.json")).unwrap();
        let rules=policy["Statement"].as_array().unwrap();
        assert!(rules.iter().any(|r|r["Effect"]=="Allow" && r["Action"]==serde_json::json!(["s3:PutObject"]) && r["Resource"]==serde_json::json!(["arn:aws:s3:::__COCO_BUCKET__/*"])));
        assert!(rules.iter().any(|r|r["Effect"]=="Deny" && r["Action"]==serde_json::json!(["s3:GetObject","s3:GetObjectVersion"]) && r["Resource"]==serde_json::json!(["arn:aws:s3:::*/*"])));
        assert!(validate_environment("production",|key| if key=="S3_UPLOAD_ACCESS_KEY_ID" {valid("S3_ACCESS_KEY_ID")} else {valid(key)}).is_err());
    }
}
